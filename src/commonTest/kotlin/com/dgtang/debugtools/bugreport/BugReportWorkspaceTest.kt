package com.dgtang.debugtools.bugreport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BugReportWorkspaceTest {
    @Test fun `cancelled create response remains unknown and cannot be retried implicitly`() = runTest {
        WorkspaceFixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            fixture.beforeBug = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            val submitting = launch { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            entered.await()
            submitting.cancel(); submitting.join()
            assertEquals(PendingBugStatus.UNKNOWN, fixture.repository.pending().single().status)
            val error = assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(BugReportMessage.UNKNOWN_RESULT, error.notice.code)
            assertEquals(1, fixture.bugRequests)
        }
    }

    @Test fun `unreadable attachment preserves created bug without claiming an upload was sent`() = runTest {
        WorkspaceFixture(loadAttachment = { error("local file unavailable") }).use { fixture ->
            val result = fixture.repository.submit(DRAFT.copy(attachments = listOf(FILE_A)), CONTEXT, BugEvidenceSnapshot())
            assertEquals(42, result.id)
            assertEquals(listOf(FILE_A), result.failedAttachments)
            val entry = fixture.repository.pending().single()
            assertEquals(PendingBugStatus.CREATED, entry.status)
            assertTrue(entry.uncertainAttachments.isEmpty())
            assertEquals(0, fixture.fileRequests)
            assertEquals(1, fixture.bugRequests)
        }
    }

    @Test fun `known creation survives failed acknowledgement journal without duplicate network write`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.beforeBug = { fixture.store.failPending = true }
            val result = fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot())
            assertEquals(42, result.id)
            assertFalse(result.workspaceSaved)
            assertEquals(PendingBugStatus.UNKNOWN, fixture.store.entries.single().status)
            assertEquals(42, fixture.store.history.single().id)
            assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(1, fixture.bugRequests)
        }
    }
    @Test fun `draft and pending survive reauthorization without sending a report`() = runTest {
        val store = WorkspaceStore()
        WorkspaceFixture(store).use { first ->
            first.repository.saveDraft(DRAFT)
            first.repository.queueDraft(DRAFT)
            first.repository.clearCredentials()
            assertEquals(DRAFT, first.repository.draft())
            assertEquals(1, first.repository.pending().size)
            assertEquals(0, first.bugRequests)
        }
        WorkspaceFixture(store).use { next ->
            assertEquals(DRAFT, next.repository.draft())
            next.repository.authorize("developer", "password")
            assertEquals(0, next.bugRequests)
            assertEquals(DRAFT.reportId, next.repository.pending().single().draft.reportId)
        }
    }

    @Test fun `multipart attaches to known bug and confirmed files are never resent`() = runTest {
        WorkspaceFixture().use { fixture ->
            val draft = DRAFT.copy(attachments = listOf(FILE_A))
            val result = fixture.repository.submit(draft, CONTEXT, BugEvidenceSnapshot())
            assertEquals(42, result.id)
            assertTrue(result.failedAttachments.isEmpty())
            assertTrue(fixture.fileBodies.single().contains("objectType"))
            assertTrue(fixture.fileBodies.single().contains("bug"))
            assertTrue(fixture.fileBodies.single().contains("42"))
            assertTrue(fixture.fileBodies.single().contains("a.txt"))
            val again = fixture.repository.submit(draft.copy(title = "Edited after acknowledgement"), CONTEXT, BugEvidenceSnapshot())
            assertEquals(42, again.id)
            assertEquals(1, fixture.bugRequests)
            assertEquals(1, fixture.fileRequests)
            assertEquals(1, fixture.store.history.size)
            assertTrue(fixture.repository.pending().isEmpty())
        }
    }

    @Test fun `editing an uncertain draft does not bypass manual verification`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.loseCreateResponse = true
            val error = assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(BugReportMessage.UNKNOWN_RESULT, error.notice.code)
            assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT.copy(title = "Changed title"), CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(1, fixture.bugRequests)
            val pending = fixture.repository.pending().single()
            assertEquals(PendingBugStatus.UNKNOWN, pending.status)
            fixture.repository.confirmCreated(pending.id, 42)
            assertEquals(42, fixture.repository.retryAttachments(pending.id).id)
            assertEquals(42, fixture.store.history.single().id)
            assertEquals(1, fixture.bugRequests)
        }
    }

    @Test fun `HTML unauthorized response remains manually recoverable after login`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.rejectAuthorization = true
            val error = assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(BugReportMessage.TOKEN_REQUIRED, error.notice.code)
            assertEquals(PendingBugStatus.READY, fixture.repository.pending().single().status)
            fixture.rejectAuthorization = false
            fixture.repository.authorize("developer", "password")
            assertEquals(1, fixture.bugRequests)
            assertEquals(42, fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()).id)
            assertEquals(2, fixture.bugRequests)
        }
    }

    @Test fun `partial attachments preserve known bug and require verification before retry`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.loseFileResponse = 2
            val draft = DRAFT.copy(attachments = listOf(FILE_A, FILE_B))
            val result = fixture.repository.submit(draft, CONTEXT, BugEvidenceSnapshot())
            assertEquals(42, result.id)
            assertEquals(listOf(FILE_B), result.failedAttachments)
            val pending = fixture.repository.pending().single()
            assertEquals(1, pending.uploadedAttachments.size)
            assertEquals(listOf(FILE_B.id), pending.uncertainAttachments)
            assertFailsWith<BugReportException> { fixture.repository.retryAttachments(pending.id) }
            fixture.loseFileResponse = 0
            val repaired = fixture.repository.retryAttachments(pending.id, setOf(FILE_B.id))
            assertTrue(repaired.failedAttachments.isEmpty())
            assertEquals(1, fixture.bugRequests)
            assertEquals(3, fixture.fileRequests)
            assertEquals(1, fixture.store.history.size)
        }
    }

    @Test fun `unconfirmed local journal prevents every network write`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.store.failPending = true
            assertFailsWith<IllegalStateException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(0, fixture.bugRequests)
            assertEquals(0, fixture.fileRequests)
        }
    }

    @Test fun `cancellation after known creation preserves acknowledgement and prevents repeat`() = runTest {
        WorkspaceFixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.store.beforeHistory = { entered.complete(Unit); release.await() }
            val work = launch { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            entered.await()
            work.cancel()
            release.complete(Unit)
            work.join()
            fixture.store.beforeHistory = {}
            assertEquals(42, fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()).id)
            assertEquals(1, fixture.bugRequests)
            assertEquals(42, fixture.store.history.single().id)
        }
    }

    @Test fun `restored target cannot send report to a different product`() = runTest {
        val store = WorkspaceStore()
        WorkspaceFixture(store).use { it.repository.queueDraft(DRAFT) }
        WorkspaceFixture(store, product = 2).use { fixture ->
            val pending = fixture.repository.pending().single()
            val error = assertFailsWith<BugReportException> { fixture.repository.submitPending(pending.id, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(BugReportMessage.WRONG_DESTINATION, error.notice.code)
            assertEquals(0, fixture.bugRequests)
            val direct = assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            assertEquals(BugReportMessage.WRONG_DESTINATION, direct.notice.code)
            assertEquals(1, store.entries.size)
            assertEquals(0, fixture.bugRequests)
        }
    }

    @Test fun `manual known bug confirmation remains visible in history and controller`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.loseCreateResponse = true
            assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            val controller = BugReportController(backgroundScope, fixture.repository, hostSource())
            controller.state.first { !it.loading }
            controller.confirmCreated(DRAFT.reportId, 42)
            val state = controller.state.first { !it.working && it.submittedUrl.isNotBlank() }
            assertEquals(42, state.history.single().id)
            assertTrue(state.pending.isEmpty())
            assertFalse(state.isError)
            assertEquals(1, fixture.bugRequests)
        }
    }

    @Test fun `manual known bug remains successful when journal cannot save`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.loseCreateResponse = true
            assertFailsWith<BugReportException> { fixture.repository.submit(DRAFT, CONTEXT, BugEvidenceSnapshot()) }
            fixture.store.failPending = true
            val result = fixture.repository.confirmCreated(DRAFT.reportId, 42)
            assertEquals(42, result.id)
            assertFalse(result.workspaceSaved)
            assertEquals(42, fixture.store.history.single().id)
            assertEquals(1, fixture.bugRequests)
        }
    }

    @Test fun `attachment authorization failure preserves created bug and typed cause`() = runTest {
        WorkspaceFixture().use { fixture ->
            fixture.rejectFileAuthorization = true
            val controller = BugReportController(backgroundScope, fixture.repository, hostSource())
            controller.state.first { !it.loading }
            controller.updateTitle("Attachment report")
            controller.updateAttachments(listOf(FILE_A))
            controller.submit()
            val state = controller.state.first { !it.working && it.submittedUrl.isNotBlank() }
            assertFalse(state.isError)
            assertEquals(42, state.notice?.bugId)
            assertEquals(BugReportMessage.TOKEN_REQUIRED, state.attachmentNotice?.code)
            assertTrue(state.message.contains("授权"))
            assertEquals(1, fixture.bugRequests)
        }
    }

    @Test fun `navigation and editing clear both rendered and typed feedback without clearing the draft`() = runTest {
        WorkspaceFixture().use { fixture ->
            val controller = BugReportController(backgroundScope, fixture.repository, hostSource())
            controller.state.first { !it.loading }
            val navigation = listOf(controller::openSettings, controller::openHistory, controller::closeSection, controller::openForm)
            for (navigate in navigation) {
                controller.submit()
                assertEquals(BugReportMessage.TITLE_REQUIRED, controller.state.value.notice?.code)
                navigate()
                val state = controller.state.first { !it.working }
                assertEquals("", state.message)
                assertNull(state.notice)
                assertFalse(state.isError)
            }
            controller.submit()
            controller.updateTitle("New draft")
            assertEquals("New draft", controller.state.value.draft.title)
            assertNull(controller.state.value.notice)
            assertFalse(controller.state.value.isError)
            assertEquals(0, fixture.bugRequests)
        }
    }

    @Test fun `successful creation refreshes pending even when history save or refresh fails`() = runTest {
        for (failAtRead in listOf(2, 3)) {
            WorkspaceFixture().use { fixture ->
                fixture.store.savedDraft = DRAFT
                var reads = 0
                fixture.store.beforeHistory = { if (++reads == failAtRead) error("history unavailable") }
                val controller = BugReportController(backgroundScope, fixture.repository, hostSource())
                controller.state.first { !it.loading }
                controller.queueDraft()
                controller.state.first { !it.working }
                val pending = controller.state.value.pending.single()
                controller.submitPending(pending.id)
                val state = controller.state.first { !it.working && it.submittedUrl.isNotBlank() }
                assertFalse(state.isError)
                assertTrue(state.pending.isEmpty())
                assertEquals(1, fixture.bugRequests)
            }
        }
    }

    @Test fun `controller uses supplied scope and English messages without androidx lifecycle`() = runTest {
        WorkspaceFixture().use { fixture ->
            val controller = BugReportController(backgroundScope, fixture.repository, object : BugReportHostDataSource {
                override suspend fun captureEvidence() = BugEvidenceSnapshot()
                override suspend fun captureContext() = CONTEXT
            }, { it.text(BugReportLanguage.ENGLISH) })
            kotlinx.coroutines.yield()
            controller.submit()
            assertEquals("Enter a bug title", controller.state.value.message)
            assertEquals(BugReportMessage.TITLE_REQUIRED, controller.state.value.notice?.code)
            assertEquals(0, fixture.bugRequests)
        }
    }
}

