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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.external.CustomTileStatePersister
import com.android.systemui.qs.external.PackageManagerAdapter
import com.android.systemui.qs.external.TileServiceKey
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.commons.copy
import com.android.systemui.qs.tiles.impl.custom.commons.setFrom
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository store the [Tile] associated with the custom tile. It lives on [QSTileScope] which
 * allows it to survive service rebinding. Given that, it provides the last received state when
 * connected again.
 */
interface CustomTileRepository {

    /**
     * Restores the [Tile] if it's [isPersistable]. Restored [Tile] will be available via [getTile]
     * (but there is no guarantee that restoration is synchronous) and emitted in [getTiles] for a
     * corresponding [user].
     */
    suspend fun restoreForTheUserIfNeeded(user: UserHandle, isPersistable: Boolean)

    /** Returns [Tile] updates for a [user]. */
    fun getTiles(user: UserHandle): Flow<Tile>

    /**
     * Return current [Tile] for a [user] or null if the [user] doesn't match currently cached one.
     * Suspending until [getTiles] returns something is a way to wait for this to become available.
     *
     * @throws IllegalStateException when there is no current tile.
     */
    fun getTile(user: UserHandle): Tile?

    /** @see [com.android.systemui.qs.external.TileLifecycleManager.isActiveTile] */
    suspend fun isTileActive(): Boolean

    /** @see [com.android.systemui.qs.external.TileLifecycleManager.isToggleableTile] */
    suspend fun isTileToggleable(): Boolean

    /**
     * Updates tile with the non-null values from [newTile]. Overwrites the current cache when
     * [user] differs from the cached one. [isPersistable] tile will be persisted to be possibly
     * loaded when the [restoreForTheUserIfNeeded].
     */
    suspend fun updateWithTile(
        user: UserHandle,
        newTile: Tile,
        isPersistable: Boolean,
    )

    /**
     * Updates tile with the values from [defaults]. Overwrites the current cache when [user]
     * differs from the cached one. [isPersistable] tile will be persisted to be possibly loaded
     * when the [restoreForTheUserIfNeeded].
     */
    suspend fun updateWithDefaults(
        user: UserHandle,
        defaults: CustomTileDefaults,
        isPersistable: Boolean,
    )
}

@QSTileScope
class CustomTileRepositoryImpl
@Inject
constructor(
    private val tileSpec: TileSpec.CustomTileSpec,
    private val customTileStatePersister: CustomTileStatePersister,
    private val packageManagerAdapter: PackageManagerAdapter,
    @Background private val backgroundContext: CoroutineContext,
) : CustomTileRepository {

    private val tileUpdateMutex = Mutex()
    private val tileWithUserState =
        MutableSharedFlow<TileWithUser>(onBufferOverflow = BufferOverflow.DROP_OLDEST, replay = 1)

    override suspend fun restoreForTheUserIfNeeded(user: UserHandle, isPersistable: Boolean) {
        if (isPersistable && getCurrentTileWithUser()?.user != user) {
            withContext(backgroundContext) {
                customTileStatePersister.readState(user.getKey())?.let {
                    updateWithTile(
                        user,
                        it,
                        true,
                    )
                }
            }
        }
    }

    override fun getTiles(user: UserHandle): Flow<Tile> =
        tileWithUserState.filter { it.user == user }.map { it.tile }

    override fun getTile(user: UserHandle): Tile? {
        val tileWithUser =
            getCurrentTileWithUser() ?: throw IllegalStateException("Tile is not set")
        return if (tileWithUser.user == user) {
            tileWithUser.tile
        } else {
            null
        }
    }

    override suspend fun updateWithTile(
        user: UserHandle,
        newTile: Tile,
        isPersistable: Boolean,
    ) = updateTile(user, isPersistable) { setFrom(newTile) }

    override suspend fun updateWithDefaults(
        user: UserHandle,
        defaults: CustomTileDefaults,
        isPersistable: Boolean,
    ) {
        if (defaults is CustomTileDefaults.Result) {
            updateTile(user, isPersistable) {
                // Update the icon if it's not set or is the default icon.
                val updateIcon = (icon == null || icon.isResourceEqual(defaults.icon))
                if (updateIcon) {
                    icon = defaults.icon
                }
                setDefaultLabel(defaults.label)
            }
        }
    }

    override suspend fun isTileActive(): Boolean =
        withContext(backgroundContext) {
            try {
                val info: ServiceInfo =
                    packageManagerAdapter.getServiceInfo(
                        tileSpec.componentName,
                        META_DATA_QUERY_FLAGS
                    )
                info.metaData?.getBoolean(TileService.META_DATA_ACTIVE_TILE, false) == true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    override suspend fun isTileToggleable(): Boolean =
        withContext(backgroundContext) {
            try {
                val info: ServiceInfo =
                    packageManagerAdapter.getServiceInfo(
                        tileSpec.componentName,
                        META_DATA_QUERY_FLAGS
                    )
                info.metaData?.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE, false) == true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    private suspend fun updateTile(
        user: UserHandle,
        isPersistable: Boolean,
        update: Tile.() -> Unit
    ): Unit =
        tileUpdateMutex.withLock {
            val currentTileWithUser = getCurrentTileWithUser()
            val tileToUpdate =
                if (currentTileWithUser?.user == user) {
                    currentTileWithUser.tile.copy()
                } else {
                    Tile()
                }
            tileToUpdate.update()
            if (isPersistable) {
                withContext(backgroundContext) {
                    customTileStatePersister.persistState(user.getKey(), tileToUpdate)
                }
            }
            tileWithUserState.tryEmit(TileWithUser(user, tileToUpdate))
        }

    private fun getCurrentTileWithUser(): TileWithUser? = tileWithUserState.replayCache.lastOrNull()

    /** Compare two icons, only works for resources. */
    private fun Icon.isResourceEqual(icon2: Icon?): Boolean {
        if (icon2 == null) {
            return false
        }
        if (this === icon2) {
            return true
        }
        if (type != Icon.TYPE_RESOURCE || icon2.type != Icon.TYPE_RESOURCE) {
            return false
        }
        if (resId != icon2.resId) {
            return false
        }
        return resPackage == icon2.resPackage
    }

    private fun UserHandle.getKey() = TileServiceKey(tileSpec.componentName, this.identifier)

    private data class TileWithUser(val user: UserHandle, val tile: Tile)

    private companion object {
        const val META_DATA_QUERY_FLAGS =
            (PackageManager.GET_META_DATA or
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
                PackageManager.MATCH_DIRECT_BOOT_AWARE)
    }
}
