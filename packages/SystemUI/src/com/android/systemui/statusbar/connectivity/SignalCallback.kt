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

import android.telephony.SubscriptionInfo

/**
 * SignalCallback contains all of the connectivity updates from [NetworkController]. Implement this
 * interface to be able to draw iconography for Wi-Fi, mobile data, ethernet, call strength
 * indicators, etc.
 */
interface SignalCallback {
    /**
     * Called when the Wi-Fi iconography has been updated. Implement this method to draw Wi-Fi icons
     *
     * @param wifiIndicators a box type containing enough information to properly draw a Wi-Fi icon
     */
    fun setWifiIndicators(wifiIndicators: WifiIndicators) {}

    /**
     * Called when the mobile iconography has been updated. Implement this method to draw mobile
     * indicators
     *
     * @param mobileDataIndicators a box type containing enough information to properly draw
     * mobile data icons
     *
     * NOTE: phones can have multiple subscriptions, so this [mobileDataIndicators] object should be
     * indexed based on its [subId][MobileDataIndicators.subId]
     */
    fun setMobileDataIndicators(mobileDataIndicators: MobileDataIndicators) {}

    /**
     * Called when the list of mobile data subscriptions has changed. Use this method as a chance
     * to remove views that are no longer needed, or to make room for new icons to come in
     *
     * @param subs a [SubscriptionInfo] for each subscription that we know about
     */
    fun setSubs(subs: List<@JvmSuppressWildcards SubscriptionInfo>) {}

    /**
     * Called when:
     * 1. The number of [MobileSignalController]s goes to 0 while mobile data is enabled
     * OR
     * 2. The presence of any SIM changes
     *
     * @param show whether or not to show a "no sim" view
     * @param simDetected whether any SIM is detected or not
     */
    fun setNoSims(show: Boolean, simDetected: Boolean) {}

    /**
     * Called when there is any update to the ethernet iconography. Implement this method to set an
     * ethernet icon
     *
     * @param icon an [IconState] for the current ethernet status
     */
    fun setEthernetIndicators(icon: IconState) {}

    /**
     * Called whenever airplane mode changes
     *
     * @param icon an [IconState] for the current airplane mode status
     */
    fun setIsAirplaneMode(icon: IconState) {}

    /**
     * Called whenever the mobile data feature enabled state changes
     *
     * @param enabled the current mobile data feature ennabled state
     */
    fun setMobileDataEnabled(enabled: Boolean) {}

    /**
     * Callback for listeners to be able to update the connectivity status
     * @param noDefaultNetwork whether there is any default network.
     * @param noValidatedNetwork whether there is any validated network.
     * @param noNetworksAvailable whether there is any WiFi networks available.
     */
    fun setConnectivityStatus(
        noDefaultNetwork: Boolean,
        noValidatedNetwork: Boolean,
        noNetworksAvailable: Boolean
    ) { }

    /**
     * Callback for listeners to be able to update the call indicator
     * @param statusIcon the icon for the call indicator
     * @param subId subscription ID for which to update the UI
     */
    fun setCallIndicator(statusIcon: IconState, subId: Int) {}
}

/** Box type for [SignalCallback.setWifiIndicators] */
data class WifiIndicators(
    @JvmField val enabled: Boolean,
    @JvmField val statusIcon: IconState?,
    @JvmField val qsIcon: IconState?,
    @JvmField val activityIn: Boolean,
    @JvmField val activityOut: Boolean,
    @JvmField val description: String?,
    @JvmField val isTransient: Boolean,
    @JvmField val statusLabel: String?
) {
    override fun toString(): String {
        return StringBuilder("WifiIndicators[")
                .append("enabled=").append(enabled)
                .append(",statusIcon=").append(statusIcon?.toString() ?: "")
                .append(",qsIcon=").append(qsIcon?.toString() ?: "")
                .append(",activityIn=").append(activityIn)
                .append(",activityOut=").append(activityOut)
                .append(",qsDescription=").append(description)
                .append(",isTransient=").append(isTransient)
                .append(",statusLabel=").append(statusLabel)
                .append(']').toString()
    }
}

/** Box type for [SignalCallback.setMobileDataIndicators] */
data class MobileDataIndicators(
    @JvmField val statusIcon: IconState?,
    @JvmField val qsIcon: IconState?,
    @JvmField val statusType: Int,
    @JvmField val qsType: Int,
    @JvmField val activityIn: Boolean,
    @JvmField val activityOut: Boolean,
    @JvmField val typeContentDescription: CharSequence?,
    @JvmField val typeContentDescriptionHtml: CharSequence?,
    @JvmField val qsDescription: CharSequence?,
    @JvmField val subId: Int,
    @JvmField val roaming: Boolean,
    @JvmField val showTriangle: Boolean
) {
    override fun toString(): String {
        return java.lang.StringBuilder("MobileDataIndicators[")
                .append("statusIcon=").append(statusIcon?.toString() ?: "")
                .append(",qsIcon=").append(qsIcon?.toString() ?: "")
                .append(",statusType=").append(statusType)
                .append(",qsType=").append(qsType)
                .append(",activityIn=").append(activityIn)
                .append(",activityOut=").append(activityOut)
                .append(",typeContentDescription=").append(typeContentDescription)
                .append(",typeContentDescriptionHtml=").append(typeContentDescriptionHtml)
                .append(",description=").append(qsDescription)
                .append(",subId=").append(subId)
                .append(",roaming=").append(roaming)
                .append(",showTriangle=").append(showTriangle)
                .append(']').toString()
    }
}

/** Box for an icon with its visibility and content description */
data class IconState(
    @JvmField val visible: Boolean,
    @JvmField val icon: Int,
    @JvmField val contentDescription: String
) {
    override fun toString(): String {
        val builder = java.lang.StringBuilder()
        return builder.append("[visible=").append(visible).append(',')
                .append("icon=").append(icon).append(',')
                .append("contentDescription=").append(contentDescription).append(']')
                .toString()
    }
}