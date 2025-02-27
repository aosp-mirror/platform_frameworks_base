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

package com.android.systemui.shade.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.display.ShadeDisplayPolicy
import com.android.systemui.shade.display.SpecificDisplayIdPolicy
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeDisplaysRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val defaultPolicy = SpecificDisplayIdPolicy(0)

    private val shadeDisplaysRepository =
        ShadeDisplaysRepositoryImpl(defaultPolicy, testScope.backgroundScope)

    @Test
    fun policy_changing_propagatedFromTheLatestPolicy() =
        testScope.runTest {
            val displayIds by collectValues(shadeDisplaysRepository.displayId)
            val policy1 = MutablePolicy()
            val policy2 = MutablePolicy()

            assertThat(displayIds).containsExactly(0)

            shadeDisplaysRepository.policy.value = policy1

            policy1.sendDisplayId(1)

            assertThat(displayIds).containsExactly(0, 1)

            policy1.sendDisplayId(2)

            assertThat(displayIds).containsExactly(0, 1, 2)

            shadeDisplaysRepository.policy.value = policy2

            assertThat(displayIds).containsExactly(0, 1, 2, 0)

            policy1.sendDisplayId(4)

            // Changes to the first policy don't affect the output now
            assertThat(displayIds).containsExactly(0, 1, 2, 0)

            policy2.sendDisplayId(5)

            assertThat(displayIds).containsExactly(0, 1, 2, 0, 5)
        }

    private class MutablePolicy : ShadeDisplayPolicy {
        fun sendDisplayId(id: Int) {
            _displayId.value = id
        }

        private val _displayId = MutableStateFlow(0)
        override val name: String
            get() = "mutable_policy"

        override val displayId: StateFlow<Int>
            get() = _displayId
    }
}
