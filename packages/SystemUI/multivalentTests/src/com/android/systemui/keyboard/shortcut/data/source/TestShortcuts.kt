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

package com.android.systemui.keyboard.shortcut.data.source

import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.KeyGestureEvent
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.AppCategories
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.shared.model.shortcut
import com.android.systemui.res.R

object TestShortcuts {

    private val shortcutInfoWithRepeatedLabel =
        KeyboardShortcutInfo(
            /* label = */ "Shortcut with repeated label",
            /* keycode = */ KeyEvent.KEYCODE_H,
            /* modifiers = */ KeyEvent.META_META_ON,
        )

    private val shortcutInfoWithRepeatedLabelAlternate =
        KeyboardShortcutInfo(
            /* label = */ shortcutInfoWithRepeatedLabel.label,
            /* keycode = */ KeyEvent.KEYCODE_L,
            /* modifiers = */ KeyEvent.META_META_ON,
        )

    private val shortcutInfoWithRepeatedLabelSecondAlternate =
        KeyboardShortcutInfo(
            /* label = */ shortcutInfoWithRepeatedLabel.label,
            /* keycode = */ KeyEvent.KEYCODE_M,
            /* modifiers = */ KeyEvent.META_SHIFT_ON,
        )

    private val shortcutWithGroupedRepeatedLabel =
        shortcut(shortcutInfoWithRepeatedLabel.label!!.toString()) {
            command {
                key(R.drawable.ic_ksh_key_meta)
                key("H")
            }
            command {
                key(R.drawable.ic_ksh_key_meta)
                key("L")
            }
            command {
                key("Shift")
                key("M")
            }
        }

