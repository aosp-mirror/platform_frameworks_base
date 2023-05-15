/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.binder

import android.os.VibrationEffect
import kotlin.time.Duration.Companion.milliseconds

object KeyguardBottomAreaVibrations {

    val ShakeAnimationDuration = 300.milliseconds
    const val ShakeAnimationCycles = 5f

    private const val SmallVibrationScale = 0.3f
    private const val BigVibrationScale = 0.6f

    val Shake =
        VibrationEffect.startComposition()
            .apply {
                val vibrationDelayMs =
                    (ShakeAnimationDuration.inWholeMilliseconds / (ShakeAnimationCycles * 2))
                    .toInt()

                val vibrationCount = ShakeAnimationCycles.toInt() * 2
                repeat(vibrationCount) {
                    addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        SmallVibrationScale,
                        vibrationDelayMs,
                    )
                }
            }
            .compose()

    val Activated =
        VibrationEffect.startComposition()
            .addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                BigVibrationScale,
                0,
            )
            .addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                0.1f,
                0,
            )
            .compose()

    val Deactivated =
        VibrationEffect.startComposition()
            .addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                BigVibrationScale,
                0,
            )
            .addPrimitive(
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                0.1f,
                0,
            )
            .compose()
}
