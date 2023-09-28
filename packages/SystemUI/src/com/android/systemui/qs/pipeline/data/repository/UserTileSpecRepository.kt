package com.android.systemui.qs.pipeline.data.repository

import android.annotation.UserIdInt
import android.database.ContentObserver
import android.provider.Settings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.util.settings.SecureSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single user version of [TileSpecRepository]. It provides a similar interface as
 * [TileSpecRepository], but focusing solely on the user it was created for.
 *
 * This is the source of truth for that user's tiles, after the user has been started. Persisting
 * all the changes to [Settings]. Changes in [Settings] that disagree with this repository will be
 * reverted
 *
 * All operations against [Settings] will be performed in a background thread.
 */
class UserTileSpecRepository
@AssistedInject
constructor(
    @Assisted private val userId: Int,
    private val defaultTilesRepository: DefaultTilesRepository,
    private val secureSettings: SecureSettings,
    private val logger: QSPipelineLogger,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val defaultTiles: List<TileSpec>
        get() = defaultTilesRepository.defaultTiles

    private val changeEvents = MutableSharedFlow<ChangeAction>(
        extraBufferCapacity = CHANGES_BUFFER_SIZE
    )

    private lateinit var _tiles: StateFlow<List<TileSpec>>

    suspend fun tiles(): Flow<List<TileSpec>> {
        if (!::_tiles.isInitialized) {
            _tiles =
                changeEvents
                    .scan(loadTilesFromSettingsAndParse(userId)) { current, change ->
                        change.apply(current).also {
                            if (current != it) {
                                if (change is RestoreTiles) {
                                    logger.logTilesRestoredAndReconciled(current, it, userId)
                                } else {
                                    logger.logProcessTileChange(change, it, userId)
                                }
                            }
                        }
                    }
                    .flowOn(backgroundDispatcher)
                    .stateIn(applicationScope)
                    .also { startFlowCollections(it) }
        }
        return _tiles
    }

    private fun startFlowCollections(tiles: StateFlow<List<TileSpec>>) {
        applicationScope.launch(backgroundDispatcher) {
            launch { tiles.collect { storeTiles(userId, it) } }
            launch {
                // As Settings is not the source of truth, once we started tracking tiles for a
                // user, we don't want anyone to change the underlying setting. Therefore, if there
                // are any changes that don't match with the source of truth (this class), we
                // overwrite them with the current value.
                ConflatedCallbackFlow.conflatedCallbackFlow {
                        val observer =
                            object : ContentObserver(null) {
                                override fun onChange(selfChange: Boolean) {
                                    trySend(Unit)
                                }
                            }
                        secureSettings.registerContentObserverForUser(SETTING, observer, userId)
                        awaitClose { secureSettings.unregisterContentObserver(observer) }
                    }
                    .map { loadTilesFromSettings(userId) }
                    .flowOn(backgroundDispatcher)
                    .collect { setting ->
                        val current = tiles.value
                        if (setting != current) {
                            storeTiles(userId, current)
                        }
                    }
            }
        }
    }

    private suspend fun storeTiles(@UserIdInt forUser: Int, tiles: List<TileSpec>) {
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

    suspend fun addTile(tile: TileSpec, position: Int = TileSpecRepository.POSITION_AT_END) {
        if (tile is TileSpec.Invalid) {
            return
        }
        changeEvents.emit(AddTile(tile, position))
    }

    suspend fun removeTiles(tiles: Collection<TileSpec>) {
        changeEvents.emit(RemoveTiles(tiles))
    }

    suspend fun setTiles(tiles: List<TileSpec>) {
        changeEvents.emit(ChangeTiles(tiles))
    }

    private fun parseTileSpecs(fromSettings: List<TileSpec>, user: Int): List<TileSpec> {
        return if (fromSettings.isNotEmpty()) {
            fromSettings.also { logger.logParsedTiles(it, false, user) }
        } else {
            defaultTiles.also { logger.logParsedTiles(it, true, user) }
        }
    }

    private suspend fun loadTilesFromSettingsAndParse(userId: Int): List<TileSpec> {
        return parseTileSpecs(loadTilesFromSettings(userId), userId)
    }

    private suspend fun loadTilesFromSettings(userId: Int): List<TileSpec> {
        return withContext(backgroundDispatcher) {
                secureSettings.getStringForUser(SETTING, userId) ?: ""
            }
            .toTilesList()
    }

    suspend fun reconcileRestore(restoreData: RestoreData, currentAutoAdded: Set<TileSpec>) {
        changeEvents.emit(RestoreTiles(restoreData, currentAutoAdded))
    }

    sealed interface ChangeAction {
        fun apply(currentTiles: List<TileSpec>): List<TileSpec>
    }

    private data class AddTile(
        val tileSpec: TileSpec,
        val position: Int = TileSpecRepository.POSITION_AT_END
    ) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            val tilesList = currentTiles.toMutableList()
            if (tileSpec !in tilesList) {
                if (position < 0 || position >= tilesList.size) {
                    tilesList.add(tileSpec)
                } else {
                    tilesList.add(position, tileSpec)
                }
            }
            return tilesList
        }
    }

    private data class RemoveTiles(val tileSpecs: Collection<TileSpec>) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            return currentTiles.toMutableList().apply { removeAll(tileSpecs) }
        }
    }

    private data class ChangeTiles(
        val newTiles: List<TileSpec>,
    ) : ChangeAction {
        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            val new = newTiles.filter { it !is TileSpec.Invalid }
            return if (new.isNotEmpty()) new else currentTiles
        }
    }

    private data class RestoreTiles(
        val restoreData: RestoreData,
        val currentAutoAdded: Set<TileSpec>,
    ) : ChangeAction {

        override fun apply(currentTiles: List<TileSpec>): List<TileSpec> {
            return reconcileTiles(currentTiles, currentAutoAdded, restoreData)
        }
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_TILES
        private const val DELIMITER = TilesSettingConverter.DELIMITER
        // We want a small buffer in case multiple changes come in at the same time (sometimes
        // happens in first start. This should be enough to not lose changes.
        private const val CHANGES_BUFFER_SIZE = 10

        private fun String.toTilesList() = TilesSettingConverter.toTilesList(this)

        fun reconcileTiles(
            currentTiles: List<TileSpec>,
            currentAutoAdded: Set<TileSpec>,
            restoreData: RestoreData
        ): List<TileSpec> {
            val toRestore = restoreData.restoredTiles.toMutableList()
            val freshlyAutoAdded =
                currentAutoAdded.filterNot { it in restoreData.restoredAutoAddedTiles }
            freshlyAutoAdded
                .filter { it in currentTiles && it !in restoreData.restoredTiles }
                .map { it to currentTiles.indexOf(it) }
                .sortedBy { it.second }
                .forEachIndexed { iteration, (tile, position) ->
                    val insertAt = position + iteration
                    if (insertAt > toRestore.size) {
                        toRestore.add(tile)
                    } else {
                        toRestore.add(insertAt, tile)
                    }
                }

            return toRestore
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            userId: Int,
        ): UserTileSpecRepository
    }
}
