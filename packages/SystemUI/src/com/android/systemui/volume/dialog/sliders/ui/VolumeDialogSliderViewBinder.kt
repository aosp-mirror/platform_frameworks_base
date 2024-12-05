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

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderViewModel
import com.android.systemui.volume.dialog.ui.utils.JankListenerFactory
import com.android.systemui.volume.dialog.ui.utils.awaitAnimation
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val PROGRESS_CHANGE_ANIMATION_DURATION_MS = 80L

@VolumeDialogSliderScope
class VolumeDialogSliderViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSliderViewModel,
    private val jankListenerFactory: JankListenerFactory,
) {

    fun CoroutineScope.bind(view: View) {
        val sliderView: Slider =
            view.requireViewById<Slider>(R.id.volume_dialog_slider).apply {
                labelBehavior = LabelFormatter.LABEL_GONE
            }
        sliderView.addOnChangeListener { _, value, fromUser ->
            viewModel.setStreamVolume(value.roundToInt(), fromUser)
        }

        viewModel.model.onEach { it.bindToSlider(sliderView) }.launchIn(this)
    }

    private suspend fun VolumeDialogStreamModel.bindToSlider(slider: Slider) {
        with(slider) {
            valueFrom = levelMin.toFloat()
            valueTo = levelMax.toFloat()
            // coerce the current value to the new value range before animating it
            value = value.coerceIn(valueFrom, valueTo)
            setValueAnimated(
                level.toFloat(),
                jankListenerFactory.update(this, PROGRESS_CHANGE_ANIMATION_DURATION_MS),
            )
        }
    }
}

private suspend fun Slider.setValueAnimated(
    newValue: Float,
    jankListener: Animator.AnimatorListener,
) {
    ObjectAnimator.ofFloat(value, newValue)
        .apply {
            duration = PROGRESS_CHANGE_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addListener(jankListener)
        }
        .awaitAnimation<Float> { value = it }
}
