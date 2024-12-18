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

package com.android.systemui.volume.dialog.data.repository

import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPlugin
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStateModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds current [VolumeDialogStateModel]. */
@VolumeDialogPlugin
class VolumeDialogStateRepository @Inject constructor() {

    private val mutableState = MutableStateFlow(VolumeDialogStateModel())
    val state: Flow<VolumeDialogStateModel> = mutableState.asStateFlow()

    fun updateState(update: (VolumeDialogStateModel) -> VolumeDialogStateModel) {
        mutableState.update(update)
    }
}
