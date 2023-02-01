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

package com.android.systemui.dump

import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

import java.io.PrintWriter
import java.io.StringWriter

@SmallTest
class DumpsysTableLoggerTest : SysuiTestCase() {
    private val logger = DumpsysTableLogger(
            TEST_SECTION_NAME,
            TEST_COLUMNS,
            TEST_DATA_VALID)

    private val stringWriter = StringWriter()
    private val printWriter = PrintWriter(stringWriter)

    @Before
    fun setup() {
    }

    @Test
    fun testTableLogger_header() {
        logger.printTableData(printWriter)
        val lines = logLines(stringWriter)

        val line1 = lines[0]

        assertEquals("table logger header is incorrect",
                HEADER_PREFIX + TEST_SECTION_NAME, line1)
    }

    @Test
    fun testTableLogger_version() {
        logger.printTableData(printWriter)
        val lines = logLines(stringWriter)

        val line2 = lines[1]

        assertEquals("version probably shouldn't have changed",
        "version $VERSION", line2)
    }

    @Test
    fun testTableLogger_footer() {
        logger.printTableData(printWriter)
        val lines = logLines(stringWriter)

        val footer = lines.last()
        android.util.Log.d("evanevan", footer)
        android.util.Log.d("evanevan", lines.toString())

        assertEquals("table logger footer is incorrect",
                FOOTER_PREFIX + TEST_SECTION_NAME, footer)
    }

    @Test
    fun testTableLogger_data_length() {
        logger.printTableData(printWriter)
        val lines = logLines(stringWriter)

        // Header is 2 lines long, plus a line for the column defs so data is lines[3..last()-1]
        val data = lines.subList(3, lines.size - 1)
        assertEquals(TEST_DATA_LENGTH, data.size)
    }

    @Test
    fun testTableLogger_data_columns() {
        logger.printTableData(printWriter)
        val lines = logLines(stringWriter)

        // Header is always 2 lines long so data is lines[2..last()-1]
        val data = lines.subList(3, lines.size - 1)

        data.forEach { dataLine ->
            assertEquals(TEST_COLUMNS.size, dataLine.split(SEPARATOR).size)
        }
    }

    @Test
    fun testInvalidLinesAreFiltered() {
        // GIVEN an invalid data row, by virtue of having an extra field
        val invalidLine = List(TEST_COLUMNS.size) { col ->
            "data${col}X"
        } + "INVALID COLUMN"
        val invalidData = TEST_DATA_VALID.toMutableList().also {
            it.add(invalidLine)
        }

        // WHEN the table logger is created and asked to print the table
        val tableLogger = DumpsysTableLogger(
                TEST_SECTION_NAME,
                TEST_COLUMNS,
                invalidData)

        tableLogger.printTableData(printWriter)

        // THEN the invalid line is filtered out
        val invalidString = invalidLine.joinToString(separator = SEPARATOR)
        val logString = stringWriter.toString()

        assertThat(logString).doesNotContain(invalidString)
    }

    private fun logLines(sw: StringWriter): List<String> {
        return sw.toString().split("\n").filter { it.isNotBlank() }
    }
}

// Copying these here from [DumpsysTableLogger] so that we catch any accidental versioning change
private const val HEADER_PREFIX = "SystemUI TableSection START: "
private const val FOOTER_PREFIX = "SystemUI TableSection END: "
private const val SEPARATOR = "|" // TBD
private const val VERSION = "1"

const val TEST_SECTION_NAME = "TestTableSection"
const val TEST_DATA_LENGTH = 5
val TEST_COLUMNS = arrayListOf("col1", "col2", "col3")
val TEST_DATA_VALID = List(TEST_DATA_LENGTH) { row ->
    List(TEST_COLUMNS.size) { col ->
        "data$col$row"
    }
}