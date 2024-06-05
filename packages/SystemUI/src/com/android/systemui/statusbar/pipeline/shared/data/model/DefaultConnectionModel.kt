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

package com.android.systemui.statusbar.pipeline.shared.data.model

import android.net.NetworkCapabilities
import com.android.systemui.log.core.LogMessage

/**
 * A model for all of the current default connections(s).
 *
 * Uses different classes for each connection type to ensure type safety when setting the values.
 *
 * Important: We generally expect there to be only *one* default network at a time (with the
 * exception of carrier merged). Specifically, we don't expect to ever have both wifi *and* cellular
 * as default at the same time. However, the framework network callbacks don't provide any
 * guarantees about why types of network could be default at the same time, so we don't enforce any
 * guarantees on this class.
 */
data class DefaultConnectionModel(
    /** Wifi's status as default or not. */
    val wifi: Wifi = Wifi(isDefault = false),

    /** Mobile's status as default or not. */
    val mobile: Mobile = Mobile(isDefault = false),

    /**
     * True if the current default network represents a carrier merged network, and false otherwise.
     * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
     *
     * Important: A carrier merged network can come in as either a
     * [NetworkCapabilities.TRANSPORT_CELLULAR] *or* as a [NetworkCapabilities.TRANSPORT_WIFI]. This
     * means that when carrier merged is in effect, either:
     * - [wifi] *and* [carrierMerged] will be marked as default; or
     * - [mobile] *and* [carrierMerged] will be marked as default
     *
     * Specifically, [carrierMerged] will never be the *only* default connection.
     */
    val carrierMerged: CarrierMerged = CarrierMerged(isDefault = false),

    /** Ethernet's status as default or not. */
    val ethernet: Ethernet = Ethernet(isDefault = false),

    /** True if the default connection is currently validated and false otherwise. */
    val isValidated: Boolean = false,
) {
    data class Wifi(val isDefault: Boolean)
    data class Mobile(val isDefault: Boolean)
    data class CarrierMerged(val isDefault: Boolean)
    data class Ethernet(val isDefault: Boolean)

    /**
     * Used in conjunction with [ConnectivityInputLogger] to log this class without calling
     * [toString] on it.
     *
     * Be sure to change [messagePrinter] whenever this method is changed.
     */
    fun messageInitializer(message: LogMessage) {
        message.bool1 = wifi.isDefault
        message.bool2 = mobile.isDefault
        message.bool3 = carrierMerged.isDefault
        message.bool4 = ethernet.isDefault
        message.int1 = if (isValidated) 1 else 0
    }

    fun messagePrinter(message: LogMessage): String {
        return "DefaultConnectionModel(" +
            "wifi.isDefault=${message.bool1}, " +
            "mobile.isDefault=${message.bool2}, " +
            "carrierMerged.isDefault=${message.bool3}, " +
            "ethernet.isDefault=${message.bool4}, " +
            "isValidated=${if (message.int1 == 1) "true" else "false"})"
    }
}
