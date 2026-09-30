package com.dgtang.debugtools.bugreport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BugReportRepositoryTest {
    @Test
    fun `remote success survives local history read and write failures`() = runTest {
        for (failReading in listOf(true, false)) {
            var requests = 0
            val store = MemoryBugReportStore().apply {
                token = "test-token"
                if (failReading) historyReadError = IllegalStateException("read failed")
                else historyWriteError = IllegalStateException("write failed")
            }
            val http = HttpClient(MockEngine {
                requests += 1
                respond("""{"id":42}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                val repository = BugReportRepository(ZentaoBugClient(http, TEST_REPOSITORY_TARGET), store)
                val result = repository.submit(TEST_DRAFT, TEST_CONTEXT, BugEvidenceSnapshot())
                assertEquals(42, result.id)
                assertEquals("https://example.test/zentao/bug-view-42.html", result.url)
                assertFalse(result.historySaved)
                assertEquals(1, requests)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun `history cancellation still propagates`() = runTest {
        val store = MemoryBugReportStore().apply {
            token = "test-token"
            historyReadError = CancellationException("cancelled")
        }
        val http = HttpClient(MockEngine {
            respond("""{"id":42}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repository = BugReportRepository(ZentaoBugClient(http, TEST_REPOSITORY_TARGET), store)
            assertFailsWith<CancellationException> {
                repository.submit(TEST_DRAFT, TEST_CONTEXT, BugEvidenceSnapshot())
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun `stores token only after authorization and product permission check succeed`() = runTest {
        var requestCount = 0
        val store = MemoryBugReportStore()
        val repository = BugReportRepository(
            client = ZentaoBugClient(
                engineClient = HttpClient(MockEngine {
                    requestCount += 1
                    respond(
                        content = if (requestCount == 1) {
                            """{"token":"issued-token"}"""
                        } else {
                            """{"status":"success","id":19}"""
                        },
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }),
                target = TEST_REPOSITORY_TARGET,
            ),
            store = store,
        )

        repository.authorize("developer", "password")

        assertEquals(2, requestCount)
        assertEquals("issued-token", store.token)
    }

    @Test
    fun `does not store token when product permission check fails`() = runTest {
        var requestCount = 0
        val store = MemoryBugReportStore()
        val repository = BugReportRepository(
            client = ZentaoBugClient(
                engineClient = HttpClient(MockEngine {
                    requestCount += 1
                    respond(
                        content = if (requestCount == 1) {
                            """{"token":"issued-token"}"""
                        } else {
                            """{"message":"无权访问当前产品"}"""
                        },
                        status = if (requestCount == 1) HttpStatusCode.OK else HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }),
                target = TEST_REPOSITORY_TARGET,
            ),
            store = store,
        )

        val error = runCatching { repository.authorize("developer", "password") }.exceptionOrNull()

        assertEquals("当前禅道账号无权访问产品 19", error?.message)
        assertEquals("", store.token)
    }
}

private val TEST_REPOSITORY_TARGET = BugReportTarget.Configured(
    tokenUrl = "https://example.test/zentao/api.php/v1/tokens",
    apiBaseUrl = "https://example.test/zentao/api.php/v2",
    webBaseUrl = "https://example.test/zentao",
    productId = 19,
    branchId = 9,
    openedBuild = "trunk",
)

private class MemoryBugReportStore : BugReportStore {
    var token = ""
    private var shakeEnabled = true
    private var history = emptyList<BugSubmissionRecord>()
    var historyReadError: Exception? = null
    var historyWriteError: Exception? = null

    override suspend fun readToken(): String = token
    override suspend fun writeToken(value: String) { token = value }
    override suspend fun readShakeEnabled(): Boolean = shakeEnabled
    override suspend fun writeShakeEnabled(value: Boolean) { shakeEnabled = value }
    override suspend fun readHistory(): List<BugSubmissionRecord> {
        historyReadError?.let { throw it }
        return history
    }
    override suspend fun writeHistory(value: List<BugSubmissionRecord>) {
        historyWriteError?.let { throw it }
        history = value
    }
    override suspend fun clearCredentials() { token = "" }
}

private val TEST_DRAFT = BugReportDraft(title = "Test bug")
private val TEST_CONTEXT = BugReportContext("test-brand", BugReportPlatform.ANDROID, "1", "test", "test", 1)
