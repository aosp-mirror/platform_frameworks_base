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

import com.android.settingslib.mobile.TelephonyIcons
import java.lang.IllegalArgumentException

/**
 * Box for all policy-related state used in [MobileSignalController]
 */
internal class MobileState(
    @JvmField var networkName: String? = null,
    @JvmField var networkNameData: String? = null,
    @JvmField var dataSim: Boolean = false,
    @JvmField var dataConnected: Boolean = false,
    @JvmField var isEmergency: Boolean = false,
    @JvmField var airplaneMode: Boolean = false,
    @JvmField var carrierNetworkChangeMode: Boolean = false,
    @JvmField var isDefault: Boolean = false,
    @JvmField var userSetup: Boolean = false,
    @JvmField var roaming: Boolean = false,
    // Tracks the on/off state of the defaultDataSubscription
    @JvmField var defaultDataOff: Boolean = false
) : ConnectivityState() {

    /** @return true if this state is disabled or not default data */
    val isDataDisabledOrNotDefault: Boolean
        get() = (iconGroup === TelephonyIcons.DATA_DISABLED
                || iconGroup === TelephonyIcons.NOT_DEFAULT_DATA) && userSetup

    /** @return if this state is considered to have inbound activity */
    fun hasActivityIn(): Boolean {
        return dataConnected && !carrierNetworkChangeMode && activityIn
    }

    /** @return if this state is considered to have outbound activity */
    fun hasActivityOut(): Boolean {
        return dataConnected && !carrierNetworkChangeMode && activityOut
    }

    /** @return true if this state should show a RAT icon in quick settings */
    fun showQuickSettingsRatIcon(): Boolean {
        return dataConnected || isDataDisabledOrNotDefault
    }

    override fun copyFrom(other: ConnectivityState) {
        val o = other as? MobileState ?: throw IllegalArgumentException(
                "MobileState can only update from another MobileState")

        super.copyFrom(o)
        networkName = o.networkName
        networkNameData = o.networkNameData
        dataSim = o.dataSim
        dataConnected = o.dataConnected
        isEmergency = o.isEmergency
        airplaneMode = o.airplaneMode
        carrierNetworkChangeMode = o.carrierNetworkChangeMode
        isDefault = o.isDefault
        userSetup = o.userSetup
        roaming = o.roaming
        defaultDataOff = o.defaultDataOff
    }

    override fun toString(builder: StringBuilder) {
        builder.append("connected=$connected,")
                .append(',')
                .append("dataSim=$dataSim,")
                .append("networkName=$networkName,")
                .append("networkNameData=$networkNameData,")
                .append("dataConnected=$dataConnected,")
                .append("roaming=$roaming,")
                .append("isDefault=$isDefault,")
                .append("isEmergency=$isEmergency,")
                .append("airplaneMode=$airplaneMode,")
                .append("carrierNetworkChangeMode=$carrierNetworkChangeMode,")
                .append("userSetup=$userSetup,")
                .append("defaultDataOff=$defaultDataOff,")
                .append("showQuickSettingsRatIcon=${showQuickSettingsRatIcon()}")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as MobileState

        if (networkName != other.networkName) return false
        if (networkNameData != other.networkNameData) return false
        if (dataSim != other.dataSim) return false
        if (dataConnected != other.dataConnected) return false
        if (isEmergency != other.isEmergency) return false
        if (airplaneMode != other.airplaneMode) return false
        if (carrierNetworkChangeMode != other.carrierNetworkChangeMode) return false
        if (isDefault != other.isDefault) return false
        if (userSetup != other.userSetup) return false
        if (roaming != other.roaming) return false
        if (defaultDataOff != other.defaultDataOff) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (networkName?.hashCode() ?: 0)
        result = 31 * result + (networkNameData?.hashCode() ?: 0)
        result = 31 * result + dataSim.hashCode()
        result = 31 * result + dataConnected.hashCode()
        result = 31 * result + isEmergency.hashCode()
        result = 31 * result + airplaneMode.hashCode()
        result = 31 * result + carrierNetworkChangeMode.hashCode()
        result = 31 * result + isDefault.hashCode()
        result = 31 * result + userSetup.hashCode()
        result = 31 * result + roaming.hashCode()
        result = 31 * result + defaultDataOff.hashCode()
        return result
    }
}
