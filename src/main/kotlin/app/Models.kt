package app

/** 一个词例（token）。empty=true 表示空节点（无语音形式，仍占据逻辑位置）。 */
data class Token(
    val id: String,
    val versionId: String,
    val ord: Int,
    val text: String,
    val lemma: String,
    val pos: String,
    val empty: Boolean = false,
)

/** 依存弧：headId 为 null 表示根弧（root）。 */
data class Dep(
    val id: String,
    val versionId: String,
    val headId: String?,
    val depId: String,
    val relation: String,
)

/** 成分。parts 为若干个有序区段；区段多于一个即不连续成分。 */
data class Constituent(
    val id: String,
    val versionId: String,
    val label: String,
    val parts: List<List<String>>,
)

data class Version(
    val id: String,
    val sentenceId: String,
    val label: String,
    val kind: String, // human | merged
    val parentA: String? = null,
    val parentB: String? = null,
    val mergeRunId: String? = null,
    val note: String? = null,
    val createdAt: String,
)

data class Analysis(
    val version: Version,
    val tokens: List<Token>,
    val deps: List<Dep>,
    val constituents: List<Constituent>,
)

/** token 谱系对应（边）：左侧版本 token 与右侧版本 token 的同源关系。 */
data class CorrLink(
    val id: Long? = null,
    val leftTokenId: String,
    val rightTokenId: String,
    val source: String,   // seed | manual
    val operatorId: String?,
    val createdAt: String,
)

data class Decision(
    val id: Long? = null,
    val sessionId: String,
    val seq: Int,
    val operatorId: String,
    val type: String,      // GRANULARITY | EDGE | CONSTITUENT | ATTR | ALIGN_BASIS
    val ref: String,       // 对象签名（当前 item 标识）
    val choice: String,    // CONFIRM | KEEP_A | KEEP_B | REJECT | PENDING | USE_A | USE_B | ADD_LINK | REMOVE_LINK
    val payload: String? = null,
    val itemSignature: String,
    val itemDescription: String,
    val basisRevision: Int,
    val createdAt: String,
)

data class MergeRun(
    val id: String,
    val sessionId: String,
    val newVersionId: String,
    val operatorId: String,
    val basisRevision: Int,
    val createdAt: String,
)

data class EventLogRow(
    val id: Long,
    val ts: String,
    val actor: String,
    val kind: String,
    val sessionId: String?,
    val detail: String,
)
