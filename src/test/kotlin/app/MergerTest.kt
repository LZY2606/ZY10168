package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MergerTest {
    private val v1 = Fixture.all()[0]
    private val v2 = Fixture.all()[1]
    private val v3 = Fixture.all()[2]

    private fun links(pairs: List<Pair<String, String>>) =
        pairs.mapIndexed { i, (l, r) -> CorrLink(i + 1L, l, r, "seed", null, "t$i") }

    @Test
    fun `merge with USE_B granularity picks fine split and keeps provenance`() {
        val st0 = Engine.computeState(v1, v2, links(Fixture.corrV1V2()), emptyList(), 0)
        val sig = st0.groupItems.single { it.kind == "GRANULARITY" }.signature
        val decision = Decision(1L, "s", 1, "甲", "GRANULARITY", sig, "USE_B", null, sig, "拆词", 0, "t")
        val r = Merger.merge("s", "nv", v1, v2, links(Fixture.corrV1V2()), listOf(decision), 0, "甲", "now")
        // 新版本保留细分的三个 token：读 不 读
        val splitTokens = r.analysis.tokens.map { it.text }
        assertEquals(2, splitTokens.count { it == "读" })
        assertTrue(splitTokens.contains("不"))
        val prov = r.tokenProvs.first { it.newTokenId == "nv|t8" }
        assertEquals(Fixture.V2, prov.sourceVersionId)
        assertEquals(1, prov.viaDecisionSeq)
        // 没有粒度未决；可能无任何硬错误（弧与成分都可落地）
        assertTrue(r.unresolved.none { it.category == "GRANULARITY" })
        val errors = r.findings.filter { it.severity == "ERROR" }
        assertEquals(emptyList<Validator.Finding>(), errors)
    }

    @Test
    fun `pending granularity drops dependent arcs into unresolved instead of healing`() {
        val st0 = Engine.computeState(v1, v2, links(Fixture.corrV1V2()), emptyList(), 0)
        val sig = st0.groupItems.single { it.kind == "GRANULARITY" }.signature
        val decision = Decision(1L, "s", 1, "甲", "GRANULARITY", sig, "PENDING", null, sig, "拆词", 0, "t")
        val r = Merger.merge("s", "nv", v1, v2, links(Fixture.corrV1V2()), listOf(decision), 0, "甲", "now")
        assertTrue(r.unresolved.any { it.category == "GRANULARITY" && it.description.contains("拆词") })
        // 断裂必须显式暴露（少了核心动词组，单根校验失败），不得自动加根
        assertTrue(r.findings.any { it.code == "BROKEN_ROOT" })
    }

    @Test
    fun `discontinuous constituent survives merge without gap filling`() {
        val st0 = Engine.computeState(v1, v2, links(Fixture.corrV1V2()), emptyList(), 0)
        val sig = st0.groupItems.single { it.kind == "GRANULARITY" }.signature
        val d = Decision(1L, "s", 1, "甲", "GRANULARITY", sig, "USE_B", null, sig, "拆词", 0, "t")
        val r = Merger.merge("s", "nv", v1, v2, links(Fixture.corrV1V2()), listOf(d), 0, "甲", "now")
        val vp = r.analysis.constituents.first { it.label == "VP" }
        assertTrue(vp.parts.size >= 2, "VP 必须保持多区段（不连续）")
        // 所有区段内部连续，但区段之间确实有缺口
        val ords = r.analysis.tokens.associate { it.id to it.ord }
        vp.parts.forEach { part ->
            val ps = part.map { ords[it]!! }.sorted()
            assertEquals((ps.first()..ps.last()).toList(), ps)
        }
        val declared = vp.parts.flatten().map { ords[it]!! }.toSet()
        val all = (declared.min()..declared.max()).toSet()
        assertTrue((all - declared).isNotEmpty(), "区段之间必须有真实缺口")
        assertTrue(r.findings.none { it.code == "GAP_FILLED" && it.severity == "ERROR" })
    }

    @Test
    fun `empty node only on v3 survives merge as empty token and is traceable`() {
        val st0 = Engine.computeState(v2, v3, links(Fixture.corrV2V3()), emptyList(), 0)
        val gran = st0.groupItems.single { it.kind == "GRANULARITY" }.signature
        // 上海市 合词：v3 一侧为单 token，选 B；case/mark 弧冲突选 B；其他默认
        val d1 = Decision(1L, "s", 1, "乙", "GRANULARITY", gran, "USE_B", null, gran, "合词", 0, "t")
        val edgeSig = st0.edgeItems.first { ei ->
            ei.leftEdges.any { it.relation == "case" } && ei.rightEdges.any { it.relation == "mark" }
        }.signature
        val d2 = Decision(2L, "s", 2, "乙", "EDGE", edgeSig, "KEEP_B", null, edgeSig, "case/mark", 0, "t")
        // v3 在宾语位置多一个空 pro：凡 v2 书(t12) 与 v3 pro(t12) 各自作为头/从属参与的弧，
        // 全部选 B 侧，保证结构来自同一版本，不靠“混合两侧”自动拼接。
        val chooseBSigs = st0.edgeItems.filter { ei ->
            val involvesPro = ei.rightEdges.any { e -> e.depId == "${Fixture.V3}|t12" || e.headId == "${Fixture.V3}|t12" }
            val involvesBook = ei.leftEdges.any { e -> e.depId == "${Fixture.V2}|t12" || e.headId == "${Fixture.V2}|t12" }
            involvesPro || involvesBook
        }.map { it.signature }.toMutableList()
        val decisions = mutableListOf(d1, d2)
        var seq = 3
        chooseBSigs.filter { it != edgeSig }.forEach { sig ->
            decisions += Decision(seq.toLong(), "s", seq, "乙", "EDGE", sig, "KEEP_B", null, sig, "弧", 0, "t"); seq++
        }
        val r = Merger.merge("s", "nv", v2, v3, links(Fixture.corrV2V3()), decisions, 0, "乙", "now")
        val empty = r.analysis.tokens.filter { it.empty }
        assertEquals(1, empty.size, "空节点 pro 必须保留")
        val prov = r.tokenProvs.first { it.newTokenId == empty.single().id }
        assertEquals(Fixture.V3, prov.sourceVersionId)
        // pro 的 obj 弧也落地
        assertTrue(r.analysis.deps.any { it.depId == empty.single().id && it.relation == "obj" })
        val errors = r.findings.filter { it.severity == "ERROR" }
        assertEquals(emptyList<Validator.Finding>(), errors, errors.joinToString { it.message })
    }
}
