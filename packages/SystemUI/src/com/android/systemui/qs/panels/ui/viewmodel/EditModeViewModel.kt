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
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.panels.domain.interactor.EditTilesListInteractor
import com.android.systemui.qs.panels.domain.interactor.GridLayoutTypeInteractor
import com.android.systemui.qs.panels.domain.interactor.TilesAvailabilityInteractor
import com.android.systemui.qs.panels.shared.model.GridLayoutType
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.domain.interactor.MinimumTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import javax.inject.Named
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

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class EditModeViewModel
@Inject
constructor(
    private val editTilesListInteractor: EditTilesListInteractor,
    private val currentTilesInteractor: CurrentTilesInteractor,
    private val tilesAvailabilityInteractor: TilesAvailabilityInteractor,
    private val minTilesInteractor: MinimumTilesInteractor,
    private val configurationInteractor: ConfigurationInteractor,
    @Application private val applicationContext: Context,
    @Named("Default") private val defaultGridLayout: GridLayout,
    @Application private val applicationScope: CoroutineScope,
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
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                defaultGridLayout,
            )

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
                        tiles.fastMap { it.load(applicationContext) }
                    }
            } else {
                emptyFlow()
            }
        }

    /** @see isEditing */
    fun startEditing() {
        _isEditing.value = true
    }

    /** @see isEditing */
    fun stopEditing() {
        _isEditing.value = false
    }

    /**
     * Immediately adds [tileSpec] to the current tiles at [position]. If the [tileSpec] was already
     * present, it will be moved to the new position.
     */
    fun addTile(tileSpec: TileSpec, position: Int = POSITION_AT_END) {
        val specs = currentTilesInteractor.currentTilesSpecs.toMutableList()
        val currentPosition = specs.indexOf(tileSpec)

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

        // Setting the new tiles as one operation to avoid UI jank with tiles disappearing and
        // reappearing
        currentTilesInteractor.setTiles(specs)
    }

    /** Immediately removes [tileSpec] from the current tiles. */
    fun removeTile(tileSpec: TileSpec) {
        currentTilesInteractor.removeTiles(listOf(tileSpec))
    }

    fun setTiles(tileSpecs: List<TileSpec>) {
        currentTilesInteractor.setTiles(tileSpecs)
    }

    /** Immediately resets the current tiles to the default list. */
    fun resetCurrentTilesToDefault() {
        throw NotImplementedError("This is not supported yet")
    }
}
