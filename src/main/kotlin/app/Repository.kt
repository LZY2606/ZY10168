package app

import java.sql.Statement

/** 合并/导入等所有写操作都经过 Repository，保证来源版本永不被覆盖。 */
class Repository(val db: Database) {

    private fun insertVersion(sid: Long, v: VersionDto, parentMergeId: Long?): Long {
        val id = db.conn.prepareStatement(
            """INSERT INTO versions(sentence_id,side,label,parent_merge_id,status,violations,created_at,created_by)
               VALUES(?,?,?,?,?,?,?,?)""".trimMargin(),
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setLong(1, sid)
            ps.setString(2, v.side.name)
            ps.setString(3, v.label)
            if (parentMergeId == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setLong(4, parentMergeId)
            ps.setString(5, (v.status ?: VersionStatus.VALID).name)
            ps.setString(6, JsonIO.write(v.violations))
            ps.setString(7, db.now())
            ps.setString(8, v.createdBy)
            ps.executeUpdate()
            ps.generatedKeys.use { it.next(); it.getLong(1) }
        }
        val ordToId = HashMap<Int, Long>()
        v.tokens.forEach { t ->
            val tid = db.conn.prepareStatement(
                "INSERT INTO tokens(version_id,ord,surface,pos,empty,lemma,origin_group) VALUES(?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setLong(1, id); ps.setInt(2, t.ord); ps.setString(3, t.surface); ps.setString(4, t.pos)
                ps.setInt(5, if (t.empty) 1 else 0); ps.setString(6, t.lemma); ps.setString(7, t.originGroup)
                ps.executeUpdate(); ps.generatedKeys.use { it.next(); it.getLong(1) }
            }
            ordToId[t.ord] = tid
        }
        v.arcs.forEach { a ->
            db.conn.prepareStatement("INSERT INTO arcs(version_id,dep_id,head_id,relation,provenance) VALUES(?,?,?,?,?)").use { ps ->
                ps.setLong(1, id); ps.setLong(2, ordToId.getValue(a.dep)); ps.setLong(3, ordToId.getValue(a.head))
                ps.setString(4, a.relation); ps.setString(5, a.provenance)
                ps.executeUpdate()
            }
        }
        v.spans.forEach { sp ->
            sp.segments.forEachIndexed { idx, seg ->
                db.conn.prepareStatement(
                    "INSERT INTO spans(version_id,label,seg_first,seg_last,seg_idx,provenance) VALUES(?,?,?,?,?,?)"
                ).use { ps ->
                    ps.setLong(1, id); ps.setString(2, sp.label)
                    ps.setInt(3, seg[0]); ps.setInt(4, seg[1]); ps.setInt(5, idx); ps.setString(6, sp.provenance)
                    ps.executeUpdate()
                }
            }
        }
        // 谱系
        v.tokens.forEach { t ->
            t.tracks.forEach { track ->
                db.conn.prepareStatement("INSERT INTO genealogy(sentence_id,track,version_id,token_id) VALUES(?,?,?,?)").use { ps ->
                    ps.setLong(1, sid); ps.setString(2, track); ps.setLong(3, id); ps.setLong(4, ordToId.getValue(t.ord))
                    ps.executeUpdate()
                }
            }
        }
        return id
    }

    /** 导入完整快照（用于固定 fixture 初始化与清空后复核）。默认对来源版本做四项校验。 */
    @Synchronized
    fun importSnapshot(snap: Snapshot, validate: Boolean = true): List<String> {
        val notes = mutableListOf<String>()
        snap.sentences.forEach { sdto ->
            val sid = db.conn.prepareStatement(
                "INSERT INTO sentences(key,text,created_at) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, sdto.key); ps.setString(2, sdto.text); ps.setString(3, db.now())
                ps.executeUpdate(); ps.generatedKeys.use { it.next(); it.getLong(1) }
            }
            val versionIds = HashMap<VersionSide, Long>()
            sdto.versions.forEach { vdto ->
                // 来源版本一律重新跑四项校验，不允许用 import 掩盖断裂结构
                val rebuilt = mutableMapOf<Int, Token>()
                val tokens = vdto.tokens.map { t ->
                    val tk = Token(versionId = 0, ord = t.ord, surface = t.surface, pos = t.pos,
                                   empty = t.empty, lemma = t.lemma, originGroup = t.originGroup)
                    rebuilt[t.ord] = tk; tk
                }.mapIndexed { index, token -> token.copy(id = (index + 1).toLong()) }
                val idMap = tokens.associate { it.ord to it.id }
                val arcs = vdto.arcs.map {
                    Arc(versionId = 0, depId = idMap.getValue(it.dep), headId = idMap.getValue(it.head),
                        relation = it.relation, provenance = it.provenance)
                }
                val spans = vdto.spans.map { Span(versionId = 0, label = it.label,
                    segments = it.segments.map { s -> IntRange(s[0], s[1]) }, provenance = it.provenance) }
                val violations = if (validate) Validator.validate(tokens, arcs, spans) else emptyList()
                val withStatus = vdto.copy(
                    status = if (validate) { if (violations.isEmpty()) VersionStatus.VALID else VersionStatus.INVALID } else vdto.status,
                    violations = if (validate) violations else vdto.violations
                )
                val vid = insertVersion(sid, withStatus, vdto.parentMergeId)
                versionIds[vdto.side] = vid
                if (violations.isNotEmpty()) notes += "${sdto.key}/${vdto.label}: ${violations.joinToString(";")}"
            }

            if (sdto.groups.isEmpty()) {
                generateGroupsFromGenealogy(sid)
            } else {
                sdto.groups.forEach { gdto ->
                    val gid = insertGroup(sid, gdto.gkey, gdto.label, gdto.basisRev, gdto.note)
                    gdto.members.forEach { m ->
                        val vid = versionIds.getValue(m.side)
                        val tokenId = db.conn.prepareStatement("SELECT id FROM tokens WHERE version_id=? AND ord=?").use { ps ->
                            ps.setLong(1, vid); ps.setInt(2, m.tokenOrd)
                            ps.executeQuery().use { if (it.next()) it.getLong(1) else error("成员 token ${m.side}#${m.tokenOrd} 不存在") }
                        }
                        addMemberRow(gid, m.side, tokenId)
                    }
                }
            }

            sdto.decisions.forEach { d ->
                db.conn.prepareStatement(
                    """INSERT INTO decisions(sentence_id,kind,conflict_key,operator,choice,side,group_id,basis_rev,status,detail,supersedes_id,created_at)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()
                ).use { ps ->
                    ps.setLong(1, sid); ps.setString(2, d.kind.name); ps.setString(3, d.conflictKey)
                    ps.setString(4, d.operator); ps.setString(5, d.choice)
                    ps.setString(6, d.side?.name); ps.setNull(7, java.sql.Types.INTEGER)
                    ps.setLong(8, d.basisRev); ps.setString(9, d.status.name); ps.setString(10, d.detail)
                    if (d.supersedesId == null) ps.setNull(11, java.sql.Types.INTEGER) else ps.setLong(11, d.supersedesId)
                    ps.setString(12, d.createdAt)
                    ps.executeUpdate()
                }
            }
            sdto.merges.forEach { m ->
                // 快照中的 merge 记录：merged 版本已在 versions 列表里，这里只补关联记录
                val mergedId = db.conn.prepareStatement("SELECT id FROM versions WHERE sentence_id=? AND side='M' ORDER BY id LIMIT 1").use { ps ->
                    ps.setLong(1, sid)
                    ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
                }
                db.conn.prepareStatement(
                    "INSERT INTO merges(sentence_id,version_a,version_b,merged_version_id,operator,basis_snapshot,created_at) VALUES(?,?,?,?,?,?,?)"
                ).use { ps ->
                    ps.setLong(1, sid)
                    ps.setLong(2, versionIds[m.sideA] ?: error("merge 引用缺失 A 版本"))
                    ps.setLong(3, versionIds[m.sideB] ?: error("merge 引用缺失 B 版本"))
                    ps.setLong(4, mergedId ?: error("merge 引用缺失 M 版本"))
                    ps.setString(5, m.operator); ps.setString(6, m.basisSnapshot); ps.setString(7, m.createdAt)
                    ps.executeUpdate()
                }
            }
        }
        return notes
    }

    @Synchronized
    fun insertGroup(sid: Long, gkey: String, label: String, basisRev: Long = 1, note: String? = null): Long =
        db.conn.prepareStatement(
            "INSERT INTO align_groups(sentence_id,gkey,label,basis_rev,note) VALUES(?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setLong(1, sid); ps.setString(2, gkey); ps.setString(3, label); ps.setLong(4, basisRev); ps.setString(5, note)
            ps.executeUpdate(); ps.generatedKeys.use { it.next(); it.getLong(1) }
        }

    @Synchronized
    fun addMemberRow(groupId: Long, side: VersionSide, tokenId: Long) {
        db.conn.prepareStatement("INSERT INTO align_members(group_id,side,token_id) VALUES(?,?,?)").use { ps ->
            ps.setLong(1, groupId); ps.setString(2, side.name); ps.setLong(3, tokenId); ps.executeUpdate()
        }
    }

    /** 依据 token 谱系的并查集自动生成 A/B 对齐组；合词/拆词形成多成员组。 */
    @Synchronized
    fun generateGroupsFromGenealogy(sid: Long): Int {
        val versions = db.versionsOf(sid)
        val va = versions.firstOrNull { it.side == VersionSide.A } ?: return 0
        val vb = versions.firstOrNull { it.side == VersionSide.B } ?: return 0
        val links = db.genealogyOf(sid).filter { it.versionId == va.id || it.versionId == vb.id }

        data class MT(val side: VersionSide, val tokenId: Long)
        val parent = HashMap<MT, MT>()
        fun find(x: MT): MT { parent.putIfAbsent(x, x); var r = x; while (parent[r] != r) r = parent[r]!!; return r }
        fun union(a: MT, b: MT) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }

        links.groupBy { it.track }.forEach { (_, ls) ->
            val ms = ls.map { MT(if (it.versionId == va.id) VersionSide.A else VersionSide.B, it.tokenId) }
            ms.forEach { find(it) }
            for (i in 1 until ms.size) union(ms[0], ms[i])
        }
        val clusters = links.flatMap { l ->
            listOf(MT(if (l.versionId == va.id) VersionSide.A else VersionSide.B, l.tokenId))
        }.distinct().groupBy { find(it) }

        val tokenById = (va.tokens + vb.tokens).associateBy { it.id }
        var n = 0
        clusters.values.sortedBy { cl -> cl.minOf { (tokenById[it.tokenId]?.ord ?: 0) } }.forEachIndexed { idx, cl ->
            val gkey = "G%02d".format(idx + 1)
            val surfaces = cl.mapNotNull { tokenById[it.tokenId]?.surface }.distinct().joinToString("/")
            val ambiguous = cl.groupingBy { it.side }.eachCount().values.any { it > 1 }
            val gid = insertGroup(
                sid, gkey, gkey + " " + surfaces, 1,
                if (ambiguous) "谱系显示拆/合词：对应不确定，需人工确认" else null
            )
            cl.sortedWith(compareBy({ it.side.name }, { tokenById[it.tokenId]?.ord })).forEach { addMemberRow(gid, it.side, it.tokenId) }
            n++
        }
        return n
    }

    @Synchronized
    fun exportSnapshot(): Snapshot {
        val sentences = db.listSentences().map { s ->
            val versions = db.versionsOf(s.id)
            val vOrdId = versions.associate { v ->
                v.id to v.tokens.associate { it.id to it.ord }
            }
            val vDto = versions.map { v ->
                VersionDto(
                    side = v.side, label = v.label, status = v.status, violations = v.violations,
                    createdBy = v.createdBy, parentMergeId = v.parentMergeId,
                    tokens = v.tokens.map { t ->
                        TokenDto(ord = t.ord, surface = t.surface, pos = t.pos, empty = t.empty,
                                 lemma = t.lemma, originGroup = t.originGroup,
                                 tracks = db.genealogyOf(s.id).filter { it.tokenId == t.id }.map { it.track })
                    },
                    arcs = v.arcs.map { ArcDto(vOrdId.getValue(v.id).getValue(it.depId),
                                               vOrdId.getValue(v.id).getValue(it.headId), it.relation, it.provenance) },
                    spans = v.spans.map { SpanDto(it.label, it.segments.map { sg -> listOf(sg.first, sg.last) }, it.provenance) }
                )
            }
            val groups = db.groupsOf(s.id).map { g ->
                val members = db.membersOf(g.id).map { m ->
                    MemberDto(m.side, vOrdId.getValue(m.tokenId.let { tid ->
                        db.conn.prepareStatement("SELECT version_id FROM tokens WHERE id=?").use { ps ->
                            ps.setLong(1, tid); ps.executeQuery().use { it.next(); it.getLong(1) }
                        }
                    }).getValue(tid))
                }
                GroupDto(g.gkey, g.label, g.basisRev, g.note, members)
            }
            val decisions = db.decisionsOf(s.id).map { d ->
                DecisionDto(d.id, d.kind, d.conflictKey, d.operator, d.choice, d.side, null,
                            d.basisRev, d.status, d.detail, d.supersedesId, d.createdAt)
            }
            val merges = db.mergesOf(s.id).map { m ->
                val sa = db.version(m.versionA)!!.side; val sb = db.version(m.versionB)!!.side
                MergeDto(m.id, sa, sb, m.operator, m.basisSnapshot, m.createdAt)
            }
            SentenceDto(s.key, s.text, vDto, emptyList(), groups, decisions, merges)
        }
        return Snapshot(exportedAt = db.now(), sentences = sentences)
    }
}
