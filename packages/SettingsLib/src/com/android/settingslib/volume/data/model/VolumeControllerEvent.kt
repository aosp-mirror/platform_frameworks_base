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

package com.android.settingslib.volume.data.model

import android.media.IVolumeController

/** Models events received via [IVolumeController] */
sealed interface VolumeControllerEvent {

    /** @see [IVolumeController.displaySafeVolumeWarning] */
    data class DisplaySafeVolumeWarning(val flags: Int) : VolumeControllerEvent

    /** @see [IVolumeController.volumeChanged] */
    data class VolumeChanged(val streamType: Int, val flags: Int) : VolumeControllerEvent

    /** @see [IVolumeController.masterMuteChanged] */
    data class MasterMuteChanged(val flags: Int) : VolumeControllerEvent

    /** @see [IVolumeController.setLayoutDirection] */
    data class SetLayoutDirection(val layoutDirection: Int) : VolumeControllerEvent

    /** @see [IVolumeController.setA11yMode] */
    data class SetA11yMode(val mode: Int) : VolumeControllerEvent

    /** @see [IVolumeController.displayCsdWarning] */
    data class DisplayCsdWarning(
        val csdWarning: Int,
        val displayDurationMs: Int,
    ) : VolumeControllerEvent

    /** @see [IVolumeController.dismiss] */
    data object Dismiss : VolumeControllerEvent
}
