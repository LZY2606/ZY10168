package app

/** 极简 JSON 构造器，避免在模型上散落反射注解。 */
class JsonObj(private val map: LinkedHashMap<String, Any?> = LinkedHashMap()) {
    fun put(k: String, v: Any?): JsonObj { map[k] = v; return this }
    override fun toString(): String = render()
    fun render(sb: StringBuilder = StringBuilder()): String {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(','); first = false
            str(sb, k); sb.append(':'); writeValue(sb, v)
        }
        sb.append('}'); return sb.toString()
    }
    companion object {
        operator fun invoke(block: JsonObj.() -> Unit = {}): JsonObj = JsonObj().apply(block)

        fun writeValue(sb: StringBuilder, v: Any?) {
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(v)
                is Number -> sb.append(v)
                is String -> str(sb, v)
                is JsonObj -> v.render(sb)
                is Map<*, *> -> {
                    sb.append('{'); var f = true
                    for ((k, vv) in v) { if (!f) sb.append(','); f = false; str(sb, k.toString()); sb.append(':'); writeValue(sb, vv) }
                    sb.append('}')
                }
                is Iterable<*> -> {
                    sb.append('['); var f = true
                    for (x in v) { if (!f) sb.append(','); f = false; writeValue(sb, x) }
                    sb.append(']')
                }
                else -> str(sb, v.toString())
            }
        }
        private fun str(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            sb.append('"')
        }
    }
}
