package app

import java.time.Instant

class Service(val db: Database) {
    val repo = Repository(db)
    val writes = Writes(db)
    private fun now() = Instant.now().toString()

    fun ensureFixture() {
        db.tx {
            if (repo.sentences().isNotEmpty()) return@tx
            writes.insertSentence(Fixture.SENTENCE_ID, Fixture.SURFACE,
                "人造诱发句：含拆词/合词/空节点/交叉依存弧/不连续成分，详见 README")
            Fixture.all().forEach { writes.insertAnalysis(it) }
            val ts = now()
            writes.insertSession("sess-v1-v2", Fixture.SENTENCE_ID, Fixture.V1, Fixture.V2, ts)
            writes.insertSession("sess-v2-v3", Fixture.SENTENCE_ID, Fixture.V2, Fixture.V3, ts)
            Fixture.corrV1V2().forEach { (l, r) ->
                writes.addLink("sess-v1-v2", l, r, "seed", null, 0, ts)
            }
            Fixture.corrV2V3().forEach { (l, r) ->
                writes.addLink("sess-v2-v3", l, r, "seed", null, 0, ts)
            }
            writes.log("system", "SEED", null, "导入固定 fixture：三版短语料 + 两个基线会话", ts)
        }
    }

    class Conflict(val status: Int, val code: String, override val message: String, val body: Any? = null) : RuntimeException(message)

    private fun loadPair(sessionId: String): Pair<SessionRow, Engine.State> {
        val s = repo.session(sessionId) ?: throw Conflict(404, "NOT_FOUND", "会话不存在: $sessionId")
        val left = repo.analysis(s.leftVersionId)!!
        val right = repo.analysis(s.rightVersionId)!!
        val links = repo.links(sessionId)
        val decisions = repo.decisions(sessionId)
        val state = Engine.computeState(left, right, links, decisions, s.basisRevision)
        return s to state
    }

    fun sessionViewJson(id: String): JsonObj {
        val (s, state) = loadPair(id)
        val left = repo.analysis(s.leftVersionId)!!
        val right = repo.analysis(s.rightVersionId)!!
        return Views.sessionView(s, left, right, state, repo.links(id))
    }

    /** 追加一条决定。绝不覆盖；相反决定并存时，旧 seq 进入重放冲突。 */
    fun decide(sessionId: String, operatorId: String, ref: String, choice: String,
               payload: String?, expectedBasis: Int?): Decision {
        var created: Decision? = null
        db.tx {
            val s = repo.session(sessionId) ?: throw Conflict(404, "NOT_FOUND", "会话不存在")
            if (expectedBasis != null && expectedBasis != s.basisRevision) {
                throw Conflict(409, "BASIS_CHANGED",
                    "对齐基础已被另一方修订（本地 r$expectedBasis，当前 r${s.basisRevision}）；旧决定必须先进入重放，而不是覆盖保存")
            }
            val (_, state) = loadPair(sessionId)
            val desc = (state.groupItems.firstOrNull { it.signature == ref }?.description
                ?: state.edgeItems.firstOrNull { it.signature == ref }?.description
                ?: state.constituentItems.firstOrNull { it.signature == ref }?.description
                ?: "（已不在当前对齐基础上的对象）$ref")
            val type = when {
                ref.startsWith("GRP::") ->
                    state.groupItems.firstOrNull { it.signature == ref }?.kind ?: "GRANULARITY"
                ref.startsWith("EDGE::") -> "EDGE"
                ref.startsWith("CON::") -> "CONSTITUENT"
                else -> "UNKNOWN"
            }
            val seq = repo.nextDecisionSeq(sessionId)
            created = Decision(null, sessionId, seq, operatorId, type, ref, choice, payload,
                ref, desc, s.basisRevision, now()).also {
                val id = writes.insertDecision(it)
                writes.log(operatorId, "DECISION", sessionId,
                    "seq=$seq $choice 于「$desc」(basis r${s.basisRevision})", now())
            }
        }
        return created!!
    }

