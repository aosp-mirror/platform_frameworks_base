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
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test

@SmallTest
class TableLogBufferTest : SysuiTestCase() {
    private lateinit var underTest: TableLogBuffer

    private lateinit var systemClock: FakeSystemClock
    private lateinit var outputWriter: StringWriter

    @Before
    fun setup() {
        systemClock = FakeSystemClock()
        outputWriter = StringWriter()

        underTest = TableLogBuffer(MAX_SIZE, NAME, systemClock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun maxSizeZero_throwsException() {
        TableLogBuffer(maxSize = 0, "name", systemClock)
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
    fun logChange_rowInitializer_dumpsCorrectly() {
        systemClock.setCurrentTimeMillis(100L)

        underTest.logChange("") { row ->
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
        assertThat(dumpedString).doesNotContain("testString[2]")
        assertThat(dumpedString).contains("testString[3]")
        assertThat(dumpedString).contains("testString[${MAX_SIZE + 2}]")
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
