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

package com.android.systemui.log.echo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.core.LogLevel.VERBOSE
import com.android.systemui.log.core.LogLevel.WARNING
import com.android.systemui.log.echo.EchoOverrideType.BUFFER
import com.android.systemui.log.echo.EchoOverrideType.TAG
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.settings.FakeGlobalSettings
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class LogcatEchoTrackerDebugTest : SysuiTestCase() {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val globalSettings = FakeGlobalSettings()

    @Mock private lateinit var commandRegistry: CommandRegistry

    private lateinit var echoTracker: LogcatEchoTrackerDebug

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        echoTracker =
            LogcatEchoTrackerDebug(
                testScope,
                dispatcher,
                globalSettings,
                commandRegistry,
            )
    }

    @Test
    fun testUnsetLogLevelIsWarning() {
        assertTrue(echoTracker.isBufferLoggable("foo", WARNING))
        assertFalse(echoTracker.isBufferLoggable("foo", INFO))

        assertTrue(echoTracker.isTagLoggable("foo", WARNING))
        assertFalse(echoTracker.isTagLoggable("foo", INFO))
    }

    @Test
    fun testLoadEmptySetting() =
        testScope.runTest {
            startAndLoadOverrides()

            assertFalse(echoTracker.isBufferLoggable("foo", INFO))
            assertFalse(echoTracker.isTagLoggable("foo", INFO))
        }

    @Test
    fun testLoadOverridesFromSettings() =
        testScope.runTest {
            setOverrides(
                LogcatEchoOverride(BUFFER, "buffer_1", DEBUG),
                LogcatEchoOverride(TAG, "tag_1", INFO),
            )
            startAndLoadOverrides()

            assertTrue(echoTracker.isBufferLoggable("buffer_1", DEBUG))
            assertFalse(echoTracker.isBufferLoggable("buffer_1", VERBOSE))

            assertTrue(echoTracker.isTagLoggable("tag_1", INFO))
            assertFalse(echoTracker.isTagLoggable("tag_1", DEBUG))
        }

    @Test
    fun testSetOverride() =
        testScope.runTest {
            setOverrides(
                LogcatEchoOverride(BUFFER, "buffer_0", VERBOSE),
            )
            startAndLoadOverrides()

            echoTracker.setEchoLevel(BUFFER, "buffer_1", DEBUG)
            echoTracker.setEchoLevel(TAG, "tag_1", ERROR)

            advanceUntilIdle()

            assertTrue(echoTracker.isBufferLoggable("buffer_0", VERBOSE))

            assertTrue(echoTracker.isBufferLoggable("buffer_1", DEBUG))
            assertFalse(echoTracker.isBufferLoggable("buffer_1", VERBOSE))

            assertTrue(echoTracker.isTagLoggable("tag_1", ERROR))
            assertFalse(echoTracker.isTagLoggable("tag_1", WARNING))
        }

    @Test
    fun testSetOverrideNotAppliedUntilCoroutinesRun() =
        testScope.runTest {
            startAndLoadOverrides()
            echoTracker.setEchoLevel(BUFFER, "buffer_1", DEBUG)

            assertTrue(echoTracker.isBufferLoggable("buffer_1", WARNING))
            assertFalse(echoTracker.isBufferLoggable("buffer_1", INFO))
        }

    @Test
    fun testSetOverrideStoresInSettings() =
        testScope.runTest {
            setOverrides(
                LogcatEchoOverride(BUFFER, "buffer_1", DEBUG),
            )
            startAndLoadOverrides()

            echoTracker.setEchoLevel(BUFFER, "buffer_2", INFO)
            echoTracker.setEchoLevel(TAG, "tag_1", ERROR)

            advanceUntilIdle()

            val expected =
                setOf(
                    LogcatEchoOverride(BUFFER, "buffer_1", DEBUG),
                    LogcatEchoOverride(BUFFER, "buffer_2", INFO),
                    LogcatEchoOverride(TAG, "tag_1", ERROR),
                )

            assertEquals(expected, loadStoredOverrideSet())
        }

    @Test
    fun testClearAllOverrides() =
        testScope.runTest {
            setOverrides(
                LogcatEchoOverride(BUFFER, "buffer_1", DEBUG),
                LogcatEchoOverride(TAG, "tag_1", INFO),
            )
            startAndLoadOverrides()

            echoTracker.setEchoLevel(BUFFER, "buffer_2", VERBOSE)

            advanceUntilIdle()

            echoTracker.clearAllOverrides()

            runCurrent()

            assertFalse(echoTracker.isBufferLoggable("buffer_1", DEBUG))
            assertFalse(echoTracker.isTagLoggable("tag_1", INFO))
            assertFalse(echoTracker.isBufferLoggable("buffer_2", VERBOSE))

            advanceUntilIdle()

            assertEquals(emptySet(), loadStoredOverrideSet())
        }

    private fun setOverrides(vararg overrides: LogcatEchoOverride) {
        val encoded = LogcatEchoSettingFormat().stringifyOverrides(overrides.asList())
        globalSettings.putString(OVERRIDE_SETTING_PATH, encoded)
        echoTracker.start()
    }

    private fun loadStoredOverrideSet(): Set<LogcatEchoOverride> {
        val storedSetting = assertNotNull(globalSettings.getString(OVERRIDE_SETTING_PATH))
        return LogcatEchoSettingFormat().parseOverrides(storedSetting).toSet()
    }

    private fun TestScope.startAndLoadOverrides() {
        echoTracker.start()
        advanceUntilIdle()
    }

    companion object {
        private const val OVERRIDE_SETTING_PATH = "systemui/logbuffer_echo_overrides"
    }
}
