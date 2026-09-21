package app

/** 够用的最小 JSON 解析器（请求体很小）。 */
object JsonMin {
    @Suppress("UNCHECKED_CAST")
    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws(); require(p.i >= s.length) { "trailing chars" }
        return v
    }

    fun obj(s: String) = parse(s) as? Map<String, Any?> ?: error("expected object")

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) error("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't', 'f' -> bool()
                'n' -> { require(s.startsWith("null", i)); i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++
            ws(); if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return m
            }
        }
        fun arr(): List<Any?> {
            val a = mutableListOf<Any?>(); i++
            ws(); if (i < s.length && s[i] == ']') { i++; return a }
            while (true) { a += value(); ws(); if (s[i] == ',') { i++; continue }; require(s[i] == ']'); i++; return a }
        }
        fun str(): String {
            require(s[i] == '"'); i++; val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') when (val e = s[i++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("bad escape $e")
                } else sb.append(c)
            }
        }
        fun bool(): Boolean = when {
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            else -> error("bad literal")
        }
        fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.contains('.') || t.contains('e', true)) t.toDouble() else t.toLong()
        }
    }
}
