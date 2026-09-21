package app

/**
 * 裁决引擎：按 token 谱系把两个版本对齐成若干对齐组（alignment group），
 * 从对齐组派生属性差异 / 粒度 / 依存弧 / 成分四类待决项，
 * 并在 basisRevision 推进时把旧决定显式标成重放冲突。
 *
 * 本对象全部为纯函数；数据库写入在 Route 层的事务里完成。
 */
object Engine {

    data class GroupItem(
        val signature: String,
        val kind: String,                    // GRANULARITY / ATTR
        val description: String,
        val leftTokens: List<String>,
        val rightTokens: List<String>,
        val attrDiffs: List<AttrDiff> = emptyList(),
    )

    data class AttrDiff(val attr: String, val left: String, val right: String)

    data class EdgeItem(
        val signature: String,
        val headGroup: String,
        val depGroup: String,
        val description: String,
        val leftEdges: List<Dep> = emptyList(),
        val rightEdges: List<Dep> = emptyList(),
    )

    data class ConstituentItem(
        val signature: String,
        val description: String,
        val label: String,
        val left: Constituent? = null,
        val right: Constituent? = null,
    )

    data class ReplayConflict(
        val seq: Int,
        val operatorId: String,
        val ref: String,
        val type: String,
        val choice: String,
        val itemDescription: String,
        val reason: String,
        val atBasis: Int,
        val currentBasis: Int,
    )

    data class Group(
        val gid: String,
        val left: List<String>,
        val right: List<String>,
    )

    data class State(
        val groups: List<Group>,
        val groupItems: List<GroupItem>,
        val edgeItems: List<EdgeItem>,
        val constituentItems: List<ConstituentItem>,
        val tokenIndex: Map<String, Pair<String, Token>>,
        val decisions: List<Decision>,
        val effective: Map<String, Decision>,
        val replayConflicts: List<ReplayConflict>,
        val basisRevision: Int,
        val leftOnlyTokens: List<String>,
        val rightOnlyTokens: List<String>,
    ) {
        fun allItems(): List<String> =
            groupItems.map { it.signature } + edgeItems.map { it.signature } + constituentItems.map { it.signature }
    }

    // ---------- 对齐组 ----------

    /** 并查集；[sideOf] 判定 token 归属，[ordOf] 决定组内排序。 */
    fun buildGroups(links: List<CorrLink>, sideOf: (String) -> String, ordOf: (String) -> Int): List<Group> {
        val parent = HashMap<String, String>()
        fun find(x: String): String {
            val p = parent[x]
            if (p == null) { parent[x] = x; return x }
            if (p == x) return x
            val r = find(p); parent[x] = r; return r
        }
        fun union(a: String, b: String) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        links.forEach { union(it.leftTokenId, it.rightTokenId) }
        return parent.keys.map { find(it) }.distinct().sorted().map { root ->
            val members = parent.keys.filter { find(it) == root }
            val l = members.filter { sideOf(it) == "L" }.sortedBy(ordOf)
            val r = members.filter { sideOf(it) == "R" }.sortedBy(ordOf)
            Group(root, l, r)
        }.filter { it.left.isNotEmpty() && it.right.isNotEmpty() }
    }

    private fun tokenLabel(t: Token?): String = t?.let { if (it.empty) "∅(${it.id.substringAfter('|')})" else it.text } ?: "?"

    // ---------- 状态计算 ----------

