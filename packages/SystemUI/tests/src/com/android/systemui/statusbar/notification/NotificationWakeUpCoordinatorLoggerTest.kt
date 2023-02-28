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

package com.android.systemui.statusbar.notification

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.plugins.log.LogcatEchoTracker
import com.android.systemui.statusbar.StatusBarState
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationWakeUpCoordinatorLoggerTest : SysuiTestCase() {

    private val logBufferCounter = LogBufferCounter()
    private lateinit var logger: NotificationWakeUpCoordinatorLogger

    private fun verifyDidLog(times: Int) {
        logBufferCounter.verifyDidLog(times)
    }

    @Before
    fun setup() {
        logger = NotificationWakeUpCoordinatorLogger(logBufferCounter.logBuffer)
    }

    @Test
    fun setDozeAmountWillThrottleFractionalUpdates() {
        logger.logSetDozeAmount(0f, 0f, "source1", StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logSetDozeAmount(0.1f, 0.1f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logSetDozeAmount(0.2f, 0.2f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.3f, 0.3f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.4f, 0.4f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.5f, 0.5f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logSetDozeAmount(1f, 1f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
    }

    @Test
    fun setDozeAmountWillIncludeFractionalUpdatesWhenStateChanges() {
        logger.logSetDozeAmount(0f, 0f, "source1", StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logSetDozeAmount(0.1f, 0.1f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logSetDozeAmount(0.2f, 0.2f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.3f, 0.3f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.4f, 0.4f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.5f, 0.5f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logSetDozeAmount(0.5f, 0.5f, "source1", StatusBarState.KEYGUARD, changed = false)
        verifyDidLog(1)
    }

    @Test
    fun setDozeAmountWillIncludeFractionalUpdatesWhenSourceChanges() {
        logger.logSetDozeAmount(0f, 0f, "source1", StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logSetDozeAmount(0.1f, 0.1f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logSetDozeAmount(0.2f, 0.2f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.3f, 0.3f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.4f, 0.4f, "source1", StatusBarState.SHADE, changed = true)
        logger.logSetDozeAmount(0.5f, 0.5f, "source1", StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logSetDozeAmount(0.5f, 0.5f, "source2", StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
    }

    class LogBufferCounter {
        val recentLogs = mutableListOf<Pair<String, LogLevel>>()
        val tracker =
            object : LogcatEchoTracker {
                override val logInBackgroundThread: Boolean = false
                override fun isBufferLoggable(bufferName: String, level: LogLevel): Boolean = false
                override fun isTagLoggable(tagName: String, level: LogLevel): Boolean {
                    recentLogs.add(tagName to level)
                    return true
                }
            }
        val logBuffer =
            LogBuffer(name = "test", maxSize = 1, logcatEchoTracker = tracker, systrace = false)

        fun verifyDidLog(times: Int) {
            assertThat(recentLogs).hasSize(times)
            recentLogs.clear()
        }
    }
}
