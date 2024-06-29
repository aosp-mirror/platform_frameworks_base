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
import com.android.systemui.keyboard.shortcut.data.source.MultitaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.SystemShortcutsSource
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.shortcutCategory
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

@SysUISingleton
class ShortcutHelperCategoriesRepository
@Inject
constructor(
    private val systemShortcutsSource: SystemShortcutsSource,
    private val multitaskingShortcutsSource: MultitaskingShortcutsSource,
    private val windowManager: WindowManager,
    shortcutHelperStateRepository: ShortcutHelperStateRepository
) {

    val systemShortcutsCategory =
        shortcutHelperStateRepository.state.map {
            if (it is Active) systemShortcutsSource.systemShortcutsCategory() else null
        }

    val multitaskingShortcutsCategory =
        shortcutHelperStateRepository.state.map {
            if (it is Active) multitaskingShortcutsSource.multitaskingShortcutCategory() else null
        }

    val imeShortcutsCategory =
        shortcutHelperStateRepository.state.map {
            if (it is Active) retrieveImeShortcuts(it.deviceId) else null
        }

    private suspend fun retrieveImeShortcuts(deviceId: Int): ShortcutCategory {
        return suspendCancellableCoroutine { continuation ->
            val shortcutsReceiver = KeyboardShortcutsReceiver { shortcutGroups ->
                continuation.resumeWith(Result.success(toShortcutCategory(shortcutGroups)))
            }
            windowManager.requestImeKeyboardShortcuts(shortcutsReceiver, deviceId)
        }
    }

    private fun toShortcutCategory(shortcutGroups: List<KeyboardShortcutGroup>) =
        shortcutCategory(ShortcutCategoryType.IME) {
            shortcutGroups.map { shortcutGroup ->
                subCategory(shortcutGroup.label.toString(), toShortcuts(shortcutGroup.items))
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
