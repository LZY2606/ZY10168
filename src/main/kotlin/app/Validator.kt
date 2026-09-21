package app

/**
 * 四项硬校验 + 一项提示。任何一项失败都必须显式报告，
 * 禁止通过“自动补一个根”之类的方式掩盖断裂结构。
 */
object Validator {

    data class Finding(
        val code: String,   // WORD_ORDER / MULTI_HEAD / CYCLE / BROKEN_ROOT / GAP_FILLED / CROSSING_INFO
        val severity: String, // ERROR | INFO
        val message: String,
    )

    fun validate(a: Analysis): List<Finding> {
        val out = mutableListOf<Finding>()
        val tokens = a.tokens
        val byId = tokens.associateBy { it.id }

        // 1) 词序：ord 唯一且严格递增；空节点有独立逻辑位。
        val ords = tokens.map { it.ord }
        if (ords.toSet().size != ords.size) {
            out += Finding("WORD_ORDER", "ERROR", "ord 存在重复: $ords")
        }
        if (ords != ords.sorted()) {
            out += Finding("WORD_ORDER", "ERROR", "token 未按 ord 严格递增排列")
        }
        tokens.filter { !it.empty && it.text.isBlank() }.forEach {
            out += Finding("WORD_ORDER", "ERROR", "非空 token ${it.id} 缺少词形")
        }

        // 2) 唯一句法头：每个 token 至多被一条非根弧作为从属。
        val incoming = HashMap<String, MutableList<Dep>>()
        a.deps.filter { it.headId != null }.forEach { incoming.getOrPut(it.depId) { mutableListOf() }.add(it) }
        incoming.forEach { (depId, edges) ->
            if (edges.size > 1) {
                out += Finding("MULTI_HEAD", "ERROR",
                    "token ${labelOf(byId[depId])} 有 ${edges.size} 个句法头: " +
                        edges.joinToString { "${it.relation}<-${labelOf(byId[it.headId])}" })
            }
        }
        a.deps.forEach { d ->
            if (d.headId != null && byId[d.headId] == null)
                out += Finding("MULTI_HEAD", "ERROR", "弧 ${d.id} 的头 ${d.headId} 不存在")
            if (byId[d.depId] == null)
                out += Finding("MULTI_HEAD", "ERROR", "弧 ${d.id} 的从属 ${d.depId} 不存在")
        }

        // 3) 无环。
        val cycles = detectCycles(a.deps, byId.keys)
        cycles.forEach { cycle ->
            out += Finding("CYCLE", "ERROR", "依存环: ${cycle.joinToString(" -> ") { labelOf(byId[it]) }}")
        }

        // 4) 单根：恰好一个 root 弧，且所有 token 沿头链可达该根。不自动补根。
        val roots = a.deps.filter { it.headId == null }
        val rootDep = roots.map { it.depId }.toSet()
        if (roots.isEmpty()) {
            out += Finding("BROKEN_ROOT", "ERROR", "缺少 root 弧（结构断裂，拒绝自动加根）")
        } else {
            if (roots.size > 1) {
                out += Finding("BROKEN_ROOT", "ERROR",
                    "存在 ${roots.size} 条 root 弧（多根）: ${roots.joinToString { labelOf(byId[it.depId]) }}")
            }
            val seen = HashSet<String>()
            a.deps.filter { it.headId != null }.groupBy { it.depId }
            fun walk(id: String) {
                var cur: String? = id
                val path = HashSet<String>()
                while (cur != null && seen.add(cur)) {
                    if (!path.add(cur)) return
                    cur = a.deps.firstOrNull { it.depId == cur && it.headId != null }?.headId
                }
            }
            tokens.forEach { walk(it.id) }
            val unreachable = tokens.filter { it.id !in rootDep && !reaches(it.id, rootDep, a.deps) }
            if (unreachable.isNotEmpty()) {
                out += Finding("BROKEN_ROOT", "ERROR",
                    "存在无法到达 root 的孤岛 token（拒绝自动加根）: " +
                        unreachable.joinToString { labelOf(it) })
            }
        }

        // 5) 不连续成分：区段只能是“连续极小区间的并”，不允许把最小到最大位置粗暴填满。
        for (c in a.constituents) {
            val allTokenOrds = c.parts.flatten().mapNotNull { byId[it]?.ord }.sorted()
            if (allTokenOrds.isEmpty()) continue
            // 声明的覆盖集
            val declared = allTokenOrds.toSet()
            val min = allTokenOrds.first(); val max = allTokenOrds.last()
            // 每个 part 自身必须连续
            c.parts.forEach { part ->
                val ps = part.mapNotNull { byId[it]?.ord }.sorted()
                if (ps.isNotEmpty() && ps != (ps.first()..ps.last()).toList()) {
                    out += Finding("GAP_FILLED", "ERROR",
                        "成分 ${c.label}(${c.id}) 的区段内部有缺口却被填为连续段")
                }
            }
            // 若声明集合 == 整个 min..max，但 parts 又多于一个，属于“粗暴填满”
            if (c.parts.size > 1 && declared == (min..max).toSet()) {
                out += Finding("GAP_FILLED", "ERROR",
                    "成分 ${c.label}(${c.id}) 名义上不连续，却把 ${min}..${max} 全部填满")
            }
            // 区间内被排除的 token，必须真正是缺口（用于页面提示）
            val gaps = (min..max).toSet() - declared
            if (c.parts.size > 1 && gaps.isEmpty()) {
                out += Finding("GAP_FILLED", "ERROR", "成分 ${c.label} 的多区段之间没有真实缺口")
            }
        }

        // INFO：交叉依存弧（非投射）仅提示，不是错误；fixture 中会出现。
        val crossing = countCrossings(a.deps, byId)
        if (crossing > 0) {
            out += Finding("CROSSING_INFO", "INFO", "存在 $crossing 对交叉依存弧（非投射，允许）")
        }
        return out
    }

