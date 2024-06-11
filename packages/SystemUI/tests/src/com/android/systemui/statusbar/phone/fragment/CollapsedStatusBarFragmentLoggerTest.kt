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

package com.android.systemui.statusbar.phone.fragment

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Test
import org.mockito.Mockito.mock

@SmallTest
class CollapsedStatusBarFragmentLoggerTest : SysuiTestCase() {

    private val buffer = LogBufferFactory(DumpManager(), mock(LogcatEchoTracker::class.java))
            .create("buffer", 10)
    private val disableFlagsLogger = DisableFlagsLogger(
            listOf(DisableFlagsLogger.DisableFlag(0b001, 'A', 'a')),
            listOf(DisableFlagsLogger.DisableFlag(0b001, 'B', 'b'))
    )
    private val logger = CollapsedStatusBarFragmentLogger(buffer, disableFlagsLogger)

    @Test
    fun logDisableFlagChange_bufferHasStates() {
        val state = DisableFlagsLogger.DisableState(0, 1)

        logger.logDisableFlagChange(state)

        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        val actualString = stringWriter.toString()
        val expectedLogString =
            disableFlagsLogger.getDisableFlagsString(
                new = state,
                newAfterLocalModification = null,
            )

        assertThat(actualString).contains(expectedLogString)
    }

    @Test
    fun logVisibilityModel_bufferCorrect() {
        logger.logVisibilityModel(
            StatusBarVisibilityModel(
                showClock = false,
                showNotificationIcons = true,
                showOngoingActivityChip = false,
                showSystemInfo = true,
            )
        )

        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        val actualString = stringWriter.toString()

        assertThat(actualString).contains("showClock=false")
        assertThat(actualString).contains("showNotificationIcons=true")
        assertThat(actualString).contains("showOngoingActivityChip=false")
        assertThat(actualString).contains("showSystemInfo=true")
    }
}
