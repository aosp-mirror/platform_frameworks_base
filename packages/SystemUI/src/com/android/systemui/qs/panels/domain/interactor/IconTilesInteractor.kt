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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.qs.panels.data.repository.DefaultLargeTilesRepository
import com.android.systemui.qs.panels.data.repository.LargeTileSpanRepository
import com.android.systemui.qs.panels.shared.model.PanelsLog
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Interactor for retrieving the list of [TileSpec] to be displayed as icons and resizing icons. */
@SysUISingleton
class IconTilesInteractor
@Inject
constructor(
    private val repo: DefaultLargeTilesRepository,
    private val currentTilesInteractor: CurrentTilesInteractor,
    private val preferencesInteractor: QSPreferencesInteractor,
    largeTilesSpanRepo: LargeTileSpanRepository,
    @PanelsLog private val logBuffer: LogBuffer,
    @Application private val applicationScope: CoroutineScope,
) {
    val largeTilesSpecs =
        preferencesInteractor.largeTilesSpecs
            .onEach { logChange(it) }
            .stateIn(applicationScope, SharingStarted.Eagerly, repo.defaultLargeTiles)

    val largeTilesSpan: StateFlow<Int> = largeTilesSpanRepo.span

    fun isIconTile(spec: TileSpec): Boolean = !largeTilesSpecs.value.contains(spec)

    fun setLargeTiles(specs: Set<TileSpec>) {
        preferencesInteractor.setLargeTilesSpecs(specs)
    }

    fun resetToDefault() {
        preferencesInteractor.setLargeTilesSpecs(repo.defaultLargeTiles)
    }

    fun resize(spec: TileSpec, toIcon: Boolean) {
        if (!isCurrent(spec)) {
            return
        }

        val isIcon = !largeTilesSpecs.value.contains(spec)
        if (toIcon && !isIcon) {
            preferencesInteractor.setLargeTilesSpecs(largeTilesSpecs.value - spec)
        } else if (!toIcon && isIcon) {
            preferencesInteractor.setLargeTilesSpecs(largeTilesSpecs.value + spec)
        }
    }

    private fun isCurrent(spec: TileSpec): Boolean {
        return currentTilesInteractor.currentTilesSpecs.contains(spec)
    }

    private fun logChange(specs: Set<TileSpec>) {
        logBuffer.log(
            LOG_BUFFER_LARGE_TILES_SPECS_CHANGE_TAG,
            LogLevel.DEBUG,
            { str1 = specs.toString() },
            { "Large tiles change: $str1" },
        )
    }

    private companion object {
        const val LOG_BUFFER_LARGE_TILES_SPECS_CHANGE_TAG = "LargeTilesSpecsChange"
    }
}
