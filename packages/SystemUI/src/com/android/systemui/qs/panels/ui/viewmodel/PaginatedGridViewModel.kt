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

import androidx.compose.runtime.getValue
import com.android.systemui.classifier.Classifier.QS_SWIPE_SIDE
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.qs.panels.domain.interactor.PaginatedGridInteractor
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class PaginatedGridViewModel
@AssistedInject
constructor(
    iconTilesViewModel: IconTilesViewModel,
    columnsWithMediaViewModelFactory: QSColumnsViewModel.Factory,
    paginatedGridInteractor: PaginatedGridInteractor,
    inFirstPageViewModel: InFirstPageViewModel,
    val buildNumberViewModelFactory: BuildNumberViewModel.Factory,
    val editModeButtonViewModelFactory: EditModeButtonViewModel.Factory,
    private val falsingInteractor: FalsingInteractor,
) : IconTilesViewModel by iconTilesViewModel, ExclusiveActivatable() {

    private val hydrator = Hydrator("PaginatedGridViewModel")
    private val columnsWithMediaViewModel = columnsWithMediaViewModelFactory.create(LOCATION_QS)

    val rows by
        hydrator.hydratedStateOf(
            traceName = "rows",
            initialValue = paginatedGridInteractor.defaultRows,
            source = paginatedGridInteractor.rows,
        )

    var inFirstPage by inFirstPageViewModel::inFirstPage

    val columns: Int
        get() = columnsWithMediaViewModel.columns

    fun registerSideSwipeGesture() {
        falsingInteractor.isFalseTouch(QS_SWIPE_SIDE)
    }

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }
            launch { columnsWithMediaViewModel.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): PaginatedGridViewModel
    }
}
