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
import android.hardware.input.KeyGlyphMap
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyEvent.META_META_ON
import com.android.systemui.Flags.shortcutHelperKeyGlyph
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperExclusions
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutIcon
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

class ShortcutCategoriesUtils
@Inject
constructor(
    private val context: Context,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    private val inputManager: InputManager,
    private val shortcutHelperExclusions: ShortcutHelperExclusions,
) {

    fun removeUnsupportedModifiers(modifierMask: Int): Int {
        return SUPPORTED_MODIFIERS.reduce { acc, modifier -> acc or modifier } and modifierMask
    }

    fun fetchShortcutCategory(
        type: ShortcutCategoryType?,
        groups: List<InternalKeyboardShortcutGroup>,
        inputDevice: InputDevice,
        supportedKeyCodes: Set<Int>,
    ): ShortcutCategory? {
        return if (type == null) {
            null
        } else {
            val keyGlyphMap =
                if (shortcutHelperKeyGlyph()) inputManager.getKeyGlyphMap(inputDevice.id) else null
            toShortcutCategory(
                keyGlyphMap,
                inputDevice.keyCharacterMap,
                type,
                groups,
                type.isTrusted,
                supportedKeyCodes,
            )
        }
    }

    private fun toShortcutCategory(
        keyGlyphMap: KeyGlyphMap?,
        keyCharacterMap: KeyCharacterMap,
        type: ShortcutCategoryType,
        shortcutGroups: List<InternalKeyboardShortcutGroup>,
        keepIcons: Boolean,
        supportedKeyCodes: Set<Int>,
    ): ShortcutCategory? {
        val subCategories =
            shortcutGroups
                .map { shortcutGroup ->
                    ShortcutSubCategory(
                        shortcutGroup.label,
                        toShortcuts(
                            keyGlyphMap,
                            keyCharacterMap,
                            shortcutGroup.items,
                            keepIcons,
                            supportedKeyCodes,
                        ),
                    )
                }
                .filter { it.shortcuts.isNotEmpty() }
        return if (subCategories.isEmpty()) {
            Log.w(TAG, "Empty sub categories after converting $shortcutGroups")
            null
        } else {
            ShortcutCategory(type, subCategories)
        }
    }

    private fun toShortcuts(
        keyGlyphMap: KeyGlyphMap?,
        keyCharacterMap: KeyCharacterMap,
        infoList: List<InternalKeyboardShortcutInfo>,
        keepIcons: Boolean,
        supportedKeyCodes: Set<Int>,
    ) =
        infoList
            .filter {
                // Allow KEYCODE_UNKNOWN (0) because shortcuts can have just modifiers and no
                // keycode, or they could have a baseCharacter instead of a keycode.
                it.keycode == KeyEvent.KEYCODE_UNKNOWN ||
                    supportedKeyCodes.contains(it.keycode) ||
                    // Support keyboard function row key codes
                    keyGlyphMap?.functionRowKeys?.contains(it.keycode) ?: false
            }
            .mapNotNull { toShortcut(keyGlyphMap, keyCharacterMap, it, keepIcons) }

    private fun toShortcut(
        keyGlyphMap: KeyGlyphMap?,
        keyCharacterMap: KeyCharacterMap,
        shortcutInfo: InternalKeyboardShortcutInfo,
        keepIcon: Boolean,
    ): Shortcut? {
        val shortcutCommand =
            toShortcutCommand(keyGlyphMap, keyCharacterMap, shortcutInfo) ?: return null
        return Shortcut(
            label = shortcutInfo.label,
            icon = toShortcutIcon(keepIcon, shortcutInfo),
            commands = listOf(shortcutCommand),
            isCustomizable =
                shortcutHelperExclusions.isShortcutCustomizable(shortcutInfo.label),
        )
    }

    private fun toShortcutIcon(
        keepIcon: Boolean,
        shortcutInfo: InternalKeyboardShortcutInfo,
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
        keyGlyphMap: KeyGlyphMap?,
        keyCharacterMap: KeyCharacterMap,
        info: InternalKeyboardShortcutInfo,
    ): ShortcutCommand? {
        val keys = mutableListOf<ShortcutKey>()
        var remainingModifiers = info.modifiers
        SUPPORTED_MODIFIERS.forEach { supportedModifier ->
            if ((supportedModifier and remainingModifiers) != 0) {
                keys += toShortcutModifierKey(keyGlyphMap, supportedModifier) ?: return null
                // "Remove" the modifier from the remaining modifiers
                remainingModifiers = remainingModifiers and supportedModifier.inv()
            }
        }
        if (remainingModifiers != 0) {
            // There is a remaining modifier we don't support
            Log.w(TAG, "Unsupported modifiers remaining: $remainingModifiers")
            return null
        }
        if (info.keycode != 0 || info.baseCharacter > Char.MIN_VALUE) {
            keys +=
                toShortcutKey(keyGlyphMap, keyCharacterMap, info.keycode, info.baseCharacter)
                    ?: return null
        }
        if (keys.isEmpty()) {
            Log.w(TAG, "No keys for $info")
            return null
        }
        return ShortcutCommand(keys = keys, isCustom = info.isCustomShortcut)
    }

    fun toShortcutModifierKeys(modifiers: Int, keyGlyphMap: KeyGlyphMap?): List<ShortcutKey>? {
        val keys: MutableList<ShortcutKey> = mutableListOf()
        var remainingModifiers = modifiers
        SUPPORTED_MODIFIERS.forEach { supportedModifier ->
            if ((supportedModifier and remainingModifiers) != 0) {
                keys += toShortcutModifierKey(keyGlyphMap, supportedModifier) ?: return null
                remainingModifiers = remainingModifiers and supportedModifier.inv()
            }
        }
        return keys
    }

    private fun toShortcutModifierKey(keyGlyphMap: KeyGlyphMap?, modifierMask: Int): ShortcutKey? {
        val modifierDrawable = keyGlyphMap?.getDrawableForModifierState(context, modifierMask)
        if (modifierDrawable != null) {
            return ShortcutKey.Icon.DrawableIcon(drawable = modifierDrawable)
        }

        if (modifierMask == META_META_ON) {
            return ShortcutKey.Icon.ResIdIcon(ShortcutHelperKeys.metaModifierIconResId)
        }

        val modifierLabel = ShortcutHelperKeys.modifierLabels[modifierMask]
        if (modifierLabel != null) {
            return ShortcutKey.Text(modifierLabel(context))
        }
        Log.wtf("TAG", "Couldn't find label or icon for modifier $modifierMask")
        return null
    }

    fun toShortcutKey(
        keyGlyphMap: KeyGlyphMap?,
        keyCharacterMap: KeyCharacterMap,
        keyCode: Int,
        baseCharacter: Char = Char.MIN_VALUE,
    ): ShortcutKey? {
        val keycodeDrawable = keyGlyphMap?.getDrawableForKeycode(context, keyCode)
        if (keycodeDrawable != null) {
            return ShortcutKey.Icon.DrawableIcon(drawable = keycodeDrawable)
        }

        val iconResId = ShortcutHelperKeys.keyIcons[keyCode]
        if (iconResId != null) {
            return ShortcutKey.Icon.ResIdIcon(iconResId)
        }
        if (baseCharacter > Char.MIN_VALUE) {
            return ShortcutKey.Text(baseCharacter.uppercase())
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
        Log.w(TAG, "Couldn't find label or icon for key: $keyCode")
        return null
    }

    suspend fun fetchSupportedKeyCodes(
        deviceId: Int,
        groupsFromAllSources: List<List<InternalKeyboardShortcutGroup>>,
    ): Set<Int> =
        withContext(backgroundCoroutineContext) {
            val allUsedKeyCodes =
                groupsFromAllSources
                    .flatMap { groups -> groups.flatMap { group -> group.items } }
                    .map { info -> info.keycode }
                    .distinct()
            val keyCodesSupported =
                inputManager.deviceHasKeys(deviceId, allUsedKeyCodes.toIntArray())
            return@withContext allUsedKeyCodes
                .filterIndexed { index, _ -> keyCodesSupported[index] }
                .toSet()
        }

    companion object {
        private const val TAG = "ShortcutCategoriesUtils"

        private val SUPPORTED_MODIFIERS =
            listOf(
                KeyEvent.META_META_ON,
                KeyEvent.META_CTRL_ON,
                KeyEvent.META_ALT_ON,
                KeyEvent.META_SHIFT_ON,
                KeyEvent.META_SYM_ON,
                KeyEvent.META_FUNCTION_ON,
            )
    }
}
