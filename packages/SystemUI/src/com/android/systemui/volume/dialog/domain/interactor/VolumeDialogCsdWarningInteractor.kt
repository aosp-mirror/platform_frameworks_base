/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.shared.model.VolumeDialogCsdWarningModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogPluginScope
class VolumeDialogCsdWarningInteractor
@Inject
constructor(private val stateInteractor: VolumeDialogStateInteractor) {

    /** Emits warning when the warning should be visible and null when it shouldn't */
    val csdWarning: Flow<Int?> =
        stateInteractor.volumeDialogState
            .map { it.isShowingCsdWarning }
            .flatMapLatest { model ->
                when (model) {
                    is VolumeDialogCsdWarningModel.Visible ->
                        flow {
                            emit(model.warning)
                            delay(model.duration)
                            emit(null)
                        }
                    is VolumeDialogCsdWarningModel.Invisible -> flowOf(null)
                }
            }

    fun onCsdWarningDismissed() {
        stateInteractor.setCsdWarning(VolumeDialogCsdWarningModel.Invisible)
    }
}
