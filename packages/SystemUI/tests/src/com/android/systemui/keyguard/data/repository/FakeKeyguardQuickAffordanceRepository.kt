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
 */

package com.android.systemui.keyguard.data.repository

import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield

/** Fake implementation of [KeyguardQuickAffordanceRepository], for tests. */
class FakeKeyguardQuickAffordanceRepository : KeyguardQuickAffordanceRepository {

    private val modelByPosition =
        mutableMapOf<
                KeyguardQuickAffordancePosition, MutableStateFlow<KeyguardQuickAffordanceModel>>()

    init {
        KeyguardQuickAffordancePosition.values().forEach { value ->
            modelByPosition[value] = MutableStateFlow(KeyguardQuickAffordanceModel.Hidden)
        }
    }

    override fun affordance(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        return modelByPosition.getValue(position)
    }

    suspend fun setModel(
        position: KeyguardQuickAffordancePosition,
        model: KeyguardQuickAffordanceModel
    ) {
        modelByPosition.getValue(position).value = model
        // Yield to allow the test's collection coroutine to "catch up" and collect this value
        // before the test continues to the next line.
        // TODO(b/239834928): once coroutines.test is updated, switch to the approach described in
        // https://developer.android.com/kotlin/flow/test#continuous-collection and remove this.
        yield()
    }
}
