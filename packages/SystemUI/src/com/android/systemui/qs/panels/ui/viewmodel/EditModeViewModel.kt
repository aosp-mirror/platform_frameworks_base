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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.Context
import androidx.compose.ui.util.fastMap
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.android.internal.logging.UiEventLogger
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.QSEditEvent
import com.android.systemui.qs.panels.domain.interactor.EditTilesListInteractor
import com.android.systemui.qs.panels.domain.interactor.GridLayoutTypeInteractor
import com.android.systemui.qs.panels.domain.interactor.TilesAvailabilityInteractor
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.domain.interactor.MinimumTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.metricSpec
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class EditModeViewModel
@Inject
constructor(
    private val editTilesListInteractor: EditTilesListInteractor,
    private val currentTilesInteractor: CurrentTilesInteractor,
    private val tilesAvailabilityInteractor: TilesAvailabilityInteractor,
    private val minTilesInteractor: MinimumTilesInteractor,
    private val uiEventLogger: UiEventLogger,
    @ShadeDisplayAware private val configurationInteractor: ConfigurationInteractor,
    @ShadeDisplayAware private val context: Context,
    @Named("Default") private val defaultGridLayout: GridLayout,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    gridLayoutTypeInteractor: GridLayoutTypeInteractor,
    gridLayoutMap: Map<GridLayoutType, @JvmSuppressWildcards GridLayout>,
) {
    private val _isEditing = MutableStateFlow(false)

    /**
     * Whether we should be editing right now. Use [startEditing] and [stopEditing] to change this
     */
    val isEditing = _isEditing.asStateFlow()
    private val minimumTiles: Int
        get() = minTilesInteractor.minNumberOfTiles

    val gridLayout: StateFlow<GridLayout> =
        gridLayoutTypeInteractor.layout
            .map { gridLayoutMap[it] ?: defaultGridLayout }
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), defaultGridLayout)

    /**
     * Flow of view models for each tile that should be visible in edit mode (or empty flow when not
     * editing).
     *
     * Guarantees of the data:
     * * The data for the tiles is fetched once whenever [isEditing] goes from `false` to `true`.
     *   This prevents icons/labels changing while in edit mode.
     * * It tracks the current tiles as they are added/removed/moved by the user.
     * * The tiles that are current will be in the same relative order as the user sees them in
     *   Quick Settings.
     * * The tiles that are not current will preserve their relative order even when the current
     *   tiles change.
     * * Tiles that are not available will be filtered out. None of them can be current (as they
     *   cannot be created), and they won't be able to be added.
     */
    val tiles =
        isEditing.flatMapLatest {
            if (it) {
                val editTilesData = editTilesListInteractor.getTilesToEdit()
                // Query only the non current platform tiles, as any current tile is clearly
                // available
                val unavailable =
                    tilesAvailabilityInteractor.getUnavailableTiles(
                        editTilesData.stockTiles
                            .map { it.tileSpec }
                            .minus(currentTilesInteractor.currentTilesSpecs.toSet())
                    )
                currentTilesInteractor.currentTiles
                    .map { tiles ->
                        val currentSpecs = tiles.map { it.spec }
                        val canRemoveTiles = currentSpecs.size > minimumTiles
                        val allTiles = editTilesData.stockTiles + editTilesData.customTiles
                        val allTilesMap = allTiles.associate { it.tileSpec to it }
                        val currentTiles = currentSpecs.map { allTilesMap.get(it) }.filterNotNull()
                        val nonCurrentTiles = allTiles.filter { it.tileSpec !in currentSpecs }

                        (currentTiles + nonCurrentTiles)
                            .filterNot { it.tileSpec in unavailable }
                            .map {
                                val current = it.tileSpec in currentSpecs
                                val availableActions = buildSet {
                                    if (current) {
                                        add(AvailableEditActions.MOVE)
                                        if (canRemoveTiles) {
                                            add(AvailableEditActions.REMOVE)
                                        }
                                    } else {
                                        add(AvailableEditActions.ADD)
                                    }
                                }
                                UnloadedEditTileViewModel(
                                    it.tileSpec,
                                    it.icon,
                                    it.label,
                                    it.appName,
                                    current,
                                    availableActions,
                                    it.category,
                                )
                            }
                    }
                    .combine(configurationInteractor.onAnyConfigurationChange.emitOnStart()) {
                        tiles,
                        _ ->
                        tiles.fastMap { it.load(context) }
                    }
            } else {
                emptyFlow()
            }
        }

    /** @see isEditing */
    fun startEditing() {
        if (!isEditing.value) {
            uiEventLogger.log(QSEditEvent.QS_EDIT_OPEN)
        }
        _isEditing.value = true
    }

    /** @see isEditing */
    fun stopEditing() {
        if (isEditing.value) {
            uiEventLogger.log(QSEditEvent.QS_EDIT_CLOSED)
        }
        _isEditing.value = false
    }

    /**
     * Immediately adds [tileSpec] to the current tiles at [position]. If the [tileSpec] was already
     * present, it will be moved to the new position.
     */
    fun addTile(tileSpec: TileSpec, position: Int = POSITION_AT_END) {
        val specs = currentTilesInteractor.currentTilesSpecs.toMutableList()
        val currentPosition = specs.indexOf(tileSpec)
        val moved = currentPosition != -1

        if (currentPosition != -1) {
            // No operation needed if the element is already in the list at the right position
            if (currentPosition == position) {
                return
            }
            // Removing tile if it's present at a different position to insert it at the new index.
            specs.removeAt(currentPosition)
        }

        if (position >= 0 && position < specs.size) {
            specs.add(position, tileSpec)
        } else {
            specs.add(tileSpec)
        }
        uiEventLogger.logWithPosition(
            if (moved) QSEditEvent.QS_EDIT_MOVE else QSEditEvent.QS_EDIT_ADD,
            /* uid= */ 0,
            /* packageName= */ tileSpec.metricSpec,
            if (moved && position == POSITION_AT_END) specs.size - 1 else position,
        )

        // Setting the new tiles as one operation to avoid UI jank with tiles disappearing and
        // reappearing
        currentTilesInteractor.setTiles(specs)
    }

    /** Immediately removes [tileSpec] from the current tiles. */
    fun removeTile(tileSpec: TileSpec) {
        uiEventLogger.log(
            QSEditEvent.QS_EDIT_REMOVE,
            /* uid= */ 0,
            /* packageName= */ tileSpec.metricSpec,
        )
        currentTilesInteractor.removeTiles(listOf(tileSpec))
    }

    fun setTiles(tileSpecs: List<TileSpec>) {
        val currentTiles = currentTilesInteractor.currentTilesSpecs
        currentTilesInteractor.setTiles(tileSpecs)
        applicationScope.launch(bgDispatcher) {
            calculateDiffsAndEmitUiEvents(currentTiles, tileSpecs)
        }
    }

    private fun calculateDiffsAndEmitUiEvents(
        currentTiles: List<TileSpec>,
        newTiles: List<TileSpec>,
    ) {
        val listDiff = DiffUtil.calculateDiff(DiffCallback(currentTiles, newTiles))
        listDiff.dispatchUpdatesTo(
            object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) {
                    newTiles.getOrNull(position)?.let {
                        uiEventLogger.logWithPosition(
                            QSEditEvent.QS_EDIT_ADD,
                            /* uid= */ 0,
                            /* packageName= */ it.metricSpec,
                            position,
                        )
                    }
                }

                override fun onRemoved(position: Int, count: Int) {
                    currentTiles.getOrNull(position)?.let {
                        uiEventLogger.log(QSEditEvent.QS_EDIT_REMOVE, 0, it.metricSpec)
                    }
                }

                override fun onMoved(fromPosition: Int, toPosition: Int) {
                    currentTiles.getOrNull(fromPosition)?.let {
                        uiEventLogger.logWithPosition(
                            QSEditEvent.QS_EDIT_MOVE,
                            /* uid= */ 0,
                            /* packageName= */ it.metricSpec,
                            toPosition,
                        )
                    }
                }

                override fun onChanged(position: Int, count: Int, payload: Any?) {}
            }
        )
    }
}

private class DiffCallback(
    private val currentList: List<TileSpec>,
    private val newList: List<TileSpec>,
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int {
        return currentList.size
    }

    override fun getNewListSize(): Int {
        return newList.size
    }

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return currentList[oldItemPosition] == newList[newItemPosition]
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return areItemsTheSame(oldItemPosition, newItemPosition)
    }
}
