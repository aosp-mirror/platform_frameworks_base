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

package com.android.systemui.media.taptotransfer.receiver

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogcatEchoTracker
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@SmallTest
class MediaTttReceiverLoggerTest : SysuiTestCase() {

    private lateinit var buffer: LogBuffer
    private lateinit var logger: MediaTttReceiverLogger

    @Before
    fun setUp() {
        buffer =
            LogBufferFactory(DumpManager(), mock(LogcatEchoTracker::class.java))
                .create("buffer", 10)
        logger = MediaTttReceiverLogger(buffer)
    }

    @Test
    fun logStateChange_bufferHasDeviceTypeTagAndParamInfo() {
        val stateName = "test state name"
        val id = "test id"
        val packageName = "this.is.a.package"

        logger.logStateChange(stateName, id, packageName)

        val actualString = getStringFromBuffer()
        assertThat(actualString).contains(stateName)
        assertThat(actualString).contains(id)
        assertThat(actualString).contains(packageName)
    }

    @Test
    fun logStateChangeError_hasState() {
        logger.logStateChangeError(3456)

        val actualString = getStringFromBuffer()
        assertThat(actualString).contains("3456")
    }

    @Test
    fun logPackageNotFound_bufferHasPackageName() {
        val packageName = "this.is.a.package"

        logger.logPackageNotFound(packageName)

        val actualString = getStringFromBuffer()
        assertThat(actualString).contains(packageName)
    }

    private fun getStringFromBuffer(): String {
        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        return stringWriter.toString()
    }
}
