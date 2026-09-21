package app

object Backup {
    fun export(service: Service): JsonObj {
        val repo = service.repo; val db = service.db
        var root: JsonObj? = null
        db.tx {
            val versions = repo.allVersions().map { v ->
                val a = repo.analysis(v.id)!!
                JsonObj {
                    put("analysis", Views.analysis(a))
                }
            }
            val sentences = repo.sentences().map { (id, surface, note) ->
                JsonObj { put("id", id); put("surface", surface); put("note", note) }
            }
            val sessions = repo.sessions().map { s ->
                JsonObj {
                    put("id", s.id); put("sentenceId", s.sentenceId)
                    put("leftVersionId", s.leftVersionId); put("rightVersionId", s.rightVersionId)
                    put("basisRevision", s.basisRevision); put("createdAt", s.createdAt)
                    put("links", repo.links(s.id).map { Views.link(it) })
                    put("decisions", repo.decisions(s.id).map { Views.decision(it) })
                    put("runs", repo.runs(s.id).map { r ->
                        val tokens = mutableListOf<JsonObj>()
                        service.db.conn.createStatement().use { st ->
                            st.executeQuery("SELECT new_token_id, source_version_id, source_token_ids, selected_side, via_decision_seq, attr_side FROM token_prov WHERE run_id='${r.id}'").use { rs ->
                                while (rs.next()) tokens += JsonObj {
                                    put("newTokenId", rs.getString(1)); put("sourceVersionId", rs.getString(2))
                                    put("sourceTokenIds", rs.getString(3)); put("selectedSide", rs.getString(4))
                                    put("viaDecisionSeq", rs.getObject(5)); put("attrSide", rs.getString(6))
                                }
                            }
                        }
                        val deps = mutableListOf<JsonObj>()
                        service.db.conn.createStatement().use { st ->
                            st.executeQuery("SELECT new_dep_id, source_dep_id, source_version_id, selected_side, via_decision_seq FROM dep_prov WHERE run_id='${r.id}'").use { rs ->
                                while (rs.next()) deps += JsonObj {
                                    put("newDepId", rs.getString(1)); put("sourceDepId", rs.getString(2))
                                    put("sourceVersionId", rs.getString(3)); put("selectedSide", rs.getString(4))
                                    put("viaDecisionSeq", rs.getObject(5))
                                }
                            }
                        }
                        val cons = mutableListOf<JsonObj>()
                        service.db.conn.createStatement().use { st ->
                            st.executeQuery("SELECT new_constituent_id, source_constituent_id, source_version_id, selected_side, via_decision_seq FROM constituent_prov WHERE run_id='${r.id}'").use { rs ->
                                while (rs.next()) cons += JsonObj {
                                    put("newConstituentId", rs.getString(1)); put("sourceConstituentId", rs.getString(2))
                                    put("sourceVersionId", rs.getString(3)); put("selectedSide", rs.getString(4))
                                    put("viaDecisionSeq", rs.getObject(5))
                                }
                            }
                        }
                        JsonObj {
                            put("id", r.id); put("newVersionId", r.newVersionId)
                            put("operatorId", r.operatorId); put("basisRevision", r.basisRevision)
                            put("createdAt", r.createdAt)
                            put("unresolved", repo.unresolved(r.id).map {
                                JsonObj { put("category", it.category); put("itemSignature", it.itemSignature)
                                    put("description", it.description); put("reason", it.reason) }
                            })
                            put("tokenProv", tokens); put("depProv", deps); put("conProv", cons)
                        }
                    })
                }
            }
            val logs = service.repo.eventLogs(null).map { l ->
                JsonObj { put("id", l.id); put("ts", l.ts); put("actor", l.actor); put("kind", l.kind)
                    put("sessionId", l.sessionId); put("detail", l.detail) }
            }
            root = JsonObj {
                put("format", "sentence-track-arbitration/v1"); put("exportedAt", java.time.Instant.now().toString())
                put("sentences", sentences); put("versions", versions)
                put("sessions", sessions); put("eventLogs", logs)
            }
        }
        return root!!
    }

