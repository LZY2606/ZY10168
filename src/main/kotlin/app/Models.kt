package app

import kotlinx.serialization.Serializable

/** 版本侧别：A/B 为来源修订版，M 为人工合并产生的新版本。 */
@Serializable
enum class VersionSide { A, B, M, L }

/** VALID=四项校验通过；INVALID=校验不通过但仍完整留档（不回滚、不覆盖）。 */
@Serializable
enum class VersionStatus { VALID, INVALID }

/** 决策状态。REPLAY_CONFLICT 表示对齐基础改变后旧决定必须重放裁决。 */
@Serializable
enum class DecisionStatus { ACTIVE, SUPERSEDED, REPLAY_CONFLICT, DISPUTED }

@Serializable
enum class DecisionKind { ALIGN, TOKENIZE, ATTR, ARC }

/** 合词时选择哪一侧的具体取值（用于词形/词性等属性）。 */
@Serializable
enum class AttrChoice { A, B, JOIN }

@Serializable
data class Token(
    val id: Long = 0,
    val versionId: Long,
    val ord: Int,
    val surface: String,
    val pos: String,
    val empty: Boolean = false,
    val lemma: String? = null,
    /** 合并 token 的来源说明，如 "AG3:B"；来源版本为空。 */
    val originGroup: String? = null
)

@Serializable
data class Arc(
    val id: Long = 0,
    val versionId: Long,
    val depId: Long,
    val headId: Long,
    val relation: String,
    /** 合并弧的来源，如 "M2:A(u04->u05 nsubj)#d7"。 */
    val provenance: String? = null
)

/** 不连续成分用多条 segment 表示，绝不把 min..max 之间的位置粗暴填满。 */
@Serializable
data class Span(
    val id: Long = 0,
    val versionId: Long,
    val label: String,
    val segments: List<IntRange>,
    val provenance: String? = null
) {
    val minPos get() = segments.minOf { it.first }
    val maxPos get() = segments.maxOf { it.last }
}

@Serializable
data class Version(
    val id: Long = 0,
    val sentenceId: Long,
    val side: VersionSide,
    val label: String,
    val parentMergeId: Long? = null,
    val status: VersionStatus,
    val violations: List<String> = emptyList(),
    val createdAt: String,
    val createdBy: String? = null,
    val tokens: List<Token> = emptyList(),
    val arcs: List<Arc> = emptyList(),
    val spans: List<Span> = emptyList()
)

@Serializable
data class Sentence(
    val id: Long = 0,
    val key: String,
    val text: String,
    val createdAt: String
)

/** token 谱系：一个 (version, token) 归属到一条 track；同 track 的 token 同源。 */
@Serializable
data class GenealogyLink(
    val id: Long = 0,
    val sentenceId: Long,
    val track: String,
    val versionId: Long,
    val tokenId: Long
)

/** 对齐组：把 A/B 两侧同源（含拆词/合词/空节点）的 token 聚到一起。 */
@Serializable
data class AlignmentGroup(
    val id: Long = 0,
    val sentenceId: Long,
    val gkey: String,
    val label: String,
    val basisRev: Long = 1,
    val note: String? = null
)

@Serializable
data class AlignmentMember(
    val id: Long = 0,
    val groupId: Long,
    val side: VersionSide,
    val tokenId: Long
)

@Serializable
data class Decision(
    val id: Long = 0,
    val sentenceId: Long,
    val kind: DecisionKind,
    /** ARC: "ARC:gkey:depOrd:headOrd" 侧别无关；其余: "KIND:gkey[:field]". */
    val conflictKey: String,
    val operator: String,
    val choice: String,
    val side: VersionSide? = null,
    val groupId: Long? = null,
    val basisRev: Long,
    val status: DecisionStatus = DecisionStatus.ACTIVE,
    val detail: String? = null,
    val supersedesId: Long? = null,
    val createdAt: String
)

@Serializable
data class MergeRecord(
    val id: Long = 0,
    val sentenceId: Long,
    val versionA: Long,
    val versionB: Long,
    val mergedVersionId: Long,
    val operator: String,
    val basisSnapshot: String,
    val createdAt: String
)
