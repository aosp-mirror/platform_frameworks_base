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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.pm.UserInfo
import android.service.trust.TrustAgentService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.TrustGrantFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.TrustModel
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardDismissInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private val underTest = kosmos.keyguardDismissInteractor
    private val userInfo = UserInfo(0, "", 0)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)

        kosmos.fakeUserRepository.setUserInfos(listOf(userInfo))
    }

    @Test
    fun biometricAuthenticatedRequestDismissKeyguard_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)

            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun onTrustGrantedRequestDismissKeyguard_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            kosmos.fakeTrustRepository.setRequestDismissKeyguard(
                TrustModel(
                    true,
                    0,
                    TrustGrantFlags(0),
                )
            )
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            kosmos.fakePowerRepository.setInteractive(true)
            kosmos.fakeTrustRepository.setRequestDismissKeyguard(
                TrustModel(
                    true,
                    0,
                    TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD),
                )
            )
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun primaryAuthenticated_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            kosmos.fakeUserRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            // authenticated different user
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedPrimaryAuth(22)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            // authenticated correct user
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedPrimaryAuth(userInfo.id)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun userRequestedBouncerWhenAlreadyAuthenticated_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            kosmos.fakeUserRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            // requested from different user
            kosmos.fakeKeyguardBouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(22)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            // requested from correct user
            kosmos.fakeKeyguardBouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
                userInfo.id
            )
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun keyguardDone() =
        testScope.runTest {
            val keyguardDone by collectLastValue(underTest.keyguardDone)
            assertThat(keyguardDone).isNull()

            underTest.setKeyguardDone(KeyguardDone.IMMEDIATE)
            assertThat(keyguardDone).isEqualTo(KeyguardDone.IMMEDIATE)

            underTest.setKeyguardDone(KeyguardDone.LATER)
            assertThat(keyguardDone).isEqualTo(KeyguardDone.LATER)
        }

    @Test
    fun userRequestedBouncerWhenAlreadyAuthenticated_dismissActionRunImmediately() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            val dismissKeyguardRequestWithImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithImmediateDismissAction)
            kosmos.fakeUserRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            kosmos.fakeKeyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            )
            kosmos.fakeKeyguardBouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
                userInfo.id
            )
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()
            assertThat(dismissKeyguardRequestWithImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun userRequestedBouncerWhenAlreadyAuthenticated_dismissActionRunAfterKeyguardGone() =
        testScope.runTest {
            val dismissKeyguardRequestWithImmediateWithoutDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            val dismissKeyguardRequestWithImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithImmediateDismissAction)
            kosmos.fakeUserRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            kosmos.fakeKeyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = {},
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            )
            kosmos.fakeKeyguardBouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
                userInfo.id
            )
            assertThat(dismissKeyguardRequestWithImmediateDismissAction).isNull()
            assertThat(dismissKeyguardRequestWithImmediateWithoutDismissAction).isEqualTo(Unit)
        }
}