private fun hostSource() = object : BugReportHostDataSource {
    override suspend fun captureEvidence() = BugEvidenceSnapshot()
    override suspend fun captureContext() = CONTEXT
}

private val DRAFT = BugReportDraft(title = "A reproducible bug")
private val CONTEXT = BugReportContext("test", BugReportPlatform.OHOS, "1", "test", "device", 0)
private val FILE_A = BugReportAttachment("file-a", "a.txt", "text/plain", 3)
private val FILE_B = BugReportAttachment("file-b", "b.txt", "text/plain", 3)

private class WorkspaceStore : BugReportStore, BugReportWorkspaceStore {
    var token = "token"
    var savedDraft = BugReportDraft()
    var entries = emptyList<PendingBugReport>()
    var history = emptyList<BugSubmissionRecord>()
    var failPending = false
    var beforeHistory: suspend () -> Unit = {}
    override suspend fun readToken() = token
    override suspend fun writeToken(value: String) { token = value }
    override suspend fun readShakeEnabled() = true
    override suspend fun writeShakeEnabled(value: Boolean) = Unit
    override suspend fun readHistory(): List<BugSubmissionRecord> { beforeHistory(); return history }
    override suspend fun writeHistory(value: List<BugSubmissionRecord>) { history = value }
    override suspend fun clearCredentials() { token = "" }
    override suspend fun readDraft() = savedDraft
    override suspend fun writeDraft(value: BugReportDraft) { savedDraft = value }
    override suspend fun readPending() = entries
    override suspend fun writePending(value: List<PendingBugReport>) { check(!failPending); entries = value }
}

