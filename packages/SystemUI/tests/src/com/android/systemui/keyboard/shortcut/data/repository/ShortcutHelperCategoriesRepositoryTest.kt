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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.shortcutCategory
import com.android.systemui.keyboard.shortcut.shortcutHelperCategoriesRepository
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperCategoriesRepositoryTest : SysuiTestCase() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val kosmos = testKosmos().also { it.testDispatcher = UnconfinedTestDispatcher() }
    private val repo = kosmos.shortcutHelperCategoriesRepository
    private val helper = kosmos.shortcutHelperTestHelper
    private val testScope = kosmos.testScope

    @Test
    fun stateActive_imeShortcuts_shortcutInfoCorrectlyConverted() =
        testScope.runTest {
            helper.setImeShortcuts(imeShortcutsGroupWithPreviousLanguageSwitchShortcut)
            val imeShortcutCategory by collectLastValue(repo.imeShortcutsCategory)

            helper.showFromActivity()

            assertThat(imeShortcutCategory)
                .isEqualTo(expectedImeShortcutCategoryWithPreviousLanguageSwitchShortcut)
        }

    @Test
    fun stateActive_imeShortcuts_discardUnsupportedShortcutInfoModifiers() =
        testScope.runTest {
            helper.setImeShortcuts(imeShortcutsGroupWithUnsupportedShortcutModifiers)
            val imeShortcutCategory by collectLastValue(repo.imeShortcutsCategory)

            helper.showFromActivity()

            assertThat(imeShortcutCategory)
                .isEqualTo(expectedImeShortcutCategoryWithDiscardedUnsupportedShortcuts)
        }

    private val switchToPreviousLanguageCommand =
        ShortcutCommand(
            listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON, KeyEvent.KEYCODE_SPACE)
        )

    private val expectedImeShortcutCategoryWithDiscardedUnsupportedShortcuts =
        shortcutCategory(ShortcutCategoryType.IME) { subCategory("input", emptyList()) }

    private val switchToPreviousLanguageKeyboardShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ "switch to previous language",
            /* keycode = */ switchToPreviousLanguageCommand.keyCodes[2],
            /* modifiers = */ switchToPreviousLanguageCommand.keyCodes[0] or
                switchToPreviousLanguageCommand.keyCodes[1],
        )

    private val expectedImeShortcutCategoryWithPreviousLanguageSwitchShortcut =
        shortcutCategory(ShortcutCategoryType.IME) {
            subCategory(
                "input",
                listOf(
                    Shortcut(
                        switchToPreviousLanguageKeyboardShortcutInfo.label!!.toString(),
                        listOf(switchToPreviousLanguageCommand)
                    )
                )
            )
        }

    private val imeShortcutsGroupWithPreviousLanguageSwitchShortcut =
        listOf(
            KeyboardShortcutGroup(
                "input",
                listOf(
                    switchToPreviousLanguageKeyboardShortcutInfo,
                )
            )
        )

    private val shortcutInfoWithUnsupportedModifier =
        KeyboardShortcutInfo(
            /* label = */ "unsupported shortcut",
            /* keycode = */ KeyEvent.KEYCODE_SPACE,
            /* modifiers = */ 32
        )

    private val imeShortcutsGroupWithUnsupportedShortcutModifiers =
        listOf(
            KeyboardShortcutGroup(
                "input",
                listOf(
                    shortcutInfoWithUnsupportedModifier,
                )
            )
        )
}
