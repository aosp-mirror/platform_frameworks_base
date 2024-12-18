/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.haptics.slider

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.view.VelocityTracker
import android.view.animation.AccelerateInterpolator
import androidx.annotation.FloatRange
import androidx.annotation.VisibleForTesting
import com.android.systemui.Flags
import com.android.systemui.statusbar.VibratorHelper
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.InteractionProperties
import com.google.android.msdl.domain.MSDLPlayer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Listener of slider events that triggers haptic feedback.
 *
 * @property[vibratorHelper] Singleton instance of the [VibratorHelper] to deliver haptics.
 * @property[velocityTracker] Instance of a [VelocityTracker] that tracks slider dragging velocity.
 * @property[config] Configuration parameters for vibration encapsulated as a
 *   [SliderHapticFeedbackConfig].
 * @property[clock] Clock to obtain elapsed real time values.
 */
class SliderHapticFeedbackProvider(
    private val vibratorHelper: VibratorHelper,
    private val msdlPlayer: MSDLPlayer,
    private val velocityProvider: SliderDragVelocityProvider,
    private val config: SliderHapticFeedbackConfig = SliderHapticFeedbackConfig(),
    private val clock: com.android.systemui.util.time.SystemClock,
) : SliderStateListener {

    private val velocityAccelerateInterpolator =
        AccelerateInterpolator(config.velocityInterpolatorFactor)
    private val positionAccelerateInterpolator =
        AccelerateInterpolator(config.progressInterpolatorFactor)
    private var dragTextureLastTime = clock.elapsedRealtime()
    var dragTextureLastProgress = -1f
        private set

    private val lowTickDurationMs =
        vibratorHelper.getPrimitiveDurations(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)[0]
    private var hasVibratedAtLowerBookend = false
    private var hasVibratedAtUpperBookend = false

    /** Time threshold to wait before making new API call. */
    private val thresholdUntilNextDragCallMillis =
        lowTickDurationMs * config.numberOfLowTicks + config.deltaMillisForDragInterval

    /**
     * Vibrate when the handle reaches either bookend with a certain velocity.
     *
     * @param[absoluteVelocity] Velocity of the handle when it reached the bookend.
     */
    private fun vibrateOnEdgeCollision(absoluteVelocity: Float) {
        val powerScale = scaleOnEdgeCollision(absoluteVelocity)
        if (Flags.msdlFeedback()) {
            val properties =
                InteractionProperties.DynamicVibrationScale(
                    powerScale,
                    VIBRATION_ATTRIBUTES_PIPELINING,
                )
            msdlPlayer.playToken(MSDLToken.DRAG_THRESHOLD_INDICATOR_LIMIT, properties)
        } else {
            val vibration =
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, powerScale)
                    .compose()
            vibratorHelper.vibrate(vibration, VIBRATION_ATTRIBUTES_PIPELINING)
        }
    }

    /**
     * Get the velocity-based scale at the bookends
     *
     * @param[absoluteVelocity] Velocity of the handle when it reached the bookend.
     * @return The power scale for the vibration.
     */
    @VisibleForTesting
    fun scaleOnEdgeCollision(absoluteVelocity: Float): Float {
        val velocityInterpolated =
            velocityAccelerateInterpolator.getInterpolation(
                min(absoluteVelocity / config.maxVelocityToScale, 1f)
            )
        val bookendScaleRange = config.upperBookendScale - config.lowerBookendScale
        val bookendsHitScale = bookendScaleRange * velocityInterpolated + config.lowerBookendScale
        return bookendsHitScale.pow(config.exponent)
    }

    /**
     * Create a drag texture vibration based on velocity and slider progress.
     *
     * @param[absoluteVelocity] Absolute velocity of the handle.
     * @param[normalizedSliderProgress] Progress of the slider handled normalized to the range from
     *   0F to 1F (inclusive).
     */
    private fun vibrateDragTexture(
        absoluteVelocity: Float,
        @FloatRange(from = 0.0, to = 1.0) normalizedSliderProgress: Float,
    ) {
        // Check if its time to vibrate
        val currentTime = clock.elapsedRealtime()
        val elapsedSinceLastDrag = currentTime - dragTextureLastTime
        if (elapsedSinceLastDrag < thresholdUntilNextDragCallMillis) return

        val deltaProgress = abs(normalizedSliderProgress - dragTextureLastProgress)
        if (deltaProgress < config.deltaProgressForDragThreshold) return

        // Check if the progress is a discrete step so haptics can be delivered
        if (
            config.sliderStepSize > 0 &&
                !normalizedSliderProgress.isDiscreteStep(config.sliderStepSize)
        ) {
            return
        }

        val powerScale = scaleOnDragTexture(absoluteVelocity, normalizedSliderProgress)

        // Deliver haptic feedback
        when {
            config.sliderStepSize == 0f -> performContinuousSliderDragVibration(powerScale)
            config.sliderStepSize > 0f -> performDiscreteSliderDragVibration(powerScale)
        }
        dragTextureLastTime = currentTime
        dragTextureLastProgress = normalizedSliderProgress
    }

    private fun Float.isDiscreteStep(stepSize: Float, epsilon: Float = 0.001f): Boolean {
        if (stepSize <= 0f) return false
        val division = this / stepSize
        return abs(division - round(division)) < epsilon
    }

    private fun performDiscreteSliderDragVibration(scale: Float) {
        if (Flags.msdlFeedback()) {
            val properties =
                InteractionProperties.DynamicVibrationScale(scale, VIBRATION_ATTRIBUTES_PIPELINING)
            msdlPlayer.playToken(MSDLToken.DRAG_INDICATOR_DISCRETE, properties)
        } else {
            val effect =
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                    .compose()
            vibratorHelper.vibrate(effect, VIBRATION_ATTRIBUTES_PIPELINING)
        }
    }

    private fun performContinuousSliderDragVibration(scale: Float) {
        if (Flags.msdlFeedback()) {
            val properties =
                InteractionProperties.DynamicVibrationScale(scale, VIBRATION_ATTRIBUTES_PIPELINING)
            msdlPlayer.playToken(MSDLToken.DRAG_INDICATOR_CONTINUOUS, properties)
        } else {
            val composition = VibrationEffect.startComposition()
            repeat(config.numberOfLowTicks) {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
            }
            vibratorHelper.vibrate(composition.compose(), VIBRATION_ATTRIBUTES_PIPELINING)
        }
    }

    /**
     * Get the scale of the drag texture vibration.
     *
     * @param[absoluteVelocity] Absolute velocity of the handle.
     * @param[normalizedSliderProgress] Progress of the slider handled normalized to the range from
     *   0F to 1F (inclusive).
     *     @return the scale of the vibration.
     */
    @VisibleForTesting
    fun scaleOnDragTexture(
        absoluteVelocity: Float,
        @FloatRange(from = 0.0, to = 1.0) normalizedSliderProgress: Float,
    ): Float {
        val velocityInterpolated =
            velocityAccelerateInterpolator.getInterpolation(
                min(absoluteVelocity / config.maxVelocityToScale, 1f)
            )

        // Scaling of vibration due to the position of the slider
        val positionScaleRange = config.progressBasedDragMaxScale - config.progressBasedDragMinScale
        val sliderProgressInterpolated =
            positionAccelerateInterpolator.getInterpolation(normalizedSliderProgress)
        val positionBasedScale =
            positionScaleRange * sliderProgressInterpolated + config.progressBasedDragMinScale

        // Scaling bump due to velocity
        val velocityBasedScale = velocityInterpolated * config.additionalVelocityMaxBump

        // Total scale
        val scale = positionBasedScale + velocityBasedScale
        return scale.pow(config.exponent)
    }

    override fun onHandleAcquiredByTouch() {}

    override fun onHandleReleasedFromTouch() {
        dragTextureLastProgress = -1f
    }

    override fun onLowerBookend() {
        if (!hasVibratedAtLowerBookend) {
            vibrateOnEdgeCollision(abs(velocityProvider.getTrackedVelocity()))
            hasVibratedAtLowerBookend = true
        }
    }

    override fun onUpperBookend() {
        if (!hasVibratedAtUpperBookend) {
            vibrateOnEdgeCollision(abs(velocityProvider.getTrackedVelocity()))
            hasVibratedAtUpperBookend = true
        }
    }

    override fun onProgress(@FloatRange(from = 0.0, to = 1.0) progress: Float) {
        vibrateDragTexture(abs(velocityProvider.getTrackedVelocity()), progress)
        hasVibratedAtUpperBookend = false
        hasVibratedAtLowerBookend = false
    }

    override fun onProgressJump(@FloatRange(from = 0.0, to = 1.0) progress: Float) {}

    override fun onSelectAndArrow(@FloatRange(from = 0.0, to = 1.0) progress: Float) {}

    private companion object {
        private val VIBRATION_ATTRIBUTES_PIPELINING =
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .setFlags(VibrationAttributes.FLAG_PIPELINED_EFFECT)
                .build()
    }
}
