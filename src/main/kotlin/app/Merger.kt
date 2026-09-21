package app

object Merger {

    data class TokenProv(
        val newTokenId: String,
        val sourceVersionId: String,
        val sourceTokenIds: List<String>,
        val selectedSide: String,   // A | B | BOTH | A_AUTO
        val viaDecisionSeq: Int?,
        val attrSide: String?,
    )

    data class DepProv(
        val newDepId: String,
        val sourceDepId: String,
        val sourceVersionId: String,
        val selectedSide: String,
        val viaDecisionSeq: Int?,
    )

    data class ConProv(
        val newConstituentId: String,
        val sourceConstituentId: String,
        val sourceVersionId: String,
        val selectedSide: String,
        val viaDecisionSeq: Int?,
    )

    data class Unresolved(val category: String, val itemSignature: String, val description: String, val reason: String)

    data class Result(
        val analysis: Analysis,
        val tokenProvs: List<TokenProv>,
        val depProvs: List<DepProv>,
        val conProvs: List<ConProv>,
        val unresolved: List<Unresolved>,
        val findings: List<Validator.Finding>,
    )

    fun merge(
        sessionId: String,
        newVersionId: String,
        left: Analysis,
        right: Analysis,
        links: List<CorrLink>,
        decisions: List<Decision>,
        basisRevision: Int,
        operatorId: String,
        now: String,
    ): Result {
        val st = Engine.computeState(left, right, links, decisions, basisRevision)
        val unresolved = mutableListOf<Unresolved>()
        val tokenProvs = mutableListOf<TokenProv>()
        val depProvs = mutableListOf<DepProv>()
        val conProvs = mutableListOf<ConProv>()
        val newDeps = mutableListOf<Dep>()
        val newCons = mutableListOf<Constituent>()
        val seenDepKey = HashSet<Triple<String?, String, String>>()

        // ---- 对齐组 -> 新 token ----
        val mergedOfGroup = HashMap<String, String>()
        val tokenMap = HashMap<String, String>()
        val order = mutableListOf<Pair<Pair<Int, Int>, Any>>()

        val groups = st.groups
        for (g in groups) {
            val minOrd = (g.left + g.right).mapNotNull { st.tokenIndex[it]?.second?.ord }
                .minOrNull() ?: Int.MAX_VALUE
            order += (minOrd to 0) to g
        }
        for (t in st.leftOnlyTokens)
            order += (st.tokenIndex[t]!!.second.ord to 1) to st.tokenIndex[t]!!.second
        for (t in st.rightOnlyTokens)
            order += (st.tokenIndex[t]!!.second.ord to 2) to st.tokenIndex[t]!!.second

        val newTokens = mutableListOf<Token>()
        var tokSeq = 0
        for ((_, payload) in order.sortedWith(compareBy({ it.first.first }, { it.first.second }))) {
            val nextId = { ++tokSeq; "$newVersionId|t$tokSeq" }
            when (payload) {
                is Engine.Group -> {
                    val g = payload
                    val item = st.groupItems.firstOrNull { it.signature == Engine.groupSig(g.gid) }
                    val eff = st.effective[Engine.groupSig(g.gid)]
                    val isGran = g.left.size != 1 || g.right.size != 1
                    if (isGran) {
                        when (eff?.choice) {
                            "USE_A", "KEEP_A" -> {
                                val srcL = g.left.map { st.tokenIndex[it]!!.second }.sortedBy { it.ord }
                                var firstId: String? = null
                                srcL.forEach { tt ->
                                    val id = nextId()
                                    newTokens += Token(id, newVersionId, tokSeq, tt.text, tt.lemma, tt.pos, tt.empty)
                                    tokenMap[tt.id] = id
                                    tokenProvs += TokenProv(id, left.version.id, listOf(tt.id), "A", eff.seq, "A")
                                    if (firstId == null) firstId = id
                                }
                                mergedOfGroup[g.gid] = firstId!!
                                // 对侧（细/粗）全部指向首个新 token，弧仍可解析；provenance 完整保留来源
                                (g.right).forEach { tokenMap[it] = firstId!! }
                            }
                            "USE_B", "KEEP_B" -> {
                                val srcR = g.right.map { st.tokenIndex[it]!!.second }.sortedBy { it.ord }
                                var firstId: String? = null
                                srcR.forEach { tt ->
                                    val id = nextId()
                                    newTokens += Token(id, newVersionId, tokSeq, tt.text, tt.lemma, tt.pos, tt.empty)
                                    tokenMap[tt.id] = id
                                    tokenProvs += TokenProv(id, right.version.id, listOf(tt.id), "B", eff.seq, "B")
                                    if (firstId == null) firstId = id
                                }
                                mergedOfGroup[g.gid] = firstId!!
                                (g.left).forEach { tokenMap[it] = firstId!! }
                            }
                            "CONFIRM" -> {
                                // CONFIRM = 接受现状的对应（用于“确认 1:1 对齐”）；多对多组按左侧表层连写产出一个 token
                                val id = nextId()
                                val srcL = g.left.map { st.tokenIndex[it]!!.second }.sortedBy { it.ord }
                                val rep = (g.left + g.right).map { st.tokenIndex[it]!!.second }.minByOrNull { it.ord }!!
                                newTokens += Token(id, newVersionId, tokSeq,
                                    srcL.joinToString("") { it.text }, rep.lemma, rep.pos,
                                    (g.left + g.right).any { st.tokenIndex[it]!!.second.empty })
                                mergedOfGroup[g.gid] = id
                                (g.left + g.right).forEach { tokenMap[it] = id }
                                tokenProvs += TokenProv(id, left.version.id, g.left + g.right, "BOTH", eff.seq, "A")
                            }
                            else -> {
                                unresolved += Unresolved("GRANULARITY", Engine.groupSig(g.gid),
                                    item?.description ?: "组 ${g.gid}",
                                    "拆词/合词语粒度尚无决定（或处于 PENDING），该组在新版本中没有任何 token")
                            }
                        }
                    } else {
                        val id = nextId()
                        val lt = st.tokenIndex[g.left[0]]!!.second
                        val rt = st.tokenIndex[g.right[0]]!!.second
                        val attrItem = item?.takeIf { it.kind == "ATTR" }
                        val attrSide = when (eff?.choice) {
                            "KEEP_B", "USE_B" -> "B"
                            else -> "A"
                        }
                        val chosen = if (attrSide == "A") lt else rt
                        newTokens += Token(id, newVersionId, tokSeq, chosen.text, chosen.lemma, chosen.pos,
                            lt.empty || rt.empty)
                        mergedOfGroup[g.gid] = id
                        tokenMap[g.left[0]] = id; tokenMap[g.right[0]] = id
                        tokenProvs += TokenProv(id, left.version.id, listOf(lt.id, rt.id),
                            "BOTH", eff?.seq, if (attrItem == null) null else attrSide)
                    }
                }
                is Token -> {
                    // 空节点：谱系不确定（默认无对应）也仍然保留，供空占位分析延续。
                    // 非空的单侧 token：不自动并入新版本（那等于偷偷做了合/弃决定）。
                    if (payload.empty) {
                        val id = nextId()
                        newTokens += Token(id, newVersionId, tokSeq, payload.text, payload.lemma, payload.pos, true)
                        val side = if (payload.versionId == left.version.id) "A" else "B"
                        tokenProvs += TokenProv(id, payload.versionId, listOf(payload.id), side, null, null)
                        mergedOfGroup[payload.id] = id
                        tokenMap[payload.id] = id
                    } else {
                        unresolved += Unresolved("ALIGNMENT", Engine.groupSig(payload.id),
                            "token「${payload.text}」在另一侧没有谱系对应",
                            "需要人工建立链接或决定去留；合并不自动采纳单侧词")
                    }
                }
            }
        }

        // ---- 依存弧 ----
        var depSeq = 0
        // 词内弧：当粒度组在所选侧展开为多个 token 时，恢复该侧的词内结构（advmod/conj）。
        fun emitIntra(group: Engine.Group, sourceEdges: List<Dep>, sideVersion: String, side: String, viaSeq: Int?) {
            for (src in sourceEdges) {
                val nh = tokenMap[src.headId!!]; val nd = tokenMap[src.depId]
                if (nh == null || nd == null || nh == nd) continue
                depSeq++; val nid = "$newVersionId|d$depSeq"
                if (!seenDepKey.add(Triple(nh, nd, src.relation))) continue
                newDeps += Dep(nid, newVersionId, nh, nd, src.relation)
                depProvs += DepProv(nid, src.id, sideVersion, side, viaSeq)
            }
        }
        for (g in st.groups) {
            if (g.left.size == 1 && g.right.size == 1) continue
            val eff = st.effective[Engine.groupSig(g.gid)]
            val lSet = g.left.toSet(); val rSet = g.right.toSet()
            val intraL = left.deps.filter { it.headId != null && it.depId in lSet && it.headId in lSet }
            val intraR = right.deps.filter { it.headId != null && it.depId in rSet && it.headId in rSet }
            when (eff?.choice) {
                "USE_A", "KEEP_A" -> emitIntra(g, intraL, left.version.id, "A", eff.seq)
                "USE_B", "KEEP_B" -> emitIntra(g, intraR, right.version.id, "B", eff.seq)
                else -> {
                    // 未决粒度组：词内弧也记为未决（在组未决中已覆盖，这里不补弧）
                }
            }
        }
        for (ei in st.edgeItems) {
            if (ei.headGroup == ei.depGroup) continue // 词内弧
            val eff = st.effective[ei.signature]
            val sameRel = ei.leftEdges.map { it.relation }.toSet() == ei.rightEdges.map { it.relation }.toSet() &&
                ei.leftEdges.isNotEmpty() && ei.rightEdges.isNotEmpty()
            val chosenEdges: List<Dep>
            val side: String
            val auto: Boolean
            when {
                eff?.choice == "KEEP_A" || eff?.choice == "USE_A" -> { chosenEdges = ei.leftEdges; side = "A"; auto = false }
                eff?.choice == "KEEP_B" || eff?.choice == "USE_B" -> { chosenEdges = ei.rightEdges; side = "B"; auto = false }
                eff?.choice == "PENDING" || eff?.choice == "REJECT" -> {
                    unresolved += Unresolved("DEPENDENCY", ei.signature, ei.description, "人工标记为待决/拒绝")
                    continue
                }
                eff?.choice == "CONFIRM" -> { chosenEdges = ei.leftEdges; side = "A"; auto = false }
                sameRel -> { chosenEdges = ei.leftEdges; side = "A"; auto = true }
                ei.leftEdges.isNotEmpty() && ei.rightEdges.isEmpty() -> { chosenEdges = ei.leftEdges; side = "A"; auto = true }
                ei.rightEdges.isNotEmpty() && ei.leftEdges.isEmpty() -> { chosenEdges = ei.rightEdges; side = "B"; auto = true }
                else -> {
                    unresolved += Unresolved("DEPENDENCY", ei.signature, ei.description,
                        "两侧关系不同且没有人工选择: " +
                            (ei.leftEdges.map { "A:${it.relation}" } + ei.rightEdges.map { "B:${it.relation}" }).joinToString())
                    continue
                }
            }
            val headId = if (ei.headGroup == "__ROOT__") null else mergedOfGroup[ei.headGroup]
            val depId = mergedOfGroup[ei.depGroup]
            if (depId == null) {
                unresolved += Unresolved("DEPENDENCY", ei.signature, ei.description,
                    "从属组粒度待决，弧无法落地（不自动悬挂到其他词）")
                continue
            }
            if (headId == null && ei.headGroup != "__ROOT__") {
                unresolved += Unresolved("DEPENDENCY", ei.signature, ei.description,
                    "头组粒度待决，弧无法落地（不自动改挂）")
                continue
            }
            for (src in chosenEdges) {
                depSeq++; val nid = "$newVersionId|d$depSeq"
                val key = Triple(headId, depId, src.relation)
                if (!seenDepKey.add(key)) continue // 不同源弧合并塌缩到同一目标时去重
                newDeps += Dep(nid, newVersionId, headId, depId, src.relation)
                depProvs += DepProv(nid, src.id,
                    if (side == "A") left.version.id else right.version.id,
                    if (auto) "${side}_AUTO" else side, eff?.seq)
            }
        }

        // ---- 成分 ----
        var conSeq = 0
        for (ci in st.constituentItems) {
            val eff = st.effective[ci.signature]
            val source: Constituent
            val side: String
            val auto: Boolean
            when {
                eff?.choice == "KEEP_A" || eff?.choice == "USE_A" -> { if (ci.left == null) {
                    unresolved += Unresolved("CONSTITUENT", ci.signature, ci.description, "选择了左侧但左侧不存在"); continue }
                    source = ci.left; side = "A"; auto = false }
                eff?.choice == "KEEP_B" || eff?.choice == "USE_B" -> { if (ci.right == null) {
                    unresolved += Unresolved("CONSTITUENT", ci.signature, ci.description, "选择了右侧但右侧不存在"); continue }
                    source = ci.right; side = "B"; auto = false }
                eff?.choice == "PENDING" || eff?.choice == "REJECT" -> {
                    unresolved += Unresolved("CONSTITUENT", ci.signature, ci.description, "人工标记为待决/拒绝"); continue
                }
                eff?.choice == "CONFIRM" || ci.left != null -> { source = ci.left ?: ci.right!!; side = if (ci.left != null) "A" else "B"; auto = ci.left != null && ci.right != null }
                ci.right != null -> { source = ci.right; side = "B"; auto = true }
                else -> continue
            }
            val newParts = mutableListOf<List<String>>()
            var missing = false
            for (part in source.parts) {
                val mapped = part.mapNotNull { tokenMap[it] }.distinct()
                if (mapped.size != part.size) { missing = true; break }
                newParts += mapped
            }
            if (missing) {
                unresolved += Unresolved("CONSTITUENT", ci.signature, ci.description,
                    "成分包含粒度待决的 token，不能部分落地（不做最小-最大填满）")
                continue
            }
            conSeq++; val nid = "$newVersionId|c$conSeq"
            newCons += Constituent(nid, newVersionId, source.label, newParts)
            conProvs += ConProv(nid, source.id,
                if (side == "A") left.version.id else right.version.id,
                if (auto) "${side}_AUTO" else side, eff?.seq)
        }

        val newVersion = Version(
            newVersionId, left.version.sentenceId,
            "合并 ${left.version.label} + ${right.version.label} @r$basisRevision",
            "merged", left.version.id, right.version.id, null,
            "操作者 $operatorId 在 basis r$basisRevision 上合并；未决 ${unresolved.size} 项", now)
        // 不同 EdgeItem 可能塌缩出同一条 (head, dep, relation)：保留一条，provenance 保留全部来源
        val dedupDeps = mutableListOf<Dep>()
        val dedupProv = mutableListOf<DepProv>()
        val keptDep = HashMap<Triple<String?, String, String>, String>()
        for (d in newDeps) {
            val key = Triple(d.headId, d.depId, d.relation)
            val existing = keptDep[key]
            if (existing == null) { keptDep[key] = d.id; dedupDeps += d }
        }
        val keptIds = keptDep.values.toHashSet()
        for (p in depProvs) {
            if (p.newDepId in keptIds) dedupProv += p
            else {
                // 找到同键保留弧，来源挂到它上面（通过 source 信息附加为独立来源行，id 用保留弧 id）
                val d = newDeps.first { it.id == p.newDepId }
                val target = keptDep[Triple(d.headId, d.depId, d.relation)]!!
                dedupProv += p.copy(newDepId = target)
            }
        }
        val analysis = Analysis(newVersion, newTokens.sortedBy { it.ord },
            dedupDeps.sortedBy { it.id }, newCons)
        val findings = Validator.validate(analysis)
        return Result(analysis, tokenProvs, dedupProv, conProvs, unresolved, findings)
    }
}
