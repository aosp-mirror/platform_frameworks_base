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

import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.UNKNOWN_SSID
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo
import android.telephony.SubscriptionManager
import androidx.annotation.VisibleForTesting
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel.Active.Companion.MAX_VALID_LEVEL
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel.Active.Companion.isValid
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel.Active.Companion.of
import com.android.wifitrackerlib.HotspotNetworkEntry.DeviceType
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_UNREACHABLE

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
            row.logChange(COL_SUB_ID, SUB_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_NUM_LEVELS, NUM_LEVELS_DEFAULT)
            row.logChange(COL_SSID, null)
            row.logChange(COL_HOTSPOT, null)
        }
    }

    /** A model representing that the wifi information we received was invalid in some way. */
    data class Invalid(
        /** A description of why the wifi information was invalid. */
        val invalidReason: String,
    ) : WifiNetworkModel() {
        override fun toString() = "WifiNetwork.Invalid[reason=$invalidReason]"

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is Invalid) {
                logFull(row)
                return
            }

            if (invalidReason != prevVal.invalidReason) {
                row.logChange(COL_NETWORK_TYPE, "$TYPE_UNAVAILABLE[reason=$invalidReason]")
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, "$TYPE_UNAVAILABLE[reason=$invalidReason]")
            row.logChange(COL_SUB_ID, SUB_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_NUM_LEVELS, NUM_LEVELS_DEFAULT)
            row.logChange(COL_SSID, null)
            row.logChange(COL_HOTSPOT, null)
        }
    }

    /** A model representing that we have no active wifi network. */
    data class Inactive(
        /** An optional description of why the wifi information was inactive. */
        val inactiveReason: String? = null,
    ) : WifiNetworkModel() {
        override fun toString() = "WifiNetwork.Inactive[reason=$inactiveReason]"

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is Inactive) {
                logFull(row)
                return
            }

            if (inactiveReason != prevVal.inactiveReason) {
                row.logChange(COL_NETWORK_TYPE, "$TYPE_INACTIVE[reason=$inactiveReason]")
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, "$TYPE_INACTIVE[reason=$inactiveReason]")
            row.logChange(COL_SUB_ID, SUB_ID_DEFAULT)
            row.logChange(COL_VALIDATED, false)
            row.logChange(COL_LEVEL, LEVEL_DEFAULT)
            row.logChange(COL_NUM_LEVELS, NUM_LEVELS_DEFAULT)
            row.logChange(COL_SSID, null)
            row.logChange(COL_HOTSPOT, null)
        }
    }

    /**
     * A model representing that our wifi network is actually a carrier merged network, meaning it's
     * treated as more of a mobile network.
     *
     * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
     *
     * IMPORTANT: Do *not* call [copy] on this class. Instead, use the factory [of] methods. [of]
     * will verify preconditions correctly.
     */
    data class CarrierMerged
    private constructor(
        /**
         * The subscription ID that this connection represents.
         *
         * Comes from [android.net.wifi.WifiInfo.getSubscriptionId].
         *
         * Per that method, this value must not be [SubscriptionManager.INVALID_SUBSCRIPTION_ID] (if
         * it was invalid, then this is *not* a carrier merged network).
         */
        val subscriptionId: Int,

        /** The signal level, required to be 0 <= level <= numberOfLevels. */
        val level: Int,

        /** The maximum possible level. */
        val numberOfLevels: Int,
    ) : WifiNetworkModel() {
        companion object {
            /**
             * Creates a [CarrierMerged] instance, or an [Invalid] instance if any of the arguments
             * are invalid.
             */
            fun of(
                subscriptionId: Int,
                level: Int,
                numberOfLevels: Int = MobileConnectionRepository.DEFAULT_NUM_LEVELS
            ): WifiNetworkModel {
                if (!subscriptionId.isSubscriptionIdValid()) {
                    return Invalid(INVALID_SUB_ID_ERROR_STRING)
                }
                if (!level.isLevelValid(numberOfLevels)) {
                    return Invalid(getInvalidLevelErrorString(level, numberOfLevels))
                }
                return CarrierMerged(subscriptionId, level, numberOfLevels)
            }

            private fun Int.isLevelValid(maxLevel: Int): Boolean {
                return this != WIFI_LEVEL_UNREACHABLE && this in MIN_VALID_LEVEL..maxLevel
            }

            private fun getInvalidLevelErrorString(level: Int, maxLevel: Int): String {
                return "Wifi network was carrier merged but had invalid level. " +
                    "$MIN_VALID_LEVEL <= wifi level <= $maxLevel required; " +
                    "level was $level"
            }

            private fun Int.isSubscriptionIdValid(): Boolean {
                return this != SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }

            private const val INVALID_SUB_ID_ERROR_STRING =
                "Wifi network was carrier merged but had invalid sub ID"
        }

        init {
            require(level.isLevelValid(numberOfLevels)) {
                "${getInvalidLevelErrorString(level, numberOfLevels)}. $DO_NOT_USE_COPY_ERROR"
            }
            require(subscriptionId.isSubscriptionIdValid()) {
                "$INVALID_SUB_ID_ERROR_STRING. $DO_NOT_USE_COPY_ERROR"
            }
        }

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is CarrierMerged) {
                logFull(row)
                return
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
            row.logChange(COL_SUB_ID, subscriptionId)
            row.logChange(COL_VALIDATED, true)
            row.logChange(COL_LEVEL, level)
            row.logChange(COL_NUM_LEVELS, numberOfLevels)
            row.logChange(COL_SSID, null)
            row.logChange(COL_HOTSPOT, null)
        }
    }

    /**
     * Provides information about an active wifi network.
     *
     * IMPORTANT: Do *not* call [copy] on this class. Instead, use the factory [of] method. [of]
     * will verify preconditions correctly.
     */
    data class Active
    private constructor(
        /** See [android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED]. */
        val isValidated: Boolean,

        /** The wifi signal level, required to be 0 <= level <= 4. */
        val level: Int,

        /** See [android.net.wifi.WifiInfo.ssid]. */
        val ssid: String?,

        /**
         * The type of device providing a hotspot connection, or [HotspotDeviceType.NONE] if this
         * isn't a hotspot connection.
         */
        val hotspotDeviceType: HotspotDeviceType,
    ) : WifiNetworkModel() {
        companion object {
            /**
             * Creates an [Active] instance, or an [Inactive] instance if any of the arguments are
             * invalid.
             */
            @JvmStatic
            fun of(
                isValidated: Boolean = false,
                level: Int,
                ssid: String? = null,
                hotspotDeviceType: HotspotDeviceType = HotspotDeviceType.NONE,
            ): WifiNetworkModel {
                if (!level.isValid()) {
                    return Inactive(getInvalidLevelErrorString(level))
                }
                return Active(isValidated, level, ssid, hotspotDeviceType)
            }

            private fun Int.isValid(): Boolean {
                return this != WIFI_LEVEL_UNREACHABLE && this in MIN_VALID_LEVEL..MAX_VALID_LEVEL
            }

            private fun getInvalidLevelErrorString(level: Int): String {
                return "Wifi network was active but had invalid level. " +
                    "$MIN_VALID_LEVEL <= wifi level <= $MAX_VALID_LEVEL required; " +
                    "level was $level"
            }

            @VisibleForTesting internal const val MAX_VALID_LEVEL = WifiEntry.WIFI_LEVEL_MAX
        }

        init {
            require(level.isValid()) {
                "${getInvalidLevelErrorString(level)}. $DO_NOT_USE_COPY_ERROR"
            }
        }

        /** Returns true if this network has a valid SSID and false otherwise. */
        fun hasValidSsid(): Boolean {
            return ssid != null && ssid != UNKNOWN_SSID
        }

        override fun logDiffs(prevVal: WifiNetworkModel, row: TableRowLogger) {
            if (prevVal !is Active) {
                logFull(row)
                return
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
            if (prevVal.hotspotDeviceType != hotspotDeviceType) {
                row.logChange(COL_HOTSPOT, hotspotDeviceType.name)
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_TYPE, TYPE_ACTIVE)
            row.logChange(COL_SUB_ID, null)
            row.logChange(COL_VALIDATED, isValidated)
            row.logChange(COL_LEVEL, level)
            row.logChange(COL_NUM_LEVELS, null)
            row.logChange(COL_SSID, ssid)
            row.logChange(COL_HOTSPOT, hotspotDeviceType.name)
        }
    }

    companion object {
        @VisibleForTesting internal const val MIN_VALID_LEVEL = WifiEntry.WIFI_LEVEL_MIN
    }

    /**
     * Enum for the type of device providing the hotspot connection, or [NONE] if this connection
     * isn't a hotspot.
     */
    enum class HotspotDeviceType {
        /* This wifi connection isn't a hotspot. */
        NONE,
        /** The device type for this hotspot is unknown. */
        UNKNOWN,
        PHONE,
        TABLET,
        LAPTOP,
        WATCH,
        AUTO,
        /** The device type sent for this hotspot is invalid to SysUI. */
        INVALID,
    }

    /**
     * Converts a device type from [com.android.wifitrackerlib.HotspotNetworkEntry.deviceType] to
     * our internal representation.
     */
    fun @receiver:DeviceType Int.toHotspotDeviceType(): HotspotDeviceType {
        return when (this) {
            NetworkProviderInfo.DEVICE_TYPE_UNKNOWN -> HotspotDeviceType.UNKNOWN
            NetworkProviderInfo.DEVICE_TYPE_PHONE -> HotspotDeviceType.PHONE
            NetworkProviderInfo.DEVICE_TYPE_TABLET -> HotspotDeviceType.TABLET
            NetworkProviderInfo.DEVICE_TYPE_LAPTOP -> HotspotDeviceType.LAPTOP
            NetworkProviderInfo.DEVICE_TYPE_WATCH -> HotspotDeviceType.WATCH
            NetworkProviderInfo.DEVICE_TYPE_AUTO -> HotspotDeviceType.AUTO
            else -> HotspotDeviceType.INVALID
        }
    }
}

const val TYPE_CARRIER_MERGED = "CarrierMerged"
const val TYPE_UNAVAILABLE = "Unavailable"
const val TYPE_INACTIVE = "Inactive"
const val TYPE_ACTIVE = "Active"

const val COL_NETWORK_TYPE = "type"
const val COL_SUB_ID = "subscriptionId"
const val COL_VALIDATED = "isValidated"
const val COL_LEVEL = "level"
const val COL_NUM_LEVELS = "maxLevel"
const val COL_SSID = "ssid"
const val COL_HOTSPOT = "hotspot"

val LEVEL_DEFAULT: String? = null
val NUM_LEVELS_DEFAULT: String? = null
val SUB_ID_DEFAULT: String? = null

private const val DO_NOT_USE_COPY_ERROR =
    "This should only be an issue if the caller incorrectly used `copy` to get a new instance. " +
        "Please use the `of` method instead."
