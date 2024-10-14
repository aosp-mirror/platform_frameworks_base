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

package com.android.systemui.volume.dialog.ringer.data

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.volume.dialog.ringer.shared.model.VolumeDialogRingerModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update

/** Stores the state of volume dialog ringer model */
@SysUISingleton
class VolumeDialogRingerRepository @Inject constructor() {

    private val mutableRingerModel = MutableStateFlow<VolumeDialogRingerModel?>(null)
    val ringerModel: Flow<VolumeDialogRingerModel> = mutableRingerModel.filterNotNull()

    fun updateRingerModel(update: (current: VolumeDialogRingerModel?) -> VolumeDialogRingerModel) {
        mutableRingerModel.update(update)
    }
}
