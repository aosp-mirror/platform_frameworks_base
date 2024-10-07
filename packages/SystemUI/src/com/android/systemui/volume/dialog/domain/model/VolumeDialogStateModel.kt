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

import android.content.ComponentName
import android.util.SparseArray
import androidx.core.util.keyIterator
import com.android.systemui.plugins.VolumeDialogController

/** Models a state of the Volume Dialog. */
data class VolumeDialogStateModel(
    val states: Map<Int, VolumeDialogStreamStateModel>,
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

    constructor(
        legacyState: VolumeDialogController.State
    ) : this(
        states = legacyState.states.mapToMap { VolumeDialogStreamStateModel(it) },
        ringerModeInternal = legacyState.ringerModeInternal,
        ringerModeExternal = legacyState.ringerModeExternal,
        zenMode = legacyState.zenMode,
        effectsSuppressor = legacyState.effectsSuppressor,
        effectsSuppressorName = legacyState.effectsSuppressorName,
        activeStream = legacyState.activeStream,
        disallowAlarms = legacyState.disallowAlarms,
        disallowMedia = legacyState.disallowMedia,
        disallowSystem = legacyState.disallowSystem,
        disallowRinger = legacyState.disallowRinger,
    )

    companion object {
        const val NO_ACTIVE_STREAM: Int = -1
    }
}

private fun <INPUT, OUTPUT> SparseArray<INPUT>.mapToMap(map: (INPUT) -> OUTPUT): Map<Int, OUTPUT> {
    val resultMap = mutableMapOf<Int, OUTPUT>()
    for (key in keyIterator()) {
        val mappedValue: OUTPUT = map(get(key)!!)
        resultMap[key] = mappedValue
    }
    return resultMap
}
