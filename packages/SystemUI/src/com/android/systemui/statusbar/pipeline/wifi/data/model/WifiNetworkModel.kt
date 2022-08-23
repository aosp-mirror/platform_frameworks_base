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

/** Provides information about the current wifi network. */
sealed class WifiNetworkModel {
    /** A model representing that we have no active wifi network. */
    object Inactive : WifiNetworkModel()

    /** Provides information about an active wifi network. */
    class Active(
        /**
         * The [android.net.Network.netId] we received from
         * [android.net.ConnectivityManager.NetworkCallback] in association with this wifi network.
         *
         * Importantly, **not** [android.net.wifi.WifiInfo.getNetworkId].
         */
        val networkId: Int,

        /** See [android.net.wifi.WifiInfo.ssid]. */
        val ssid: String? = null,

        /** See [android.net.wifi.WifiInfo.isPasspointAp]. */
        val isPasspointAccessPoint: Boolean = false,

        /** See [android.net.wifi.WifiInfo.isOsuAp]. */
        val isOnlineSignUpForPasspointAccessPoint: Boolean = false,

        /** See [android.net.wifi.WifiInfo.passpointProviderFriendlyName]. */
        val passpointProviderFriendlyName: String? = null,
    ) : WifiNetworkModel()
}
