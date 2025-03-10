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

package com.android.systemui.volume.dialog.sliders.ui

import android.view.View
import com.android.systemui.haptics.slider.HapticSlider
import com.android.systemui.haptics.slider.HapticSliderPlugin
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.time.SystemClock
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.shared.model.SliderInputEvent
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderInputEventsViewModel
import com.google.android.material.slider.Slider
import com.google.android.msdl.domain.MSDLPlayer
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogSliderScope
class VolumeDialogSliderHapticsViewBinder
@Inject
constructor(
    private val inputEventsViewModel: VolumeDialogSliderInputEventsViewModel,
    private val vibratorHelper: VibratorHelper,
    private val msdlPlayer: MSDLPlayer,
    private val systemClock: SystemClock,
) {

    fun CoroutineScope.bind(view: View) {
        val sliderView = view.requireViewById<Slider>(R.id.volume_dialog_slider)
        val hapticSliderPlugin =
            HapticSliderPlugin(
                slider = HapticSlider.Slider(sliderView),
                vibratorHelper = vibratorHelper,
                msdlPlayer = msdlPlayer,
                systemClock = systemClock,
            )
        hapticSliderPlugin.startInScope(this)

        sliderView.addOnChangeListener { _, value, fromUser ->
            hapticSliderPlugin.onProgressChanged(value.roundToInt(), fromUser)
        }
        sliderView.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {

                override fun onStartTrackingTouch(slider: Slider) {
                    hapticSliderPlugin.onStartTrackingTouch()
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    hapticSliderPlugin.onStopTrackingTouch()
                }
            }
        )

        inputEventsViewModel.event
            .onEach {
                when (it) {
                    is SliderInputEvent.Button -> hapticSliderPlugin.onKeyDown()
                    is SliderInputEvent.Touch -> hapticSliderPlugin.onTouchEvent(it.event)
                }
            }
            .launchIn(this)
    }
}
