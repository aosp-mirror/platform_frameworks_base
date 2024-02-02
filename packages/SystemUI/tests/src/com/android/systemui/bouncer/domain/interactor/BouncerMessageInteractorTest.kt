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

package com.android.systemui.bouncer.domain.interactor

import android.content.pm.UserInfo
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.PIN
import com.android.systemui.R.string.kg_too_many_failed_attempts_countdown
import com.android.systemui.R.string.kg_unlock_with_pin_or_fp
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.factory.BouncerMessageFactory
import com.android.systemui.bouncer.data.repository.FakeBouncerMessageRepository
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.bouncer.shared.model.Message
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class BouncerMessageInteractorTest : SysuiTestCase() {

    @Mock private lateinit var securityModel: KeyguardSecurityModel
    @Mock private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    @Mock private lateinit var countDownTimerUtil: CountDownTimerUtil
    private lateinit var countDownTimerCallback: KotlinArgumentCaptor<CountDownTimerCallback>
    private lateinit var underTest: BouncerMessageInteractor
    private lateinit var repository: FakeBouncerMessageRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var testScope: TestScope
    private lateinit var bouncerMessage: FlowValue<BouncerMessageModel?>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        repository = FakeBouncerMessageRepository()
        userRepository = FakeUserRepository()
        userRepository.setUserInfos(listOf(PRIMARY_USER))
        testScope = TestScope()
        countDownTimerCallback = KotlinArgumentCaptor(CountDownTimerCallback::class.java)
        biometricSettingsRepository = FakeBiometricSettingsRepository()

        allowTestableLooperAsMainThread()
        whenever(securityModel.getSecurityMode(PRIMARY_USER_ID)).thenReturn(PIN)
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
    }

    suspend fun TestScope.init() {
        userRepository.setSelectedUserInfo(PRIMARY_USER)
        val featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.REVAMPED_BOUNCER_MESSAGES, true)
        underTest =
            BouncerMessageInteractor(
                repository = repository,
                factory = BouncerMessageFactory(biometricSettingsRepository, securityModel),
                userRepository = userRepository,
                countDownTimerUtil = countDownTimerUtil,
                featureFlags = featureFlags
            )
        bouncerMessage = collectLastValue(underTest.bouncerMessage)
    }

    @Test
    fun onIncorrectSecurityInput_setsTheBouncerModelInTheRepository() =
        testScope.runTest {
            init()
            underTest.onPrimaryAuthIncorrectAttempt()

            assertThat(repository.primaryAuthMessage).isNotNull()
            assertThat(
                    context.resources.getString(
                        repository.primaryAuthMessage.value!!.message!!.messageResId!!
                    )
                )
                .isEqualTo("Wrong PIN. Try again.")
        }

    @Test
    fun onUserStartsPrimaryAuthInput_clearsAllSetBouncerMessages() =
        testScope.runTest {
            init()
            repository.setCustomMessage(message("not empty"))
            repository.setFaceAcquisitionMessage(message("not empty"))
            repository.setFingerprintAcquisitionMessage(message("not empty"))
            repository.setPrimaryAuthMessage(message("not empty"))

            underTest.onPrimaryBouncerUserInput()

            assertThat(repository.customMessage.value).isNull()
            assertThat(repository.faceAcquisitionMessage.value).isNull()
            assertThat(repository.fingerprintAcquisitionMessage.value).isNull()
            assertThat(repository.primaryAuthMessage.value).isNull()
        }

    @Test
    fun onBouncerBeingHidden_clearsAllSetBouncerMessages() =
        testScope.runTest {
            init()
            repository.setCustomMessage(message("not empty"))
            repository.setFaceAcquisitionMessage(message("not empty"))
            repository.setFingerprintAcquisitionMessage(message("not empty"))
            repository.setPrimaryAuthMessage(message("not empty"))

            underTest.onBouncerBeingHidden()

            assertThat(repository.customMessage.value).isNull()
            assertThat(repository.faceAcquisitionMessage.value).isNull()
            assertThat(repository.fingerprintAcquisitionMessage.value).isNull()
            assertThat(repository.primaryAuthMessage.value).isNull()
        }

    @Test
    fun setCustomMessage_setsRepositoryValue() =
        testScope.runTest {
            init()

            underTest.setCustomMessage("not empty")

            val customMessage = repository.customMessage
            assertThat(customMessage.value!!.message!!.messageResId)
                .isEqualTo(kg_unlock_with_pin_or_fp)
            assertThat(customMessage.value!!.secondaryMessage!!.message).isEqualTo("not empty")

            underTest.setCustomMessage(null)
            assertThat(customMessage.value).isNull()
        }

    @Test
    fun setFaceMessage_setsRepositoryValue() =
        testScope.runTest {
            init()

            underTest.setFaceAcquisitionMessage("not empty")

            val faceAcquisitionMessage = repository.faceAcquisitionMessage

            assertThat(faceAcquisitionMessage.value!!.message!!.messageResId)
                .isEqualTo(kg_unlock_with_pin_or_fp)
            assertThat(faceAcquisitionMessage.value!!.secondaryMessage!!.message)
                .isEqualTo("not empty")

            underTest.setFaceAcquisitionMessage(null)
            assertThat(faceAcquisitionMessage.value).isNull()
        }

    @Test
    fun setFingerprintMessage_setsRepositoryValue() =
        testScope.runTest {
            init()

            underTest.setFingerprintAcquisitionMessage("not empty")

            val fingerprintAcquisitionMessage = repository.fingerprintAcquisitionMessage

            assertThat(fingerprintAcquisitionMessage.value!!.message!!.messageResId)
                .isEqualTo(kg_unlock_with_pin_or_fp)
            assertThat(fingerprintAcquisitionMessage.value!!.secondaryMessage!!.message)
                .isEqualTo("not empty")

            underTest.setFingerprintAcquisitionMessage(null)
            assertThat(fingerprintAcquisitionMessage.value).isNull()
        }

    @Test
    fun onPrimaryAuthLockout_startsTimerForSpecifiedNumberOfSeconds() =
        testScope.runTest {
            init()

            underTest.onPrimaryAuthLockedOut(3)

            verify(countDownTimerUtil)
                .startNewTimer(eq(3000L), eq(1000L), countDownTimerCallback.capture())

            countDownTimerCallback.value.onTick(2000L)

            val primaryMessage = repository.primaryAuthMessage.value!!.message!!
            assertThat(primaryMessage.messageResId!!)
                .isEqualTo(kg_too_many_failed_attempts_countdown)
            assertThat(primaryMessage.formatterArgs).isEqualTo(mapOf(Pair("count", 2)))
        }

    @Test
    fun onPrimaryAuthLockout_timerComplete_resetsRepositoryMessages() =
        testScope.runTest {
            init()
            repository.setCustomMessage(message("not empty"))
            repository.setFaceAcquisitionMessage(message("not empty"))
            repository.setFingerprintAcquisitionMessage(message("not empty"))
            repository.setPrimaryAuthMessage(message("not empty"))

            underTest.onPrimaryAuthLockedOut(3)

            verify(countDownTimerUtil)
                .startNewTimer(eq(3000L), eq(1000L), countDownTimerCallback.capture())

            countDownTimerCallback.value.onFinish()

            assertThat(repository.customMessage.value).isNull()
            assertThat(repository.faceAcquisitionMessage.value).isNull()
            assertThat(repository.fingerprintAcquisitionMessage.value).isNull()
            assertThat(repository.primaryAuthMessage.value).isNull()
        }

    @Test
    fun bouncerMessage_hasPriorityOrderOfMessages() =
        testScope.runTest {
            init()
            repository.setBiometricAuthMessage(message("biometric message"))
            repository.setFaceAcquisitionMessage(message("face acquisition message"))
            repository.setFingerprintAcquisitionMessage(message("fingerprint acquisition message"))
            repository.setPrimaryAuthMessage(message("primary auth message"))
            repository.setAuthFlagsMessage(message("auth flags message"))
            repository.setBiometricLockedOutMessage(message("biometrics locked out"))
            repository.setCustomMessage(message("custom message"))

            assertThat(bouncerMessage()).isEqualTo(message("primary auth message"))

            repository.setPrimaryAuthMessage(null)

            assertThat(bouncerMessage()).isEqualTo(message("biometric message"))

            repository.setBiometricAuthMessage(null)

            assertThat(bouncerMessage()).isEqualTo(message("fingerprint acquisition message"))

            repository.setFingerprintAcquisitionMessage(null)

            assertThat(bouncerMessage()).isEqualTo(message("face acquisition message"))

            repository.setFaceAcquisitionMessage(null)

            assertThat(bouncerMessage()).isEqualTo(message("custom message"))

            repository.setCustomMessage(null)

            assertThat(bouncerMessage()).isEqualTo(message("auth flags message"))

            repository.setAuthFlagsMessage(null)

            assertThat(bouncerMessage()).isEqualTo(message("biometrics locked out"))

            repository.setBiometricLockedOutMessage(null)

            // sets the default message if everything else is null
            assertThat(bouncerMessage()!!.message!!.messageResId)
                .isEqualTo(kg_unlock_with_pin_or_fp)
        }

    private fun message(value: String): BouncerMessageModel {
        return BouncerMessageModel(message = Message(message = value))
    }

    companion object {
        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER =
            UserInfo(
                /* id= */ PRIMARY_USER_ID,
                /* name= */ "primary user",
                /* flags= */ UserInfo.FLAG_PRIMARY
            )
    }
}
