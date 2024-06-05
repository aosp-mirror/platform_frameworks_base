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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.TrustModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardDismissInteractorTest : SysuiTestCase() {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private lateinit var underTestDependencies: KeyguardDismissInteractorFactory.WithDependencies
    private lateinit var underTest: KeyguardDismissInteractor
    private val userInfo = UserInfo(0, "", 0)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)

        underTestDependencies =
            KeyguardDismissInteractorFactory.create(
                context = context,
                testScope = testScope,
            )
        underTest = underTestDependencies.interactor
        underTestDependencies.userRepository.setUserInfos(listOf(userInfo))
    }

    @Test
    fun biometricAuthenticatedRequestDismissKeyguard_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)

            underTestDependencies.bouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            underTestDependencies.bouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun onTrustGrantedRequestDismissKeyguard_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            underTestDependencies.trustRepository.setRequestDismissKeyguard(
                TrustModel(
                    true,
                    0,
                    TrustGrantFlags(0),
                )
            )
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            underTestDependencies.powerRepository.setInteractive(true)
            underTestDependencies.trustRepository.setRequestDismissKeyguard(
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
            underTestDependencies.userRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            // authenticated different user
            underTestDependencies.bouncerRepository.setKeyguardAuthenticatedPrimaryAuth(22)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            // authenticated correct user
            underTestDependencies.bouncerRepository.setKeyguardAuthenticatedPrimaryAuth(userInfo.id)
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isEqualTo(Unit)
        }

    @Test
    fun userRequestedBouncerWhenAlreadyAuthenticated_noDismissAction() =
        testScope.runTest {
            val dismissKeyguardRequestWithoutImmediateDismissAction by
                collectLastValue(underTest.dismissKeyguardRequestWithoutImmediateDismissAction)
            underTestDependencies.userRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            // requested from different user
            underTestDependencies.bouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
                22
            )
            assertThat(dismissKeyguardRequestWithoutImmediateDismissAction).isNull()

            // requested from correct user
            underTestDependencies.bouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
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
            underTestDependencies.userRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            underTestDependencies.keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            )
            underTestDependencies.bouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
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
            underTestDependencies.userRepository.setSelectedUserInfo(userInfo)
            runCurrent()

            underTestDependencies.keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = {},
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            )
            underTestDependencies.bouncerRepository.setUserRequestedBouncerWhenAlreadyAuthenticated(
                userInfo.id
            )
            assertThat(dismissKeyguardRequestWithImmediateDismissAction).isNull()
            assertThat(dismissKeyguardRequestWithImmediateWithoutDismissAction).isEqualTo(Unit)
        }
}
