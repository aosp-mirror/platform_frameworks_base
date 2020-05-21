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

package com.android.systemui.controls.ui

import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK

object Vibrations {
    private const val TOGGLE_TICK_COUNT = 40

    val toggleOnEffect = initToggleOnEffect()
    val toggleOffEffect = initToggleOffEffect()
    val rangeEdgeEffect = initRangeEdgeEffect()
    val rangeMiddleEffect = initRangeMiddleEffect()

    private fun initToggleOnEffect(): VibrationEffect {
        val composition = VibrationEffect.startComposition()
        composition.addPrimitive(PRIMITIVE_TICK, 0.05f, 200)
        var i = 0
        while (i++ < TOGGLE_TICK_COUNT) {
            composition.addPrimitive(PRIMITIVE_TICK, 0.05f, 0)
        }
        composition.addPrimitive(PRIMITIVE_TICK, 0.5f, 100)
        return composition.compose()
    }

    private fun initToggleOffEffect(): VibrationEffect {
        val composition = VibrationEffect.startComposition()
        composition.addPrimitive(PRIMITIVE_TICK, 0.5f, 0)
        composition.addPrimitive(PRIMITIVE_TICK, 0.05f, 100)
        var i = 0
        while (i++ < TOGGLE_TICK_COUNT) {
            composition.addPrimitive(PRIMITIVE_TICK, 0.05f, 0)
        }
        return composition.compose()
    }

    private fun initRangeEdgeEffect(): VibrationEffect {
        val composition = VibrationEffect.startComposition()
        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
        return composition.compose()
    }

    private fun initRangeMiddleEffect(): VibrationEffect {
        val composition = VibrationEffect.startComposition()
        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.1f)
        return composition.compose()
    }
}
