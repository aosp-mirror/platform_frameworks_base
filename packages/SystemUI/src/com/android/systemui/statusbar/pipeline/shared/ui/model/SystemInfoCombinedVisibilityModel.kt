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

import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState

/** The combined visibility + animation state for the system info status bar area */
data class SystemInfoCombinedVisibilityModel(
    val baseVisibility: VisibilityModel,
    val animationState: SystemEventAnimationState,
) : Diffable<SystemInfoCombinedVisibilityModel> {
    override fun logDiffs(prevVal: SystemInfoCombinedVisibilityModel, row: TableRowLogger) {
        if (animationState != prevVal.animationState) {
            row.logChange(COL_ANIM, animationState.name)
        }

        baseVisibility.logDiffs(prevVal.baseVisibility, row)
    }

    override fun logFull(row: TableRowLogger) {
        row.logChange(COL_ANIM, animationState.name)
        baseVisibility.logFull(row)
    }

    companion object {
        const val COL_ANIM = "animState"
    }
}
