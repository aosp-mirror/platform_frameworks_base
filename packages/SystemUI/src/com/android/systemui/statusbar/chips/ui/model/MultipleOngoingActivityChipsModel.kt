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
    /**
     * The chips with a currently ongoing activity which are eligible to be shown, sorted by
     * priority. These can be either shown or hidden, depending on other system states like which
     * apps are open and ongoing transitions. If this list contains the maximum number of active and
     * not-hidden chips allowed, any other lower priority active chip will be hidden and stored in
     * [overflow].
     */
    val active: List<OngoingActivityChipModel.Active> = emptyList(),
    /**
     * The chips with a currently ongoing activity that have strictly lower priority than those in
     * [active] and cannot be displayed, sorted by priority. These will *always* be hidden.
     */
    val overflow: List<OngoingActivityChipModel.Active> = emptyList(),
    /**
     * The chips with no currently ongoing activity, sorted by priority. These will *always* be
     * hidden.
     */
    val inactive: List<OngoingActivityChipModel.Inactive> = emptyList(),
)

/** Models multiple active ongoing activity chips at once. */
@Deprecated("Since StatusBarChipsModernization, use the new MultipleOngoingActivityChipsModel")
data class MultipleOngoingActivityChipsModelLegacy(
    /** The primary chip to show. This will *always* be shown. */
    val primary: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
    /**
     * The secondary chip to show. If there's not enough room in the status bar, this chip will
     * *not* be shown.
     */
    val secondary: OngoingActivityChipModel = OngoingActivityChipModel.Inactive(),
) {
    init {
        if (
            primary is OngoingActivityChipModel.Inactive &&
                secondary is OngoingActivityChipModel.Active
        ) {
            throw IllegalArgumentException("`secondary` cannot be Active if `primary` is Inactive")
        }
    }
}
