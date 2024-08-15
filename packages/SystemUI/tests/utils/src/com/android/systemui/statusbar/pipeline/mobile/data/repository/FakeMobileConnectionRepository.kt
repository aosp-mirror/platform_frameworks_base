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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.flow.MutableStateFlow

// TODO(b/261632894): remove this in favor of the real impl or DemoMobileConnectionRepository
class FakeMobileConnectionRepository(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
) : MobileConnectionRepository {
    override val carrierId = MutableStateFlow(UNKNOWN_CARRIER_ID)
    override val inflateSignalStrength: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val allowNetworkSliceIndicator: MutableStateFlow<Boolean> = MutableStateFlow(true)
    override val isEmergencyOnly = MutableStateFlow(false)
    override val isRoaming = MutableStateFlow(false)
    override val operatorAlphaShort: MutableStateFlow<String?> = MutableStateFlow(null)
    override val isInService = MutableStateFlow(false)
    override val isNonTerrestrial = MutableStateFlow(false)
    override val isGsm = MutableStateFlow(false)
    override val cdmaLevel = MutableStateFlow(0)
    override val primaryLevel = MutableStateFlow(0)
    override val dataConnectionState = MutableStateFlow(DataConnectionState.Disconnected)
    override val dataActivityDirection =
        MutableStateFlow(DataActivityModel(hasActivityIn = false, hasActivityOut = false))
    override val carrierNetworkChangeActive = MutableStateFlow(false)
    override val resolvedNetworkType: MutableStateFlow<ResolvedNetworkType> =
        MutableStateFlow(ResolvedNetworkType.UnknownNetworkType)

    override val numberOfLevels = MutableStateFlow(DEFAULT_NUM_LEVELS)

    private val _dataEnabled = MutableStateFlow(true)
    override val dataEnabled = _dataEnabled

    override val cdmaRoaming = MutableStateFlow(false)

    override val networkName: MutableStateFlow<NetworkNameModel> =
        MutableStateFlow(NetworkNameModel.Default(DEFAULT_NETWORK_NAME))

    override val carrierName: MutableStateFlow<NetworkNameModel> =
        MutableStateFlow(NetworkNameModel.Default(DEFAULT_NETWORK_NAME))

    override val isAllowedDuringAirplaneMode = MutableStateFlow(false)

    override val hasPrioritizedNetworkCapabilities = MutableStateFlow(false)

    private var isInEcmMode: Boolean = false

    override suspend fun isInEcmMode(): Boolean = isInEcmMode

    fun setDataEnabled(enabled: Boolean) {
        _dataEnabled.value = enabled
    }

    /**
     * Set [primaryLevel] and [cdmaLevel]. Convenient when you don't care about the connection type
     */
    fun setAllLevels(level: Int) {
        cdmaLevel.value = level
        primaryLevel.value = level
    }

    /**
     * Set both [isRoaming] and [cdmaRoaming] properties, in the event that you don't care about the
     * connection type
     */
    fun setAllRoaming(roaming: Boolean) {
        isRoaming.value = roaming
        cdmaRoaming.value = roaming
    }

    /** Set the correct [resolvedNetworkType] for the given group via its lookup key */
    fun setNetworkTypeKey(key: String) {
        resolvedNetworkType.value = ResolvedNetworkType.DefaultNetworkType(key)
    }

    fun setIsInEcmMode(isInEcmMode: Boolean) {
        this.isInEcmMode = isInEcmMode
    }

    companion object {
        const val DEFAULT_NETWORK_NAME = "default name"
    }
}
