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

package com.android.systemui.demomode

import android.content.Intent
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.demomode.DemoMode.ACTION_DEMO
import com.android.systemui.demomode.DemoMode.COMMAND_STATUS
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class DemoModeControllerTest : SysuiTestCase() {
    private lateinit var underTest: DemoModeController

    @Mock private lateinit var dumpManager: DumpManager

    private val globalSettings = FakeSettings()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()

        MockitoAnnotations.initMocks(this)

        globalSettings.putInt(DemoModeController.DEMO_MODE_ALLOWED, 1)
        globalSettings.putInt(DemoModeController.DEMO_MODE_ON, 1)

        underTest =
            DemoModeController(
                context = context,
                dumpManager = dumpManager,
                globalSettings = globalSettings,
                broadcastDispatcher = fakeBroadcastDispatcher
            )

        underTest.initialize()
    }

    @Test
    fun `demo command flow - returns args`() =
        testScope.runTest {
            var latest: Bundle? = null
            val flow = underTest.demoFlowForCommand(TEST_COMMAND)
            val job = launch { flow.collect { latest = it } }

            sendDemoCommand(args = mapOf("key1" to "val1"))
            assertThat(latest!!.getString("key1")).isEqualTo("val1")

            sendDemoCommand(args = mapOf("key2" to "val2"))
            assertThat(latest!!.getString("key2")).isEqualTo("val2")

            job.cancel()
        }

    private fun sendDemoCommand(command: String? = TEST_COMMAND, args: Map<String, String>) {
        val intent = Intent(ACTION_DEMO)
        intent.putExtra("command", command)
        args.forEach { arg -> intent.putExtra(arg.key, arg.value) }

        fakeBroadcastDispatcher.registeredReceivers.forEach { it.onReceive(context, intent) }
    }

    companion object {
        // Use a valid command until we properly fake out everything
        const val TEST_COMMAND = COMMAND_STATUS
    }
}
