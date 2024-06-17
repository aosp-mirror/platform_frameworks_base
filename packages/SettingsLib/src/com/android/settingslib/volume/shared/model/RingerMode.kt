/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.volume.shared.model

import android.media.AudioManager

/** Type-safe wrapper for [AudioManager] ringer mode. */
@JvmInline
value class RingerMode(val value: Int) {

    init {
        require(value in supportedRingerModes) { "Unsupported stream=$value" }
    }

    private companion object {
        val supportedRingerModes =
            setOf(
                AudioManager.RINGER_MODE_SILENT,
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_MAX,
            )
    }
}
