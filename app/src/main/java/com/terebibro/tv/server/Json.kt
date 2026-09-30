package com.terebibro.tv.server

import java.io.InputStream

/**
 * Strict JSON request parsing.
 *
 * Implemented as a small hand-written parser so it is pure JVM code (no
 * `android.util.JsonReader`) and therefore directly unit-testable: the top
 * level must be an object, unknown and duplicate keys are rejected, only scalar
 * values are accepted and trailing data is refused. Any violation is a
 * [BadJson], which routes map to HTTP 400.
 */
object Json {

    class BadJson(message: String) : Exception(message)

    sealed class Value {
        data class Str(val value: String) : Value()
        data class Bool(val value: Boolean) : Value()
        data class Num(val value: Double) : Value()
        object Null : Value()
    }

    fun parseObject(input: InputStream, allowedKeys: Set<String>): Map<String, Value> =
        Parser(input.readBytes().toString(Charsets.UTF_8)).parseObject(allowedKeys)

    private class Parser(private val text: String) {

        private var pos = 0

        fun parseObject(allowedKeys: Set<String>): Map<String, Value> {
            skipWs()
            expect('{')
            val result = LinkedHashMap<String, Value>()
            skipWs()
            if (peek() == '}') {
                pos++
                skipWs()
                requireEnd()
                return result
            }
            while (true) {
                skipWs()
                val name = parseString()
                if (!allowedKeys.contains(name)) throw BadJson("unknown_key")
                if (result.containsKey(name)) throw BadJson("duplicate_key")
                skipWs()
                expect(':')
                skipWs()
                result[name] = parseScalar()
                skipWs()
                when (cur()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        break
                    }
                    else -> throw BadJson("malformed_json")
                }
            }
            skipWs()
            requireEnd()
            return result
        }

        private fun parseScalar(): Value = when (cur()) {
            '"' -> Value.Str(parseString())
            't' -> {
                expectLiteral("true")
                Value.Bool(true)
            }
            'f' -> {
                expectLiteral("false")
                Value.Bool(false)
            }
            'n' -> {
                expectLiteral("null")
                Value.Null
            }
            '-', in '0'..'9' -> Value.Num(parseNumber())
            else -> throw BadJson("bad_type")
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = cur()
                pos++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val escape = cur()
                        pos++
                        when (escape) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw BadJson("malformed_json")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw BadJson("malformed_json")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw BadJson("malformed_json")
                        }
                    }
                    else -> {
                        if (c < ' ') throw BadJson("malformed_json")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') {
                pos++
            } else if (isNonZeroDigit()) {
                while (isDigit()) pos++
            } else {
                throw BadJson("malformed_json")
            }
            if (peek() == '.') {
                pos++
                if (!isDigit()) throw BadJson("malformed_json")
                while (isDigit()) pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (!isDigit()) throw BadJson("malformed_json")
                while (isDigit()) pos++
            }
            return text.substring(start, pos).toDoubleOrNull() ?: throw BadJson("malformed_json")
        }

        private fun isDigit(): Boolean = peek()?.let { it in '0'..'9' } ?: false

        private fun isNonZeroDigit(): Boolean = peek()?.let { it in '1'..'9' } ?: false

        private fun expectLiteral(literal: String) {
            if (!text.regionMatches(pos, literal, 0, literal.length)) throw BadJson("malformed_json")
            pos += literal.length
        }

        private fun expect(c: Char) {
            if (cur() != c) throw BadJson("malformed_json")
            pos++
        }

        private fun skipWs() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun requireEnd() {
            if (pos != text.length) throw BadJson("trailing_data")
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun cur(): Char = peek() ?: throw BadJson("malformed_json")
    }

    // ---------------------------------------------------------------------
    // Accessors used by the routes
    // ---------------------------------------------------------------------

    fun requireString(values: Map<String, Value>, key: String, maxLength: Int): String {
        val value = values[key] as? Value.Str ?: throw BadJson("missing_or_wrong_type:$key")
        if (value.value.length > maxLength) throw BadJson("too_long:$key")
        return value.value
    }

    /** Throws when the key is present but not a boolean; a missing key reads as null. */
    fun optionalBool(values: Map<String, Value>, key: String): Boolean? {
        val raw = values[key] ?: return null
        val value = raw as? Value.Bool ?: throw BadJson("wrong_type:$key")
        return value.value
    }

    fun requireInt(values: Map<String, Value>, key: String): Int {
        val value = values[key] as? Value.Num ?: throw BadJson("missing_or_wrong_type:$key")
        if (value.value % 1.0 != 0.0) throw BadJson("not_integer:$key")
        return value.value.toInt()
    }
}
