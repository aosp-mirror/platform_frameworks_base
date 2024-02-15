/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.custom.domain.interactor

import android.os.UserHandle
import android.service.quicksettings.Tile
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileRepository
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manages updates of the [Tile] assigned for the current custom tile. */
@QSTileScope
class CustomTileInteractor
@Inject
constructor(
    private val tileSpec: TileSpec.CustomTileSpec,
    private val defaultsRepository: CustomTileDefaultsRepository,
    private val customTileRepository: CustomTileRepository,
    @QSTileScope private val tileScope: CoroutineScope,
    @Background private val backgroundContext: CoroutineContext,
) {

    private val userMutex = Mutex()
    private val tileUpdates =
        MutableSharedFlow<Tile>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var currentUser: UserHandle? = null
    private var updatesJob: Job? = null

    /** [Tile] updates. [updateTile] to emit a new one. */
    fun getTiles(user: UserHandle): Flow<Tile> = customTileRepository.getTiles(user)

    /**
     * Current [Tile]
     *
     * @throws IllegalStateException when the repository stores a tile for another user. This means
     *   the tile hasn't been updated for the current user. Can happen when this is accessed before
     *   [initForUser] returns.
     */
    fun getTile(user: UserHandle): Tile =
        customTileRepository.getTile(user)
            ?: throw IllegalStateException("Attempt to get a tile for a wrong user")

    /**
     * True if the tile is toggleable like a switch and false if it operates as a clickable button.
     */
    suspend fun isTileToggleable(): Boolean = customTileRepository.isTileToggleable()

    /**
     * Initializes the repository for the current user. Suspends until it's safe to call [getTile]
     * which needs at least one of the following:
     * - defaults are loaded;
     * - receive tile update in [updateTile];
     * - restoration happened for a persisted tile.
     */
    suspend fun initForUser(user: UserHandle) {
        userMutex.withLock {
            if (currentUser == user) {
                return
            }
            updatesJob?.cancel()
            defaultsRepository.requestNewDefaults(user, tileSpec.componentName)
            launchUpdates(user)
            customTileRepository.restoreForTheUserIfNeeded(
                user,
                customTileRepository.isTileActive()
            )
            // Suspend to make sure it gets the tile from one of the sources: restoration, defaults,
            // or
            // tile update.
            customTileRepository.getTiles(user).firstOrNull()
            currentUser = user
        }
    }

    private fun launchUpdates(user: UserHandle) {
        updatesJob =
            tileScope.launch {
                tileUpdates
                    .onEach {
                        customTileRepository.updateWithTile(
                            user,
                            it,
                            customTileRepository.isTileActive(),
                        )
                    }
                    .flowOn(backgroundContext)
                    .launchIn(this)
                defaultsRepository
                    .defaults(user)
                    .onEach {
                        customTileRepository.updateWithDefaults(
                            user,
                            it,
                            customTileRepository.isTileActive(),
                        )
                    }
                    .flowOn(backgroundContext)
                    .launchIn(this)
            }
    }

    /** Updates current [Tile]. Emits a new event in [getTiles]. */
    fun updateTile(newTile: Tile) {
        tileUpdates.tryEmit(newTile)
    }
}
