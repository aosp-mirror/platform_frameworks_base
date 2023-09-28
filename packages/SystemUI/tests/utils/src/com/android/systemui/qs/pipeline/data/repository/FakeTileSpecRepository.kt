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

package com.android.systemui.qs.pipeline.data.repository

import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.shared.TileSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTileSpecRepository : TileSpecRepository {

    private val tilesPerUser = mutableMapOf<Int, MutableStateFlow<List<TileSpec>>>()

    override suspend fun tilesSpecs(userId: Int): Flow<List<TileSpec>> {
        return getFlow(userId).asStateFlow()
    }

    override suspend fun addTile(userId: Int, tile: TileSpec, position: Int) {
        if (tile == TileSpec.Invalid) return
        with(getFlow(userId)) {
            value =
                value.toMutableList().apply {
                    if (position == POSITION_AT_END) {
                        add(tile)
                    } else {
                        add(position, tile)
                    }
                }
        }
    }

    override suspend fun removeTiles(userId: Int, tiles: Collection<TileSpec>) {
        with(getFlow(userId)) {
            value =
                value.toMutableList().apply { removeAll(tiles.filter { it != TileSpec.Invalid }) }
        }
    }

    override suspend fun setTiles(userId: Int, tiles: List<TileSpec>) {
        getFlow(userId).value = tiles.filter { it != TileSpec.Invalid }
    }

    private fun getFlow(userId: Int): MutableStateFlow<List<TileSpec>> =
        tilesPerUser.getOrPut(userId) { MutableStateFlow(emptyList()) }

    override suspend fun reconcileRestore(
        restoreData: RestoreData,
        currentAutoAdded: Set<TileSpec>
    ) {
        with(getFlow(restoreData.userId)) {
            value = UserTileSpecRepository.reconcileTiles(value, currentAutoAdded, restoreData)
        }
    }
}