    /** 修订对齐基础：加/删一条谱系链接并推进 basisRevision；旧决定不删除，读取时进入重放冲突。 */
    fun changeBasis(sessionId: String, operatorId: String, action: String,
                   leftTokenId: String?, rightTokenId: String?, linkId: Long?, expectedBasis: Int): Map<String, Any?> {
        lateinit var out: Map<String, Any?>
        db.tx {
            val s = repo.session(sessionId) ?: throw Conflict(404, "NOT_FOUND", "会话不存在")
            if (expectedBasis != s.basisRevision) throw Conflict(409, "BASIS_CHANGED",
                "对齐基础已被另一方修订（本地 r$expectedBasis，当前 r${s.basisRevision}）；请先拉取并重放旧决定")
            val ts = now()
            when (action) {
                "ADD_LINK" -> {
                    require(leftTokenId != null && rightTokenId != null) { "ADD_LINK 需要两个 token" }
                    writes.addLink(sessionId, leftTokenId, rightTokenId, "manual", operatorId, s.basisRevision + 1, ts)
                    writes.log(operatorId, "BASIS_ADD_LINK", sessionId,
                        "建立谱系链接 $leftTokenId ~ $rightTokenId；basis r${s.basisRevision} -> r${s.basisRevision + 1}", ts)
                }
                "REMOVE_LINK" -> {
                    require(linkId != null) { "REMOVE_LINK 需要 linkId" }
                    writes.removeLink(linkId)
                    writes.log(operatorId, "BASIS_REMOVE_LINK", sessionId,
                        "删除谱系链接 #$linkId；basis r${s.basisRevision} -> r${s.basisRevision + 1}", ts)
                }
                else -> throw Conflict(400, "BAD_ACTION", "未知基础修订动作: $action")
            }
            writes.bumpBasis(sessionId)
            out = mapOf("basisRevision" to (s.basisRevision + 1))
        }
        return out
    }

    /** 执行一次合并：产生不可变新版本 + 完整 provenance + 未决项报告。来源版本永不被覆盖。 */
    fun merge(sessionId: String, operatorId: String, expectedBasis: Int?): JsonObj {
        var view: JsonObj? = null
        db.tx {
            val s = repo.session(sessionId) ?: throw Conflict(404, "NOT_FOUND", "会话不存在")
            if (expectedBasis != null && expectedBasis != s.basisRevision) throw Conflict(409, "BASIS_CHANGED",
                "对齐基础已变化（本地 r$expectedBasis，当前 r${s.basisRevision}），拒绝在过期基础上合并")
            val left = repo.analysis(s.leftVersionId)!!
            val right = repo.analysis(s.rightVersionId)!!
            val runId = "run-" + (repo.runs(sessionId).size + 1) + "-" + System.currentTimeMillis().toString(36)
            val newVersionId = "${s.sentenceId}-m-" + runId
            val result = Merger.merge(sessionId, newVersionId, left, right, repo.links(sessionId),
                repo.decisions(sessionId), s.basisRevision, operatorId, now())
            writes.persistMergeRun(runId, sessionId, newVersionId, operatorId, s.basisRevision, now(), result)
            writes.log(operatorId, "MERGE", sessionId,
                "合并出版本 $newVersionId（来源 ${s.leftVersionId} + ${s.rightVersionId}，" +
                    "未决 ${result.unresolved.size} 项，校验错误 ${result.findings.count { it.severity == "ERROR" }} 个）", now())
            view = JsonObj {
                put("runId", runId); put("newVersionId", newVersionId)
                put("unresolved", result.unresolved.map {
                    JsonObj { put("category", it.category); put("itemSignature", it.itemSignature)
                        put("description", it.description); put("reason", it.reason) }
                })
                put("validation", result.findings.map { Views.finding(it) })
            }
        }
        return view!!
    }
}
