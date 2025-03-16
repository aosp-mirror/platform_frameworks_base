/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.repository

import com.android.systemui.statusbar.LightRevealEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fake implementation of [LightRevealScrimRepository] */
class FakeLightRevealScrimRepository : LightRevealScrimRepository {

    private val _revealEffect: MutableStateFlow<LightRevealEffect> =
        MutableStateFlow(DEFAULT_REVEAL_EFFECT)
    override val revealEffect = _revealEffect

    fun setRevealEffect(effect: LightRevealEffect) {
        _revealEffect.tryEmit(effect)
    }

    private val _revealAmount: MutableStateFlow<Float> = MutableStateFlow(0.0f)
    override val revealAmount: Flow<Float> = _revealAmount

    val revealAnimatorRequests: MutableList<RevealAnimatorRequest> = arrayListOf()

    override val isAnimating: Boolean
        get() = false

    override val maxAlpha: MutableStateFlow<Float> = MutableStateFlow(1f)

    override fun startRevealAmountAnimator(reveal: Boolean, duration: Long) {
        if (reveal) {
            _revealAmount.value = 1.0f
        } else {
            _revealAmount.value = 0.0f
        }

        revealAnimatorRequests.add(RevealAnimatorRequest(reveal, duration))
    }

    data class RevealAnimatorRequest(val reveal: Boolean, val duration: Long)
}
