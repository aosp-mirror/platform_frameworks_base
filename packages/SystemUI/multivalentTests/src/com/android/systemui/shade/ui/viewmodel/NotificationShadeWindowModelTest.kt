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

package com.android.systemui.shade.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationShadeWindowModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val underTest: NotificationShadeWindowModel by lazy {
        kosmos.notificationShadeWindowModel
    }

    @Test
    fun transitionToOccluded() =
        testScope.runTest {
            val isKeyguardOccluded by collectLastValue(underTest.isKeyguardOccluded)
            assertThat(isKeyguardOccluded).isFalse()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope,
            )
            assertThat(isKeyguardOccluded).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.GONE,
                testScope,
            )
            assertThat(isKeyguardOccluded).isFalse()
        }
}
