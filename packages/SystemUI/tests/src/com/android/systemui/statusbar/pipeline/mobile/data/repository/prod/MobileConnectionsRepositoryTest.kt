/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.vcn.VcnTransportInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.ParcelUuid
import android.telephony.CarrierConfigManager
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.telephony.PhoneConstants
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.R
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.tableBufferLogName
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.FakeSubscriptionManagerProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlots
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.android.wifitrackerlib.MergedCarrierEntry
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiPickerTracker
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
// This is required because our [SubscriptionManager.OnSubscriptionsChangedListener] uses a looper
// to run the callback and this makes the looper place nicely with TestScope etc.
@TestableLooper.RunWithLooper
class MobileConnectionsRepositoryTest : SysuiTestCase() {

    private val flags =
        FakeFeatureFlagsClassic().also { it.set(Flags.ROAMING_INDICATOR_VIA_DISPLAY_INFO, true) }

    private lateinit var connectionFactory: MobileConnectionRepositoryImpl.Factory
    private lateinit var carrierMergedFactory: CarrierMergedConnectionRepository.Factory
    private lateinit var fullConnectionFactory: FullMobileConnectionRepository.Factory
    private lateinit var connectivityRepository: ConnectivityRepository
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var wifiRepository: WifiRepository
    private lateinit var carrierConfigRepository: CarrierConfigRepository

    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var subscriptionManager: SubscriptionManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: MobileInputLogger
    @Mock private lateinit var summaryLogger: TableLogBuffer
    @Mock private lateinit var logBufferFactory: TableLogBufferFactory
    @Mock private lateinit var updateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var wifiManager: WifiManager
    @Mock private lateinit var wifiPickerTrackerFactory: WifiPickerTrackerFactory
    @Mock private lateinit var wifiPickerTracker: WifiPickerTracker
    @Mock private lateinit var wifiTableLogBuffer: TableLogBuffer

    private val mobileMappings = FakeMobileMappingsProxy()
    private val subscriptionManagerProxy = FakeSubscriptionManagerProxy()
    private val mainExecutor = FakeExecutor(FakeSystemClock())
    private val wifiLogBuffer = LogBuffer("wifi", maxSize = 100, logcatEchoTracker = mock())
    private val wifiPickerTrackerCallback =
        argumentCaptor<WifiPickerTracker.WifiPickerTrackerCallback>()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: MobileConnectionsRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(telephonyManager.simOperatorName).thenReturn("")

        // Set up so the individual connection repositories
        whenever(telephonyManager.createForSubscriptionId(anyInt())).thenAnswer { invocation ->
            telephonyManager.also {
                whenever(it.subscriptionId).thenReturn(invocation.getArgument(0))
            }
        }

        whenever(logBufferFactory.getOrCreate(anyString(), anyInt())).thenAnswer { _ ->
            mock<TableLogBuffer>()
        }

        whenever(wifiPickerTrackerFactory.create(any(), capture(wifiPickerTrackerCallback), any()))
            .thenReturn(wifiPickerTracker)

        // For convenience, set up the subscription info callbacks
        whenever(subscriptionManager.getActiveSubscriptionInfo(anyInt())).thenAnswer { invocation ->
            when (invocation.getArgument(0) as Int) {
                1 -> SUB_1
                2 -> SUB_2
                3 -> SUB_3
                4 -> SUB_4
                else -> null
            }
        }

        connectivityRepository =
            ConnectivityRepositoryImpl(
                connectivityManager,
                ConnectivitySlots(context),
                context,
                mock(),
                mock(),
                testScope.backgroundScope,
                mock(),
            )

        airplaneModeRepository = FakeAirplaneModeRepository()

        wifiRepository =
            WifiRepositoryImpl(
                testScope.backgroundScope,
                mainExecutor,
                testDispatcher,
                wifiPickerTrackerFactory,
                wifiManager,
                wifiLogBuffer,
                wifiTableLogBuffer,
            )

        carrierConfigRepository =
            CarrierConfigRepository(
                fakeBroadcastDispatcher,
                mock(),
                mock(),
                logger,
                testScope.backgroundScope,
            )

        connectionFactory =
            MobileConnectionRepositoryImpl.Factory(
                context,
                fakeBroadcastDispatcher,
                connectivityManager,
                telephonyManager = telephonyManager,
                bgDispatcher = testDispatcher,
                logger = logger,
                mobileMappingsProxy = mobileMappings,
                scope = testScope.backgroundScope,
                flags = flags,
                carrierConfigRepository = carrierConfigRepository,
            )
        carrierMergedFactory =
            CarrierMergedConnectionRepository.Factory(
                telephonyManager,
                testScope.backgroundScope.coroutineContext,
                testScope.backgroundScope,
                wifiRepository,
            )
        fullConnectionFactory =
            FullMobileConnectionRepository.Factory(
                scope = testScope.backgroundScope,
                logFactory = logBufferFactory,
                mobileRepoFactory = connectionFactory,
                carrierMergedRepoFactory = carrierMergedFactory,
            )

