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

import android.content.res.Resources
import android.telephony.PinResult
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.FakeSimBouncerRepository
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor.Companion.INVALID_SUBSCRIPTION_ID
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.mobile.data.repository.mobileConnectionsRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimBouncerInteractorTest : SysuiTestCase() {
    @Mock lateinit var telephonyManager: TelephonyManager
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock lateinit var euiccManager: EuiccManager

    private val kosmos = testKosmos()
    private val bouncerSimRepository = FakeSimBouncerRepository()
    private val resources: Resources = context.resources
    private val testScope = kosmos.testScope

    private lateinit var underTest: SimBouncerInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            SimBouncerInteractor(
                context,
                testScope.backgroundScope,
                kosmos.testDispatcher,
                bouncerSimRepository,
                telephonyManager,
                resources,
                keyguardUpdateMonitor,
                euiccManager,
                kosmos.mobileConnectionsRepository,
            )
    }

    @Test
    fun getDefaultMessage() {
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(
            SubscriptionInfo.Builder().setDisplayName("sim").build()
        )
        whenever(telephonyManager.activeModemCount).thenReturn(1)

        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_sim_pin_instructions))
    }

    @Test
    fun getDefaultMessage_isPuk() {
        bouncerSimRepository.setSimPukLocked(true)
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(
            SubscriptionInfo.Builder().setDisplayName("sim").build()
        )
        whenever(telephonyManager.activeModemCount).thenReturn(1)

        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_puk_enter_puk_hint))
    }

    @Test
    fun getDefaultMessage_isEsimLocked() {
        bouncerSimRepository.setLockedEsim(true)
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(
            SubscriptionInfo.Builder().setDisplayName("sim").build()
        )
        whenever(telephonyManager.activeModemCount).thenReturn(1)

        val msg = resources.getString(R.string.kg_sim_pin_instructions)
        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_sim_lock_esim_instructions, msg))
    }

    @Test
    fun getDefaultMessage_multipleSims() {
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(
            SubscriptionInfo.Builder().setDisplayName("sim").build()
        )
        whenever(telephonyManager.activeModemCount).thenReturn(2)

        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_sim_pin_instructions_multi, "sim"))
    }

    @Test
    fun getDefaultMessage_multipleSims_isPuk() {
        bouncerSimRepository.setSimPukLocked(true)
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(
            SubscriptionInfo.Builder().setDisplayName("sim").build()
        )
        whenever(telephonyManager.activeModemCount).thenReturn(2)

        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_puk_enter_puk_hint_multi, "sim"))
    }

    @Test
    fun getDefaultMessage_multipleSims_emptyDisplayName() {
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(SubscriptionInfo.Builder().build())
        whenever(telephonyManager.activeModemCount).thenReturn(2)

        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_sim_pin_instructions))
    }

    @Test
    fun getDefaultMessage_multipleSims_emptyDisplayName_isPuk() {
        bouncerSimRepository.setSimPukLocked(true)
        bouncerSimRepository.setSubscriptionId(1)
        bouncerSimRepository.setActiveSubscriptionInfo(SubscriptionInfo.Builder().build())
        whenever(telephonyManager.activeModemCount).thenReturn(2)

        assertThat(underTest.getDefaultMessage())
            .isEqualTo(resources.getString(R.string.kg_puk_enter_puk_hint))
    }

    @Test
    fun resetSimPukUserInput() {
        bouncerSimRepository.setSimPukUserInput("00000000", "1234")

        assertThat(bouncerSimRepository.simPukInputModel.enteredSimPuk).isEqualTo("00000000")
        assertThat(bouncerSimRepository.simPukInputModel.enteredSimPin).isEqualTo("1234")

        underTest.resetSimPukUserInput()

        assertThat(bouncerSimRepository.simPukInputModel.enteredSimPuk).isNull()
        assertThat(bouncerSimRepository.simPukInputModel.enteredSimPin).isNull()
    }

    @Test
    fun disableEsim() =
        testScope.runTest {
            val portIndex = 1
            bouncerSimRepository.setActiveSubscriptionInfo(
                SubscriptionInfo.Builder().setPortIndex(portIndex).build()
            )

            underTest.disableEsim()
            runCurrent()

            verify(euiccManager)
                .switchToSubscription(
                    eq(INVALID_SUBSCRIPTION_ID),
                    eq(portIndex),
                    ArgumentMatchers.any()
                )
        }

    @Test
    fun verifySimPin() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)
            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(false)
            whenever(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager)
            whenever(telephonyManager.supplyIccLockPin(anyString()))
                .thenReturn(PinResult(PinResult.PIN_RESULT_TYPE_SUCCESS, 1))

            verifySim(listOf(0, 0, 0, 0))
            assertThat(msg).isNull()

            verify(keyguardUpdateMonitor).reportSimUnlocked(1)
        }

    @Test
    fun verifySimPin_incorrect_oneRemainingAttempt() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)
            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(false)
            whenever(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager)
            whenever(telephonyManager.supplyIccLockPin(anyString()))
                .thenReturn(
                    PinResult(
                        PinResult.PIN_RESULT_TYPE_INCORRECT,
                        1,
                    )
                )
            verifySim(listOf(0, 0, 0, 0))

            assertThat(msg).isNull()
            val errorDialogMessage by collectLastValue(bouncerSimRepository.errorDialogMessage)
            assertThat(errorDialogMessage)
                .isEqualTo(
                    "Enter SIM PIN. You have 1 remaining attempt before you must contact" +
                        " your carrier to unlock your device."
                )
        }

    @Test
    fun verifySimPin_incorrect_threeRemainingAttempts() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)
            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(false)
            whenever(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager)
            whenever(telephonyManager.supplyIccLockPin(anyString()))
                .thenReturn(
                    PinResult(
                        PinResult.PIN_RESULT_TYPE_INCORRECT,
                        3,
                    )
                )

            verifySim(listOf(0, 0, 0, 0))

            assertThat(msg).isEqualTo("Enter SIM PIN. You have 3 remaining attempts.")
        }

    @Test
    fun verifySimPin_notCorrectLength_tooShort() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)
            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(false)

            verifySim(listOf(0))

            assertThat(msg).isEqualTo(resources.getString(R.string.kg_invalid_sim_pin_hint))
        }

    @Test
    fun verifySimPin_notCorrectLength_tooLong() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)

            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(false)

            verifySim(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0))

            assertThat(msg).isEqualTo(resources.getString(R.string.kg_invalid_sim_pin_hint))
        }

    @Test
    fun verifySimPuk() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)
            whenever(telephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(telephonyManager)
            whenever(telephonyManager.supplyIccLockPuk(anyString(), anyString()))
                .thenReturn(PinResult(PinResult.PIN_RESULT_TYPE_SUCCESS, 1))
            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(true)

            verifySim(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0))
            assertThat(msg).isEqualTo(resources.getString(R.string.kg_puk_enter_pin_hint))

            verifySim(listOf(0, 0, 0, 0))
            assertThat(msg).isEqualTo(resources.getString(R.string.kg_enter_confirm_pin_hint))

            verifySim(listOf(0, 0, 0, 0))
            assertThat(msg).isNull()

            runCurrent()
            verify(keyguardUpdateMonitor).reportSimUnlocked(1)
        }

    @Test
    fun verifySimPuk_inputTooShort() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)

            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(true)

            verifySim(listOf(0, 0, 0, 0))
            assertThat(msg).isEqualTo(resources.getString(R.string.kg_invalid_sim_puk_hint))
        }

    @Test
    fun verifySimPuk_pinNotCorrectLength() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)
            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(true)

            verifySim(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0))

            verifySim(listOf(0, 0, 0))

            assertThat(msg).isEqualTo(resources.getString(R.string.kg_invalid_sim_pin_hint))
        }

    @Test
    fun verifySimPuk_confirmedPinDoesNotMatch() =
        testScope.runTest {
            val msg by collectLastValue(underTest.bouncerMessageChanged)

            bouncerSimRepository.setSubscriptionId(1)
            bouncerSimRepository.setSimPukLocked(true)

            verifySim(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0))
            verifySim(listOf(0, 0, 0, 0))

            verifySim(listOf(0, 0, 0, 1))
            assertThat(msg).isEqualTo(resources.getString(R.string.kg_puk_enter_pin_hint))
        }

    private suspend fun TestScope.verifySim(pinDigits: List<Int>) {
        runCurrent()
        underTest.verifySim(pinDigits)
    }

    @Test
    fun onErrorDialogDismissed_clearsErrorDialogMessageInRepository() {
        bouncerSimRepository.setSimVerificationErrorMessage("abc")
        assertThat(bouncerSimRepository.errorDialogMessage.value).isNotNull()

        underTest.onErrorDialogDismissed()

        assertThat(bouncerSimRepository.errorDialogMessage.value).isNull()
    }
}
