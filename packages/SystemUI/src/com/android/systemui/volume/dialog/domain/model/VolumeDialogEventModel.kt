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

package com.android.systemui.volume.dialog.domain.model

import android.media.AudioManager
import com.android.systemui.plugins.VolumeDialogController

/**
 * Models VolumeDialogController callback events.
 *
 * @see VolumeDialogController.Callbacks
 */
sealed interface VolumeDialogEventModel {

    data class ShowRequested(
        val reason: Int,
        val keyguardLocked: Boolean,
        val lockTaskModeState: Int,
    ) : VolumeDialogEventModel

    data class DismissRequested(val reason: Int) : VolumeDialogEventModel

    data class StateChanged(val state: VolumeDialogController.State) : VolumeDialogEventModel

    data class LayoutDirectionChanged(val layoutDirection: Int) : VolumeDialogEventModel

    data object ShowVibrateHint : VolumeDialogEventModel

    data object ShowSilentHint : VolumeDialogEventModel

    data object ScreenOff : VolumeDialogEventModel

    data class ShowSafetyWarning(val flags: Int) : VolumeDialogEventModel

    data class AccessibilityModeChanged(val showA11yStream: Boolean) : VolumeDialogEventModel

    data class ShowCsdWarning(@AudioManager.CsdWarning val csdWarning: Int, val durationMs: Int) :
        VolumeDialogEventModel

    data object VolumeChangedFromKey : VolumeDialogEventModel
}
