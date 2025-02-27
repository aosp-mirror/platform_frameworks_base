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

package com.android.systemui.volume.dialog.shared.model

import android.content.ComponentName

/** Models a state of the Volume Dialog. */
data class VolumeDialogStateModel(
    val shouldShowA11ySlider: Boolean = false,
    val isShowingSafetyWarning: VolumeDialogSafetyWarningModel =
        VolumeDialogSafetyWarningModel.Invisible,
    val streamModels: Map<Int, VolumeDialogStreamModel> = mapOf(),
    val ringerModeInternal: Int = 0,
    val ringerModeExternal: Int = 0,
    val zenMode: Int = 0,
    val effectsSuppressor: ComponentName? = null,
    val effectsSuppressorName: String? = null,
    val activeStream: Int = NO_ACTIVE_STREAM,
    val disallowAlarms: Boolean = false,
    val disallowMedia: Boolean = false,
    val disallowSystem: Boolean = false,
    val disallowRinger: Boolean = false,
) {

    companion object {
        const val NO_ACTIVE_STREAM: Int = -1
    }
}
