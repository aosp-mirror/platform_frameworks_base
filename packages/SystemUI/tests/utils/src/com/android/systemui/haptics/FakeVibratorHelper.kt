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

package com.android.systemui.haptics

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock

/** A fake [VibratorHelper] that only keeps track of the latest vibration effects delivered */
@SuppressLint("VisibleForTests")
class FakeVibratorHelper : VibratorHelper(EmptyVibrator(), FakeExecutor(FakeSystemClock())) {

    /** A customizable map of primitive ids and their durations in ms */
    val primitiveDurations: HashMap<Int, Int> = ALL_PRIMITIVE_DURATIONS

    private val vibrationEffectHistory = ArrayList<VibrationEffect>()

    val totalVibrations: Int
        get() = vibrationEffectHistory.size

    override fun vibrate(effect: VibrationEffect) {
        vibrationEffectHistory.add(effect)
    }

    override fun vibrate(effect: VibrationEffect, attributes: VibrationAttributes) = vibrate(effect)

    override fun vibrate(effect: VibrationEffect, attributes: AudioAttributes) = vibrate(effect)

    override fun vibrate(
        uid: Int,
        opPkg: String?,
        vibe: VibrationEffect,
        reason: String?,
        attributes: VibrationAttributes,
    ) = vibrate(vibe)

    override fun getPrimitiveDurations(vararg primitiveIds: Int): IntArray =
        primitiveIds.map { primitiveDurations[it] ?: 0 }.toIntArray()

    fun hasVibratedWithEffects(vararg effects: VibrationEffect): Boolean =
        vibrationEffectHistory.containsAll(effects.toList())

    fun timesVibratedWithEffect(effect: VibrationEffect): Int =
        vibrationEffectHistory.count { it == effect }

    companion object {
        val ALL_PRIMITIVE_DURATIONS =
            hashMapOf(
                VibrationEffect.Composition.PRIMITIVE_NOOP to 0,
                VibrationEffect.Composition.PRIMITIVE_CLICK to 12,
                VibrationEffect.Composition.PRIMITIVE_THUD to 300,
                VibrationEffect.Composition.PRIMITIVE_SPIN to 133,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 150,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE to 500,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL to 100,
                VibrationEffect.Composition.PRIMITIVE_TICK to 5,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 12,
            )
    }
}
