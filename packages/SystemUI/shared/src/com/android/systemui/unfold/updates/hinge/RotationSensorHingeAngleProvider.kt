package com.android.systemui.unfold.updates.hinge

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.util.Consumer
import com.android.systemui.shared.recents.utilities.Utilities

/**
 * Temporary hinge angle provider that uses rotation sensor instead.
 * It requires to have the device in a certain position to work correctly
 * (flat to the ground)
 */
internal class RotationSensorHingeAngleProvider(
    private val sensorManager: SensorManager
) : HingeAngleProvider {

    private val sensorListener = HingeAngleSensorListener()
    private val listeners: MutableList<Consumer<Float>> = arrayListOf()

    override fun start() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    override fun stop() {
        sensorManager.unregisterListener(sensorListener)
    }

    override fun removeCallback(listener: Consumer<Float>) {
        listeners.remove(listener)
    }

    override fun addCallback(listener: Consumer<Float>) {
        listeners.add(listener)
    }

    private fun onHingeAngle(angle: Float) {
        listeners.forEach { it.accept(angle) }
    }

    private inner class HingeAngleSensorListener : SensorEventListener {

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }

        override fun onSensorChanged(event: SensorEvent) {
            // Jumbojack sends incorrect sensor reading 1.0f event in the beginning, let's ignore it
            if (event.values[3] == 1.0f) return

            val angleRadians = event.values.convertToAngle()
            val hingeAngleDegrees = Math.toDegrees(angleRadians).toFloat()
            val angle = Utilities.clamp(hingeAngleDegrees, FULLY_CLOSED_DEGREES, FULLY_OPEN_DEGREES)
            onHingeAngle(angle)
        }

        private val rotationMatrix = FloatArray(9)
        private val resultOrientation = FloatArray(9)

        private fun FloatArray.convertToAngle(): Double {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, this)
            SensorManager.getOrientation(rotationMatrix, resultOrientation)
            return resultOrientation[2] + Math.PI
        }
    }
}
