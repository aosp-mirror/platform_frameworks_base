/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.ui.view

import android.content.Context
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R

class TimerView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val configTracker = ConfigurationTracker(resources)

    private lateinit var icon: ImageView
    private lateinit var label: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var pausedTimeRemaining: TextView
    lateinit var mainButton: TimerButtonView
        private set

    lateinit var altButton: TimerButtonView
        private set

    lateinit var resetButton: TimerButtonView
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = requireViewById(R.id.icon)
        label = requireViewById(R.id.label)
        chronometer = requireViewById(R.id.chronoRemaining)
        pausedTimeRemaining = requireViewById(R.id.pausedTimeRemaining)
        mainButton = requireViewById(R.id.mainButton)
        altButton = requireViewById(R.id.altButton)
        resetButton = requireViewById(R.id.resetButton)
    }

    /** the resources configuration has changed such that the view needs to be reinflated */
    fun isReinflateNeeded(): Boolean = configTracker.hasUnhandledConfigChange()

    fun setIcon(icon: Icon?) {
        this.icon.setImageIcon(icon)
    }

    fun setLabel(label: String) {
        this.label.text = label
    }

    fun setPausedTime(pausedTime: String?) {
        if (pausedTime != null) {
            pausedTimeRemaining.text = pausedTime
            pausedTimeRemaining.isVisible = true
        } else {
            pausedTimeRemaining.isVisible = false
        }
    }

    fun setCountdownTime(countdownTimeMs: Long?) {
        if (countdownTimeMs != null) {
            chronometer.base =
                countdownTimeMs - System.currentTimeMillis() + SystemClock.elapsedRealtime()
            chronometer.isVisible = true
            chronometer.start()
        } else {
            chronometer.isVisible = false
            chronometer.stop()
        }
    }
}
