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

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.content.res.Configuration.Orientation
import android.content.res.Configuration.SCREENLAYOUT_LONG_NO
import android.content.res.Configuration.SCREENLAYOUT_LONG_YES
import android.service.quicksettings.Tile
import android.view.View
import com.android.systemui.log.ConstantStringsLogger
import com.android.systemui.log.ConstantStringsLoggerImpl
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.core.LogLevel.VERBOSE
import com.android.systemui.log.dagger.QSConfigLog
import com.android.systemui.log.dagger.QSLog
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.statusbar.StatusBarState
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "QSLog"

class QSLogger
@Inject
constructor(
    @QSLog private val buffer: LogBuffer,
    @QSConfigLog private val configChangedBuffer: LogBuffer,
) : ConstantStringsLogger by ConstantStringsLoggerImpl(buffer, TAG) {

    fun logException(@CompileTimeConstant logMsg: String, ex: Exception) {
        buffer.log(TAG, ERROR, {}, { logMsg }, exception = ex)
    }

    fun v(@CompileTimeConstant msg: String, arg: Any) {
        buffer.log(TAG, VERBOSE, { str1 = arg.toString() }, { "$msg: $str1" })
    }

    fun d(@CompileTimeConstant msg: String, arg: Any) {
        buffer.log(TAG, DEBUG, { str1 = arg.toString() }, { "$msg: $str1" })
    }

    fun logTileAdded(tileSpec: String) {
        buffer.log(TAG, DEBUG, { str1 = tileSpec }, { "[$str1] Tile added" })
    }

    fun logTileDestroyed(tileSpec: String, reason: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                str2 = reason
            },
            { "[$str1] Tile destroyed. Reason: $str2" }
        )
    }

    fun logTileChangeListening(tileSpec: String, listening: Boolean) {
        buffer.log(
            TAG,
            VERBOSE,
            {
                bool1 = listening
                str1 = tileSpec
            },
            { "[$str1] Tile listening=$bool1" }
        )
    }

    fun logAllTilesChangeListening(listening: Boolean, containerName: String, allSpecs: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                bool1 = listening
                str1 = containerName
                str2 = allSpecs
            },
            { "Tiles listening=$bool1 in $str1. $str2" }
        )
    }

    fun logTileClick(tileSpec: String, statusBarState: Int, state: Int, eventId: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                int1 = eventId
                str2 = StatusBarState.toString(statusBarState)
                str3 = toStateString(state)
            },
            { "[$str1][$int1] Tile clicked. StatusBarState=$str2. TileState=$str3" }
        )
    }

    fun logHandleClick(tileSpec: String, eventId: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                int1 = eventId
            },
            { "[$str1][$int1] Tile handling click." }
        )
    }

    fun logTileSecondaryClick(tileSpec: String, statusBarState: Int, state: Int, eventId: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                int1 = eventId
                str2 = StatusBarState.toString(statusBarState)
                str3 = toStateString(state)
            },
            { "[$str1][$int1] Tile secondary clicked. StatusBarState=$str2. TileState=$str3" }
        )
    }

    fun logHandleSecondaryClick(tileSpec: String, eventId: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                int1 = eventId
            },
            { "[$str1][$int1] Tile handling secondary click." }
        )
    }

    fun logTileLongClick(tileSpec: String, statusBarState: Int, state: Int, eventId: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                int1 = eventId
                str2 = StatusBarState.toString(statusBarState)
                str3 = toStateString(state)
            },
            { "[$str1][$int1] Tile long clicked. StatusBarState=$str2. TileState=$str3" }
        )
    }

    fun logHandleLongClick(tileSpec: String, eventId: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileSpec
                int1 = eventId
            },
            { "[$str1][$int1] Tile handling long click." }
        )
    }

    fun logInternetTileUpdate(tileSpec: String, lastType: Int, callback: String) {
        buffer.log(
            TAG,
            VERBOSE,
            {
                str1 = tileSpec
                int1 = lastType
                str2 = callback
            },
            { "[$str1] mLastTileState=$int1, Callback=$str2." }
        )
    }

    // TODO(b/250618218): Remove this method once we know the root cause of b/250618218.
    fun logTileBackgroundColorUpdateIfInternetTile(
        tileSpec: String,
        state: Int,
        disabledByPolicy: Boolean,
        color: Int
    ) {
        // This method is added to further debug b/250618218 which has only been observed from the
        // InternetTile, so we are only logging the background color change for the InternetTile
        // to avoid spamming the QSLogger.
        if (tileSpec != "internet") {
            return
        }
        buffer.log(
            TAG,
            VERBOSE,
            {
                str1 = tileSpec
                int1 = state
                bool1 = disabledByPolicy
                int2 = color
            },
            { "[$str1] state=$int1, disabledByPolicy=$bool1, color=$int2." }
        )
    }

    fun logTileUpdated(tileSpec: String, state: QSTile.State) {
        buffer.log(
            TAG,
            VERBOSE,
            {
                str1 = tileSpec
                str2 = state.label?.toString()
                str3 = state.icon?.toString()
                int1 = state.state
            },
            { "[$str1] Tile updated. Label=$str2. State=$int1. Icon=$str3." }
        )
    }

    fun logPanelExpanded(expanded: Boolean, containerName: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = containerName
                bool1 = expanded
            },
            { "$str1 expanded=$bool1" }
        )
    }

    fun logOnViewAttached(orientation: Int, containerName: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = containerName
                int1 = orientation
            },
            { "onViewAttached: $str1 orientation $int1" }
        )
    }

    fun logOnViewDetached(orientation: Int, containerName: String) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = containerName
                int1 = orientation
            },
            { "onViewDetached: $str1 orientation $int1" }
        )
    }

    fun logOnConfigurationChanged(
        @Orientation oldOrientation: Int,
        @Orientation newOrientation: Int,
        oldShouldUseSplitShade: Boolean,
        newShouldUseSplitShade: Boolean,
        oldScreenLayout: Int,
        newScreenLayout: Int,
        containerName: String
    ) {
        configChangedBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = containerName
                int1 = oldOrientation
                int2 = newOrientation
                long1 = oldScreenLayout.toLong()
                long2 = newScreenLayout.toLong()
                bool1 = oldShouldUseSplitShade
                bool2 = newShouldUseSplitShade
            },
            {
                "config change: " +
                    "$str1 orientation=${toOrientationString(int2)} " +
                    "(was ${toOrientationString(int1)}), " +
                    "screen layout=${toScreenLayoutString(long1.toInt())} " +
                    "(was ${toScreenLayoutString(long2.toInt())}), " +
                    "splitShade=$bool2 (was $bool1)"
            }
        )
    }

    fun logSwitchTileLayout(
        after: Boolean,
        before: Boolean,
        force: Boolean,
        containerName: String
    ) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = containerName
                bool1 = after
                bool2 = before
                bool3 = force
            },
            { "change tile layout: $str1 horizontal=$bool1 (was $bool2), force? $bool3" }
        )
    }

    fun logTileDistributionInProgress(tilesPerPageCount: Int, totalTilesCount: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                int1 = tilesPerPageCount
                int2 = totalTilesCount
            },
            { "Distributing tiles: [tilesPerPageCount=$int1] [totalTilesCount=$int2]" }
        )
    }

    fun logTileDistributed(tileName: String, pageIndex: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = tileName
                int1 = pageIndex
            },
            { "Adding $str1 to page number $int1" }
        )
    }

    private fun toStateString(state: Int): String {
        return when (state) {
            Tile.STATE_ACTIVE -> "active"
            Tile.STATE_INACTIVE -> "inactive"
            Tile.STATE_UNAVAILABLE -> "unavailable"
            else -> "wrong state"
        }
    }

    fun logVisibility(viewName: String, @View.Visibility visibility: Int) {
        buffer.log(
            TAG,
            DEBUG,
            {
                str1 = viewName
                str2 = toVisibilityString(visibility)
            },
            { "$str1 visibility: $str2" }
        )
    }

    private fun toVisibilityString(visibility: Int): String {
        return when (visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "undefined"
        }
    }
}

private inline fun toOrientationString(@Orientation orientation: Int): String {
    return when (orientation) {
        ORIENTATION_LANDSCAPE -> "land"
        ORIENTATION_PORTRAIT -> "port"
        else -> "undefined"
    }
}

private inline fun toScreenLayoutString(screenLayout: Int): String {
    return when (screenLayout) {
        SCREENLAYOUT_LONG_YES -> "long"
        SCREENLAYOUT_LONG_NO -> "notlong"
        else -> "undefined"
    }
}
