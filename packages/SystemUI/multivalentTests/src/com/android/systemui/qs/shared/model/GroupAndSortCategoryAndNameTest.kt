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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GroupAndSortCategoryAndNameTest : SysuiTestCase() {

    private val elements =
        listOf(
            CategoryAndName(TileCategory.DISPLAY, "B"),
            CategoryAndName(TileCategory.PRIVACY, "A"),
            CategoryAndName(TileCategory.DISPLAY, "C"),
            CategoryAndName(TileCategory.UTILITIES, "B"),
            CategoryAndName(TileCategory.CONNECTIVITY, "A"),
            CategoryAndName(TileCategory.PROVIDED_BY_APP, "B"),
            CategoryAndName(TileCategory.CONNECTIVITY, "C"),
            CategoryAndName(TileCategory.ACCESSIBILITY, "A")
        )

    @Test
    fun allElementsInResult() {
        val grouped = groupAndSort(elements)
        val allValues = grouped.values.reduce { acc, el -> acc + el }
        assertThat(allValues).containsExactlyElementsIn(elements)
    }

    @Test
    fun groupedByCategory() {
        val grouped = groupAndSort(elements)
        grouped.forEach { tileCategory, categoryAndNames ->
            categoryAndNames.forEach { element ->
                assertThat(element.category).isEqualTo(tileCategory)
            }
        }
    }

    @Test
    fun sortedAlphabeticallyInEachCategory() {
        val grouped = groupAndSort(elements)
        grouped.values.forEach { elements ->
            assertThat(elements.map(CategoryAndName::name)).isInOrder()
        }
    }

    @Test
    fun categoriesSortedInNaturalOrder() {
        val grouped = groupAndSort(elements)
        assertThat(grouped.keys).isInOrder()
    }

    @Test
    fun missingCategoriesAreNotInResult() {
        val grouped = groupAndSort(elements.filterNot { it.category == TileCategory.CONNECTIVITY })
        assertThat(grouped.keys).doesNotContain(TileCategory.CONNECTIVITY)
    }

    companion object {
        private fun CategoryAndName(category: TileCategory, name: String): CategoryAndName {
            return object : CategoryAndName {
                override val category = category
                override val name = name
            }
        }
    }
}
