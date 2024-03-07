package com.android.systemui.qs.pipeline.data.repository

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single user version of [AutoAddRepository]. It provides a similar interface as
 * [AutoAddRepository], but focusing solely on the user it was created for.
 *
 * This is the source of truth for that user's tiles, after the user has been started. Persisting
 * all the changes to [Settings]. Changes in [Settings] that disagree with this repository will be
 * reverted
 *
 * All operations against [Settings] will be performed in a background thread.
 */
class UserAutoAddRepository
@AssistedInject
constructor(
    @Assisted private val userId: Int,
    private val secureSettings: SecureSettings,
    private val logger: QSPipelineLogger,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
) {

    private val changeEvents = MutableSharedFlow<ChangeAction>(
        extraBufferCapacity = CHANGES_BUFFER_SIZE
    )

    private lateinit var _autoAdded: StateFlow<Set<TileSpec>>

    suspend fun autoAdded(): StateFlow<Set<TileSpec>> {
        if (!::_autoAdded.isInitialized) {
            _autoAdded =
                changeEvents
                    .scan(load().also { logger.logAutoAddTilesParsed(userId, it) }) {
                        current,
                        change ->
                        change.apply(current).also {
                            if (change is RestoreTiles) {
                                logger.logAutoAddTilesRestoredReconciled(userId, it)
                            }
                        }
                    }
                    .flowOn(bgDispatcher)
                    .stateIn(applicationScope)
                    .also { startFlowCollections(it) }
        }
        return _autoAdded
    }

    private fun startFlowCollections(autoAdded: StateFlow<Set<TileSpec>>) {
        applicationScope.launch(bgDispatcher) {
            launch { autoAdded.collect { store(it) } }
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
                    .map { load() }
                    .flowOn(bgDispatcher)
                    .collect { setting ->
                        val current = autoAdded.value
                        if (setting != current) {
                            store(current)
                        }
                    }
            }
        }
    }

    suspend fun markTileAdded(spec: TileSpec) {
        if (spec is TileSpec.Invalid) {
            return
        }
        changeEvents.emit(MarkTile(spec))
    }

    suspend fun unmarkTileAdded(spec: TileSpec) {
        if (spec is TileSpec.Invalid) {
            return
        }
        changeEvents.emit(UnmarkTile(spec))
    }

    private suspend fun store(tiles: Set<TileSpec>) {
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

    private suspend fun load(): Set<TileSpec> {
        return withContext(bgDispatcher) {
            (secureSettings.getStringForUser(SETTING, userId) ?: "").toTilesSet()
        }
    }

    suspend fun reconcileRestore(restoreData: RestoreData) {
        changeEvents.emit(RestoreTiles(restoreData))
    }

    private sealed interface ChangeAction {
        fun apply(currentAutoAdded: Set<TileSpec>): Set<TileSpec>
    }

    private data class MarkTile(
        val tileSpec: TileSpec,
    ) : ChangeAction {
        override fun apply(currentAutoAdded: Set<TileSpec>): Set<TileSpec> {
            return currentAutoAdded.toMutableSet().apply { add(tileSpec) }
        }
    }

    private data class UnmarkTile(
        val tileSpec: TileSpec,
    ) : ChangeAction {
        override fun apply(currentAutoAdded: Set<TileSpec>): Set<TileSpec> {
            return currentAutoAdded.toMutableSet().apply { remove(tileSpec) }
        }
    }

    private data class RestoreTiles(
        val restoredData: RestoreData,
    ) : ChangeAction {
        override fun apply(currentAutoAdded: Set<TileSpec>): Set<TileSpec> {
            return currentAutoAdded + restoredData.restoredAutoAddedTiles
        }
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_AUTO_ADDED_TILES
        private const val DELIMITER = ","
        // We want a small buffer in case multiple changes come in at the same time (sometimes
        // happens in first start. This should be enough to not lose changes.
        private const val CHANGES_BUFFER_SIZE = 10

        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)
    }

    @AssistedFactory
    interface Factory {
        fun create(userId: Int): UserAutoAddRepository
    }
}
