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

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.qs.pipeline.dagger.QSAutoAddLog
import com.android.systemui.qs.pipeline.dagger.QSRestoreLog
import com.android.systemui.qs.pipeline.dagger.QSTileListLog
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.repository.UserTileSpecRepository
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
    @QSRestoreLog private val restoreLogBuffer: LogBuffer,
) {

    companion object {
        const val TILE_LIST_TAG = "QSTileListLog"
        const val AUTO_ADD_TAG = "QSAutoAddableLog"
        const val RESTORE_TAG = "QSRestoreLog"
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

    fun logTilesRestoredAndReconciled(
        currentTiles: List<TileSpec>,
        reconciledTiles: List<TileSpec>,
        user: Int,
    ) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.DEBUG,
            {
                str1 = currentTiles.toString()
                str2 = reconciledTiles.toString()
                int1 = user
            },
            { "Tiles restored and reconciled for user: $int1\nWas: $str1\nSet to: $str2" }
        )
    }

    fun logProcessTileChange(
        action: UserTileSpecRepository.ChangeAction,
        newList: List<TileSpec>,
        userId: Int,
    ) {
        tileListLogBuffer.log(
            TILE_LIST_TAG,
            LogLevel.DEBUG,
            {
                str1 = action.toString()
                str2 = newList.toString()
                int1 = userId
            },
            { "Processing $str1 for user $int1\nNew list: $str2" }
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

    fun logAutoAddTilesParsed(userId: Int, tiles: Set<TileSpec>) {
        tileAutoAddLogBuffer.log(
            AUTO_ADD_TAG,
            LogLevel.DEBUG,
            {
                str1 = tiles.toString()
                int1 = userId
            },
            { "Auto add tiles parsed for user $int1: $str1" }
        )
    }

    fun logAutoAddTilesRestoredReconciled(userId: Int, tiles: Set<TileSpec>) {
        tileAutoAddLogBuffer.log(
            AUTO_ADD_TAG,
            LogLevel.DEBUG,
            {
                str1 = tiles.toString()
                int1 = userId
            },
            { "Auto-add tiles reconciled for user $int1: $str1" }
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

    fun logTileUnmarked(userId: Int, spec: TileSpec) {
        tileAutoAddLogBuffer.log(
            AUTO_ADD_TAG,
            LogLevel.DEBUG,
            {
                int1 = userId
                str1 = spec.toString()
            },
            { "Tile $str1 unmarked as auto-added for user $int1" }
        )
    }

    fun logSettingsRestored(restoreData: RestoreData) {
        restoreLogBuffer.log(
            RESTORE_TAG,
            LogLevel.DEBUG,
            {
                int1 = restoreData.userId
                str1 = restoreData.restoredTiles.toString()
                str2 = restoreData.restoredAutoAddedTiles.toString()
            },
            {
                "Restored settings data for user $int1\n" +
                    "\tRestored tiles: $str1\n" +
                    "\tRestored auto added tiles: $str2"
            }
        )
    }

    fun logRestoreProcessorApplied(
        restoreProcessorClassName: String?,
        step: RestorePreprocessorStep,
    ) {
        restoreLogBuffer.log(
            RESTORE_TAG,
            LogLevel.DEBUG,
            {
                str1 = restoreProcessorClassName
                str2 = step.name
            },
            { "Restore $str2 processed by $str1" }
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

    enum class RestorePreprocessorStep {
        PREPROCESSING,
        POSTPROCESSING
    }
}
