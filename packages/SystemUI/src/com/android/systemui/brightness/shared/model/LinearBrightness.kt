/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.brightness.shared.model

import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.kotlin.pairwiseBy
import kotlinx.coroutines.flow.Flow

@JvmInline
value class LinearBrightness(val floatValue: Float) {
    fun clamp(min: LinearBrightness, max: LinearBrightness): LinearBrightness {
        return if (floatValue < min.floatValue) {
            min
        } else if (floatValue > max.floatValue) {
            max
        } else {
            this
        }
    }

    val loggableString: String
        get() = floatValue.formatBrightness()
}

fun Float.formatBrightness(): String {
    return "%.3f".format(this)
}

internal fun Flow<LinearBrightness>.logDiffForTable(
    tableLogBuffer: TableLogBuffer,
    columnPrefix: String,
    columnName: String,
    initialValue: LinearBrightness?,
): Flow<LinearBrightness> {
    val initialValueFun = {
        tableLogBuffer.logChange(
            columnPrefix,
            columnName,
            initialValue?.loggableString,
            isInitial = true
        )
        initialValue
    }
    return this.pairwiseBy(initialValueFun) { prevVal: LinearBrightness?, newVal: LinearBrightness
        ->
        if (prevVal != newVal) {
            tableLogBuffer.logChange(columnPrefix, columnName, newVal.loggableString)
        }
        newVal
    }
}
