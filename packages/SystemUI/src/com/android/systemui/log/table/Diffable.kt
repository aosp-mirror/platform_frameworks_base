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

import com.android.systemui.util.kotlin.pairwiseBy
import kotlinx.coroutines.flow.Flow

/**
 * An interface that enables logging the difference between values in table format.
 *
 * Many objects that we want to log are data-y objects with a collection of fields. When logging
 * these objects, we want to log each field separately. This allows ABT (Android Bug Tool) to easily
 * highlight changes in individual fields.
 *
 * See [TableLogBuffer].
 */
interface Diffable<T> {
    /**
     * Finds the differences between [prevVal] and this object and logs those diffs to [row].
     *
     * Each implementer should determine which individual fields have changed between [prevVal] and
     * this object, and only log the fields that have actually changed. This helps save buffer
     * space.
     *
     * For example, if:
     * - prevVal = Object(val1=100, val2=200, val3=300)
     * - this = Object(val1=100, val2=200, val3=333)
     *
     * Then only the val3 change should be logged.
     */
    fun logDiffs(prevVal: T, row: TableRowLogger)

    /**
     * Logs all the relevant fields of this object to [row].
     *
     * As opposed to [logDiffs], this method should log *all* fields.
     *
     * Implementation is optional. This method will only be used with [logDiffsForTable] in order to
     * fully log the initial value of the flow.
     */
    fun logFull(row: TableRowLogger) {}
}

/**
 * Each time the flow is updated with a new value, logs the differences between the previous value
 * and the new value to the given [tableLogBuffer].
 *
 * The new value's [Diffable.logDiffs] method will be used to log the differences to the table.
 *
 * @param columnPrefix a prefix that will be applied to every column name that gets logged.
 */
fun <T : Diffable<T>> Flow<T>.logDiffsForTable(
    tableLogBuffer: TableLogBuffer,
    columnPrefix: String,
    initialValue: T,
): Flow<T> {
    // Fully log the initial value to the table.
    val getInitialValue = {
        tableLogBuffer.logChange(columnPrefix) { row -> initialValue.logFull(row) }
        initialValue
    }
    return this.pairwiseBy(getInitialValue) { prevVal: T, newVal: T ->
        tableLogBuffer.logDiffs(columnPrefix, prevVal, newVal)
        newVal
    }
}

/**
 * Each time the boolean flow is updated with a new value that's different from the previous value,
 * logs the new value to the given [tableLogBuffer].
 */
fun Flow<Boolean>.logDiffsForTable(
    tableLogBuffer: TableLogBuffer,
    columnPrefix: String,
    columnName: String,
    initialValue: Boolean,
): Flow<Boolean> {
    val initialValueFun = {
        tableLogBuffer.logChange(columnPrefix, columnName, initialValue)
        initialValue
    }
    return this.pairwiseBy(initialValueFun) { prevVal, newVal: Boolean ->
        if (prevVal != newVal) {
            tableLogBuffer.logChange(columnPrefix, columnName, newVal)
        }
        newVal
    }
}
/**
 * Each time the Int flow is updated with a new value that's different from the previous value, logs
 * the new value to the given [tableLogBuffer].
 */
fun Flow<Int>.logDiffsForTable(
    tableLogBuffer: TableLogBuffer,
    columnPrefix: String,
    columnName: String,
    initialValue: Int,
): Flow<Int> {
    val initialValueFun = {
        tableLogBuffer.logChange(columnPrefix, columnName, initialValue)
        initialValue
    }
    return this.pairwiseBy(initialValueFun) { prevVal, newVal: Int ->
        if (prevVal != newVal) {
            tableLogBuffer.logChange(columnPrefix, columnName, newVal)
        }
        newVal
    }
}

/**
 * Each time the String? flow is updated with a new value that's different from the previous value,
 * logs the new value to the given [tableLogBuffer].
 */
fun Flow<String?>.logDiffsForTable(
    tableLogBuffer: TableLogBuffer,
    columnPrefix: String,
    columnName: String,
    initialValue: String?,
): Flow<String?> {
    val initialValueFun = {
        tableLogBuffer.logChange(columnPrefix, columnName, initialValue)
        initialValue
    }
    return this.pairwiseBy(initialValueFun) { prevVal, newVal: String? ->
        if (prevVal != newVal) {
            tableLogBuffer.logChange(columnPrefix, columnName, newVal)
        }
        newVal
    }
}
