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

package com.android.systemui.communal.layout.ui.compose.config

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.times

/**
 * Configurations of the communal grid layout.
 *
 * The communal grid layout follows Material Design's responsive layout grid (see
 * https://m2.material.io/design/layout/responsive-layout-grid.html), in which the layout is divided
 * up by columns and gutters, and each card occupies one or multiple columns.
 */
data class CommunalGridLayoutConfig(
    /**
     * Size in dp of each grid column.
     *
     * Every card occupies one or more grid columns, which means that the width of each card is
     * influenced by the size of the grid columns.
     */
    val gridColumnSize: Dp,

    /**
     * Size in dp of each grid gutter.
     *
     * A gutter is the space between columns that helps separate content. This is, therefore, also
     * the size of the gaps between cards, both horizontally and vertically.
     */
    val gridGutter: Dp,

    /**
     * Height in dp of the grid layout.
     *
     * Cards with a full size take up the entire height of the grid layout.
     */
    val gridHeight: Dp,

    /**
     * Number of grid columns that each card occupies.
     *
     * It's important to note that all the cards take up the same number of grid columns, or in
     * simpler terms, they all have the same width.
     */
    val gridColumnsPerCard: Int,
) {
    /**
     * Width in dp of each card.
     *
     * It's important to note that all the cards take up the same number of grid columns, or in
     * simpler terms, they all have the same width.
     */
    val cardWidth = gridColumnSize * gridColumnsPerCard + gridGutter * (gridColumnsPerCard - 1)

    /** Returns the height of a card in dp, based on its size. */
    fun cardHeight(cardSize: CommunalGridLayoutCard.Size): Dp {
        return when (cardSize) {
            CommunalGridLayoutCard.Size.FULL -> cardHeightBy(denominator = 1)
            CommunalGridLayoutCard.Size.HALF -> cardHeightBy(denominator = 2)
            CommunalGridLayoutCard.Size.THIRD -> cardHeightBy(denominator = 3)
        }
    }

    /** Returns the height of a card in dp when the layout is evenly divided by [denominator]. */
    private fun cardHeightBy(denominator: Int): Dp {
        return (gridHeight - (denominator - 1) * gridGutter) / denominator
    }
}
