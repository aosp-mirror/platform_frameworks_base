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

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.qs.panels.domain.interactor.QSColumnsInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * View model for the number of columns that should be shown in a QS grid.
 * * Create it with a [MediaLocation] to halve the number of columns when media should show in a row
 *   with the tiles.
 * * Create it with a `null` [MediaLocation] to ignore media visibility (useful for edit mode).
 */
class QSColumnsViewModel
@AssistedInject
constructor(
    interactor: QSColumnsInteractor,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    @Assisted @MediaLocation mediaLocation: Int?,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("QSColumnsViewModelWithMedia")

    val columns by derivedStateOf {
        if (mediaInRowInLandscapeViewModel?.shouldMediaShowInRow == true) {
            columnsWithoutMedia / 2
        } else {
            columnsWithoutMedia
        }
    }

    private val mediaInRowInLandscapeViewModel =
        mediaLocation?.let { mediaInRowInLandscapeViewModelFactory.create(it) }

    private val columnsWithoutMedia by
        hydrator.hydratedStateOf(traceName = "columnsWithoutMedia", source = interactor.columns)

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }
            launch { mediaInRowInLandscapeViewModel?.activate() }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(mediaLocation: Int?): QSColumnsViewModel

        fun createWithoutMediaTracking() = create(null)
    }
}
