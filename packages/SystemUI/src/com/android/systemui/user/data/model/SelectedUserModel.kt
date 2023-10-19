/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.user.data.model

import android.content.pm.UserInfo

/** A model for the currently selected user. */
data class SelectedUserModel(
    /** Information about the user. */
    val userInfo: UserInfo,
    /** The current status of the selection. */
    val selectionStatus: SelectionStatus,
)

/** The current status of the selection. */
enum class SelectionStatus {
    /** This user has started being selected but the selection hasn't completed. */
    SELECTION_IN_PROGRESS,
    /** The selection of this user has completed. */
    SELECTION_COMPLETE,
}
