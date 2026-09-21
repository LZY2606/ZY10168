package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValidatorTest {

    private val v = Fixture.all().first()

    @Test
    fun `three fixture versions have exactly one root and no hard errors`() {
        Fixture.all().forEach { a ->
            val errors = Validator.validate(a).filter { it.severity == "ERROR" }
            assertEquals(emptyList<Validator.Finding>(), errors,
                "版本 ${a.version.id} 不应有硬错误: ${errors.map { it.message }}")
            val roots = a.deps.count { it.headId == null }
            assertEquals(1, roots, "${a.version.id} 必须单根")
        }
    }

    @Test
    fun `crossing arcs are reported as info only`() {
        Fixture.all().forEach { a ->
            val findings = Validator.validate(a)
            assertTrue(findings.any { it.code == "CROSSING_INFO" },
                "${a.version.id} 应含交叉依存弧（fixture 设计）")
            assertTrue(findings.none { it.code == "CROSSING_INFO" && it.severity == "ERROR" })
        }
    }

    @Test
    fun `discontinuous constituents must not be min-max filled`() {
        // 把 v2 的 VP 改成“t8..t12 全填满”且仍声明两段 -> 必须报 GAP_FILLED
        val a = Fixture.all()[1]
        val bad = a.copy(constituents = a.constituents.map { c ->
            if (c.id == "${Fixture.V2}|c4")
                c.copy(parts = listOf(listOf("${Fixture.V2}|t8", "${Fixture.V2}|t9", "${Fixture.V2}|t10"),
                    listOf("${Fixture.V2}|t11", "${Fixture.V2}|t12")))
            else c
        })
        val findings = Validator.validate(bad).filter { it.code == "GAP_FILLED" }
        assertTrue(findings.isNotEmpty(), "填满伪不连续成分必须被抓到")
    }

    @Test
    fun `multi-head is rejected`() {
        val a = Fixture.all()[0]
        val bad = a.copy(deps = a.deps + Dep("x", a.version.id, "${Fixture.V1}|t9", "${Fixture.V1}|t10", "extra"))
        assertTrue(Validator.validate(bad).any { it.code == "MULTI_HEAD" })
    }

    @Test
    fun `cycle is rejected`() {
        val a = Fixture.all()[0]
        // 人为制造一个 2-环：t1 -> t2 原本 det；新增 t2 -> t1 会与 det 方向相反（t1 已有头），
        // 所以用一个孤岛式环：在两个原本没有互相指向的 token 上重排不现实，直接构造小型分析。
        val tiny = Analysis(
            a.version,
            listOf(
                Token("z|t1", "z", 1, "a", "a", "X"),
                Token("z|t2", "z", 2, "b", "b", "X"),
            ),
            listOf(
                Dep("z|d1", "z", "z|t2", "z|t1", "x"),
                Dep("z|d2", "z", "z|t1", "z|t2", "y"),
            ),
            emptyList())
        assertTrue(Validator.validate(tiny).any { it.code == "CYCLE" })
    }

    @Test
    fun `missing root is reported as broken root without auto-fix`() {
        val a = Fixture.all()[0]
        val bad = a.copy(deps = a.deps.filter { it.headId != null })
        val findings = Validator.validate(bad)
        assertTrue(findings.any { it.code == "BROKEN_ROOT" && it.message.contains("拒绝自动加根") })
        assertEquals(0, bad.deps.count { it.headId == null })
    }

    @Test
    fun `word order duplicate ord rejected`() {
        val a = Fixture.all()[0]
        val bad = a.copy(tokens = a.tokens.map { if (it.id == "${Fixture.V1}|t2") it.copy(ord = 1) else it })
        assertTrue(Validator.validate(bad).any { it.code == "WORD_ORDER" })
    }
}
