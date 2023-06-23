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

package com.android.systemui.statusbar.notification.logging

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.statusbar.notification.stack.StackStateLogger
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class StackStateLoggerTest : SysuiTestCase() {
    private val logBufferCounter = LogBufferCounter()
    private lateinit var logger: StackStateLogger

    @Before
    fun setup() {
        logger = StackStateLogger(logBufferCounter.logBuffer, logBufferCounter.logBuffer)
    }

    @Test
    fun groupChildRemovalEvent() {
        logger.groupChildRemovalEventProcessed(KEY)
        verifyDidLog(1)
        logger.groupChildRemovalAnimationEnded(KEY)
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
            Truth.assertThat(recentLogs).hasSize(times)
            recentLogs.clear()
        }
    }

    private fun verifyDidLog(times: Int) {
        logBufferCounter.verifyDidLog(times)
    }

    companion object {
        private val KEY = "PACKAGE_NAME"
    }
}