    fun computeState(
        left: Analysis,
        right: Analysis,
        links: List<CorrLink>,
        decisions: List<Decision>,
        basisRevision: Int,
    ): State {
        val leftIds = left.tokens.map { it.id }.toSet()
        val sideOf: (String) -> String = { if (it in leftIds) "L" else "R" }
        val ordOf: (String) -> Int = { id ->
            (left.tokens.firstOrNull { it.id == id } ?: right.tokens.firstOrNull { it.id == id })?.ord ?: Int.MAX_VALUE
        }
        val groups = buildGroups(links, sideOf, ordOf)
        val index = HashMap<String, Pair<String, Token>>()
        left.tokens.forEach { index[it.id] = "L" to it }
        right.tokens.forEach { index[it.id] = "R" to it }
        val gidOf = HashMap<String, String>()
        groups.forEach { g -> (g.left + g.right).forEach { gidOf[it] = g.gid } }

        val groupItems = mutableListOf<GroupItem>()
        for (g in groups) {
            val desc = describeGroup(g, index)
            if (g.left.size != 1 || g.right.size != 1) {
                groupItems += GroupItem(groupSig(g.gid), "GRANULARITY", "拆词/合词: $desc",
                    g.left, g.right)
            } else {
                val lt = index[g.left[0]]!!.second; val rt = index[g.right[0]]!!.second
                val diffs = mutableListOf<AttrDiff>()
                if (lt.lemma != rt.lemma) diffs += AttrDiff("lemma", lt.lemma, rt.lemma)
                if (lt.pos != rt.pos) diffs += AttrDiff("pos", lt.pos, rt.pos)
                if (lt.text != rt.text) diffs += AttrDiff("text", lt.text, rt.text)
                if (diffs.isNotEmpty())
                    groupItems += GroupItem(groupSig(g.gid), "ATTR",
                        "属性差异: $desc（" + diffs.joinToString { "${it.attr}: ${it.left}↔${it.right}" } + "）",
                        g.left, g.right, diffs)
            }
        }

        // 依存弧：按 (headGroup, depGroup, side) 聚合
        data class EdgeKey(val h: String, val d: String)
        val edgeMap = HashMap<EdgeKey, Pair<MutableList<Dep>, MutableList<Dep>>>()
        fun g(tokId: String?) = tokId?.let { gidOf[it] ?: it } ?: "__ROOT__"
        for (e in left.deps) {
            val slot = edgeMap.getOrPut(EdgeKey(g(e.headId), g(e.depId))) { mutableListOf<Dep>() to mutableListOf() }
            slot.first += e
        }
        for (e in right.deps) {
            val slot = edgeMap.getOrPut(EdgeKey(g(e.headId), g(e.depId))) { mutableListOf<Dep>() to mutableListOf() }
            slot.second += e
        }
        val edgeItems = edgeMap.entries.sortedBy { it.key.d }.map { (k, sides) ->
            val hDesc = if (k.h == "__ROOT__") "ROOT" else groupName(k.h, groups, index)
            val dDesc = groupName(k.d, groups, index)
            val rels = (sides.first + sides.second).joinToString("/") { it.relation }
            EdgeItem(edgeSig(k.d, k.h), k.h, k.d, "弧: $dDesc —$rels→ $hDesc",
                sides.first.sortedBy { it.id }, sides.second.sortedBy { it.id })
        }

        // 成分：按 组序列 匹配，左右分别与对侧比较
        val constituentItems = matchConstituents(left, right, g = { gidOf[it] ?: it })

        val leftOnly = left.tokens.filter { it.id !in gidOf }.map { it.id }.sorted()
        val rightOnly = right.tokens.filter { it.id !in gidOf }.map { it.id }.sorted()

        // 决策重放：在旧 basis 上做出、而对象签名已不存在/已变化 -> 显式重放冲突
        val currentSigs = (groupItems.map { it.signature } + edgeItems.map { it.signature } +
            constituentItems.map { it.signature }).toSet()
        val replay = mutableListOf<ReplayConflict>()
        for (d in decisions) {
            if (d.basisRevision < basisRevision && d.itemSignature !in currentSigs) {
                replay += ReplayConflict(
                    d.seq, d.operatorId, d.ref, d.type, d.choice, d.itemDescription,
                    "对齐基础已在第 $basisRevision 次修订；该决定所针对的对象「${d.itemDescription}」已不存在或签名改变",
                    d.basisRevision, basisRevision)
            }
        }
        // 同一当前对象上存在互相矛盾的旧决定（相反决定并存），旧 seq 进入重放冲突
        val effective = HashMap<String, Decision>()
        for (d in decisions.sortedBy { it.seq }) {
            val prev = effective[d.itemSignature]
            if (prev != null && d.basisRevision >= basisRevision && prev.basisRevision >= basisRevision &&
                contradicts(prev.choice, d.choice)) {
                replay += ReplayConflict(prev.seq, prev.operatorId, prev.ref, prev.type, prev.choice,
                    prev.itemDescription,
                    "同一对象上存在相反决定（另一位操作者选择了 ${d.choice}）；旧决定保留在日志中，需重放裁决",
                    prev.basisRevision, basisRevision)
            }
            effective[d.itemSignature] = d
        }

        return State(groups, groupItems, edgeItems, constituentItems, index,
            decisions.sortedBy { it.seq }, effective, replay.distinctBy { it.seq },
            basisRevision, leftOnly, rightOnly)
    }

