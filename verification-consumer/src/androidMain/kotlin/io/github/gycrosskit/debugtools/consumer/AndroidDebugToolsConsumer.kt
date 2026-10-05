package io.github.gycrosskit.debugtools.consumer

import android.content.Context
import com.dgtang.debugtools.bugreport.AndroidBugReportStore
import com.dgtang.debugtools.bugreport.BugReportStore
import com.dgtang.debugtools.shake.AndroidShakeDetector
import com.dgtang.debugtools.shake.ShakeDetector
import kotlinx.serialization.json.Json

/** 只编译公开平台 API；实际命名空间由宿主注入，样例不访问生产凭据。 */
class AndroidDebugToolsConsumer(context: Context, json: Json, namespace: String, keyAlias: String) {
    val store: BugReportStore = AndroidBugReportStore(context, json, namespace, keyAlias)
    val shakeDetector: ShakeDetector = AndroidShakeDetector(context)
}


fun consumeLegacyViewModel(repository: com.dgtang.debugtools.bugreport.BugReportRepository,
    host: com.dgtang.debugtools.bugreport.BugReportHostDataSource): androidx.lifecycle.ViewModel =
    com.dgtang.debugtools.bugreport.BugReportViewModel(repository, host)
