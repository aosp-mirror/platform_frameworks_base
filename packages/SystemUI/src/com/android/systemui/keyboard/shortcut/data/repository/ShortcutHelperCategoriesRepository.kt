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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.graphics.drawable.Icon
import android.hardware.input.InputManager
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.qualifiers.AppCategoriesShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.CurrentAppShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.InputShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.MultitaskingShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.SystemShortcuts
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.AppCategories
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.CurrentApp
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.InputMethodEditor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutIcon
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@SysUISingleton
class ShortcutHelperCategoriesRepository
@Inject
constructor(
    private val context: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @SystemShortcuts private val systemShortcutsSource: KeyboardShortcutGroupsSource,
    @MultitaskingShortcuts private val multitaskingShortcutsSource: KeyboardShortcutGroupsSource,
    @AppCategoriesShortcuts private val appCategoriesShortcutsSource: KeyboardShortcutGroupsSource,
    @InputShortcuts private val inputShortcutsSource: KeyboardShortcutGroupsSource,
    @CurrentAppShortcuts private val currentAppShortcutsSource: KeyboardShortcutGroupsSource,
    private val inputManager: InputManager,
    stateRepository: ShortcutHelperStateRepository
) {

    private val activeInputDevice =
        stateRepository.state.map {
            if (it is Active) {
                withContext(backgroundDispatcher) { inputManager.getInputDevice(it.deviceId) }
            } else {
                null
            }
        }

    val systemShortcutsCategory =
        activeInputDevice.map {
            if (it != null) {
                toShortcutCategory(
                    it.keyCharacterMap,
                    System,
                    systemShortcutsSource.shortcutGroups(it.id),
                    keepIcons = true,
                )
            } else {
                null
            }
        }

    val multitaskingShortcutsCategory =
        activeInputDevice.map {
            if (it != null) {
                toShortcutCategory(
                    it.keyCharacterMap,
                    MultiTasking,
                    multitaskingShortcutsSource.shortcutGroups(it.id),
                    keepIcons = true,
                )
            } else {
                null
            }
        }

    val appCategoriesShortcutsCategory =
        activeInputDevice.map {
            if (it != null) {
                toShortcutCategory(
                    it.keyCharacterMap,
                    AppCategories,
                    appCategoriesShortcutsSource.shortcutGroups(it.id),
                    keepIcons = true,
                )
            } else {
                null
            }
        }

    val imeShortcutsCategory =
        activeInputDevice.map {
            if (it != null) {
                toShortcutCategory(
                    it.keyCharacterMap,
                    InputMethodEditor,
                    inputShortcutsSource.shortcutGroups(it.id),
                    keepIcons = false,
                )
            } else {
                null
            }
        }

    val currentAppShortcutsCategory: Flow<ShortcutCategory?> =
        activeInputDevice.map {
            if (it != null) {
                val shortcutGroups = currentAppShortcutsSource.shortcutGroups(it.id)
                val categoryType = getCurrentAppShortcutCategoryType(shortcutGroups)
                if (categoryType == null) {
                    null
                } else {
                    toShortcutCategory(
                        it.keyCharacterMap,
                        categoryType,
                        shortcutGroups,
                        keepIcons = false
                    )
                }
            } else {
                null
            }
        }

    private fun toShortcutCategory(
        keyCharacterMap: KeyCharacterMap,
        type: ShortcutCategoryType,
        shortcutGroups: List<KeyboardShortcutGroup>,
        keepIcons: Boolean,
    ): ShortcutCategory? {
        val subCategories =
            shortcutGroups
                .map { shortcutGroup ->
                    ShortcutSubCategory(
                        shortcutGroup.label.toString(),
                        toShortcuts(keyCharacterMap, shortcutGroup.items, keepIcons)
                    )
                }
                .filter { it.shortcuts.isNotEmpty() }
        return if (subCategories.isEmpty()) {
            Log.wtf(TAG, "Empty sub categories after converting $shortcutGroups")
            null
        } else {
            ShortcutCategory(type, subCategories)
        }
    }

    private fun getCurrentAppShortcutCategoryType(
        shortcutGroups: List<KeyboardShortcutGroup>
    ): ShortcutCategoryType? {
        return if (shortcutGroups.isEmpty()) {
            null
        } else {
            CurrentApp(packageName = shortcutGroups[0].packageName.toString())
        }
    }

    private fun toShortcuts(
        keyCharacterMap: KeyCharacterMap,
        infoList: List<KeyboardShortcutInfo>,
        keepIcons: Boolean,
    ) = infoList.mapNotNull { toShortcut(keyCharacterMap, it, keepIcons) }

    private fun toShortcut(
        keyCharacterMap: KeyCharacterMap,
        shortcutInfo: KeyboardShortcutInfo,
        keepIcon: Boolean,
    ): Shortcut? {
        val shortcutCommand = toShortcutCommand(keyCharacterMap, shortcutInfo) ?: return null
        return Shortcut(
            label = shortcutInfo.label!!.toString(),
            icon = toShortcutIcon(keepIcon, shortcutInfo),
            commands = listOf(shortcutCommand)
        )
    }

    private fun toShortcutIcon(
        keepIcon: Boolean,
        shortcutInfo: KeyboardShortcutInfo
    ): ShortcutIcon? {
        if (!keepIcon) {
            return null
        }
        val icon = shortcutInfo.icon ?: return null
        // For now only keep icons of type resource, which is what the "default apps" shortcuts
        // provide.
        if (icon.type != Icon.TYPE_RESOURCE || icon.resPackage.isNullOrEmpty() || icon.resId <= 0) {
            return null
        }
        return ShortcutIcon(packageName = icon.resPackage, resourceId = icon.resId)
    }

    private fun toShortcutCommand(
        keyCharacterMap: KeyCharacterMap,
        info: KeyboardShortcutInfo
    ): ShortcutCommand? {
        val keys = mutableListOf<ShortcutKey>()
        var remainingModifiers = info.modifiers
        SUPPORTED_MODIFIERS.forEach { supportedModifier ->
            if ((supportedModifier and remainingModifiers) != 0) {
                keys += toShortcutKey(keyCharacterMap, supportedModifier) ?: return null
                // "Remove" the modifier from the remaining modifiers
                remainingModifiers = remainingModifiers and supportedModifier.inv()
            }
        }
        if (remainingModifiers != 0) {
            // There is a remaining modifier we don't support
            Log.wtf(TAG, "Unsupported modifiers remaining: $remainingModifiers")
            return null
        }
        if (info.keycode != 0) {
            keys += toShortcutKey(keyCharacterMap, info.keycode, info.baseCharacter) ?: return null
        }
        if (keys.isEmpty()) {
            Log.wtf(TAG, "No keys for $info")
            return null
        }
        return ShortcutCommand(keys)
    }

    private fun toShortcutKey(
        keyCharacterMap: KeyCharacterMap,
        keyCode: Int,
        baseCharacter: Char = Char.MIN_VALUE,
    ): ShortcutKey? {
        val iconResId = ShortcutHelperKeys.keyIcons[keyCode]
        if (iconResId != null) {
            return ShortcutKey.Icon(iconResId)
        }
        if (baseCharacter > Char.MIN_VALUE) {
            return ShortcutKey.Text(baseCharacter.toString())
        }
        val specialKeyLabel = ShortcutHelperKeys.specialKeyLabels[keyCode]
        if (specialKeyLabel != null) {
            val label = specialKeyLabel(context)
            return ShortcutKey.Text(label)
        }
        val displayLabelCharacter = keyCharacterMap.getDisplayLabel(keyCode)
        if (displayLabelCharacter.code != 0) {
            return ShortcutKey.Text(displayLabelCharacter.toString())
        }
        Log.wtf(TAG, "Couldn't find label or icon for key: $keyCode")
        return null
    }

    companion object {
        private const val TAG = "SHCategoriesRepo"

        private val SUPPORTED_MODIFIERS =
            listOf(
                KeyEvent.META_META_ON,
                KeyEvent.META_CTRL_ON,
                KeyEvent.META_ALT_ON,
                KeyEvent.META_SHIFT_ON,
                KeyEvent.META_SYM_ON,
                KeyEvent.META_FUNCTION_ON
            )
    }
}
