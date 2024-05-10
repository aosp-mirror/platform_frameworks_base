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

import java.lang.StringBuilder

internal class WifiState(
    @JvmField var ssid: String? = null,
    @JvmField var isTransient: Boolean = false,
    @JvmField var isDefault: Boolean = false,
    @JvmField var statusLabel: String? = null,
    @JvmField var isCarrierMerged: Boolean = false,
    /**
     * True if the current default connection is validated for *any* transport, not just wifi.
     * (Specifically TRANSPORT_CELLULAR *or* TRANSPORT_WIFI.)
     *
     * This should *only* be used when calculating information for the carrier merged connection and
     * *not* for typical wifi connections. See b/225902574.
     */
    @JvmField var isDefaultConnectionValidated: Boolean = false,
    @JvmField var subId: Int = 0
) : ConnectivityState() {

    public override fun copyFrom(s: ConnectivityState) {
        super.copyFrom(s)
        val state = s as WifiState
        ssid = state.ssid
        isTransient = state.isTransient
        isDefault = state.isDefault
        statusLabel = state.statusLabel
        isCarrierMerged = state.isCarrierMerged
        isDefaultConnectionValidated = state.isDefaultConnectionValidated
        subId = state.subId
    }

    override fun toString(builder: StringBuilder) {
        super.toString(builder)
        builder.append(",ssid=").append(ssid)
                .append(",isTransient=").append(isTransient)
                .append(",isDefault=").append(isDefault)
                .append(",statusLabel=").append(statusLabel)
                .append(",isCarrierMerged=").append(isCarrierMerged)
                .append(",isDefaultConnectionValidated=").append(isDefaultConnectionValidated)
                .append(",subId=").append(subId)
    }

    override fun tableColumns(): List<String> {
        val columns = listOf("ssid",
                "isTransient",
                "isDefault",
                "statusLabel",
                "isCarrierMerged",
                "isDefaultConnectionValidated",
                "subId")

        return super.tableColumns() + columns
    }

    override fun tableData(): List<String> {
        val data = listOf(ssid,
        isTransient,
        isDefault,
        statusLabel,
        isCarrierMerged,
        isDefaultConnectionValidated,
        subId).map {
            it.toString()
        }

        return super.tableData() + data
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as WifiState

        if (ssid != other.ssid) return false
        if (isTransient != other.isTransient) return false
        if (isDefault != other.isDefault) return false
        if (statusLabel != other.statusLabel) return false
        if (isCarrierMerged != other.isCarrierMerged) return false
        if (isDefaultConnectionValidated != other.isDefaultConnectionValidated) return false
        if (subId != other.subId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (ssid?.hashCode() ?: 0)
        result = 31 * result + isTransient.hashCode()
        result = 31 * result + isDefault.hashCode()
        result = 31 * result + (statusLabel?.hashCode() ?: 0)
        result = 31 * result + isCarrierMerged.hashCode()
        result = 31 * result + isDefaultConnectionValidated.hashCode()
        result = 31 * result + subId
        return result
    }
}
