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

package com.android.systemui.volume

import android.media.IVolumeController
import com.android.settingslib.volume.data.model.VolumeControllerEvent
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * This class is a bridge between
 * [com.android.settingslib.volume.data.repository.AudioRepository.volumeControllerEvents] and the
 * old code that uses [IVolumeController] interface directly.
 */
class VolumeControllerAdapter
@Inject
constructor(
    @Application private val coroutineScope: CoroutineScope,
    private val audioRepository: AudioRepository,
) {

    /** Collects [Flow] of [VolumeControllerEvent] into [IVolumeController]. */
    fun collectToController(controller: IVolumeController) {
        coroutineScope.launch {
            audioRepository.volumeControllerEvents.collect { event ->
                when (event) {
                    is VolumeControllerEvent.VolumeChanged ->
                        controller.volumeChanged(event.streamType, event.flags)
                    VolumeControllerEvent.Dismiss -> controller.dismiss()
                    is VolumeControllerEvent.DisplayCsdWarning ->
                        controller.displayCsdWarning(event.csdWarning, event.displayDurationMs)
                    is VolumeControllerEvent.DisplaySafeVolumeWarning ->
                        controller.displaySafeVolumeWarning(event.flags)
                    is VolumeControllerEvent.MasterMuteChanged ->
                        controller.masterMuteChanged(event.flags)
                    is VolumeControllerEvent.SetA11yMode -> controller.setA11yMode(event.mode)
                    is VolumeControllerEvent.SetLayoutDirection ->
                        controller.setLayoutDirection(event.layoutDirection)
                }
            }
        }
    }

    fun notifyVolumeControllerVisible(isVisible: Boolean) {
        coroutineScope.launch { audioRepository.notifyVolumeControllerVisible(isVisible) }
    }
}
