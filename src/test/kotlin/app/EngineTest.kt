package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EngineTest {
    private val v1 = Fixture.all()[0]
    private val v2 = Fixture.all()[1]
    private val v3 = Fixture.all()[2]

    private fun links(pairs: List<Pair<String, String>>, session: String = "s") =
        pairs.mapIndexed { i, (l, r) -> CorrLink(i + 1L, l, r, "seed", null, "t$i") }

    @Test
    fun `split word forms one many-to-one granularity group`() {
        val st = Engine.computeState(v1, v2, links(Fixture.corrV1V2()), emptyList(), 0)
        val gran = st.groupItems.filter { it.kind == "GRANULARITY" }
        // 读不读(t8) -> 读(t8)+不(t9)+读(t10) 是唯一拆词组
        assertEquals(1, gran.size)
        assertEquals(listOf("${Fixture.V1}|t8"), gran.single().leftTokens)
        assertEquals(listOf("${Fixture.V2}|t8", "${Fixture.V2}|t9", "${Fixture.V2}|t10"), gran.single().rightTokens)
    }

    @Test
    fun `merged word in v3 forms many-to-one group and empty node stays unlinked`() {
        val st = Engine.computeState(v2, v3, links(Fixture.corrV2V3()), emptyList(), 0)
        val gran = st.groupItems.first { it.kind == "GRANULARITY" }
        assertEquals(listOf("${Fixture.V2}|t6", "${Fixture.V2}|t7"), gran.leftTokens)
        assertEquals(listOf("${Fixture.V3}|t6"), gran.rightTokens)
        // 空节点 pro 没有对应
        assertTrue(st.rightOnlyTokens.any { it == "${Fixture.V3}|t12" })
        assertTrue(st.rightOnlyTokens.all { st.tokenIndex[it]!!.second.empty })
    }

    @Test
    fun `attribute diff on lemma and case-mark relation conflict both visible`() {
        val st = Engine.computeState(v2, v3, links(Fixture.corrV2V3()), emptyList(), 0)
        val lemma = st.groupItems.filter { it.kind == "ATTR" }.flatMap { it.attrDiffs }
        assertTrue(lemma.any { it.attr == "lemma" && it.left == "老" && it.right == "旧" })
        // 在 -> 在 ：pos 相同 ADP，text 相同，lemma 相同，无属性差异；差异在依存关系
        val caseMark = st.edgeItems.firstOrNull { ei ->
            ei.leftEdges.any { it.relation == "case" } && ei.rightEdges.any { it.relation == "mark" }
        }
        assertNotNull(caseMark, "case↔mark 弧冲突必须可见")
    }

    @Test
    fun `changing alignment basis turns stale decision into explicit replay conflict`() {
        // 旧 basis 0：对“读不读”拆词组做 USE_B（细分为准）
        val links0 = links(Fixture.corrV1V2())
        var st0 = Engine.computeState(v1, v2, links0, emptyList(), 0)
        val granSig = st0.groupItems.single { it.kind == "GRANULARITY" }.signature
        val d = Decision(1L, "s", 1, "甲", "GRANULARITY", granSig, "USE_B", null,
            granSig, "拆词/合词: 读不读", 0, "t1")
        // 操作者乙改了对齐基础（basis=1）：拆除“读不读”这一组（删掉它的全部谱系链接），
        // 让“不/读”作为无对应 token 悬出，原组签名消失 -> 旧 USE_B 决定必须进入重放冲突。
        val changedLinks = links0.filterNot { it.leftTokenId == "${Fixture.V1}|t8" }
        val st1 = Engine.computeState(v1, v2, changedLinks, listOf(d), 1)
        val conflicts = st1.replayConflicts
        assertEquals(1, conflicts.size, "旧决定必须显式进入重放冲突，而不是被静默丢弃/覆盖")
        assertEquals(1, conflicts.single().seq)
        assertTrue(conflicts.single().reason.contains("对齐基础"))
    }

    @Test
    fun `opposite decisions on same basis are both kept and flagged for re-adjudication`() {
        val links0 = links(Fixture.corrV1V2())
        val st0 = Engine.computeState(v1, v2, links0, emptyList(), 0)
        val sig = st0.groupItems.single { it.kind == "GRANULARITY" }.signature
        val d1 = Decision(1L, "s", 1, "甲", "GRANULARITY", sig, "USE_A", null, sig, "拆词", 0, "t1")
        val d2 = Decision(2L, "s", 2, "乙", "GRANULARITY", sig, "USE_B", null, sig, "拆词", 0, "t2")
        val st = Engine.computeState(v1, v2, links0, listOf(d1, d2), 0)
        // 最新决定生效，但旧的相反决定进入重放冲突，日志完整保留
        assertEquals("USE_B", st.effective[sig]?.choice)
        assertTrue(st.replayConflicts.any { it.seq == 1 && it.operatorId == "甲" })
        assertEquals(listOf(1, 2), st.decisions.map { it.seq })
    }
}