        underTest =
            MobileConnectionsRepositoryImpl(
                connectivityRepository,
                subscriptionManager,
                subscriptionManagerProxy,
                telephonyManager,
                logger,
                summaryLogger,
                mobileMappings,
                fakeBroadcastDispatcher,
                context,
                /* bgDispatcher = */ testDispatcher,
                testScope.backgroundScope,
                /* mainDispatcher = */ testDispatcher,
                airplaneModeRepository,
                wifiRepository,
                fullConnectionFactory,
                updateMonitor,
                mock(),
            )

        testScope.runCurrent()
    }

    @Test
    fun testSubscriptions_initiallyEmpty() =
        testScope.runTest {
            assertThat(underTest.subscriptions.value).isEqualTo(listOf<SubscriptionModel>())
        }

    @Test
    fun testSubscriptions_listUpdates() =
        testScope.runTest {
            val latest by collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(MODEL_1, MODEL_2))
        }

    @Test
    fun testSubscriptions_removingSub_updatesList() =
        testScope.runTest {
            val latest by collectLastValue(underTest.subscriptions)

            // WHEN 2 networks show up
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // WHEN one network is removed
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // THEN the subscriptions list represents the newest change
            assertThat(latest).isEqualTo(listOf(MODEL_2))
        }

    @Test
    fun subscriptions_subIsOnlyNtn_modelHasExclusivelyNtnTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.subscriptions)

            val onlyNtnSub =
                mock<SubscriptionInfo>().also {
                    whenever(it.isOnlyNonTerrestrialNetwork).thenReturn(true)
                    whenever(it.subscriptionId).thenReturn(45)
                    whenever(it.groupUuid).thenReturn(GROUP_1)
                    whenever(it.carrierName).thenReturn("NTN only")
                    whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
                }

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(onlyNtnSub))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].isExclusivelyNonTerrestrial).isTrue()
        }

    @Test
    fun subscriptions_subIsNotOnlyNtn_modelHasExclusivelyNtnFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.subscriptions)

            val notOnlyNtnSub =
                mock<SubscriptionInfo>().also {
                    whenever(it.isOnlyNonTerrestrialNetwork).thenReturn(false)
                    whenever(it.subscriptionId).thenReturn(45)
                    whenever(it.groupUuid).thenReturn(GROUP_1)
                    whenever(it.carrierName).thenReturn("NTN only")
                    whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
                }

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(notOnlyNtnSub))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].isExclusivelyNonTerrestrial).isFalse()
        }

    @Test
    fun testSubscriptions_carrierMergedOnly_listHasCarrierMerged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.subscriptions)

            setWifiState(isCarrierMerged = true)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(MODEL_CM))
        }

    @Test
    fun testSubscriptions_carrierMergedAndOther_listHasBothWithCarrierMergedLast() =
        testScope.runTest {
            val latest by collectLastValue(underTest.subscriptions)

            setWifiState(isCarrierMerged = true)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(MODEL_1, MODEL_2, MODEL_CM))
        }

    @Test
    fun testActiveDataSubscriptionId_initialValueIsNull() =
        testScope.runTest {
            assertThat(underTest.activeMobileDataSubscriptionId.value).isEqualTo(null)
        }

    @Test
    fun testActiveDataSubscriptionId_updates() =
        testScope.runTest {
            val active by collectLastValue(underTest.activeMobileDataSubscriptionId)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(active).isEqualTo(SUB_2_ID)
        }

    @Test
    fun activeSubId_nullIfInvalidSubIdIsReceived() =
        testScope.runTest {
            val latest by collectLastValue(underTest.activeMobileDataSubscriptionId)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(latest).isNotNull()

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(INVALID_SUBSCRIPTION_ID)

            assertThat(latest).isNull()
        }

    @Test
    fun activeRepo_initiallyNull() {
        assertThat(underTest.activeMobileDataRepository.value).isNull()
    }

    @Test
    fun activeRepo_updatesWithActiveDataId() =
        testScope.runTest {
            val latest by collectLastValue(underTest.activeMobileDataRepository)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(latest?.subId).isEqualTo(SUB_2_ID)
        }

    @Test
    fun activeRepo_nullIfActiveDataSubIdBecomesInvalid() =
        testScope.runTest {
            val latest by collectLastValue(underTest.activeMobileDataRepository)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(latest).isNotNull()

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(INVALID_SUBSCRIPTION_ID)

            assertThat(latest).isNull()
        }

    @Test
    /** Regression test for b/268146648. */
    fun activeSubIdIsSetBeforeSubscriptionsAreUpdated_doesNotThrow() =
        testScope.runTest {
            val activeRepo by collectLastValue(underTest.activeMobileDataRepository)
            val subscriptions by collectLastValue(underTest.subscriptions)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(subscriptions).isEmpty()
            assertThat(activeRepo).isNotNull()
        }

    @Test
    fun getRepoForSubId_activeDataSubIdIsRequestedBeforeSubscriptionsUpdate() =
        testScope.runTest {
            var latestActiveRepo: MobileConnectionRepository? = null
            collectLastValue(
                underTest.activeMobileDataSubscriptionId.filterNotNull().onEach {
                    latestActiveRepo = underTest.getRepoForSubId(it)
                }
            )

            val latestSubscriptions by collectLastValue(underTest.subscriptions)

            // Active data subscription id is sent, but no subscription change has been posted yet
            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            // Subscriptions list is empty
            assertThat(latestSubscriptions).isEmpty()
            // getRepoForSubId does not throw
            assertThat(latestActiveRepo).isNotNull()
        }

    @Test
    fun activeDataSentBeforeSubscriptionList_subscriptionReusesActiveDataRepo() =
        testScope.runTest {
            val activeRepo by collectLastValue(underTest.activeMobileDataRepository)
            collectLastValue(underTest.subscriptions)

            // GIVEN active repo is updated before the subscription list updates
            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(activeRepo).isNotNull()

            // GIVEN the subscription list is then updated which includes the active data sub id
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // WHEN requesting a connection repository for the subscription
            val newRepo = underTest.getRepoForSubId(SUB_2_ID)

            // THEN the newly request repo has been cached and reused
            assertThat(activeRepo).isSameInstanceAs(newRepo)
        }

    @Test
    fun testConnectionRepository_validSubId_isCached() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_1_ID)

            assertThat(repo1).isSameInstanceAs(repo2)
        }

    @Test
    fun testConnectionRepository_carrierMergedSubId_isCached() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_CM)
            setWifiState(isCarrierMerged = true)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1 = underTest.getRepoForSubId(SUB_CM_ID)
            val repo2 = underTest.getRepoForSubId(SUB_CM_ID)

            assertThat(repo1).isSameInstanceAs(repo2)
        }

    @Test
    fun testConnectionRepository_carrierMergedAndMobileSubs_usesCorrectRepos() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_CM)
            setWifiState(isCarrierMerged = true)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val carrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            val mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(carrierMergedRepo.getIsCarrierMerged()).isTrue()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()
        }

    @Test
    fun testSubscriptions_subNoLongerCarrierMerged_repoUpdates() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_CM)
            setWifiState(isCarrierMerged = true)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            val carrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            var mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(carrierMergedRepo.getIsCarrierMerged()).isTrue()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            // WHEN the wifi network updates to be not carrier merged
            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_ACTIVE)
            setWifiState(isCarrierMerged = false)
            runCurrent()

            // THEN the repos update
            val noLongerCarrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(noLongerCarrierMergedRepo.getIsCarrierMerged()).isFalse()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()
        }

    @Test
    fun testSubscriptions_subBecomesCarrierMerged_repoUpdates() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_ACTIVE)
            setWifiState(isCarrierMerged = false)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()
            runCurrent()

            val notYetCarrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            var mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(notYetCarrierMergedRepo.getIsCarrierMerged()).isFalse()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()

            // WHEN the wifi network updates to be carrier merged
            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_CM)
            setWifiState(isCarrierMerged = true)
            runCurrent()

            // THEN the repos update
            val carrierMergedRepo = underTest.getRepoForSubId(SUB_CM_ID)
            mobileRepo = underTest.getRepoForSubId(SUB_1_ID)
            assertThat(carrierMergedRepo.getIsCarrierMerged()).isTrue()
            assertThat(mobileRepo.getIsCarrierMerged()).isFalse()
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Test
    fun testDeviceEmergencyCallState_eagerlyChecksState() =
        testScope.runTest {
            // Value starts out false
            assertThat(underTest.isDeviceEmergencyCallCapable.value).isFalse()
            whenever(telephonyManager.activeModemCount).thenReturn(1)
            whenever(telephonyManager.getServiceStateForSlot(any())).thenAnswer { _ ->
                ServiceState().apply { isEmergencyOnly = true }
            }

            // WHEN an appropriate intent gets sent out
            val intent = serviceStateIntent(subId = -1)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                intent,
            )
            runCurrent()

            // THEN the repo's state is updated despite no listeners
            assertThat(underTest.isDeviceEmergencyCallCapable.value).isEqualTo(true)
        }

    @Test
    fun testDeviceEmergencyCallState_aggregatesAcrossSlots_oneTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isDeviceEmergencyCallCapable)

            // GIVEN there are multiple slots
            whenever(telephonyManager.activeModemCount).thenReturn(4)
            // GIVEN only one of them reports ECM
            whenever(telephonyManager.getServiceStateForSlot(any())).thenAnswer { invocation ->
                when (invocation.getArgument(0) as Int) {
                    0 -> ServiceState().apply { isEmergencyOnly = false }
                    1 -> ServiceState().apply { isEmergencyOnly = false }
                    2 -> ServiceState().apply { isEmergencyOnly = true }
                    3 -> ServiceState().apply { isEmergencyOnly = false }
                    else -> null
                }
            }

            // GIVEN a broadcast goes out for the appropriate subID
            val intent = serviceStateIntent(subId = -1)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                intent,
            )
            runCurrent()

            // THEN the device is in ECM, because one of the service states is
            assertThat(latest).isTrue()
        }

    @Test
    fun testDeviceEmergencyCallState_aggregatesAcrossSlots_allFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isDeviceEmergencyCallCapable)

            // GIVEN there are multiple slots
            whenever(telephonyManager.activeModemCount).thenReturn(4)
            // GIVEN only one of them reports ECM
            whenever(telephonyManager.getServiceStateForSlot(any())).thenAnswer { invocation ->
                when (invocation.getArgument(0) as Int) {
                    0 -> ServiceState().apply { isEmergencyOnly = false }
                    1 -> ServiceState().apply { isEmergencyOnly = false }
                    2 -> ServiceState().apply { isEmergencyOnly = false }
                    3 -> ServiceState().apply { isEmergencyOnly = false }
                    else -> null
                }
            }

            // GIVEN a broadcast goes out for the appropriate subID
            val intent = serviceStateIntent(subId = -1)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                intent,
            )
            runCurrent()

            // THEN the device is in ECM, because one of the service states is
            assertThat(latest).isFalse()
        }

    @Test
    @Ignore("b/333912012")
    fun testConnectionCache_clearsInvalidSubscriptions() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger caching
            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_2_ID)

            assertThat(underTest.getSubIdRepoCache())
                .containsExactly(SUB_1_ID, repo1, SUB_2_ID, repo2)

            // SUB_2 disappears
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(underTest.getSubIdRepoCache()).containsExactly(SUB_1_ID, repo1)
        }

    @Test
    @Ignore("b/333912012")
    fun testConnectionCache_clearsInvalidSubscriptions_includingCarrierMerged() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, WIFI_NETWORK_CAPS_CM)
            setWifiState(isCarrierMerged = true)
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2, SUB_CM))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger caching
            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_2_ID)
            val repoCarrierMerged = underTest.getRepoForSubId(SUB_CM_ID)

            assertThat(underTest.getSubIdRepoCache())
                .containsExactly(SUB_1_ID, repo1, SUB_2_ID, repo2, SUB_CM_ID, repoCarrierMerged)

            // SUB_2 and SUB_CM disappear
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(underTest.getSubIdRepoCache()).containsExactly(SUB_1_ID, repo1)
        }

    /** Regression test for b/261706421 */
    @Test
    @Ignore("b/333912012")
    fun testConnectionsCache_clearMultipleSubscriptionsAtOnce_doesNotThrow() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger caching
            val repo1 = underTest.getRepoForSubId(SUB_1_ID)
            val repo2 = underTest.getRepoForSubId(SUB_2_ID)

            assertThat(underTest.getSubIdRepoCache())
                .containsExactly(SUB_1_ID, repo1, SUB_2_ID, repo2)

            // All subscriptions disappear
            whenever(subscriptionManager.completeActiveSubscriptionInfoList).thenReturn(listOf())
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(underTest.getSubIdRepoCache()).isEmpty()
        }

    @Test
    fun testConnectionsCache_keepsReposCached() =
        testScope.runTest {
            // Collect subscriptions to start the job
            collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1_1 = underTest.getRepoForSubId(SUB_1_ID)

            // All subscriptions disappear
            whenever(subscriptionManager.completeActiveSubscriptionInfoList).thenReturn(listOf())
            getSubscriptionCallback().onSubscriptionsChanged()

            // Sub1 comes back
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1_2 = underTest.getRepoForSubId(SUB_1_ID)

            assertThat(repo1_1).isSameInstanceAs(repo1_2)
        }

    @Test
    fun testConnectionsCache_doesNotDropReferencesThatHaveBeenRealized() =
        testScope.runTest {
            // Collect subscriptions to start the job
            collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Client grabs a reference to a repository, but doesn't keep it around
            underTest.getRepoForSubId(SUB_1_ID)

            // All subscriptions disappear
            whenever(subscriptionManager.completeActiveSubscriptionInfoList).thenReturn(listOf())
            getSubscriptionCallback().onSubscriptionsChanged()

            val repo1 = underTest.getRepoForSubId(SUB_1_ID)

            assertThat(repo1).isNotNull()
        }

    @Test
    fun testConnectionRepository_invalidSubId_doesNotThrow() =
        testScope.runTest {
            underTest.getRepoForSubId(SUB_1_ID)
            // No exception
        }

    @Test
    fun connectionRepository_logBufferContainsSubIdInItsName() =
        testScope.runTest {
            collectLastValue(underTest.subscriptions)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // Get repos to trigger creation
            underTest.getRepoForSubId(SUB_1_ID)
            verify(logBufferFactory)
                .getOrCreate(
                    eq(tableBufferLogName(SUB_1_ID)),
                    anyInt(),
                )
            underTest.getRepoForSubId(SUB_2_ID)
            verify(logBufferFactory)
                .getOrCreate(
                    eq(tableBufferLogName(SUB_2_ID)),
                    anyInt(),
                )
        }

    @Test
    fun testDefaultDataSubId_updatesOnBroadcast() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultDataSubId)

            assertThat(latest).isEqualTo(INVALID_SUBSCRIPTION_ID)

            val intent2 =
                Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                    .putExtra(PhoneConstants.SUBSCRIPTION_KEY, SUB_2_ID)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent2)

            assertThat(latest).isEqualTo(SUB_2_ID)

            val intent1 =
                Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                    .putExtra(PhoneConstants.SUBSCRIPTION_KEY, SUB_1_ID)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent1)

            assertThat(latest).isEqualTo(SUB_1_ID)
        }

    @Test
    fun defaultDataSubId_fetchesInitialValueOnStart() =
        testScope.runTest {
            subscriptionManagerProxy.defaultDataSubId = 2
            val latest by collectLastValue(underTest.defaultDataSubId)

            assertThat(latest).isEqualTo(2)
        }

    @Test
    fun defaultDataSubId_fetchesCurrentOnRestart() =
        testScope.runTest {
            subscriptionManagerProxy.defaultDataSubId = 2
            var latest: Int? = null
            var job = underTest.defaultDataSubId.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isEqualTo(2)

            job.cancel()

            // Collectors go away but come back later

            latest = null

            subscriptionManagerProxy.defaultDataSubId = 1

            job = underTest.defaultDataSubId.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isEqualTo(1)

            job.cancel()
        }

    @Test
    fun mobileIsDefault_startsAsFalse() {
        assertThat(underTest.mobileIsDefault.value).isFalse()
    }

    @Test
    fun mobileIsDefault_capsHaveCellular_isDefault() =
        testScope.runTest {
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                }

            val latest by collectLastValue(underTest.mobileIsDefault)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isTrue()
        }

    @Test
    fun mobileIsDefault_capsDoNotHaveCellular_isNotDefault() =
        testScope.runTest {
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                }

            val latest by collectLastValue(underTest.mobileIsDefault)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isFalse()
        }

    @Test
    fun mobileIsDefault_carrierMergedViaMobile_isDefault() =
        testScope.runTest {
            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }

            val latest by collectLastValue(underTest.mobileIsDefault)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isTrue()
        }

    @Test
    fun mobileIsDefault_wifiDefault_mobileNotDefault() =
        testScope.runTest {
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                }

            val latest by collectLastValue(underTest.mobileIsDefault)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isFalse()
        }

    @Test
    fun mobileIsDefault_ethernetDefault_mobileNotDefault() =
        testScope.runTest {
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(true)
                }

            val latest by collectLastValue(underTest.mobileIsDefault)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isFalse()
        }

    /** Regression test for b/272586234. */
    @Test
    fun hasCarrierMergedConnection_carrierMergedViaWifi_isTrue() =
        testScope.runTest {
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }

            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)
            setWifiState(isCarrierMerged = true)

            assertThat(latest).isTrue()
        }

    @Test
    fun hasCarrierMergedConnection_carrierMergedViaMobile_isTrue() =
        testScope.runTest {
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }

            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)
            setWifiState(isCarrierMerged = true)

            assertThat(latest).isTrue()
        }

    /** Regression test for b/272586234. */
    @Test
    fun hasCarrierMergedConnection_carrierMergedViaWifiWithVcnTransport_isTrue() =
        testScope.runTest {
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                }

            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)
            setWifiState(isCarrierMerged = true)

            assertThat(latest).isTrue()
        }

    @Test
    fun hasCarrierMergedConnection_carrierMergedViaMobileWithVcnTransport_isTrue() =
        testScope.runTest {
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                }

            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)
            setWifiState(isCarrierMerged = true)

            assertThat(latest).isTrue()
        }

    @Test
    fun hasCarrierMergedConnection_isCarrierMergedViaUnderlyingWifi_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            val underlyingNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val underlyingWifiCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
                .thenReturn(underlyingWifiCapabilities)

            // WHEN the main capabilities have an underlying carrier merged network via WIFI
            // transport and WifiInfo
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)
            setWifiState(isCarrierMerged = true)

            // THEN there's a carrier merged connection
            assertThat(latest).isTrue()
        }

    @Test
    fun hasCarrierMergedConnection_isCarrierMergedViaUnderlyingCellular_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            val underlyingCarrierMergedNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val underlyingCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingCarrierMergedNetwork))
                .thenReturn(underlyingCapabilities)

            // WHEN the main capabilities have an underlying carrier merged network via CELLULAR
            // transport and VcnTransportInfo
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks)
                        .thenReturn(listOf(underlyingCarrierMergedNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)
            setWifiState(isCarrierMerged = true)

            // THEN there's a carrier merged connection
            assertThat(latest).isTrue()
        }

    /** Regression test for b/272586234. */
    @Test
    fun hasCarrierMergedConnection_defaultIsWifiNotCarrierMerged_wifiRepoIsCarrierMerged_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            // WHEN the default callback is TRANSPORT_WIFI but not carrier merged
            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(false) }
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }
            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            // BUT the wifi repo has gotten updates that it *is* carrier merged
            setWifiState(isCarrierMerged = true)

            // THEN hasCarrierMergedConnection is true
            assertThat(latest).isTrue()
        }

    /** Regression test for b/278618530. */
    @Test
    fun hasCarrierMergedConnection_defaultIsCellular_wifiRepoIsCarrierMerged_isFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            // WHEN the default callback is TRANSPORT_CELLULAR and not carrier merged
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                }
            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            // BUT the wifi repo has gotten updates that it *is* carrier merged
            setWifiState(isCarrierMerged = true)

            // THEN hasCarrierMergedConnection is **false** (The default network being CELLULAR
            // takes precedence over the wifi network being carrier merged.)
            assertThat(latest).isFalse()
        }

    /** Regression test for b/278618530. */
    @Test
    fun hasCarrierMergedConnection_defaultCellular_wifiIsCarrierMerged_airplaneMode_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.hasCarrierMergedConnection)

            // WHEN the default callback is TRANSPORT_CELLULAR and not carrier merged
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                }
            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            // BUT the wifi repo has gotten updates that it *is* carrier merged
            setWifiState(isCarrierMerged = true)
            // AND we're in airplane mode
            airplaneModeRepository.setIsAirplaneMode(true)

            // THEN hasCarrierMergedConnection is true.
            assertThat(latest).isTrue()
        }

    @Test
    fun defaultConnectionIsValidated_startsAsFalse() {
        assertThat(underTest.defaultConnectionIsValidated.value).isFalse()
    }

    @Test
    fun defaultConnectionIsValidated_capsHaveValidated_isValidated() =
        testScope.runTest {
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(true)
                }

            val latest by collectLastValue(underTest.defaultConnectionIsValidated)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isTrue()
        }

    @Test
    fun defaultConnectionIsValidated_capsHaveNotValidated_isNotValidated() =
        testScope.runTest {
            val caps =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(false)
                }

            val latest by collectLastValue(underTest.defaultConnectionIsValidated)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, caps)

            assertThat(latest).isFalse()
        }

    @Test
    fun config_initiallyFromContext() =
        testScope.runTest {
            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // The initial value will be fetched when the repo is created, so we need to override
            // the resources and then re-create the repo.
            underTest =
                MobileConnectionsRepositoryImpl(
                    connectivityRepository,
                    subscriptionManager,
                    subscriptionManagerProxy,
                    telephonyManager,
                    logger,
                    summaryLogger,
                    mobileMappings,
                    fakeBroadcastDispatcher,
                    context,
                    testDispatcher,
                    testScope.backgroundScope,
                    testDispatcher,
                    airplaneModeRepository,
                    wifiRepository,
                    fullConnectionFactory,
                    updateMonitor,
                    mock(),
                )

            val latest by collectLastValue(underTest.defaultDataSubRatConfig)

            assertTrue(latest!!.areEqual(configFromContext))
            assertTrue(latest!!.showAtLeast3G)
        }

    @Test
    fun config_subIdChangeEvent_updated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultDataSubRatConfig)

            assertThat(latest!!.showAtLeast3G).isFalse()

            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // WHEN the change event is fired
            val intent =
                Intent(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                    .putExtra(PhoneConstants.SUBSCRIPTION_KEY, SUB_1_ID)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)

            // THEN the config is updated
            assertTrue(latest!!.areEqual(configFromContext))
            assertTrue(latest!!.showAtLeast3G)
        }

    @Test
    fun config_carrierConfigChangeEvent_updated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultDataSubRatConfig)

            assertThat(latest!!.showAtLeast3G).isFalse()

            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // WHEN the change event is fired
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
            )

            // THEN the config is updated
            assertThat(latest!!.areEqual(configFromContext)).isTrue()
            assertThat(latest!!.showAtLeast3G).isTrue()
        }

    @Test
    fun carrierConfig_initialValueIsFetched() =
        testScope.runTest {
            // Value starts out false
            assertThat(underTest.defaultDataSubRatConfig.value.showAtLeast3G).isFalse()

            overrideResource(R.bool.config_showMin3G, true)
            val configFromContext = MobileMappings.Config.readConfig(context)
            assertThat(configFromContext.showAtLeast3G).isTrue()

            // WHEN the change event is fired
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
            )

            // WHEN collection starts AFTER the broadcast is sent out
            val latest by collectLastValue(underTest.defaultDataSubRatConfig)

            // THEN the config has the updated value
            assertThat(latest!!.areEqual(configFromContext)).isTrue()
            assertThat(latest!!.showAtLeast3G).isTrue()
        }

    @Test
    fun activeDataChange_inSameGroup_emitsUnit() =
        testScope.runTest {
            val latest by collectLastValue(underTest.activeSubChangedInGroupEvent)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_3_ID_GROUPED)
            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_4_ID_GROUPED)

            assertThat(latest).isEqualTo(Unit)
        }

    @Test
    fun activeDataChange_notInSameGroup_doesNotEmit() =
        testScope.runTest {
            val latest by collectLastValue(underTest.activeSubChangedInGroupEvent)

            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_3_ID_GROUPED)
            getTelephonyCallbackForType<ActiveDataSubscriptionIdListener>()
                .onActiveDataSubscriptionIdChanged(SUB_1_ID)

            assertThat(latest).isEqualTo(null)
        }

    @Test
    fun anySimSecure_propagatesStateFromKeyguardUpdateMonitor() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isAnySimSecure)
            assertThat(latest).isFalse()

            val updateMonitorCallback = argumentCaptor<KeyguardUpdateMonitorCallback>()
            verify(updateMonitor).registerCallback(updateMonitorCallback.capture())

            whenever(updateMonitor.isSimPinSecure).thenReturn(true)
            updateMonitorCallback.value.onSimStateChanged(0, 0, 0)

            assertThat(latest).isTrue()

            whenever(updateMonitor.isSimPinSecure).thenReturn(false)
            updateMonitorCallback.value.onSimStateChanged(0, 0, 0)

            assertThat(latest).isFalse()
        }

    @Test
    fun getIsAnySimSecure_delegatesCallToKeyguardUpdateMonitor() =
        testScope.runTest {
            assertThat(underTest.getIsAnySimSecure()).isFalse()

            whenever(updateMonitor.isSimPinSecure).thenReturn(true)

            assertThat(underTest.getIsAnySimSecure()).isTrue()
        }

    @Test
    fun noSubscriptionsInEcmMode_notInEcmMode() =
        testScope.runTest {
            whenever(telephonyManager.emergencyCallbackMode).thenReturn(false)

            runCurrent()

            assertThat(underTest.isInEcmMode()).isFalse()
        }

    @Test
    fun someSubscriptionsInEcmMode_inEcmMode() =
        testScope.runTest {
            whenever(telephonyManager.emergencyCallbackMode).thenReturn(true)

            runCurrent()

            assertThat(underTest.isInEcmMode()).isTrue()
        }

    private fun TestScope.getDefaultNetworkCallback(): ConnectivityManager.NetworkCallback {
        runCurrent()
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun setWifiState(isCarrierMerged: Boolean) {
        if (isCarrierMerged) {
            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                    whenever(this.subscriptionId).thenReturn(SUB_CM_ID)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
        } else {
            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(null)
        }
        wifiPickerTrackerCallback.value.onWifiEntriesChanged()
    }

    private fun TestScope.getSubscriptionCallback():
        SubscriptionManager.OnSubscriptionsChangedListener {
        runCurrent()
        val callbackCaptor = argumentCaptor<SubscriptionManager.OnSubscriptionsChangedListener>()
        verify(subscriptionManager)
            .addOnSubscriptionsChangedListener(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun TestScope.getTelephonyCallbacks(): List<TelephonyCallback> {
        runCurrent()
        val callbackCaptor = argumentCaptor<TelephonyCallback>()
        verify(telephonyManager).registerTelephonyCallback(any(), callbackCaptor.capture())
        return callbackCaptor.allValues
    }

    private inline fun <reified T> TestScope.getTelephonyCallbackForType(): T {
        val cbs = this.getTelephonyCallbacks().filterIsInstance<T>()
        assertThat(cbs.size).isEqualTo(1)
        return cbs[0]
    }

    companion object {
        // Subscription 1
        private const val SUB_1_ID = 1
        private const val SUB_1_NAME = "Carrier $SUB_1_ID"
        private val GROUP_1 = ParcelUuid(UUID.randomUUID())
        private val SUB_1 =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_1_ID)
                whenever(it.groupUuid).thenReturn(GROUP_1)
                whenever(it.carrierName).thenReturn(SUB_1_NAME)
                whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
            }
        private val MODEL_1 =
            SubscriptionModel(
                subscriptionId = SUB_1_ID,
                groupUuid = GROUP_1,
                carrierName = SUB_1_NAME,
                profileClass = PROFILE_CLASS_UNSET,
            )

        // Subscription 2
        private const val SUB_2_ID = 2
        private const val SUB_2_NAME = "Carrier $SUB_2_ID"
        private val GROUP_2 = ParcelUuid(UUID.randomUUID())
        private val SUB_2 =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_2_ID)
                whenever(it.groupUuid).thenReturn(GROUP_2)
                whenever(it.carrierName).thenReturn(SUB_2_NAME)
                whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
            }
        private val MODEL_2 =
            SubscriptionModel(
                subscriptionId = SUB_2_ID,
                groupUuid = GROUP_2,
                carrierName = SUB_2_NAME,
                profileClass = PROFILE_CLASS_UNSET,
            )

        // Subs 3 and 4 are considered to be in the same group ------------------------------------
        private val GROUP_ID_3_4 = ParcelUuid(UUID.randomUUID())

        // Subscription 3
        private const val SUB_3_ID_GROUPED = 3
        private val SUB_3 =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_3_ID_GROUPED)
                whenever(it.groupUuid).thenReturn(GROUP_ID_3_4)
                whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
            }

        // Subscription 4
        private const val SUB_4_ID_GROUPED = 4
        private val SUB_4 =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_4_ID_GROUPED)
                whenever(it.groupUuid).thenReturn(GROUP_ID_3_4)
                whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
            }

        // Subs 3 and 4 are considered to be in the same group ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

        private const val NET_ID = 123
        private val NETWORK = mock<Network>().apply { whenever(getNetId()).thenReturn(NET_ID) }

        // Carrier merged subscription
        private const val SUB_CM_ID = 5
        private const val SUB_CM_NAME = "Carrier $SUB_CM_ID"
        private val SUB_CM =
            mock<SubscriptionInfo>().also {
                whenever(it.subscriptionId).thenReturn(SUB_CM_ID)
                whenever(it.carrierName).thenReturn(SUB_CM_NAME)
                whenever(it.profileClass).thenReturn(PROFILE_CLASS_UNSET)
            }
        private val MODEL_CM =
            SubscriptionModel(
                subscriptionId = SUB_CM_ID,
                carrierName = SUB_CM_NAME,
                profileClass = PROFILE_CLASS_UNSET,
            )

        private val WIFI_INFO_CM =
            mock<WifiInfo>().apply {
                whenever(this.isPrimary).thenReturn(true)
                whenever(this.isCarrierMerged).thenReturn(true)
                whenever(this.subscriptionId).thenReturn(SUB_CM_ID)
            }
        private val WIFI_NETWORK_CAPS_CM =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(WIFI_INFO_CM)
                whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(true)
            }

        private val WIFI_INFO_ACTIVE =
            mock<WifiInfo>().apply {
                whenever(this.isPrimary).thenReturn(true)
                whenever(this.isCarrierMerged).thenReturn(false)
            }
        private val WIFI_NETWORK_CAPS_ACTIVE =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(WIFI_INFO_ACTIVE)
                whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(true)
            }

        /**
         * To properly mimic telephony manager, create a service state, and then turn it into an
         * intent
         */
        private fun serviceStateIntent(
            subId: Int,
        ): Intent {
            return Intent(Intent.ACTION_SERVICE_STATE).apply {
                putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId)
            }
        }
    }
}
