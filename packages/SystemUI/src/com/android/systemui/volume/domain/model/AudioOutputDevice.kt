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

package com.android.systemui.volume.domain.model

import android.graphics.drawable.Drawable
import com.android.settingslib.bluetooth.CachedBluetoothDevice

/** Models an audio output device. */
sealed interface AudioOutputDevice {

    val name: String
    val icon: Drawable?

    /** Models a built audio output device. */
    data class BuiltIn(
        override val name: String,
        override val icon: Drawable?,
    ) : AudioOutputDevice

    /** Models a wired audio output device. */
    data class Wired(
        override val name: String,
        override val icon: Drawable?,
    ) : AudioOutputDevice

    /** Models a bluetooth audio output device. */
    data class Bluetooth(
        override val name: String,
        override val icon: Drawable?,
        val cachedBluetoothDevice: CachedBluetoothDevice,
    ) : AudioOutputDevice

    /** Models a state when the current audio output device is unknown. */
    data object Unknown : AudioOutputDevice {
        override val name: String
            get() = error("Unsupported for unknown device")

        override val icon: Drawable
            get() = error("Unsupported for unknown device")
    }
}
