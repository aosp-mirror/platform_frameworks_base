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

package com.android.systemui.volume.panel.component.volume.domain.model

import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession

/** The type of volume slider that can be shown at the UI. */
sealed interface SliderType {

    /** The slider represents one of the device volume streams. */
    data class Stream(val stream: AudioStream) : SliderType

    /** The represents media device casting volume. */
    data class MediaDeviceCast(val session: MediaDeviceSession) : SliderType
}
