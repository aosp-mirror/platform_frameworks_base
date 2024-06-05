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

package com.android.systemui.communal.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    private lateinit var underTest: CommunalTransitionViewModel

    @Before
    fun setup() {
        underTest = kosmos.communalTransitionViewModel
    }

    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenLockscreenAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            assertThat(isUmoOnCommunal).isNull()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope
            )
            assertThat(isUmoOnCommunal).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.LOCKSCREEN,
                testScope
            )
            assertThat(isUmoOnCommunal).isFalse()
        }

    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenDreamingAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            assertThat(isUmoOnCommunal).isNull()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DREAMING,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope
            )
            assertThat(isUmoOnCommunal).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.DREAMING,
                testScope
            )
            assertThat(isUmoOnCommunal).isFalse()
        }

    @Test
    fun testIsUmoOnCommunalDuringTransitionBetweenOccludedAndGlanceableHub() =
        testScope.runTest {
            val isUmoOnCommunal by collectLastValue(underTest.isUmoOnCommunal)
            assertThat(isUmoOnCommunal).isNull()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope
            )
            assertThat(isUmoOnCommunal).isTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope
            )

            assertThat(isUmoOnCommunal).isFalse()
        }
}
