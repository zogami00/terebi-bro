package com.terebibro.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class JsonTest {

    private fun parse(json: String, allowed: Set<String> = setOf("a", "b", "c")) =
        Json.parseObject(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), allowed)

    private inline fun assertBad(block: () -> Unit) {
        try {
            block()
            fail("expected Json.BadJson")
        } catch (e: Json.BadJson) {
            // expected
        }
    }

    @Test
    fun `parses scalar values`() {
        val values = parse("""{"a":"x","b":true,"c":3}""")
        assertEquals("x", (values["a"] as Json.Value.Str).value)
        assertTrue((values["b"] as Json.Value.Bool).value)
        assertEquals(3.0, (values["c"] as Json.Value.Num).value, 0.0)
    }

    @Test
    fun `rejects duplicate keys`() {
        assertBad { parse("""{"a":1,"a":2}""") }
    }

    @Test
    fun `rejects unknown keys`() {
        assertBad { parse("""{"z":1}""") }
    }

    @Test
    fun `rejects nested objects and arrays`() {
        assertBad { parse("""{"a":{"b":1}}""") }
        assertBad { parse("""{"a":[1]}""") }
    }

    @Test
    fun `rejects trailing data`() {
        assertBad { parse("""{"a":1} extra""") }
    }

    @Test
    fun `rejects a non object top level`() {
        assertBad { parse("[1,2]") }
        assertBad { parse("42") }
        assertBad { parse("") }
    }

    @Test
    fun `rejects malformed syntax`() {
        assertBad { parse("{a:1}") }
        assertBad { parse("""{"a":}""") }
        assertBad { parse("""{"a":1,}""") }
        assertBad { parse("""{"a":"unterminated}""") }
    }

    @Test
    fun `decodes string escapes`() {
        val value = (parse("""{"a":"a\"b\n\u0041"}""")["a"] as Json.Value.Str).value
        assertEquals("a\"b\nA", value)
    }

    @Test
    fun `optionalBool throws on a present non boolean`() {
        val wrongType = parse("""{"a":"true"}""")
        assertThrows(Json.BadJson::class.java) { Json.optionalBool(wrongType, "a") }
        val absent = parse("""{"a":"x"}""")
        assertNull(Json.optionalBool(absent, "b"))
        val ok = parse("""{"a":false}""")
        assertEquals(false, Json.optionalBool(ok, "a"))
    }

    @Test
    fun `requireInt rejects fractions`() {
        assertThrows(Json.BadJson::class.java) { Json.requireInt(parse("""{"a":1.5}"""), "a") }
        assertEquals(7, Json.requireInt(parse("""{"a":7}"""), "a"))
    }

    // ------------------------------------------------------ opt-in string arrays

    private fun parseArray(
        json: String,
        allowed: Set<String> = setOf("a", "b", "c"),
        arrayKeys: Set<String> = allowed
    ) = Json.parseObject(
        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
        allowed,
        arrayKeys
    )

    @Test
    fun `an array is rejected by default`() {
        assertBad { parse("""{"a":["x"]}""") }
    }

    @Test
    fun `an array is rejected for a key not named in arrayKeys`() {
        assertBad { parseArray("""{"a":["x"]}""", allowed = setOf("a", "b"), arrayKeys = setOf("b")) }
    }

    @Test
    fun `an array is accepted only for a key named in arrayKeys`() {
        val values = parseArray("""{"a":["x","y"]}""")
        assertEquals(listOf("x", "y"), (values["a"] as Json.Value.StrList).items)
        assertEquals(listOf("x", "y"), Json.requireStringList(values, "a", 5, 10))

        val empty = parseArray("""{"a":[]}""")
        assertEquals(emptyList<String>(), (empty["a"] as Json.Value.StrList).items)
    }

    @Test
    fun `a scalar or null for an array key is rejected`() {
        // The key is an array key, so a scalar or null value is a parse error.
        assertBad { parseArray("""{"a":"10.0.0.0/8"}""") }
        assertBad { parseArray("""{"a":null}""") }
        assertBad { parseArray("""{"a":42}""") }

        // And requireStringList refuses a value that is not an array at all.
        assertThrows(Json.BadJson::class.java) {
            Json.requireStringList(parse("""{"a":"x"}"""), "a", 5, 20)
        }
    }

    @Test
    fun `a nested array or a non string item is rejected`() {
        assertBad { parseArray("""{"a":[[1]]}""") }
        assertBad { parseArray("""{"a":[1]}""") }
        assertBad { parseArray("""{"a":["x",2]}""") }
    }

    @Test
    fun `duplicate and unknown array keys are still rejected`() {
        assertBad { parseArray("""{"a":[],"a":[]}""") }
        assertBad { parseArray("""{"z":[]}""") }
    }

    @Test
    fun `requireStringList rejects too many and over-long items`() {
        val many = parseArray("""{"a":["1","2","3"]}""")
        assertThrows(Json.BadJson::class.java) { Json.requireStringList(many, "a", 2, 10) }

        val longItem = parseArray("""{"a":["123456"]}""")
        assertThrows(Json.BadJson::class.java) { Json.requireStringList(longItem, "a", 5, 3) }
    }
}
