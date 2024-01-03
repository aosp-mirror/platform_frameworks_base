/*
 * Copyright (C) 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class AodAlphaViewModelTest : SysuiTestCase() {

    @Mock
    private lateinit var occludedToLockscreenTransitionViewModel:
        OccludedToLockscreenTransitionViewModel

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val occludedToLockscreenAlpha = MutableStateFlow(0f)

    private lateinit var underTest: AodAlphaViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(occludedToLockscreenTransitionViewModel.lockscreenAlpha)
            .thenReturn(occludedToLockscreenAlpha)
        kosmos.occludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel

        underTest = kosmos.aodAlphaViewModel
    }

    @Test
    fun alpha() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OFF,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )

            keyguardRepository.setKeyguardAlpha(0.1f)
            assertThat(alpha).isEqualTo(0.1f)
            keyguardRepository.setKeyguardAlpha(0.5f)
            assertThat(alpha).isEqualTo(0.5f)
            keyguardRepository.setKeyguardAlpha(0.2f)
            assertThat(alpha).isEqualTo(0.2f)
            keyguardRepository.setKeyguardAlpha(0f)
            assertThat(alpha).isEqualTo(0f)
            occludedToLockscreenAlpha.value = 0.8f
            assertThat(alpha).isEqualTo(0.8f)
        }

    @Test
    fun alpha_whenGone_equalsZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.alpha)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )

            keyguardRepository.setKeyguardAlpha(0.1f)
            assertThat(alpha).isEqualTo(0f)
            keyguardRepository.setKeyguardAlpha(0.5f)
            assertThat(alpha).isEqualTo(0f)
            keyguardRepository.setKeyguardAlpha(1f)
            assertThat(alpha).isEqualTo(0f)
        }
}