    private fun labelOf(t: Token?): String = t?.let { "${it.text}/${it.id}" } ?: "?"

    private fun reaches(start: String, targets: Set<String>, deps: List<Dep>): Boolean {
        var cur: String? = start
        val seen = HashSet<String>()
        while (cur != null) {
            if (cur in targets) return true
            if (!seen.add(cur)) return false
            cur = deps.firstOrNull { it.depId == cur && it.headId != null }?.headId
        }
        return false
    }

    private fun detectCycles(deps: List<Dep>, ids: Set<String>): List<List<String>> {
        val headOf = deps.filter { it.headId != null }.associate { it.depId to it.headId!! }
        val color = HashMap<String, Int>() // 0 white 1 gray 2 black
        val cycles = mutableListOf<List<String>>()
        fun dfs(u: String, stack: ArrayDeque<String>) {
            color[u] = 1; stack.addLast(u)
            headOf[u]?.let { v ->
                if (v in ids) when (color[v]) {
                    1 -> {
                        val cyc = stack.dropWhile { it != v } + v
                        cycles += cyc
                    }
                    null, 0 -> dfs(v, stack)
                }
            }
            color[u] = 2; stack.removeLast()
        }
        ids.forEach { if (color[it] != 2) dfs(it, ArrayDeque()) }
        return cycles.distinctBy { it.sorted() }
    }

    private fun countCrossings(deps: List<Dep>, byId: Map<String, Token>): Int {
        fun span(d: Dep): Pair<Int, Int>? {
            val h = d.headId?.let { byId[it]?.ord } ?: return null
            val q = byId[d.depId]?.ord ?: return null
            return minOf(h, q) to maxOf(h, q)
        }
        val spans = deps.mapNotNull { span(it) }
        var n = 0
        for (i in spans.indices) for (j in i + 1 until spans.size) {
            val (a, b) = spans[i]; val (c, d) = spans[j]
            if (a < c && c < b && b < d || c < a && a < d && d < b) n++
        }
        return n
    }
}
