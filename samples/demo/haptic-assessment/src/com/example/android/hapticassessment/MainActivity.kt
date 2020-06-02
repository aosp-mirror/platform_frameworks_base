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

package com.example.android.hapticassessment

import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibrationEffect.EFFECT_CLICK
import android.os.Vibrator
import android.view.View
import android.widget.Button

import androidx.appcompat.app.AppCompatActivity

/** App main screen. */
class MainActivity : AppCompatActivity() {

    companion object {

        private const val ONE_SHOT_TIMING = 20L
        private const val ONE_SHOT_AMPLITUDE = 255

        private val WAVEFORM_TIMINGS = longArrayOf(500, 500)
        private val WAVEFORM_AMPLITUDES = intArrayOf(128, 255)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val vibrator = getSystemService(Vibrator::class.java)

        findViewById<Button>(R.id.click_effect_button).setOnClickListener {
            vibrator.vibrate(VibrationEffect.createPredefined(EFFECT_CLICK))
        }

        findViewById<Button>(R.id.oneshot_effect_button).setOnClickListener {
            vibrator.vibrate(VibrationEffect.createOneShot(ONE_SHOT_TIMING, ONE_SHOT_AMPLITUDE))
        }

        findViewById<Button>(R.id.waveform_effect_button).setOnClickListener { view: View ->
            vibrator.vibrate(
                VibrationEffect.createWaveform(WAVEFORM_TIMINGS, WAVEFORM_AMPLITUDES, -1))

            val button = view as Button
            if (vibrator.hasAmplitudeControl()) {
                button.text = getString(R.string.button_3_pass)
                button.setBackgroundColor(Color.GREEN)
                button.setTextColor(Color.BLACK)
            } else {
                button.text = getString(R.string.button_3_fail)
                button.setBackgroundColor(Color.RED)
                button.setTextColor(Color.WHITE)
            }
        }
    }
}
