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

package com.android.systemui.statusbar.pipeline.shared.data.model

import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** Provides information about the current data activity direction */
data class DataActivityModel(
    /** True if the connection has activity in (download). */
    val hasActivityIn: Boolean,
    /** True if the connection has activity out (upload). */
    val hasActivityOut: Boolean,
) : Diffable<DataActivityModel> {
    override fun logDiffs(prevVal: DataActivityModel, row: TableRowLogger) {
        if (prevVal.hasActivityIn != hasActivityIn) {
            row.logChange(COL_ACTIVITY_IN, hasActivityIn)
        }
        if (prevVal.hasActivityOut != hasActivityOut) {
            row.logChange(COL_ACTIVITY_OUT, hasActivityOut)
        }
    }

    override fun logFull(row: TableRowLogger) {
        row.logChange(COL_ACTIVITY_IN, hasActivityIn)
        row.logChange(COL_ACTIVITY_OUT, hasActivityOut)
    }
}

const val ACTIVITY_PREFIX = "dataActivity"
private const val COL_ACTIVITY_IN = "in"
private const val COL_ACTIVITY_OUT = "out"
