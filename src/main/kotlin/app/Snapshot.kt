package app

import kotlinx.serialization.Serializable

/** 可导出/可重新导入的完整运行记录。token 引用一律用 ord，跨重导入保持稳定。 */
@Serializable
data class Snapshot(
    val format: Int = 1,
    val exportedAt: String = "",
    val sentences: List<SentenceDto> = emptyList()
)

@Serializable
data class TokenDto(
    val ord: Int,
    val surface: String,
    val pos: String,
    val empty: Boolean = false,
    val lemma: String? = null,
    val originGroup: String? = null,
    val tracks: List<String> = emptyList()
)

@Serializable
data class ArcDto(
    val dep: Int,
    val head: Int,
    val relation: String,
    val provenance: String? = null
)

@Serializable
data class SpanDto(
    val label: String,
    val segments: List<List<Int>>,
    val provenance: String? = null
)

@Serializable
data class VersionDto(
    val side: VersionSide,
    val label: String,
    val status: VersionStatus? = null,
    val violations: List<String> = emptyList(),
    val createdBy: String? = null,
    val parentMergeId: Long? = null,
    val tokens: List<TokenDto>,
    val arcs: List<ArcDto> = emptyList(),
    val spans: List<SpanDto> = emptyList()
)

@Serializable
data class GenealogyDto(val track: String, val side: VersionSide, val tokenOrd: Int)

@Serializable
data class GroupDto(
    val gkey: String,
    val label: String,
    val basisRev: Long = 1,
    val note: String? = null,
    val members: List<MemberDto>
)

@Serializable
data class MemberDto(val side: VersionSide, val tokenOrd: Int)

@Serializable
data class DecisionDto(
    val id: Long? = null,
    val kind: DecisionKind,
    val conflictKey: String,
    val operator: String,
    val choice: String,
    val side: VersionSide? = null,
    val gkey: String? = null,
    val basisRev: Long,
    val status: DecisionStatus = DecisionStatus.ACTIVE,
    val detail: String? = null,
    val supersedesId: Long? = null,
    val createdAt: String
)

@Serializable
data class MergeDto(
    val id: Long? = null,
    val sideA: VersionSide = VersionSide.A,
    val sideB: VersionSide = VersionSide.B,
    val operator: String,
    val basisSnapshot: String,
    val createdAt: String
)

@Serializable
data class SentenceDto(
    val key: String,
    val text: String,
    val versions: List<VersionDto>,
    val genealogy: List<GenealogyDto>,
    val groups: List<GroupDto> = emptyList(),
    val decisions: List<DecisionDto> = emptyList(),
    val merges: List<MergeDto> = emptyList()
)
