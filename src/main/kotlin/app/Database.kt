package app

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

class Database(val path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use {
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA journal_mode=WAL")
        }
        autoCommit = true
    }

    init { schema() }

    private fun schema() = conn.createStatement().use { st ->
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS sentences(
              id INTEGER PRIMARY KEY, key TEXT UNIQUE NOT NULL, text TEXT NOT NULL, created_at TEXT NOT NULL)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS versions(
              id INTEGER PRIMARY KEY, sentence_id INTEGER NOT NULL REFERENCES sentences(id),
              side TEXT NOT NULL, label TEXT NOT NULL, parent_merge_id INTEGER,
              status TEXT NOT NULL, violations TEXT NOT NULL DEFAULT '[]',
              created_at TEXT NOT NULL, created_by TEXT)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS tokens(
              id INTEGER PRIMARY KEY, version_id INTEGER NOT NULL REFERENCES versions(id),
              ord INTEGER NOT NULL, surface TEXT NOT NULL, pos TEXT NOT NULL,
              empty INTEGER NOT NULL DEFAULT 0, lemma TEXT, origin_group TEXT)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS arcs(
              id INTEGER PRIMARY KEY, version_id INTEGER NOT NULL REFERENCES versions(id),
              dep_id INTEGER NOT NULL REFERENCES tokens(id), head_id INTEGER NOT NULL REFERENCES tokens(id),
              relation TEXT NOT NULL, provenance TEXT)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS spans(
              id INTEGER PRIMARY KEY, version_id INTEGER NOT NULL REFERENCES versions(id),
              label TEXT NOT NULL, seg_first INTEGER NOT NULL, seg_last INTEGER NOT NULL,
              seg_idx INTEGER NOT NULL, provenance TEXT)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS genealogy(
              id INTEGER PRIMARY KEY, sentence_id INTEGER NOT NULL REFERENCES sentences(id),
              track TEXT NOT NULL, version_id INTEGER NOT NULL REFERENCES versions(id),
              token_id INTEGER NOT NULL REFERENCES tokens(id))""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS align_groups(
              id INTEGER PRIMARY KEY, sentence_id INTEGER NOT NULL REFERENCES sentences(id),
              gkey TEXT NOT NULL UNIQUE, label TEXT NOT NULL, basis_rev INTEGER NOT NULL DEFAULT 1, note TEXT)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS align_members(
              id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL REFERENCES align_groups(id),
              side TEXT NOT NULL, token_id INTEGER NOT NULL REFERENCES tokens(id))""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS decisions(
              id INTEGER PRIMARY KEY, sentence_id INTEGER NOT NULL REFERENCES sentences(id),
              kind TEXT NOT NULL, conflict_key TEXT NOT NULL, operator TEXT NOT NULL,
              choice TEXT NOT NULL, side TEXT, group_id INTEGER, basis_rev INTEGER NOT NULL,
              status TEXT NOT NULL, detail TEXT, supersedes_id INTEGER, created_at TEXT NOT NULL)""".trimIndent()
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS merges(
              id INTEGER PRIMARY KEY, sentence_id INTEGER NOT NULL REFERENCES sentences(id),
              version_a INTEGER NOT NULL REFERENCES versions(id),
              version_b INTEGER NOT NULL REFERENCES versions(id),
              merged_version_id INTEGER NOT NULL REFERENCES versions(id),
              operator TEXT NOT NULL, basis_snapshot TEXT NOT NULL, created_at TEXT NOT NULL)""".trimIndent()
        )
    }

    @Synchronized fun now(): String = Instant.now().toString()

    @Synchronized
    fun reset() {
        conn.createStatement().use { st ->
            listOf("merges", "decisions", "align_members", "align_groups", "genealogy",
                   "spans", "arcs", "tokens", "versions", "sentences").forEach {
                st.executeUpdate("DELETE FROM $it")
            }
        }
    }

    @Synchronized
    fun isEmpty(): Boolean =
        conn.createStatement().executeQuery("SELECT COUNT(*) FROM sentences").use { it.next() && it.getInt(1) == 0 }

    private fun <T> ResultSet.map(fn: ResultSet.() -> T): List<T> {
        val out = mutableListOf<T>()
        while (next()) out.add(fn())
        return out
    }

    @Synchronized fun listSentences(): List<Sentence> =
        conn.createStatement().executeQuery("SELECT id,key,text,created_at FROM sentences ORDER BY id").map {
            Sentence(getLong(1), getString(2), getString(3), getString(4))
        }

    @Synchronized
    fun sentenceByKey(key: String): Sentence? =
        conn.prepareStatement("SELECT id,key,text,created_at FROM sentences WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) Sentence(it.getLong(1), it.getString(2), it.getString(3), it.getString(4)) else null }
        }

    @Synchronized
    fun versionsOf(sentenceId: Long): List<Version> {
        val vs = conn.prepareStatement(
            "SELECT id,sentence_id,side,label,parent_merge_id,status,violations,created_at,created_by FROM versions WHERE sentence_id=? ORDER BY id"
        ).use { ps ->
            ps.setLong(1, sentenceId)
            ps.executeQuery().map {
                Version(
                    id = getLong(1), sentenceId = getLong(2),
                    side = VersionSide.valueOf(getString(3)), label = getString(4),
                    parentMergeId = getObject(5)?.let { (it as Number).toLong() },
                    status = VersionStatus.valueOf(getString(6)),
                    violations = JsonIO.stringList(getString(7)),
                    createdAt = getString(8), createdBy = getString(9)
                )
            }
        }
        return vs.map { v ->
            v.copy(tokens = tokensOf(v.id), arcs = arcsOf(v.id), spans = spansOf(v.id))
        }
    }

    @Synchronized
    fun version(id: Long): Version? {
        conn.prepareStatement(
            "SELECT id,sentence_id,side,label,parent_merge_id,status,violations,created_at,created_by FROM versions WHERE id=?"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val v = Version(
                    id = rs.getLong(1), sentenceId = rs.getLong(2),
                    side = VersionSide.valueOf(rs.getString(3)), label = rs.getString(4),
                    parentMergeId = rs.getObject(5)?.let { (it as Number).toLong() },
                    status = VersionStatus.valueOf(rs.getString(6)),
                    violations = JsonIO.stringList(rs.getString(7)),
                    createdAt = rs.getString(8), createdBy = rs.getString(9)
                )
                return v.copy(tokens = tokensOf(v.id), arcs = arcsOf(v.id), spans = spansOf(v.id))
            }
        }
    }

    private fun tokensOf(versionId: Long): List<Token> =
        conn.prepareStatement(
            "SELECT id,version_id,ord,surface,pos,empty,lemma,origin_group FROM tokens WHERE version_id=? ORDER BY ord"
        ).use { ps ->
            ps.setLong(1, versionId)
            ps.executeQuery().map {
                Token(getLong(1), getLong(2), getInt(3), getString(4), getString(5),
                      getInt(6) == 1, getString(7), getString(8))
            }
        }

    private fun arcsOf(versionId: Long): List<Arc> =
        conn.prepareStatement("SELECT id,version_id,dep_id,head_id,relation,provenance FROM arcs WHERE version_id=?").use { ps ->
            ps.setLong(1, versionId)
            ps.executeQuery().map {
                Arc(getLong(1), getLong(2), getLong(3), getLong(4), getString(5), getString(6))
            }
        }

    private fun spansOf(versionId: Long): List<Span> =
        conn.prepareStatement(
            "SELECT label,seg_first,seg_last,provenance, seg_idx, id FROM spans WHERE version_id=? ORDER BY id,seg_idx"
        ).use { ps ->
            ps.setLong(1, versionId)
            ps.executeQuery().map {
                Triple(Pair(getString(1), getString(4)), IntRange(getInt(2), getInt(3)), getInt(5))
            }.groupBy { it.first }.map { (meta, rows) ->
                Span(label = meta.first, segments = rows.sortedBy { it.third }.map { it.second }, provenance = meta.second)
            }
        }

    @Synchronized
    fun genealogyOf(sentenceId: Long): List<GenealogyLink> =
        conn.prepareStatement("SELECT id,sentence_id,track,version_id,token_id FROM genealogy WHERE sentence_id=?").use { ps ->
            ps.setLong(1, sentenceId)
            ps.executeQuery().map { GenealogyLink(getLong(1), getLong(2), getString(3), getLong(4), getLong(5)) }
        }

    @Synchronized
    fun groupsOf(sentenceId: Long): List<AlignmentGroup> =
        conn.prepareStatement("SELECT id,sentence_id,gkey,label,basis_rev,note FROM align_groups WHERE sentence_id=? ORDER BY gkey").use { ps ->
            ps.setLong(1, sentenceId)
            ps.executeQuery().map {
                AlignmentGroup(getLong(1), getLong(2), getString(3), getString(4), getLong(5), getString(6))
            }
        }

    @Synchronized
    fun membersOf(groupId: Long): List<AlignmentMember> =
        conn.prepareStatement("SELECT id,group_id,side,token_id FROM align_members WHERE group_id=? ORDER BY id").use { ps ->
            ps.setLong(1, groupId)
            ps.executeQuery().map { AlignmentMember(getLong(1), getLong(2), VersionSide.valueOf(getString(3)), getLong(4)) }
        }

    @Synchronized
    fun allMembers(sentenceId: Long): Map<Long, List<AlignmentMember>> {
        val gs = groupsOf(sentenceId)
        return gs.associate { it.id to membersOf(it.id) }
    }

    @Synchronized
    fun decisionsOf(sentenceId: Long): List<Decision> =
        conn.prepareStatement(
            """SELECT id,sentence_id,kind,conflict_key,operator,choice,side,group_id,basis_rev,status,detail,supersedes_id,created_at
              FROM decisions WHERE sentence_id=? ORDER BY id""".trimMargin()
        ).use { ps ->
            ps.setLong(1, sentenceId)
            ps.executeQuery().map(readDecision)
        }

    fun ResultSet.readDecision(): Decision = Decision(
        id = getLong(1), sentenceId = getLong(2), kind = DecisionKind.valueOf(getString(3)),
        conflictKey = getString(4), operator = getString(5), choice = getString(6),
        side = getString(7)?.let { VersionSide.valueOf(it) },
        groupId = getObject(8)?.let { (it as Number).toLong() },
        basisRev = getLong(9), status = DecisionStatus.valueOf(getString(10)),
        detail = getString(11),
        supersedesId = getObject(12)?.let { (it as Number).toLong() },
        createdAt = getString(13)
    )

    @Synchronized
    fun decision(id: Long): Decision? =
        conn.prepareStatement(
            """SELECT id,sentence_id,kind,conflict_key,operator,choice,side,group_id,basis_rev,status,detail,supersedes_id,created_at
              FROM decisions WHERE id=?""".trimMargin()
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { if (it.next()) it.readDecision() else null }
        }

    @Synchronized
    fun mergesOf(sentenceId: Long): List<MergeRecord> =
        conn.prepareStatement(
            "SELECT id,sentence_id,version_a,version_b,merged_version_id,operator,basis_snapshot,created_at FROM merges WHERE sentence_id=? ORDER BY id"
        ).use { ps ->
            ps.setLong(1, sentenceId)
            ps.executeQuery().map {
                MergeRecord(getLong(1), getLong(2), getLong(3), getLong(4), getLong(5), getString(6), getString(7), getString(8))
            }
        }
}
