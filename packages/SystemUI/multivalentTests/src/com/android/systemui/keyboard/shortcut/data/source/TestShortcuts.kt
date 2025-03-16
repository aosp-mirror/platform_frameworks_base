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
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_HOME
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_FUNCTION_ON
import android.view.KeyEvent.META_META_LEFT_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.KeyEvent.META_SHIFT_RIGHT_ON
import android.view.KeyEvent.META_SYM_ON
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperKeys
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.shared.model.shortcut
import com.android.systemui.keyboard.shortcut.shared.model.shortcutCategory
import com.android.systemui.res.R

object TestShortcuts {

    private val shortcutInfoWithRepeatedLabel =
        KeyboardShortcutInfo(
            /* label = */ "Shortcut with repeated label",
            /* keycode = */ KeyEvent.KEYCODE_H,
            /* modifiers = */ META_META_ON,
        )

    private val shortcutInfoWithRepeatedLabelAlternate =
        KeyboardShortcutInfo(
            /* label = */ shortcutInfoWithRepeatedLabel.label,
            /* keycode = */ KeyEvent.KEYCODE_L,
            /* modifiers = */ META_META_ON,
        )

    private val shortcutInfoWithRepeatedLabelSecondAlternate =
        KeyboardShortcutInfo(
            /* label = */ shortcutInfoWithRepeatedLabel.label,
            /* keycode = */ KeyEvent.KEYCODE_M,
            /* modifiers = */ KeyEvent.META_SHIFT_ON,
        )

    private val ShortcutsWithDiffSizeOfKeys =
        KeyboardShortcutInfo(
            /* label = */ "Shortcuts with diff size of keys",
            /* keycode = */ KeyEvent.KEYCODE_HOME,
            /* modifiers = */ 0,
        )

    private val ShortcutsWithDiffSizeOfKeys2 =
        KeyboardShortcutInfo(
            /* label = */ ShortcutsWithDiffSizeOfKeys.label,
            /* keycode = */ KeyEvent.KEYCODE_1,
            /* modifiers = */ META_META_ON,
        )

    private val ShortcutsWithDiffSizeOfKeys3 =
        KeyboardShortcutInfo(
            /* label = */ ShortcutsWithDiffSizeOfKeys.label,
            /* keycode = */ KeyEvent.KEYCODE_2,
            /* modifiers = */ META_META_ON or META_FUNCTION_ON,
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
            contentDescription {
                "${shortcutInfoWithRepeatedLabel.label}, " +
                    "Press key Meta plus H, or Meta plus L, or Shift plus M"
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
            /* modifiers = */ META_META_ON,
        )

    const val CYCLE_FORWARD_THROUGH_RECENT_APPS_SHORTCUT_LABEL = "Cycle forward through recent apps"
    const val CYCLE_BACK_THROUGH_RECENT_APPS_SHORTCUT_LABEL = "Cycle backward through recent apps"

    private val recentAppsCycleForwardShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ CYCLE_FORWARD_THROUGH_RECENT_APPS_SHORTCUT_LABEL,
            /* keycode = */ KeyEvent.KEYCODE_N,
            /* modifiers = */ META_META_ON,
        )

