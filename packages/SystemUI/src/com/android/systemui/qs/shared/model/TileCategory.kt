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

package com.android.systemui.qs.shared.model

import com.android.systemui.common.shared.model.Text
import com.android.systemui.res.R

/** Categories for tiles. This can be used to sort tiles in edit mode. */
enum class TileCategory(val label: Text) {
    CONNECTIVITY(Text.Resource(R.string.qs_edit_mode_category_connectivity)),
    UTILITIES(Text.Resource(R.string.qs_edit_mode_category_utilities)),
    DISPLAY(Text.Resource(R.string.qs_edit_mode_category_display)),
    PRIVACY(Text.Resource(R.string.qs_edit_mode_category_privacy)),
    ACCESSIBILITY(Text.Resource(R.string.qs_edit_mode_category_accessibility)),
    PROVIDED_BY_APP(Text.Resource(R.string.qs_edit_mode_category_providedByApps)),
    UNKNOWN(Text.Resource(R.string.qs_edit_mode_category_unknown)),
}

interface CategoryAndName {
    val category: TileCategory
    val name: String
}

/**
 * Groups the elements of the list by [CategoryAndName.category] (with the keys sorted in the
 * natural order of [TileCategory]), and sorts the elements of each group based on the
 * [CategoryAndName.name].
 */
fun <T : CategoryAndName> groupAndSort(list: List<T>): Map<TileCategory, List<T>> {
    val groupedByCategory = list.groupBy { it.category }.toSortedMap()
    return groupedByCategory.mapValues { it.value.sortedBy { it.name } }
}
