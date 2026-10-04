package io.github.gycrosskit.debugtools.consumer

import com.dgtang.debugtools.bugreport.BugReportStore
import com.dgtang.debugtools.bugreport.IosBugReportStore
import com.dgtang.debugtools.shake.IosShakeDetector
import com.dgtang.debugtools.shake.ShakeDetector
import kotlinx.serialization.json.Json

class IosDebugToolsConsumer(json: Json, service: String, account: String, namespace: String) {
    val store: BugReportStore = IosBugReportStore(json, service, account, namespace)
    val shakeDetector: ShakeDetector = IosShakeDetector()
}
