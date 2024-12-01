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

data class Shortcut(
    val label: String,
    val commands: List<ShortcutCommand>,
    val icon: ShortcutIcon? = null,
    val contentDescription: String = "",
) {
    val containsCustomShortcutCommands: Boolean = commands.any { it.isCustom }
}

class ShortcutBuilder(private val label: String) {
    val commands = mutableListOf<ShortcutCommand>()
    var contentDescription = ""

    fun command(builder: ShortcutCommandBuilder.() -> Unit) {
        commands += ShortcutCommandBuilder().apply(builder).build()
    }

    fun contentDescription(string: () -> String) {
        contentDescription = string.invoke()
    }

    fun build() = Shortcut(label, commands, contentDescription = contentDescription)
}

fun shortcut(label: String, block: ShortcutBuilder.() -> Unit): Shortcut =
    ShortcutBuilder(label).apply(block).build()
