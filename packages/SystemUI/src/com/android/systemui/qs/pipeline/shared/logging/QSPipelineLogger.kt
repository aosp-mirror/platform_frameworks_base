/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.shared.logging

import android.annotation.UserIdInt
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.qs.pipeline.dagger.QSAutoAddLog
import com.android.systemui.qs.pipeline.dagger.QSTileListLog
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject

/**
 * Logger for the new pipeline.
 *
 * This may log to different buffers depending of the function of the log.
 */
class QSPipelineLogger
@Inject
constructor(
    @QSTileListLog private val tileListLogBuffer: LogBuffer,
    @QSAutoAddLog private val tileAutoAddLogBuffer: LogBuffer,
) {

    companion object {
        const val TILE_LIST_TAG = "QSTileListLog"
        const val AUTO_ADD_TAG = "QSAutoAddableLog"
    }

    /**
     * Log the tiles that are parsed in the repo. This is effectively what is surfaces in the flow.
     *
     * [usesDefault] indicates if the default tiles were used (due to the setting being empty or
     * invalid).
     */
    fun logParsedTiles(tiles: List<TileSpec>, usesDefault: Boolean, user: Int) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.DEBUG,
            {
                str1 = tiles.toString()
                bool1 = usesDefault
                int1 = user
            },
            { "Parsed tiles (default=$bool1, user=$int1): $str1" }
        )
    }

    /**
     * Logs when the tiles change in Settings.
     *
     * This could be caused by SystemUI, or restore.
     */
    fun logTilesChangedInSettings(newTiles: String, @UserIdInt user: Int) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.VERBOSE,
            {
                str1 = newTiles
                int1 = user
            },
            { "Tiles changed in settings for user $int1: $str1" }
        )
    }

    /** Log when a tile is destroyed and its reason for destroying. */
    fun logTileDestroyed(spec: TileSpec, reason: TileDestroyedReason) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.DEBUG,
            {
                str1 = spec.toString()
                str2 = reason.readable
            },
            { "Tile $str1 destroyed. Reason: $str2" }
        )
    }

    /** Log when a tile is created. */
    fun logTileCreated(spec: TileSpec) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.DEBUG,
            { str1 = spec.toString() },
            { "Tile $str1 created" }
        )
    }

    /** Ĺog when trying to create a tile, but it's not found in the factory. */
    fun logTileNotFoundInFactory(spec: TileSpec) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.VERBOSE,
            { str1 = spec.toString() },
            { "Tile $str1 not found in factory" }
        )
    }

    /** Log when the user is changed for a platform tile. */
    fun logTileUserChanged(spec: TileSpec, user: Int) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.VERBOSE,
            {
                str1 = spec.toString()
                int1 = user
            },
            { "User changed to $int1 for tile $str1" }
        )
    }

    fun logUsingRetailTiles() {
        tileListLogBuffer.log(TILE_LIST_TAG, LogLevel.DEBUG, {}, { "Using retail tiles" })
    }

    fun logTilesNotInstalled(tiles: Collection<TileSpec>, user: Int) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.DEBUG,
            {
                str1 = tiles.toString()
                int1 = user
            },
            { "Tiles kept for not installed packages for user $int1: $str1" }
        )
    }

    fun logTileAutoAdded(userId: Int, spec: TileSpec, position: Int) {
        tileAutoAddLogBuffer.log(
            AUTO_ADD_TAG,
            LogLevel.DEBUG,
            {
                int1 = userId
                int2 = position
                str1 = spec.toString()
            },
            { "Tile $str1 auto added for user $int1 at position $int2" }
        )
    }

    fun logTileAutoRemoved(userId: Int, spec: TileSpec) {
        tileAutoAddLogBuffer.log(
            AUTO_ADD_TAG,
            LogLevel.DEBUG,
            {
                int1 = userId
                str1 = spec.toString()
            },
            { "Tile $str1 auto removed for user $int1" }
        )
    }

    /** Reasons for destroying an existing tile. */
    enum class TileDestroyedReason(val readable: String) {
        TILE_REMOVED("Tile removed from  current set"),
        CUSTOM_TILE_USER_CHANGED("User changed for custom tile"),
        NEW_TILE_NOT_AVAILABLE("New tile not available"),
        EXISTING_TILE_NOT_AVAILABLE("Existing tile not available"),
        TILE_NOT_PRESENT_IN_NEW_USER("Tile not present in new user"),
    }
}
