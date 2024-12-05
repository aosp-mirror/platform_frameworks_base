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

import android.graphics.drawable.Drawable
import android.hardware.input.KeyGlyphMap
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_B
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_G
import android.view.KeyEvent.META_FUNCTION_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_SHORTCUT_HELPER_KEY_GLYPH
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.data.source.FakeKeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.CYCLE_BACK_THROUGH_RECENT_APPS_SHORTCUT_LABEL
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.CYCLE_FORWARD_THROUGH_RECENT_APPS_SHORTCUT_LABEL
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.recentAppsGroup
import com.android.systemui.keyboard.shortcut.defaultShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.shortcutHelperAppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperCurrentAppShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperInputShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperMultiTaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperSystemShortcutsSource
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DefaultShortcutCategoriesRepositoryTest : SysuiTestCase() {

    private val fakeSystemSource = FakeKeyboardShortcutGroupsSource()
    private val fakeMultiTaskingSource = FakeKeyboardShortcutGroupsSource()
    private val fakeAppCategoriesSource = FakeKeyboardShortcutGroupsSource()

    private val kosmos =
        testKosmos().also {
            it.testDispatcher = UnconfinedTestDispatcher()
            it.shortcutHelperSystemShortcutsSource = fakeSystemSource
            it.shortcutHelperMultiTaskingShortcutsSource = fakeMultiTaskingSource
            it.shortcutHelperAppCategoriesShortcutsSource = fakeAppCategoriesSource
            it.shortcutHelperInputShortcutsSource = FakeKeyboardShortcutGroupsSource()
            it.shortcutHelperCurrentAppShortcutsSource = FakeKeyboardShortcutGroupsSource()
        }

    private val repo = kosmos.defaultShortcutCategoriesRepository
    private val helper = kosmos.shortcutHelperTestHelper
    private val testScope = kosmos.testScope
    private val fakeInputManager = kosmos.fakeInputManager

    @Before
    fun setUp() {
        fakeSystemSource.setGroups(TestShortcuts.systemGroups)
        fakeMultiTaskingSource.setGroups(TestShortcuts.multitaskingGroups)
    }

    @Test
    fun categories_keycodeAndModifiersAreMappedSeparatelyWhenIdentical() =
        testScope.runTest {
            fakeSystemSource.setGroups(simpleGroup(simpleShortcutInfo(KEYCODE_1)))

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            val systemCategory = categories?.firstOrNull { it.type == ShortcutCategoryType.System }

            // Keycode 0x8 should be translated to the Key 1 instead of modifier FN
            // which has the same keycode.
            val expectedCategory =
                ShortcutCategory(
                    type = ShortcutCategoryType.System,
                    simpleSubCategory(simpleShortcut("1")),
                )

            assertThat(systemCategory).isEqualTo(expectedCategory)
        }

    @Test
    fun categories_keyCodeAndModifierHaveSameCode_codesAreMappedCorrectly() =
        testScope.runTest {
            fakeSystemSource.setGroups(simpleGroup(simpleShortcutInfo(KEYCODE_1, META_FUNCTION_ON)))

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            val systemCategory = categories?.firstOrNull { it.type == ShortcutCategoryType.System }

            // Keycode 0x8 should be translated to the Key 1 instead of modifier FN
            // which has the same keycode. while modifier mask 0x8 should be translated to FN.
            val expectedCategory =
                ShortcutCategory(
                    type = ShortcutCategoryType.System,
                    simpleSubCategory(simpleShortcut("Fn", "1")),
                )

            assertThat(systemCategory).isEqualTo(expectedCategory)
        }

    @Test
    fun categories_multipleSubscribers_replaysExistingValueToNewSubscribers() =
        testScope.runTest {
            fakeSystemSource.setGroups(TestShortcuts.systemGroups)
            fakeMultiTaskingSource.setGroups(TestShortcuts.multitaskingGroups)
            helper.showFromActivity()
            val firstCategories by collectLastValue(repo.categories)

            // Intentionally change shortcuts now. This simulates "current app" shortcuts changing
            // when our helper is shown.
            // We still want to return the shortcuts that were returned before our helper was
            // showing.
            fakeSystemSource.setGroups(emptyList())

            val secondCategories by collectLastValue(repo.categories)
            // Make sure the second subscriber receives the same value as the first subscriber, even
            // though fetching shortcuts again would have returned a new result.
            assertThat(secondCategories).isEqualTo(firstCategories)
        }

    @Test
    fun categories_filtersShortcutsWithUnsupportedKeyCodes() =
        testScope.runTest {
            fakeSystemSource.setGroups(
                listOf(
                    simpleGroup(simpleShortcutInfo(KEYCODE_A), simpleShortcutInfo(KEYCODE_B)),
                    simpleGroup(simpleShortcutInfo(KEYCODE_C)),
                )
            )
            fakeMultiTaskingSource.setGroups(
                listOf(
                    simpleGroup(simpleShortcutInfo(KEYCODE_D)),
                    simpleGroup(simpleShortcutInfo(KEYCODE_E), simpleShortcutInfo(KEYCODE_F)),
                )
            )
            fakeAppCategoriesSource.setGroups(listOf(simpleGroup(simpleShortcutInfo(KEYCODE_G))))

            fakeInputManager.removeKeysFromKeyboard(deviceId = 123, KEYCODE_A, KEYCODE_D, KEYCODE_G)
            helper.toggle(deviceId = 123)

            val categories by collectLastValue(repo.categories)
            assertThat(categories)
                .containsExactly(
                    ShortcutCategory(
                        ShortcutCategoryType.System,
                        listOf(
                            simpleSubCategory(simpleShortcut("B")),
                            simpleSubCategory(simpleShortcut("C")),
                        ),
                    ),
                    ShortcutCategory(
                        ShortcutCategoryType.MultiTasking,
                        listOf(simpleSubCategory(simpleShortcut("E"), simpleShortcut("F"))),
                    ),
                )
        }

    @EnableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    @Test
    fun modifierMappedToCustomDrawableWhenKeyGlyphMapExists() =
        testScope.runTest {
            val metaDrawable = mock(Drawable::class.java)
            val keyGlyph = mock(KeyGlyphMap::class.java)
            whenever(keyGlyph.getDrawableForModifierState(context, META_META_ON))
                .thenReturn(metaDrawable)
            whenever(kosmos.fakeInputManager.inputManager.getKeyGlyphMap(anyInt()))
                .thenReturn(keyGlyph)
            fakeSystemSource.setGroups(simpleGroup(simpleShortcutInfo(KEYCODE_1, META_META_ON)))
            helper.toggle(deviceId = 123)

            val categories by collectLastValue(repo.categories)
            val systemCategory = categories?.firstOrNull { it.type == ShortcutCategoryType.System }

            val expectedCategory =
                ShortcutCategory(
                    type = ShortcutCategoryType.System,
                    simpleSubCategory(
                        simpleDrawableModifierShortcut("1", modifierDrawable = metaDrawable)
                    ),
                )

            assertThat(systemCategory).isEqualTo(expectedCategory)
        }

    @EnableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    @Test
    fun modifierMappedToDefaultDrawableWhenNoKeyGlyphMapExists() =
        testScope.runTest {
            fakeSystemSource.setGroups(simpleGroup(simpleShortcutInfo(KEYCODE_1, META_META_ON)))
            helper.toggle(deviceId = 123)

            val categories by collectLastValue(repo.categories)
            val systemCategory = categories?.firstOrNull { it.type == ShortcutCategoryType.System }

            val expectedCategory =
                ShortcutCategory(
                    type = ShortcutCategoryType.System,
                    simpleSubCategory(
                        simpleResIdModifierShortcut("1", modifierResId = R.drawable.ic_ksh_key_meta)
                    ),
                )
            assertThat(systemCategory).isEqualTo(expectedCategory)
        }

    @DisableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    @Test
    fun modifierMappedToDefaultDrawableWhenKeyGlyphDisabled() =
        testScope.runTest {
            val metaDrawable = mock(Drawable::class.java)
            val keyGlyph = mock(KeyGlyphMap::class.java)
            whenever(keyGlyph.getDrawableForModifierState(context, META_META_ON))
                .thenReturn(metaDrawable)
            whenever(kosmos.fakeInputManager.inputManager.getKeyGlyphMap(anyInt()))
                .thenReturn(keyGlyph)
            fakeSystemSource.setGroups(simpleGroup(simpleShortcutInfo(KEYCODE_1, META_META_ON)))
            helper.toggle(deviceId = 123)

            val categories by collectLastValue(repo.categories)
            val systemCategory = categories?.firstOrNull { it.type == ShortcutCategoryType.System }

            val expectedCategory =
                ShortcutCategory(
                    type = ShortcutCategoryType.System,
                    simpleSubCategory(
                        simpleResIdModifierShortcut("1", modifierResId = R.drawable.ic_ksh_key_meta)
                    ),
                )
            assertThat(systemCategory).isEqualTo(expectedCategory)
        }

    @Test
    fun categories_recentAppsSwitchShortcutsIsMarkedNonCustomizable() {
        testScope.runTest {
            helper.setImeShortcuts(emptyList())
            fakeSystemSource.setGroups(emptyList())
            fakeMultiTaskingSource.setGroups(recentAppsGroup)

            helper.showFromActivity()
            val categories by collectLastValue(repo.categories)

            val cycleForwardThroughRecentAppsShortcut =
                categories?.first { it.type == ShortcutCategoryType.MultiTasking }
                    ?.subCategories?.first { it.label == recentAppsGroup.label }
                    ?.shortcuts?.first { it.label == CYCLE_FORWARD_THROUGH_RECENT_APPS_SHORTCUT_LABEL }

            val cycleBackThroughRecentAppsShortcut =
                categories?.first { it.type == ShortcutCategoryType.MultiTasking }
                    ?.subCategories?.first { it.label == recentAppsGroup.label }
                    ?.shortcuts?.first { it.label == CYCLE_BACK_THROUGH_RECENT_APPS_SHORTCUT_LABEL }

            assertThat(cycleForwardThroughRecentAppsShortcut?.isCustomizable).isFalse()
            assertThat(cycleBackThroughRecentAppsShortcut?.isCustomizable).isFalse()
        }
    }

    private fun simpleSubCategory(vararg shortcuts: Shortcut) =
        ShortcutSubCategory(simpleGroupLabel, shortcuts.asList())

    private fun simpleShortcut(vararg keys: String) =
        Shortcut(
            label = simpleShortcutLabel,
            commands = listOf(ShortcutCommand(keys.map { ShortcutKey.Text(it) })),
        )

    private fun simpleDrawableModifierShortcut(
        vararg keys: String,
        modifierDrawable: Drawable,
    ): Shortcut {
        val keyShortcuts = keys.map { ShortcutKey.Text(it) }
        return Shortcut(
            label = simpleShortcutLabel,
            commands =
                listOf(
                    ShortcutCommand(
                        listOf(ShortcutKey.Icon.DrawableIcon(drawable = modifierDrawable)) +
                            keyShortcuts
                    )
                ),
        )
    }

    private fun simpleResIdModifierShortcut(vararg keys: String, modifierResId: Int): Shortcut {
        val keyShortcuts = keys.map { ShortcutKey.Text(it) }
        return Shortcut(
            label = simpleShortcutLabel,
            commands =
                listOf(
                    ShortcutCommand(
                        listOf(ShortcutKey.Icon.ResIdIcon(drawableResId = modifierResId)) +
                            keyShortcuts
                    )
                ),
        )
    }

    private fun simpleGroup(vararg shortcuts: KeyboardShortcutInfo) =
        KeyboardShortcutGroup(simpleGroupLabel, shortcuts.asList())

    private fun simpleShortcutInfo(keyCode: Int = 0, modifiers: Int = 0) =
        KeyboardShortcutInfo(simpleShortcutLabel, keyCode, modifiers)

    private val simpleShortcutLabel = "shortcut label"
    private val simpleGroupLabel = "group label"
}
