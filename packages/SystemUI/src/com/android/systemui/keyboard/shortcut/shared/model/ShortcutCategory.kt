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

package com.android.systemui.keyboard.shortcut.shared.model

sealed interface ShortcutCategoryType {
    val isTrusted: Boolean
    val includeInCustomization: Boolean

    data object System : ShortcutCategoryType {
        override val isTrusted: Boolean = true
        override val includeInCustomization: Boolean = true
    }

    data object MultiTasking : ShortcutCategoryType {
        override val isTrusted: Boolean = true
        override val includeInCustomization: Boolean = true
    }

    data object InputMethodEditor : ShortcutCategoryType {
        override val isTrusted: Boolean = false
        override val includeInCustomization: Boolean = false
    }

    data object AppCategories : ShortcutCategoryType {
        override val isTrusted: Boolean = true
        override val includeInCustomization: Boolean = true
    }

    data class CurrentApp(val packageName: String) : ShortcutCategoryType {
        override val isTrusted: Boolean = false
        override val includeInCustomization: Boolean = false
    }
}

data class ShortcutCategory(
    val type: ShortcutCategoryType,
    val subCategories: List<ShortcutSubCategory>,
) {
    constructor(
        type: ShortcutCategoryType,
        vararg subCategories: ShortcutSubCategory,
    ) : this(type, subCategories.asList())
}

class ShortcutCategoryBuilder(val type: ShortcutCategoryType) {
    private val subCategories = mutableListOf<ShortcutSubCategory>()

    fun subCategory(label: String, builder: ShortcutSubCategoryBuilder.() -> Unit) {
        subCategories += ShortcutSubCategoryBuilder(label).apply(builder).build()
    }

    fun build() = ShortcutCategory(type, subCategories)
}

fun shortcutCategory(type: ShortcutCategoryType, block: ShortcutCategoryBuilder.() -> Unit) =
    ShortcutCategoryBuilder(type).apply(block).build()
