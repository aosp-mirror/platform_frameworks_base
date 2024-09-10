/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.qs.panels.shared.model.GridConsistencyLog
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SysUISingleton
class GridConsistencyInteractor
@Inject
constructor(
    private val gridLayoutTypeInteractor: GridLayoutTypeInteractor,
    private val currentTilesInteractor: CurrentTilesInteractor,
    private val consistencyInteractors:
        Map<GridLayoutType, @JvmSuppressWildcards GridTypeConsistencyInteractor>,
    private val defaultConsistencyInteractor: GridTypeConsistencyInteractor,
    @GridConsistencyLog private val logBuffer: LogBuffer,
    @Application private val applicationScope: CoroutineScope,
) {
    fun start() {
        applicationScope.launch {
            gridLayoutTypeInteractor.layout.collectLatest { type ->
                val consistencyInteractor =
                    consistencyInteractors[type] ?: defaultConsistencyInteractor
                currentTilesInteractor.currentTiles
                    .map { tiles -> tiles.map { it.spec } }
                    .collectLatest { tiles ->
                        val newTiles = consistencyInteractor.reconcileTiles(tiles)
                        if (newTiles != tiles) {
                            currentTilesInteractor.setTiles(newTiles)
                            logChange(newTiles)
                        }
                    }
            }
        }
    }

    private fun logChange(tiles: List<TileSpec>) {
        logBuffer.log(
            LOG_BUFFER_CURRENT_TILES_CHANGE_TAG,
            LogLevel.DEBUG,
            { str1 = tiles.toString() },
            { "Tiles reordered: $str1" }
        )
    }

    private companion object {
        const val LOG_BUFFER_CURRENT_TILES_CHANGE_TAG = "GridConsistencyTilesChange"
    }
}
