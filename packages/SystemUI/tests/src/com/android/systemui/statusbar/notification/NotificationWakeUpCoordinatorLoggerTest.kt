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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.StatusBarState
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
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
    fun updateVisibilityThrottleFractionalUpdates() {
        logger.logSetVisibilityAmount(0f)
        verifyDidLog(1)
        logger.logSetVisibilityAmount(0.1f)
        verifyDidLog(1)
        logger.logSetVisibilityAmount(0.2f)
        logger.logSetVisibilityAmount(0.3f)
        logger.logSetVisibilityAmount(0.4f)
        logger.logSetVisibilityAmount(0.5f)
        verifyDidLog(0)
        logger.logSetVisibilityAmount(1f)
        verifyDidLog(1)
    }

    @Test
    fun updateHideAmountThrottleFractionalOrRepeatedUpdates() {
        logger.logSetHideAmount(0f)
        verifyDidLog(1)
        logger.logSetHideAmount(0f)
        logger.logSetHideAmount(0f)
        verifyDidLog(0)
        logger.logSetHideAmount(0.1f)
        verifyDidLog(1)
        logger.logSetHideAmount(0.2f)
        logger.logSetHideAmount(0.3f)
        logger.logSetHideAmount(0.4f)
        logger.logSetHideAmount(0.5f)
        logger.logSetHideAmount(0.5f)
        logger.logSetHideAmount(0.5f)
        verifyDidLog(0)
        logger.logSetHideAmount(1f)
        verifyDidLog(1)
        logger.logSetHideAmount(1f)
        logger.logSetHideAmount(1f)
        verifyDidLog(0)
    }

    @Test
    fun updateDozeAmountWillThrottleFractionalInputUpdates() {
        logger.logUpdateDozeAmount(0f, 0f, null, 0f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.1f, 0f, null, 0.1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.2f, 0f, null, 0.2f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.3f, 0f, null, 0.3f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.4f, 0f, null, 0.4f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.5f, 0f, null, 0.5f, StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(1f, 0f, null, 1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
    }

    @Test
    fun updateDozeAmountWillThrottleFractionalDelayUpdates() {
        logger.logUpdateDozeAmount(0f, 0f, null, 0f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0f, 0.1f, null, 0.1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0f, 0.2f, null, 0.2f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0f, 0.3f, null, 0.3f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0f, 0.4f, null, 0.4f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0f, 0.5f, null, 0.5f, StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(0f, 1f, null, 1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
    }

    @Test
    fun updateDozeAmountWillIncludeFractionalUpdatesWhenOtherInputChangesFractionality() {
        logger.logUpdateDozeAmount(0.0f, 1.0f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.1f, 1.0f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.2f, 1.0f, 1f, 1f, StatusBarState.SHADE, changed = false)
        logger.logUpdateDozeAmount(0.3f, 1.0f, 1f, 1f, StatusBarState.SHADE, changed = false)
        logger.logUpdateDozeAmount(0.4f, 1.0f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(0.5f, 0.9f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.6f, 0.8f, 1f, 1f, StatusBarState.SHADE, changed = false)
        logger.logUpdateDozeAmount(0.8f, 0.6f, 1f, 1f, StatusBarState.SHADE, changed = false)
        logger.logUpdateDozeAmount(0.9f, 0.5f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(1.0f, 0.4f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(1.0f, 0.3f, 1f, 1f, StatusBarState.SHADE, changed = false)
        logger.logUpdateDozeAmount(1.0f, 0.2f, 1f, 1f, StatusBarState.SHADE, changed = false)
        logger.logUpdateDozeAmount(1.0f, 0.1f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(1.0f, 0.0f, 1f, 1f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
    }

    @Test
    fun updateDozeAmountWillIncludeFractionalUpdatesWhenStateChanges() {
        logger.logUpdateDozeAmount(0f, 0f, null, 0f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.1f, 0f, null, 0.1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.2f, 0f, null, 0.2f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.3f, 0f, null, 0.3f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.4f, 0f, null, 0.4f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.5f, 0f, null, 0.5f, StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(0.5f, 0f, null, 0.5f, StatusBarState.KEYGUARD, changed = false)
        verifyDidLog(1)
    }

    @Test
    fun updateDozeAmountWillIncludeFractionalUpdatesWhenHardOverrideChanges() {
        logger.logUpdateDozeAmount(0f, 0f, null, 0f, StatusBarState.SHADE, changed = false)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.1f, 0f, null, 0.1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.2f, 0f, null, 0.2f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.3f, 0f, null, 0.3f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.4f, 0f, null, 0.4f, StatusBarState.SHADE, changed = true)
        logger.logUpdateDozeAmount(0.5f, 0f, null, 0.5f, StatusBarState.SHADE, changed = true)
        verifyDidLog(0)
        logger.logUpdateDozeAmount(0.5f, 0f, 1f, 1f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.5f, 0f, 0f, 0f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
        logger.logUpdateDozeAmount(0.5f, 0f, null, 0.5f, StatusBarState.SHADE, changed = true)
        verifyDidLog(1)
    }

    class LogBufferCounter {
        val recentLogs = mutableListOf<Pair<String, LogLevel>>()
        val tracker =
            object : LogcatEchoTracker {
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
