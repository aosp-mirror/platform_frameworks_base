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

package com.android.systemui.log.table

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableChange.Companion.IS_INITIAL_PREFIX
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LogDiffsForTableTest : SysuiTestCase() {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var systemClock: FakeSystemClock
    private lateinit var tableLogBuffer: TableLogBuffer

    @Before
    fun setUp() {
        systemClock = FakeSystemClock()
        tableLogBuffer =
            TableLogBuffer(
                MAX_SIZE,
                BUFFER_NAME,
                systemClock,
                mock(),
                testDispatcher,
                testScope.backgroundScope,
            )
    }

    // ---- Flow<Boolean> tests ----

    @Test
    fun boolean_doesNotLogWhenNotCollected() {
        val flow = flowOf(true, true, false)

        flow.logDiffsForTable(
            tableLogBuffer,
            COLUMN_PREFIX,
            COLUMN_NAME,
            initialValue = false,
        )

        val logs = dumpLog()
        assertThat(logs).doesNotContain(COLUMN_PREFIX)
        assertThat(logs).doesNotContain(COLUMN_NAME)
        assertThat(logs).doesNotContain("false")
    }

    @Test
    fun boolean_logsInitialWhenCollected() =
        testScope.runTest {
            val flow = flowOf(true, true, false)

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = false,
                )

            systemClock.setCurrentTimeMillis(3000L)
            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "false"
                )

            job.cancel()
        }

    @Test
    fun boolean_logsUpdates() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (bool in listOf(true, false, true)) {
                    systemClock.advanceTime(100L)
                    emit(bool)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = false,
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "false"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "true"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "false"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "true"
                )

            job.cancel()
        }

    @Test
    fun boolean_doesNotLogIfSameValue() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (bool in listOf(true, true, false, false, true)) {
                    systemClock.advanceTime(100L)
                    emit(bool)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = true,
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            // Input flow: true@100, true@200, true@300, false@400, false@500, true@600
            // Output log: true@100, --------, --------, false@400, ---------, true@600
            val expected1 =
                TABLE_LOG_DATE_FORMAT.format(100L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    IS_INITIAL_PREFIX +
                    "true"
            val expected4 =
                TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "false"
            val expected6 =
                TABLE_LOG_DATE_FORMAT.format(600L) + SEPARATOR + FULL_NAME + SEPARATOR + "true"
            assertThat(logs).contains(expected1)
            assertThat(logs).contains(expected4)
            assertThat(logs).contains(expected6)

            val unexpected2 =
                TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "true"
            val unexpected3 =
                TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "true"
            val unexpected5 =
                TABLE_LOG_DATE_FORMAT.format(500L) + SEPARATOR + FULL_NAME + SEPARATOR + "false"
            assertThat(logs).doesNotContain(unexpected2)
            assertThat(logs).doesNotContain(unexpected3)
            assertThat(logs).doesNotContain(unexpected5)

            job.cancel()
        }

    @Test
    fun boolean_worksForStateFlows() =
        testScope.runTest {
            val flow = MutableStateFlow(false)

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = false,
                )

            systemClock.setCurrentTimeMillis(50L)
            val job = launch { flowWithLogging.collect() }
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "false"
                )

            systemClock.setCurrentTimeMillis(100L)
            flow.emit(true)
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) + SEPARATOR + FULL_NAME + SEPARATOR + "true"
                )

            systemClock.setCurrentTimeMillis(200L)
            flow.emit(false)
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "false"
                )

            // Doesn't log duplicates
            systemClock.setCurrentTimeMillis(300L)
            flow.emit(false)
            assertThat(dumpLog())
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "false"
                )

            job.cancel()
        }

    // ---- Flow<Int> tests ----

    @Test
    fun int_doesNotLogWhenNotCollected() {
        val flow = flowOf(5, 6, 7)

        flow.logDiffsForTable(
            tableLogBuffer,
            COLUMN_PREFIX,
            COLUMN_NAME,
            initialValue = 1234,
        )

        val logs = dumpLog()
        assertThat(logs).doesNotContain(COLUMN_PREFIX)
        assertThat(logs).doesNotContain(COLUMN_NAME)
        assertThat(logs).doesNotContain("1234")
    }

    @Test
    fun int_logsInitialWhenCollected() =
        testScope.runTest {
            val flow = flowOf(5, 6, 7)

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = 1234,
                )

            systemClock.setCurrentTimeMillis(3000L)
            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "1234"
                )

            job.cancel()
        }

    @Test
    fun intNullable_logsNull() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (int in listOf(null, 6, null, 8)) {
                    systemClock.advanceTime(100L)
                    emit(int)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = 1234,
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "1234"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "null"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "6"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "null"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(500L) + SEPARATOR + FULL_NAME + SEPARATOR + "8"
                )

            job.cancel()
        }

    @Test
    fun int_logsUpdates() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (int in listOf(2, 3, 4)) {
                    systemClock.advanceTime(100L)
                    emit(int)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = 1,
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "2"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "3"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "4"
                )

            job.cancel()
        }

    @Test
    fun int_doesNotLogIfSameValue() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (bool in listOf(2, 3, 3, 3, 2, 6, 6)) {
                    systemClock.advanceTime(100L)
                    emit(bool)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = 1,
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            // Input flow: 1@100, 2@200, 3@300, 3@400, 3@500, 2@600, 6@700, 6@800
            // Output log: 1@100, 2@200, 3@300, -----, -----, 2@600, 6@700, -----
            val expected1 =
                TABLE_LOG_DATE_FORMAT.format(100L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    IS_INITIAL_PREFIX +
                    "1"
            val expected2 =
                TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "2"
            val expected3 =
                TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "3"
            val expected6 =
                TABLE_LOG_DATE_FORMAT.format(600L) + SEPARATOR + FULL_NAME + SEPARATOR + "2"
            val expected7 =
                TABLE_LOG_DATE_FORMAT.format(700L) + SEPARATOR + FULL_NAME + SEPARATOR + "6"
            assertThat(logs).contains(expected1)
            assertThat(logs).contains(expected2)
            assertThat(logs).contains(expected3)
            assertThat(logs).contains(expected6)
            assertThat(logs).contains(expected7)

            val unexpected4 =
                TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "3"
            val unexpected5 =
                TABLE_LOG_DATE_FORMAT.format(500L) + SEPARATOR + FULL_NAME + SEPARATOR + "3"
            val unexpected8 =
                TABLE_LOG_DATE_FORMAT.format(800L) + SEPARATOR + FULL_NAME + SEPARATOR + "6"
            assertThat(logs).doesNotContain(unexpected4)
            assertThat(logs).doesNotContain(unexpected5)
            assertThat(logs).doesNotContain(unexpected8)
            job.cancel()
        }

    @Test
    fun int_worksForStateFlows() =
        testScope.runTest {
            val flow = MutableStateFlow(1111)

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = 1111,
                )

            systemClock.setCurrentTimeMillis(50L)
            val job = launch { flowWithLogging.collect() }
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "1111"
                )

            systemClock.setCurrentTimeMillis(100L)
            flow.emit(2222)
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) + SEPARATOR + FULL_NAME + SEPARATOR + "2222"
                )

            systemClock.setCurrentTimeMillis(200L)
            flow.emit(3333)
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "3333"
                )

            // Doesn't log duplicates
            systemClock.setCurrentTimeMillis(300L)
            flow.emit(3333)
            assertThat(dumpLog())
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "3333"
                )

            job.cancel()
        }

    // ---- Flow<String> tests ----

    @Test
    fun string_doesNotLogWhenNotCollected() {
        val flow = flowOf("val5", "val6", "val7")

        flow.logDiffsForTable(
            tableLogBuffer,
            COLUMN_PREFIX,
            COLUMN_NAME,
            initialValue = "val1234",
        )

        val logs = dumpLog()
        assertThat(logs).doesNotContain(COLUMN_PREFIX)
        assertThat(logs).doesNotContain(COLUMN_NAME)
        assertThat(logs).doesNotContain("val1234")
    }

    @Test
    fun string_logsInitialWhenCollected() =
        testScope.runTest {
            val flow = flowOf("val5", "val6", "val7")

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = "val1234",
                )

            systemClock.setCurrentTimeMillis(3000L)
            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "val1234"
                )

            job.cancel()
        }

    @Test
    fun string_logsUpdates() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (int in listOf("val2", "val3", "val4")) {
                    systemClock.advanceTime(100L)
                    emit(int)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = "val1",
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "val1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "val2"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "val3"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "val4"
                )

            job.cancel()
        }

    @Test
    fun string_logsNull() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (int in listOf(null, "something", null)) {
                    systemClock.advanceTime(100L)
                    emit(int)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = "start",
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "start"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "null"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        "something"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "null"
                )

            job.cancel()
        }

    @Test
    fun string_doesNotLogIfSameValue() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (bool in listOf("start", "new", "new", "newer", "newest", "newest")) {
                    systemClock.advanceTime(100L)
                    emit(bool)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = "start",
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            // Input flow: start@100, start@200, new@300, new@400, newer@500, newest@600, newest@700
            // Output log: start@100, ---------, new@300, -------, newer@500, newest@600, ----------
            val expected1 =
                TABLE_LOG_DATE_FORMAT.format(100L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    IS_INITIAL_PREFIX +
                    "start"
            val expected3 =
                TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + FULL_NAME + SEPARATOR + "new"
            val expected5 =
                TABLE_LOG_DATE_FORMAT.format(500L) + SEPARATOR + FULL_NAME + SEPARATOR + "newer"
            val expected6 =
                TABLE_LOG_DATE_FORMAT.format(600L) + SEPARATOR + FULL_NAME + SEPARATOR + "newest"
            assertThat(logs).contains(expected1)
            assertThat(logs).contains(expected3)
            assertThat(logs).contains(expected5)
            assertThat(logs).contains(expected6)

            val unexpected2 =
                TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + FULL_NAME + SEPARATOR + "start"
            val unexpected4 =
                TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + FULL_NAME + SEPARATOR + "new"
            val unexpected7 =
                TABLE_LOG_DATE_FORMAT.format(700L) + SEPARATOR + FULL_NAME + SEPARATOR + "newest"
            assertThat(logs).doesNotContain(unexpected2)
            assertThat(logs).doesNotContain(unexpected4)
            assertThat(logs).doesNotContain(unexpected7)

            job.cancel()
        }

    @Test
    fun string_worksForStateFlows() =
        testScope.runTest {
            val flow = MutableStateFlow("initial")

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = "initial",
                )

            systemClock.setCurrentTimeMillis(50L)
            val job = launch { flowWithLogging.collect() }
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "initial"
                )

            systemClock.setCurrentTimeMillis(100L)
            flow.emit("nextVal")
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        "nextVal"
                )

            systemClock.setCurrentTimeMillis(200L)
            flow.emit("nextNextVal")
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        "nextNextVal"
                )

            // Doesn't log duplicates
            systemClock.setCurrentTimeMillis(300L)
            flow.emit("nextNextVal")
            assertThat(dumpLog())
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        "nextNextVal"
                )

            job.cancel()
        }

    // ---- Flow<Diffable> tests ----

    @Test
    fun diffable_doesNotLogWhenNotCollected() {
        val flow =
            flowOf(
                TestDiffable(1, "1", true),
                TestDiffable(2, "2", false),
            )

        val initial = TestDiffable(0, "0", false)
        flow.logDiffsForTable(
            tableLogBuffer,
            COLUMN_PREFIX,
            initial,
        )

        val logs = dumpLog()
        assertThat(logs).doesNotContain(COLUMN_PREFIX)
        assertThat(logs).doesNotContain(TestDiffable.COL_FULL)
        assertThat(logs).doesNotContain(TestDiffable.COL_INT)
        assertThat(logs).doesNotContain(TestDiffable.COL_STRING)
        assertThat(logs).doesNotContain(TestDiffable.COL_BOOLEAN)
    }

    @Test
    fun diffable_logsInitialWhenCollected_usingLogFull() =
        testScope.runTest {
            val flow =
                flowOf(
                    TestDiffable(1, "1", true),
                    TestDiffable(2, "2", false),
                )

            val initial = TestDiffable(1234, "string1234", false)
            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    initial,
                )

            systemClock.setCurrentTimeMillis(3000L)
            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_FULL +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "true"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "1234"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "string1234"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "false"
                )
            job.cancel()
        }

    @Test
    fun diffable_logsUpdates_usingLogDiffs() =
        testScope.runTest {
            val initialValue = TestDiffable(0, "string0", false)
            val diffables =
                listOf(
                    TestDiffable(1, "string1", true),
                    TestDiffable(2, "string1", true),
                    TestDiffable(2, "string2", false),
                )

            systemClock.setCurrentTimeMillis(100L)
            val flow = flow {
                for (diffable in diffables) {
                    systemClock.advanceTime(100L)
                    emit(diffable)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    initialValue,
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()

            // Initial -> first: everything different
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_FULL +
                        SEPARATOR +
                        "false"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        "1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        "string1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        "true"
                )

            // First -> second: int different
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_FULL +
                        SEPARATOR +
                        "false"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        "2"
                )
            assertThat(logs)
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        "string1"
                )
            assertThat(logs)
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        "true"
                )

            // Second -> third: string & boolean different
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_FULL +
                        SEPARATOR +
                        "false"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        "string2"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        "false"
                )
            assertThat(logs)
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(400L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        "2"
                )

            job.cancel()
        }

    @Test
    fun diffable_worksForStateFlows() =
        testScope.runTest {
            val initialValue = TestDiffable(0, "string0", false)
            val flow = MutableStateFlow(initialValue)
            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    initialValue,
                )

            systemClock.setCurrentTimeMillis(50L)
            val job = launch { flowWithLogging.collect() }

            var logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "0"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "string0"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        "false"
                )

            systemClock.setCurrentTimeMillis(100L)
            flow.emit(TestDiffable(1, "string1", true))

            logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        "1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        "string1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        "true"
                )

            // Doesn't log duplicates
            systemClock.setCurrentTimeMillis(200L)
            flow.emit(TestDiffable(1, "newString", true))

            logs = dumpLog()
            assertThat(logs)
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_INT +
                        SEPARATOR +
                        "1"
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_STRING +
                        SEPARATOR +
                        "newString"
                )
            assertThat(logs)
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        COLUMN_PREFIX +
                        "." +
                        TestDiffable.COL_BOOLEAN +
                        SEPARATOR +
                        "true"
                )

            job.cancel()
        }

    // ---- Flow<List<T>> tests ----

    @Test
    fun list_doesNotLogWhenNotCollected() {
        val flow = flowOf(listOf(5), listOf(6), listOf(7))

        flow.logDiffsForTable(
            tableLogBuffer,
            COLUMN_PREFIX,
            COLUMN_NAME,
            initialValue = listOf(1234),
        )

        val logs = dumpLog()
        assertThat(logs).doesNotContain(COLUMN_PREFIX)
        assertThat(logs).doesNotContain(COLUMN_NAME)
        assertThat(logs).doesNotContain("1234")
    }

    @Test
    fun list_logsInitialWhenCollected() =
        testScope.runTest {
            val flow = flowOf(listOf(5), listOf(6), listOf(7))

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = listOf(1234),
                )

            systemClock.setCurrentTimeMillis(3000L)
            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(3000L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        listOf(1234).toString()
                )

            job.cancel()
        }

    @Test
    fun list_logsUpdates() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)

            val listItems =
                listOf(listOf("val1", "val2"), listOf("val3"), listOf("val4", "val5", "val6"))
            val flow = flow {
                for (list in listItems) {
                    systemClock.advanceTime(100L)
                    emit(list)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = listOf("val0", "val00"),
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        listOf("val0", "val00").toString()
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        listOf("val1", "val2").toString()
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        listOf("val3").toString()
                )
            assertThat(logs)
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(400L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        listOf("val4", "val5", "val6").toString()
                )

            job.cancel()
        }

    @Test
    fun list_doesNotLogIfSameValue() =
        testScope.runTest {
            systemClock.setCurrentTimeMillis(100L)

            val listItems =
                listOf(
                    listOf("val0", "val00"),
                    listOf("val1"),
                    listOf("val1"),
                    listOf("val1", "val2"),
                )
            val flow = flow {
                for (bool in listItems) {
                    systemClock.advanceTime(100L)
                    emit(bool)
                }
            }

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = listOf("val0", "val00"),
                )

            val job = launch { flowWithLogging.collect() }

            val logs = dumpLog()

            val expected1 =
                TABLE_LOG_DATE_FORMAT.format(100L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    IS_INITIAL_PREFIX +
                    listOf("val0", "val00").toString()
            val expected3 =
                TABLE_LOG_DATE_FORMAT.format(300L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    listOf("val1").toString()
            val expected5 =
                TABLE_LOG_DATE_FORMAT.format(500L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    listOf("val1", "val2").toString()
            assertThat(logs).contains(expected1)
            assertThat(logs).contains(expected3)
            assertThat(logs).contains(expected5)

            val unexpected2 =
                TABLE_LOG_DATE_FORMAT.format(200L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    listOf("val0", "val00")
            val unexpected4 =
                TABLE_LOG_DATE_FORMAT.format(400L) +
                    SEPARATOR +
                    FULL_NAME +
                    SEPARATOR +
                    listOf("val1")
            assertThat(logs).doesNotContain(unexpected2)
            assertThat(logs).doesNotContain(unexpected4)
            job.cancel()
        }

    @Test
    fun list_worksForStateFlows() =
        testScope.runTest {
            val flow = MutableStateFlow(listOf(1111))

            val flowWithLogging =
                flow.logDiffsForTable(
                    tableLogBuffer,
                    COLUMN_PREFIX,
                    COLUMN_NAME,
                    initialValue = listOf(1111),
                )

            systemClock.setCurrentTimeMillis(50L)
            val job = launch { flowWithLogging.collect() }
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(50L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        IS_INITIAL_PREFIX +
                        listOf(1111).toString()
                )

            systemClock.setCurrentTimeMillis(100L)
            flow.emit(listOf(2222, 3333))
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(100L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        listOf(2222, 3333).toString()
                )

            systemClock.setCurrentTimeMillis(200L)
            flow.emit(listOf(3333, 4444))
            assertThat(dumpLog())
                .contains(
                    TABLE_LOG_DATE_FORMAT.format(200L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        listOf(3333, 4444).toString()
                )

            // Doesn't log duplicates
            systemClock.setCurrentTimeMillis(300L)
            flow.emit(listOf(3333, 4444))
            assertThat(dumpLog())
                .doesNotContain(
                    TABLE_LOG_DATE_FORMAT.format(300L) +
                        SEPARATOR +
                        FULL_NAME +
                        SEPARATOR +
                        listOf(3333, 4444).toString()
                )

            job.cancel()
        }

    private fun dumpLog(): String {
        val outputWriter = StringWriter()
        tableLogBuffer.dump(PrintWriter(outputWriter), arrayOf())
        return outputWriter.toString()
    }

    class TestDiffable(
        private val testInt: Int,
        private val testString: String,
        private val testBoolean: Boolean,
    ) : Diffable<TestDiffable> {
        override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
            row.logChange(COL_FULL, false)

            if (testInt != prevVal.testInt) {
                row.logChange(COL_INT, testInt)
            }
            if (testString != prevVal.testString) {
                row.logChange(COL_STRING, testString)
            }
            if (testBoolean != prevVal.testBoolean) {
                row.logChange(COL_BOOLEAN, testBoolean)
            }
        }

        override fun logFull(row: TableRowLogger) {
            row.logChange(COL_FULL, true)
            row.logChange(COL_INT, testInt)
            row.logChange(COL_STRING, testString)
            row.logChange(COL_BOOLEAN, testBoolean)
        }

        companion object {
            const val COL_INT = "intColumn"
            const val COL_STRING = "stringColumn"
            const val COL_BOOLEAN = "booleanColumn"
            const val COL_FULL = "loggedFullColumn"
        }
    }

    private companion object {
        const val MAX_SIZE = 50
        const val BUFFER_NAME = "LogDiffsForTableTest"
        const val COLUMN_PREFIX = "columnPrefix"
        const val COLUMN_NAME = "columnName"
        const val FULL_NAME = "$COLUMN_PREFIX.$COLUMN_NAME"
        private const val SEPARATOR = "|"
    }
}
