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

package com.android.systemui.communal.data.model

import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** Data model of media on the communal hub. */
data class CommunalMediaModel(
    val hasActiveMediaOrRecommendation: Boolean,
    val createdTimestampMillis: Long = 0L,
) : Diffable<CommunalMediaModel> {
    companion object {
        val INACTIVE =
            CommunalMediaModel(
                hasActiveMediaOrRecommendation = false,
            )
    }

    override fun logDiffs(prevVal: CommunalMediaModel, row: TableRowLogger) {
        if (hasActiveMediaOrRecommendation != prevVal.hasActiveMediaOrRecommendation) {
            row.logChange(
                columnName = "isMediaActive",
                value = hasActiveMediaOrRecommendation,
            )
        }

        if (createdTimestampMillis != prevVal.createdTimestampMillis) {
            row.logChange(
                columnName = "mediaCreationTimestamp",
                value = createdTimestampMillis.toString(),
            )
        }
    }
}
