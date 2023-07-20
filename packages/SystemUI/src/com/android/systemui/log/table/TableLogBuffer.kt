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

import com.android.systemui.Dumpable
import com.android.systemui.plugins.util.RingBuffer
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/**
 * A logger that logs changes in table format.
 *
 * Some parts of System UI maintain a lot of pieces of state at once.
 * [com.android.systemui.plugins.log.LogBuffer] allows us to easily log change events:
 *
 * - 10-10 10:10:10.456: state2 updated to newVal2
 * - 10-10 10:11:00.000: stateN updated to StateN(val1=true, val2=1)
 * - 10-10 10:11:02.123: stateN updated to StateN(val1=true, val2=2)
 * - 10-10 10:11:05.123: state1 updated to newVal1
 * - 10-10 10:11:06.000: stateN updated to StateN(val1=false, val2=3)
 *
 * However, it can sometimes be more useful to view the state changes in table format:
 *
 * - timestamp--------- | state1- | state2- | ... | stateN.val1 | stateN.val2
 * - -------------------------------------------------------------------------
 * - 10-10 10:10:10.123 | val1--- | val2--- | ... | false------ | 0-----------
 * - 10-10 10:10:10.456 | val1--- | newVal2 | ... | false------ | 0-----------
 * - 10-10 10:11:00.000 | val1--- | newVal2 | ... | true------- | 1-----------
 * - 10-10 10:11:02.123 | val1--- | newVal2 | ... | true------- | 2-----------
 * - 10-10 10:11:05.123 | newVal1 | newVal2 | ... | true------- | 2-----------
 * - 10-10 10:11:06.000 | newVal1 | newVal2 | ... | false------ | 3-----------
 *
 * This class enables easy logging of the state changes in both change event format and table
 * format.
 *
 * This class also enables easy logging of states that are a collection of fields. For example,
 * stateN in the above example consists of two fields -- val1 and val2. It's useful to put each
 * field into its own column so that ABT (Android Bug Tool) can easily highlight changes to
 * individual fields.
 *
 * How it works:
 *
 * 1) Create an instance of this buffer via [TableLogBufferFactory].
 *
 * 2) For any states being logged, implement [Diffable]. Implementing [Diffable] allows the state to
 * only log the fields that have *changed* since the previous update, instead of always logging all
 * fields.
 *
 * 3) Each time a change in a state happens, call [logDiffs]. If your state is emitted using a
 * [Flow], you should use the [logDiffsForTable] extension function to automatically log diffs any
 * time your flow emits a new value.
 *
 * When a dump occurs, there will be two dumps:
 *
 * 1) The change events under the dumpable name "$name-changes".
 *
 * 2) This class will coalesce all the diffs into a table format and log them under the dumpable
 * name "$name-table".
 *
 * @param maxSize the maximum size of the buffer. Must be > 0.
 */
