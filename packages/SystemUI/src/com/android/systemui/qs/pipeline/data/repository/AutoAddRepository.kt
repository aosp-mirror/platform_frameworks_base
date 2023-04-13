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

import android.database.ContentObserver
import android.provider.Settings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Repository to track what QS tiles have been auto-added */
interface AutoAddRepository {

    /** Flow of tiles that have been auto-added */
    fun autoAddedTiles(userId: Int): Flow<Set<TileSpec>>

    /** Mark a tile as having been auto-added */
    suspend fun markTileAdded(userId: Int, spec: TileSpec)

    /**
     * Unmark a tile as having been auto-added. This is used for tiles that can be auto-added
     * multiple times.
     */
    suspend fun unmarkTileAdded(userId: Int, spec: TileSpec)
}

/**
 * Implementation that tracks the auto-added tiles stored in [Settings.Secure.QS_AUTO_ADDED_TILES].
 */
@SysUISingleton
class AutoAddSettingRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    @Background private val bgDispatcher: CoroutineDispatcher,
) : AutoAddRepository {
    override fun autoAddedTiles(userId: Int): Flow<Set<TileSpec>> {
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
            .map {
                it.split(DELIMITER).map(TileSpec::create).filter { it !is TileSpec.Invalid }.toSet()
            }
            .flowOn(bgDispatcher)
    }

    override suspend fun markTileAdded(userId: Int, spec: TileSpec) {
        if (spec is TileSpec.Invalid) {
            return
        }
        val added = load(userId).toMutableSet()
        if (added.add(spec)) {
            store(userId, added)
        }
    }

    override suspend fun unmarkTileAdded(userId: Int, spec: TileSpec) {
        if (spec is TileSpec.Invalid) {
            return
        }
        val added = load(userId).toMutableSet()
        if (added.remove(spec)) {
            store(userId, added)
        }
    }

    private suspend fun store(userId: Int, tiles: Set<TileSpec>) {
        val toStore =
            tiles
                .filter { it !is TileSpec.Invalid }
                .joinToString(DELIMITER, transform = TileSpec::spec)
        withContext(bgDispatcher) {
            secureSettings.putStringForUser(
                SETTING,
                toStore,
                null,
                false,
                userId,
                true,
            )
        }
    }

    private suspend fun load(userId: Int): Set<TileSpec> {
        return withContext(bgDispatcher) {
            (secureSettings.getStringForUser(SETTING, userId) ?: "")
                .split(",")
                .map(TileSpec::create)
                .filter { it !is TileSpec.Invalid }
                .toSet()
        }
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_AUTO_ADDED_TILES
        private const val DELIMITER = ","
    }
}
