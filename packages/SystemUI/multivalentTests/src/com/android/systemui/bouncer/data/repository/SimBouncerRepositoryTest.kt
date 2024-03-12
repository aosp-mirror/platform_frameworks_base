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

package com.android.systemui.bouncer.data.repository

import android.telephony.TelephonyManager
import android.telephony.euicc.EuiccManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.mobile.util.FakeSubscriptionManagerProxy
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class SimBouncerRepositoryTest : SysuiTestCase() {
    @Mock lateinit var euiccManager: EuiccManager
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val fakeSubscriptionManagerProxy = FakeSubscriptionManagerProxy()
    private val keyguardUpdateMonitorCallbacks = mutableListOf<KeyguardUpdateMonitorCallback>()

    private lateinit var underTest: SimBouncerRepositoryImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(/* testClass = */ this)
        whenever(keyguardUpdateMonitor.registerCallback(any())).thenAnswer {
            val cb = it.arguments[0] as KeyguardUpdateMonitorCallback
            keyguardUpdateMonitorCallbacks.add(cb)
        }
        whenever(keyguardUpdateMonitor.removeCallback(any())).thenAnswer {
            keyguardUpdateMonitorCallbacks.remove(it.arguments[0])
        }
        underTest =
            SimBouncerRepositoryImpl(
                applicationScope = testScope.backgroundScope,
                backgroundDispatcher = dispatcher,
                resources = context.resources,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                subscriptionManager = fakeSubscriptionManagerProxy,
                broadcastDispatcher = fakeBroadcastDispatcher,
                euiccManager = euiccManager,
            )
    }

    @Test
    fun subscriptionId() =
        testScope.runTest {
            val subscriptionId =
                emitSubscriptionIdAndCollectLastValue(underTest.subscriptionId, subId = 2)
            assertThat(subscriptionId).isEqualTo(2)
        }

    @Test
    fun activeSubscriptionInfo() =
        testScope.runTest {
            fakeSubscriptionManagerProxy.setActiveSubscriptionInfo(subId = 2)
            val activeSubscriptionInfo =
                emitSubscriptionIdAndCollectLastValue(underTest.activeSubscriptionInfo, subId = 2)

            assertThat(activeSubscriptionInfo?.subscriptionId).isEqualTo(2)
        }

    @Test
    fun isLockedEsim_initialValue_isNull() =
        testScope.runTest {
            val isLockedEsim by collectLastValue(underTest.isLockedEsim)
            assertThat(isLockedEsim).isNull()
        }

    @Test
    fun isLockedEsim() =
        testScope.runTest {
            whenever(euiccManager.isEnabled).thenReturn(true)
            fakeSubscriptionManagerProxy.setActiveSubscriptionInfo(subId = 2, isEmbedded = true)
            val isLockedEsim =
                emitSubscriptionIdAndCollectLastValue(underTest.isLockedEsim, subId = 2)
            assertThat(isLockedEsim).isTrue()
        }

    @Test
    fun isLockedEsim_notEmbedded() =
        testScope.runTest {
            fakeSubscriptionManagerProxy.setActiveSubscriptionInfo(subId = 2, isEmbedded = false)
            val isLockedEsim =
                emitSubscriptionIdAndCollectLastValue(underTest.isLockedEsim, subId = 2)
            assertThat(isLockedEsim).isFalse()
        }

    @Test
    fun isSimPukLocked() =
        testScope.runTest {
            val isSimPukLocked =
                emitSubscriptionIdAndCollectLastValue(
                    underTest.isSimPukLocked,
                    subId = 2,
                    isSimPuk = true
                )
            assertThat(isSimPukLocked).isTrue()
        }

    @Test
    fun setSimPukUserInput() {
        val pukCode = "00000000"
        val pinCode = "1234"
        underTest.setSimPukUserInput(pukCode, pinCode)
        assertThat(underTest.simPukInputModel.enteredSimPuk).isEqualTo(pukCode)
        assertThat(underTest.simPukInputModel.enteredSimPin).isEqualTo(pinCode)
    }

    @Test
    fun setSimPukUserInput_nullPuk() {
        val pukCode = null
        val pinCode = "1234"
        underTest.setSimPukUserInput(pukCode, pinCode)
        assertThat(underTest.simPukInputModel.enteredSimPuk).isNull()
        assertThat(underTest.simPukInputModel.enteredSimPin).isEqualTo(pinCode)
    }

    @Test
    fun setSimPukUserInput_nullPin() {
        val pukCode = "00000000"
        val pinCode = null
        underTest.setSimPukUserInput(pukCode, pinCode)
        assertThat(underTest.simPukInputModel.enteredSimPuk).isEqualTo(pukCode)
        assertThat(underTest.simPukInputModel.enteredSimPin).isNull()
    }

    @Test
    fun setSimPukUserInput_nullCodes() {
        underTest.setSimPukUserInput()
        assertThat(underTest.simPukInputModel.enteredSimPuk).isNull()
        assertThat(underTest.simPukInputModel.enteredSimPin).isNull()
    }

    @Test
    fun setSimPinVerificationErrorMessage() =
        testScope.runTest {
            val errorMsg = "error"
            underTest.setSimVerificationErrorMessage(errorMsg)
            val msg by collectLastValue(underTest.errorDialogMessage)
            assertThat(msg).isEqualTo(errorMsg)
        }

    /** Emits a new sim card state and collects the last value of the flow argument. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> TestScope.emitSubscriptionIdAndCollectLastValue(
        flow: Flow<T>,
        subId: Int = 1,
        isSimPuk: Boolean = false
    ): T? {
        val value by collectLastValue(flow)
        runCurrent()
        val simState =
            if (isSimPuk) {
                TelephonyManager.SIM_STATE_PUK_REQUIRED
            } else {
                TelephonyManager.SIM_STATE_PIN_REQUIRED
            }
        whenever(keyguardUpdateMonitor.getNextSubIdForState(anyInt())).thenReturn(-1)
        whenever(keyguardUpdateMonitor.getNextSubIdForState(simState)).thenReturn(subId)
        keyguardUpdateMonitorCallbacks.forEach {
            it.onSimStateChanged(subId, /* slotId= */ 0, simState)
        }
        runCurrent()
        return value
    }
}
