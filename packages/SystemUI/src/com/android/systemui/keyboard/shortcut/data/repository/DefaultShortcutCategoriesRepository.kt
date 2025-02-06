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

import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.qualifiers.AccessibilityShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.AppCategoriesShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.CurrentAppShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.InputShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.MultitaskingShortcuts
import com.android.systemui.keyboard.shortcut.qualifiers.SystemShortcuts
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.Accessibility
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.AppCategories
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.CurrentApp
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.InputMethodEditor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class DefaultShortcutCategoriesRepository
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    @SystemShortcuts systemShortcutsSource: KeyboardShortcutGroupsSource,
    @MultitaskingShortcuts multitaskingShortcutsSource: KeyboardShortcutGroupsSource,
    @AppCategoriesShortcuts appCategoriesShortcutsSource: KeyboardShortcutGroupsSource,
    @InputShortcuts inputShortcutsSource: KeyboardShortcutGroupsSource,
    @CurrentAppShortcuts currentAppShortcutsSource: KeyboardShortcutGroupsSource,
    @AccessibilityShortcuts accessibilityShortcutsSource: KeyboardShortcutGroupsSource,
    inputDeviceRepository: ShortcutHelperInputDeviceRepository,
    shortcutCategoriesUtils: ShortcutCategoriesUtils,
) : ShortcutCategoriesRepository {

    private val sources =
        listOf(
            InternalGroupsSource(source = systemShortcutsSource, typeProvider = { System }),
            InternalGroupsSource(
                source = multitaskingShortcutsSource,
                typeProvider = { MultiTasking },
            ),
            InternalGroupsSource(
                source = appCategoriesShortcutsSource,
                typeProvider = { AppCategories },
            ),
            InternalGroupsSource(
                source = inputShortcutsSource,
                typeProvider = { InputMethodEditor },
            ),
            InternalGroupsSource(
                source = accessibilityShortcutsSource,
                typeProvider = { Accessibility },
            ),
            InternalGroupsSource(
                source = currentAppShortcutsSource,
                typeProvider = { groups -> getCurrentAppShortcutCategoryType(groups) },
            ),
        )

    override val categories: Flow<List<ShortcutCategory>> =
        inputDeviceRepository.activeInputDevice
            .map { inputDevice ->
                if (inputDevice == null) {
                    return@map emptyList()
                }
                val groupsFromAllSources =
                    sources.map {
                        toInternalKeyboardShortcutGroups(it.source.shortcutGroups(inputDevice.id))
                    }
                val supportedKeyCodes =
                    shortcutCategoriesUtils.fetchSupportedKeyCodes(
                        inputDevice.id,
                        groupsFromAllSources,
                    )
                return@map sources.mapIndexedNotNull { index, internalGroupsSource ->
                    shortcutCategoriesUtils.fetchShortcutCategory(
                        internalGroupsSource.typeProvider(groupsFromAllSources[index]),
                        groupsFromAllSources[index],
                        inputDevice,
                        supportedKeyCodes,
                    )
                }
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.Lazily,
                initialValue = emptyList(),
            )

    private fun toInternalKeyboardShortcutGroups(
        keyboardShortcutGroups: List<KeyboardShortcutGroup>
    ): List<InternalKeyboardShortcutGroup> {
        return keyboardShortcutGroups.map { group ->
            InternalKeyboardShortcutGroup(
                label = group.label.toString(),
                items = group.items.map { toInternalKeyboardShortcutInfo(it) },
                packageName = group.packageName?.toString(),
            )
        }
    }

    private fun toInternalKeyboardShortcutInfo(
        keyboardShortcutInfo: KeyboardShortcutInfo
    ): InternalKeyboardShortcutInfo {
        return InternalKeyboardShortcutInfo(
            label = keyboardShortcutInfo.label!!.toString(),
            keycode = keyboardShortcutInfo.keycode,
            modifiers = keyboardShortcutInfo.modifiers,
            baseCharacter = keyboardShortcutInfo.baseCharacter,
            icon = keyboardShortcutInfo.icon,
        )
    }

    private fun getCurrentAppShortcutCategoryType(
        shortcutGroups: List<InternalKeyboardShortcutGroup>
    ): ShortcutCategoryType? {
        val packageName = shortcutGroups.firstOrNull()?.packageName ?: return null
        return CurrentApp(packageName)
    }

    private class InternalGroupsSource(
        val source: KeyboardShortcutGroupsSource,
        val typeProvider: (groups: List<InternalKeyboardShortcutGroup>) -> ShortcutCategoryType?,
    )
}
