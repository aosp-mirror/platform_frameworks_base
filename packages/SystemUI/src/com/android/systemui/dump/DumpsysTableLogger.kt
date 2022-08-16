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

import java.io.PrintWriter

/**
 * Utility for logging nice table data to be parsed (and pretty printed) in bugreports. The general
 * idea here is to feed your nice, table-like data to this class, which embeds the schema and rows
 * into the dumpsys, wrapped in a known start and stop tags. Later, one can build a simple parser
 * and pretty-print this data in a table
 *
 * Note: Something should be said here about silently eating errors by filtering out malformed
 * lines. Because this class is expected to be utilized only during a dumpsys, it doesn't feel
 * most correct to throw an exception here (since an exception can often be the reason that this
 * class is created). Because of this, [DumpsysTableLogger] will simply filter out invalid lines
 * based solely on line length. This behavior might need to be revisited in the future.
 *
 * USAGE:
 * Assuming we have some data that would be logged to dumpsys like so:
 *
 * ```
 *      1: field1=val1, field2=val2..., fieldN=valN
 *      //...
 *      M: field1M=val1M, ..., fieldNM
 * ```
 *
 * You can break the `field<n>` values out into a columns spec:
 * ```
 *      val cols = [field1, field2,...,fieldN]
 * ```
 * And then take all of the historical data lines (1 through M), and break them out into their own
 * lists:
 * ```
 *      val rows = [
 *          [field10, field20,..., fieldN0],
 *          //...
 *          [field1M, field2M,..., fieldNM]
 *      ]
 * ```
 *
 * Lastly, create a bugreport-unique section name, and use the table logger to write the data to
 * dumpsys:
 * ```
 *      val logger = DumpsysTableLogger(uniqueName, cols, rows)
 *      logger.printTableData(pw)
 * ```
 *
 * The expected output in the dumpsys would be:
 * ```
 *      SystemUI TableSection START: <SectionName>
 *      version 1
 *      col1|col2|...|colN
 *      field10|field20|...|fieldN0
 *      //...
 *      field1M|field2M|...|fieldNM
 *      SystemUI TableSection END: <SectionName>
 * ```
 *
 *  @param sectionName A name for the table data section. Should be unique in the bugreport
 *  @param columns Definition for the columns of the table. This should be the same length as all
 *      data rows
 *  @param rows List of rows to be displayed in the table
 */
class DumpsysTableLogger(
    private val sectionName: String,
    private val columns: List<String>,
    private val rows: List<Row>
) {

    fun printTableData(pw: PrintWriter) {
        printSectionStart(pw)
        printSchema(pw)
        printData(pw)
        printSectionEnd(pw)
    }

    private fun printSectionStart(pw: PrintWriter) {
        pw.println(HEADER_PREFIX + sectionName)
        pw.println("version $VERSION")
    }

    private fun printSectionEnd(pw: PrintWriter) {
        pw.println(FOOTER_PREFIX + sectionName)
    }

    private fun printSchema(pw: PrintWriter) {
        pw.println(columns.joinToString(separator = SEPARATOR))
    }

    private fun printData(pw: PrintWriter) {
        val count = columns.size
        rows
            .filter { it.size == count }
            .forEach { dataLine ->
                pw.println(dataLine.joinToString(separator = SEPARATOR))
        }
    }
}

typealias Row = List<String>

/**
 * DO NOT CHANGE! (but if you must...)
 *  1. Update the version number
 *  2. Update any consumers to parse the new version
 */
private const val HEADER_PREFIX = "SystemUI TableSection START: "
private const val FOOTER_PREFIX = "SystemUI TableSection END: "
private const val SEPARATOR = "|" // TBD
private const val VERSION = "1"