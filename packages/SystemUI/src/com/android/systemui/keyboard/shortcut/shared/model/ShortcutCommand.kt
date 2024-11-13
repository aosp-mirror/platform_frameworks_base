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

import androidx.annotation.DrawableRes

data class ShortcutCommand(val keys: List<ShortcutKey>)

class ShortcutCommandBuilder {
    private val keys = mutableListOf<ShortcutKey>()

    fun key(text: String) {
        keys += ShortcutKey.Text(text)
    }

    fun key(@DrawableRes drawableResId: Int) {
        keys += ShortcutKey.Icon(drawableResId)
    }

    fun build() = ShortcutCommand(keys)
}

fun shortcutCommand(block: ShortcutCommandBuilder.() -> Unit) =
    ShortcutCommandBuilder().apply(block).build()