    private fun contradicts(a: String, b: String): Boolean = when {
        a == b -> false
        a == "PENDING" || b == "PENDING" -> true
        a == "CONFIRM" && (b == "KEEP_A" || b == "KEEP_B" || b == "USE_A" || b == "USE_B") -> true
        (a == "KEEP_A" || a == "USE_A") && (b == "KEEP_B" || b == "USE_B") -> true
        (a == "KEEP_B" || a == "USE_B") && (b == "KEEP_A" || b == "USE_A") -> true
        else -> false
    }

    private fun describeGroup(g: Group, index: Map<String, Pair<String, Token>>): String {
        val l = g.left.joinToString("+") { tokenLabel(index[it]?.second) }
        val r = g.right.joinToString("+") { tokenLabel(index[it]?.second) }
        return "$l ↔ $r"
    }

    private fun groupName(gid: String, groups: List<Group>, index: Map<String, Pair<String, Token>>): String {
        val g = groups.firstOrNull { it.gid == gid } ?: return tokenLabel(index[gid]?.second)
        return (g.left + g.right).joinToString("/") { tokenLabel(index[it]?.second) }
    }

    fun groupSig(gid: String) = "GRP::$gid"
    fun edgeSig(depGroup: String, headGroup: String) = "EDGE::$depGroup->$headGroup"
    fun constituentSig(seq: String, label: String) = "CON::$label::$seq"

    data class ConMatch(
        val signature: String,
        val label: String,
        val left: Constituent?,
        val right: Constituent?,
        val leftSeq: List<String>,
        val rightSeq: List<String>,
    )

    fun matchConstituents(left: Analysis, right: Analysis, g: (String) -> String): List<ConstituentItem> {
        fun seqOf(c: Constituent) = c.parts.map { part -> part.map(g).joinToString(",") }.joinToString("|")
        val leftByKey = left.constituents.groupBy { constituentSig(seqOf(it), it.label) }
        val rightByKey = right.constituents.groupBy { constituentSig(seqOf(it), it.label) }
        val keys = (leftByKey.keys + rightByKey.keys).toSortedSet()
        return keys.map { key ->
            val lc = leftByKey[key]?.firstOrNull(); val rc = rightByKey[key]?.firstOrNull()
            val rep = lc ?: rc!!
            val desc = buildString {
                append("成分 ${rep.label}")
                if ((lc != null && lc.parts.size > 1) || (rc != null && rc.parts.size > 1)) append("（不连续）")
                append(": ")
                val c = lc ?: rc!!
                append(c.parts.joinToString(" … ") { part -> part.map { g(it) }.joinToString(" ") })
                if (lc != null && rc == null) append(" [仅左]")
                if (rc != null && lc == null) append(" [仅右]")
            }
            ConstituentItem(key, desc, rep.label, lc, rc)
        }
    }
}