    private val recentAppsCycleBackShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ CYCLE_BACK_THROUGH_RECENT_APPS_SHORTCUT_LABEL,
            /* keycode = */ KeyEvent.KEYCODE_N,
            /* modifiers = */ META_META_ON,
        )

    private val recentAppsCycleForwardShortcut =
        shortcut(recentAppsCycleForwardShortcutInfo.label!!.toString()) {
            command {
                key(R.drawable.ic_ksh_key_meta)
                key("N")
            }
            isCustomizable = false
        }

    private val recentAppsCycleBackShortcut =
        shortcut(recentAppsCycleBackShortcutInfo.label!!.toString()) {
            command {
                key(R.drawable.ic_ksh_key_meta)
                key("N")
            }
            isCustomizable = false
        }

    private val standardShortcut1 =
        shortcut(standardShortcutInfo1.label!!.toString()) {
            command {
                key(R.drawable.ic_ksh_key_meta)
                key("N")
            }
            contentDescription { "${standardShortcutInfo1.label}, Press key Meta plus N" }
        }

    private val customGoHomeShortcut =
        shortcut("Go to home screen") {
            command {
                key("Ctrl")
                key("Alt")
                key("A")
                isCustom(true)
            }
            contentDescription { "Go to home screen, Press key Ctrl plus Alt plus A" }
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
            contentDescription { "${standardShortcutInfo2.label}, Press key Alt plus Shift plus Z" }
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
            contentDescription { "${standardShortcutInfo3.label}, Press key Ctrl plus J" }
        }

    private val shortcutInfoWithUnsupportedModifiers =
        KeyboardShortcutInfo(
            /* label = */ "Shortcut with unsupported modifiers",
            /* keycode = */ KeyEvent.KEYCODE_A,
            /* modifiers = */ META_META_ON or KeyEvent.KEYCODE_SPACE,
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
            contentDescription { "Switch to next language, Press key Ctrl plus Space" }
        }

    private val switchToPreviousLanguageShortcut =
        shortcut("Switch to previous language") {
            command {
                key("Ctrl")
                key("Shift")
                key("Space")
            }
            contentDescription {
                "Switch to previous language, Press key Ctrl plus Shift plus Space"
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

    val recentAppsGroup =
        KeyboardShortcutGroup(
            "Recent apps",
            listOf(recentAppsCycleForwardShortcutInfo, recentAppsCycleBackShortcutInfo),
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

    private val recentAppsSubCategory =
        ShortcutSubCategory(
            recentAppsGroup.label!!.toString(),
            listOf(recentAppsCycleForwardShortcut, recentAppsCycleBackShortcut),
        )

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
                        contentDescription {
                            "Go to home screen, Press key Ctrl plus Alt plus B, " +
                                "or Ctrl plus Alt plus A"
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

    val multitaskingCategoryWithRecentAppsGroup =
        ShortcutCategory(type = MultiTasking, subCategories = listOf(recentAppsSubCategory))

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

    val groupWithDifferentSizeOfShortcutKeys =
        KeyboardShortcutGroup(
            "Group with different size of shortcut keys",
            listOf(
                ShortcutsWithDiffSizeOfKeys3,
                ShortcutsWithDiffSizeOfKeys,
                ShortcutsWithDiffSizeOfKeys2,
            ),
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
        includeInCustomization: Boolean = true,
    ): ShortcutCategory {
        return ShortcutCategory(
            type = category,
            subCategories =
                listOf(
                    ShortcutSubCategory(
                        label = subcategoryLabel,
                        shortcuts = listOf(simpleShortcut(shortcutLabel, includeInCustomization)),
                    )
                ),
        )
    }

    private fun simpleShortcut(label: String, includeInCustomization: Boolean = true) =
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
            isCustomizable = includeInCustomization,
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
                "Switch to full screen",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Use split screen with app on the left",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Use split screen with app on the right",
            ),
            simpleShortcutCategory(System, "System controls", "Show shortcuts"),
            simpleShortcutCategory(System, "System controls", "View recent apps"),
        )
    val customInputGestureTypeHome = simpleInputGestureData(keyGestureType = KEY_GESTURE_TYPE_HOME)

    val allCustomizableInputGesturesWithSimpleShortcutCombinations =
        listOf(
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT
            ),
            simpleInputGestureData(keyGestureType = KEY_GESTURE_TYPE_HOME),
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
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER
            ),
        )

    val allAppsShortcutAddRequest =
        ShortcutCustomizationRequestInfo.Add(
            label = "Open apps list",
            categoryType = System,
            subCategoryLabel = "System controls",
        )

    val allAppsShortcutDeleteRequest =
        ShortcutCustomizationRequestInfo.Delete(
            label = "Open apps list",
            categoryType = System,
            subCategoryLabel = "System controls",
        )

    val standardKeyCombination =
        KeyCombination(
            modifiers = META_META_ON or META_SHIFT_ON or META_META_LEFT_ON or META_SHIFT_RIGHT_ON,
            keyCode = KEYCODE_A,
        )

    const val ALL_SUPPORTED_MODIFIERS =
        META_META_ON or
            META_CTRL_ON or
            META_FUNCTION_ON or
            META_SHIFT_ON or
            META_ALT_ON or
            META_SYM_ON

    val allAppsInputGestureData: InputGestureData =
        InputGestureData.Builder()
            .setKeyGestureType(KEY_GESTURE_TYPE_ALL_APPS)
            .setTrigger(
                createKeyTrigger(
                    /* keycode = */ standardKeyCombination.keyCode!!,
                    /* modifierState = */ standardKeyCombination.modifiers and
                        ALL_SUPPORTED_MODIFIERS,
                )
            )
            .build()

    val goHomeInputGestureData: InputGestureData =
        InputGestureData.Builder()
            .setKeyGestureType(KEY_GESTURE_TYPE_HOME)
            .setTrigger(
                createKeyTrigger(
                    /* keycode = */ standardKeyCombination.keyCode!!,
                    /* modifierState = */ standardKeyCombination.modifiers and
                        ALL_SUPPORTED_MODIFIERS,
                )
            )
            .build()

    val allAppsShortcutCategory =
        shortcutCategory(System) {
            subCategory("System controls") {
                shortcut("Open apps list") {
                    command {
                        isCustom(true)
                        key(ShortcutHelperKeys.metaModifierIconResId)
                        key("Shift")
                        key("A")
                    }
                }
            }
        }

    val keyDownEventWithoutActionKeyPressed =
        androidx.compose.ui.input.key.KeyEvent(
            android.view.KeyEvent(
                /* downTime = */ SystemClock.uptimeMillis(),
                /* eventTime = */ SystemClock.uptimeMillis(),
                /* action = */ ACTION_DOWN,
                /* code = */ KEYCODE_A,
                /* repeat = */ 0,
                /* metaState = */ META_CTRL_ON,
            )
        )

    val keyDownEventWithActionKeyPressed =
        androidx.compose.ui.input.key.KeyEvent(
            android.view.KeyEvent(
                /* downTime = */ SystemClock.uptimeMillis(),
                /* eventTime = */ SystemClock.uptimeMillis(),
                /* action = */ ACTION_DOWN,
                /* code = */ KEYCODE_A,
                /* repeat = */ 0,
                /* metaState = */ META_CTRL_ON or META_META_ON,
            )
        )

    val keyUpEventWithActionKeyPressed =
        androidx.compose.ui.input.key.KeyEvent(
            android.view.KeyEvent(
                /* downTime = */ SystemClock.uptimeMillis(),
                /* eventTime = */ SystemClock.uptimeMillis(),
                /* action = */ ACTION_DOWN,
                /* code = */ KEYCODE_A,
                /* repeat = */ 0,
                /* metaState = */ 0,
            )
        )

    val standardAddShortcutRequest =
        ShortcutCustomizationRequestInfo.Add(
            label = "Standard shortcut",
            categoryType = System,
            subCategoryLabel = "Standard subcategory",
        )
}