    @Suppress("UNCHECKED_CAST")
    fun restore(service: Service, root: Map<String, Any?>) {
        require(root["format"] == "sentence-track-arbitration/v1") { "未知导出格式" }
        val db = service.db
        db.tx {
            service.writes.wipeAll()
            val ts0 = java.time.Instant.now().toString()
            (root["sentences"] as List<Map<String, Any?>>).forEach {
                service.writes.insertSentence(it["id"] as String, it["surface"] as String, it["note"] as String?)
            }
            (root["versions"] as List<Map<String, Any?>>).forEach { vWrap ->
                val a = readAnalysis((vWrap["analysis"] as? Map<String, Any?>) ?: vWrap)
                service.writes.insertAnalysis(a)
            }
            (root["sessions"] as List<Map<String, Any?>>).forEach { s ->
                val sid = s["id"] as String
                service.writes.insertSession(sid, s["sentenceId"] as String,
                    s["leftVersionId"] as String, s["rightVersionId"] as String, s["createdAt"] as String)
                var rev = 0
                (s["links"] as List<Map<String, Any?>>).forEach { l ->
                    service.writes.addLink(sid, l["leftTokenId"] as String, l["rightTokenId"] as String,
                        (l["source"] as? String) ?: "seed", l["operatorId"] as String?,
                        (l["id"] as? Number)?.toInt()?.let { 0 } ?: 0, l["createdAt"] as String)
                }
                (s["decisions"] as List<Map<String, Any?>>).forEach { d ->
                    service.writes.insertDecision(Decision(
                        null, sid, (d["seq"] as Number).toInt(), d["operatorId"] as String,
                        d["type"] as String, d["ref"] as String, d["choice"] as String,
                        d["payload"] as String?, d["itemSignature"] as String,
                        d["itemDescription"] as String, (d["basisRevision"] as Number).toInt(),
                        d["createdAt"] as String))
                    if ((d["basisRevision"] as Number).toInt() > rev) rev = (d["basisRevision"] as Number).toInt()
                }
                repeat(rev) { service.writes.bumpBasis(sid) }
                val runsList = s["runs"] as List<*>
                runsList.forEach { rRaw ->
                    @Suppress("UNCHECKED_CAST") val r = rRaw as Map<String, Any?>
                    val newId = r["newVersionId"] as String
                    service.db.conn.createStatement().use {
                        it.executeUpdate("""UPDATE version SET merge_run_id='${(r["id"] as String).replace("'", "''")}'
                            WHERE id='${newId.replace("'", "''")}'""")
                    }
                    service.db.conn.prepareStatement("""INSERT INTO merge_run
                        (id, session_id, new_version_id, operator_id, basis_revision, created_at)
                        VALUES(?,?,?,?,?,?)""").use { ps ->
                        ps.setString(1, r["id"] as String); ps.setString(2, sid)
                        ps.setString(3, newId); ps.setString(4, r["operatorId"] as String)
                        ps.setInt(5, (r["basisRevision"] as Number).toInt()); ps.setString(6, r["createdAt"] as String)
                        ps.executeUpdate()
                    }
                    (r["unresolved"] as? List<Map<String, Any?>>)?.forEach { u ->
                        service.db.conn.prepareStatement("""INSERT INTO merge_unresolved
                            (run_id, category, item_signature, description, reason) VALUES(?,?,?,?,?)""").use { ps ->
                            ps.setString(1, r["id"] as String); ps.setString(2, u["category"] as String)
                            ps.setString(3, u["itemSignature"] as String); ps.setString(4, u["description"] as String)
                            ps.setString(5, u["reason"] as String); ps.executeUpdate()
                        }
                    }
                    (r["tokenProv"] as? List<Map<String, Any?>>)?.forEach { p ->
                        service.db.conn.prepareStatement("""INSERT INTO token_prov
                            (new_token_id, source_version_id, source_token_ids, selected_side, via_decision_seq, attr_side, run_id)
                            VALUES(?,?,?,?,?,?,?)""").use { ps ->
                            ps.setString(1, p["newTokenId"] as String); ps.setString(2, p["sourceVersionId"] as String)
                            ps.setString(3, p["sourceTokenIds"] as String); ps.setString(4, p["selectedSide"] as String)
                            ps.setObject(5, p["viaDecisionSeq"]); ps.setString(6, p["attrSide"] as String?)
                            ps.setString(7, r["id"] as String); ps.executeUpdate()
                        }
                    }
                    (r["depProv"] as? List<Map<String, Any?>>)?.forEach { p ->
                        service.db.conn.prepareStatement("""INSERT INTO dep_prov
                            (new_dep_id, source_dep_id, source_version_id, selected_side, via_decision_seq, run_id)
                            VALUES(?,?,?,?,?,?)""").use { ps ->
                            ps.setString(1, p["newDepId"] as String); ps.setString(2, p["sourceDepId"] as String)
                            ps.setString(3, p["sourceVersionId"] as String); ps.setString(4, p["selectedSide"] as String)
                            ps.setObject(5, p["viaDecisionSeq"]); ps.setString(6, r["id"] as String)
                            ps.executeUpdate()
                        }
                    }
                    (r["conProv"] as? List<Map<String, Any?>>)?.forEach { p ->
                        service.db.conn.prepareStatement("""INSERT INTO constituent_prov
                            (new_constituent_id, source_constituent_id, source_version_id, selected_side, via_decision_seq, run_id)
                            VALUES(?,?,?,?,?,?)""").use { ps ->
                            ps.setString(1, p["newConstituentId"] as String); ps.setString(2, p["sourceConstituentId"] as String)
                            ps.setString(3, p["sourceVersionId"] as String); ps.setString(4, p["selectedSide"] as String)
                            ps.setObject(5, p["viaDecisionSeq"]); ps.setString(6, r["id"] as String)
                            ps.executeUpdate()
                        }
                    }
                }
            }
            (root["eventLogs"] as? List<Map<String, Any?>>)?.forEach { l ->
                service.db.conn.prepareStatement("""INSERT INTO event_log(id, ts, actor, kind, session_id, detail)
                    VALUES(?,?,?,?,?,?)""").use { ps ->
                    ps.setLong(1, (l["id"] as Number).toLong()); ps.setString(2, l["ts"] as String)
                    ps.setString(3, l["actor"] as String); ps.setString(4, l["kind"] as String)
                    ps.setString(5, l["sessionId"] as String?); ps.setString(6, l["detail"] as String)
                    ps.executeUpdate()
                }
            }
            service.writes.log("system", "IMPORT", null, "从导出文件恢复数据库并复核", ts0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readAnalysis(root: Map<String, Any?>): Analysis {
        val v = root["version"] as Map<String, Any?>
        val version = Version(v["id"] as String, v["sentenceId"] as String, v["label"] as String,
            v["kind"] as String, v["parentA"] as String?, v["parentB"] as String?,
            v["mergeRunId"] as String?, v["note"] as String?, v["createdAt"] as String)
        val tokens = (root["tokens"] as List<Map<String, Any?>>).map {
            Token(it["id"] as String, it["versionId"] as String, (it["ord"] as Number).toInt(),
                it["text"] as String, it["lemma"] as String, it["pos"] as String, it["empty"] == true)
        }
        val deps = (root["deps"] as List<Map<String, Any?>>).map {
            Dep(it["id"] as String, it["versionId"] as String, it["headId"] as String?,
                it["depId"] as String, it["relation"] as String)
        }
        val cons = (root["constituents"] as List<Map<String, Any?>>).map {
            val parts = (it["parts"] as List<*>).map { part -> (part as List<*>).map { x -> x as String } }
            Constituent(it["id"] as String, it["versionId"] as String, it["label"] as String, parts)
        }
        return Analysis(version, tokens, deps, cons)
    }
}
