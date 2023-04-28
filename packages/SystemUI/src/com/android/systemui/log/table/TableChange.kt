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

import androidx.annotation.VisibleForTesting

/**
 * A object used with [TableLogBuffer] to store changes in variables over time. Is recyclable.
 *
 * Each message represents a change to exactly 1 type, specified by [DataType].
 *
 * @property isInitial see [TableLogBuffer.logChange(String, Boolean, (TableRowLogger) -> Unit].
 */
data class TableChange(
    var timestamp: Long = 0,
    private var columnPrefix: String = "",
    private var columnName: String = "",
    private var isInitial: Boolean = false,
    private var type: DataType = DataType.EMPTY,
    private var bool: Boolean = false,
    private var int: Int? = null,
    private var str: String? = null,
) {
    init {
        // Truncate any strings that were passed into the constructor. [reset] and [set] will take
        // care of the rest of the truncation.
        this.columnPrefix = columnPrefix.take(MAX_STRING_LENGTH)
        this.columnName = columnName.take(MAX_STRING_LENGTH)
        this.str = str?.take(MAX_STRING_LENGTH)
    }

    /** Resets to default values so that the object can be recycled. */
    fun reset(timestamp: Long, columnPrefix: String, columnName: String, isInitial: Boolean) {
        this.timestamp = timestamp
        this.columnPrefix = columnPrefix.take(MAX_STRING_LENGTH)
        this.columnName = columnName.take(MAX_STRING_LENGTH)
        this.isInitial = isInitial
        this.type = DataType.EMPTY
        this.bool = false
        this.int = 0
        this.str = null
    }

    /** Sets this to store a string change. */
    fun set(value: String?) {
        type = DataType.STRING
        str = value?.take(MAX_STRING_LENGTH)
    }

    /** Sets this to store a boolean change. */
    fun set(value: Boolean) {
        type = DataType.BOOLEAN
        bool = value
    }

    /** Sets this to store an int change. */
    fun set(value: Int?) {
        type = DataType.INT
        int = value
    }

    /** Updates this to store the same value as [change]. */
    fun updateTo(change: TableChange) {
        reset(change.timestamp, change.columnPrefix, change.columnName, change.isInitial)
        when (change.type) {
            DataType.STRING -> set(change.str)
            DataType.INT -> set(change.int)
            DataType.BOOLEAN -> set(change.bool)
            DataType.EMPTY -> {}
        }
    }

    /** Returns true if this object has a change. */
    fun hasData(): Boolean {
        return columnName.isNotBlank() && type != DataType.EMPTY
    }

    fun getName(): String {
        return if (columnPrefix.isNotBlank()) {
            "$columnPrefix.$columnName"
        } else {
            columnName
        }
    }

    fun getColumnName() = columnName

    fun getVal(): String {
        val value =
            when (type) {
                DataType.EMPTY -> null
                DataType.STRING -> str
                DataType.INT -> int
                DataType.BOOLEAN -> bool
            }.toString()
        return "${if (isInitial) IS_INITIAL_PREFIX else ""}$value"
    }

    enum class DataType {
        STRING,
        BOOLEAN,
        INT,
        EMPTY,
    }

    companion object {
        @VisibleForTesting const val IS_INITIAL_PREFIX = "**"
        // Don't allow any strings larger than this length so that we have a hard upper limit on the
        // size of the data stored by the buffer.
        @VisibleForTesting const val MAX_STRING_LENGTH = 500
    }
}
