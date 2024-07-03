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

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.WindowManager
import android.view.WindowManager.KeyboardShortcutsReceiver
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.qualifiers.MultitaskingShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.SystemShortcuts
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.IME
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MULTI_TASKING
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

@SysUISingleton
class ShortcutHelperCategoriesRepository
@Inject
constructor(
    @SystemShortcuts private val systemShortcutsSource: KeyboardShortcutGroupsSource,
    @MultitaskingShortcuts private val multitaskingShortcutsSource: KeyboardShortcutGroupsSource,
    private val windowManager: WindowManager,
    shortcutHelperStateRepository: ShortcutHelperStateRepository
) {

    val systemShortcutsCategory =
        shortcutHelperStateRepository.state.map {
            if (it is Active) {
                toShortcutCategory(
                    systemShortcutsSource.shortcutGroups(),
                    ShortcutCategoryType.SYSTEM
                )
            } else {
                null
            }
        }

    val multitaskingShortcutsCategory =
        shortcutHelperStateRepository.state.map {
            if (it is Active) {
                toShortcutCategory(multitaskingShortcutsSource.shortcutGroups(), MULTI_TASKING)
            } else {
                null
            }
        }

    val imeShortcutsCategory =
        shortcutHelperStateRepository.state.map {
            if (it is Active) retrieveImeShortcuts(it.deviceId) else null
        }

    private suspend fun retrieveImeShortcuts(deviceId: Int): ShortcutCategory? {
        return suspendCancellableCoroutine { continuation ->
            val shortcutsReceiver = KeyboardShortcutsReceiver { shortcutGroups ->
                continuation.resumeWith(Result.success(toShortcutCategory(shortcutGroups, IME)))
            }
            windowManager.requestImeKeyboardShortcuts(shortcutsReceiver, deviceId)
        }
    }

    private fun toShortcutCategory(
        shortcutGroups: List<KeyboardShortcutGroup>,
        type: ShortcutCategoryType,
    ): ShortcutCategory? {
        val subCategories =
            shortcutGroups
                .map { shortcutGroup ->
                    ShortcutSubCategory(
                        label = shortcutGroup.label.toString(),
                        shortcuts = toShortcuts(shortcutGroup.items)
                    )
                }
                .filter { it.shortcuts.isNotEmpty() }
        return if (subCategories.isEmpty()) {
            null
        } else {
            ShortcutCategory(type, subCategories)
        }
    }

    private fun toShortcuts(infoList: List<KeyboardShortcutInfo>) =
        infoList.mapNotNull { toShortcut(it) }

    private fun toShortcut(shortcutInfo: KeyboardShortcutInfo): Shortcut? {
        val shortcutCommand = toShortcutCommand(shortcutInfo)
        return if (shortcutCommand == null) null
        else Shortcut(label = shortcutInfo.label!!.toString(), commands = listOf(shortcutCommand))
    }

    private fun toShortcutCommand(info: KeyboardShortcutInfo): ShortcutCommand? {
        val keyCodes = mutableListOf<Int>()
        var remainingModifiers = info.modifiers
        SUPPORTED_MODIFIERS.forEach { supportedModifier ->
            if ((supportedModifier and remainingModifiers) != 0) {
                keyCodes += supportedModifier
                // "Remove" the modifier from the remaining modifiers
                remainingModifiers = remainingModifiers and supportedModifier.inv()
            }
        }
        if (remainingModifiers != 0) {
            // There is a remaining modifier we don't support
            return null
        }
        keyCodes += info.keycode
        return ShortcutCommand(keyCodes)
    }

    companion object {
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
