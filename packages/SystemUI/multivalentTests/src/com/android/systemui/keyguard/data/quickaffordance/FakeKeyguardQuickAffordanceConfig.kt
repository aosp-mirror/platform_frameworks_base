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

package com.android.systemui.keyguard.data.quickaffordance

import com.android.systemui.animation.Expandable
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.OnTriggeredResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield

/** Fake implementation of a quick affordance data source. */
class FakeKeyguardQuickAffordanceConfig(
    override val key: String,
    private val pickerName: String = key,
    override val pickerIconResourceId: Int = 0,
) : KeyguardQuickAffordanceConfig {

    var onTriggeredResult: OnTriggeredResult = OnTriggeredResult.Handled

    private val _lockScreenState =
        MutableStateFlow<KeyguardQuickAffordanceConfig.LockScreenState>(
            KeyguardQuickAffordanceConfig.LockScreenState.Hidden
        )
    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> =
        _lockScreenState

    override fun pickerName(): String = pickerName

    override fun onTriggered(
        expandable: Expandable?,
    ): OnTriggeredResult {
        return onTriggeredResult
    }

    suspend fun setState(lockScreenState: KeyguardQuickAffordanceConfig.LockScreenState) {
        _lockScreenState.value = lockScreenState
        // Yield to allow the test's collection coroutine to "catch up" and collect this value
        // before the test continues to the next line.
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection and remove this.
        yield()
    }
}
