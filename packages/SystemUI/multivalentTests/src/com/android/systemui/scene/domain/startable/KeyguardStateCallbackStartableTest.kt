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

package com.android.systemui.scene.domain.startable

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.policy.IKeyguardStateCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class KeyguardStateCallbackStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val underTest = kosmos.keyguardStateCallbackStartable

    @Test
    fun addCallback_hydratesAllWithCurrentState() =
        testScope.runTest {
            val testState = setUpTest()
            val callback = mockCallback()

            underTest.addCallback(callback)
            runCurrent()

            with(testState) {
                val captor = argumentCaptor<Boolean>()
                verify(callback, atLeastOnce()).onShowingStateChanged(captor.capture(), eq(userId))
                assertThat(captor.lastValue).isEqualTo(isKeyguardShowing)
                verify(callback, atLeastOnce()).onInputRestrictedStateChanged(captor.capture())
                assertThat(captor.lastValue).isEqualTo(isInputRestricted)
                verify(callback, atLeastOnce()).onSimSecureStateChanged(captor.capture())
                assertThat(captor.lastValue).isEqualTo(isSimSecure)
                verify(callback, atLeastOnce()).onTrustedChanged(captor.capture())
                assertThat(captor.lastValue).isEqualTo(isTrusted)
            }
        }

    @Test
    fun hydrateKeyguardShowingState() =
        testScope.runTest {
            setUpTest(isKeyguardShowing = true)
            val callback = mockCallback()
            underTest.addCallback(callback)
            runCurrent()
            verify(callback, atLeastOnce()).onShowingStateChanged(eq(true), anyInt())

            unlockDevice()
            runCurrent()

            verify(callback).onShowingStateChanged(eq(false), anyInt())
        }

    @Test
    fun hydrateInputRestrictedState() =
        testScope.runTest {
            setUpTest(isKeyguardShowing = true)
            val callback = mockCallback()
            underTest.addCallback(callback)
            runCurrent()
            val captor = argumentCaptor<Boolean>()
            verify(callback, atLeastOnce()).onInputRestrictedStateChanged(captor.capture())
            assertThat(captor.lastValue).isTrue()

            unlockDevice()
            runCurrent()

            verify(callback, atLeastOnce()).onInputRestrictedStateChanged(captor.capture())
            assertThat(captor.lastValue).isFalse()
        }

    @Test
    fun hydrateSimSecureState() =
        testScope.runTest {
            setUpTest(isSimSecure = false)
            val callback = mockCallback()
            underTest.addCallback(callback)
            runCurrent()
            val captor = argumentCaptor<Boolean>()
            verify(callback, atLeastOnce()).onSimSecureStateChanged(captor.capture())
            assertThat(captor.lastValue).isFalse()

            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            runCurrent()

            verify(callback, atLeastOnce()).onSimSecureStateChanged(captor.capture())
            assertThat(captor.lastValue).isTrue()
        }

    @Test
    fun notifyWhenKeyguardShowingChanged() =
        testScope.runTest {
            setUpTest(isKeyguardShowing = true)
            val callback = mockCallback()
            underTest.addCallback(callback)
            runCurrent()
            assertThat(kosmos.fakeTrustRepository.keyguardShowingChangeEventCount).isEqualTo(1)

            unlockDevice()
            runCurrent()

            assertThat(kosmos.fakeTrustRepository.keyguardShowingChangeEventCount).isEqualTo(2)
        }

    @Test
    fun notifyWhenTrustChanged() =
        testScope.runTest {
            setUpTest(isTrusted = false)
            val callback = mockCallback()
            underTest.addCallback(callback)
            runCurrent()
            val captor = argumentCaptor<Boolean>()
            verify(callback, atLeastOnce()).onTrustedChanged(captor.capture())
            assertThat(captor.lastValue).isFalse()

            kosmos.fakeTrustRepository.setCurrentUserTrusted(true)
            runCurrent()

            verify(callback, atLeastOnce()).onTrustedChanged(captor.capture())
            assertThat(captor.lastValue).isTrue()
        }

    private suspend fun TestScope.setUpTest(
        isKeyguardShowing: Boolean = true,
        userId: Int = selectedUser.id,
        isInputRestricted: Boolean = true,
        isSimSecure: Boolean = false,
        isTrusted: Boolean = false,
    ): TestState {
        val testState =
            TestState(
                isKeyguardShowing = isKeyguardShowing,
                userId = userId,
                isInputRestricted = isInputRestricted,
                isSimSecure = isSimSecure,
                isTrusted = isTrusted,
            )

        if (isKeyguardShowing) {
            lockDevice()
        } else {
            unlockDevice()
        }

        kosmos.fakeUserRepository.setUserInfos(listOf(selectedUser))
        kosmos.fakeUserRepository.setSelectedUserInfo(selectedUser)

        if (isInputRestricted && !isKeyguardShowing) {
            // TODO(b/348644111): add support for mNeedToReshowWhenReenabled
        } else if (!isInputRestricted) {
            assertWithMessage(
                    "If isInputRestricted is false, isKeyguardShowing must also be false!"
                )
                .that(isKeyguardShowing)
                .isFalse()
        }

        kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = isSimSecure

        kosmos.fakeTrustRepository.setCurrentUserTrusted(isTrusted)

        runCurrent()

        underTest.start()

        return testState
    }

    private fun lockDevice() {
        kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Lockscreen))
        kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "")
    }

    private fun unlockDevice() {
        kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        kosmos.setSceneTransition(ObservableTransitionState.Idle(Scenes.Gone))
        kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
    }

    private fun mockCallback(): IKeyguardStateCallback {
        return mock()
    }

    private data class TestState(
        val isKeyguardShowing: Boolean,
        val userId: Int,
        val isInputRestricted: Boolean,
        val isSimSecure: Boolean,
        val isTrusted: Boolean,
    )

    companion object {
        private val selectedUser =
            UserInfo(
                /* id= */ 100,
                /* name= */ "First user",
                /* flags= */ 0,
            )
    }
}
