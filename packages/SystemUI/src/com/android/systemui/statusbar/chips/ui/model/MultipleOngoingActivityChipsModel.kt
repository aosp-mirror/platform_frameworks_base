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

package com.android.systemui.statusbar.chips.ui.model

/** Models multiple active ongoing activity chips at once. */
data class MultipleOngoingActivityChipsModel(
    /** The primary chip to show. This will *always* be shown. */
    val primary: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
    /**
     * The secondary chip to show. If there's not enough room in the status bar, this chip will
     * *not* be shown.
     */
    val secondary: OngoingActivityChipModel = OngoingActivityChipModel.Hidden(),
) {
    init {
        if (
            primary is OngoingActivityChipModel.Hidden &&
                secondary is OngoingActivityChipModel.Shown
        ) {
            throw IllegalArgumentException("`secondary` cannot be Shown if `primary` is Hidden")
        }
    }
}
