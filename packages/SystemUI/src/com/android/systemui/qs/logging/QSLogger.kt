/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs.logging

import android.service.quicksettings.Tile
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.VERBOSE
import com.android.systemui.log.LogMessage
import com.android.systemui.log.dagger.QSLog
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

private const val TAG = "QSLog"

class QSLogger @Inject constructor(
    @QSLog private val buffer: LogBuffer
) {

    fun logTileAdded(tileSpec: String) {
        log(DEBUG, {
            str1 = tileSpec
        }, {
            "[$str1] Tile added"
        })
    }

    fun logTileDestroyed(tileSpec: String, reason: String) {
        log(DEBUG, {
            str1 = tileSpec
            str2 = reason
        }, {
            "[$str1] Tile destroyed. Reason: $str2"
        })
    }

    fun logTileChangeListening(tileSpec: String, listening: Boolean) {
        log(VERBOSE, {
            bool1 = listening
            str1 = tileSpec
        }, {
            "[$str1] Tile listening=$bool1"
        })
    }

    fun logAllTilesChangeListening(listening: Boolean, containerName: String, allSpecs: String) {
        log(DEBUG, {
            bool1 = listening
            str1 = containerName
            str2 = allSpecs
        }, {
            "Tiles listening=$bool1 in $str1. $str2"
        })
    }

    fun logTileClick(tileSpec: String, statusBarState: Int, state: Int) {
        log(DEBUG, {
            str1 = tileSpec
            int1 = statusBarState
            str2 = StatusBarState.toString(statusBarState)
            str3 = toStateString(state)
        }, {
            "[$str1] Tile clicked. StatusBarState=$str2. TileState=$str3"
        })
    }

    fun logTileSecondaryClick(tileSpec: String, statusBarState: Int, state: Int) {
        log(DEBUG, {
            str1 = tileSpec
            int1 = statusBarState
            str2 = StatusBarState.toString(statusBarState)
            str3 = toStateString(state)
        }, {
            "[$str1] Tile long clicked. StatusBarState=$str2. TileState=$str3"
        })
    }

    fun logTileLongClick(tileSpec: String, statusBarState: Int, state: Int) {
        log(DEBUG, {
            str1 = tileSpec
            int1 = statusBarState
            str2 = StatusBarState.toString(statusBarState)
            str3 = toStateString(state)
        }, {
            "[$str1] Tile long clicked. StatusBarState=$str2. TileState=$str3"
        })
    }

    fun logTileUpdated(tileSpec: String, state: QSTile.State) {
        log(VERBOSE, {
            str1 = tileSpec
            str2 = state.label?.toString()
            str3 = state.icon?.toString()
            int1 = state.state
            if (state is QSTile.SignalState) {
                bool1 = true
                bool2 = state.activityIn
                bool3 = state.activityOut
            }
        }, {
            "[$str1] Tile updated. Label=$str2. State=$int1. Icon=$str3." +
                if (bool1) " Activity in/out=$bool2/$bool3" else ""
        })
    }

    fun logPanelExpanded(expanded: Boolean, containerName: String) {
        log(DEBUG, {
            str1 = containerName
            bool1 = expanded
        }, {
            "$str1 expanded=$bool1"
        })
    }

    private fun toStateString(state: Int): String {
        return when (state) {
            Tile.STATE_ACTIVE -> "active"
            Tile.STATE_INACTIVE -> "inactive"
            Tile.STATE_UNAVAILABLE -> "unavailable"
            else -> "wrong state"
        }
    }

    private inline fun log(
        logLevel: LogLevel,
        initializer: LogMessage.() -> Unit,
        noinline printer: LogMessage.() -> String
    ) {
        buffer.log(TAG, logLevel, initializer, printer)
    }
}