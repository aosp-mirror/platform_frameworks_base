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

package com.android.systemui.qs.pipeline.domain.interactor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import com.android.systemui.Dumpable
import com.android.systemui.ProtoDumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.nano.SystemUIProtoDump
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.CustomTile
import com.android.systemui.qs.external.CustomTileStatePersister
import com.android.systemui.qs.external.TileLifecycleManager
import com.android.systemui.qs.external.TileServiceKey
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository
import com.android.systemui.qs.pipeline.data.repository.InstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository
import com.android.systemui.qs.pipeline.domain.model.TileModel
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.qs.tiles.di.NewQSTileFactory
import com.android.systemui.qs.toProto
import com.android.systemui.settings.UserTracker
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.pairwise
import dagger.Lazy
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactor for retrieving the list of current QS tiles, as well as making changes to this list
 *
 * It is [ProtoDumpable] as it needs to be able to dump state for CTS tests.
 */
interface CurrentTilesInteractor : ProtoDumpable {
    /** Current list of tiles with their corresponding spec. */
    val currentTiles: StateFlow<List<TileModel>>

    /** User for the [currentTiles]. */
    val userId: StateFlow<Int>

    /** [Context] corresponding to [userId] */
    val userContext: StateFlow<Context>

    /** List of specs corresponding to the last value of [currentTiles] */
    val currentTilesSpecs: List<TileSpec>
        get() = currentTiles.value.map(TileModel::spec)

    /** List of tiles corresponding to the last value of [currentTiles] */
    val currentQSTiles: List<QSTile>
        get() = currentTiles.value.map(TileModel::tile)

    /**
     * Requests that a tile be added in the list of tiles for the current user.
     *
     * @see TileSpecRepository.addTile
     */
    fun addTile(spec: TileSpec, position: Int = TileSpecRepository.POSITION_AT_END)

    /**
     * Requests that tiles be removed from the list of tiles for the current user
     *
     * If tiles with [TileSpec.CustomTileSpec] are removed, their lifecycle will be terminated and
     * marked as removed.
     *
     * @see TileSpecRepository.removeTiles
     */
    fun removeTiles(specs: Collection<TileSpec>)

    /**
     * Requests that the list of tiles for the current user is changed to [specs].
     *
     * If tiles with [TileSpec.CustomTileSpec] are removed, their lifecycle will be terminated and
     * marked as removed.
     *
     * @see TileSpecRepository.setTiles
     */
    fun setTiles(specs: List<TileSpec>)
}

