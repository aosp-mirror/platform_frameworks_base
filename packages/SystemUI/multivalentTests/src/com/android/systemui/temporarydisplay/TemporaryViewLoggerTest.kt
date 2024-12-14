/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.temporarydisplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.LogcatEchoTracker
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@SmallTest
@RunWith(AndroidJUnit4::class)
class TemporaryViewLoggerTest : SysuiTestCase() {
    private lateinit var buffer: LogBuffer
    private lateinit var logger: TemporaryViewLogger<TemporaryViewInfo>

    @Before
    fun setUp() {
        buffer =
            LogBufferFactory(DumpManager(), Mockito.mock(LogcatEchoTracker::class.java))
                .create("buffer", 10)
        logger = TemporaryViewLogger(buffer, TAG)
    }

    @Test
    fun logViewAddition_bufferHasLog() {
        val info =
            object : TemporaryViewInfo() {
                override val id: String = "test id"
                override val priority: ViewPriority = ViewPriority.CRITICAL
                override val windowTitle: String = "Test Window Title"
                override val wakeReason: String = "wake reason"
                override val instanceId: InstanceId = InstanceId.fakeInstanceId(0)
            }

        logger.logViewAddition(info)

        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        val actualString = stringWriter.toString()

        assertThat(actualString).contains(TAG)
        assertThat(actualString).contains("test id")
        assertThat(actualString).contains("Test Window Title")
    }

    @Test
    fun logViewRemoval_bufferHasTagAndReason() {
        val reason = "test reason"
        val deviceId = "test id"
        logger.logViewRemoval(deviceId, reason)

        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        val actualString = stringWriter.toString()

        assertThat(actualString).contains(TAG)
        assertThat(actualString).contains(reason)
        assertThat(actualString).contains(deviceId)
    }
}

private const val TAG = "TestTag"
