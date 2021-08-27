/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.ongoingcall

import androidx.annotation.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** A class to log events for the ongoing call chip. */
@SysUISingleton
class OngoingCallLogger @Inject constructor(private val logger: UiEventLogger) {

    private var chipIsVisible: Boolean = false

    /** Logs that the ongoing call chip was clicked. */
    fun logChipClicked() {
        logger.log(OngoingCallEvents.ONGOING_CALL_CLICKED)
    }

    /**
     * If needed, logs that the ongoing call chip's visibility has changed.
     *
     * For now, only logs when the chip changes from not visible to visible.
     */
    fun logChipVisibilityChanged(chipIsVisible: Boolean) {
        if (chipIsVisible && chipIsVisible != this.chipIsVisible) {
            logger.log(OngoingCallEvents.ONGOING_CALL_VISIBLE)
        }
        this.chipIsVisible = chipIsVisible
    }

    @VisibleForTesting
    enum class OngoingCallEvents(val metricId: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The ongoing call chip became visible")
        ONGOING_CALL_VISIBLE(813),

        @UiEvent(doc = "The ongoing call chip was clicked")
        ONGOING_CALL_CLICKED(814);

        override fun getId() = metricId
    }
}
