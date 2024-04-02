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

package com.android.systemui.statusbar.pipeline.mobile.domain.model

import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

sealed interface SignalIconModel : Diffable<SignalIconModel> {
    val level: Int

    override fun logDiffs(prevVal: SignalIconModel, row: TableRowLogger) {
        logPartial(prevVal, row)
    }

    override fun logFull(row: TableRowLogger) = logFully(row)

    fun logFully(row: TableRowLogger)

    fun logPartial(prevVal: SignalIconModel, row: TableRowLogger)

    /** A model that will be consumed by [SignalDrawable] to show the mobile triangle icon. */
    data class Cellular(
        override val level: Int,
        val numberOfLevels: Int,
        val showExclamationMark: Boolean,
        val carrierNetworkChange: Boolean,
    ) : SignalIconModel {
        override fun logPartial(prevVal: SignalIconModel, row: TableRowLogger) {
            if (prevVal !is Cellular) {
                logFull(row)
            } else {
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
        }

        override fun logFully(row: TableRowLogger) {
            row.logChange(COL_TYPE, "c")
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
    }

    /**
     * For non-terrestrial networks, we can use a resource-backed icon instead of the
     * [SignalDrawable]-backed version above
     */
    data class Satellite(
        override val level: Int,
        val icon: Icon.Resource,
    ) : SignalIconModel {
        override fun logPartial(prevVal: SignalIconModel, row: TableRowLogger) {
            if (prevVal !is Satellite) {
                logFull(row)
            } else {
                if (prevVal.level != level) row.logChange(COL_LEVEL, level)
            }
        }

        override fun logFully(row: TableRowLogger) {
            // Satellite icon has only 3 levels, unchanging
            row.logChange(COL_NUM_LEVELS, "3")
            row.logChange(COL_TYPE, "s")
            row.logChange(COL_LEVEL, level)
        }
    }

    companion object {
        private const val COL_LEVEL = "level"
        private const val COL_NUM_LEVELS = "numLevels"
        private const val COL_SHOW_EXCLAMATION = "showExclamation"
        private const val COL_CARRIER_NETWORK_CHANGE = "carrierNetworkChange"
        private const val COL_TYPE = "type"
    }
}
