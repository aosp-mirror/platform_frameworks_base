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

/** Models current Volume Dialog visibility state. */
sealed interface VolumeDialogVisibilityModel {

    /** Dialog is currently visible. */
    data class Visible(val reason: Int, val keyguardLocked: Boolean, val lockTaskModeState: Int) :
        VolumeDialogVisibilityModel

    /** Dialog has never been shown. So it's just invisible. */
    interface Invisible : VolumeDialogVisibilityModel {
        companion object : Invisible
    }

    /** Dialog has been shown and then dismissed. */
    data class Dismissed(val reason: Int) : Invisible
}
