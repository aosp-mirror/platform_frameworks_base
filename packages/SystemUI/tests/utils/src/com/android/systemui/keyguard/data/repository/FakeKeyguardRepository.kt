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

import com.android.systemui.common.shared.model.Position
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fake implementation of [KeyguardRepository] */
class FakeKeyguardRepository : KeyguardRepository {

    private val _animateBottomAreaDozingTransitions = MutableStateFlow(false)
    override val animateBottomAreaDozingTransitions: StateFlow<Boolean> =
        _animateBottomAreaDozingTransitions

    private val _bottomAreaAlpha = MutableStateFlow(1f)
    override val bottomAreaAlpha: StateFlow<Float> = _bottomAreaAlpha

    private val _clockPosition = MutableStateFlow(Position(0, 0))
    override val clockPosition: StateFlow<Position> = _clockPosition

    private val _isKeyguardShowing = MutableStateFlow(false)
    override val isKeyguardShowing: Flow<Boolean> = _isKeyguardShowing

    private val _isDozing = MutableStateFlow(false)
    override val isDozing: Flow<Boolean> = _isDozing

    private val _dozeAmount = MutableStateFlow(0f)
    override val dozeAmount: Flow<Float> = _dozeAmount

    override fun setAnimateDozingTransitions(animate: Boolean) {
        _animateBottomAreaDozingTransitions.tryEmit(animate)
    }

    override fun setBottomAreaAlpha(alpha: Float) {
        _bottomAreaAlpha.value = alpha
    }

    override fun setClockPosition(x: Int, y: Int) {
        _clockPosition.value = Position(x, y)
    }

    fun setKeyguardShowing(isShowing: Boolean) {
        _isKeyguardShowing.value = isShowing
    }

    fun setDozing(isDozing: Boolean) {
        _isDozing.value = isDozing
    }

    fun setDozeAmount(dozeAmount: Float) {
        _dozeAmount.value = dozeAmount
    }
}
