package com.dgtang.debugtools.shake

import kotlinx.coroutines.flow.Flow

/** 宿主决定准入、用户开关和导航；调用须在主线程，关闭后不能再次注册传感器。 */
interface ShakeDetector {
    /** 有界、无 replay 的事件；沿用宿主现有生命周期收集，不另建常驻 scope。 */
    val shakes: Flow<Unit>
    fun start(): ShakeStartResult
    fun stop()
    fun close()
}

enum class ShakeStartResult { STARTED, SENSOR_UNAVAILABLE, REGISTRATION_FAILED, CLOSED }

/** 两端旧实现使用相同阈值与冷却；停用不会重置冷却，避免快速启停造成重复导航。 */
internal class ShakeTrigger {
    private var lastTriggerMillis = 0L

    fun detect(forceG: Double, nowMillis: Long): Boolean {
        if (forceG < 2.7 || !forceG.isFinite()) return false
        if (nowMillis - lastTriggerMillis < 1_200L) return false
        lastTriggerMillis = nowMillis
        return true
    }
}
