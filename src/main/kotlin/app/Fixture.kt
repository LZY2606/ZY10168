package app

/**
 * 固定三版短语料（人造诱发句，README 有数据口径说明）。
 *
 *  S = 这些 书 ， 张三 在 上海 市 读 不读 老 书 ？
 *
 * v1 粗分：把 A-not-A 的「读 不读」合为一个 token
 * v2 细分：拆成「读 / 不 / 读」
 * v3 合词 + 空节点：「上海+市」合为「上海市」，宾语位置补一个空节点 pro
 *
 * 三个版本都带：
 *  - 交叉依存弧（话题「书」跨越主语「张三」回挂到动词，非投射，仅提示不报错）
 *  - 不连续成分（VP / NP 区段带真实缺口，绝不用最小-最大填满）
 * v3 与 v2 在「在」的格关系标签上故意不一致（case ↔ mark），作为属性/关系冲突素材。
 */
object Fixture {
    const val SENTENCE_ID = "s1"
    const val V1 = "s1-v1"; const val V2 = "s1-v2"; const val V3 = "s1-v3"
    val SURFACE = "这些书，张三在上海市读不读老书？"
    private val T0 = "2026-09-22T09:00:00Z"

    private fun tok(v: String, i: Int, ord: Int, text: String, lemma: String, pos: String, empty: Boolean = false) =
        Token("$v|t$i", v, ord, text, lemma, pos, empty)
    private fun dep(v: String, i: Int, head: Int?, from: Int, rel: String) =
        Dep("$v|d$i", v, head?.let { "$v|t$it" }, "$v|t$from", rel)

    private fun v1(): Analysis {
        // 1这些 2书 3， 4张三 5在 6上海 7市 8读不读 9老 10书 11？
        val ts = listOf(
            tok(V1, 1, 1, "这些", "这些", "DET"),
            tok(V1, 2, 2, "书", "书", "NOUN"),
            tok(V1, 3, 3, "，", "，", "PUNCT"),
            tok(V1, 4, 4, "张三", "张三", "PROPN"),
            tok(V1, 5, 5, "在", "在", "ADP"),
            tok(V1, 6, 6, "上海", "上海", "PROPN"),
            tok(V1, 7, 7, "市", "市", "PART"),
            tok(V1, 8, 8, "读不读", "读", "AUX"),
            tok(V1, 9, 9, "老", "老", "ADJ"),
            tok(V1, 10, 10, "书", "书", "NOUN"),
            tok(V1, 11, 11, "？", "？", "PUNCT"),
        )
        val ds = listOf(
            dep(V1, 1, 2, 1, "det"),
            dep(V1, 2, 10, 2, "dislocated"),
            dep(V1, 3, 10, 3, "punct"),
            dep(V1, 4, 8, 4, "nsubj"),
            dep(V1, 5, 6, 5, "case"),
            // 地点状语跨挂到宾语中心(t10)（诱发的非常规分析，README 标注为语料设计），
            // 与 nsubj(t4->t8) 形成真正交叉：(4,8) × (6,10)
            dep(V1, 6, 10, 6, "obl:lvc"),
            dep(V1, 7, 6, 7, "flat:name"),
            dep(V1, 8, null, 8, "root"),
            dep(V1, 9, 10, 9, "amod"),
            dep(V1, 10, 8, 10, "obj"),
            dep(V1, 11, 10, 11, "punct"),
        )
        val cs = listOf(
            Constituent("$V1|c1", V1, "NP", listOf(listOf("$V1|t1", "$V1|t2"))),
            Constituent("$V1|c2", V1, "NP", listOf(listOf("$V1|t6", "$V1|t7"))),
            Constituent("$V1|c3", V1, "NP", listOf(listOf("$V1|t9", "$V1|t10"))),
            // 不连续 VP：动词(t8) + 宾语中心(t10)，缺口 t9（老）在两个区段之间
            Constituent("$V1|c4", V1, "VP", listOf(listOf("$V1|t8"), listOf("$V1|t10"))),
        )
        return Analysis(Version(V1, SENTENCE_ID, "v1 粗分（读+不读 合词）", "human", null, null, null,
            "基线版本", T0), ts, ds, cs)
    }

