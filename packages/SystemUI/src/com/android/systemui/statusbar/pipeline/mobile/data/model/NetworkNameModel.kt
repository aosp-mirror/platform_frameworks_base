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

import android.content.Intent
import android.telephony.TelephonyManager.EXTRA_DATA_SPN
import android.telephony.TelephonyManager.EXTRA_PLMN
import android.telephony.TelephonyManager.EXTRA_SHOW_PLMN
import android.telephony.TelephonyManager.EXTRA_SHOW_SPN
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/**
 * Encapsulates the data needed to show a network name for a mobile network. The data is parsed from
 * the intent sent by [android.telephony.TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED].
 */
sealed interface NetworkNameModel : Diffable<NetworkNameModel> {
    val name: String

    /** The default name is read from [com.android.internal.R.string.lockscreen_carrier_default] */
    data class Default(override val name: String) : NetworkNameModel {
        override fun logDiffs(prevVal: NetworkNameModel, row: TableRowLogger) {
            if (prevVal !is Default || prevVal.name != name) {
                row.logChange(COL_NETWORK_NAME, "Default($name)")
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_NAME, "Default($name)")
        }
    }

    /**
     * This name has been derived from telephony intents. see
     * [android.telephony.TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED]
     */
    data class Derived(override val name: String) : NetworkNameModel {
        override fun logDiffs(prevVal: NetworkNameModel, row: TableRowLogger) {
            if (prevVal !is Derived || prevVal.name != name) {
                row.logChange(COL_NETWORK_NAME, "Derived($name)")
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_NETWORK_NAME, "Derived($name)")
        }
    }

    companion object {
        const val COL_NETWORK_NAME = "networkName"
    }
}

fun Intent.toNetworkNameModel(separator: String): NetworkNameModel? {
    val showSpn = getBooleanExtra(EXTRA_SHOW_SPN, false)
    val spn = getStringExtra(EXTRA_DATA_SPN)
    val showPlmn = getBooleanExtra(EXTRA_SHOW_PLMN, false)
    val plmn = getStringExtra(EXTRA_PLMN)

    val str = StringBuilder()
    val strData = StringBuilder()
    if (showPlmn && plmn != null) {
        str.append(plmn)
        strData.append(plmn)
    }
    if (showSpn && spn != null) {
        if (str.isNotEmpty()) {
            str.append(separator)
        }
        str.append(spn)
    }

    return if (str.isNotEmpty()) NetworkNameModel.Derived(str.toString()) else null
}
