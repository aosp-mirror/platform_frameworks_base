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
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.WindowManager
import android.view.WindowManager.KeyboardShortcutsReceiver
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.qualifiers.MultitaskingShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.SystemShortcuts
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.IME
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MULTI_TASKING
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.SYSTEM
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@SysUISingleton
class ShortcutHelperCategoriesRepository
@Inject
constructor(
    private val context: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @SystemShortcuts private val systemShortcutsSource: KeyboardShortcutGroupsSource,
    @MultitaskingShortcuts private val multitaskingShortcutsSource: KeyboardShortcutGroupsSource,
    private val windowManager: WindowManager,
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
                    SYSTEM,
                    systemShortcutsSource.shortcutGroups()
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
                    MULTI_TASKING,
                    multitaskingShortcutsSource.shortcutGroups()
                )
            } else {
                null
            }
        }

    val imeShortcutsCategory =
        activeInputDevice.map { if (it != null) retrieveImeShortcuts(it) else null }

    private suspend fun retrieveImeShortcuts(
        inputDevice: InputDevice,
    ): ShortcutCategory? {
        return suspendCancellableCoroutine { continuation ->
            val shortcutsReceiver = KeyboardShortcutsReceiver { shortcutGroups ->
                continuation.resumeWith(
                    Result.success(
                        toShortcutCategory(inputDevice.keyCharacterMap, IME, shortcutGroups)
                    )
                )
            }
            windowManager.requestImeKeyboardShortcuts(shortcutsReceiver, inputDevice.id)
        }
    }

    private fun toShortcutCategory(
        keyCharacterMap: KeyCharacterMap,
        type: ShortcutCategoryType,
        shortcutGroups: List<KeyboardShortcutGroup>,
    ): ShortcutCategory? {
        val subCategories =
            shortcutGroups
                .map { shortcutGroup ->
                    ShortcutSubCategory(
                        shortcutGroup.label.toString(),
                        toShortcuts(keyCharacterMap, shortcutGroup.items)
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

    private fun toShortcuts(
        keyCharacterMap: KeyCharacterMap,
        infoList: List<KeyboardShortcutInfo>
    ) = infoList.mapNotNull { toShortcut(keyCharacterMap, it) }

    private fun toShortcut(
        keyCharacterMap: KeyCharacterMap,
        shortcutInfo: KeyboardShortcutInfo
    ): Shortcut? {
        val shortcutCommand = toShortcutCommand(keyCharacterMap, shortcutInfo)
        return if (shortcutCommand == null) null
        else Shortcut(label = shortcutInfo.label!!.toString(), commands = listOf(shortcutCommand))
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
