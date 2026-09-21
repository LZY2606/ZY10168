package app

import java.sql.ResultSet
import java.sql.Types

data class SessionRow(
    val id: String, val sentenceId: String,
    val leftVersionId: String, val rightVersionId: String,
    val basisRevision: Int, val createdAt: String,
)

class Repository(val db: Database) {
    private fun <T> query(sql: String, vararg args: Any?, body: (ResultSet) -> T): List<T> {
        db.conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v ->
                if (v == null) ps.setNull(i + 1, Types.VARCHAR) else ps.setObject(i + 1, v)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += body(rs)
                return out
            }
        }
    }

    fun sentences() = query("SELECT id, surface, note FROM sentence ORDER BY id") {
        Triple(it.getString(1), it.getString(2), it.getString(3))
    }

    fun versions(sentenceId: String) =
        query("SELECT id, sentence_id, label, kind, parent_a, parent_b, merge_run_id, note, created_at " +
            "FROM version WHERE sentence_id=? ORDER BY created_at, id", sentenceId) { rs ->
            Version(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))
        }

    fun allVersions() =
        query("SELECT id, sentence_id, label, kind, parent_a, parent_b, merge_run_id, note, created_at FROM version ORDER BY id") {
            Version(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                it.getString(5), it.getString(6), it.getString(7), it.getString(8), it.getString(9))
        }

    fun analysis(versionId: String): Analysis? {
        val v = query("SELECT id, sentence_id, label, kind, parent_a, parent_b, merge_run_id, note, created_at " +
            "FROM version WHERE id=?", versionId) {
            Version(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
                it.getString(5), it.getString(6), it.getString(7), it.getString(8), it.getString(9))
        }.firstOrNull() ?: return null
        val toks = query("SELECT id, version_id, ord, text, lemma, pos, empty FROM token WHERE version_id=? ORDER BY ord", versionId) {
            Token(it.getString(1), it.getString(2), it.getInt(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7) == 1)
        }
        val deps = query("SELECT id, version_id, head_id, dep_id, relation FROM dep WHERE version_id=?", versionId) {
            Dep(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
        val cons = query("SELECT id, label FROM constituent WHERE version_id=? ORDER BY ord", versionId) {
            it.getString(1) to it.getString(2)
        }.map { (cid, label) ->
            val partCount = query("SELECT COALESCE(MAX(part_index),-1)+1 FROM constituent_part WHERE constituent_id=?", cid) {
                it.getInt(1)
            }.first()
            val grouped = (0 until partCount).map { pi ->
                query("SELECT token_id FROM constituent_part WHERE constituent_id=? AND part_index=? ORDER BY token_index", cid, pi) {
                    it.getString(1)
                }
            }
            Constituent(cid, versionId, label, grouped)
        }
        return Analysis(v, toks, deps, cons)
    }

    fun session(id: String): SessionRow? =
        query("SELECT id, sentence_id, left_version_id, right_version_id, basis_revision, created_at FROM session WHERE id=?", id) {
            SessionRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6))
        }.firstOrNull()

    fun sessions() =
        query("SELECT id, sentence_id, left_version_id, right_version_id, basis_revision, created_at FROM session ORDER BY id") {
            SessionRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6))
        }

    fun links(sessionId: String): List<CorrLink> =
        query("SELECT id, left_token_id, right_token_id, source, operator_id, created_at FROM corr_link WHERE session_id=? ORDER BY id",
            sessionId) {
            CorrLink(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
        }

    fun decisions(sessionId: String): List<Decision> =
        query("""SELECT id, session_id, seq, operator_id, type, ref, choice, payload,
                 item_signature, item_description, basis_revision, created_at
                 FROM decision WHERE session_id=? ORDER BY seq""", sessionId) {
            val idv = it.getObject(1)
            Decision(if (idv == null) null else (idv as Number).toLong(),
                it.getString(2), it.getInt(3), it.getString(4), it.getString(5), it.getString(6),
                it.getString(7), it.getString(8), it.getString(9), it.getString(10), it.getInt(11), it.getString(12))
        }

    fun nextDecisionSeq(sessionId: String): Int =
        query("SELECT COALESCE(MAX(seq),0)+1 FROM decision WHERE session_id=?", sessionId) { it.getInt(1) }.first()

    fun eventLogs(sessionId: String?) = if (sessionId == null)
        query("SELECT id, ts, actor, kind, session_id, detail FROM event_log ORDER BY id DESC LIMIT 500") {
            EventLogRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
        }
    else
        query("SELECT id, ts, actor, kind, session_id, detail FROM event_log WHERE session_id=? ORDER BY id DESC LIMIT 500", sessionId) {
            EventLogRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
        }

    fun runs(sessionId: String) =
        query("SELECT id, session_id, new_version_id, operator_id, basis_revision, created_at FROM merge_run WHERE session_id=? ORDER BY id DESC", sessionId) {
            MergeRun(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6))
        }

    fun unresolved(runId: String) =
        query("SELECT category, item_signature, description, reason FROM merge_unresolved WHERE run_id=?", runId) {
            Merger.Unresolved(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }

    data class TokenProvRow(val newTokenId: String, val sourceVersionId: String, val sourceTokenIds: String,
                            val selectedSide: String, val viaSeq: Long?, val attrSide: String?, val runId: String)
    data class DepProvRow(val newDepId: String, val sourceDepId: String, val sourceVersionId: String,
                          val selectedSide: String, val viaSeq: Long?, val runId: String)
    data class ConProvRow(val newConstituentId: String, val sourceConstituentId: String, val sourceVersionId: String,
                          val selectedSide: String, val viaSeq: Long?, val runId: String)

    fun tokenProv(versionId: String) = query(
        "SELECT new_token_id, source_version_id, source_token_ids, selected_side, via_decision_seq, attr_side, run_id " +
        "FROM token_prov WHERE new_token_id LIKE ? ORDER BY new_token_id", "$versionId%") {
        TokenProvRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
            (it.getObject(5) as? Number)?.toLong(), it.getString(6), it.getString(7))
    }
    fun depProv(versionId: String) = query(
        "SELECT new_dep_id, source_dep_id, source_version_id, selected_side, via_decision_seq, run_id " +
        "FROM dep_prov WHERE new_dep_id LIKE ? ORDER BY new_dep_id", "$versionId%") {
        DepProvRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
            (it.getObject(5) as? Number)?.toLong(), it.getString(6))
    }
    fun conProv(versionId: String) = query(
        "SELECT new_constituent_id, source_constituent_id, source_version_id, selected_side, via_decision_seq, run_id " +
        "FROM constituent_prov WHERE new_constituent_id LIKE ? ORDER BY new_constituent_id", "$versionId%") {
        ConProvRow(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
            (it.getObject(5) as? Number)?.toLong(), it.getString(6))
    }
}
