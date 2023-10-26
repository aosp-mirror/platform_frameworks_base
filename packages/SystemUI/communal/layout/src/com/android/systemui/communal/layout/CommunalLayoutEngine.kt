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

package com.android.systemui.communal.layout

import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutCard

/** Computes the arrangement of cards. */
class CommunalLayoutEngine {
    companion object {
        /**
         * Determines the size that each card should be rendered in, and distributes the cards into
         * columns.
         *
         * Returns a nested list where the outer list contains columns, and the inner list contains
         * cards in each column.
         *
         * Currently treats the first supported size as the size to be rendered in, ignoring other
         * supported sizes.
         *
         * Cards are ordered by priority, from highest to lowest.
         */
        fun distributeCardsIntoColumns(
            cards: List<CommunalGridLayoutCard>,
        ): List<List<CommunalGridLayoutCardInfo>> {
            val result = ArrayList<ArrayList<CommunalGridLayoutCardInfo>>()

            var capacityOfLastColumn = 0
            val sorted = cards.sortedByDescending { it.priority }
            for (card in sorted) {
                val cardSize = card.supportedSizes.first()
                if (capacityOfLastColumn >= cardSize.value) {
                    // Card fits in last column
                    capacityOfLastColumn -= cardSize.value
                } else {
                    // Create a new column
                    result.add(arrayListOf())
                    capacityOfLastColumn = CommunalGridLayoutCard.Size.FULL.value - cardSize.value
                }

                result.last().add(CommunalGridLayoutCardInfo(card, cardSize))
            }

            return result
        }
    }

    /**
     * A data class that wraps around a [CommunalGridLayoutCard] and also contains the size that the
     * card should be rendered in.
     */
    data class CommunalGridLayoutCardInfo(
        val card: CommunalGridLayoutCard,
        val size: CommunalGridLayoutCard.Size,
    )
}
