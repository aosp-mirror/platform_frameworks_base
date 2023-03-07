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

package com.android.systemui.statusbar.pipeline.mobile.data

import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.plugins.log.LogcatEchoTracker
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

@SmallTest
class MobileInputLoggerTest : SysuiTestCase() {
    private val buffer =
        LogBufferFactory(DumpManager(), mock(LogcatEchoTracker::class.java)).create("buffer", 10)
    private val logger = MobileInputLogger(buffer)

    @Test
    fun testLogNetworkCapsChange_bufferHasInfo() {
        logger.logOnCapabilitiesChanged(NET_1, NET_1_CAPS, isDefaultNetworkCallback = true)

        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        val actualString = stringWriter.toString()

        val expectedNetId = NET_1_ID.toString()
        val expectedCaps = NET_1_CAPS.toString()

        assertThat(actualString).contains("onDefaultCapabilitiesChanged")
        assertThat(actualString).contains(expectedNetId)
        assertThat(actualString).contains(expectedCaps)
    }

    @Test
    fun testLogOnLost_bufferHasNetIdOfLostNetwork() {
        logger.logOnLost(NET_1, isDefaultNetworkCallback = false)

        val stringWriter = StringWriter()
        buffer.dump(PrintWriter(stringWriter), tailLength = 0)
        val actualString = stringWriter.toString()

        val expectedNetId = NET_1_ID.toString()

        assertThat(actualString).contains("onLost")
        assertThat(actualString).contains(expectedNetId)
    }

    companion object {
        private const val NET_1_ID = 100
        private val NET_1 =
            com.android.systemui.util.mockito.mock<Network>().also {
                Mockito.`when`(it.getNetId()).thenReturn(NET_1_ID)
            }
        private val NET_1_CAPS =
            NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
    }
}
