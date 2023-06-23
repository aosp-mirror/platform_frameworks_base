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

import android.content.Intent
import android.telephony.CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.ServiceState.STATE_IN_SERVICE
import android.telephony.ServiceState.STATE_OUT_OF_SERVICE
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.DataActivityListener
import android.telephony.TelephonyCallback.ServiceStateListener
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.DATA_ACTIVITY_DORMANT
import android.telephony.TelephonyManager.DATA_ACTIVITY_IN
import android.telephony.TelephonyManager.DATA_ACTIVITY_INOUT
import android.telephony.TelephonyManager.DATA_ACTIVITY_NONE
import android.telephony.TelephonyManager.DATA_ACTIVITY_OUT
import android.telephony.TelephonyManager.DATA_CONNECTED
import android.telephony.TelephonyManager.DATA_CONNECTING
import android.telephony.TelephonyManager.DATA_DISCONNECTED
import android.telephony.TelephonyManager.DATA_DISCONNECTING
import android.telephony.TelephonyManager.DATA_HANDOVER_IN_PROGRESS
import android.telephony.TelephonyManager.DATA_SUSPENDED
import android.telephony.TelephonyManager.DATA_UNKNOWN
import android.telephony.TelephonyManager.ERI_OFF
import android.telephony.TelephonyManager.ERI_ON
import android.telephony.TelephonyManager.EXTRA_CARRIER_ID
import android.telephony.TelephonyManager.EXTRA_PLMN
import android.telephony.TelephonyManager.EXTRA_SHOW_PLMN
import android.telephony.TelephonyManager.EXTRA_SHOW_SPN
import android.telephony.TelephonyManager.EXTRA_SPN
import android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import androidx.test.filters.SmallTest
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfigTest.Companion.configWithOverride
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfigTest.Companion.createTestConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.toNetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers.signalStrength
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers.telephonyDisplayInfo
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileConnectionRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: MobileConnectionRepositoryImpl
    private lateinit var connectionsRepo: FakeMobileConnectionsRepository

    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: MobileInputLogger
    @Mock private lateinit var tableLogger: TableLogBuffer

    private val mobileMappings = FakeMobileMappingsProxy()
    private val systemUiCarrierConfig =
        SystemUiCarrierConfig(
            SUB_1_ID,
            createTestConfig(),
        )

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(telephonyManager.subscriptionId).thenReturn(SUB_1_ID)

        connectionsRepo = FakeMobileConnectionsRepository(mobileMappings, tableLogger)

        underTest =
            MobileConnectionRepositoryImpl(
                SUB_1_ID,
                DEFAULT_NAME,
                SEP,
                telephonyManager,
                systemUiCarrierConfig,
                fakeBroadcastDispatcher,
                mobileMappings,
                testDispatcher,
                logger,
                tableLogger,
                testScope.backgroundScope,
            )
    }

    @Test
    fun emergencyOnly() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isEmergencyOnly.onEach { latest = it }.launchIn(this)

            val serviceState = ServiceState()
            serviceState.isEmergencyOnly = true

            getTelephonyCallbackForType<ServiceStateListener>().onServiceStateChanged(serviceState)

            assertThat(latest).isEqualTo(true)

            job.cancel()
        }

    @Test
    fun emergencyOnly_toggles() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isEmergencyOnly.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<ServiceStateListener>()
            callback.onServiceStateChanged(ServiceState().also { it.isEmergencyOnly = true })
            assertThat(latest).isTrue()

            callback.onServiceStateChanged(ServiceState().also { it.isEmergencyOnly = false })

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun cdmaLevelUpdates() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.cdmaLevel.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>()
            var strength = signalStrength(gsmLevel = 1, cdmaLevel = 2, isGsm = true)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest).isEqualTo(2)

            // gsmLevel updates, no change to cdmaLevel
            strength = signalStrength(gsmLevel = 3, cdmaLevel = 2, isGsm = true)

            assertThat(latest).isEqualTo(2)

            job.cancel()
        }

    @Test
    fun gsmLevelUpdates() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.primaryLevel.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>()
            var strength = signalStrength(gsmLevel = 1, cdmaLevel = 2, isGsm = true)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest).isEqualTo(1)

            strength = signalStrength(gsmLevel = 3, cdmaLevel = 2, isGsm = true)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest).isEqualTo(3)

            job.cancel()
        }

    @Test
    fun isGsm() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isGsm.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>()
            var strength = signalStrength(gsmLevel = 1, cdmaLevel = 2, isGsm = true)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest).isTrue()

            strength = signalStrength(gsmLevel = 1, cdmaLevel = 2, isGsm = false)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun dataConnectionState_connected() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_CONNECTED, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Connected)

            job.cancel()
        }

    @Test
    fun dataConnectionState_connecting() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_CONNECTING, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Connecting)

            job.cancel()
        }

    @Test
    fun dataConnectionState_disconnected() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_DISCONNECTED, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Disconnected)

            job.cancel()
        }

    @Test
    fun dataConnectionState_disconnecting() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_DISCONNECTING, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Disconnecting)

            job.cancel()
        }

    @Test
    fun dataConnectionState_suspended() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_SUSPENDED, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Suspended)

            job.cancel()
        }

    @Test
    fun dataConnectionState_handoverInProgress() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_HANDOVER_IN_PROGRESS, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.HandoverInProgress)

            job.cancel()
        }

    @Test
    fun dataConnectionState_unknown() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_UNKNOWN, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Unknown)

            job.cancel()
        }

    @Test
    fun dataConnectionState_invalid() =
        testScope.runTest {
            var latest: DataConnectionState? = null
            val job = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(45, 200 /* unused */)

            assertThat(latest).isEqualTo(DataConnectionState.Invalid)

            job.cancel()
        }

    @Test
    fun dataActivity() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.dataActivityDirection.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<DataActivityListener>()
            callback.onDataActivity(DATA_ACTIVITY_INOUT)

            assertThat(latest).isEqualTo(DATA_ACTIVITY_INOUT.toMobileDataActivityModel())

            job.cancel()
        }

    @Test
    fun carrierId_initialValueCaptured() =
        testScope.runTest {
            whenever(telephonyManager.simCarrierId).thenReturn(1234)

            var latest: Int? = null
            val job = underTest.carrierId.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(1234)

            job.cancel()
        }

    @Test
    fun carrierId_updatesOnBroadcast() =
        testScope.runTest {
            whenever(telephonyManager.simCarrierId).thenReturn(1234)

            var latest: Int? = null
            val job = underTest.carrierId.onEach { latest = it }.launchIn(this)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                carrierIdIntent(carrierId = 4321),
            )

            assertThat(latest).isEqualTo(4321)

            job.cancel()
        }

    @Test
    fun carrierNetworkChange() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.carrierNetworkChangeActive.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.CarrierNetworkListener>()
            callback.onCarrierNetworkChange(true)

            assertThat(latest).isEqualTo(true)

            job.cancel()
        }

    @Test
    fun networkType_default() =
        testScope.runTest {
            var latest: ResolvedNetworkType? = null
            val job = underTest.resolvedNetworkType.onEach { latest = it }.launchIn(this)

            val expected = UnknownNetworkType

            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_unknown_hasCorrectKey() =
        testScope.runTest {
            var latest: ResolvedNetworkType? = null
            val job = underTest.resolvedNetworkType.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DisplayInfoListener>()
            val ti =
                telephonyDisplayInfo(
                    networkType = NETWORK_TYPE_UNKNOWN,
                    overrideNetworkType = NETWORK_TYPE_UNKNOWN,
                )

            callback.onDisplayInfoChanged(ti)

            val expected = UnknownNetworkType
            assertThat(latest).isEqualTo(expected)
            assertThat(latest!!.lookupKey).isEqualTo(MobileMappings.toIconKey(NETWORK_TYPE_UNKNOWN))

            job.cancel()
        }

    @Test
    fun networkType_updatesUsingDefault() =
        testScope.runTest {
            var latest: ResolvedNetworkType? = null
            val job = underTest.resolvedNetworkType.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DisplayInfoListener>()
            val overrideType = OVERRIDE_NETWORK_TYPE_NONE
            val type = NETWORK_TYPE_LTE
            val ti = telephonyDisplayInfo(networkType = type, overrideNetworkType = overrideType)
            callback.onDisplayInfoChanged(ti)

            val expected = DefaultNetworkType(mobileMappings.toIconKey(type))
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_updatesUsingOverride() =
        testScope.runTest {
            var latest: ResolvedNetworkType? = null
            val job = underTest.resolvedNetworkType.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DisplayInfoListener>()
            val type = OVERRIDE_NETWORK_TYPE_LTE_CA
            val ti = telephonyDisplayInfo(networkType = type, overrideNetworkType = type)
            callback.onDisplayInfoChanged(ti)

            val expected = OverrideNetworkType(mobileMappings.toIconKeyOverride(type))
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun networkType_unknownNetworkWithOverride_usesOverrideKey() =
        testScope.runTest {
            var latest: ResolvedNetworkType? = null
            val job = underTest.resolvedNetworkType.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DisplayInfoListener>()
            val unknown = NETWORK_TYPE_UNKNOWN
            val type = OVERRIDE_NETWORK_TYPE_LTE_CA
            val ti = telephonyDisplayInfo(unknown, type)
            callback.onDisplayInfoChanged(ti)

            val expected = OverrideNetworkType(mobileMappings.toIconKeyOverride(type))
            assertThat(latest).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun dataEnabled_initial_false() =
        testScope.runTest {
            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(false)

            assertThat(underTest.dataEnabled.value).isFalse()
        }

    @Test
    fun isDataEnabled_tracksTelephonyCallback() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.dataEnabled.onEach { latest = it }.launchIn(this)

            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(false)
            assertThat(underTest.dataEnabled.value).isFalse()

            val callback = getTelephonyCallbackForType<TelephonyCallback.DataEnabledListener>()

            callback.onDataEnabledChanged(true, 1)
            assertThat(latest).isTrue()

            callback.onDataEnabledChanged(false, 1)
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun numberOfLevels_isDefault() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.numberOfLevels.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(DEFAULT_NUM_LEVELS)

            job.cancel()
        }

    @Test
    fun roaming_cdma_queriesTelephonyManager() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.cdmaRoaming.onEach { latest = it }.launchIn(this)

            val cb = getTelephonyCallbackForType<ServiceStateListener>()

            // CDMA roaming is off, GSM roaming is on
            whenever(telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber).thenReturn(ERI_OFF)
            cb.onServiceStateChanged(ServiceState().also { it.roaming = true })

            assertThat(latest).isFalse()

            // CDMA roaming is on, GSM roaming is off
            whenever(telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber).thenReturn(ERI_ON)
            cb.onServiceStateChanged(ServiceState().also { it.roaming = false })

            assertThat(latest).isTrue()

            job.cancel()
        }

    /**
     * [TelephonyManager.getCdmaEnhancedRoamingIndicatorDisplayNumber] returns -1 if the service is
     * not running or if there is an error while retrieving the cdma ERI
     */
    @Test
    fun cdmaRoaming_ignoresNegativeOne() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.cdmaRoaming.onEach { latest = it }.launchIn(this)

            val serviceState = ServiceState()
            serviceState.roaming = false

            val cb = getTelephonyCallbackForType<ServiceStateListener>()

            // CDMA roaming is unavailable (-1), GSM roaming is off
            whenever(telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber).thenReturn(-1)
            cb.onServiceStateChanged(serviceState)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun roaming_gsm_queriesServiceState() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isRoaming.onEach { latest = it }.launchIn(this)

            val cb = getTelephonyCallbackForType<ServiceStateListener>()

            // CDMA roaming is off, GSM roaming is off
            whenever(telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber).thenReturn(ERI_OFF)
            cb.onServiceStateChanged(ServiceState().also { it.roaming = false })

            assertThat(latest).isFalse()

            // CDMA roaming is off, GSM roaming is on
            cb.onServiceStateChanged(ServiceState().also { it.roaming = true })

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun activity_updatesFromCallback() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.dataActivityDirection.onEach { latest = it }.launchIn(this)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = false))

            val cb = getTelephonyCallbackForType<DataActivityListener>()
            cb.onDataActivity(DATA_ACTIVITY_IN)
            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = false))

            cb.onDataActivity(DATA_ACTIVITY_OUT)
            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = true))

            cb.onDataActivity(DATA_ACTIVITY_INOUT)
            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = true))

            cb.onDataActivity(DATA_ACTIVITY_NONE)
            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = false))

            cb.onDataActivity(DATA_ACTIVITY_DORMANT)
            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = false))

            cb.onDataActivity(1234)
            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = false))

            job.cancel()
        }

    @Test
    fun networkName_default() =
        testScope.runTest {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(DEFAULT_NAME)

            job.cancel()
        }

    @Test
    fun networkName_usesBroadcastInfo_returnsDerived() =
        testScope.runTest {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            val intent = spnIntent()
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)

            assertThat(latest).isEqualTo(intent.toNetworkNameModel(SEP))

            job.cancel()
        }

    @Test
    fun networkName_broadcastNotForThisSubId_keepsOldValue() =
        testScope.runTest {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            val intent = spnIntent()
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)
            assertThat(latest).isEqualTo(intent.toNetworkNameModel(SEP))

            // WHEN an intent with a different subId is sent
            val wrongSubIntent = spnIntent(subId = 101)

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, wrongSubIntent)

            // THEN the previous intent's name is still used
            assertThat(latest).isEqualTo(intent.toNetworkNameModel(SEP))

            job.cancel()
        }

    @Test
    fun networkName_broadcastHasNoData_updatesToDefault() =
        testScope.runTest {
            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            val intent = spnIntent()
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)
            assertThat(latest).isEqualTo(intent.toNetworkNameModel(SEP))

            val intentWithoutInfo =
                spnIntent(
                    showSpn = false,
                    showPlmn = false,
                )

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intentWithoutInfo)

            assertThat(latest).isEqualTo(DEFAULT_NAME)

            job.cancel()
        }

    @Test
    fun operatorAlphaShort_tracked() =
        testScope.runTest {
            var latest: String? = null

            val job = underTest.operatorAlphaShort.onEach { latest = it }.launchIn(this)

            val shortName = "short name"
            val serviceState = ServiceState()
            serviceState.setOperatorName(
                /* longName */ "long name",
                /* shortName */ shortName,
                /* numeric */ "12345",
            )

            getTelephonyCallbackForType<ServiceStateListener>().onServiceStateChanged(serviceState)

            assertThat(latest).isEqualTo(shortName)

            job.cancel()
        }

    @Test
    fun isInService_notIwlan() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isInService.onEach { latest = it }.launchIn(this)

            getTelephonyCallbackForType<ServiceStateListener>()
                .onServiceStateChanged(
                    ServiceState().also {
                        it.voiceRegState = STATE_IN_SERVICE
                        it.dataRegState = STATE_IN_SERVICE
                    }
                )

            assertThat(latest).isTrue()

            getTelephonyCallbackForType<ServiceStateListener>()
                .onServiceStateChanged(
                    ServiceState().also {
                        it.dataRegState = STATE_IN_SERVICE
                        it.voiceRegState = STATE_OUT_OF_SERVICE
                    }
                )
            assertThat(latest).isTrue()

            getTelephonyCallbackForType<ServiceStateListener>()
                .onServiceStateChanged(
                    ServiceState().also {
                        it.voiceRegState = STATE_OUT_OF_SERVICE
                        it.dataRegState = STATE_OUT_OF_SERVICE
                    }
                )
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isInService_isIwlan_voiceOutOfService_dataInService() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.isInService.onEach { latest = it }.launchIn(this)

            // Mock the service state here so we can make it specifically IWLAN
            val serviceState: ServiceState = mock()
            whenever(serviceState.state).thenReturn(STATE_OUT_OF_SERVICE)
            whenever(serviceState.dataRegistrationState).thenReturn(STATE_IN_SERVICE)

            // See [com.android.settingslib.Utils.isInService] for more info. This is one way to
            // make the network look like IWLAN
            val networkRegWlan: NetworkRegistrationInfo = mock()
            whenever(serviceState.getNetworkRegistrationInfo(any(), any()))
                .thenReturn(networkRegWlan)
            whenever(networkRegWlan.registrationState)
                .thenReturn(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)

            getTelephonyCallbackForType<ServiceStateListener>().onServiceStateChanged(serviceState)
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun numberOfLevels_usesCarrierConfig() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.numberOfLevels.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(DEFAULT_NUM_LEVELS)

            systemUiCarrierConfig.processNewCarrierConfig(
                configWithOverride(KEY_INFLATE_SIGNAL_STRENGTH_BOOL, true)
            )

            assertThat(latest).isEqualTo(DEFAULT_NUM_LEVELS + 1)

            systemUiCarrierConfig.processNewCarrierConfig(
                configWithOverride(KEY_INFLATE_SIGNAL_STRENGTH_BOOL, false)
            )

            assertThat(latest).isEqualTo(DEFAULT_NUM_LEVELS)

            job.cancel()
        }

    private inline fun <reified T> getTelephonyCallbackForType(): T {
        return MobileTelephonyHelpers.getTelephonyCallbackForType(telephonyManager)
    }

    private fun carrierIdIntent(
        subId: Int = SUB_1_ID,
        carrierId: Int,
    ): Intent =
        Intent(TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED).apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, subId)
            putExtra(EXTRA_CARRIER_ID, carrierId)
        }

    private fun spnIntent(
        subId: Int = SUB_1_ID,
        showSpn: Boolean = true,
        spn: String = SPN,
        showPlmn: Boolean = true,
        plmn: String = PLMN,
    ): Intent =
        Intent(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED).apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, subId)
            putExtra(EXTRA_SHOW_SPN, showSpn)
            putExtra(EXTRA_SPN, spn)
            putExtra(EXTRA_SHOW_PLMN, showPlmn)
            putExtra(EXTRA_PLMN, plmn)
        }

    companion object {
        private const val SUB_1_ID = 1

        private val DEFAULT_NAME = NetworkNameModel.Default("default name")
        private const val SEP = "-"

        private const val SPN = "testSpn"
        private const val PLMN = "testPlmn"
    }
}
