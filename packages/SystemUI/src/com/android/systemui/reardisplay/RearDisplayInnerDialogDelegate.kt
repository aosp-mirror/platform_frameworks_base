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

package com.android.systemui.reardisplay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.widget.SeekBar
import com.android.systemui.haptics.slider.HapticSlider
import com.android.systemui.haptics.slider.HapticSliderPlugin
import com.android.systemui.haptics.slider.HapticSliderViewBinder
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.time.SystemClock
import com.google.android.msdl.domain.MSDLPlayer
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * A {@link com.android.systemui.statusbar.phone.SystemUIDialog.Delegate} providing a dialog which
 * lets the user know that the Rear Display Mode is active, and that the content has moved to the
 * outer display.
 */
class RearDisplayInnerDialogDelegate
@AssistedInject
internal constructor(
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @Assisted private val rearDisplayContext: Context,
    private val vibratorHelper: VibratorHelper,
    private val msdlPlayer: MSDLPlayer,
    private val systemClock: SystemClock,
    @Assisted private val onCanceledRunnable: Runnable,
) : SystemUIDialog.Delegate {

    private val sliderHapticFeedbackConfig =
        SliderHapticFeedbackConfig(
            /* velocityInterpolatorFactor = */ 1f,
            /* progressInterpolatorFactor = */ 1f,
            /* progressBasedDragMinScale = */ 0f,
            /* progressBasedDragMaxScale = */ 0.2f,
            /* additionalVelocityMaxBump = */ 0.25f,
            /* deltaMillisForDragInterval = */ 0f,
            /* deltaProgressForDragThreshold = */ 0.05f,
            /* numberOfLowTicks = */ 5,
            /* maxVelocityToScale = */ 200f,
            /* velocityAxis = */ MotionEvent.AXIS_X,
            /* upperBookendScale = */ 1f,
            /* lowerBookendScale = */ 0.05f,
            /* exponent = */ 1f / 0.89f,
            /* sliderStepSize = */ 0f,
        )

    private val sliderTrackerConfig =
        SeekableSliderTrackerConfig(
            /* waitTimeMillis = */ 100,
            /* jumpThreshold = */ 0.02f,
            /* lowerBookendThreshold = */ 0.01f,
            /* upperBookendThreshold = */ 0.99f,
        )

    @AssistedFactory
    interface Factory {
        fun create(
            rearDisplayContext: Context,
            onCanceledRunnable: Runnable,
        ): RearDisplayInnerDialogDelegate
    }

    override fun createDialog(): SystemUIDialog {
        return systemUIDialogFactory.create(
            this,
            rearDisplayContext,
            false, /* shouldAcsdDismissDialog */
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            setContentView(R.layout.activity_rear_display_enabled)
            setCanceledOnTouchOutside(false)

            requireViewById<SeekBar>(R.id.seekbar).let { it ->
                // Create and bind the HapticSliderPlugin
                val hapticSliderPlugin =
                    HapticSliderPlugin(
                        vibratorHelper,
                        msdlPlayer,
                        systemClock,
                        HapticSlider.SeekBar(it),
                        sliderHapticFeedbackConfig,
                        sliderTrackerConfig,
                    )
                HapticSliderViewBinder.bind(it, hapticSliderPlugin)

                // Send MotionEvents to the plugin, so that it can compute velocity, which is
                // used during the computation of haptic effect
                it.setOnTouchListener { _, motionEvent ->
                    hapticSliderPlugin.onTouchEvent(motionEvent)
                    false
                }

                // Respond to SeekBar events, for both:
                // 1) Deciding if RDM should be terminated, etc, and
                // 2) Sending SeekBar events to the HapticSliderPlugin, so that the events
                //    are also used to compute the haptic effect
                it.setOnSeekBarChangeListener(
                    SeekBarListener(hapticSliderPlugin, onCanceledRunnable)
                )
            }
        }
    }

    class SeekBarListener(
        private val hapticSliderPlugin: HapticSliderPlugin,
        private val onCanceledRunnable: Runnable,
    ) : SeekBar.OnSeekBarChangeListener {

        var lastProgress = 0
        var secondLastProgress = 0

        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            hapticSliderPlugin.onProgressChanged(progress, fromUser)

            // Simple heuristic checking that the user did in fact slide the
            // SeekBar, instead of accidentally touching it at 100%
            if (progress == 100 && lastProgress != 0) {
                onCanceledRunnable.run()
            }

            secondLastProgress = lastProgress
            lastProgress = progress
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
            hapticSliderPlugin.onStartTrackingTouch()
        }

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            hapticSliderPlugin.onStopTrackingTouch()

            // If secondLastProgress is 0, it means the user immediately touched
            // the 100% location. We need two last values, because
            // onStopTrackingTouch is always after onProgressChanged
            if (lastProgress < 100 || secondLastProgress == 0) {
                lastProgress = 0
                secondLastProgress = 0
                seekBar?.progress = 0
            }
        }
    }
}
