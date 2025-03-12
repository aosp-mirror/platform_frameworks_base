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

package com.android.systemui.volume.dialog.sliders.ui.viewmodel

import com.android.systemui.util.time.SystemClock
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSliderInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/*
 This prevents volume slider updates while user interacts with it. This is needed due to the
 flawed VolumeDialogControllerImpl. It has a single threaded message queue that handles all state
 updates and doesn't skip sequential updates of the same stream. This leads to a bottleneck when
 user rigorously adjusts the slider.

 Remove this when getting rid of the VolumeDialogControllerImpl as this doesn't happen in the
 Volume Panel that uses the new coroutine-backed AudioRepository.
*/
// TODO(b/375355785) remove this
private const val VOLUME_UPDATE_GRACE_PERIOD = 1000

@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogSliderScope
class VolumeDialogSliderViewModel
@Inject
constructor(
    private val interactor: VolumeDialogSliderInteractor,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    private val volumeDialogSliderIconProvider: VolumeDialogSliderIconProvider,
    private val systemClock: SystemClock,
) {

    private val userVolumeUpdates = MutableStateFlow<VolumeUpdate?>(null)
    private val model: Flow<VolumeDialogStreamModel> =
        interactor.slider
            .filter {
                val lastVolumeUpdateTime = userVolumeUpdates.value?.timestampMillis ?: 0
                getTimestampMillis() - lastVolumeUpdateTime > VOLUME_UPDATE_GRACE_PERIOD
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    val state: Flow<VolumeDialogSliderStateModel> =
        model.flatMapLatest { streamModel ->
            with(streamModel) {
                    volumeDialogSliderIconProvider.getStreamIcon(
                        stream = stream,
                        level = level,
                        levelMin = levelMin,
                        levelMax = levelMax,
                        isMuted = muted,
                        isRoutedToBluetooth = routedToBluetooth,
                    )
                }
                .map { icon -> streamModel.toStateModel(icon) }
        }

    init {
        userVolumeUpdates
            .filterNotNull()
            .mapLatest { volume ->
                interactor.setStreamVolume(volume.newVolumeLevel)
                Events.writeEvent(Events.EVENT_TOUCH_LEVEL_CHANGED, model.first().stream, volume)
            }
            .launchIn(coroutineScope)
    }

    fun setStreamVolume(volume: Int, fromUser: Boolean) {
        if (fromUser) {
            visibilityInteractor.resetDismissTimeout()
            userVolumeUpdates.value =
                VolumeUpdate(newVolumeLevel = volume, timestampMillis = getTimestampMillis())
        }
    }

    private fun getTimestampMillis(): Long = systemClock.uptimeMillis()

    private data class VolumeUpdate(val newVolumeLevel: Int, val timestampMillis: Long)
}
