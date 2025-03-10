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

package com.android.systemui.volume.dialog.sliders.domain.model

/** Models different possible audio sliders shown in the Volume Dialog. */
sealed interface VolumeDialogSliderType {

    // VolumeDialogController uses the same model for every slider type. We need to follow the same
    // logic until we refactor and decouple data and domain layers from the VolumeDialogController
    // into separated interactors.
    val audioStream: Int

    data class Stream(override val audioStream: Int) : VolumeDialogSliderType

    data class RemoteMediaStream(override val audioStream: Int) : VolumeDialogSliderType

    data class AudioSharingStream(override val audioStream: Int) : VolumeDialogSliderType
}
