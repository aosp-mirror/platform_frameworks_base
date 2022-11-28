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

        assertThat(dumpedString).contains("prefix")
        assertThat(dumpedString).contains("stringValChange")
        assertThat(dumpedString).contains("newStringVal")
        assertThat(dumpedString).doesNotContain("prevStringVal")
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(100L))
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

        assertThat(dumpedString).contains("prefix")
        assertThat(dumpedString).contains("booleanValChange")
        assertThat(dumpedString).contains("true")
        assertThat(dumpedString).doesNotContain("false")
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(100L))
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

        assertThat(dumpedString).contains("prefix")
        assertThat(dumpedString).contains("intValChange")
        assertThat(dumpedString).contains("67890")
        assertThat(dumpedString).doesNotContain("12345")
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(100L))
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
        assertThat(dumpedString).contains("booleanValChange")
        assertThat(dumpedString).contains("true")
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(100L))
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

        assertThat(dumpedString).contains("valChange")
        assertThat(dumpedString).contains("stateValue12")
        assertThat(dumpedString).contains("stateValue20")
        assertThat(dumpedString).contains("stateValue40")
        assertThat(dumpedString).contains("stateValue45")
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(12000L))
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(20000L))
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(40000L))
        assertThat(dumpedString).contains(TABLE_LOG_DATE_FORMAT.format(45000L))
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

        assertThat(dumpedString).contains("status")
        assertThat(dumpedString).contains("in progress")
        assertThat(dumpedString).contains("connected")
        assertThat(dumpedString).contains("false")
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
        underTest.dumpChanges(PrintWriter(outputWriter))
        return outputWriter.toString()
    }

    private abstract class TestDiffable : Diffable<TestDiffable> {
        override fun logDiffs(prevVal: TestDiffable, row: TableRowLogger) {}
    }
}

private const val NAME = "TestTableBuffer"
private const val MAX_SIZE = 10
