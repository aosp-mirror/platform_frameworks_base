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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AccessibilityActionsViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    private lateinit var underTest: AccessibilityActionsViewModel

    @Before
    fun setUp() {
        underTest = kosmos.accessibilityActionsViewModelKosmos
    }

    @Test
    fun isOnKeyguard_isFalse_whenTransitioningAwayFromLockscreen() =
        testScope.runTest {
            val isOnKeyguard by collectLastValue(underTest.isOnKeyguard)

            // Shade not opened.
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            // Transitioning away from lock screen.
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    transitionState = TransitionState.STARTED,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                transitionState = TransitionState.RUNNING,
                value = 0.5f,
            )
            assertThat(isOnKeyguard).isEqualTo(false)

            // Transitioned to bouncer.
            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                transitionState = TransitionState.FINISHED,
                value = 1f,
            )
            assertThat(isOnKeyguard).isEqualTo(false)
        }

    @Test
    fun isOnKeyguard_isFalse_whenTransitioningToLockscreenIsRunning() =
        testScope.runTest {
            val isOnKeyguard by collectLastValue(underTest.isOnKeyguard)

            // Shade is not opened.
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            // Starts transitioning to lock screen.
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            )
            assertThat(isOnKeyguard).isEqualTo(false)

            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.LOCKSCREEN,
                transitionState = TransitionState.RUNNING,
                value = 0.5f,
            )
            assertThat(isOnKeyguard).isEqualTo(false)

            // Transition has finished.
            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.LOCKSCREEN,
                transitionState = TransitionState.FINISHED,
                value = 1f,
            )
            assertThat(isOnKeyguard).isEqualTo(true)
        }

    @Test
    fun isOnKeyguard_isTrue_whenKeyguardStateIsLockscreen_andShadeIsNotOpened() =
        testScope.runTest {
            val isOnKeyguard by collectLastValue(underTest.isOnKeyguard)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            assertThat(isOnKeyguard).isEqualTo(true)
        }

    @Test
    fun isOnKeyguard_isFalse_whenKeyguardStateIsLockscreen_andShadeOpened() =
        testScope.runTest {
            val isOnKeyguard by collectLastValue(underTest.isOnKeyguard)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )
            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)

            assertThat(isOnKeyguard).isEqualTo(false)
        }
}
