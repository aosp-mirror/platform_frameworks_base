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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.telephony.TelephonyManager.DATA_CONNECTED
import android.telephony.TelephonyManager.DATA_CONNECTING
import android.telephony.TelephonyManager.DATA_DISCONNECTED
import android.telephony.TelephonyManager.DATA_DISCONNECTING
import android.telephony.TelephonyManager.DATA_HANDOVER_IN_PROGRESS
import android.telephony.TelephonyManager.DATA_SUSPENDED
import android.telephony.TelephonyManager.DATA_UNKNOWN
import android.telephony.TelephonyManager.DataState
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** Internal enum representation of the telephony data connection states */
enum class DataConnectionState : Diffable<DataConnectionState> {
    Connected,
    Connecting,
    Disconnected,
    Disconnecting,
    Suspended,
    HandoverInProgress,
    Unknown,
    Invalid;

    override fun logDiffs(prevVal: DataConnectionState, row: TableRowLogger) {
        if (prevVal != this) {
            row.logChange(COL_CONNECTION_STATE, name)
        }
    }

    companion object {
        private const val COL_CONNECTION_STATE = "connectionState"
    }
}

fun @receiver:DataState Int.toDataConnectionType(): DataConnectionState =
    when (this) {
        DATA_CONNECTED -> DataConnectionState.Connected
        DATA_CONNECTING -> DataConnectionState.Connecting
        DATA_DISCONNECTED -> DataConnectionState.Disconnected
        DATA_DISCONNECTING -> DataConnectionState.Disconnecting
        DATA_SUSPENDED -> DataConnectionState.Suspended
        DATA_HANDOVER_IN_PROGRESS -> DataConnectionState.HandoverInProgress
        DATA_UNKNOWN -> DataConnectionState.Unknown
        else -> DataConnectionState.Invalid
    }
