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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogcatEchoTracker
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.table.TableChange.Companion.IS_INITIAL_PREFIX
import com.android.systemui.log.table.TableChange.Companion.MAX_STRING_LENGTH
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class TableLogBufferTest : SysuiTestCase() {
    private lateinit var underTest: TableLogBuffer

    private lateinit var systemClock: FakeSystemClock
    private lateinit var outputWriter: StringWriter
    private lateinit var logcatEchoTracker: LogcatEchoTracker
    private lateinit var localLogcat: FakeLogProxy

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        localLogcat = FakeLogProxy()
        logcatEchoTracker = mock()
        systemClock = FakeSystemClock()
        outputWriter = StringWriter()

        underTest =
            TableLogBuffer(
                MAX_SIZE,
                NAME,
                systemClock,
                logcatEchoTracker,
                testDispatcher,
                testScope.backgroundScope,
                localLogcat = localLogcat,
            )
    }

    @Test(expected = IllegalArgumentException::class)
    fun maxSizeZero_throwsException() {
        TableLogBuffer(
            maxSize = 0,
            "name",
            systemClock,
            logcatEchoTracker,
            testDispatcher,
            testScope.backgroundScope,
            localLogcat = localLogcat,
        )
    }

    @Test
    fun dumpChanges_hasHeader() {
        val dumpedString = dumpChanges()

        assertThat(logLines(dumpedString)[0]).isEqualTo(HEADER_PREFIX + NAME)
    }

    @Test
    fun dumpChanges_hasVersion() {
        val dumpedString = dumpChanges()

        assertThat(logLines(dumpedString)[1]).isEqualTo("version $VERSION")
    }

    @Test
    fun dumpChanges_hasFooter() {
        val dumpedString = dumpChanges()

        assertThat(logLines(dumpedString).last()).isEqualTo(FOOTER_PREFIX + NAME)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dumpChanges_str_separatorNotAllowedInPrefix() {
        val next =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("columnName", "stringValue")
                }
            }
        underTest.logDiffs("some${SEPARATOR}thing", TestDiffable(), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dumpChanges_bool_separatorNotAllowedInPrefix() {
        val next =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("columnName", true)
                }
            }
        underTest.logDiffs("some${SEPARATOR}thing", TestDiffable(), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dumpChanges_int_separatorNotAllowedInPrefix() {
        val next =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("columnName", 567)
                }
            }
        underTest.logDiffs("some${SEPARATOR}thing", TestDiffable(), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dumpChanges_str_separatorNotAllowedInColumnName() {
        val next =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("column${SEPARATOR}Name", "stringValue")
                }
            }
        underTest.logDiffs("prefix", TestDiffable(), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dumpChanges_bool_separatorNotAllowedInColumnName() {
        val next =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("column${SEPARATOR}Name", true)
                }
            }
        underTest.logDiffs("prefix", TestDiffable(), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dumpChanges_int_separatorNotAllowedInColumnName() {
        val next =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("column${SEPARATOR}Name", 456)
                }
            }
        underTest.logDiffs("prefix", TestDiffable(), next)
    }

    @Test
    fun logChange_bool_dumpsCorrectly() {
        systemClock.setCurrentTimeMillis(4000L)

        underTest.logChange("prefix", "columnName", true)

        val dumpedString = dumpChanges()
        val expected =
            TABLE_LOG_DATE_FORMAT.format(4000L) +
                SEPARATOR +
                "prefix.columnName" +
                SEPARATOR +
                "true"
        assertThat(dumpedString).contains(expected)
    }

    @Test
    fun dumpChanges_strChange_logsFromNext() {
        systemClock.setCurrentTimeMillis(100L)

        val prevDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("stringValChange", "prevStringVal")
                }
            }
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("stringValChange", "newStringVal")
                }
            }

        underTest.logDiffs("prefix", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        val expected =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "prefix.stringValChange" +
                SEPARATOR +
                "newStringVal"
        assertThat(dumpedString).contains(expected)
        assertThat(dumpedString).doesNotContain("prevStringVal")
    }

    @Test
    fun dumpChanges_boolChange_logsFromNext() {
        systemClock.setCurrentTimeMillis(100L)

        val prevDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("booleanValChange", false)
                }
            }
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("booleanValChange", true)
                }
            }

        underTest.logDiffs("prefix", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        val expected =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "prefix.booleanValChange" +
                SEPARATOR +
                "true"
        assertThat(dumpedString).contains(expected)
        assertThat(dumpedString).doesNotContain("false")
    }

    @Test
    fun dumpChanges_intChange_logsFromNext() {
        systemClock.setCurrentTimeMillis(100L)

        val prevDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("intValChange", 12345)
                }
            }
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("intValChange", 67890)
                }
            }

        underTest.logDiffs("prefix", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        val expected =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "prefix.intValChange" +
                SEPARATOR +
                "67890"
        assertThat(dumpedString).contains(expected)
        assertThat(dumpedString).doesNotContain("12345")
    }

    @Test
    fun dumpChanges_noPrefix() {
        systemClock.setCurrentTimeMillis(100L)

        val prevDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("booleanValChange", false)
                }
            }
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("booleanValChange", true)
                }
            }

        // WHEN there's a blank prefix
        underTest.logDiffs("", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        // THEN the dump still works
        val expected =
            TABLE_LOG_DATE_FORMAT.format(100L) + SEPARATOR + "booleanValChange" + SEPARATOR + "true"
        assertThat(dumpedString).contains(expected)
    }

    @Test
    fun dumpChanges_multipleChangesForSameColumn_logs() {
        lateinit var valToDump: String

        val diffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("valChange", valToDump)
                }
            }

        systemClock.setCurrentTimeMillis(12000L)
        valToDump = "stateValue12"
        underTest.logDiffs(columnPrefix = "", diffable, diffable)

        systemClock.setCurrentTimeMillis(20000L)
        valToDump = "stateValue20"
        underTest.logDiffs(columnPrefix = "", diffable, diffable)

        systemClock.setCurrentTimeMillis(40000L)
        valToDump = "stateValue40"
        underTest.logDiffs(columnPrefix = "", diffable, diffable)

        systemClock.setCurrentTimeMillis(45000L)
        valToDump = "stateValue45"
        underTest.logDiffs(columnPrefix = "", diffable, diffable)

        val dumpedString = dumpChanges()

        val expected1 =
            TABLE_LOG_DATE_FORMAT.format(12000L) +
                SEPARATOR +
                "valChange" +
                SEPARATOR +
                "stateValue12"
        val expected2 =
            TABLE_LOG_DATE_FORMAT.format(20000L) +
                SEPARATOR +
                "valChange" +
                SEPARATOR +
                "stateValue20"
        val expected3 =
            TABLE_LOG_DATE_FORMAT.format(40000L) +
                SEPARATOR +
                "valChange" +
                SEPARATOR +
                "stateValue40"
        val expected4 =
            TABLE_LOG_DATE_FORMAT.format(45000L) +
                SEPARATOR +
                "valChange" +
                SEPARATOR +
                "stateValue45"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
        assertThat(dumpedString).contains(expected3)
        assertThat(dumpedString).contains(expected4)
    }

    @Test
    fun dumpChanges_multipleChangesAtOnce_logs() {
        systemClock.setCurrentTimeMillis(100L)

        val prevDiffable = object : TestDiffable() {}
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("status", "in progress")
                    row.logChange("connected", false)
                }
            }

        underTest.logDiffs(columnPrefix = "", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        val timestamp = TABLE_LOG_DATE_FORMAT.format(100L)
        val expected1 = timestamp + SEPARATOR + "status" + SEPARATOR + "in progress"
        val expected2 = timestamp + SEPARATOR + "connected" + SEPARATOR + "false"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
    }

    @Test
    fun logChange_rowInitializer_notIsInitial_dumpsCorrectly() {
        systemClock.setCurrentTimeMillis(100L)

        underTest.logChange(columnPrefix = "", isInitial = false) { row ->
            row.logChange("column1", "val1")
            row.logChange("column2", 2)
            row.logChange("column3", true)
        }

        val dumpedString = dumpChanges()

        val timestamp = TABLE_LOG_DATE_FORMAT.format(100L)
        val expected1 = timestamp + SEPARATOR + "column1" + SEPARATOR + "val1"
        val expected2 = timestamp + SEPARATOR + "column2" + SEPARATOR + "2"
        val expected3 = timestamp + SEPARATOR + "column3" + SEPARATOR + "true"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
        assertThat(dumpedString).contains(expected3)
    }

    @Test
    fun logChange_rowInitializer_isInitial_dumpsCorrectly() {
        systemClock.setCurrentTimeMillis(100L)

        underTest.logChange(columnPrefix = "", isInitial = true) { row ->
            row.logChange("column1", "val1")
            row.logChange("column2", 2)
            row.logChange("column3", true)
        }

        val dumpedString = dumpChanges()

        val timestamp = TABLE_LOG_DATE_FORMAT.format(100L)
        val expected1 = timestamp + SEPARATOR + "column1" + SEPARATOR + IS_INITIAL_PREFIX + "val1"
        val expected2 = timestamp + SEPARATOR + "column2" + SEPARATOR + IS_INITIAL_PREFIX + "2"
        val expected3 = timestamp + SEPARATOR + "column3" + SEPARATOR + IS_INITIAL_PREFIX + "true"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
        assertThat(dumpedString).contains(expected3)
    }

    @Test
    fun logChange_rowInitializer_isInitialThenNotInitial_dumpsCorrectly() {
        systemClock.setCurrentTimeMillis(100L)
        underTest.logChange(columnPrefix = "", isInitial = true) { row ->
            row.logChange("column1", "val1")
            row.logChange("column2", 2)
            row.logChange("column3", true)
        }

        systemClock.setCurrentTimeMillis(200L)
        underTest.logChange(columnPrefix = "", isInitial = false) { row ->
            row.logChange("column1", "val11")
            row.logChange("column2", 22)
            row.logChange("column3", false)
        }

        val dumpedString = dumpChanges()

        val timestamp = TABLE_LOG_DATE_FORMAT.format(100L)
        val expected1 = timestamp + SEPARATOR + "column1" + SEPARATOR + IS_INITIAL_PREFIX + "val1"
        val expected2 = timestamp + SEPARATOR + "column2" + SEPARATOR + IS_INITIAL_PREFIX + "2"
        val expected3 = timestamp + SEPARATOR + "column3" + SEPARATOR + IS_INITIAL_PREFIX + "true"
        val timestamp2 = TABLE_LOG_DATE_FORMAT.format(200L)
        val expected4 = timestamp2 + SEPARATOR + "column1" + SEPARATOR + "val11"
        val expected5 = timestamp2 + SEPARATOR + "column2" + SEPARATOR + "22"
        val expected6 = timestamp2 + SEPARATOR + "column3" + SEPARATOR + "false"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
        assertThat(dumpedString).contains(expected3)
        assertThat(dumpedString).contains(expected4)
        assertThat(dumpedString).contains(expected5)
        assertThat(dumpedString).contains(expected6)
    }

    @Test
    fun logDiffs_neverInitial() {
        systemClock.setCurrentTimeMillis(100L)

        val prevDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("stringValChange", "prevStringVal")
                }
            }
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("stringValChange", "newStringVal")
                }
            }

        underTest.logDiffs("prefix", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        assertThat(dumpedString).doesNotContain(IS_INITIAL_PREFIX)
    }

    @Test
    fun logChange_variousPrimitiveValues_isInitialAlwaysUpdated() {
        systemClock.setCurrentTimeMillis(100L)
        underTest.logChange(prefix = "", columnName = "first", value = "val1", isInitial = true)
        systemClock.setCurrentTimeMillis(200L)
        underTest.logChange(prefix = "", columnName = "second", value = "val2", isInitial = true)
        systemClock.setCurrentTimeMillis(300L)
        underTest.logChange(prefix = "", columnName = "first", value = 11, isInitial = false)
        systemClock.setCurrentTimeMillis(400L)
        underTest.logChange(prefix = "", columnName = "first", value = false, isInitial = false)
        systemClock.setCurrentTimeMillis(500L)
        underTest.logChange(prefix = "", columnName = "third", value = 33, isInitial = true)

        val dumpedString = dumpChanges()

        val expected1 =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "first" +
                SEPARATOR +
                IS_INITIAL_PREFIX +
                "val1"
        val expected2 =
            TABLE_LOG_DATE_FORMAT.format(200L) +
                SEPARATOR +
                "second" +
                SEPARATOR +
                IS_INITIAL_PREFIX +
                "val2"
        val expected3 = TABLE_LOG_DATE_FORMAT.format(300L) + SEPARATOR + "first" + SEPARATOR + "11"
        val expected4 =
            TABLE_LOG_DATE_FORMAT.format(400L) + SEPARATOR + "first" + SEPARATOR + "false"
        val expected5 =
            TABLE_LOG_DATE_FORMAT.format(500L) +
                SEPARATOR +
                "third" +
                SEPARATOR +
                IS_INITIAL_PREFIX +
                "33"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
        assertThat(dumpedString).contains(expected3)
        assertThat(dumpedString).contains(expected4)
        assertThat(dumpedString).contains(expected5)
    }

    @Test
    fun logChangeAndLogDiffs_bothLogged() {
        systemClock.setCurrentTimeMillis(100L)

        underTest.logChange("") { row ->
            row.logChange("column1", "val1")
            row.logChange("column2", 2)
            row.logChange("column3", true)
        }

        systemClock.setCurrentTimeMillis(200L)
        val prevDiffable = object : TestDiffable() {}
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("column1", "newVal1")
                    row.logChange("column2", 222)
                    row.logChange("column3", false)
                }
            }

        underTest.logDiffs(columnPrefix = "", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        val timestamp1 = TABLE_LOG_DATE_FORMAT.format(100L)
        val expected1 = timestamp1 + SEPARATOR + "column1" + SEPARATOR + "val1"
        val expected2 = timestamp1 + SEPARATOR + "column2" + SEPARATOR + "2"
        val expected3 = timestamp1 + SEPARATOR + "column3" + SEPARATOR + "true"
        val timestamp2 = TABLE_LOG_DATE_FORMAT.format(200L)
        val expected4 = timestamp2 + SEPARATOR + "column1" + SEPARATOR + "newVal1"
        val expected5 = timestamp2 + SEPARATOR + "column2" + SEPARATOR + "222"
        val expected6 = timestamp2 + SEPARATOR + "column3" + SEPARATOR + "false"
        assertThat(dumpedString).contains(expected1)
        assertThat(dumpedString).contains(expected2)
        assertThat(dumpedString).contains(expected3)
        assertThat(dumpedString).contains(expected4)
        assertThat(dumpedString).contains(expected5)
        assertThat(dumpedString).contains(expected6)
    }

    @Test
    fun dumpChanges_tooLongColumnPrefix_viaLogChange_truncated() {
        underTest.logChange(
            prefix = "P".repeat(MAX_STRING_LENGTH + 10),
            columnName = "name",
            value = true,
        )

        val dumpedString = dumpChanges()

        assertThat(dumpedString).contains("P".repeat(MAX_STRING_LENGTH))
        assertThat(dumpedString).doesNotContain("P".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun dumpChanges_tooLongColumnPrefix_viaLogDiffs_truncated() {
        val prevDiffable = object : TestDiffable() {}
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("status", "value")
                }
            }

        // WHEN the column prefix is too large
        underTest.logDiffs(
            columnPrefix = "P".repeat(MAX_STRING_LENGTH + 10),
            prevDiffable,
            nextDiffable,
        )

        val dumpedString = dumpChanges()

        // THEN it's truncated to the max length
        assertThat(dumpedString).contains("P".repeat(MAX_STRING_LENGTH))
        assertThat(dumpedString).doesNotContain("P".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun dumpChanges_tooLongColumnName_viaLogChange_truncated() {
        underTest.logChange(
            prefix = "prefix",
            columnName = "N".repeat(MAX_STRING_LENGTH + 10),
            value = 10,
        )

        val dumpedString = dumpChanges()

        assertThat(dumpedString).contains("N".repeat(MAX_STRING_LENGTH))
        assertThat(dumpedString).doesNotContain("N".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun dumpChanges_tooLongColumnName_viaLogDiffs_truncated() {
        val prevDiffable = object : TestDiffable() {}
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    // WHEN the column name is too large
                    row.logChange(columnName = "N".repeat(MAX_STRING_LENGTH + 10), "value")
                }
            }

        underTest.logDiffs(columnPrefix = "prefix", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        // THEN it's truncated to the max length
        assertThat(dumpedString).contains("N".repeat(MAX_STRING_LENGTH))
        assertThat(dumpedString).doesNotContain("N".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun dumpChanges_tooLongValue_viaLogChange_truncated() {
        underTest.logChange(
            prefix = "prefix",
            columnName = "name",
            value = "V".repeat(MAX_STRING_LENGTH + 10),
        )

        val dumpedString = dumpChanges()

        assertThat(dumpedString).contains("V".repeat(MAX_STRING_LENGTH))
        assertThat(dumpedString).doesNotContain("V".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun dumpChanges_tooLongValue_viaLogDiffs_truncated() {
        val prevDiffable = object : TestDiffable() {}
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    // WHEN the value is too large
                    row.logChange("columnName", value = "V".repeat(MAX_STRING_LENGTH + 10))
                }
            }

        underTest.logDiffs(columnPrefix = "prefix", prevDiffable, nextDiffable)

        val dumpedString = dumpChanges()

        // THEN it's truncated to the max length
        assertThat(dumpedString).contains("V".repeat(MAX_STRING_LENGTH))
        assertThat(dumpedString).doesNotContain("V".repeat(MAX_STRING_LENGTH + 1))
    }

    @Test
    fun dumpChanges_rotatesIfBufferIsFull() {
        lateinit var valToDump: String

        val prevDiffable = object : TestDiffable() {}
        val nextDiffable =
            object : TestDiffable() {
                override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {
                    row.logChange("status", valToDump)
                }
            }

        for (i in 0 until MAX_SIZE + 3) {
            valToDump = "testString[$i]"
            underTest.logDiffs(columnPrefix = "", prevDiffable, nextDiffable)
        }

        val dumpedString = dumpChanges()

        assertThat(dumpedString).doesNotContain("testString[0]")
        assertThat(dumpedString).doesNotContain("testString[1]")
        // The buffer should contain [MAX_SIZE + 1] entries since we also save the most recently
        // evicted value.
        assertThat(dumpedString).contains("testString[2]")
        assertThat(dumpedString).contains("testString[3]")
        assertThat(dumpedString).contains("testString[${MAX_SIZE + 2}]")
    }

    @Test
    fun columnEvicted_lastKnownColumnValueInDump() {
        systemClock.setCurrentTimeMillis(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvicted", value = "evictedValue")

        // Exactly fill the buffer so that "willBeEvicted" is evicted
        for (i in 0 until MAX_SIZE) {
            systemClock.advanceTime(100L)
            val dumpString = "fillString[$i]"
            underTest.logChange(prefix = "", columnName = "fillingColumn", value = dumpString)
        }

        val dumpedString = dumpChanges()

        // Expect that we'll have both the evicted column entry...
        val evictedColumnLog =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "willBeEvicted" +
                SEPARATOR +
                "evictedValue"
        assertThat(dumpedString).contains(evictedColumnLog)

        // ... *and* all of the fillingColumn entries.
        val firstFillingColumnLog =
            TABLE_LOG_DATE_FORMAT.format(200L) +
                SEPARATOR +
                "fillingColumn" +
                SEPARATOR +
                "fillString[0]"
        val lastFillingColumnLog =
            TABLE_LOG_DATE_FORMAT.format(1100L) +
                SEPARATOR +
                "fillingColumn" +
                SEPARATOR +
                "fillString[9]"
        assertThat(dumpedString).contains(firstFillingColumnLog)
        assertThat(dumpedString).contains(lastFillingColumnLog)
    }

    @Test
    fun multipleColumnsEvicted_allColumnsInDump() {
        systemClock.setCurrentTimeMillis(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvictedString", value = "evictedValue")
        systemClock.advanceTime(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvictedInt", value = 45)
        systemClock.advanceTime(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvictedBool", value = true)

        // Exactly fill the buffer so that all the above columns will be evicted
        for (i in 0 until MAX_SIZE) {
            systemClock.advanceTime(100L)
            val dumpString = "fillString[$i]"
            underTest.logChange(prefix = "", columnName = "fillingColumn", value = dumpString)
        }

        val dumpedString = dumpChanges()

        // Expect that we'll have all the evicted column entries...
        val evictedColumnLogString =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "willBeEvictedString" +
                SEPARATOR +
                "evictedValue"
        val evictedColumnLogInt =
            TABLE_LOG_DATE_FORMAT.format(200L) + SEPARATOR + "willBeEvictedInt" + SEPARATOR + "45"
        val evictedColumnLogBool =
            TABLE_LOG_DATE_FORMAT.format(300L) +
                SEPARATOR +
                "willBeEvictedBool" +
                SEPARATOR +
                "true"
        assertThat(dumpedString).contains(evictedColumnLogString)
        assertThat(dumpedString).contains(evictedColumnLogInt)
        assertThat(dumpedString).contains(evictedColumnLogBool)

        // ... *and* all of the fillingColumn entries.
        val firstFillingColumnLog =
            TABLE_LOG_DATE_FORMAT.format(400) +
                SEPARATOR +
                "fillingColumn" +
                SEPARATOR +
                "fillString[0]"
        val lastFillingColumnLog =
            TABLE_LOG_DATE_FORMAT.format(1300) +
                SEPARATOR +
                "fillingColumn" +
                SEPARATOR +
                "fillString[9]"
        assertThat(dumpedString).contains(firstFillingColumnLog)
        assertThat(dumpedString).contains(lastFillingColumnLog)
    }

    @Test
    fun multipleColumnsEvicted_differentPrefixSameName_allColumnsInDump() {
        systemClock.setCurrentTimeMillis(100L)
        underTest.logChange(prefix = "prefix1", columnName = "sameName", value = "value1")
        systemClock.advanceTime(100L)
        underTest.logChange(prefix = "prefix2", columnName = "sameName", value = "value2")
        systemClock.advanceTime(100L)
        underTest.logChange(prefix = "prefix3", columnName = "sameName", value = "value3")

        // Exactly fill the buffer so that all the above columns will be evicted
        for (i in 0 until MAX_SIZE) {
            systemClock.advanceTime(100L)
            val dumpString = "fillString[$i]"
            underTest.logChange(prefix = "", columnName = "fillingColumn", value = dumpString)
        }

        val dumpedString = dumpChanges()

        // Expect that we'll have all the evicted column entries
        val evictedColumn1 =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "prefix1.sameName" +
                SEPARATOR +
                "value1"
        val evictedColumn2 =
            TABLE_LOG_DATE_FORMAT.format(200L) +
                SEPARATOR +
                "prefix2.sameName" +
                SEPARATOR +
                "value2"
        val evictedColumn3 =
            TABLE_LOG_DATE_FORMAT.format(300L) +
                SEPARATOR +
                "prefix3.sameName" +
                SEPARATOR +
                "value3"
        assertThat(dumpedString).contains(evictedColumn1)
        assertThat(dumpedString).contains(evictedColumn2)
        assertThat(dumpedString).contains(evictedColumn3)
    }

    @Test
    fun multipleColumnsEvicted_dumpSortedByTimestamp() {
        systemClock.setCurrentTimeMillis(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvictedFirst", value = "evictedValue")
        systemClock.advanceTime(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvictedSecond", value = 45)
        systemClock.advanceTime(100L)
        underTest.logChange(prefix = "", columnName = "willBeEvictedThird", value = true)

        // Exactly fill the buffer with so that all the above columns will be evicted
        for (i in 0 until MAX_SIZE) {
            systemClock.advanceTime(100L)
            val dumpString = "fillString[$i]"
            underTest.logChange(prefix = "", columnName = "fillingColumn", value = dumpString)
        }

        val dumpedString = dumpChanges()

        // Expect that we'll have all the evicted column entries in timestamp order
        val firstEvictedLog =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "willBeEvictedFirst" +
                SEPARATOR +
                "evictedValue"
        val secondEvictedLog =
            TABLE_LOG_DATE_FORMAT.format(200L) +
                SEPARATOR +
                "willBeEvictedSecond" +
                SEPARATOR +
                "45"
        val thirdEvictedLog =
            TABLE_LOG_DATE_FORMAT.format(300L) +
                SEPARATOR +
                "willBeEvictedThird" +
                SEPARATOR +
                "true"
        assertThat(dumpedString).contains(firstEvictedLog)
        val stringAfterFirst = dumpedString.substringAfter(firstEvictedLog)
        assertThat(stringAfterFirst).contains(secondEvictedLog)
        val stringAfterSecond = stringAfterFirst.substringAfter(secondEvictedLog)
        assertThat(stringAfterSecond).contains(thirdEvictedLog)
    }

    @Test
    fun sameColumnEvictedMultipleTimes_onlyLastEvictionInDump() {
        systemClock.setCurrentTimeMillis(0L)

        for (i in 1 until 4) {
            systemClock.advanceTime(100L)
            val dumpString = "evicted[$i]"
            underTest.logChange(prefix = "", columnName = "evictedColumn", value = dumpString)
        }

        // Exactly fill the buffer so that all the entries for "evictedColumn" will be evicted.
        for (i in 0 until MAX_SIZE) {
            systemClock.advanceTime(100L)
            val dumpString = "fillString[$i]"
            underTest.logChange(prefix = "", columnName = "fillingColumn", value = dumpString)
        }

        val dumpedString = dumpChanges()

        // Expect that we only have the most recent evicted column entry
        val evictedColumnLog1 =
            TABLE_LOG_DATE_FORMAT.format(100L) +
                SEPARATOR +
                "evictedColumn" +
                SEPARATOR +
                "evicted[1]"
        val evictedColumnLog2 =
            TABLE_LOG_DATE_FORMAT.format(200L) +
                SEPARATOR +
                "evictedColumn" +
                SEPARATOR +
                "evicted[2]"
        val evictedColumnLog3 =
            TABLE_LOG_DATE_FORMAT.format(300L) +
                SEPARATOR +
                "evictedColumn" +
                SEPARATOR +
                "evicted[3]"
        assertThat(dumpedString).doesNotContain(evictedColumnLog1)
        assertThat(dumpedString).doesNotContain(evictedColumnLog2)
        assertThat(dumpedString).contains(evictedColumnLog3)
    }

    @Test
    fun logcat_bufferNotLoggable_tagNotLoggable_noEcho() {
        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), any())).thenReturn(false)
        whenever(logcatEchoTracker.isTagLoggable(eq("columnName"), any())).thenReturn(false)

        underTest.logChange("prefix", "columnName", true)

        assertThat(localLogcat.logs).isEmpty()
    }

    @Test
    fun logcat_bufferIsLoggable_tagNotLoggable_echoes() {
        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), any())).thenReturn(true)
        whenever(logcatEchoTracker.isTagLoggable(eq("columnName"), any())).thenReturn(false)

        underTest.logChange("prefix", "columnName", true)

        assertThat(localLogcat.logs).hasSize(1)
    }

    @Test
    fun logcat_bufferNotLoggable_tagIsLoggable_echoes() {
        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), any())).thenReturn(false)
        whenever(logcatEchoTracker.isTagLoggable(eq("columnName"), any())).thenReturn(true)

        underTest.logChange("prefix", "columnName", true)

        assertThat(localLogcat.logs).hasSize(1)
    }

    @Test
    fun logcat_echoesDebugLogs_debugDisabled_noEcho() {
        // Allow any log other than debug
        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), any())).thenAnswer { invocation ->
            (invocation.getArgument(1) as LogLevel) != LogLevel.DEBUG
        }

        underTest.logChange("prefix", "columnName", true)

        assertThat(localLogcat.logs).isEmpty()
    }

    @Test
    fun logcat_echoesDebugLogs_debugEnabled_echoes() {
        // Only allow debug logs
        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), eq(LogLevel.DEBUG))).thenReturn(true)

        underTest.logChange("prefix", "columnName", true)

        assertThat(localLogcat.logs).hasSize(1)
    }

    @Test
    fun logcat_bufferNotLoggable_tagIsLoggable_usesColNameForTagCheck() {
        systemClock.setCurrentTimeMillis(1000L)

        val nonLoggingTag = "nonLoggingColName"
        val loggingTag = "loggingColName"

        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), any())).thenReturn(false)
        whenever(logcatEchoTracker.isTagLoggable(eq(loggingTag), eq(LogLevel.DEBUG)))
            .thenReturn(true)
        whenever(logcatEchoTracker.isTagLoggable(eq(nonLoggingTag), eq(LogLevel.DEBUG)))
            .thenReturn(false)

        underTest.logChange("", nonLoggingTag, true)
        underTest.logChange("", loggingTag, true)

        assertThat(localLogcat.logs).hasSize(1)

        val timestamp = TABLE_LOG_DATE_FORMAT.format(1000L)
        val expectedMessage = "${timestamp}${SEPARATOR}${loggingTag}${SEPARATOR}true"
        val expectedLine = "D $NAME: $expectedMessage"

        assertThat(localLogcat.logs[0]).isEqualTo(expectedLine)
    }

    @Test
    fun logcat_bufferLoggable_multipleMessagesAreEchoed() {
        systemClock.setCurrentTimeMillis(1000L)
        whenever(logcatEchoTracker.isBufferLoggable(eq(NAME), any())).thenReturn(true)

        val col1 = "column1"
        val col2 = "column2"

        // Log a couple of columns that flip bits
        underTest.logChange("", col1, true)
        underTest.logChange("", col2, false)
        underTest.logChange("", col1, false)
        underTest.logChange("", col2, true)

        assertThat(localLogcat.logs).hasSize(4)

        val timestamp = TABLE_LOG_DATE_FORMAT.format(1000L)
        val msg1 = "${timestamp}${SEPARATOR}${col1}${SEPARATOR}true"
        val msg2 = "${timestamp}${SEPARATOR}${col2}${SEPARATOR}false"
        val msg3 = "${timestamp}${SEPARATOR}${col1}${SEPARATOR}false"
        val msg4 = "${timestamp}${SEPARATOR}${col2}${SEPARATOR}true"
        val expected = listOf(msg1, msg2, msg3, msg4).map { "D $NAME: $it" }

        // Logs use the same bg dispatcher for writing to logcat, they should be in order
        for ((msg, logLine) in expected zip localLogcat.logs) {
            assertThat(logLine).isEqualTo(msg)
        }
    }

    private fun dumpChanges(): String {
        underTest.dump(PrintWriter(outputWriter), arrayOf())
        return outputWriter.toString()
    }

    private fun logLines(string: String): List<String> {
        return string.split("\n").filter { it.isNotBlank() }
    }

    private open class TestDiffable : Diffable<TestDiffable> {
        override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {}
    }
}

private const val NAME = "TestTableBuffer"
private const val MAX_SIZE = 10

// Copying these here from [TableLogBuffer] so that we catch any accidental versioning change
private const val HEADER_PREFIX = "SystemUI StateChangeTableSection START: "
private const val FOOTER_PREFIX = "SystemUI StateChangeTableSection END: "
private const val SEPARATOR = "|" // TBD
private const val VERSION = "1"
