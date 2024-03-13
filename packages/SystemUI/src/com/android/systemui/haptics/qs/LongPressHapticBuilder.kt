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

package com.android.systemui.haptics.qs

import android.os.VibrationEffect
import android.util.Log
import kotlin.math.max

object LongPressHapticBuilder {

    const val INVALID_DURATION = 0 /* in ms */

    private const val TAG = "LongPressHapticBuilder"
    private const val SPIN_SCALE = 0.2f
    private const val CLICK_SCALE = 0.5f
    private const val LOW_TICK_SCALE = 0.08f
    private const val WARMUP_TIME = 75 /* in ms */
    private const val DAMPING_TIME = 24 /* in ms */

    /** Create the signal that indicates that a long-press action is available. */
    fun createLongPressHint(
        lowTickDuration: Int,
        spinDuration: Int,
        effectDuration: Int
    ): VibrationEffect? {
        if (lowTickDuration == 0 || spinDuration == 0) {
            Log.d(
                TAG,
                "The LOW_TICK and/or SPIN primitives are not supported. No signal created.",
            )
            return null
        }
        if (effectDuration < WARMUP_TIME + spinDuration + DAMPING_TIME) {
            Log.d(
                TAG,
                "Cannot fit long-press hint signal in the effect duration. No signal created",
            )
            return null
        }

        val nLowTicks = WARMUP_TIME / lowTickDuration
        val rampDownLowTicks = DAMPING_TIME / lowTickDuration
        val composition = VibrationEffect.startComposition()

        // Warmup low ticks
        repeat(nLowTicks) {
            composition.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                LOW_TICK_SCALE,
                0,
            )
        }

        // Spin effect
        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, SPIN_SCALE, 0)

        // Damping low ticks
        repeat(rampDownLowTicks) { i ->
            composition.addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                LOW_TICK_SCALE / (i + 1),
                0,
            )
        }

        return composition.compose()
    }

    /** Create a "snapping" effect that triggers at the end of a long-press gesture */
    fun createSnapEffect(): VibrationEffect? =
        VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, CLICK_SCALE, 0)
            .compose()

    /** Creates a signal that indicates the reversal of the long-press animation. */
    fun createReversedEffect(
        pausedProgress: Float,
        lowTickDuration: Int,
        effectDuration: Int,
    ): VibrationEffect? {
        val duration = pausedProgress * effectDuration
        if (duration == 0f) return null

        if (lowTickDuration == 0) {
            Log.d(TAG, "Cannot play reverse haptics because LOW_TICK is not supported")
            return null
        }

        val nLowTicks = (duration / lowTickDuration).toInt()
        if (nLowTicks == 0) return null

        val composition = VibrationEffect.startComposition()
        var scale: Float
        val step = LOW_TICK_SCALE / nLowTicks
        repeat(nLowTicks) { i ->
            scale = max(LOW_TICK_SCALE - step * i, 0f)
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, 0)
        }
        return composition.compose()
    }
}
