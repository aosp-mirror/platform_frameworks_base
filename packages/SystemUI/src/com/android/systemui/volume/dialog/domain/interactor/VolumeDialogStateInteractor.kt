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

package com.android.systemui.volume.dialog.domain.interactor

import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import com.android.systemui.volume.dialog.domain.model.VolumeDialogStateModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Exposes [VolumeDialogController.getState] in the [volumeDialogState].
 *
 * @see [VolumeDialogController]
 */
@VolumeDialog
class VolumeDialogStateInteractor
@Inject
constructor(
    volumeDialogCallbacksInteractor: VolumeDialogCallbacksInteractor,
    private val volumeDialogController: VolumeDialogController,
    @VolumeDialog private val coroutineScope: CoroutineScope,
) {

    val volumeDialogState: Flow<VolumeDialogStateModel> =
        volumeDialogCallbacksInteractor.event
            .onStart { volumeDialogController.getState() }
            .filterIsInstance(VolumeDialogEventModel.StateChanged::class)
            .map { it.state }
            .stateIn(scope = coroutineScope, started = SharingStarted.Eagerly, initialValue = null)
            .filterNotNull()
}
