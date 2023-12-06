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
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.test.silkfx.R
import com.android.test.silkfx.app.WindowObserver
import java.util.function.Consumer

class ColorModeControls : LinearLayout, WindowObserver {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var window: Window? = null
    private var currentModeDisplay: TextView? = null

    private var desiredRatio = 0.0f

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
            setColorMode(ActivityInfo.COLOR_MODE_HDR10)
        }
    }

    private val hdrSdrListener = Consumer<Display> { display ->
        Log.d("SilkFX", "HDR/SDR changed ${display.hdrSdrRatio}")
        post {
            updateModeInfoDisplay()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        desiredRatio = window?.desiredHdrHeadroom ?: 0.0f
        val hdrVis = if (display.isHdrSdrRatioAvailable) {
            display.registerHdrSdrRatioChangedListener({ it.run() }, hdrSdrListener)
            View.VISIBLE
        } else {
            View.GONE
        }
        findViewById<Button>(R.id.mode_hdr)!!.visibility = hdrVis
        findViewById<Button>(R.id.mode_hdr10)!!.visibility = hdrVis
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        display.unregisterHdrSdrRatioChangedListener(hdrSdrListener)
    }

    private fun setColorMode(newMode: Int) {
        if (newMode == ActivityInfo.COLOR_MODE_HDR &&
                window!!.colorMode == ActivityInfo.COLOR_MODE_HDR) {
            desiredRatio = (desiredRatio + 1) % 5.0f
            window!!.desiredHdrHeadroom = desiredRatio
        }
        window!!.colorMode = newMode
        updateModeInfoDisplay()
    }

    override fun setWindow(window: Window) {
        this.window = window
    }

    private fun updateModeInfoDisplay() {
        val sdrHdrRatio = display?.hdrSdrRatio ?: 1.0f
        val colormode = window!!.colorMode
        currentModeDisplay?.run {
            text = "Current Mode: " + when (colormode) {
                ActivityInfo.COLOR_MODE_DEFAULT -> "Default/SRGB"
                ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT -> "Wide Gamut"
                ActivityInfo.COLOR_MODE_HDR -> "HDR (sdr/hdr ratio $sdrHdrRatio)"
                ActivityInfo.COLOR_MODE_HDR10 -> "HDR10 (sdr/hdr ratio $sdrHdrRatio)"
                else -> "Unknown"
            }
        }
    }
}
