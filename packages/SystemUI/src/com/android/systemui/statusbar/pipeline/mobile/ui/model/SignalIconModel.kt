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

import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** A model that will be consumed by [SignalDrawable] to show the mobile triangle icon. */
data class SignalIconModel(
    val level: Int,
    val numberOfLevels: Int,
    val showExclamationMark: Boolean,
    val carrierNetworkChange: Boolean,
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
        if (prevVal.carrierNetworkChange != carrierNetworkChange) {
            row.logChange(COL_CARRIER_NETWORK_CHANGE, carrierNetworkChange)
        }
    }

    override fun logFull(row: TableRowLogger) {
        row.logChange(COL_LEVEL, level)
        row.logChange(COL_NUM_LEVELS, numberOfLevels)
        row.logChange(COL_SHOW_EXCLAMATION, showExclamationMark)
        row.logChange(COL_CARRIER_NETWORK_CHANGE, carrierNetworkChange)
    }

    /** Convert this model to an [Int] consumable by [SignalDrawable]. */
    fun toSignalDrawableState(): Int =
        if (carrierNetworkChange) {
            SignalDrawable.getCarrierChangeState(numberOfLevels)
        } else {
            SignalDrawable.getState(level, numberOfLevels, showExclamationMark)
        }

    companion object {
        private const val COL_LEVEL = "level"
        private const val COL_NUM_LEVELS = "numLevels"
        private const val COL_SHOW_EXCLAMATION = "showExclamation"
        private const val COL_CARRIER_NETWORK_CHANGE = "carrierNetworkChange"
    }
}
