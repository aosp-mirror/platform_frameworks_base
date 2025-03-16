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

package com.android.systemui.communal.shared.model

import com.android.systemui.Flags.communalResponsiveGrid

/**
 * Supported sizes for communal content in the layout grid.
 *
 * @property span The span of the content in a column.
 */
sealed interface CommunalContentSize {
    val span: Int

    @Deprecated("Use Responsive size instead")
    enum class FixedSize(override val span: Int) : CommunalContentSize {
        /** Content takes the full height of the column. */
        FULL(6),

        /** Content takes half of the height of the column. */
        HALF(3),

        /** Content takes a third of the height of the column. */
        THIRD(2),
    }

    @JvmInline value class Responsive(override val span: Int) : CommunalContentSize

    companion object {
        /** Converts from span to communal content size. */
        fun toSize(span: Int): CommunalContentSize {
            return if (communalResponsiveGrid()) {
                Responsive(span)
            } else {
                FixedSize.entries.find { it.span == span }
                    ?: throw IllegalArgumentException("$span is not a valid span size")
            }
        }
    }
}
