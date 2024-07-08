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
import android.media.AudioSystem

/** Type-safe wrapper for [AudioManager] audio stream. */
@JvmInline
value class AudioStream(val value: Int) {
    init {
        require(value in supportedStreamTypes) { "Unsupported stream=$value" }
    }

    override fun toString(): String = AudioSystem.streamToString(value)

    companion object {
        val supportedStreamTypes =
            setOf(
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_BLUETOOTH_SCO,
                AudioManager.STREAM_SYSTEM_ENFORCED,
                AudioManager.STREAM_DTMF,
                AudioManager.STREAM_TTS,
                AudioManager.STREAM_ACCESSIBILITY,
                AudioManager.STREAM_ASSISTANT,
            )
    }
}
