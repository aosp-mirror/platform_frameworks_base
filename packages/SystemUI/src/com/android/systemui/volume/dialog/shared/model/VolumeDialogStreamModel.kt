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

import android.content.Context
import androidx.annotation.StringRes
import com.android.systemui.plugins.VolumeDialogController

/** Models a state of an audio stream of the Volume Dialog. */
data class VolumeDialogStreamModel(
    val stream: Int,
    val isDynamic: Boolean = false,
    val isActive: Boolean,
    val level: Int = 0,
    val levelMin: Int = 0,
    val levelMax: Int = 0,
    val muted: Boolean = false,
    val muteSupported: Boolean = false,
    /** You likely need to use [streamLabel] instead. */
    @StringRes val name: Int = 0,
    /** You likely need to use [streamLabel] instead. */
    val remoteLabel: String? = null,
    val routedToBluetooth: Boolean = false,
) {
    constructor(
        stream: Int,
        isActive: Boolean,
        legacyState: VolumeDialogController.StreamState,
    ) : this(
        stream = stream,
        isActive = isActive,
        isDynamic = legacyState.dynamic,
        level = legacyState.level,
        levelMin = legacyState.levelMin,
        levelMax = legacyState.levelMax,
        muted = legacyState.muted,
        muteSupported = legacyState.muteSupported,
        name = legacyState.name,
        remoteLabel = legacyState.remoteLabel,
        routedToBluetooth = legacyState.routedToBluetooth,
    )
}

fun VolumeDialogStreamModel.streamLabel(context: Context): String {
    if (remoteLabel != null) {
        return remoteLabel
    }
    return context.resources.getString(name)
}
