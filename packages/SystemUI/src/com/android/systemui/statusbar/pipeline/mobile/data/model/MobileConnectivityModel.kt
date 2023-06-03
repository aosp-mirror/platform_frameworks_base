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

import android.net.NetworkCapabilities
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** Provides information about a mobile network connection */
data class MobileConnectivityModel(
    /** Whether mobile is the connected transport see [NetworkCapabilities.TRANSPORT_CELLULAR] */
    val isConnected: Boolean = false,
    /** Whether the mobile transport is validated [NetworkCapabilities.NET_CAPABILITY_VALIDATED] */
    val isValidated: Boolean = false,
) : Diffable<MobileConnectivityModel> {
    // TODO(b/267767715): Can we implement [logDiffs] and [logFull] generically for data classes?
    override fun logDiffs(prevVal: MobileConnectivityModel, row: TableRowLogger) {
        if (prevVal.isConnected != isConnected) {
            row.logChange(COL_IS_CONNECTED, isConnected)
        }
        if (prevVal.isValidated != isValidated) {
            row.logChange(COL_IS_VALIDATED, isValidated)
        }
    }

    override fun logFull(row: TableRowLogger) {
        row.logChange(COL_IS_CONNECTED, isConnected)
        row.logChange(COL_IS_VALIDATED, isValidated)
    }

    companion object {
        private const val COL_IS_CONNECTED = "isConnected"
        private const val COL_IS_VALIDATED = "isValidated"
    }
}
