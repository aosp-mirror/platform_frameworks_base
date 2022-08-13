/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig.OnClickedResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield

/**
 * Fake implementation of a quick affordance data source.
 *
 * This class is abstract to force tests to provide extensions of it as the system that references
 * these configs uses each implementation's class type to refer to them.
 */
abstract class FakeKeyguardQuickAffordanceConfig : KeyguardQuickAffordanceConfig {

    var onClickedResult: OnClickedResult = OnClickedResult.Handled

    private val _state =
        MutableStateFlow<KeyguardQuickAffordanceConfig.State>(
            KeyguardQuickAffordanceConfig.State.Hidden
        )
    override val state: Flow<KeyguardQuickAffordanceConfig.State> = _state

    override fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?,
    ): OnClickedResult {
        return onClickedResult
    }

    suspend fun setState(state: KeyguardQuickAffordanceConfig.State) {
        _state.value = state
        // Yield to allow the test's collection coroutine to "catch up" and collect this value
        // before the test continues to the next line.
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection and remove this.
        yield()
    }
}