    private val goHomeShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ "Go to home screen",
            /* keycode = */ KeyEvent.KEYCODE_B,
            /* modifiers = */ KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
        )

    private val standardShortcutInfo1 =
        KeyboardShortcutInfo(
            /* label = */ "Standard shortcut 1",
            /* keycode = */ KeyEvent.KEYCODE_N,
            /* modifiers = */ KeyEvent.META_META_ON,
        )

    private val standardShortcut1 =
        shortcut(standardShortcutInfo1.label!!.toString()) {
            command {
                key(R.drawable.ic_ksh_key_meta)
                key("N")
            }
        }

    private val customGoHomeShortcut =
        shortcut("Go to home screen") {
            command {
                key("Ctrl")
                key("Alt")
                key("A")
                isCustom(true)
            }
        }

    private val standardShortcutInfo2 =
        KeyboardShortcutInfo(
            /* label = */ "Standard shortcut 2",
            /* keycode = */ KeyEvent.KEYCODE_Z,
            /* modifiers = */ KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON,
        )

    private val standardShortcut2 =
        shortcut(standardShortcutInfo2.label!!.toString()) {
            command {
                key("Alt")
                key("Shift")
                key("Z")
            }
        }

    private val standardShortcutInfo3 =
        KeyboardShortcutInfo(
            /* label = */ "Standard shortcut 3",
            /* keycode = */ KeyEvent.KEYCODE_J,
            /* modifiers = */ KeyEvent.META_CTRL_ON,
        )

    private val standardShortcut3 =
        shortcut(standardShortcutInfo3.label!!.toString()) {
            command {
                key("Ctrl")
                key("J")
            }
        }

    private val shortcutInfoWithUnsupportedModifiers =
        KeyboardShortcutInfo(
            /* label = */ "Shortcut with unsupported modifiers",
            /* keycode = */ KeyEvent.KEYCODE_A,
            /* modifiers = */ KeyEvent.META_META_ON or KeyEvent.KEYCODE_SPACE,
        )

    private val groupWithRepeatedShortcutLabels =
        KeyboardShortcutGroup(
            "Group with duplicate labels",
            listOf(
                shortcutInfoWithRepeatedLabel,
                shortcutInfoWithRepeatedLabelAlternate,
                shortcutInfoWithRepeatedLabelSecondAlternate,
            ),
        )

    private val subCategoryWithGroupedRepeatedShortcutLabels =
        ShortcutSubCategory(
            label = groupWithRepeatedShortcutLabels.label!!.toString(),
            shortcuts = listOf(shortcutWithGroupedRepeatedLabel),
        )

    private val groupWithStandardShortcutInfo =
        KeyboardShortcutGroup("Standard group", listOf(standardShortcutInfo1))

    val groupWithGoHomeShortcutInfo =
        KeyboardShortcutGroup("System controls", listOf(goHomeShortcutInfo))

    private val subCategoryWithStandardShortcut =
        ShortcutSubCategory(
            label = groupWithStandardShortcutInfo.label!!.toString(),
            shortcuts = listOf(standardShortcut1),
        )

    private val groupWithOnlyUnsupportedModifierShortcut =
        KeyboardShortcutGroup(
            "Group with unsupported modifiers",
            listOf(shortcutInfoWithUnsupportedModifiers),
        )

    private val groupWithSupportedAndUnsupportedModifierShortcut =
        KeyboardShortcutGroup(
            "Group with mix of supported and not supported modifiers",
            listOf(standardShortcutInfo3, shortcutInfoWithUnsupportedModifiers),
        )

    private val switchToNextLanguageShortcut =
        shortcut("Switch to next language") {
            command {
                key("Ctrl")
                key("Space")
            }
        }

    private val switchToPreviousLanguageShortcut =
        shortcut("Switch to previous language") {
            command {
                key("Ctrl")
                key("Shift")
                key("Space")
            }
        }

    private val subCategoryForInputLanguageSwitchShortcuts =
        ShortcutSubCategory(
            "Input",
            listOf(switchToNextLanguageShortcut, switchToPreviousLanguageShortcut),
        )

    private val subCategoryWithUnsupportedShortcutsRemoved =
        ShortcutSubCategory(
            groupWithSupportedAndUnsupportedModifierShortcut.label!!.toString(),
            listOf(standardShortcut3),
        )

    private val standardGroup1 =
        KeyboardShortcutGroup(
            "Standard group 1",
            listOf(standardShortcutInfo1, standardShortcutInfo2, standardShortcutInfo3),
        )

    private val standardPackageName1 = "standard.app.group1"

    private val standardAppGroup1 =
        KeyboardShortcutGroup(
                "Standard app group 1",
                listOf(standardShortcutInfo1, standardShortcutInfo2, standardShortcutInfo3),
            )
            .apply { packageName = standardPackageName1 }

    private val standardSystemAppSubcategoryWithCustomHomeShortcut =
        ShortcutSubCategory("System controls", listOf(customGoHomeShortcut))

    private val standardSubCategory1 =
        ShortcutSubCategory(
            standardGroup1.label!!.toString(),
            listOf(standardShortcut1, standardShortcut2, standardShortcut3),
        )

    private val standardGroup2 =
        KeyboardShortcutGroup(
            "Standard group 2",
            listOf(standardShortcutInfo3, standardShortcutInfo2, standardShortcutInfo1),
        )

    private val standardSubCategory2 =
        ShortcutSubCategory(
            standardGroup2.label!!.toString(),
            listOf(standardShortcut3, standardShortcut2, standardShortcut1),
        )
    private val standardGroup3 =
        KeyboardShortcutGroup(
            "Standard group 3",
            listOf(standardShortcutInfo2, standardShortcutInfo1),
        )

    private val standardSubCategory3 =
        ShortcutSubCategory(
            standardGroup3.label!!.toString(),
            listOf(standardShortcut2, standardShortcut1),
        )

    private val systemSubCategoryWithGoHomeShortcuts =
        ShortcutSubCategory(
            label = "System controls",
            shortcuts =
                listOf(
                    shortcut("Go to home screen") {
                        command {
                            key("Ctrl")
                            key("Alt")
                            key("B")
                        }
                        command {
                            key("Ctrl")
                            key("Alt")
                            key("A")
                            isCustom(true)
                        }
                    }
                ),
        )

    val imeGroups = listOf(standardGroup1, standardGroup2, standardGroup3)
    val imeCategory =
        ShortcutCategory(
            type = ShortcutCategoryType.InputMethodEditor,
            subCategories =
                listOf(
                    subCategoryForInputLanguageSwitchShortcuts,
                    standardSubCategory1,
                    standardSubCategory2,
                    standardSubCategory3,
                ),
        )

    val currentAppGroups = listOf(standardAppGroup1)
    val currentAppPackageName = standardPackageName1

    val systemGroups = listOf(standardGroup3, standardGroup2, standardGroup1)
    val systemCategory =
        ShortcutCategory(
            type = System,
            subCategories = listOf(standardSubCategory3, standardSubCategory2, standardSubCategory1),
        )

    val systemCategoryWithMergedGoHomeShortcut =
        ShortcutCategory(
            type = System,
            subCategories = listOf(systemSubCategoryWithGoHomeShortcuts),
        )

    val systemCategoryWithCustomHomeShortcut =
        ShortcutCategory(
            type = System,
            subCategories =
                listOf(
                    standardSubCategory3,
                    standardSubCategory2,
                    standardSubCategory1,
                    standardSystemAppSubcategoryWithCustomHomeShortcut,
                ),
        )

    val multitaskingGroups = listOf(standardGroup2, standardGroup1)
    val multitaskingCategory =
        ShortcutCategory(
            type = MultiTasking,
            subCategories = listOf(standardSubCategory2, standardSubCategory1),
        )

    val groupsWithDuplicateShortcutLabels =
        listOf(groupWithRepeatedShortcutLabels, groupWithStandardShortcutInfo)

    val subCategoriesWithGroupedDuplicatedShortcutLabels =
        listOf(subCategoryWithGroupedRepeatedShortcutLabels, subCategoryWithStandardShortcut)

    val imeSubCategoriesWithGroupedDuplicatedShortcutLabels =
        listOf(
            subCategoryForInputLanguageSwitchShortcuts,
            subCategoryWithGroupedRepeatedShortcutLabels,
            subCategoryWithStandardShortcut,
        )

    val groupsWithUnsupportedModifier =
        listOf(
            groupWithStandardShortcutInfo,
            groupWithOnlyUnsupportedModifierShortcut,
            groupWithSupportedAndUnsupportedModifierShortcut,
        )

    val subCategoriesWithUnsupportedModifiersRemoved =
        listOf(subCategoryWithStandardShortcut, subCategoryWithUnsupportedShortcutsRemoved)

    val imeSubCategoriesWithUnsupportedModifiersRemoved =
        listOf(
            subCategoryForInputLanguageSwitchShortcuts,
            subCategoryWithStandardShortcut,
            subCategoryWithUnsupportedShortcutsRemoved,
        )

    val groupsWithOnlyUnsupportedModifiers = listOf(groupWithOnlyUnsupportedModifierShortcut)

    private fun simpleInputGestureData(
        keyCode: Int = KeyEvent.KEYCODE_A,
        modifiers: Int = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
        keyGestureType: Int,
    ): InputGestureData {
        val builder = InputGestureData.Builder()
        builder.setKeyGestureType(keyGestureType)
        builder.setTrigger(createKeyTrigger(keyCode, modifiers))
        return builder.build()
    }

    private fun simpleShortcutCategory(
        category: ShortcutCategoryType,
        subcategoryLabel: String,
        shortcutLabel: String,
    ): ShortcutCategory {
        return ShortcutCategory(
            type = category,
            subCategories =
                listOf(
                    ShortcutSubCategory(
                        label = subcategoryLabel,
                        shortcuts = listOf(simpleShortcut(shortcutLabel)),
                    )
                ),
        )
    }

    private fun simpleShortcut(label: String) =
        Shortcut(
            label = label,
            commands =
                listOf(
                    ShortcutCommand(
                        isCustom = true,
                        keys =
                            listOf(
                                ShortcutKey.Text("Ctrl"),
                                ShortcutKey.Text("Alt"),
                                ShortcutKey.Text("A"),
                            ),
                    )
                ),
        )

    val customizableInputGestureWithUnknownKeyGestureType =
        // These key gesture events are currently not supported by shortcut helper customizer
        listOf(
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY),
        )

    val expectedShortcutCategoriesWithSimpleShortcutCombination =
        listOf(
            simpleShortcutCategory(System, "System apps", "Open assistant"),
            simpleShortcutCategory(System, "System controls", "Go to home screen"),
            simpleShortcutCategory(System, "System apps", "Open settings"),
            simpleShortcutCategory(System, "System controls", "Lock screen"),
            simpleShortcutCategory(System, "System controls", "View notifications"),
            simpleShortcutCategory(System, "System apps", "Take a note"),
            simpleShortcutCategory(System, "System controls", "Take screenshot"),
            simpleShortcutCategory(System, "System controls", "Go back"),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Switch from split screen to full screen",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Use split screen with current app on the left",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Switch to app on left or above while using split screen",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Use split screen with current app on the right",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Switch to app on right or below while using split screen",
            ),
            simpleShortcutCategory(System, "System controls", "Show shortcuts"),
            simpleShortcutCategory(System, "System controls", "View recent apps"),
            simpleShortcutCategory(AppCategories, "Applications", "Calculator"),
            simpleShortcutCategory(AppCategories, "Applications", "Calendar"),
            simpleShortcutCategory(AppCategories, "Applications", "Browser"),
            simpleShortcutCategory(AppCategories, "Applications", "Contacts"),
            simpleShortcutCategory(AppCategories, "Applications", "Email"),
            simpleShortcutCategory(AppCategories, "Applications", "Maps"),
            simpleShortcutCategory(AppCategories, "Applications", "SMS"),
            simpleShortcutCategory(MultiTasking, "Recent apps", "Cycle forward through recent apps"),
        )
    val customInputGestureTypeHome =
        simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_HOME)

    val allCustomizableInputGesturesWithSimpleShortcutCombinations =
        listOf(
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_HOME),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_BACK),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER
            ),
        )
}
