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

import android.util.SizeF
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A card that hosts content to be rendered in the communal grid layout. */
abstract class CommunalGridLayoutCard {
    /**
     * Content to be hosted by the card.
     *
     * To host non-Compose views, see
     * https://developer.android.com/jetpack/compose/migrate/interoperability-apis/views-in-compose.
     *
     * @param size The size given to the card. Content of the card should fill all this space, given
     *   that margins and paddings have been taken care of by the layout.
     */
    @Composable abstract fun Content(modifier: Modifier, size: SizeF)

    /**
     * Sizes supported by the card.
     *
     * If multiple sizes are available, they should be ranked in order of preference, from most to
     * least preferred.
     */
    abstract val supportedSizes: List<Size>

    /**
     * Priority of the content hosted by the card.
     *
     * The value of priority is relative to other cards. Cards with a higher priority are generally
     * ordered first.
     */
    open val priority: Int = 0

    /**
     * Size of the card.
     *
     * @param value A numeric value that represents the size. Must be less than or equal to
     *   [Size.FULL].
     */
    enum class Size(val value: Int) {
        /** The card takes up full height of the grid layout. */
        FULL(value = 6),

        /** The card takes up half of the vertical space of the grid layout. */
        HALF(value = 3),

        /** The card takes up a third of the vertical space of the grid layout. */
        THIRD(value = 2),
    }
}
