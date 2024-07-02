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

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.shared.model.shortcut
import com.android.systemui.keyboard.shortcut.shortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.shortcutHelperMultiTaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperSystemShortcutsSource
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
class ShortcutHelperCategoriesInteractorTest : SysuiTestCase() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val kosmos = testKosmos().also { it.testDispatcher = UnconfinedTestDispatcher() }
    private val testScope = kosmos.testScope
    private val interactor = kosmos.shortcutHelperCategoriesInteractor
    private val helper = kosmos.shortcutHelperTestHelper
    private val systemShortcutsSource = kosmos.shortcutHelperSystemShortcutsSource
    private val multitaskingShortcutsSource = kosmos.shortcutHelperMultiTaskingShortcutsSource

    @Test
    fun categories_emptyByDefault() =
        testScope.runTest {
            val categories by collectLastValue(interactor.shortcutCategories)

            assertThat(categories).isEmpty()
        }

    @Test
    fun categories_stateActive_emitsAllCategoriesInOrder() =
        testScope.runTest {
            helper.setImeShortcuts(imeShortcutGroups)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    systemShortcutsSource.systemShortcutsCategory(),
                    multitaskingShortcutsSource.multitaskingShortcutCategory(),
                    imeShortcutCategory
                )
                .inOrder()
        }

    @Test
    fun categories_stateInactiveAfterActive_emitsEmpty() =
        testScope.runTest {
            val categories by collectLastValue(interactor.shortcutCategories)
            helper.showFromActivity()
            helper.hideFromActivity()

            assertThat(categories).isEmpty()
        }

    fun categories_stateActive_emitsGroupedShortcuts() =
        testScope.runTest {
            helper.setImeShortcuts(imeShortcutsGroupsWithDuplicateLabels)
            val categories by collectLastValue(interactor.shortcutCategories)

            helper.showFromActivity()

            assertThat(categories)
                .containsExactly(
                    systemShortcutsSource.systemShortcutsCategory(),
                    multitaskingShortcutsSource.multitaskingShortcutCategory(),
                    expectedGroupedShortcutCategories
                )
        }

    private val switchToNextLanguageShortcut =
        shortcut(label = "switch to next language") {
            command(KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_SPACE)
        }

    private val switchToNextLanguageKeyboardShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ switchToNextLanguageShortcut.label,
            /* keycode = */ switchToNextLanguageShortcut.commands[0].keyCodes[1],
            /* modifiers = */ switchToNextLanguageShortcut.commands[0].keyCodes[0],
        )

    private val switchToNextLanguageShortcutAlternative =
        shortcut("switch to next language") {
            command(KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_SPACE)
        }

    private val switchToNextLanguageKeyboardShortcutInfoAlternative =
        KeyboardShortcutInfo(
            /* label = */ switchToNextLanguageShortcutAlternative.label,
            /* keycode = */ switchToNextLanguageShortcutAlternative.commands[0].keyCodes[1],
            /* modifiers = */ switchToNextLanguageShortcutAlternative.commands[0].keyCodes[0],
        )

    private val switchToPreviousLanguageShortcut =
        shortcut("switch to previous language") {
            command(
                KeyEvent.META_SHIFT_ON,
                KeyEvent.KEYCODE_SPACE,
            )
        }

    private val switchToPreviousLanguageKeyboardShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ switchToPreviousLanguageShortcut.label,
            /* keycode = */ switchToPreviousLanguageShortcut.commands[0].keyCodes[1],
            /* modifiers = */ switchToPreviousLanguageShortcut.commands[0].keyCodes[0],
        )

    private val switchToPreviousLanguageShortcutAlternative =
        shortcut("switch to previous language") {
            command(
                KeyEvent.META_SHIFT_ON,
                KeyEvent.KEYCODE_SPACE,
            )
        }

    private val switchToPreviousLanguageKeyboardShortcutInfoAlternative =
        KeyboardShortcutInfo(
            /* label = */ switchToPreviousLanguageShortcutAlternative.label,
            /* keycode = */ switchToPreviousLanguageShortcutAlternative.commands[0].keyCodes[1],
            /* modifiers = */ switchToPreviousLanguageShortcutAlternative.commands[0].keyCodes[0],
        )

    private val showOnscreenKeyboardShortcut =
        shortcut(label = "Show on-screen keyboard") {
            command(KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_K)
        }

    private val showOnScreenKeyboardShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ showOnscreenKeyboardShortcut.label,
            /* keycode = */ showOnscreenKeyboardShortcut.commands[0].keyCodes[1],
            /* modifiers = */ showOnscreenKeyboardShortcut.commands[0].keyCodes[0],
        )

    private val accessClipboardShortcut =
        shortcut(label = "Access clipboard") { command(KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_V) }

    private val accessClipboardShortcutInfo =
        KeyboardShortcutInfo(
            /* label = */ accessClipboardShortcut.label,
            /* keycode = */ accessClipboardShortcut.commands[0].keyCodes[1],
            /* modifiers = */ accessClipboardShortcut.commands[0].keyCodes[0],
        )

    private val imeShortcutGroups =
        listOf(
            KeyboardShortcutGroup(
                /* label = */ "input",
                /* shortcutInfoList = */ listOf(
                    switchToNextLanguageKeyboardShortcutInfo,
                    switchToPreviousLanguageKeyboardShortcutInfo
                )
            )
        )

    private val imeShortcutCategory =
        ShortcutCategory(
            type = ShortcutCategoryType.IME,
            subCategories =
                listOf(
                    ShortcutSubCategory(
                        imeShortcutGroups[0].label.toString(),
                        listOf(switchToNextLanguageShortcut, switchToPreviousLanguageShortcut)
                    )
                )
        )

    private val imeShortcutsGroupsWithDuplicateLabels =
        listOf(
            KeyboardShortcutGroup(
                "input",
                listOf(
                    switchToNextLanguageKeyboardShortcutInfo,
                    switchToNextLanguageKeyboardShortcutInfoAlternative,
                    switchToPreviousLanguageKeyboardShortcutInfo,
                    switchToPreviousLanguageKeyboardShortcutInfoAlternative
                )
            ),
            KeyboardShortcutGroup(
                "Gboard",
                listOf(
                    showOnScreenKeyboardShortcutInfo,
                    accessClipboardShortcutInfo,
                )
            )
        )

    private val expectedGroupedShortcutCategories =
        ShortcutCategory(
            type = ShortcutCategoryType.IME,
            subCategories =
                listOf(
                    ShortcutSubCategory(
                        imeShortcutsGroupsWithDuplicateLabels[0].label.toString(),
                        listOf(
                            switchToNextLanguageShortcut.copy(
                                commands =
                                    switchToNextLanguageShortcut.commands +
                                        switchToNextLanguageShortcutAlternative.commands
                            ),
                            switchToPreviousLanguageShortcut.copy(
                                commands =
                                    switchToPreviousLanguageShortcut.commands +
                                        switchToPreviousLanguageShortcutAlternative.commands
                            )
                        ),
                    ),
                    ShortcutSubCategory(
                        imeShortcutsGroupsWithDuplicateLabels[1].label.toString(),
                        listOf(showOnscreenKeyboardShortcut, accessClipboardShortcut),
                    )
                )
        )
}
