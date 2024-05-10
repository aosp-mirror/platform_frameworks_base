/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.app.tracing

import android.os.Handler
import android.os.Looper
import android.os.Trace.TRACE_TAG_APP
import android.testing.AndroidTestingRunner
import android.util.Log
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TraceUtilsTest : SysuiTestCase() {

    companion object {
        private const val TAG = "TraceUtilsTest"
        private const val TEST_FAIL_TIMEOUT = 5000L

        // A string that is 128 characters long
        private const val SECTION_NAME_THATS_TOO_LONG =
            "123456789_123456789_123456789_123456789_123456789_123456789_123456789_123456789_" +
                "123456789_123456789_123456789_123456789_12345678"
    }

    @Before
    fun setUp() {
        // Enable tracing via atrace in order to see the expected IllegalArgumentException. Trace
        // sections won't run if tracing is disabled.
        uiDevice.executeShellCommand("atrace --async_start -a com.android.*")
    }

    @After
    fun tearDown() {
        uiDevice.executeShellCommand("atrace --async_stop")
    }

    @Test
    fun testLongTraceSection_throws_whenUsingPublicAPI() {
        // Expects: "java.lang.IllegalArgumentException: sectionName is too long"
        assertThrows(IllegalArgumentException::class.java) {
            android.os.Trace.beginSection(SECTION_NAME_THATS_TOO_LONG)
        }
    }

    @Test
    fun testLongTraceSection_doesNotThrow_whenUsingPrivateAPI() {
        android.os.Trace.traceBegin(TRACE_TAG_APP, SECTION_NAME_THATS_TOO_LONG)
    }

    @Test
    @Ignore("b/267482189 - Enable once androidx.tracing >= 1.2.0-beta04")
    fun testLongTraceSection_doesNotThrow_whenUsingAndroidX() {
        androidx.tracing.Trace.beginSection(SECTION_NAME_THATS_TOO_LONG)
    }

    @Test
    fun testLongTraceSection_doesNotThrow_whenUsingHelper() {
        traceSection(SECTION_NAME_THATS_TOO_LONG) {
            Log.v(TAG, "com.android.app.tracing.traceSection() block.")
        }
    }

    @Test
    fun testLongTraceSection_doesNotThrow_whenUsedAsTraceNameSupplier() {
        Handler(Looper.getMainLooper())
            .runWithScissors(
                TraceUtils.namedRunnable(SECTION_NAME_THATS_TOO_LONG) {
                    Log.v(TAG, "TraceUtils.namedRunnable() block.")
                },
                TEST_FAIL_TIMEOUT
            )
    }

    @Test
    fun testLongTraceSection_doesNotThrow_whenUsingTraceRunnable() {
        TraceUtils.traceRunnable(SECTION_NAME_THATS_TOO_LONG) {
            Log.v(TAG, "TraceUtils.traceRunnable() block.")
        }
    }
}