    private fun v2(): Analysis {
        // 1这些 2书 3， 4张三 5在 6上海 7市 8读 9不 10读 11老 12书 13？
        val ts = listOf(
            tok(V2, 1, 1, "这些", "这些", "DET"),
            tok(V2, 2, 2, "书", "书", "NOUN"),
            tok(V2, 3, 3, "，", "，", "PUNCT"),
            tok(V2, 4, 4, "张三", "张三", "PROPN"),
            tok(V2, 5, 5, "在", "在", "ADP"),
            tok(V2, 6, 6, "上海", "上海", "PROPN"),
            tok(V2, 7, 7, "市", "市", "PART"),
            tok(V2, 8, 8, "读", "读", "VERB"),
            tok(V2, 9, 9, "不", "不", "ADV"),
            tok(V2, 10, 10, "读", "读", "VERB"),
            tok(V2, 11, 11, "老", "老", "ADJ"),
            tok(V2, 12, 12, "书", "书", "NOUN"),
            tok(V2, 13, 13, "？", "？", "PUNCT"),
        )
        val ds = listOf(
            dep(V2, 1, 2, 1, "det"),
            dep(V2, 2, 12, 2, "dislocated"),
            dep(V2, 3, 12, 3, "punct"),
            dep(V2, 4, 8, 4, "nsubj"),
            dep(V2, 5, 6, 5, "case"),
            dep(V2, 6, 12, 6, "obl:lvc"),
            dep(V2, 7, 6, 7, "flat:name"),
            dep(V2, 8, null, 8, "root"),
            dep(V2, 9, 8, 9, "advmod"),
            dep(V2, 10, 8, 10, "conj"),
            dep(V2, 11, 12, 11, "amod"),
            dep(V2, 12, 8, 12, "obj"),
            dep(V2, 13, 12, 13, "punct"),
        )
        val cs = listOf(
            Constituent("$V2|c1", V2, "NP", listOf(listOf("$V2|t1", "$V2|t2"))),
            Constituent("$V2|c2", V2, "NP", listOf(listOf("$V2|t6", "$V2|t7"))),
            Constituent("$V2|c3", V2, "NP", listOf(listOf("$V2|t11", "$V2|t12"))),
            // 不连续 VP：前动词(t8) … 后动词+宾语(t10,t12 不连续跨 t11)
            // 每个区段自身连续：区段1=[t8]，区段2=[t10]，区段3=[t12]；缺口 t9,t11 在区段之间
            Constituent("$V2|c4", V2, "VP", listOf(
                listOf("$V2|t8"), listOf("$V2|t10"), listOf("$V2|t12"))),
        )
        return Analysis(Version(V2, SENTENCE_ID, "v2 细分（读/不/读 拆词）", "human", null, null, null,
            "在 v1 基础上拆分 A-not-A", T0), ts, ds, cs)
    }

