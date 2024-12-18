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

package com.android.systemui.deviceentry.domain.interactor

import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.face.FaceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FaceHelpMessageDebouncer.Companion.DEFAULT_WINDOW_MS
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.shared.model.AcquiredFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryFaceAuthStatusInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope: TestScope = kosmos.testScope
    private lateinit var underTest: DeviceEntryFaceAuthStatusInteractor
    private val ignoreHelpMessageId = 1

    @Before
    fun setup() {
        overrideResource(
            R.array.config_face_acquire_device_entry_ignorelist,
            intArrayOf(ignoreHelpMessageId)
        )
        underTest = kosmos.deviceEntryFaceAuthStatusInteractor
    }

    @Test
    fun successAuthenticationStatus() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            val successStatus =
                SuccessFaceAuthenticationStatus(
                    successResult = mock(FaceManager.AuthenticationResult::class.java)
                )
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(successStatus)
            assertThat(authenticationStatus).isEqualTo(successStatus)
        }

    @Test
    fun acquiredFaceAuthenticationStatus() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            val acquiredStatus = AcquiredFaceAuthenticationStatus(acquiredInfo = 0)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(acquiredStatus)
            assertThat(authenticationStatus).isEqualTo(acquiredStatus)
        }

    @Test
    fun failedFaceAuthenticationStatus() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            val failedStatus = FailedFaceAuthenticationStatus()
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(failedStatus)
            assertThat(authenticationStatus).isEqualTo(failedStatus)
        }

    @Test
    fun errorFaceAuthenticationStatus() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            val errorStatus = ErrorFaceAuthenticationStatus(0, "test")
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(errorStatus)
            assertThat(authenticationStatus).isEqualTo(errorStatus)
        }

    @Test
    fun firstHelpFaceAuthenticationStatus_noUpdate() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                AcquiredFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ACQUIRED_START,
                    createdAt = 0
                )
            )
            val helpMessage = HelpFaceAuthenticationStatus(0, "test", 1)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(helpMessage)
            assertThat(authenticationStatus).isNull()
        }

    @Test
    fun helpFaceAuthenticationStatus_afterWindow() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(0, "test1", 0)
            )
            runCurrent()
            val helpMessage = HelpFaceAuthenticationStatus(0, "test2", DEFAULT_WINDOW_MS)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(helpMessage)
            runCurrent()
            assertThat(authenticationStatus).isEqualTo(helpMessage)
        }

    @Test
    fun helpFaceAuthenticationStatus_onlyIgnoredHelpMessages_afterWindow() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(ignoreHelpMessageId, "ignoredMsg", 0)
            )
            runCurrent()
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(ignoreHelpMessageId, "ignoredMsg", DEFAULT_WINDOW_MS)
            )
            runCurrent()
            assertThat(authenticationStatus).isNull()
        }

    @Test
    fun helpFaceAuthenticationStatus_afterWindow_onIgnoredMessage_showsOtherMessageInstead() =
        testScope.runTest {
            val authenticationStatus by collectLastValue(underTest.authenticationStatus)
            val validHelpMessage = HelpFaceAuthenticationStatus(0, "validHelpMsg", 0)
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(validHelpMessage)
            runCurrent()
            // help message that should be ignored
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(ignoreHelpMessageId, "ignoredMsg", DEFAULT_WINDOW_MS)
            )
            runCurrent()
            assertThat(authenticationStatus).isEqualTo(validHelpMessage)
        }
}
