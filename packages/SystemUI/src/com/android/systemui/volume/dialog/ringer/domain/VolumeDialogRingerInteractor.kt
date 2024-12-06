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

package com.android.systemui.volume.dialog.ringer.domain

import android.media.AudioManager
import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_SILENT
import android.media.AudioManager.RINGER_MODE_VIBRATE
import com.android.settingslib.volume.data.repository.AudioSystemRepository
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.ringer.data.repository.VolumeDialogRingerFeedbackRepository
import com.android.systemui.volume.dialog.ringer.shared.model.VolumeDialogRingerModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStateModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** Exposes [VolumeDialogRingerModel]. */
@VolumeDialog
class VolumeDialogRingerInteractor
@Inject
constructor(
    @VolumeDialog private val coroutineScope: CoroutineScope,
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
    private val controller: VolumeDialogController,
    private val audioSystemRepository: AudioSystemRepository,
    private val ringerFeedbackRepository: VolumeDialogRingerFeedbackRepository,
) {

    val ringerModel: Flow<VolumeDialogRingerModel> =
        volumeDialogStateInteractor.volumeDialogState
            .mapNotNull { toRingerModel(it) }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    private fun toRingerModel(state: VolumeDialogStateModel): VolumeDialogRingerModel? {
        return state.streamModels[AudioManager.STREAM_RING]?.let {
            VolumeDialogRingerModel(
                availableModes =
                    mutableListOf(RingerMode(RINGER_MODE_NORMAL), RingerMode(RINGER_MODE_SILENT))
                        .also { list ->
                            if (controller.hasVibrator()) {
                                list.add(RingerMode(RINGER_MODE_VIBRATE))
                            }
                        },
                currentRingerMode = RingerMode(state.ringerModeInternal),
                isMuted = it.level == 0 || it.muted,
                level = it.level,
                levelMax = it.levelMax,
                isSingleVolume = audioSystemRepository.isSingleVolume,
            )
        }
    }

    fun setRingerMode(ringerMode: RingerMode) {
        controller.setRingerMode(ringerMode.value, false)
    }

    fun scheduleTouchFeedback() {
        controller.scheduleTouchFeedback()
    }

    suspend fun getToastCount(): Int {
        return ringerFeedbackRepository.getToastCount()
    }

    suspend fun updateToastCount(toastCount: Int) {
        ringerFeedbackRepository.updateToastCount(toastCount)
    }
}