/**
 * This implementation of [CurrentTilesInteractor] will try to re-use existing [QSTile] objects when
 * possible, in particular:
 * * It will only destroy tiles when they are not part of the list of tiles anymore
 * * Platform tiles will be kept between users, with a call to [QSTile.userSwitch]
 * * [CustomTile]s will only be destroyed if the user changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CurrentTilesInteractorImpl
@Inject
constructor(
    private val tileSpecRepository: TileSpecRepository,
    private val installedTilesComponentRepository: InstalledTilesComponentRepository,
    private val userRepository: UserRepository,
    private val customTileStatePersister: CustomTileStatePersister,
    private val newQSTileFactory: Lazy<NewQSTileFactory>,
    private val tileFactory: QSFactory,
    private val customTileAddedRepository: CustomTileAddedRepository,
    private val tileLifecycleManagerFactory: TileLifecycleManager.Factory,
    private val userTracker: UserTracker,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val logger: QSPipelineLogger,
    private val featureFlags: QSPipelineFlagsRepository,
) : CurrentTilesInteractor {

    private val _currentSpecsAndTiles: MutableStateFlow<List<TileModel>> =
        MutableStateFlow(emptyList())

    override val currentTiles: StateFlow<List<TileModel>> = _currentSpecsAndTiles.asStateFlow()

    // This variable should only be accessed inside the collect of `startTileCollection`.
    private val specsToTiles = mutableMapOf<TileSpec, TileOrNotInstalled>()

    private val currentUser = MutableStateFlow(userTracker.userId)
    override val userId = currentUser.asStateFlow()

    private val _userContext = MutableStateFlow(userTracker.userContext)
    override val userContext = _userContext.asStateFlow()

    private val userAndTiles =
        currentUser
            .flatMapLatest { userId ->
                tileSpecRepository.tilesSpecs(userId).map { UserAndTiles(userId, it) }
            }
            .distinctUntilChanged()
            .pairwise(UserAndTiles(-1, emptyList()))
            .flowOn(backgroundDispatcher)

    private val installedPackagesWithTiles =
        currentUser.flatMapLatest {
            installedTilesComponentRepository.getInstalledTilesComponents(it)
        }

    init {
        if (featureFlags.pipelineEnabled) {
            startTileCollection()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startTileCollection() {
        scope.launch {
            launch {
                userRepository.selectedUserInfo.collect { user ->
                    currentUser.value = user.id
                    _userContext.value = userTracker.userContext
                }
            }

            launch(backgroundDispatcher) {
                userAndTiles
                    .combine(installedPackagesWithTiles) { usersAndTiles, packages ->
                        Data(
                            usersAndTiles.previousValue,
                            usersAndTiles.newValue,
                            packages,
                        )
                    }
                    .collectLatest {
                        val newTileList = it.newData.tiles
                        val userChanged = it.oldData.userId != it.newData.userId
                        val newUser = it.newData.userId
                        val components = it.installedComponents

                        // Destroy all tiles that are not in the new set
                        specsToTiles
                            .filter {
                                it.key !in newTileList && it.value is TileOrNotInstalled.Tile
                            }
                            .forEach { entry ->
                                logger.logTileDestroyed(
                                    entry.key,
                                    if (userChanged) {
                                        QSPipelineLogger.TileDestroyedReason
                                            .TILE_NOT_PRESENT_IN_NEW_USER
                                    } else {
                                        QSPipelineLogger.TileDestroyedReason.TILE_REMOVED
                                    }
                                )
                                (entry.value as TileOrNotInstalled.Tile).tile.destroy()
                            }
                        // MutableMap will keep the insertion order
                        val newTileMap = mutableMapOf<TileSpec, TileOrNotInstalled>()

                        newTileList.forEach { tileSpec ->
                            if (tileSpec !in newTileMap) {
                                if (
                                    tileSpec is TileSpec.CustomTileSpec &&
                                        tileSpec.componentName !in components
                                ) {
                                    newTileMap[tileSpec] = TileOrNotInstalled.NotInstalled
                                } else {
                                    // Create tile here will never try to create a CustomTile that
                                    // is not installed
                                    val newTile =
                                        if (tileSpec in specsToTiles) {
                                            processExistingTile(
                                                tileSpec,
                                                specsToTiles.getValue(tileSpec),
                                                userChanged,
                                                newUser
                                            )
                                                ?: createTile(tileSpec)
                                        } else {
                                            createTile(tileSpec)
                                        }
                                    if (newTile != null) {
                                        newTileMap[tileSpec] = TileOrNotInstalled.Tile(newTile)
                                    }
                                }
                            }
                        }

                        val resolvedSpecs = newTileMap.keys.toList()
                        specsToTiles.clear()
                        specsToTiles.putAll(newTileMap)
                        _currentSpecsAndTiles.value =
                            newTileMap
                                .filter { it.value is TileOrNotInstalled.Tile }
                                .map {
                                    TileModel(it.key, (it.value as TileOrNotInstalled.Tile).tile)
                                }
                        logger.logTilesNotInstalled(
                            newTileMap.filter { it.value is TileOrNotInstalled.NotInstalled }.keys,
                            newUser
                        )
                        if (resolvedSpecs != newTileList) {
                            // There were some tiles that couldn't be created. Change the value in
                            // the
                            // repository
                            launch { tileSpecRepository.setTiles(currentUser.value, resolvedSpecs) }
                        }
                    }
            }
        }
    }

    override fun addTile(spec: TileSpec, position: Int) {
        scope.launch(backgroundDispatcher) {
            // Block until the list is not empty
            currentTiles.filter { it.isNotEmpty() }.first()
            tileSpecRepository.addTile(userRepository.getSelectedUserInfo().id, spec, position)
        }
    }

    override fun removeTiles(specs: Collection<TileSpec>) {
        val currentSpecsCopy = currentTilesSpecs.toSet()
        val user = currentUser.value
        // intersect: tiles that are there and are being removed
        val toFree = currentSpecsCopy.intersect(specs).filterIsInstance<TileSpec.CustomTileSpec>()
        toFree.forEach { onCustomTileRemoved(it.componentName, user) }
        if (currentSpecsCopy.intersect(specs).isNotEmpty()) {
            // We don't want to do the call to set in case getCurrentTileSpecs is not the most
            // up to date for this user.
            scope.launch { tileSpecRepository.removeTiles(user, specs) }
        }
    }

    override fun setTiles(specs: List<TileSpec>) {
        val currentSpecsCopy = currentTilesSpecs
        val user = currentUser.value
        if (currentSpecsCopy != specs) {
            // minus: tiles that were there but are not there anymore
            val toFree = currentSpecsCopy.minus(specs).filterIsInstance<TileSpec.CustomTileSpec>()
            toFree.forEach { onCustomTileRemoved(it.componentName, user) }
            scope.launch { tileSpecRepository.setTiles(user, specs) }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("CurrentTileInteractorImpl:")
        pw.println("User: ${userId.value}")
        currentTiles.value
            .map { it.tile }
            .filterIsInstance<Dumpable>()
            .forEach { it.dump(pw, args) }
    }

    override fun dumpProto(systemUIProtoDump: SystemUIProtoDump, args: Array<String>) {
        val data =
            currentTiles.value.map { it.tile.state }.mapNotNull { it?.toProto() }.toTypedArray()
        systemUIProtoDump.tiles = data
    }

    private fun onCustomTileRemoved(componentName: ComponentName, userId: Int) {
        val intent = Intent().setComponent(componentName)
        val lifecycleManager = tileLifecycleManagerFactory.create(intent, UserHandle.of(userId))
        lifecycleManager.onStopListening()
        lifecycleManager.onTileRemoved()
        customTileStatePersister.removeState(TileServiceKey(componentName, userId))
        customTileAddedRepository.setTileAdded(componentName, userId, false)
        lifecycleManager.flushMessagesAndUnbind()
    }

    private suspend fun createTile(spec: TileSpec): QSTile? {
        val tile =
            withContext(mainDispatcher) {
                if (featureFlags.tilesEnabled) {
                    newQSTileFactory.get().createTile(spec.spec)
                } else {
                    null
                }
                    ?: tileFactory.createTile(spec.spec)
            }
        if (tile == null) {
            logger.logTileNotFoundInFactory(spec)
            return null
        } else {
            return if (!tile.isAvailable) {
                logger.logTileDestroyed(
                    spec,
                    QSPipelineLogger.TileDestroyedReason.NEW_TILE_NOT_AVAILABLE,
                )
                tile.destroy()
                null
            } else {
                logger.logTileCreated(spec)
                tile
            }
        }
    }

    private fun processExistingTile(
        tileSpec: TileSpec,
        tileOrNotInstalled: TileOrNotInstalled,
        userChanged: Boolean,
        user: Int,
    ): QSTile? {
        return when (tileOrNotInstalled) {
            is TileOrNotInstalled.NotInstalled -> null
            is TileOrNotInstalled.Tile -> {
                val qsTile = tileOrNotInstalled.tile
                when {
                    !qsTile.isAvailable -> {
                        logger.logTileDestroyed(
                            tileSpec,
                            QSPipelineLogger.TileDestroyedReason.EXISTING_TILE_NOT_AVAILABLE
                        )
                        qsTile.destroy()
                        null
                    }
                    // Tile is in the current list of tiles and available.
                    // We have a handful of different cases
                    qsTile !is CustomTile -> {
                        // The tile is not a custom tile. Make sure they are reset to the correct
                        // user
                        if (userChanged) {
                            qsTile.userSwitch(user)
                            logger.logTileUserChanged(tileSpec, user)
                        }
                        qsTile
                    }
                    qsTile.user == user -> {
                        // The tile is a custom tile for the same user, just return it
                        qsTile
                    }
                    else -> {
                        // The tile is a custom tile and the user has changed. Destroy it
                        qsTile.destroy()
                        logger.logTileDestroyed(
                            tileSpec,
                            QSPipelineLogger.TileDestroyedReason.CUSTOM_TILE_USER_CHANGED
                        )
                        null
                    }
                }
            }
        }
    }

    private sealed interface TileOrNotInstalled {
        object NotInstalled : TileOrNotInstalled

        @JvmInline value class Tile(val tile: QSTile) : TileOrNotInstalled
    }

    private data class UserAndTiles(
        val userId: Int,
        val tiles: List<TileSpec>,
    )

    private data class Data(
        val oldData: UserAndTiles,
        val newData: UserAndTiles,
        val installedComponents: Set<ComponentName>,
    )
}
