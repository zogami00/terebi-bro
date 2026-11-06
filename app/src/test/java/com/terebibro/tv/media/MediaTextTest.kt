package com.terebibro.tv.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTextTest {

    @Test
    fun textIsQuotedNotInjected() {
        val script = MediaText.script("a\"); alert(1); (\"</script>")
        assertTrue(script.contains("var t=\"a\\\"); alert(1); (\\\"<\\/script>\";"))
        assertFalse(script.contains("alert(1); (\"<"))
    }

    @Test
    fun emptyTextClearsTheField() {
        assertTrue(MediaText.script("").contains("var t=\"\";"))
    }
}
