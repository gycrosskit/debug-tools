package com.dgtang.debugtools.bugreport

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugEvidenceFormatterTest {
    @Test
    fun `keeps credentials mobile numbers and h5 parameters unchanged`() {
        val logs = """
            INFO/Navigation: Main -> FeatureA
            REQUEST: https://example.test/JS2/Checkin?kk=jwt-secret&audio=1
            METHOD: POST
            -> Authorization: Basic raw-credential
            -> Cookie: session=raw-cookie; theme=dark
            BODY START
            {"account":"tester","password":"body-secret","smsCode":123456,"mobile":"13800000000"}
            BODY END
            WARN/FeatureA: submit failed
        """.trimIndent()

        val formatted = BugEvidenceFormatter.boundRecentLogs(logs)

        assertEquals(logs, formatted)
        assertContains(formatted, "kk=jwt-secret&audio=1")
        assertContains(formatted, "Authorization: Basic raw-credential")
        assertContains(formatted, "session=raw-cookie")
        assertContains(formatted, "\"password\":\"body-secret\"")
        assertContains(formatted, "13800000000")
    }

    @Test
    fun `bounds individual evidence`() {
        assertEquals("abc", BugEvidenceFormatter.bound("abcdef", maxLength = 3))
    }

    @Test
    fun `bounds recent logs and retains newest context`() {
        val logs = (0 until 140).joinToString("\n") { index ->
            "line-$index ${"x".repeat(150)}"
        } + "\nnewest-marker"
        val formatted = BugEvidenceFormatter.boundRecentLogs(logs)

        assertTrue(formatted.length <= 12_000)
        assertContains(formatted, "已省略更早日志")
        assertContains(formatted, "newest-marker")
        assertFalse("line-0 " in formatted)
    }

    @Test
    fun `keeps exactly the newest bounded line window`() {
        val logs = (0..120).joinToString("\n") { index -> "line-$index" }

        val formatted = BugEvidenceFormatter.boundRecentLogs(logs)

        assertEquals((1..120).joinToString("\n") { index -> "line-$index" }, formatted)
    }
}