class TableLogBuffer(
    maxSize: Int,
    private val name: String,
    private val systemClock: SystemClock,
) : Dumpable {
    init {
        if (maxSize <= 0) {
            throw IllegalArgumentException("maxSize must be > 0")
        }
    }

    private val buffer = RingBuffer(maxSize) { TableChange() }

    // A [TableRowLogger] object, re-used each time [logDiffs] is called.
    // (Re-used to avoid object allocation.)
    private val tempRow = TableRowLoggerImpl(0, columnPrefix = "", this)

    /**
     * Log the differences between [prevVal] and [newVal].
     *
     * The [newVal] object's method [Diffable.logDiffs] will be used to fetch the diffs.
     *
     * @param columnPrefix a prefix that will be applied to every column name that gets logged. This
     * ensures that all the columns related to the same state object will be grouped together in the
     * table.
     *
     * @throws IllegalArgumentException if [columnPrefix] or column name contain "|". "|" is used as
     * the separator token for parsing, so it can't be present in any part of the column name.
     */
    @Synchronized
    fun <T : Diffable<T>> logDiffs(columnPrefix: String, prevVal: T, newVal: T) {
        val row = tempRow
        row.timestamp = systemClock.currentTimeMillis()
        row.columnPrefix = columnPrefix
        newVal.logDiffs(prevVal, row)
    }

    /**
     * Logs change(s) to the buffer using [rowInitializer].
     *
     * @param rowInitializer a function that will be called immediately to store relevant data on
     * the row.
     */
    @Synchronized
    fun logChange(columnPrefix: String, rowInitializer: (TableRowLogger) -> Unit) {
        val row = tempRow
        row.timestamp = systemClock.currentTimeMillis()
        row.columnPrefix = columnPrefix
        rowInitializer(row)
    }

    /** Logs a String? change. */
    fun logChange(prefix: String, columnName: String, value: String?) {
        logChange(systemClock.currentTimeMillis(), prefix, columnName, value)
    }

    /** Logs a boolean change. */
    fun logChange(prefix: String, columnName: String, value: Boolean) {
        logChange(systemClock.currentTimeMillis(), prefix, columnName, value)
    }

    /** Logs a Int change. */
    fun logChange(prefix: String, columnName: String, value: Int) {
        logChange(systemClock.currentTimeMillis(), prefix, columnName, value)
    }

    // Keep these individual [logChange] methods private (don't let clients give us their own
    // timestamps.)

    private fun logChange(timestamp: Long, prefix: String, columnName: String, value: String?) {
        val change = obtain(timestamp, prefix, columnName)
        change.set(value)
    }

    private fun logChange(timestamp: Long, prefix: String, columnName: String, value: Boolean) {
        val change = obtain(timestamp, prefix, columnName)
        change.set(value)
    }

    private fun logChange(timestamp: Long, prefix: String, columnName: String, value: Int) {
        val change = obtain(timestamp, prefix, columnName)
        change.set(value)
    }

    // TODO(b/259454430): Add additional change types here.

    @Synchronized
    private fun obtain(timestamp: Long, prefix: String, columnName: String): TableChange {
        verifyValidName(prefix, columnName)
        val tableChange = buffer.advance()
        tableChange.reset(timestamp, prefix, columnName)
        return tableChange
    }

    private fun verifyValidName(prefix: String, columnName: String) {
        if (prefix.contains(SEPARATOR)) {
            throw IllegalArgumentException("columnPrefix cannot contain $SEPARATOR but was $prefix")
        }
        if (columnName.contains(SEPARATOR)) {
            throw IllegalArgumentException(
                "columnName cannot contain $SEPARATOR but was $columnName"
            )
        }
    }

    @Synchronized
    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(HEADER_PREFIX + name)
        pw.println("version $VERSION")
        for (i in 0 until buffer.size) {
            buffer[i].dump(pw)
        }
        pw.println(FOOTER_PREFIX + name)
    }

    /** Dumps an individual [TableChange]. */
    private fun TableChange.dump(pw: PrintWriter) {
        if (!this.hasData()) {
            return
        }
        val formattedTimestamp = TABLE_LOG_DATE_FORMAT.format(timestamp)
        pw.print(formattedTimestamp)
        pw.print(SEPARATOR)
        pw.print(this.getName())
        pw.print(SEPARATOR)
        pw.print(this.getVal())
        pw.println()
    }

    /**
     * A private implementation of [TableRowLogger].
     *
     * Used so that external clients can't modify [timestamp].
     */
    private class TableRowLoggerImpl(
        var timestamp: Long,
        var columnPrefix: String,
        val tableLogBuffer: TableLogBuffer,
    ) : TableRowLogger {
        /** Logs a change to a string value. */
        override fun logChange(columnName: String, value: String?) {
            tableLogBuffer.logChange(timestamp, columnPrefix, columnName, value)
        }

        /** Logs a change to a boolean value. */
        override fun logChange(columnName: String, value: Boolean) {
            tableLogBuffer.logChange(timestamp, columnPrefix, columnName, value)
        }

        /** Logs a change to an int value. */
        override fun logChange(columnName: String, value: Int) {
            tableLogBuffer.logChange(timestamp, columnPrefix, columnName, value)
        }
    }
}

val TABLE_LOG_DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

private const val HEADER_PREFIX = "SystemUI StateChangeTableSection START: "
private const val FOOTER_PREFIX = "SystemUI StateChangeTableSection END: "
private const val SEPARATOR = "|"
private const val VERSION = "1"
