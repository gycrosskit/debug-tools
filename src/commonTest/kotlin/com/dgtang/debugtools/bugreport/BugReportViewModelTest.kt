package com.dgtang.debugtools.bugreport

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BugReportViewModelTest {
    @Test
    fun `draft cannot change while submission is pending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = BugViewModelFixture()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.viewModel.state.first { !it.loading }
            fixture.beforeSubmit = { release.await() }
            fixture.viewModel.updateTitle("Submitted title")
            fixture.viewModel.submit()
            fixture.viewModel.state.first { it.working }

            fixture.viewModel.updateTitle("Unsaved edit")
            fixture.viewModel.updateScope(BugReportScope.PLATFORM)
            assertEquals("Submitted title", fixture.viewModel.state.value.draft.title)
            assertEquals(BugReportScope.COMMON, fixture.viewModel.state.value.draft.scope)

            release.complete(Unit)
            fixture.viewModel.state.first { !it.working && it.submittedUrl.isNotBlank() }
            assertEquals(1, fixture.requests)
        } finally {
            release.complete(Unit)
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `history refresh failure preserves successful submission and clears draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = BugViewModelFixture()
        try {
            fixture.viewModel.state.first { !it.loading }
            fixture.store.readHistory = {
                if (fixture.store.historyReads >= 3) error("history unavailable")
                emptyList()
            }
            fixture.viewModel.updateTitle("Test bug")
            fixture.viewModel.submit()
            val state = fixture.viewModel.state.first { !it.working && it.submittedUrl.isNotBlank() }

            assertFalse(state.isError)
            assertEquals("Bug #42 提交成功，本机历史刷新失败", state.message)
            assertEquals("", state.draft.title)
            assertEquals(1, fixture.requests)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `state change during history refresh does not repeat storage read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = BugViewModelFixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.viewModel.state.first { !it.loading }
            fixture.store.readHistory = {
                if (fixture.store.historyReads == 3) {
                    started.complete(Unit)
                    release.await()
                }
                emptyList()
            }
            fixture.viewModel.updateTitle("Test bug")
            fixture.viewModel.submit()
            started.await()
            // 结果先进入状态，历史查询期间仍能看到已经提交成功。
            assertTrue(fixture.viewModel.state.value.submittedUrl.isNotBlank())
            fixture.viewModel.openHistory()
            release.complete(Unit)
            fixture.viewModel.state.first { !it.working }

            assertEquals(3, fixture.store.historyReads)
            assertEquals(BugReportSection.HISTORY, fixture.viewModel.state.value.section)
            assertFalse(fixture.viewModel.state.value.isError)
        } finally {
            release.complete(Unit)
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelled initial refresh is not converted into an error message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = BugViewModelFixture().apply {
            store.readHistory = { throw CancellationException("cancelled") }
        }
        try {
            runCurrent()
            assertFalse(fixture.viewModel.state.value.isError)
            assertEquals("", fixture.viewModel.state.value.message)
        } finally {
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}

private class BugViewModelFixture {
    val store = ViewModelBugStore()
    var requests = 0
    var beforeSubmit: suspend () -> Unit = {}
    private val http = HttpClient(MockEngine {
        requests += 1
        beforeSubmit()
        respond("""{"id":42}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val viewModels = ViewModelStore()
    private val repository = BugReportRepository(
        ZentaoBugClient(
            http,
            BugReportTarget.Configured(
                "https://example.test/tokens", "https://example.test/api", "https://example.test", 1, 1, "trunk",
            ),
        ),
        store,
    )
    val viewModel = ViewModelProvider.create(
        store = viewModels,
        factory = viewModelFactory {
            initializer {
                BugReportViewModel(repository, object : BugReportHostDataSource {
                    override suspend fun captureEvidence() = BugEvidenceSnapshot()
                    override suspend fun captureContext() =
                        BugReportContext("test-brand", BugReportPlatform.ANDROID, "1", "test", "test", 1)
                })
            }
        },
    )[BugReportViewModel::class]

    fun close() {
        viewModels.clear()
        http.close()
    }
}

private class ViewModelBugStore : BugReportStore {
    var historyReads = 0
    var readHistory: suspend () -> List<BugSubmissionRecord> = { emptyList() }
    override suspend fun readToken() = "test-token"
    override suspend fun writeToken(value: String) = Unit
    override suspend fun readShakeEnabled() = false
    override suspend fun writeShakeEnabled(value: Boolean) = Unit
    override suspend fun readHistory(): List<BugSubmissionRecord> {
        historyReads += 1
        return readHistory.invoke()
    }
    override suspend fun writeHistory(value: List<BugSubmissionRecord>) = Unit
    override suspend fun clearCredentials() = Unit
}
