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

package com.android.systemui.volume.panel.component.spatial.domain.model

/** Models spatial audio and head tracking enabled/disabled state. */
interface SpatialAudioEnabledModel {

    companion object {
        /** All possible SpatialAudioEnabledModel implementations. */
        val values =
            listOf(
                Disabled,
                SpatialAudioEnabled,
                HeadTrackingEnabled,
            )
    }

    /** Spatial audio is disabled. */
    data object Disabled : SpatialAudioEnabledModel

    /** Spatial audio is enabled. */
    interface SpatialAudioEnabled : SpatialAudioEnabledModel {
        companion object : SpatialAudioEnabled
    }

    /** Head tracking is enabled. This also means that [SpatialAudioEnabled]. */
    data object HeadTrackingEnabled : SpatialAudioEnabled

    /** Spatial audio enabled state is unknown. */
    data object Unknown : SpatialAudioEnabled
}
