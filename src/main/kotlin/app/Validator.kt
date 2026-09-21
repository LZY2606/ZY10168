package app

/**
 * 结构四项校验：
 *  1. 词序：ord 必须在 0..n-1 内稠密、唯一；弧/成分引用的 token 必须存在。
 *  2. 唯一句法头：每个 token 至多作为一条弧的从属（dep）。
 *  3. 无环：沿 head 指针不得成环。
 *  4. 单根：恰好一个 token 没有 head。断裂（0 根）与多根都报错——绝不自动加根掩盖。
 * 不连续成分的 segments 必须有序、不重叠；不会把 min..max 之间填满。
 */
object Validator {

    fun validate(tokens: List<Token>, arcs: List<Arc>, spans: List<Span>): List<String> {
        val violations = mutableListOf<String>()
        val byId = tokens.associateBy { it.id }

        val ords = tokens.map { it.ord }
        if (ords.toSet().size != ords.size) violations += "词序校验失败: token ord 存在重复"
        val expected = tokens.indices.toSet()
        if (ords.toSet() != expected) {
            violations += "词序校验失败: ord 必须从 0 连续编号到 ${tokens.size - 1}（实际: ${ords.sorted()}），禁止跳号"
        }

        for (arc in arcs) {
            if (byId[arc.depId] == null) violations += "弧(${arc.relation}) 引用了不存在的 dep token ${arc.depId}"
            if (byId[arc.headId] == null) violations += "弧(${arc.relation}) 引用了不存在的 head token ${arc.headId}"
            if (arc.depId == arc.headId) violations += "弧(${arc.relation}) 自环: token ${arc.depId} 指向自己"
        }

        val deps = arcs.groupingBy { it.depId }.eachCount()
        for ((depId, count) in deps) {
            if (count > 1) {
                val t = byId[depId]
                violations += "唯一句法头失败: token ${depId}(${t?.surface ?: "?"}) 有 $count 个 head"
            }
        }

        val headOf = arcs.associate { it.depId to it.headId }
        val state = HashMap<Long, Int>() // 0=访问中 1=已完成
        fun walk(start: Long) {
            val path = LinkedHashSet<Long>()
            var cur: Long? = start
            while (cur != null) {
                val mark = state[cur]
                if (mark == 1) break
                if (mark == 0 || !path.add(cur)) {
                    violations += "无环校验失败: head 链成环 (${path.joinToString("->") { byId[it]?.surface ?: it.toString() }}->${byId[cur]?.surface ?: cur})"
                    break
                }
                state[cur] = 0
                cur = headOf[cur]
            }
            path.forEach { state[it] = 1 }
        }
        tokens.forEach { walk(it.id) }

        val roots = tokens.filter { !headOf.containsKey(it.id) }
        when {
            roots.isEmpty() -> violations += "单根校验失败: 结构断裂，没有根节点（不允许自动加根掩盖）"
            roots.size > 1 -> violations += "单根校验失败: 存在 ${roots.size} 个根 (${roots.joinToString { it.surface }})，不允许自动加根"
        }

        for (span in spans) {
            if (span.segments.isEmpty()) {
                violations += "成分[${span.label}]没有任何区段"
                continue
            }
            val sorted = span.segments.sortedBy { it.first }
            for (seg in sorted) {
                if (seg.first > seg.last) violations += "成分[${span.label}]区段反向: ${seg.first}..${seg.last}"
                if (byId.values.none { it.ord == seg.first } || byId.values.none { it.ord == seg.last })
                    violations += "成分[${span.label}]引用越界 ord: ${seg.first}..${seg.last}"
            }
            for (i in 1 until sorted.size) {
                if (sorted[i].first <= sorted[i - 1].last)
                    violations += "不连续成分[${span.label}]区段交叠: ${sorted[i - 1]} 与 ${sorted[i]}（须保留间隔，禁止填满）"
            }
        }

        return violations.distinct()
    }
}
