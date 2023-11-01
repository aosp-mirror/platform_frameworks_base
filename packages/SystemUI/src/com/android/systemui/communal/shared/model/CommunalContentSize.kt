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

/**
 * Supported sizes for communal content in the layout grid.
 *
 * @param span The span of the content in a column. For example, if FULL is 6, then 3 represents
 *   HALF, 2 represents THIRD, and 1 represents SIXTH.
 */
enum class CommunalContentSize(val span: Int) {
    /** Content takes the full height of the column. */
    FULL(6),

    /** Content takes half of the height of the column. */
    HALF(3),

    /** Content takes a third of the height of the column. */
    THIRD(2),
}
