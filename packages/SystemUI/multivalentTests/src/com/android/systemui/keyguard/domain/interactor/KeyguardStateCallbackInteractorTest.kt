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

package com.android.systemui.keyguard.domain.interactor

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.internal.policy.IKeyguardStateCallback
import com.android.keyguard.trustManager
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.domain.interactor.keyguardStateCallbackInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.time.fakeSystemClock
import kotlin.test.Test
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardStateCallbackInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: KeyguardStateCallbackInteractor
    private lateinit var callback: IKeyguardStateCallback
    private lateinit var systemClock: FakeSystemClock

    @Before
    fun setUp() {
        systemClock = kosmos.fakeSystemClock
        systemClock.setCurrentTimeMillis(testScope.currentTime)

        underTest = kosmos.keyguardStateCallbackInteractor
        underTest.start()

        callback = mock<IKeyguardStateCallback>()
    }

    @Test
    fun test_addCallback_passesInitialValues() =
        testScope.runTest {
            underTest.addCallback(callback)

            verify(callback).onShowingStateChanged(anyBoolean(), anyInt())
            verify(callback).onInputRestrictedStateChanged(anyBoolean())
            verify(callback).onTrustedChanged(anyBoolean())
            verify(callback).onSimSecureStateChanged(anyBoolean())
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun test_lockscreenVisibility_notifyDismissSucceeded_ifNotVisible() =
        testScope.runTest {
            underTest.addCallback(callback)

            val dismissCallback = mock<IKeyguardDismissCallback>()
            kosmos.dismissCallbackRegistry.addCallback(dismissCallback)
            runCurrent()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )

            systemClock.advanceTime(1) // Required for DismissCallbackRegistry's bgExecutor
            verify(dismissCallback).onDismissSucceeded()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )

            Mockito.verifyNoMoreInteractions(dismissCallback)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun test_lockscreenVisibility_reportsKeyguardShowingChanged() =
        testScope.runTest {
            underTest.addCallback(callback)

            Mockito.clearInvocations(callback)
            Mockito.clearInvocations(kosmos.trustManager)

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope = testScope,
            )
            runCurrent()

            verify(callback, atLeastOnce()).onShowingStateChanged(eq(false), anyInt())
            verify(kosmos.trustManager, atLeastOnce()).reportKeyguardShowingChanged()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )

            verify(callback, atLeastOnce()).onShowingStateChanged(eq(true), anyInt())
            verify(kosmos.trustManager, atLeast(2)).reportKeyguardShowingChanged()
        }
}
