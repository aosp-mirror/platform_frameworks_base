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

package com.android.systemui.keyboard.shortcut.ui.composable

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardCommandKey
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.res.R

/** Temporary data classes and data below just to populate the UI. */
data class ShortcutHelperCategory(
    @StringRes val labelResId: Int,
    val icon: ImageVector,
    val subCategories: List<SubCategory>,
)

data class SubCategory(
    val label: String,
    val shortcuts: List<Shortcut>,
)

data class Shortcut(val label: String, val commands: List<ShortcutCommand>)

data class ShortcutCommand(val keys: List<ShortcutKey>)

sealed interface ShortcutKey {
    data class Text(val value: String) : ShortcutKey

    data class Icon(val value: ImageVector) : ShortcutKey
}

// DSL Builder Functions
private fun shortcutHelperCategory(
    labelResId: Int,
    icon: ImageVector,
    block: ShortcutHelperCategoryBuilder.() -> Unit
): ShortcutHelperCategory = ShortcutHelperCategoryBuilder(labelResId, icon).apply(block).build()

private fun ShortcutHelperCategoryBuilder.subCategory(
    label: String,
    block: SubCategoryBuilder.() -> Unit
) {
    subCategories.add(SubCategoryBuilder(label).apply(block).build())
}

private fun SubCategoryBuilder.shortcut(label: String, block: ShortcutBuilder.() -> Unit) {
    shortcuts.add(ShortcutBuilder(label).apply(block).build())
}

private fun ShortcutBuilder.command(block: ShortcutCommandBuilder.() -> Unit) {
    commands.add(ShortcutCommandBuilder().apply(block).build())
}

private fun ShortcutCommandBuilder.key(value: String) {
    keys.add(ShortcutKey.Text(value))
}

private fun ShortcutCommandBuilder.key(value: ImageVector) {
    keys.add(ShortcutKey.Icon(value))
}

private class ShortcutHelperCategoryBuilder(
    private val labelResId: Int,
    private val icon: ImageVector
) {
    val subCategories = mutableListOf<SubCategory>()

    fun build() = ShortcutHelperCategory(labelResId, icon, subCategories)
}

private class SubCategoryBuilder(private val label: String) {
    val shortcuts = mutableListOf<Shortcut>()

    fun build() = SubCategory(label, shortcuts)
}

private class ShortcutBuilder(private val label: String) {
    val commands = mutableListOf<ShortcutCommand>()

    fun build() = Shortcut(label, commands)
}

private class ShortcutCommandBuilder {
    val keys = mutableListOf<ShortcutKey>()

    fun build() = ShortcutCommand(keys)
}

object ShortcutHelperTemporaryData {

    // Some shortcuts and their strings below are made up just to populate the UI for now.
    // For this reason they are not in translatable resources yet.
    val categories =
        listOf(
            shortcutHelperCategory(R.string.shortcut_helper_category_system, Icons.Default.Tv) {
                subCategory("System controls") {
                    shortcut("Go to home screen") {
                        command { key(Icons.Default.RadioButtonUnchecked) }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("H")
                        }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Return")
                        }
                    }
                    shortcut("View recent apps") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Tab")
                        }
                    }
                    shortcut("All apps search") {
                        command { key(Icons.Default.KeyboardCommandKey) }
                    }
                }
                subCategory("System apps") {
                    shortcut("Go back") {
                        command { key(Icons.Default.ArrowBackIosNew) }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Left arrow")
                        }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("ESC")
                        }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Backspace")
                        }
                    }
                    shortcut("View notifications") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("N")
                        }
                    }
                    shortcut("Take a screenshot") {
                        command { key(Icons.Default.KeyboardCommandKey) }
                        command { key("CTRL") }
                        command { key("S") }
                    }
                    shortcut("Open Settings") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("I")
                        }
                    }
                }
            },
            shortcutHelperCategory(
                R.string.shortcut_helper_category_multitasking,
                Icons.Default.VerticalSplit
            ) {
                subCategory("Multitasking & windows") {
                    shortcut("Take a screenshot") {
                        command { key(Icons.Default.KeyboardCommandKey) }
                        command { key("CTRL") }
                        command { key("S") }
                    }
                }
            },
            shortcutHelperCategory(
                R.string.shortcut_helper_category_input,
                Icons.Default.Keyboard
            ) {
                subCategory("Input") {
                    shortcut("Open Settings") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("I")
                        }
                    }
                    shortcut("View notifications") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("N")
                        }
                    }
                }
            },
            shortcutHelperCategory(
                R.string.shortcut_helper_category_app_shortcuts,
                Icons.Default.Apps
            ) {
                subCategory("App shortcuts") {
                    shortcut("Open Settings") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("I")
                        }
                    }
                    shortcut("Go back") {
                        command { key(Icons.Default.ArrowBackIosNew) }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Left arrow")
                        }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("ESC")
                        }
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Backspace")
                        }
                    }
                }
            },
            shortcutHelperCategory(
                R.string.shortcut_helper_category_a11y,
                Icons.Default.Accessibility
            ) {
                subCategory("Accessibility shortcuts") {
                    shortcut("View recent apps") {
                        command {
                            key(Icons.Default.KeyboardCommandKey)
                            key("Tab")
                        }
                    }
                    shortcut("All apps search") {
                        command { key(Icons.Default.KeyboardCommandKey) }
                    }
                }
            }
        )
}
