/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.authentication.data.repository

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.function.Function
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class AuthenticationRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var getSecurityMode: Function<Int, KeyguardSecurityModel.SecurityMode>
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val clock = FakeSystemClock()
    private val userRepository = FakeUserRepository()
    private val mobileConnectionsRepository = kosmos.fakeMobileConnectionsRepository

    private lateinit var underTest: AuthenticationRepository

    private var currentSecurityMode: KeyguardSecurityModel.SecurityMode =
        KeyguardSecurityModel.SecurityMode.PIN

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        userRepository.setUserInfos(USER_INFOS)
        runBlocking { userRepository.setSelectedUserInfo(USER_INFOS[0]) }
        whenever(getSecurityMode.apply(anyInt())).thenAnswer { currentSecurityMode }

        underTest =
            AuthenticationRepositoryImpl(
                applicationScope = testScope.backgroundScope,
                backgroundDispatcher = kosmos.testDispatcher,
                clock = clock,
                getSecurityMode = getSecurityMode,
                userRepository = userRepository,
                lockPatternUtils = lockPatternUtils,
                devicePolicyManager = devicePolicyManager,
                broadcastDispatcher = fakeBroadcastDispatcher,
                mobileConnectionsRepository = mobileConnectionsRepository,
            )
    }

    @Test
    fun authenticationMethod() =
        testScope.runTest {
            val authMethod by collectLastValue(underTest.authenticationMethod)
            runCurrent()
            dispatchBroadcast()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(AuthenticationMethodModel.Pin)

            setSecurityModeAndDispatchBroadcast(KeyguardSecurityModel.SecurityMode.Pattern)
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pattern)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.Pattern)

            setSecurityModeAndDispatchBroadcast(KeyguardSecurityModel.SecurityMode.None)
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.None)
            assertThat(underTest.getAuthenticationMethod())
                .isEqualTo(AuthenticationMethodModel.None)

            currentSecurityMode = KeyguardSecurityModel.SecurityMode.SimPin
            mobileConnectionsRepository.isAnySimSecure.value = true
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Sim)
            assertThat(underTest.getAuthenticationMethod()).isEqualTo(AuthenticationMethodModel.Sim)
        }

    @Test
    fun isAutoConfirmFeatureEnabled() =
        testScope.runTest {
            whenever(lockPatternUtils.isAutoPinConfirmEnabled(USER_INFOS[0].id)).thenReturn(true)
            whenever(lockPatternUtils.isAutoPinConfirmEnabled(USER_INFOS[1].id)).thenReturn(false)

            val values by collectValues(underTest.isAutoConfirmFeatureEnabled)
            assertThat(values.first()).isFalse()
            assertThat(values.last()).isTrue()

            userRepository.setSelectedUserInfo(USER_INFOS[1])
            assertThat(values.last()).isFalse()
        }

    @Test
    fun isPatternVisible() =
        testScope.runTest {
            whenever(lockPatternUtils.isVisiblePatternEnabled(USER_INFOS[0].id)).thenReturn(false)
            whenever(lockPatternUtils.isVisiblePatternEnabled(USER_INFOS[1].id)).thenReturn(true)

            val values by collectValues(underTest.isPatternVisible)
            assertThat(values.first()).isTrue()
            assertThat(values.last()).isFalse()

            userRepository.setSelectedUserInfo(USER_INFOS[1])
            assertThat(values.last()).isTrue()
        }

    @Test
    fun isPinEnhancedPrivacyEnabled() =
        testScope.runTest {
            whenever(lockPatternUtils.isPinEnhancedPrivacyEnabled(USER_INFOS[0].id))
                .thenReturn(false)
            whenever(lockPatternUtils.isPinEnhancedPrivacyEnabled(USER_INFOS[1].id))
                .thenReturn(true)

            val values by collectValues(underTest.isPinEnhancedPrivacyEnabled)
            assertThat(values.first()).isTrue()
            assertThat(values.last()).isFalse()

            userRepository.setSelectedUserInfo(USER_INFOS[1])
            assertThat(values.last()).isTrue()
        }

    @Test
    fun lockoutEndTimestamp() =
        testScope.runTest {
            val lockoutEndMs = clock.elapsedRealtime() + 30.seconds.inWholeMilliseconds
            whenever(lockPatternUtils.getLockoutAttemptDeadline(USER_INFOS[0].id))
                .thenReturn(lockoutEndMs)
            whenever(lockPatternUtils.getLockoutAttemptDeadline(USER_INFOS[1].id)).thenReturn(0)

            // Switch to a user who is not locked-out.
            userRepository.setSelectedUserInfo(USER_INFOS[1])
            assertThat(underTest.lockoutEndTimestamp).isNull()

            // Switch back to the locked-out user, verify the timestamp is up-to-date.
            userRepository.setSelectedUserInfo(USER_INFOS[0])
            assertThat(underTest.lockoutEndTimestamp).isEqualTo(lockoutEndMs)

            // After the lockout expires, null is returned.
            clock.setElapsedRealtime(lockoutEndMs)
            assertThat(underTest.lockoutEndTimestamp).isNull()
        }

    @Test
    fun hasLockoutOccurred() =
        testScope.runTest {
            val hasLockoutOccurred by collectLastValue(underTest.hasLockoutOccurred)
            assertThat(hasLockoutOccurred).isFalse()

            underTest.reportLockoutStarted(1000)
            assertThat(hasLockoutOccurred).isTrue()

            clock.setElapsedRealtime(clock.elapsedRealtime() + 60.seconds.inWholeMilliseconds)

            underTest.reportAuthenticationAttempt(isSuccessful = false)
            assertThat(hasLockoutOccurred).isTrue()

            underTest.reportAuthenticationAttempt(isSuccessful = true)
            assertThat(hasLockoutOccurred).isFalse()
        }

    private fun setSecurityModeAndDispatchBroadcast(
        securityMode: KeyguardSecurityModel.SecurityMode,
    ) {
        currentSecurityMode = securityMode
        dispatchBroadcast()
    }

    private fun dispatchBroadcast() {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED)
        )
    }

    companion object {
        private val USER_INFOS =
            listOf(
                UserInfo(
                    /* id= */ 100,
                    /* name= */ "First user",
                    /* flags= */ 0,
                ),
                UserInfo(
                    /* id= */ 101,
                    /* name= */ "Second user",
                    /* flags= */ 0,
                ),
            )
    }
}
