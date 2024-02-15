/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.test.silkfx.materials

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.android.test.silkfx.R
import kotlin.math.sin
import kotlin.math.sqrt

class GlassView(context: Context, attributeSet: AttributeSet) : FrameLayout(context, attributeSet) {

    private val textureTranslationMultiplier = 200f

    private var gyroXRotation = 0f
    private var gyroYRotation = 0f

    private var noise = BitmapFactory.decodeResource(resources, R.drawable.noise)
    private var materialPaint = Paint()
    private var scrimPaint = Paint()
    private var noisePaint = Paint()
    private var blurPaint = Paint()

    private val src = Rect()
    private val dst = Rect()

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensorListener = object : SensorEventListener {

        // Constant to convert nanoseconds to seconds.
        private val NS2S = 1.0f / 1000000000.0f
        private val EPSILON = 0.000001f
        private var timestamp: Float = 0f

        override fun onSensorChanged(event: SensorEvent?) {
            // This timestep's delta rotation to be multiplied by the current rotation
            // after computing it from the gyro sample data.
            if (timestamp != 0f && event != null) {
                val dT = (event.timestamp - timestamp) * NS2S
                // Axis of the rotation sample, not normalized yet.
                var axisX: Float = event.values[0]
                var axisY: Float = event.values[1]
                var axisZ: Float = event.values[2]

                // Calculate the angular speed of the sample
                val omegaMagnitude: Float = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)

                // Normalize the rotation vector if it's big enough to get the axis
                // (that is, EPSILON should represent your maximum allowable margin of error)
                if (omegaMagnitude > EPSILON) {
                    axisX /= omegaMagnitude
                    axisY /= omegaMagnitude
                    axisZ /= omegaMagnitude
                }

                // Integrate around this axis with the angular speed by the timestep
                // in order to get a delta rotation from this sample over the timestep
                // We will convert this axis-angle representation of the delta rotation
                // into a quaternion before turning it into the rotation matrix.
                val thetaOverTwo: Float = omegaMagnitude * dT / 2.0f
                val sinThetaOverTwo: Float = sin(thetaOverTwo)
                gyroXRotation += sinThetaOverTwo * axisX
                gyroYRotation += sinThetaOverTwo * axisY

                invalidate()
            }
            timestamp = event?.timestamp?.toFloat() ?: 0f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
    }

    var backgroundBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    set(value) {
        field = value
        invalidate()
    }

    var noiseOpacity = 0.0f
    set(value) {
        field = value
        noisePaint.alpha = (value * 255).toInt()
        invalidate()
    }

    var materialOpacity = 0.0f
    set(value) {
        field = value
        materialPaint.alpha = (value * 255).toInt()
        invalidate()
    }

    var scrimOpacity = 0.5f
        set(value) {
            field = value
            scrimPaint.alpha = (value * 255).toInt()
            invalidate()
        }

    var zoom = 0.0f
        set(value) {
            field = value
            invalidate()
        }

    var color = Color.BLACK
    set(value) {
        field = value
        var alpha = materialPaint.alpha
        materialPaint.color = color
        materialPaint.alpha = alpha

        alpha = scrimPaint.alpha
        scrimPaint.color = color
        scrimPaint.alpha = alpha
        invalidate()
    }

    var blurRadius = 150f
    set(value) {
        field = value
        renderNode.setRenderEffect(
                RenderEffect.createBlurEffect(value, value, Shader.TileMode.CLAMP))
        invalidate()
    }

    private var renderNodeIsDirty = true
    private val renderNode = RenderNode("GlassRenderNode")

    override fun invalidate() {
        renderNodeIsDirty = true
        super.invalidate()
    }

    init {
        setWillNotDraw(false)
        materialPaint.blendMode = BlendMode.SOFT_LIGHT
        noisePaint.blendMode = BlendMode.SOFT_LIGHT
        noisePaint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scrimPaint.alpha = (scrimOpacity * 255).toInt()
        noisePaint.alpha = (noiseOpacity * 255).toInt()
        materialPaint.alpha = (materialOpacity * 255).toInt()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View?, outline: Outline?) {
                outline?.setRoundRect(Rect(0, 0, width, height), 100f)
            }
        }
        clipToOutline = true
    }

    override fun onAttachedToWindow() {
        sensorManager?.getSensorList(Sensor.TYPE_GYROSCOPE)?.firstOrNull().let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onDraw(canvas: Canvas) {
        updateGlassRenderNode()
        canvas.drawRenderNode(renderNode)
    }

    fun resetGyroOffsets() {
        gyroXRotation = 0f
        gyroYRotation = 0f
        invalidate()
    }

    private fun updateGlassRenderNode() {
        if (renderNodeIsDirty) {
            renderNode.setPosition(0, 0, getWidth(), getHeight())

            val canvas = renderNode.beginRecording()

            src.set(-width / 2, -height / 2, width / 2, height / 2)
            src.scale(1.0f + zoom)
            val centerX = left + width / 2
            val centerY = top + height / 2
            val textureXOffset = (textureTranslationMultiplier * gyroYRotation).toInt()
            val textureYOffset = (textureTranslationMultiplier * gyroXRotation).toInt()
            src.set(src.left + centerX + textureXOffset, src.top + centerY + textureYOffset,
                    src.right + centerX + textureXOffset, src.bottom + centerY + textureYOffset)

            dst.set(0, 0, width, height)
            canvas.drawBitmap(backgroundBitmap, src, dst, blurPaint)
            canvas.drawRect(dst, materialPaint)
            canvas.drawRect(dst, noisePaint)
            canvas.drawRect(dst, scrimPaint)

            renderNode.endRecording()

            renderNodeIsDirty = false
        }
    }
}
