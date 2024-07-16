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

package com.android.systemui.statusbar.chips.mediaprojection.domain.model

import com.android.systemui.mediaprojection.data.model.MediaProjectionState

/**
 * Represents the state of media projection needed to show chips in the status bar. In particular,
 * also includes what type of projection is occurring.
 */
sealed class ProjectionChipModel {
    /** There is no media being projected. */
    data object NotProjecting : ProjectionChipModel()

    /** Media is currently being projected. */
    data class Projecting(
        val type: Type,
        val projectionState: MediaProjectionState.Projecting,
    ) : ProjectionChipModel()

    enum class Type {
        /**
         * This projection is sharing your phone screen content to another app on the same device.
         */
        SHARE_TO_APP,
        /** This projection is sharing your phone screen content to a different device. */
        CAST_TO_OTHER_DEVICE,
    }
}
