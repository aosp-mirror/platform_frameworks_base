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

package com.android.systemui.statusbar.pipeline.wifi.data.model

import androidx.annotation.VisibleForTesting
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.log.table.Diffable

/** Provides information about the current wifi network. */
sealed class WifiNetworkModel : Diffable<WifiNetworkModel> {

    /**
     * A model representing that we couldn't fetch any wifi information.
     *
     * This is only used with [DisabledWifiRepository], where [WifiManager] is null.
     */
    object Unavailable : WifiNetworkModel() {
        override fun toString() = "WifiNetwork.Unavailable"
        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal is Unavailable) {
                return
            }

            logFull(row)
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, TYPE_UNAVAILABLE)
            row.logChange(COL_NETWORK_ID, NETWORK_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_SSID, null)
            row.logChange(COL_PASSPOINT_ACCESS_POINT, false)
            row.logChange(COL_ONLINE_SIGN_UP, false)
            row.logChange(COL_PASSPOINT_NAME, null)
        }
    }

    /** A model representing that we have no active wifi network. */
    object Inactive : WifiNetworkModel() {
        override fun toString() = "WifiNetwork.Inactive"

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal is Inactive) {
                return
            }

            if (prevVal is CarrierMerged) {
                // The only difference between CarrierMerged and Inactive is the type
                row.logChange(COL_NETWORK_TYPE, TYPE_INACTIVE)
                return
            }

            // When changing from Active to Inactive, we need to log diffs to all the fields.
            logFullNonActiveNetwork(TYPE_INACTIVE, row)
        }

        override fun logFull(row: TableRowLogger) {
            logFullNonActiveNetwork(TYPE_INACTIVE, row)
        }
    }

    /**
     * A model representing that our wifi network is actually a carrier merged network, meaning it's
     * treated as more of a mobile network.
     *
     * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
     */
    object CarrierMerged : WifiNetworkModel() {
        override fun toString() = "WifiNetwork.CarrierMerged"

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal is CarrierMerged) {
                return
            }

            if (prevVal is Inactive) {
                // The only difference between CarrierMerged and Inactive is the type.
                row.logChange(COL_NETWORK_TYPE, TYPE_CARRIER_MERGED)
                return
            }

            // When changing from Active to CarrierMerged, we need to log diffs to all the fields.
            logFullNonActiveNetwork(TYPE_CARRIER_MERGED, row)
        }
    }

    /** Provides information about an active wifi network. */
    data class Active(
        /**
         * The [android.net.Network.netId] we received from
         * [android.net.ConnectivityManager.NetworkCallback] in association with this wifi network.
         *
         * Importantly, **not** [android.net.wifi.WifiInfo.getNetworkId].
         */
        val networkId: Int,

        /** See [android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED]. */
        val isValidated: Boolean = false,

        /**
         * The wifi signal level, guaranteed to be 0 <= level <= 4.
         */
        val level: Int,

        /** See [android.net.wifi.WifiInfo.ssid]. */
        val ssid: String? = null,

        /** See [android.net.wifi.WifiInfo.isPasspointAp]. */
        val isPasspointAccessPoint: Boolean = false,

        /** See [android.net.wifi.WifiInfo.isOsuAp]. */
        val isOnlineSignUpForPasspointAccessPoint: Boolean = false,

        /** See [android.net.wifi.WifiInfo.passpointProviderFriendlyName]. */
        val passpointProviderFriendlyName: String? = null,
    ) : WifiNetworkModel() {
        init {
            require(level in MIN_VALID_LEVEL..MAX_VALID_LEVEL) {
                "0 <= wifi level <= 4 required; level was $level"
            }
        }

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is Active) {
                row.logChange(COL_NETWORK_TYPE, TYPE_ACTIVE)
            }

            if (prevVal !is Active || prevVal.networkId != networkId) {
                row.logChange(COL_NETWORK_ID, networkId)
            }
            if (prevVal !is Active || prevVal.isValidated != isValidated) {
                row.logChange(COL_VALIDATED, isValidated)
            }
            if (prevVal !is Active || prevVal.level != level) {
                row.logChange(COL_LEVEL, level)
            }
            if (prevVal !is Active || prevVal.ssid != ssid) {
                row.logChange(COL_SSID, ssid)
            }

            // TODO(b/238425913): The passpoint-related values are frequently never used, so it
            //   would be great to not log them when they're not used.
            if (prevVal !is Active || prevVal.isPasspointAccessPoint != isPasspointAccessPoint) {
                row.logChange(COL_PASSPOINT_ACCESS_POINT, isPasspointAccessPoint)
            }
            if (prevVal !is Active ||
                prevVal.isOnlineSignUpForPasspointAccessPoint !=
                isOnlineSignUpForPasspointAccessPoint) {
                row.logChange(COL_ONLINE_SIGN_UP, isOnlineSignUpForPasspointAccessPoint)
            }
            if (prevVal !is Active ||
                prevVal.passpointProviderFriendlyName != passpointProviderFriendlyName) {
                row.logChange(COL_PASSPOINT_NAME, passpointProviderFriendlyName)
            }
        }

        override fun toString(): String {
            // Only include the passpoint-related values in the string if we have them. (Most
            // networks won't have them so they'll be mostly clutter.)
            val passpointString =
                if (isPasspointAccessPoint ||
                    isOnlineSignUpForPasspointAccessPoint ||
                    passpointProviderFriendlyName != null) {
                    ", isPasspointAp=$isPasspointAccessPoint, " +
                        "isOnlineSignUpForPasspointAp=$isOnlineSignUpForPasspointAccessPoint, " +
                        "passpointName=$passpointProviderFriendlyName"
            } else {
                ""
            }

            return "WifiNetworkModel.Active(networkId=$networkId, isValidated=$isValidated, " +
                "level=$level, ssid=$ssid$passpointString)"
        }

        companion object {
            @VisibleForTesting
            internal const val MIN_VALID_LEVEL = 0
            @VisibleForTesting
            internal const val MAX_VALID_LEVEL = 4
        }
    }

    internal fun logFullNonActiveNetwork(type: String, row: TableRowLogger) {
        row.logChange(COL_NETWORK_TYPE, type)
        row.logChange(COL_NETWORK_ID, NETWORK_ID_DEFAULT)
        row.logChange(COL_VALIDATED, false)
        row.logChange(COL_LEVEL, LEVEL_DEFAULT)
        row.logChange(COL_SSID, null)
        row.logChange(COL_PASSPOINT_ACCESS_POINT, false)
        row.logChange(COL_ONLINE_SIGN_UP, false)
        row.logChange(COL_PASSPOINT_NAME, null)
    }
}

const val TYPE_CARRIER_MERGED = "CarrierMerged"
const val TYPE_UNAVAILABLE = "Unavailable"
const val TYPE_INACTIVE = "Inactive"
const val TYPE_ACTIVE = "Active"

const val COL_NETWORK_TYPE = "type"
const val COL_NETWORK_ID = "networkId"
const val COL_VALIDATED = "isValidated"
const val COL_LEVEL = "level"
const val COL_SSID = "ssid"
const val COL_PASSPOINT_ACCESS_POINT = "isPasspointAccessPoint"
const val COL_ONLINE_SIGN_UP = "isOnlineSignUpForPasspointAccessPoint"
const val COL_PASSPOINT_NAME = "passpointProviderFriendlyName"

val LEVEL_DEFAULT: String? = null
val NETWORK_ID_DEFAULT: String? = null
