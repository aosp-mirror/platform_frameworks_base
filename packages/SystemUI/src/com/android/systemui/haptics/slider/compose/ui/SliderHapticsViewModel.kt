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

package com.android.systemui.haptics.slider.compose.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderDragVelocityProvider
import com.android.systemui.haptics.slider.SliderEventType
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackProvider
import com.android.systemui.haptics.slider.SliderStateProducer
import com.android.systemui.haptics.slider.SliderStateTracker
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.time.SystemClock
import com.google.android.msdl.domain.MSDLPlayer
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope

class SliderHapticsViewModel
@AssistedInject
constructor(
    @Assisted private val interactionSource: InteractionSource,
    @Assisted private val sliderRange: ClosedFloatingPointRange<Float>,
    @Assisted private val orientation: Orientation,
    @Assisted private val sliderHapticFeedbackConfig: SliderHapticFeedbackConfig,
    @Assisted private val sliderTrackerConfig: SeekableSliderTrackerConfig,
    vibratorHelper: VibratorHelper,
    msdlPlayer: MSDLPlayer,
    systemClock: SystemClock,
) : ExclusiveActivatable() {

    var currentSliderEventType = SliderEventType.NOTHING
        private set

    private val velocityTracker = VelocityTracker()
    private val maxVelocity =
        Velocity(
            sliderHapticFeedbackConfig.maxVelocityToScale,
            sliderHapticFeedbackConfig.maxVelocityToScale,
        )
    private val dragVelocityProvider = SliderDragVelocityProvider {
        val velocity =
            when (orientation) {
                Orientation.Horizontal -> velocityTracker.calculateVelocity(maxVelocity).x
                Orientation.Vertical -> velocityTracker.calculateVelocity(maxVelocity).y
            }
        abs(velocity)
    }

    private var startingProgress = 0f

    // Haptic slider stack of components
    private val sliderStateProducer = SliderStateProducer()
    private val sliderHapticFeedbackProvider =
        SliderHapticFeedbackProvider(
            vibratorHelper,
            msdlPlayer,
            dragVelocityProvider,
            sliderHapticFeedbackConfig,
            systemClock,
        )
    private var sliderTracker: SliderStateTracker? = null

    private var trackerJob: Job? = null

    val isRunning: Boolean
        get() = trackerJob?.isActive == true && sliderTracker?.isTracking == true

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            trackerJob =
                launch("SliderHapticsViewModel#SliderStateTracker") {
                    try {
                        sliderTracker =
                            SliderStateTracker(
                                sliderHapticFeedbackProvider,
                                sliderStateProducer,
                                this,
                                sliderTrackerConfig,
                            )
                        sliderTracker?.startTracking()
                        awaitCancellation()
                    } finally {
                        sliderTracker?.stopTracking()
                        sliderTracker = null
                        velocityTracker.resetTracking()
                    }
                }

            launch("SliderHapticsViewModel#InteractionSource") {
                interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) {
                        currentSliderEventType = SliderEventType.STARTED_TRACKING_TOUCH
                        sliderStateProducer.onStartTracking(true)
                    }
                }
            }
            awaitCancellation()
        }
    }

    /**
     * React to a value change in the slider.
     *
     * @param[value] latest value of the slider inside the [sliderRange] provided to the class
     *   constructor.
     */
    fun onValueChange(value: Float) {
        val normalized = value.normalize()
        when (currentSliderEventType) {
            SliderEventType.NOTHING -> {
                currentSliderEventType = SliderEventType.STARTED_TRACKING_PROGRAM
                startingProgress = normalized
                sliderStateProducer.resetWithProgress(normalized)
                sliderStateProducer.onStartTracking(false)
            }
            SliderEventType.STARTED_TRACKING_TOUCH -> {
                startingProgress = normalized
                currentSliderEventType = SliderEventType.PROGRESS_CHANGE_BY_USER
                sliderStateProducer.onProgressChanged(true, normalized)
            }
            SliderEventType.PROGRESS_CHANGE_BY_USER -> {
                addVelocityDataPoint(value)
                currentSliderEventType = SliderEventType.PROGRESS_CHANGE_BY_USER
                sliderStateProducer.onProgressChanged(true, normalized)
            }
            SliderEventType.STARTED_TRACKING_PROGRAM -> {
                startingProgress = normalized
                currentSliderEventType = SliderEventType.PROGRESS_CHANGE_BY_PROGRAM
                sliderStateProducer.onProgressChanged(false, normalized)
            }
            SliderEventType.PROGRESS_CHANGE_BY_PROGRAM -> {
                addVelocityDataPoint(value)
                currentSliderEventType = SliderEventType.PROGRESS_CHANGE_BY_PROGRAM
                sliderStateProducer.onProgressChanged(false, normalized)
            }
            else -> {}
        }
    }

    fun addVelocityDataPoint(value: Float) {
        val normalized = value.normalize()
        velocityTracker.addPosition(System.currentTimeMillis(), normalized.toOffset())
    }

    fun onValueChangeEnded() {
        when (currentSliderEventType) {
            SliderEventType.STARTED_TRACKING_PROGRAM,
            SliderEventType.PROGRESS_CHANGE_BY_PROGRAM -> sliderStateProducer.onStopTracking(false)
            SliderEventType.STARTED_TRACKING_TOUCH,
            SliderEventType.PROGRESS_CHANGE_BY_USER -> sliderStateProducer.onStopTracking(true)
            else -> {}
        }
        currentSliderEventType = SliderEventType.NOTHING
        velocityTracker.resetTracking()
    }

    private fun ClosedFloatingPointRange<Float>.length(): Float = endInclusive - start

    private fun Float.normalize(): Float =
        ((this - sliderRange.start) / sliderRange.length()).coerceIn(0f, 1f)

    private fun Float.toOffset(): Offset =
        when (orientation) {
            Orientation.Horizontal -> Offset(x = this - startingProgress, y = 0f)
            Orientation.Vertical -> Offset(x = 0f, y = this - startingProgress)
        }

    @AssistedFactory
    interface Factory {
        fun create(
            interactionSource: InteractionSource,
            sliderRange: ClosedFloatingPointRange<Float>,
            orientation: Orientation,
            sliderHapticFeedbackConfig: SliderHapticFeedbackConfig,
            sliderTrackerConfig: SeekableSliderTrackerConfig,
        ): SliderHapticsViewModel
    }
}
