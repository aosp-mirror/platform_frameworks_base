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

package com.android.test.silkfx.common

import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceControlHdrLayerInfoListener
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.test.silkfx.R
import com.android.test.silkfx.app.WindowObserver

class ColorModeControls : LinearLayout, WindowObserver {
    private val COLOR_MODE_HDR10 = 3
    private val SDR_WHITE_POINTS = floatArrayOf(200f, 250f, 300f, 350f, 400f, 100f, 150f)

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        displayManager = context.getSystemService(DisplayManager::class.java)!!
        displayId = context.getDisplayId()
        displayToken = SurfaceControl.getInternalDisplayToken()
    }

    private var window: Window? = null
    private var currentModeDisplay: TextView? = null
    private val displayManager: DisplayManager
    private var targetSdrWhitePointIndex = 0
    private var displayId: Int
    private var displayToken: IBinder

    private val whitePoint get() = SDR_WHITE_POINTS[targetSdrWhitePointIndex]

    override fun onFinishInflate() {
        super.onFinishInflate()
        val window = window ?: throw IllegalStateException("Failed to attach window")

        currentModeDisplay = findViewById(R.id.current_mode)!!
        setColorMode(window.colorMode)

        findViewById<Button>(R.id.mode_default)!!.setOnClickListener {
            setColorMode(ActivityInfo.COLOR_MODE_DEFAULT)
        }
        findViewById<Button>(R.id.mode_wide)!!.setOnClickListener {
            setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT)
        }
        findViewById<Button>(R.id.mode_hdr)!!.setOnClickListener {
            setColorMode(ActivityInfo.COLOR_MODE_HDR)
        }
        findViewById<Button>(R.id.mode_hdr10)!!.setOnClickListener {
            setColorMode(COLOR_MODE_HDR10)
        }
    }

    private fun setColorMode(newMode: Int) {
        val window = window!!
        var sdrWhitepointChanged = false
        // Need to do this before setting the colorMode, as setting the colorMode will
        // trigger the attribute change listener
        if (newMode == ActivityInfo.COLOR_MODE_HDR ||
                newMode == COLOR_MODE_HDR10) {
            if (window.colorMode == newMode) {
                targetSdrWhitePointIndex = (targetSdrWhitePointIndex + 1) % SDR_WHITE_POINTS.size
                sdrWhitepointChanged = true
            }
            setBrightness(1.0f)
        } else {
            setBrightness(.4f)
        }
        window.colorMode = newMode
        if (sdrWhitepointChanged) {
            threadedRenderer?.setColorMode(newMode, whitePoint)
        }
        val whitePoint = whitePoint.toInt()
        currentModeDisplay?.run {
            text = "Current Mode: " + when (newMode) {
                ActivityInfo.COLOR_MODE_DEFAULT -> "Default/SRGB"
                ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT -> "Wide Gamut"
                ActivityInfo.COLOR_MODE_HDR -> "HDR (sdr white point $whitePoint)"
                COLOR_MODE_HDR10 -> "HDR10 (sdr white point $whitePoint)"
                else -> "Unknown"
            }
        }
    }

    override fun setWindow(window: Window) {
        this.window = window
    }

    private fun setBrightness(level: Float) {
        // To keep window state in sync
        window?.attributes?.screenBrightness = level
        invalidate()
        // To force an 'immediate' snap to what we want
        // Imperfect, but close enough, synchronization by waiting for frame commit to set the value
        viewTreeObserver.registerFrameCommitCallback {
            try {
                SurfaceControl.setDisplayBrightness(displayToken, level)
                displayManager.setTemporaryBrightness(displayId, level)
            } catch (ex: Exception) {
                // Ignore a permission denied rejection - it doesn't meaningfully change much
            }
        }
    }

    private val listener = object : SurfaceControlHdrLayerInfoListener() {
        override fun onHdrInfoChanged(
            displayToken: IBinder?,
            numberOfHdrLayers: Int,
            maxW: Int,
            maxH: Int,
            flags: Int
        ) {
            Log.d("HDRInfo", "onHdrInfoChanged: numLayer = $numberOfHdrLayers ($maxW x $maxH)" +
                    ", flags = $flags")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        threadedRenderer?.setColorMode(window!!.colorMode, whitePoint)
        listener.register(displayToken)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        listener.unregister(displayToken)
    }
}