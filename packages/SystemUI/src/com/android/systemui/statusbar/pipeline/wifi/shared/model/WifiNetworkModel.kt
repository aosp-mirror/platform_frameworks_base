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

package com.android.systemui.statusbar.pipeline.wifi.shared.model

import android.telephony.SubscriptionManager
import androidx.annotation.VisibleForTesting
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository

/** Provides information about the current wifi network. */
sealed class WifiNetworkModel : Diffable<WifiNetworkModel> {

    // TODO(b/238425913): Have a better, more unified strategy for diff-logging instead of
    //   copy-pasting the column names for each sub-object.

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
            row.logChange(COL_SUB_ID, SUB_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_NUM_LEVELS, NUM_LEVELS_DEFAULT)
            row.logChange(COL_SSID, null)
            row.logChange(COL_PASSPOINT_ACCESS_POINT, false)
            row.logChange(COL_ONLINE_SIGN_UP, false)
            row.logChange(COL_PASSPOINT_NAME, null)
        }
    }

    /** A model representing that the wifi information we received was invalid in some way. */
    data class Invalid(
        /** A description of why the wifi information was invalid. */
        val invalidReason: String,
    ) : WifiNetworkModel() {
        override fun toString() = "WifiNetwork.Invalid[$invalidReason]"
        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is Invalid) {
                logFull(row)
                return
            }

            if (invalidReason != prevVal.invalidReason) {
                row.logChange(COL_NETWORK_TYPE, "$TYPE_UNAVAILABLE $invalidReason")
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, "$TYPE_UNAVAILABLE $invalidReason")
            row.logChange(COL_NETWORK_ID, NETWORK_ID_DEFAULT)
            row.logChange(COL_SUB_ID, SUB_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_NUM_LEVELS, NUM_LEVELS_DEFAULT)
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

            // When changing to Inactive, we need to log diffs to all the fields.
            logFull(row)
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, TYPE_INACTIVE)
            row.logChange(COL_NETWORK_ID, NETWORK_ID_DEFAULT)
            row.logChange(COL_SUB_ID, SUB_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_NUM_LEVELS, NUM_LEVELS_DEFAULT)
            row.logChange(COL_SSID, null)
            row.logChange(COL_PASSPOINT_ACCESS_POINT, false)
            row.logChange(COL_ONLINE_SIGN_UP, false)
            row.logChange(COL_PASSPOINT_NAME, null)
        }
    }

    /**
     * A model representing that our wifi network is actually a carrier merged network, meaning it's
     * treated as more of a mobile network.
     *
     * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
     */
    data class CarrierMerged(
        /**
         * The [android.net.Network.netId] we received from
         * [android.net.ConnectivityManager.NetworkCallback] in association with this wifi network.
         *
         * Importantly, **not** [android.net.wifi.WifiInfo.getNetworkId].
         */
        val networkId: Int,

        /**
         * The subscription ID that this connection represents.
         *
         * Comes from [android.net.wifi.WifiInfo.getSubscriptionId].
         *
         * Per that method, this value must not be [INVALID_SUBSCRIPTION_ID] (if it was invalid,
         * then this is *not* a carrier merged network).
         */
        val subscriptionId: Int,

        /** The signal level, guaranteed to be 0 <= level <= numberOfLevels. */
        val level: Int,

        /** The maximum possible level. */
        val numberOfLevels: Int = MobileConnectionRepository.DEFAULT_NUM_LEVELS,
    ) : WifiNetworkModel() {
        init {
            require(level in MIN_VALID_LEVEL..numberOfLevels) {
                "0 <= wifi level <= $numberOfLevels required; level was $level"
            }
            require(subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                "subscription ID cannot be invalid"
            }
        }

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is CarrierMerged) {
                logFull(row)
                return
            }

            if (prevVal.networkId != networkId) {
                row.logChange(COL_NETWORK_ID, networkId)
            }
            if (prevVal.subscriptionId != subscriptionId) {
                row.logChange(COL_SUB_ID, subscriptionId)
            }
            if (prevVal.level != level) {
                row.logChange(COL_LEVEL, level)
            }
            if (prevVal.numberOfLevels != numberOfLevels) {
                row.logChange(COL_NUM_LEVELS, numberOfLevels)
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, TYPE_CARRIER_MERGED)
            row.logChange(COL_NETWORK_ID, networkId)
            row.logChange(COL_SUB_ID, subscriptionId)
            row.logChange(COL_VALIDATED, true)
            row.logChange(COL_LEVEL, level)
            row.logChange(COL_NUM_LEVELS, numberOfLevels)
            row.logChange(COL_SSID, null)
            row.logChange(COL_PASSPOINT_ACCESS_POINT, false)
            row.logChange(COL_ONLINE_SIGN_UP, false)
            row.logChange(COL_PASSPOINT_NAME, null)
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

        /** The wifi signal level, guaranteed to be 0 <= level <= 4. */
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
                logFull(row)
                return
            }

            if (prevVal.networkId != networkId) {
                row.logChange(COL_NETWORK_ID, networkId)
            }
            if (prevVal.isValidated != isValidated) {
                row.logChange(COL_VALIDATED, isValidated)
            }
            if (prevVal.level != level) {
                row.logChange(COL_LEVEL, level)
            }
            if (prevVal.ssid != ssid) {
                row.logChange(COL_SSID, ssid)
            }

            // TODO(b/238425913): The passpoint-related values are frequently never used, so it
            //   would be great to not log them when they're not used.
            if (prevVal.isPasspointAccessPoint != isPasspointAccessPoint) {
                row.logChange(COL_PASSPOINT_ACCESS_POINT, isPasspointAccessPoint)
            }
            if (
                prevVal.isOnlineSignUpForPasspointAccessPoint !=
                    isOnlineSignUpForPasspointAccessPoint
            ) {
                row.logChange(COL_ONLINE_SIGN_UP, isOnlineSignUpForPasspointAccessPoint)
            }
            if (prevVal.passpointProviderFriendlyName != passpointProviderFriendlyName) {
                row.logChange(COL_PASSPOINT_NAME, passpointProviderFriendlyName)
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, TYPE_ACTIVE)
            row.logChange(COL_NETWORK_ID, networkId)
            row.logChange(COL_SUB_ID, null)
            row.logChange(COL_VALIDATED, isValidated)
            row.logChange(COL_LEVEL, level)
            row.logChange(COL_NUM_LEVELS, null)
            row.logChange(COL_SSID, ssid)
            row.logChange(COL_PASSPOINT_ACCESS_POINT, isPasspointAccessPoint)
            row.logChange(COL_ONLINE_SIGN_UP, isOnlineSignUpForPasspointAccessPoint)
            row.logChange(COL_PASSPOINT_NAME, passpointProviderFriendlyName)
        }

        override fun toString(): String {
            // Only include the passpoint-related values in the string if we have them. (Most
            // networks won't have them so they'll be mostly clutter.)
            val passpointString =
                if (
                    isPasspointAccessPoint ||
                        isOnlineSignUpForPasspointAccessPoint ||
                        passpointProviderFriendlyName != null
                ) {
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
            @VisibleForTesting internal const val MAX_VALID_LEVEL = 4
        }
    }

    companion object {
        @VisibleForTesting internal const val MIN_VALID_LEVEL = 0
    }
}

const val TYPE_CARRIER_MERGED = "CarrierMerged"
const val TYPE_UNAVAILABLE = "Unavailable"
const val TYPE_INACTIVE = "Inactive"
const val TYPE_ACTIVE = "Active"

const val COL_NETWORK_TYPE = "type"
const val COL_NETWORK_ID = "networkId"
const val COL_SUB_ID = "subscriptionId"
const val COL_VALIDATED = "isValidated"
const val COL_LEVEL = "level"
const val COL_NUM_LEVELS = "maxLevel"
const val COL_SSID = "ssid"
const val COL_PASSPOINT_ACCESS_POINT = "isPasspointAccessPoint"
const val COL_ONLINE_SIGN_UP = "isOnlineSignUpForPasspointAccessPoint"
const val COL_PASSPOINT_NAME = "passpointProviderFriendlyName"

val LEVEL_DEFAULT: String? = null
val NUM_LEVELS_DEFAULT: String? = null
val NETWORK_ID_DEFAULT: String? = null
val SUB_ID_DEFAULT: String? = null
