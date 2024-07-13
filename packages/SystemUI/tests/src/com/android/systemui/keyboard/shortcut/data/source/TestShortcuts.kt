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

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
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
                shortcutInfoWithRepeatedLabelSecondAlternate
            )
        )

    private val subCategoryWithGroupedRepeatedShortcutLabels =
        ShortcutSubCategory(
            label = groupWithRepeatedShortcutLabels.label!!.toString(),
            shortcuts = listOf(shortcutWithGroupedRepeatedLabel)
        )

    private val groupWithStandardShortcutInfo =
        KeyboardShortcutGroup("Standard group", listOf(standardShortcutInfo1))

    private val subCategoryWithStandardShortcut =
        ShortcutSubCategory(
            label = groupWithStandardShortcutInfo.label!!.toString(),
            shortcuts = listOf(standardShortcut1)
        )

    private val groupWithOnlyUnsupportedModifierShortcut =
        KeyboardShortcutGroup(
            "Group with unsupported modifiers",
            listOf(shortcutInfoWithUnsupportedModifiers)
        )

    private val groupWithSupportedAndUnsupportedModifierShortcut =
        KeyboardShortcutGroup(
            "Group with mix of supported and not supported modifiers",
            listOf(standardShortcutInfo3, shortcutInfoWithUnsupportedModifiers)
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
            listOf(switchToNextLanguageShortcut, switchToPreviousLanguageShortcut)
        )

    private val subCategoryWithUnsupportedShortcutsRemoved =
        ShortcutSubCategory(
            groupWithSupportedAndUnsupportedModifierShortcut.label!!.toString(),
            listOf(standardShortcut3)
        )

    private val standardGroup1 =
        KeyboardShortcutGroup(
            "Standard group 1",
            listOf(standardShortcutInfo1, standardShortcutInfo2, standardShortcutInfo3)
        )

    private val standardPackageName1 = "standard.app.group1"

    private val standardAppGroup1 =
        KeyboardShortcutGroup(
                "Standard app group 1",
                listOf(standardShortcutInfo1, standardShortcutInfo2, standardShortcutInfo3)
            )
            .apply { packageName = standardPackageName1 }

    private val standardSubCategory1 =
        ShortcutSubCategory(
            standardGroup1.label!!.toString(),
            listOf(standardShortcut1, standardShortcut2, standardShortcut3)
        )

    private val standardGroup2 =
        KeyboardShortcutGroup(
            "Standard group 2",
            listOf(standardShortcutInfo3, standardShortcutInfo2, standardShortcutInfo1)
        )

    private val standardSubCategory2 =
        ShortcutSubCategory(
            standardGroup2.label!!.toString(),
            listOf(standardShortcut3, standardShortcut2, standardShortcut1)
        )
    private val standardGroup3 =
        KeyboardShortcutGroup(
            "Standard group 3",
            listOf(standardShortcutInfo2, standardShortcutInfo1)
        )

    private val standardSubCategory3 =
        ShortcutSubCategory(
            standardGroup3.label!!.toString(),
            listOf(standardShortcut2, standardShortcut1)
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
                    standardSubCategory3
                )
        )

    val currentAppGroups = listOf(standardAppGroup1)
    val currentAppPackageName = standardPackageName1

    val systemGroups = listOf(standardGroup3, standardGroup2, standardGroup1)
    val systemCategory =
        ShortcutCategory(
            type = ShortcutCategoryType.System,
            subCategories = listOf(standardSubCategory3, standardSubCategory2, standardSubCategory1)
        )

    val multitaskingGroups = listOf(standardGroup2, standardGroup1)
    val multitaskingCategory =
        ShortcutCategory(
            type = ShortcutCategoryType.MultiTasking,
            subCategories = listOf(standardSubCategory2, standardSubCategory1)
        )

    val groupsWithDuplicateShortcutLabels =
        listOf(groupWithRepeatedShortcutLabels, groupWithStandardShortcutInfo)

    val subCategoriesWithGroupedDuplicatedShortcutLabels =
        listOf(subCategoryWithGroupedRepeatedShortcutLabels, subCategoryWithStandardShortcut)

    val imeSubCategoriesWithGroupedDuplicatedShortcutLabels =
        listOf(
            subCategoryForInputLanguageSwitchShortcuts,
            subCategoryWithGroupedRepeatedShortcutLabels,
            subCategoryWithStandardShortcut
        )

    val groupsWithUnsupportedModifier =
        listOf(
            groupWithStandardShortcutInfo,
            groupWithOnlyUnsupportedModifierShortcut,
            groupWithSupportedAndUnsupportedModifierShortcut
        )

    val subCategoriesWithUnsupportedModifiersRemoved =
        listOf(subCategoryWithStandardShortcut, subCategoryWithUnsupportedShortcutsRemoved)

    val imeSubCategoriesWithUnsupportedModifiersRemoved =
        listOf(
            subCategoryForInputLanguageSwitchShortcuts,
            subCategoryWithStandardShortcut,
            subCategoryWithUnsupportedShortcutsRemoved
        )

    val groupsWithOnlyUnsupportedModifiers = listOf(groupWithOnlyUnsupportedModifierShortcut)
}
