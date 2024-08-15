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

import androidx.annotation.IntRange
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.kotlin.pairwiseBy
import kotlinx.coroutines.flow.Flow

@JvmInline
value class GammaBrightness(
    @IntRange(
        from = BrightnessUtils.GAMMA_SPACE_MIN.toLong(),
        to = BrightnessUtils.GAMMA_SPACE_MAX.toLong()
    )
    val value: Int
)

internal fun Flow<GammaBrightness>.logDiffForTable(
    tableLogBuffer: TableLogBuffer,
    columnPrefix: String,
    columnName: String,
    initialValue: GammaBrightness?,
): Flow<GammaBrightness> {
    val initialValueFun = {
        tableLogBuffer.logChange(columnPrefix, columnName, initialValue?.value, isInitial = true)
        initialValue
    }
    return this.pairwiseBy(initialValueFun) { prevVal: GammaBrightness?, newVal: GammaBrightness ->
        if (prevVal != newVal) {
            tableLogBuffer.logChange(columnPrefix, columnName, newVal.value)
        }
        newVal
    }
}
