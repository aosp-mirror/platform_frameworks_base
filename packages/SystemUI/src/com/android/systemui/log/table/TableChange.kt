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

/**
 * A object used with [TableLogBuffer] to store changes in variables over time. Is recyclable.
 *
 * Each message represents a change to exactly 1 type, specified by [DataType].
 */
data class TableChange(
    var timestamp: Long = 0,
    var columnPrefix: String = "",
    var columnName: String = "",
    var type: DataType = DataType.EMPTY,
    var bool: Boolean = false,
    var int: Int = 0,
    var str: String? = null,
) {
    /** Resets to default values so that the object can be recycled. */
    fun reset(timestamp: Long, columnPrefix: String, columnName: String) {
        this.timestamp = timestamp
        this.columnPrefix = columnPrefix
        this.columnName = columnName
        this.type = DataType.EMPTY
        this.bool = false
        this.int = 0
        this.str = null
    }

    /** Sets this to store a string change. */
    fun set(value: String?) {
        type = DataType.STRING
        str = value
    }

    /** Sets this to store a boolean change. */
    fun set(value: Boolean) {
        type = DataType.BOOLEAN
        bool = value
    }

    /** Sets this to store an int change. */
    fun set(value: Int) {
        type = DataType.INT
        int = value
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

    fun getVal(): String {
        return when (type) {
            DataType.EMPTY -> null
            DataType.STRING -> str
            DataType.INT -> int
            DataType.BOOLEAN -> bool
        }.toString()
    }

    enum class DataType {
        STRING,
        BOOLEAN,
        INT,
        EMPTY,
    }
}
