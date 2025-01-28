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

package com.android.systemui.volume.dialog.sliders.ui.viewmodel

import android.content.Context
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSliderInputEventsInteractor
import com.android.systemui.volume.dialog.sliders.shared.model.SliderInputEvent
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transform

@VolumeDialogSliderScope
class VolumeDialogOverscrollViewModel
@Inject
constructor(
    context: Context,
    private val inputEventsInteractor: VolumeDialogSliderInputEventsInteractor,
) {

    /**
     * This is the ratio between the pointer distance and the dialog offset. The pointer has to
     * travel this distance for a single point of an offset.
     *
     * When greater than 1 this makes the dialog to follow the touch behind.
     */
    private val offsetToTranslationRatio: Float = 3f
    private val maxDeviation: Float =
        context.resources
            .getDimensionPixelSize(R.dimen.volume_dialog_slider_max_deviation)
            .toFloat()
    private val offsetInterpolator = PathInterpolator(0.15f, 0.00f, 0.20f, 1.00f)

    private val sliderValue = MutableStateFlow<Slider?>(null)

    val overscrollEvent: Flow<OverscrollEventModel> =
        sliderValue
            .filterNotNull()
            .map { slider ->
                when (slider.value) {
                    slider.min -> 1f
                    slider.max -> -1f
                    else -> 0f
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { direction ->
                if (direction == 0f) {
                    flowOf(OverscrollEventModel.Animate(0f))
                } else {
                    overscrollEvents(direction)
                }
            }

    fun setSlider(value: Float, min: Float, max: Float) {
        sliderValue.value = Slider(value = value, min = min, max = max)
    }

    /**
     * Returns a flow that for each another [MotionEvent] it receives maps into a path from the
     * first event.
     *
     * Emits [OverscrollEventModel.Move] that follows the [SliderInputEvent.Touch] from the pointer
     * down position. Emits [OverscrollEventModel.Animate] when the gesture is terminated to create
     * a spring-back effect.
     */
    private fun overscrollEvents(direction: Float): Flow<OverscrollEventModel> {
        var startPosition: Float? = null
        return inputEventsInteractor.event
            .mapNotNull { it as? SliderInputEvent.Touch }
            .transform { touchEvent ->
                // Skip events from inside the slider bounds for the case when the user adjusts
                // slider towards max when the slider is already on max value.
                if (touchEvent is SliderInputEvent.Touch.End) {
                    startPosition = null
                    emit(OverscrollEventModel.Animate(0f))
                    return@transform
                }
                val currentStartPosition = startPosition
                val newPosition: Float = touchEvent.y
                if (currentStartPosition == null) {
                    startPosition = newPosition
                } else {
                    val offset = (newPosition - currentStartPosition) / offsetToTranslationRatio
                    val interpolatedOffset =
                        if (areOfTheSameSign(direction, offset)) {
                            sign(offset) *
                                (maxDeviation *
                                    offsetInterpolator.getInterpolation(
                                        (abs(offset)) / maxDeviation
                                    ))
                        } else {
                            0f
                        }
                    emit(OverscrollEventModel.Move(interpolatedOffset))
                }
            }
    }

    /** Models overscroll event */
    sealed interface OverscrollEventModel {

        /** Notifies the consumed to move by the [touchOffsetPx]. */
        data class Move(val touchOffsetPx: Float) : OverscrollEventModel

        /** Notifies the consume to animate to the [targetOffsetPx]. */
        data class Animate(val targetOffsetPx: Float) : OverscrollEventModel
    }

    private data class Slider(val value: Float, val min: Float, val max: Float)
}

private fun areOfTheSameSign(lhs: Float, rhs: Float): Boolean =
    when {
        lhs < 0 -> rhs < 0
        lhs > 0 -> rhs > 0
        else -> rhs == 0f
    }