    private fun v3(): Analysis {
        // 1这些 2书 3， 4张三 5在 6上海市 7读 8不 9读 10老 11书 12∅pro 13？
        val ts = listOf(
            tok(V3, 1, 1, "这些", "这些", "DET"),
            tok(V3, 2, 2, "书", "书", "NOUN"),
            tok(V3, 3, 3, "，", "，", "PUNCT"),
            tok(V3, 4, 4, "张三", "张三", "PROPN"),
            tok(V3, 5, 5, "在", "在", "SCONJ"), // 与 v2 的 ADP 词性分歧（配套 case/mark 弧标签分歧）
            tok(V3, 6, 6, "上海市", "上海市", "PROPN"),
            tok(V3, 7, 7, "读", "读", "VERB"),
            tok(V3, 8, 8, "不", "不", "ADV"),
            tok(V3, 9, 9, "读", "读", "VERB"),
            tok(V3, 10, 10, "老", "旧", "ADJ"), // lemma 差异：老/旧
            tok(V3, 11, 11, "书", "书", "NOUN"),
            tok(V3, 12, 12, "", "pro", "PRON", empty = true), // 空节点：宾语位置的回指 pro
            tok(V3, 13, 13, "？", "？", "PUNCT"),
        )
        val ds = listOf(
            dep(V3, 1, 2, 1, "det"),
            dep(V3, 2, 12, 2, "dislocated"), // 话题回指空节点 -> 交叉弧
            dep(V3, 3, 7, 3, "punct"),
            dep(V3, 4, 7, 4, "nsubj"),
            dep(V3, 5, 6, 5, "mark"), // 与 v2 的 case 故意冲突
            dep(V3, 6, 12, 6, "obl:lvc"),
            dep(V3, 7, null, 7, "root"),
            dep(V3, 8, 7, 8, "advmod"),
            dep(V3, 9, 7, 9, "conj"),
            dep(V3, 10, 11, 10, "amod"),
            dep(V3, 11, 12, 11, "amod"), // 老书 修饰空 pro
            dep(V3, 12, 7, 12, "obj"),
            dep(V3, 13, 12, 13, "punct"),
        )
        val cs = listOf(
            Constituent("$V3|c1", V3, "NP", listOf(listOf("$V3|t1", "$V3|t2"))),
            Constituent("$V3|c2", V3, "NP", listOf(listOf("$V3|t6"))),
            // 不连续 NP：话题词(t2) 与宾语位置的空中心(t12) 构成同一宾语 NP 的两个实现段，
            // 区段之间是主语/状语/动词等真实缺口；每段自身连续。
            Constituent("$V3|c3", V3, "NP", listOf(listOf("$V3|t2"), listOf("$V3|t12"))),
            // 不连续 VP：前动词(t7) … 后动词(t9) … 空宾语(t12)，缺口 t8（不）、t10,t11（老书）在区段之间
            Constituent("$V3|c4", V3, "VP", listOf(
                listOf("$V3|t7"), listOf("$V3|t9"), listOf("$V3|t12"))),
        )
        return Analysis(Version(V3, SENTENCE_ID, "v3 合词+空节点（上海市 合词，宾语 pro 空节点）",
            "human", null, null, null, "在 v2 基础上合词并补空节点；case/mark 标签分歧", T0), ts, ds, cs)
    }

    fun all(): List<Analysis> = listOf(v1(), v2(), v3())

    /** v1↔v2 种子谱系：粗分 token 与细分 token 的多对多关系。 */
    fun corrV1V2(): List<Pair<String, String>> = listOf(
        "$V1|t1" to "$V2|t1",
        "$V1|t2" to "$V2|t2",
        "$V1|t3" to "$V2|t3",
        "$V1|t4" to "$V2|t4",
        "$V1|t5" to "$V2|t5",
        "$V1|t6" to "$V2|t6",
        "$V1|t7" to "$V2|t7",
        "$V1|t8" to "$V2|t8",   // 读不读 -> 读（前）
        "$V1|t8" to "$V2|t9",   //          -> 不
        "$V1|t8" to "$V2|t10",  //          -> 读（后）
        "$V1|t9" to "$V2|t11",
        "$V1|t10" to "$V2|t12",
        "$V1|t11" to "$V2|t13",
    )

    /** v2↔v3 种子谱系：合词 + 空节点（空节点没有任何链接）。 */
    fun corrV2V3(): List<Pair<String, String>> = listOf(
        "$V2|t1" to "$V3|t1",
        "$V2|t2" to "$V3|t2",
        "$V2|t3" to "$V3|t3",
        "$V2|t4" to "$V3|t4",
        "$V2|t5" to "$V3|t5",
        "$V2|t6" to "$V3|t6",   // 上海 -> 上海市
        "$V2|t7" to "$V3|t6",   // 市   -> 上海市
        "$V2|t8" to "$V3|t7",
        "$V2|t9" to "$V3|t8",
        "$V2|t10" to "$V3|t9",
        "$V2|t11" to "$V3|t10",
        "$V2|t12" to "$V3|t11",
        // V3|t12 (pro 空节点) 故意不连：不确定对应必须保留
        "$V2|t13" to "$V3|t13",
    )
}
