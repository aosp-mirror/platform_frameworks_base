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

import android.telephony.CellSignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Every mobile line of service can be identified via a [SubscriptionInfo] object. We set up a
 * repository for each individual, tracked subscription via [MobileConnectionsRepository], and this
 * repository is responsible for setting up a [TelephonyManager] object tied to its subscriptionId
 *
 * There should only ever be one [MobileConnectionRepository] per subscription, since
 * [TelephonyManager] limits the number of callbacks that can be registered per process.
 *
 * This repository should have all of the relevant information for a single line of service, which
 * eventually becomes a single icon in the status bar.
 */
interface MobileConnectionRepository {
    /** The subscriptionId that this connection represents */
    val subId: Int

    /** The carrierId for this connection. See [TelephonyManager.getSimCarrierId] */
    val carrierId: StateFlow<Int>

    /** Reflects the value from the carrier config INFLATE_SIGNAL_STRENGTH for this connection */
    val inflateSignalStrength: StateFlow<Boolean>

    /**
     * The table log buffer created for this connection. Will have the name "MobileConnectionLog
     * [subId]"
     */
    val tableLogBuffer: TableLogBuffer

    /** True if the [android.telephony.ServiceState] says this connection is emergency calls only */
    val isEmergencyOnly: StateFlow<Boolean>

    /** True if [android.telephony.ServiceState] says we are roaming */
    val isRoaming: StateFlow<Boolean>

    /**
     * See [android.telephony.ServiceState.getOperatorAlphaShort], this value is defined as the
     * current registered operator name in short alphanumeric format. In some cases this name might
     * be preferred over other methods of calculating the network name
     */
    val operatorAlphaShort: StateFlow<String?>

    /**
     * TODO (b/263167683): Clarify this field
     *
     * This check comes from [com.android.settingslib.Utils.isInService]. It is intended to be a
     * mapping from a ServiceState to a notion of connectivity. Notably, it will consider a
     * connection to be in-service if either the voice registration state is IN_SERVICE or the data
     * registration state is IN_SERVICE and NOT IWLAN.
     */
    val isInService: StateFlow<Boolean>

    /** Reflects [android.telephony.ServiceState.isUsingNonTerrestrialNetwork] */
    val isNonTerrestrial: StateFlow<Boolean>

    /** True if [android.telephony.SignalStrength] told us that this connection is using GSM */
    val isGsm: StateFlow<Boolean>

    /**
     * There is still specific logic in the pipeline that calls out CDMA level explicitly. This
     * field is not completely orthogonal to [primaryLevel], because CDMA could be primary.
     */
    // @IntRange(from = 0, to = 4)
    val cdmaLevel: StateFlow<Int>

    /** [android.telephony.SignalStrength]'s concept of the overall signal level */
    // @IntRange(from = 0, to = 4)
    val primaryLevel: StateFlow<Int>

    /** The current data connection state. See [DataConnectionState] */
    val dataConnectionState: StateFlow<DataConnectionState>

    /** The current data activity direction. See [DataActivityModel] */
    val dataActivityDirection: StateFlow<DataActivityModel>

    /** True if there is currently a carrier network change in process */
    val carrierNetworkChangeActive: StateFlow<Boolean>

    /**
     * [resolvedNetworkType] is the [TelephonyDisplayInfo.getOverrideNetworkType] if it exists or
     * [TelephonyDisplayInfo.getNetworkType]. This is used to look up the proper network type icon
     */
    val resolvedNetworkType: StateFlow<ResolvedNetworkType>

    /** The total number of levels. Used with [SignalDrawable]. */
    val numberOfLevels: StateFlow<Int>

    /** Observable tracking [TelephonyManager.isDataConnectionAllowed] */
    val dataEnabled: StateFlow<Boolean>

    /**
     * See [TelephonyManager.getCdmaEnhancedRoamingIndicatorDisplayNumber]. This bit only matters if
     * the connection type is CDMA.
     *
     * True if the Enhanced Roaming Indicator (ERI) display number is not [TelephonyManager.ERI_OFF]
     */
    val cdmaRoaming: StateFlow<Boolean>

    /** The service provider name for this network connection, or the default name. */
    val networkName: StateFlow<NetworkNameModel>

    /**
     * The service provider name for this network connection, or the default name.
     *
     * TODO(b/296600321): De-duplicate this field with [networkName] after determining the data
     *   provided is identical
     */
    val carrierName: StateFlow<NetworkNameModel>

    /**
     * True if this type of connection is allowed while airplane mode is on, and false otherwise.
     */
    val isAllowedDuringAirplaneMode: StateFlow<Boolean>

    /**
     * True if this network has NET_CAPABILITIY_PRIORITIZE_LATENCY, and can be considered to be a
     * network slice
     */
    val hasPrioritizedNetworkCapabilities: StateFlow<Boolean>

    /**
     * True if this connection is in emergency callback mode.
     *
     * @see [TelephonyManager.getEmergencyCallbackMode]
     */
    suspend fun isInEcmMode(): Boolean

    companion object {
        /** The default number of levels to use for [numberOfLevels]. */
        val DEFAULT_NUM_LEVELS = CellSignalStrength.getNumSignalStrengthLevels()
    }
}
