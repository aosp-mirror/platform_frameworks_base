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

import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class OngoingCallLoggerTest : SysuiTestCase() {
    private val uiEventLoggerFake = UiEventLoggerFake()
    private val ongoingCallLogger = OngoingCallLogger(uiEventLoggerFake)

    @Test
    fun logChipClicked_clickEventLogged() {
        ongoingCallLogger.logChipClicked()

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
                .isEqualTo(OngoingCallLogger.OngoingCallEvents.ONGOING_CALL_CLICKED.id)
    }

    @Test
    fun logChipVisibilityChanged_changeFromInvisibleToVisible_visibleEventLogged() {
        ongoingCallLogger.logChipVisibilityChanged(false)
        ongoingCallLogger.logChipVisibilityChanged(true)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
                .isEqualTo(OngoingCallLogger.OngoingCallEvents.ONGOING_CALL_VISIBLE.id)
    }

    @Test
    fun logChipVisibilityChanged_changeFromVisibleToInvisible_eventNotLogged() {
        // Setting the chip to visible here will trigger a log
        ongoingCallLogger.logChipVisibilityChanged(true)
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)

        ongoingCallLogger.logChipVisibilityChanged(false)

        // Expect that there were no new logs
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
    }

    @Test
    fun logChipVisibilityChanged_visibleThenVisibleAgain_eventNotLogged() {
        // Setting the chip to visible here will trigger a log
        ongoingCallLogger.logChipVisibilityChanged(true)
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)

        ongoingCallLogger.logChipVisibilityChanged(true)

        // Expect that there were no new logs
        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
    }
}
