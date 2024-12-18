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

package com.android.systemui.statusbar

import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import android.telephony.telephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.util.FakeSubscriptionManagerProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class OperatorNameViewControllerTest : SysuiTestCase() {
    private lateinit var underTest: OperatorNameViewController
    private lateinit var airplaneModeInteractor: AirplaneModeInteractor

    private val kosmos = Kosmos()
    private val testScope = TestScope()

    private val view = OperatorNameView(mContext)
    private val javaAdapter = JavaAdapter(testScope.backgroundScope)

    @Mock private lateinit var darkIconDispatcher: DarkIconDispatcher
    @Mock private lateinit var tunerService: TunerService
    private var telephonyManager = kosmos.telephonyManager
    private val keyguardUpdateMonitor = kosmos.keyguardUpdateMonitor
    @Mock private lateinit var carrierConfigTracker: CarrierConfigTracker
    private val subscriptionManagerProxy = FakeSubscriptionManagerProxy()

    private val airplaneModeRepository = FakeAirplaneModeRepository()
    private val connectivityRepository = FakeConnectivityRepository()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        airplaneModeInteractor =
            AirplaneModeInteractor(
                airplaneModeRepository,
                connectivityRepository,
                kosmos.fakeMobileConnectionsRepository,
            )

        underTest =
            OperatorNameViewController.Factory(
                    darkIconDispatcher,
                    tunerService,
                    telephonyManager,
                    keyguardUpdateMonitor,
                    carrierConfigTracker,
                    airplaneModeInteractor,
                    subscriptionManagerProxy,
                    javaAdapter,
                )
                .create(view)
    }

    @Test
    fun updateFromSubInfo_showsCarrieName() =
        testScope.runTest {
            whenever(telephonyManager.isDataCapable).thenReturn(true)

            val mockSubInfo =
                mock<SubscriptionInfo>().also {
                    whenever(it.subscriptionId).thenReturn(1)
                    whenever(it.carrierName).thenReturn("test_carrier")
                }
            whenever(keyguardUpdateMonitor.getSubscriptionInfoForSubId(any()))
                .thenReturn(mockSubInfo)
            whenever(keyguardUpdateMonitor.getSimState(any()))
                .thenReturn(TelephonyManager.SIM_STATE_READY)
            whenever(keyguardUpdateMonitor.getServiceState(any()))
                .thenReturn(ServiceState().also { it.state = ServiceState.STATE_IN_SERVICE })
            subscriptionManagerProxy.defaultDataSubId = 1
            airplaneModeRepository.setIsAirplaneMode(false)

            underTest.onViewAttached()
            runCurrent()

            assertThat(view.text).isEqualTo("test_carrier")
        }

    @Test
    fun notDataCapable_doesNotShowOperatorName() =
        testScope.runTest {
            whenever(telephonyManager.isDataCapable).thenReturn(false)

            val mockSubInfo =
                mock<SubscriptionInfo>().also {
                    whenever(it.subscriptionId).thenReturn(1)
                    whenever(it.carrierName).thenReturn("test_carrier")
                }
            whenever(keyguardUpdateMonitor.getSubscriptionInfoForSubId(any()))
                .thenReturn(mockSubInfo)
            whenever(keyguardUpdateMonitor.getSimState(any()))
                .thenReturn(TelephonyManager.SIM_STATE_READY)
            whenever(keyguardUpdateMonitor.getServiceState(any()))
                .thenReturn(ServiceState().also { it.state = ServiceState.STATE_IN_SERVICE })
            subscriptionManagerProxy.defaultDataSubId = 1
            airplaneModeRepository.setIsAirplaneMode(false)

            underTest.onViewAttached()
            runCurrent()

            assertTrue(view.text.isNullOrEmpty())
        }

    @Test
    fun airplaneMode_doesNotShowOperatorName() =
        testScope.runTest {
            whenever(telephonyManager.isDataCapable).thenReturn(false)
            val mockSubInfo =
                mock<SubscriptionInfo>().also {
                    whenever(it.subscriptionId).thenReturn(1)
                    whenever(it.carrierName).thenReturn("test_carrier")
                }
            whenever(keyguardUpdateMonitor.getSubscriptionInfoForSubId(any()))
                .thenReturn(mockSubInfo)
            whenever(keyguardUpdateMonitor.getSimState(any()))
                .thenReturn(TelephonyManager.SIM_STATE_READY)
            whenever(keyguardUpdateMonitor.getServiceState(any()))
                .thenReturn(ServiceState().also { it.state = ServiceState.STATE_IN_SERVICE })
            subscriptionManagerProxy.defaultDataSubId = 1
            airplaneModeRepository.setIsAirplaneMode(true)

            underTest.onViewAttached()
            runCurrent()

            assertTrue(view.text.isNullOrEmpty())
        }

    @Test
    fun notInService_doesNotShowOperatorName() =
        testScope.runTest {
            // Data capable
            whenever(telephonyManager.isDataCapable).thenReturn(true)

            // Valid subscription
            val mockSubInfo =
                mock<SubscriptionInfo>().also {
                    whenever(it.subscriptionId).thenReturn(1)
                    whenever(it.carrierName).thenReturn("test_carrier")
                }
            whenever(keyguardUpdateMonitor.getSubscriptionInfoForSubId(any()))
                .thenReturn(mockSubInfo)
            whenever(keyguardUpdateMonitor.getSimState(any()))
                .thenReturn(TelephonyManager.SIM_STATE_READY)

            // Not in service
            whenever(keyguardUpdateMonitor.getServiceState(any()))
                .thenReturn(ServiceState().also { it.state = ServiceState.STATE_OUT_OF_SERVICE })
            // Subscription is default for data
            subscriptionManagerProxy.defaultDataSubId = 1
            // Not airplane mode
            airplaneModeRepository.setIsAirplaneMode(false)

            underTest.onViewAttached()
            runCurrent()

            assertTrue(view.text.isNullOrEmpty())
        }
}
