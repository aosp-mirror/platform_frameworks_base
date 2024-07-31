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

package com.android.systemui.keyguard.shared.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardStateTest : SysuiTestCase() {

    /**
     * This test makes sure that the result of [deviceIsAwakeInState] are equal for all the states
     * that are obsolete with scene container enabled and UNDEFINED. This means for example that if
     * GONE is transformed to UNDEFINED it makes sure that GONE and UNDEFINED need to have the same
     * value. This assumption is important as with scene container flag enabled call sites will only
     * check the result passing in UNDEFINED.
     *
     * This is true today, but as more states may become obsolete this assumption may not be true
     * anymore and therefore [deviceIsAwakeInState] would need to be rewritten to factor in the
     * scene state.
     */
    @Test
    @EnableSceneContainer
    fun assertUndefinedResultMatchesObsoleteStateResults() {
        for (state in KeyguardState.entries) {
            val isAwakeInSceneContainer =
                KeyguardState.deviceIsAwakeInState(state.mapToSceneContainerState())
            val isAwake = KeyguardState.deviceIsAwakeInState(state)
            assertThat(isAwakeInSceneContainer).isEqualTo(isAwake)
        }
    }
}
