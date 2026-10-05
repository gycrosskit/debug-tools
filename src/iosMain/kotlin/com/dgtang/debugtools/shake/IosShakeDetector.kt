package com.dgtang.debugtools.shake

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import kotlin.math.sqrt

/** Main 线程启停并接收 CoreMotion；100ms 采样、2.7g 阈值、1200ms 冷却，close 释放注册。 */
@OptIn(ExperimentalForeignApi::class)
class IosShakeDetector : ShakeDetector {
    private val motionManager = CMMotionManager()
    private val mutableShakes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val shakes = mutableShakes.asSharedFlow()
    private val trigger = ShakeTrigger()
    private var started = false
    private var closed = false
    private var registration = 0L

    override fun start(): ShakeStartResult {
        if (closed) return ShakeStartResult.CLOSED
        if (started) return ShakeStartResult.STARTED
        if (!motionManager.accelerometerAvailable) return ShakeStartResult.SENSOR_UNAVAILABLE
        started = true
        val currentRegistration = ++registration
        motionManager.accelerometerUpdateInterval = 0.1
        motionManager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            // stop 后已经进入主队列的回调仍可能到达；不让失效事件触发宿主导航。
            if (started && registration == currentRegistration && data != null) {
                val force = data.acceleration.useContents { sqrt(x * x + y * y + z * z) }
                if (trigger.detect(force, (NSDate().timeIntervalSince1970 * 1_000).toLong())) {
                    mutableShakes.tryEmit(Unit)
                }
            }
        }
        return ShakeStartResult.STARTED
    }

    override fun stop() {
        started = false
        registration++
        motionManager.stopAccelerometerUpdates()
    }

    override fun close() {
        closed = true
        stop()
    }
}
