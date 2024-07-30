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

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.TileRow
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject

@SysUISingleton
class InfiniteGridConsistencyInteractor
@Inject
constructor(
    private val iconTilesInteractor: IconTilesInteractor,
    private val gridSizeInteractor: FixedColumnsSizeInteractor
) : GridTypeConsistencyInteractor {

    /**
     * Tries to fill in every columns of all rows (except the last row), potentially reordering
     * tiles.
     */
    override fun reconcileTiles(tiles: List<TileSpec>): List<TileSpec> {
        val newTiles: MutableList<TileSpec> = mutableListOf()
        val row = TileRow<TileSpec>(columns = gridSizeInteractor.columns.value)
        val tilesQueue =
            ArrayDeque(
                tiles.map {
                    SizedTile(
                        it,
                        width =
                            if (iconTilesInteractor.isIconTile(it)) {
                                1
                            } else {
                                2
                            }
                    )
                }
            )

        while (tilesQueue.isNotEmpty()) {
            if (row.isFull()) {
                newTiles.addAll(row.tiles.map { it.tile })
                row.clear()
            }

            val tile = tilesQueue.removeFirst()

            // If the tile fits in the row, add it.
            if (!row.maybeAddTile(tile)) {
                // If the tile does not fit the row, find an icon tile to move.
                // We'll try to either add an icon tile from the queue to complete the row, or
                // remove an icon tile from the current row to free up space.

                val iconTile: SizedTile<TileSpec>? = tilesQueue.firstOrNull { it.width == 1 }
                if (iconTile != null) {
                    tilesQueue.remove(iconTile)
                    tilesQueue.addFirst(tile)
                    row.maybeAddTile(iconTile)
                } else {
                    val tileToRemove: SizedTile<TileSpec>? = row.findLastIconTile()
                    if (tileToRemove != null) {
                        row.removeTile(tileToRemove)
                        row.maybeAddTile(tile)

                        // Moving the icon tile to the end because there's no other
                        // icon tiles in the queue.
                        tilesQueue.addLast(tileToRemove)
                    } else {
                        // If the row does not have an icon tile, add the incomplete row.
                        // Note: this shouldn't happen because an icon tile is guaranteed to be in a
                        // row that doesn't have enough space for a large tile.
                        val tileSpecs = row.tiles.map { it.tile }
                        Log.wtf(TAG, "Uneven row does not have an icon tile to remove: $tileSpecs")
                        newTiles.addAll(tileSpecs)
                        row.clear()
                        tilesQueue.addFirst(tile)
                    }
                }
            }
        }

        // Add last row that might be incomplete
        newTiles.addAll(row.tiles.map { it.tile })

        return newTiles.toList()
    }

    private companion object {
        const val TAG = "InfiniteGridConsistencyInteractor"
    }
}
