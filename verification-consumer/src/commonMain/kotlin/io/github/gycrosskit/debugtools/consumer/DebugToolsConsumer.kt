package io.github.gycrosskit.debugtools.consumer

import androidx.lifecycle.ViewModel
import com.dgtang.debugtools.bugreport.BugReportHostDataSource
import com.dgtang.debugtools.bugreport.BugReportRepository
import com.dgtang.debugtools.bugreport.BugReportStore
import com.dgtang.debugtools.bugreport.BugReportTarget
import com.dgtang.debugtools.bugreport.BugReportUiState
import com.dgtang.debugtools.bugreport.BugReportViewModel
import com.dgtang.debugtools.bugreport.BugSubmissionRecord
import com.dgtang.debugtools.bugreport.ZentaoBugClient
import com.dgtang.debugtools.shake.ShakeDetector
import com.dgtang.debugtools.shake.ShakeStartResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/** 只从 Maven 产物编译公共 API；不加入组件源码或直接声明其传递依赖。 */
class DebugToolsConsumer(
    engineClient: HttpClient,
    target: BugReportTarget,
    store: BugReportStore,
    host: BugReportHostDataSource,
) {
    private val model = BugReportViewModel(BugReportRepository(ZentaoBugClient(engineClient, target), store), host)
    val lifecycleOwner: ViewModel = model
    val state: StateFlow<BugReportUiState> = model.state
    val historySerializer: KSerializer<BugSubmissionRecord> = BugSubmissionRecord.serializer()
}

class ShakeConsumer(private val detector: ShakeDetector) {
    val events: Flow<Unit> = detector.shakes
    fun start(): ShakeStartResult = detector.start()
    fun stop() = detector.stop()
    fun close() = detector.close()
}
