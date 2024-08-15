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

import android.annotation.UserIdInt
import android.content.res.Resources
import android.util.SparseArray
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.res.R
import com.android.systemui.retail.data.repository.RetailModeRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Repository that tracks the current tiles. */
interface TileSpecRepository {

    /**
     * Returns a flow of the current list of [TileSpec] for a given [userId].
     *
     * Tiles will never be [TileSpec.Invalid] in the list and it will never be empty.
     */
    suspend fun tilesSpecs(@UserIdInt userId: Int): Flow<List<TileSpec>>

    /**
     * Adds a [tile] for a given [userId] at [position]. Using [POSITION_AT_END] will add the tile
     * at the end of the list.
     *
     * Passing [TileSpec.Invalid] is a noop.
     *
     * Trying to add a tile beyond the end of the list will add it at the end.
     */
    suspend fun addTile(@UserIdInt userId: Int, tile: TileSpec, position: Int = POSITION_AT_END)

    /**
     * Removes a [tile] for a given [userId].
     *
     * Passing [TileSpec.Invalid] or a non present tile is a noop.
     */
    suspend fun removeTiles(@UserIdInt userId: Int, tiles: Collection<TileSpec>)

    /**
     * Sets the list of current [tiles] for a given [userId].
     *
     * [TileSpec.Invalid] will be ignored, and an effectively empty list will not be stored.
     */
    suspend fun setTiles(@UserIdInt userId: Int, tiles: List<TileSpec>)

    suspend fun reconcileRestore(restoreData: RestoreData, currentAutoAdded: Set<TileSpec>)

    /** Prepend the default list of tiles to the current set of tiles */
    suspend fun prependDefault(@UserIdInt userId: Int)

    companion object {
        /** Position to indicate the end of the list */
        const val POSITION_AT_END = -1
    }
}

/**
 * Implementation of [TileSpecRepository] that delegates to an instance of [UserTileSpecRepository]
 * for each user.
 *
 * If the device is in retail mode, the tiles are fixed to the value of
 * [R.string.quick_settings_tiles_retail_mode].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class TileSpecSettingsRepository
@Inject
constructor(
    @Main private val resources: Resources,
    private val logger: QSPipelineLogger,
    private val retailModeRepository: RetailModeRepository,
    private val userTileSpecRepositoryFactory: UserTileSpecRepository.Factory,
) : TileSpecRepository {

    private val retailModeTiles by lazy {
        resources
            .getString(R.string.quick_settings_tiles_retail_mode)
            .split(DELIMITER)
            .map(TileSpec::create)
            .filter { it !is TileSpec.Invalid }
    }

    private val userTileRepositories = SparseArray<UserTileSpecRepository>()

    override suspend fun tilesSpecs(userId: Int): Flow<List<TileSpec>> {
        if (userId !in userTileRepositories) {
            val userTileRepository = userTileSpecRepositoryFactory.create(userId)
            userTileRepositories.put(userId, userTileRepository)
        }
        val realTiles = userTileRepositories.get(userId).tiles()

        return retailModeRepository.retailMode.flatMapLatest { inRetailMode ->
            if (inRetailMode) {
                logger.logUsingRetailTiles()
                flowOf(retailModeTiles)
            } else {
                realTiles
            }
        }
    }

    override suspend fun addTile(userId: Int, tile: TileSpec, position: Int) {
        if (retailModeRepository.inRetailMode) {
            return
        }
        if (tile is TileSpec.Invalid) {
            return
        }
        userTileRepositories.get(userId)?.addTile(tile, position)
    }

    override suspend fun removeTiles(userId: Int, tiles: Collection<TileSpec>) {
        if (retailModeRepository.inRetailMode) {
            return
        }
        userTileRepositories.get(userId)?.removeTiles(tiles)
    }

    override suspend fun setTiles(userId: Int, tiles: List<TileSpec>) {
        if (retailModeRepository.inRetailMode) {
            return
        }
        userTileRepositories.get(userId)?.setTiles(tiles)
    }

    override suspend fun reconcileRestore(
        restoreData: RestoreData,
        currentAutoAdded: Set<TileSpec>
    ) {
        userTileRepositories
            .get(restoreData.userId)
            ?.reconcileRestore(restoreData, currentAutoAdded)
    }

    override suspend fun prependDefault(
        userId: Int,
    ) {
        if (retailModeRepository.inRetailMode) {
            return
        }
        userTileRepositories.get(userId)?.prependDefault()
    }

    companion object {
        private const val DELIMITER = TilesSettingConverter.DELIMITER
    }
}
