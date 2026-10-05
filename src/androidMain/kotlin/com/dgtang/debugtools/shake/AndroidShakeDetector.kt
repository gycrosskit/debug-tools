package com.dgtang.debugtools.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.sqrt

/** Main 线程调用启停；使用 applicationContext、2.7g 阈值与 1200ms 冷却，宿主 close 释放监听。 */
class AndroidShakeDetector(context: Context) : ShakeDetector, SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mutableShakes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val shakes = mutableShakes.asSharedFlow()
    private val trigger = ShakeTrigger()
    private var started = false
    private var closed = false

    override fun start(): ShakeStartResult {
        if (closed) return ShakeStartResult.CLOSED
        if (started) return ShakeStartResult.STARTED
        val manager = sensorManager ?: return ShakeStartResult.SENSOR_UNAVAILABLE
        val sensor = accelerometer ?: return ShakeStartResult.SENSOR_UNAVAILABLE
        started = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        return if (started) ShakeStartResult.STARTED else ShakeStartResult.REGISTRATION_FAILED
    }

    override fun stop() {
        started = false
        sensorManager?.unregisterListener(this)
    }

    override fun close() {
        closed = true
        stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        if (trigger.detect(sqrt(x * x + y * y + z * z).toDouble(), SystemClock.elapsedRealtime())) {
            mutableShakes.tryEmit(Unit)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
