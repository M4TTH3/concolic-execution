package poc.core.replay

/**
 * Minimal JSON encoder for the replay data (maps, lists, strings, numbers, booleans, null).
 * Everything outside printable ASCII is `\uXXXX`-escaped, so byte strings survive any file encoding.
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean, is Int, is Long -> out.append(value)
            is String -> string(value, out)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(k as String, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> error("cannot encode ${value::class.simpleName} as JSON")
        }
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                c == '<' -> out.append("\\u003c") // never closes a <script> even if inlined
                c.code in 0x20..0x7e -> out.append(c)
                else -> out.append("\\u%04x".format(c.code))
            }
        }
        out.append('"')
    }
}
