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

package com.android.systemui.communal.util

import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize

object ResizeUtils {
    /**
     * Resizes ongoing items such that we don't mix regular content with ongoing content.
     *
     * NOTE: This is *NOT* a pure function, as it modifies items in the input list.
     *
     * Assumptions:
     * 1. Ongoing content is always at the start of the list.
     * 2. The maximum size of ongoing content is 2 rows.
     */
    fun resizeOngoingItems(
        list: List<CommunalContentModel>,
        numRows: Int,
    ): List<CommunalContentModel> {
        val finalizedList = mutableListOf<CommunalContentModel>()
        val numOngoing = list.count { it is CommunalContentModel.Ongoing }
        // Calculate the number of extra rows we have if each ongoing item were to take up a single
        // row. This is the number of rows we have to distribute across items.
        var extraRows =
            if (numOngoing % numRows == 0) {
                0
            } else {
                numRows - (numOngoing % numRows)
            }
        var remainingRows = numRows

        for (item in list) {
            if (item is CommunalContentModel.Ongoing) {
                if (remainingRows == 0) {
                    // Start a new column.
                    remainingRows = numRows
                }
                val newSize = if (extraRows > 0 && remainingRows > 1) 2 else 1
                item.size = CommunalContentSize.Responsive(newSize)
                finalizedList.add(item)
                extraRows -= (newSize - 1)
                remainingRows -= newSize
            } else {
                if (numOngoing > 0 && remainingRows > 0) {
                    finalizedList.add(
                        CommunalContentModel.Spacer(CommunalContentSize.Responsive(remainingRows))
                    )
                }
                remainingRows = -1
                finalizedList.add(item)
            }
        }
        return finalizedList
    }
}
