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
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
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
) {

    companion object {
        const val TILE_LIST_TAG = "QSTileListLog"
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
}
