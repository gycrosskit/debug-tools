package com.dgtang.debugtools.bugreport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZentaoBugClientTest {
    @Test
    fun `submits fixed product branch and complete automatic evidence`() = runTest {
        var captured: HttpRequestData? = null
        val client = ZentaoBugClient(
            engineClient = HttpClient(MockEngine { request ->
                captured = request
                respond(
                    content = """{"status":"success","data":{"id":"321"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            target = TEST_TARGET,
        )

        val result = client.submit(
            token = "personal-token",
            draft = BugReportDraft(title = "直播画面异常", steps = "进入直播间"),
            context = BugReportContext(
                brand = "test-brand",
                platform = BugReportPlatform.ANDROID,
                version = "1.0 (1)",
                environment = "TEST",
                device = "Pixel",
                userId = 7,
            ),
            evidence = BugEvidenceSnapshot(
                pagePaths = listOf("Main/LIVE", "Audience"),
                recentLogs = """
                    INFO/Navigation: opened Audience
                    REQUEST: https://example.test/Live/List
                    METHOD: POST
                    -> Authorization: Bearer secret
                    BODY START
                    {"page":2,"mobile":"13800000000"}
                    BODY END
                """.trimIndent(),
            ),
        )

        val request = requireNotNull(captured)
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals(321, result.id)
        assertEquals("personal-token", request.headers["Token"])
        assertTrue("\"productID\":19" in body)
        assertTrue("\"branch\":9" in body)
        assertTrue("\"openedBuild\":[\"trunk\"]" in body)
        assertTrue("Main/LIVE -> Audience" in body)
        assertTrue("最近日志上报" in body)
        assertTrue("INFO/Navigation" in body)
        assertTrue("METHOD: POST" in body)
        assertTrue("BODY START" in body)
        assertTrue("page" in body)
        assertTrue("secret" in body)
        assertTrue("13800000000" in body)
    }

    @Test
    fun `authorizes with account password and returns token`() = runTest {
        var captured: HttpRequestData? = null
        val client = ZentaoBugClient(
            engineClient = HttpClient(MockEngine { request ->
                captured = request
                respond(
                    content = """{"token":"issued-token"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            target = TEST_TARGET,
        )

        val token = client.authorize("developer", "one-time-password")

        val request = requireNotNull(captured)
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("issued-token", token)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(
            "https://example.test/zentao/api.php/v1/tokens",
            request.url.toString(),
        )
        assertTrue("\"account\":\"developer\"" in body)
        assertTrue("\"password\":\"one-time-password\"" in body)
    }

    @Test
    fun `missing target rejects before network request`() = runTest {
        var requested = false
        val client = ZentaoBugClient(
            engineClient = HttpClient(MockEngine {
                requested = true
                error("network must not be called")
            }),
            target = BugReportTarget.Unavailable,
        )

        val error = runCatching { client.authorize("account", "password") }.exceptionOrNull()

        assertFalse(client.available)
        assertFalse(requested)
        assertEquals("当前品牌尚未开通禅道 Bug 提交", error?.message)
    }

    @Test
    fun `maps not allowed submit response to configured target permission message`() = runTest {
        val client = ZentaoBugClient(
            engineClient = HttpClient(MockEngine {
                respond(
                    content = """{"message":"Not allowed"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
            target = TEST_TARGET,
        )

        val error = runCatching {
            client.submit(
                token = "token",
                draft = BugReportDraft(title = "标题"),
                context = BugReportContext(
                    brand = "test-brand",
                    platform = BugReportPlatform.ANDROID,
                    version = "1.0 (1)",
                    environment = "TEST",
                    device = "device",
                    userId = 0,
                ),
                evidence = BugEvidenceSnapshot(),
            )
        }.exceptionOrNull()

        assertEquals("当前禅道账号没有产品 19 / 分支 9 的提 Bug 权限", error?.message)
    }
}

private val TEST_TARGET = BugReportTarget.Configured(
    tokenUrl = "https://example.test/zentao/api.php/v1/tokens",
    apiBaseUrl = "https://example.test/zentao/api.php/v2",
    webBaseUrl = "https://example.test/zentao",
    productId = 19,
    branchId = 9,
    openedBuild = "trunk",
)
