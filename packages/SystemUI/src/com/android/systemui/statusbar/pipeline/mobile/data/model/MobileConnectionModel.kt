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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.annotation.IntRange
import android.telephony.CellSignalStrength
import android.telephony.TelephonyCallback.CarrierNetworkListener
import android.telephony.TelephonyCallback.DataActivityListener
import android.telephony.TelephonyCallback.DataConnectionStateListener
import android.telephony.TelephonyCallback.DisplayInfoListener
import android.telephony.TelephonyCallback.ServiceStateListener
import android.telephony.TelephonyCallback.SignalStrengthsListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Disconnected
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel

/**
 * Data class containing all of the relevant information for a particular line of service, known as
 * a Subscription in the telephony world. These models are the result of a single telephony listener
 * which has many callbacks which each modify some particular field on this object.
 *
 * The design goal here is to de-normalize fields from the system into our model fields below. So
 * any new field that needs to be tracked should be copied into this data class rather than
 * threading complex system objects through the pipeline.
 */
data class MobileConnectionModel(
    /** Fields below are from [ServiceStateListener.onServiceStateChanged] */
    val isEmergencyOnly: Boolean = false,
    val isRoaming: Boolean = false,
    /**
     * See [android.telephony.ServiceState.getOperatorAlphaShort], this value is defined as the
     * current registered operator name in short alphanumeric format. In some cases this name might
     * be preferred over other methods of calculating the network name
     */
    val operatorAlphaShort: String? = null,

    /** Fields below from [SignalStrengthsListener.onSignalStrengthsChanged] */
    val isGsm: Boolean = false,
    @IntRange(from = 0, to = 4)
    val cdmaLevel: Int = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN,
    @IntRange(from = 0, to = 4)
    val primaryLevel: Int = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN,

    /** Fields below from [DataConnectionStateListener.onDataConnectionStateChanged] */
    val dataConnectionState: DataConnectionState = Disconnected,

    /**
     * Fields below from [DataActivityListener.onDataActivity]. See [TelephonyManager] for the
     * values
     */
    val dataActivityDirection: DataActivityModel =
        DataActivityModel(
            hasActivityIn = false,
            hasActivityOut = false,
        ),

    /** Fields below from [CarrierNetworkListener.onCarrierNetworkChange] */
    val carrierNetworkChangeActive: Boolean = false,

    /** Fields below from [DisplayInfoListener.onDisplayInfoChanged]. */

    /**
     * [resolvedNetworkType] is the [TelephonyDisplayInfo.getOverrideNetworkType] if it exists or
     * [TelephonyDisplayInfo.getNetworkType]. This is used to look up the proper network type icon
     */
    val resolvedNetworkType: ResolvedNetworkType = ResolvedNetworkType.UnknownNetworkType,
)
