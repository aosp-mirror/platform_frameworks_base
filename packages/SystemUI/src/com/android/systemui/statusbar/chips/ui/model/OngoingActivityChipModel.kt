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

import android.view.View
import com.android.systemui.common.shared.model.Icon

/** Model representing the display of an ongoing activity as a chip in the status bar. */
sealed class OngoingActivityChipModel {
    /** This chip shouldn't be shown. */
    data object Hidden : OngoingActivityChipModel()

    /** This chip should be shown with the given information. */
    data class Shown(
        /** The icon to show on the chip. */
        val icon: Icon,
        /**
         * The time this event started, used to show the timer.
         *
         * This time should be relative to
         * [com.android.systemui.util.time.SystemClock.elapsedRealtime], *not*
         * [com.android.systemui.util.time.SystemClock.currentTimeMillis] because the
         * [ChipChronometer] is based off of elapsed realtime. See
         * [android.widget.Chronometer.setBase].
         */
        val startTimeMs: Long,
        /** Listener method to invoke when this chip is clicked. */
        val onClickListener: View.OnClickListener,
    ) : OngoingActivityChipModel()
}
