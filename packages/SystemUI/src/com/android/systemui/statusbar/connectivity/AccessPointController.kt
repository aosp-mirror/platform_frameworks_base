/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity

import android.content.Intent
import android.os.UserManager
import android.provider.Settings

import com.android.wifitrackerlib.MergedCarrierEntry
import com.android.wifitrackerlib.WifiEntry

/**
 * Tracks changes in access points.  Allows listening for changes, scanning for new APs,
 * and connecting to new ones.
 */
interface AccessPointController {
    fun addAccessPointCallback(callback: AccessPointCallback)
    fun removeAccessPointCallback(callback: AccessPointCallback)

    /**
     * Request an updated list of available access points
     *
     * This method will trigger a call to [AccessPointCallback.onAccessPointsChanged]
     */
    fun scanForAccessPoints()

    /**
     * Gets the current [MergedCarrierEntry]. If null, this call generates a call to
     * [AccessPointCallback.onAccessPointsChanged]
     *
     * @return the current [MergedCarrierEntry], if one exists
     */
    fun getMergedCarrierEntry(): MergedCarrierEntry?

    /** @return the appropriate icon id for the given [WifiEntry]'s level */
    fun getIcon(ap: WifiEntry): Int

    /**
     * Connects to a [WifiEntry] if it's saved or does not require security.
     *
     * If the entry is not saved and requires security, will trigger
     * [AccessPointCallback.onSettingsActivityTriggered].
     *
     * @param ap
     * @return `true` if [AccessPointCallback.onSettingsActivityTriggered] is triggered
     */
    fun connect(ap: WifiEntry?): Boolean

    /**
     * `true` if the current user does not have the [UserManager.DISALLOW_CONFIG_WIFI] restriction
     */
    fun canConfigWifi(): Boolean

    /**
     * `true` if the current user does not have the [UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS]
     * restriction set
     */
    fun canConfigMobileData(): Boolean

    interface AccessPointCallback {
        /**
         * Called whenever [scanForAccessPoints] is called, or [getMergedCarrierEntry] is called
         * with a null entry
         *
         * @param accessPoints the list of available access points, including the current connected
         * one if it exists
         */
        fun onAccessPointsChanged(accessPoints: List<@JvmSuppressWildcards WifiEntry>)

        /**
         * Called whenever [connecting][connect] to an unknown access point which has security.
         * Implementers should launch the intent in the appropriate context
         *
         * @param settingsIntent an intent for [Settings.ACTION_WIFI_SETTINGS] with
         * "wifi_start_connect_ssid" set as an extra
         */
        fun onSettingsActivityTriggered(settingsIntent: Intent?)

        /**
         * Called whenever a Wi-Fi scan is triggered.
         *
         * @param isScan Whether Wi-Fi scan is triggered or not.
         */
        fun onWifiScan(isScan: Boolean)
    }
}
