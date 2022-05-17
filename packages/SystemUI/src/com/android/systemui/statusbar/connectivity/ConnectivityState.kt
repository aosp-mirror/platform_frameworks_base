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

import android.annotation.SuppressLint
import com.android.settingslib.SignalIcon.IconGroup
import java.text.SimpleDateFormat

/**
 * Base type for various connectivity states, for use with [SignalController] and its subtypes
 */
open class ConnectivityState {
    @JvmField var connected = false
    @JvmField var enabled = false
    @JvmField var activityIn = false
    @JvmField var activityOut = false
    @JvmField var level = 0
    @JvmField var iconGroup: IconGroup? = null
    @JvmField var inetCondition = 0
    // Only for logging.
    @JvmField var rssi = 0
    // Not used for comparison, just used for logging.
    @JvmField var time: Long = 0

    override fun toString(): String {
        return if (time != 0L) {
            val builder = StringBuilder()
            toString(builder)
            builder.toString()
        } else {
            "Empty " + javaClass.simpleName
        }
    }

    protected open fun tableColumns(): List<String> {
        return listOf(
            "connected",
            "enabled",
            "activityIn",
            "activityOut",
            "level",
            "iconGroup",
            "inetCondition",
            "rssi",
            "time")
    }

    protected open fun tableData(): List<String> {
        return listOf(
            connected,
            enabled,
            activityIn,
            activityOut,
            level,
            iconGroup,
            inetCondition,
            rssi,
            sSDF.format(time)).map {
                it.toString()
        }
    }

    protected open fun copyFrom(other: ConnectivityState) {
        connected = other.connected
        enabled = other.enabled
        activityIn = other.activityIn
        activityOut = other.activityOut
        level = other.level
        iconGroup = other.iconGroup
        inetCondition = other.inetCondition
        rssi = other.rssi
        time = other.time
    }

    protected open fun toString(builder: StringBuilder) {
        builder.append("connected=$connected,")
                .append("enabled=$enabled,")
                .append("level=$level,")
                .append("inetCondition=$inetCondition,")
                .append("iconGroup=$iconGroup,")
                .append("activityIn=$activityIn,")
                .append("activityOut=$activityOut,")
                .append("rssi=$rssi,")
                .append("lastModified=${sSDF.format(time)}")
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other.javaClass != javaClass) return false

        val o = other as ConnectivityState
        return o.connected == connected &&
                o.enabled == enabled &&
                o.level == level &&
                o.inetCondition == inetCondition &&
                o.iconGroup === iconGroup &&
                o.activityIn == activityIn &&
                o.activityOut == activityOut &&
                o.rssi == rssi
    }

    override fun hashCode(): Int {
        var result = connected.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + activityIn.hashCode()
        result = 31 * result + activityOut.hashCode()
        result = 31 * result + level
        result = 31 * result + (iconGroup?.hashCode() ?: 0)
        result = 31 * result + inetCondition
        result = 31 * result + rssi
        result = 31 * result + time.hashCode()
        return result
    }
}

// No locale as it's only used for logging purposes
@SuppressLint("SimpleDateFormat")
private val sSDF = SimpleDateFormat("MM-dd HH:mm:ss.SSS")