private class WorkspaceFixture(val store: WorkspaceStore = WorkspaceStore(), product: Int = 1,
    loadAttachment: suspend (BugReportAttachment) -> ByteArray = { "abc".encodeToByteArray() },
) : AutoCloseable {
    var bugRequests = 0
    var fileRequests = 0
    var loseCreateResponse = false
    var beforeBug: suspend () -> Unit = {}
    var rejectAuthorization = false
    var rejectFileAuthorization = false
    var loseFileResponse = 0
    val fileBodies = mutableListOf<String>()
    private val http = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        var status = HttpStatusCode.OK
        val body = when {
            path.endsWith("/tokens") -> """{"token":"issued"}"""
            path.contains("/products/") -> """{"id":1}"""
            path.endsWith("/files") -> {
                fileRequests++
                fileBodies += request.body.toByteArray().decodeToString()
                if (loseFileResponse == fileRequests) error("File acknowledgement lost")
                if (rejectFileAuthorization) { status = HttpStatusCode.Unauthorized; "<html>Unauthorized</html>" }
                else """{"status":"success","id":${70 + fileRequests}}"""
            }
            else -> {
                bugRequests++
                beforeBug()
                if (loseCreateResponse) error("Creation acknowledgement lost")
                if (rejectAuthorization) { status = HttpStatusCode.Unauthorized; "<html>Unauthorized</html>" }
                else """{"id":42}"""
            }
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, if (rejectAuthorization) "text/html" else "application/json"))
    })
    private val client = ZentaoBugClient(http,
        BugReportTarget.Configured("https://example.test/tokens", "https://example.test/api/v2", "https://example.test", product, 1, "trunk", true))
    val repository = BugReportRepository(client, store, loadAttachment = loadAttachment)
    override fun close() { client.close(); http.close() }
}
