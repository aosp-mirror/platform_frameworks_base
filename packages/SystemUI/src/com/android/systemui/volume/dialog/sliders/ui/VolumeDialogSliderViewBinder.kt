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

import android.annotation.SuppressLint
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.google.android.material.slider.Slider
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogSliderScope
class VolumeDialogSliderViewBinder
@Inject
constructor(private val viewModel: VolumeDialogSliderViewModel) {

    private val sliderValueProperty =
        object : FloatPropertyCompat<Slider>("value") {
            override fun getValue(slider: Slider): Float = slider.value

            override fun setValue(slider: Slider, value: Float) {
                slider.value = value
            }
        }
    private val springForce =
        SpringForce().apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }

    fun CoroutineScope.bind(view: View) {
        var isInitialUpdate = true
        val sliderView: Slider = view.requireViewById(R.id.volume_dialog_slider)
        val animation = SpringAnimation(sliderView, sliderValueProperty)
        animation.spring = springForce

        sliderView.addOnChangeListener { _, value, fromUser ->
            viewModel.setStreamVolume(value.roundToInt(), fromUser)
        }

        viewModel.state
            .onEach {
                sliderView.setModel(it, animation, isInitialUpdate)
                isInitialUpdate = false
            }
            .launchIn(this)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun Slider.setModel(
        model: VolumeDialogSliderStateModel,
        animation: SpringAnimation,
        isInitialUpdate: Boolean,
    ) {
        valueFrom = model.minValue
        animation.setMinValue(model.minValue)
        valueTo = model.maxValue
        animation.setMaxValue(model.maxValue)
        // coerce the current value to the new value range before animating it. This prevents
        // animating from the value that is outside of current [valueFrom, valueTo].
        value = value.coerceIn(valueFrom, valueTo)
        trackIconActiveStart = model.icon
        if (isInitialUpdate) {
            value = model.value
        } else {
            animation.animateToFinalPosition(model.value)
        }
    }
}
