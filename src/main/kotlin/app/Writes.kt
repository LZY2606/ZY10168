package app

class Writes(val db: Database) {
    private fun exec(sql: String, vararg args: Any?) {
        db.conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeUpdate()
        }
    }

    fun insertAnalysis(a: Analysis, runId: String? = null) {
        val v = a.version
        exec("INSERT INTO version(id, sentence_id, label, kind, parent_a, parent_b, merge_run_id, note, created_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?)",
            v.id, v.sentenceId, v.label, v.kind, v.parentA, v.parentB, runId ?: v.mergeRunId, v.note, v.createdAt)
        for (t in a.tokens) {
            exec("INSERT INTO token(id, version_id, ord, text, lemma, pos, empty) VALUES(?,?,?,?,?,?,?)",
                t.id, t.versionId, t.ord, t.text, t.lemma, t.pos, if (t.empty) 1 else 0)
        }
        for (d in a.deps) {
            exec("INSERT INTO dep(id, version_id, head_id, dep_id, relation) VALUES(?,?,?,?,?)",
                d.id, d.versionId, d.headId, d.depId, d.relation)
        }
        a.constituents.forEachIndexed { ord, c ->
            exec("INSERT INTO constituent(id, version_id, label, ord) VALUES(?,?,?,?)", c.id, c.versionId, c.label, ord)
            c.parts.forEachIndexed { pi, part ->
                part.forEachIndexed { ti, tok ->
                    exec("INSERT INTO constituent_part(constituent_id, part_index, token_index, token_id) VALUES(?,?,?,?)",
                        c.id, pi, ti, tok)
                }
            }
        }
    }

    fun insertSentence(id: String, surface: String, note: String?) {
        exec("INSERT INTO sentence(id, surface, note) VALUES(?,?,?)", id, surface, note)
    }

    fun insertSession(id: String, sentenceId: String, leftId: String, rightId: String, now: String) {
        exec("INSERT INTO session(id, sentence_id, left_version_id, right_version_id, basis_revision, created_at) " +
            "VALUES(?,?,?,?,0,?)", id, sentenceId, leftId, rightId, now)
    }

    fun addLink(sessionId: String, leftTokenId: String, rightTokenId: String, source: String,
                operatorId: String?, basisRevision: Int, now: String) {
        exec("INSERT INTO corr_link(session_id, left_token_id, right_token_id, source, operator_id, basis_revision, created_at) " +
            "VALUES(?,?,?,?,?,?,?)", sessionId, leftTokenId, rightTokenId, source, operatorId, basisRevision, now)
    }

    fun removeLink(linkId: Long) = exec("DELETE FROM corr_link WHERE id=?", linkId)

    fun bumpBasis(sessionId: String) = exec("UPDATE session SET basis_revision = basis_revision + 1 WHERE id=?", sessionId)

    fun insertDecision(d: Decision): Long {
        val keys = arrayOf("id")
        db.conn.prepareStatement("""INSERT INTO decision
            (session_id, seq, operator_id, type, ref, choice, payload,
             item_signature, item_description, basis_revision, created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)""", keys).use { ps ->
            ps.setString(1, d.sessionId); ps.setInt(2, d.seq); ps.setString(3, d.operatorId)
            ps.setString(4, d.type); ps.setString(5, d.ref); ps.setString(6, d.choice)
            ps.setString(7, d.payload); ps.setString(8, d.itemSignature)
            ps.setString(9, d.itemDescription); ps.setInt(10, d.basisRevision)
            ps.setString(11, d.createdAt)
            ps.executeUpdate()
            ps.generatedKeys.use { if (it.next()) return it.getLong(1) }
        }
        error("no generated key")
    }

    fun log(actor: String, kind: String, sessionId: String?, detail: String, now: String) {
        exec("INSERT INTO event_log(ts, actor, kind, session_id, detail) VALUES(?,?,?,?,?)",
            now, actor, kind, sessionId, detail)
    }

    fun persistMergeRun(runId: String, sessionId: String, newVersionId: String, operatorId: String,
                        basisRevision: Int, now: String, result: Merger.Result) {
        exec("INSERT INTO merge_run(id, session_id, new_version_id, operator_id, basis_revision, created_at) " +
            "VALUES(?,?,?,?,?,?)", runId, sessionId, newVersionId, operatorId, basisRevision, now)
        insertAnalysis(result.analysis.copy(
            version = result.analysis.version.copy(mergeRunId = runId)), runId)
        for (p in result.tokenProvs) exec("""INSERT INTO token_prov
            (new_token_id, source_version_id, source_token_ids, selected_side, via_decision_seq, attr_side, run_id)
            VALUES(?,?,?,?,?,?,?)""",
            p.newTokenId, p.sourceVersionId, p.sourceTokenIds.joinToString(","),
            p.selectedSide, p.viaDecisionSeq, p.attrSide, runId)
        for (p in result.depProvs) exec("""INSERT INTO dep_prov
            (new_dep_id, source_dep_id, source_version_id, selected_side, via_decision_seq, run_id)
            VALUES(?,?,?,?,?,?)""",
            p.newDepId, p.sourceDepId, p.sourceVersionId, p.selectedSide, p.viaDecisionSeq, runId)
        for (p in result.conProvs) exec("""INSERT INTO constituent_prov
            (new_constituent_id, source_constituent_id, source_version_id, selected_side, via_decision_seq, run_id)
            VALUES(?,?,?,?,?,?)""",
            p.newConstituentId, p.sourceConstituentId, p.sourceVersionId, p.selectedSide, p.viaDecisionSeq, runId)
        for (u in result.unresolved) exec("""INSERT INTO merge_unresolved
            (run_id, category, item_signature, description, reason) VALUES(?,?,?,?,?)""",
            runId, u.category, u.itemSignature, u.description, u.reason)
    }

    fun wipeAll() {
        listOf("constituent_prov", "dep_prov", "token_prov", "merge_unresolved", "merge_run",
            "decision", "corr_link", "session", "constituent_part", "constituent", "dep",
            "token", "version", "sentence", "event_log").forEach {
            exec("DELETE FROM $it")
        }
    }
}
