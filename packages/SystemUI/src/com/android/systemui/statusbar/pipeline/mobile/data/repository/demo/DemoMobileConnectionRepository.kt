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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.demo

import android.telephony.CellSignalStrength
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_CARRIER_ID
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_CARRIER_NETWORK_CHANGE
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_CDMA_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_EMERGENCY
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_IS_GSM
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_IS_IN_SERVICE
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_IS_NTN
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_OPERATOR
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_PRIMARY_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_ROAMING
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Demo version of [MobileConnectionRepository]. Note that this class shares all of its flows using
 * [SharingStarted.WhileSubscribed()] to give the same semantics as using a regular
 * [MutableStateFlow] while still logging all of the inputs in the same manor as the production
 * repos.
 */
class DemoMobileConnectionRepository(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
    val scope: CoroutineScope,
) : MobileConnectionRepository {
    private val _carrierId = MutableStateFlow(INVALID_SUBSCRIPTION_ID)
    override val carrierId =
        _carrierId
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_CARRIER_ID,
                _carrierId.value,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _carrierId.value)

    private val _inflateSignalStrength: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val inflateSignalStrength =
        _inflateSignalStrength
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = "inflate",
                _inflateSignalStrength.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _inflateSignalStrength.value)

    // I don't see a reason why we would turn the config off for demo mode.
    override val allowNetworkSliceIndicator = MutableStateFlow(true)

    private val _isEmergencyOnly = MutableStateFlow(false)
    override val isEmergencyOnly =
        _isEmergencyOnly
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_EMERGENCY,
                _isEmergencyOnly.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _isEmergencyOnly.value)

    private val _isRoaming = MutableStateFlow(false)
    override val isRoaming =
        _isRoaming
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_ROAMING,
                _isRoaming.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _isRoaming.value)

    private val _operatorAlphaShort: MutableStateFlow<String?> = MutableStateFlow(null)
    override val operatorAlphaShort =
        _operatorAlphaShort
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_OPERATOR,
                _operatorAlphaShort.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _operatorAlphaShort.value)

    private val _isInService = MutableStateFlow(false)
    override val isInService =
        _isInService
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_IS_IN_SERVICE,
                _isInService.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _isInService.value)

    private val _isNonTerrestrial = MutableStateFlow(false)
    override val isNonTerrestrial =
        _isNonTerrestrial
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_IS_NTN,
                _isNonTerrestrial.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _isNonTerrestrial.value)

    private val _isGsm = MutableStateFlow(false)
    override val isGsm =
        _isGsm
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_IS_GSM,
                _isGsm.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _isGsm.value)

    private val _cdmaLevel = MutableStateFlow(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
    override val cdmaLevel =
        _cdmaLevel
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_CDMA_LEVEL,
                _cdmaLevel.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _cdmaLevel.value)

    private val _primaryLevel = MutableStateFlow(CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
    override val primaryLevel =
        _primaryLevel
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_PRIMARY_LEVEL,
                _primaryLevel.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _primaryLevel.value)

    private val _dataConnectionState = MutableStateFlow(DataConnectionState.Disconnected)
    override val dataConnectionState =
        _dataConnectionState
            .logDiffsForTable(tableLogBuffer, columnPrefix = "", _dataConnectionState.value)
            .stateIn(scope, SharingStarted.WhileSubscribed(), _dataConnectionState.value)

    private val _dataActivityDirection =
        MutableStateFlow(
            DataActivityModel(
                hasActivityIn = false,
                hasActivityOut = false,
            )
        )
    override val dataActivityDirection =
        _dataActivityDirection
            .logDiffsForTable(tableLogBuffer, columnPrefix = "", _dataActivityDirection.value)
            .stateIn(scope, SharingStarted.WhileSubscribed(), _dataActivityDirection.value)

    private val _carrierNetworkChangeActive = MutableStateFlow(false)
    override val carrierNetworkChangeActive =
        _carrierNetworkChangeActive
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_CARRIER_NETWORK_CHANGE,
                _carrierNetworkChangeActive.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), _carrierNetworkChangeActive.value)

    private val _resolvedNetworkType: MutableStateFlow<ResolvedNetworkType> =
        MutableStateFlow(ResolvedNetworkType.UnknownNetworkType)
    override val resolvedNetworkType =
        _resolvedNetworkType
            .logDiffsForTable(tableLogBuffer, columnPrefix = "", _resolvedNetworkType.value)
            .stateIn(scope, SharingStarted.WhileSubscribed(), _resolvedNetworkType.value)

    override val numberOfLevels =
        _inflateSignalStrength
            .map { shouldInflate ->
                if (shouldInflate) {
                    DEFAULT_NUM_LEVELS + 1
                } else {
                    DEFAULT_NUM_LEVELS
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_NUM_LEVELS)

    override val dataEnabled = MutableStateFlow(true)

    override val cdmaRoaming = MutableStateFlow(false)

    override val networkName = MutableStateFlow(NetworkNameModel.IntentDerived(DEMO_CARRIER_NAME))

    override val carrierName =
        MutableStateFlow(NetworkNameModel.SubscriptionDerived(DEMO_CARRIER_NAME))

    override val isAllowedDuringAirplaneMode = MutableStateFlow(false)

    override val hasPrioritizedNetworkCapabilities = MutableStateFlow(false)

    override suspend fun isInEcmMode(): Boolean = false

    /**
     * Process a new demo mobile event. Note that [resolvedNetworkType] must be passed in separately
     * from the event, due to the requirement to reverse the mobile mappings lookup in the top-level
     * repository.
     */
    fun processDemoMobileEvent(
        event: FakeNetworkEventModel.Mobile,
        resolvedNetworkType: ResolvedNetworkType,
    ) {
        // This is always true here, because we split out disabled states at the data-source level
        dataEnabled.value = true
        networkName.value = NetworkNameModel.IntentDerived(event.name)
        carrierName.value = NetworkNameModel.SubscriptionDerived("${event.name} ${event.subId}")

        _carrierId.value = event.carrierId ?: INVALID_SUBSCRIPTION_ID

        _inflateSignalStrength.value = event.inflateStrength

        cdmaRoaming.value = event.roaming
        _isRoaming.value = event.roaming
        // TODO(b/261029387): not yet supported
        _isEmergencyOnly.value = false
        _operatorAlphaShort.value = event.name
        _isInService.value = (event.level ?: 0) > 0
        // TODO(b/261029387): not yet supported
        _isGsm.value = false
        _cdmaLevel.value = event.level ?: 0
        _primaryLevel.value = event.level ?: 0
        // TODO(b/261029387): not yet supported
        _dataConnectionState.value = DataConnectionState.Connected
        _dataActivityDirection.value =
            (event.activity ?: TelephonyManager.DATA_ACTIVITY_NONE).toMobileDataActivityModel()
        _carrierNetworkChangeActive.value = event.carrierNetworkChange
        _resolvedNetworkType.value = resolvedNetworkType
        _isNonTerrestrial.value = event.ntn

        isAllowedDuringAirplaneMode.value = false
        hasPrioritizedNetworkCapabilities.value = event.slice
    }

    fun processCarrierMergedEvent(event: FakeWifiEventModel.CarrierMerged) {
        // This is always true here, because we split out disabled states at the data-source level
        dataEnabled.value = true
        networkName.value = NetworkNameModel.IntentDerived(CARRIER_MERGED_NAME)
        carrierName.value = NetworkNameModel.SubscriptionDerived(CARRIER_MERGED_NAME)
        // TODO(b/276943904): is carrierId a thing with carrier merged networks?
        _carrierId.value = INVALID_SUBSCRIPTION_ID
        cdmaRoaming.value = false
        _primaryLevel.value = event.level
        _cdmaLevel.value = event.level
        _dataActivityDirection.value = event.activity.toMobileDataActivityModel()

        // These fields are always the same for carrier-merged networks
        _resolvedNetworkType.value = ResolvedNetworkType.CarrierMergedNetworkType
        _dataConnectionState.value = DataConnectionState.Connected
        _isRoaming.value = false
        _isEmergencyOnly.value = false
        _operatorAlphaShort.value = null
        _isInService.value = true
        _isGsm.value = false
        _carrierNetworkChangeActive.value = false
        isAllowedDuringAirplaneMode.value = true
        hasPrioritizedNetworkCapabilities.value = false
    }

    companion object {
        private const val DEMO_CARRIER_NAME = "Demo Carrier"
        private const val CARRIER_MERGED_NAME = "Carrier Merged Network"
    }
}
