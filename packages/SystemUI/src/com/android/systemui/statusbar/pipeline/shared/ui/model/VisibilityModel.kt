/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.model

import android.view.View
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.util.visibilityString

/** Models the current visibility for a specific child view of status bar. */
data class VisibilityModel(
    @View.Visibility val visibility: Int,
    /** True if a visibility change should be animated. */
    val shouldAnimateChange: Boolean,
) : Diffable<VisibilityModel> {
    override fun logDiffs(prevVal: VisibilityModel, row: TableRowLogger) {
        if (visibility != prevVal.visibility) {
            row.logChange(COL_VIS, visibilityString(visibility))
        }

        if (shouldAnimateChange != prevVal.shouldAnimateChange) {
            row.logChange(COL_ANIMATE, shouldAnimateChange)
        }
    }

    override fun logFull(row: TableRowLogger) {
        row.logChange(COL_VIS, visibilityString(visibility))
        row.logChange(COL_ANIMATE, shouldAnimateChange)
    }

    companion object {
        const val COL_VIS = "vis"
        const val COL_ANIMATE = "animate"
    }
}
