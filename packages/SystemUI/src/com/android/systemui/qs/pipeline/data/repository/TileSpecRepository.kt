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
import android.database.ContentObserver
import android.provider.Settings
import com.android.systemui.R
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.retail.data.repository.RetailModeRepository
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Repository that tracks the current tiles. */
interface TileSpecRepository {

    /**
     * Returns a flow of the current list of [TileSpec] for a given [userId].
     *
     * Tiles will never be [TileSpec.Invalid] in the list and it will never be empty.
     */
    fun tilesSpecs(@UserIdInt userId: Int): Flow<List<TileSpec>>

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

    companion object {
        /** Position to indicate the end of the list */
        const val POSITION_AT_END = -1
    }
}

/**
 * Implementation of [TileSpecRepository] that persist the values of tiles in
 * [Settings.Secure.QS_TILES].
 *
 * All operations against [Settings] will be performed in a background thread.
 *
 * If the device is in retail mode, the tiles are fixed to the value of
 * [R.string.quick_settings_tiles_retail_mode].
 */
@SysUISingleton
class TileSpecSettingsRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    @Main private val resources: Resources,
    private val logger: QSPipelineLogger,
    private val retailModeRepository: RetailModeRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : TileSpecRepository {

    private val mutex = Mutex()

    private val retailModeTiles by lazy {
        resources
            .getString(R.string.quick_settings_tiles_retail_mode)
            .split(DELIMITER)
            .map(TileSpec::create)
            .filter { it !is TileSpec.Invalid }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tilesSpecs(userId: Int): Flow<List<TileSpec>> {
        return retailModeRepository.retailMode.flatMapLatest { inRetailMode ->
            if (inRetailMode) {
                logger.logUsingRetailTiles()
                flowOf(retailModeTiles)
            } else {
                settingsTiles(userId)
            }
        }
    }

    private fun settingsTiles(userId: Int): Flow<List<TileSpec>> {
        return conflatedCallbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }

                secureSettings.registerContentObserverForUser(SETTING, observer, userId)

                awaitClose { secureSettings.unregisterContentObserver(observer) }
            }
            .onStart { emit(Unit) }
            .map { secureSettings.getStringForUser(SETTING, userId) ?: "" }
            .distinctUntilChanged()
            .onEach { logger.logTilesChangedInSettings(it, userId) }
            .map { parseTileSpecs(it, userId) }
            .flowOn(backgroundDispatcher)
    }

    override suspend fun addTile(userId: Int, tile: TileSpec, position: Int) =
        mutex.withLock {
            if (tile == TileSpec.Invalid) {
                return
            }
            val tilesList = loadTiles(userId).toMutableList()
            if (tile !in tilesList) {
                if (position < 0 || position >= tilesList.size) {
                    tilesList.add(tile)
                } else {
                    tilesList.add(position, tile)
                }
                storeTiles(userId, tilesList)
            }
        }

    override suspend fun removeTiles(userId: Int, tiles: Collection<TileSpec>) =
        mutex.withLock {
            if (tiles.all { it == TileSpec.Invalid }) {
                return
            }
            val tilesList = loadTiles(userId).toMutableList()
            if (tilesList.removeAll(tiles)) {
                storeTiles(userId, tilesList.toList())
            }
        }

    override suspend fun setTiles(userId: Int, tiles: List<TileSpec>) =
        mutex.withLock {
            val filtered = tiles.filter { it != TileSpec.Invalid }
            if (filtered.isNotEmpty()) {
                storeTiles(userId, filtered)
            }
        }

    private suspend fun loadTiles(@UserIdInt forUser: Int): List<TileSpec> {
        return withContext(backgroundDispatcher) {
            (secureSettings.getStringForUser(SETTING, forUser) ?: "")
                .split(DELIMITER)
                .map(TileSpec::create)
                .filter { it !is TileSpec.Invalid }
        }
    }

    private suspend fun storeTiles(@UserIdInt forUser: Int, tiles: List<TileSpec>) {
        if (retailModeRepository.inRetailMode) {
            // No storing tiles when in retail mode
            return
        }
        val toStore =
            tiles
                .filter { it !is TileSpec.Invalid }
                .joinToString(DELIMITER, transform = TileSpec::spec)
        withContext(backgroundDispatcher) {
            secureSettings.putStringForUser(
                SETTING,
                toStore,
                null,
                false,
                forUser,
                true,
            )
        }
    }

    private fun parseTileSpecs(tilesFromSettings: String, user: Int): List<TileSpec> {
        val fromSettings =
            tilesFromSettings.split(DELIMITER).map(TileSpec::create).filter {
                it != TileSpec.Invalid
            }
        return if (fromSettings.isNotEmpty()) {
            fromSettings.also { logger.logParsedTiles(it, false, user) }
        } else {
            QSHost.getDefaultSpecs(resources)
                .map(TileSpec::create)
                .filter { it != TileSpec.Invalid }
                .also { logger.logParsedTiles(it, true, user) }
        }
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_TILES
        private const val DELIMITER = ","
    }
}
