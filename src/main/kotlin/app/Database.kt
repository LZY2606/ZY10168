package app

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

class Database(val path: String) {
    val conn: Connection = run {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:$path")
        c.autoCommit = false
        c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        c.commit()
        c
    }

    fun tx(body: () -> Unit) {
        synchronized(conn) {
            val prev = conn.autoCommit
            try { conn.autoCommit = false; body(); conn.commit() }
            catch (e: Throwable) { conn.rollback(); throw e }
            finally { conn.autoCommit = prev }
        }
    }

    fun init() {
        tx {
            conn.createStatement().use { st ->
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sentence(
                  id TEXT PRIMARY KEY, surface TEXT NOT NULL, note TEXT)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS version(
                  id TEXT PRIMARY KEY, sentence_id TEXT NOT NULL, label TEXT NOT NULL,
                  kind TEXT NOT NULL, parent_a TEXT, parent_b TEXT, merge_run_id TEXT,
                  note TEXT, created_at TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS token(
                  id TEXT PRIMARY KEY, version_id TEXT NOT NULL, ord INTEGER NOT NULL,
                  text TEXT NOT NULL, lemma TEXT NOT NULL, pos TEXT NOT NULL, empty INTEGER NOT NULL DEFAULT 0)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dep(
                  id TEXT PRIMARY KEY, version_id TEXT NOT NULL,
                  head_id TEXT, dep_id TEXT NOT NULL, relation TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS constituent(
                  id TEXT PRIMARY KEY, version_id TEXT NOT NULL, label TEXT NOT NULL,
                  ord INTEGER NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS constituent_part(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  constituent_id TEXT NOT NULL, part_index INTEGER NOT NULL,
                  token_index INTEGER NOT NULL, token_id TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS corr_link(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id TEXT NOT NULL,
                  left_token_id TEXT NOT NULL, right_token_id TEXT NOT NULL,
                  source TEXT NOT NULL, operator_id TEXT,
                  basis_revision INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session(
                  id TEXT PRIMARY KEY, sentence_id TEXT NOT NULL,
                  left_version_id TEXT NOT NULL, right_version_id TEXT NOT NULL,
                  basis_revision INTEGER NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decision(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id TEXT NOT NULL, seq INTEGER NOT NULL,
                  operator_id TEXT NOT NULL, type TEXT NOT NULL, ref TEXT NOT NULL,
                  choice TEXT NOT NULL, payload TEXT,
                  item_signature TEXT NOT NULL, item_description TEXT NOT NULL,
                  basis_revision INTEGER NOT NULL, created_at TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS merge_run(
                  id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
                  new_version_id TEXT NOT NULL, operator_id TEXT NOT NULL,
                  basis_revision INTEGER NOT NULL, created_at TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS merge_unresolved(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL,
                  category TEXT NOT NULL, item_signature TEXT NOT NULL,
                  description TEXT NOT NULL, reason TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS token_prov(
                  new_token_id TEXT PRIMARY KEY, source_version_id TEXT NOT NULL,
                  source_token_ids TEXT NOT NULL, selected_side TEXT NOT NULL,
                  via_decision_seq INTEGER, attr_side TEXT, run_id TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dep_prov(
                  new_dep_id TEXT PRIMARY KEY, source_dep_id TEXT NOT NULL,
                  source_version_id TEXT NOT NULL, selected_side TEXT NOT NULL,
                  via_decision_seq INTEGER, run_id TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS constituent_prov(
                  new_constituent_id TEXT PRIMARY KEY, source_constituent_id TEXT NOT NULL,
                  source_version_id TEXT NOT NULL, selected_side TEXT NOT NULL,
                  via_decision_seq INTEGER, run_id TEXT NOT NULL)""".trimIndent())
                st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS event_log(
                  id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL,
                  actor TEXT NOT NULL, kind TEXT NOT NULL,
                  session_id TEXT, detail TEXT NOT NULL)""".trimIndent())
            }
        }
    }
}
