package com.android.systemui.unfold.updates.hinge

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Trace
import androidx.core.util.Consumer
import java.util.concurrent.Executor

internal class HingeSensorAngleProvider(
    private val sensorManager: SensorManager,
    private val executor: Executor
) :
    HingeAngleProvider {

    private val sensorListener = HingeAngleSensorListener()
    private val listeners: MutableList<Consumer<Float>> = arrayListOf()

    override fun start() = executor.execute {
        Trace.beginSection("HingeSensorAngleProvider#start")
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        sensorManager.registerListener(
            sensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        Trace.endSection()
    }

    override fun stop() = executor.execute {
        sensorManager.unregisterListener(sensorListener)
    }

    override fun removeCallback(listener: Consumer<Float>) {
        listeners.remove(listener)
    }

    override fun addCallback(listener: Consumer<Float>) {
        listeners.add(listener)
    }

    private inner class HingeAngleSensorListener : SensorEventListener {

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            listeners.forEach { it.accept(event.values[0]) }
        }
    }
}
