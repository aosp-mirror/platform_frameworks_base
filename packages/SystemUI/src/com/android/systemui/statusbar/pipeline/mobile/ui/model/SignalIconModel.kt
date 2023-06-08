/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.ui.model

import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** A model that will be consumed by [SignalDrawable] to show the mobile triangle icon. */
data class SignalIconModel(
    val level: Int,
    val numberOfLevels: Int,
    val showExclamationMark: Boolean,
) : Diffable<SignalIconModel> {
    // TODO(b/267767715): Can we implement [logDiffs] and [logFull] generically for data classes?
    override fun logDiffs(prevVal: SignalIconModel, row: TableRowLogger) {
        if (prevVal.level != level) {
            row.logChange(COL_LEVEL, level)
        }
        if (prevVal.numberOfLevels != numberOfLevels) {
            row.logChange(COL_NUM_LEVELS, numberOfLevels)
        }
        if (prevVal.showExclamationMark != showExclamationMark) {
            row.logChange(COL_SHOW_EXCLAMATION, showExclamationMark)
        }
    }

    override fun logFull(row: TableRowLogger) {
        row.logChange(COL_LEVEL, level)
        row.logChange(COL_NUM_LEVELS, numberOfLevels)
        row.logChange(COL_SHOW_EXCLAMATION, showExclamationMark)
    }

    companion object {
        /** Creates a [SignalIconModel] representing an empty and invalidated state. */
        fun createEmptyState(numberOfLevels: Int) =
            SignalIconModel(level = 0, numberOfLevels, showExclamationMark = true)

        private const val COL_LEVEL = "level"
        private const val COL_NUM_LEVELS = "numLevels"
        private const val COL_SHOW_EXCLAMATION = "showExclamation"
    }
}
