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

package com.android.systemui.keyboard.shortcut.domain.interactor

import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult
import com.android.systemui.keyboard.shortcut.data.repository.CustomShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperKeys
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import javax.inject.Inject

class ShortcutCustomizationInteractor
@Inject
constructor(private val customShortcutRepository: CustomShortcutCategoriesRepository) {
    val pressedKeys = customShortcutRepository.pressedKeys

    fun updateUserSelectedKeyCombination(keyCombination: KeyCombination?) {
        customShortcutRepository.updateUserKeyCombination(keyCombination)
    }

    fun getDefaultCustomShortcutModifierKey(): ShortcutKey.Icon.ResIdIcon {
        return ShortcutKey.Icon.ResIdIcon(ShortcutHelperKeys.metaModifierIconResId)
    }

    fun onCustomizationRequested(requestInfo: ShortcutCustomizationRequestInfo?) {
        customShortcutRepository.onCustomizationRequested(requestInfo)
    }

    suspend fun confirmAndSetShortcutCurrentlyBeingCustomized():
        ShortcutCustomizationRequestResult {
        return customShortcutRepository.confirmAndSetShortcutCurrentlyBeingCustomized()
    }

    suspend fun deleteShortcutCurrentlyBeingCustomized(): ShortcutCustomizationRequestResult {
        return customShortcutRepository.deleteShortcutCurrentlyBeingCustomized()
    }

    suspend fun resetAllCustomShortcuts(): ShortcutCustomizationRequestResult {
        return customShortcutRepository.resetAllCustomShortcuts()
    }
}
