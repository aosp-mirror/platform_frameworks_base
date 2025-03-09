/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.keyboard.shortcut.data.source

import android.content.res.mainResources
import android.hardware.input.KeyGlyphMap
import android.hardware.input.KeyGlyphMap.KeyCombination
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_HOME
import android.view.KeyEvent.KEYCODE_RECENT_APPS
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_SHORTCUT_HELPER_KEY_GLYPH
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class SystemShortcutsSourceTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val inputManager = kosmos.fakeInputManager.inputManager
    private val source = SystemShortcutsSource(kosmos.mainResources, inputManager)
    private val mockKeyGlyphMap = mock(KeyGlyphMap::class.java)
    private val functionRowKeyCodes = listOf(KEYCODE_HOME, KEYCODE_BACK, KEYCODE_RECENT_APPS)

    @Before
    fun setUp() {
        whenever(mockKeyGlyphMap.functionRowKeys).thenReturn(intArrayOf())
        whenever(inputManager.getKeyGlyphMap(TEST_DEVICE_ID)).thenReturn(mockKeyGlyphMap)
    }

    @Test
    @EnableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagEnabled_inputManagerReturnsNoFunctionRowKeys_returnsNoFunctionRowShortcuts() =
        testScope.runTest {
            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val keyCodes = groups[0].items.map { it.keycode }
            assertThat(keyCodes).containsNoneIn(functionRowKeyCodes)
        }

    @Test
    @EnableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagEnabled_inputManagerReturnsFunctionRowKeys_returnsFunctionRowShortcuts() =
        testScope.runTest {
            whenever(mockKeyGlyphMap.functionRowKeys)
                .thenReturn(intArrayOf(KEYCODE_HOME, KEYCODE_BACK, KEYCODE_RECENT_APPS))

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val keyCodes = groups[0].items.map { it.keycode }
            assertThat(keyCodes).containsAtLeastElementsIn(functionRowKeyCodes)
        }

    @Test
    @DisableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagDisabled_inputManagerReturnsNoFunctionRowKeys_returnsDefaultFunctionRowShortcuts() =
        testScope.runTest {
            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val keyCodes = groups[0].items.map { it.keycode }
            assertThat(keyCodes).containsAtLeastElementsIn(functionRowKeyCodes)
        }

    @Test
    @EnableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagEnabled_inputManagerReturnsHardwareShortcuts_returnsHardwareShortcuts() =
        testScope.runTest {
            whenever(mockKeyGlyphMap.functionRowKeys).thenReturn(intArrayOf())
            val hardwareShortcutMap =
                mapOf(Pair(KeyCombination(KeyEvent.META_META_ON, KeyEvent.KEYCODE_1), KEYCODE_BACK))
            whenever(mockKeyGlyphMap.hardwareShortcuts).thenReturn(hardwareShortcutMap)

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val shortcuts = groups[0].items.map { c -> Triple(c.label, c.modifiers, c.keycode) }
            val hardwareShortcut =
                Triple(
                    context.getString(R.string.group_system_go_back),
                    KeyEvent.META_META_ON,
                    KeyEvent.KEYCODE_1,
                )
            assertThat(shortcuts).contains(hardwareShortcut)
        }

    @Test
    @DisableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagDisabled_inputManagerReturnsHardwareShortcuts_returnsNoHardwareShortcuts() =
        testScope.runTest {
            val hardwareShortcutMap =
                mapOf(Pair(KeyCombination(KeyEvent.META_META_ON, KeyEvent.KEYCODE_1), KEYCODE_BACK))
            whenever(mockKeyGlyphMap.hardwareShortcuts).thenReturn(hardwareShortcutMap)

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val shortcuts = groups[0].items.map { c -> Triple(c.label, c.modifiers, c.keycode) }
            val hardwareShortcut =
                Triple(
                    context.getString(R.string.group_system_go_back),
                    KeyEvent.META_META_ON,
                    KeyEvent.KEYCODE_1,
                )
            assertThat(shortcuts).doesNotContain(hardwareShortcut)
        }

    @Test
    fun shortcutGroups_containsCycleThroughRecentAppsShortcuts() {
        testScope.runTest {
            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val shortcuts =
                groups.flatMap { it.items }.map { c -> Triple(c.label, c.modifiers, c.keycode) }

            val cycleThroughRecentAppsShortcuts =
                listOf(
                    Triple(
                        context.getString(R.string.group_system_cycle_forward),
                        META_ALT_ON,
                        KEYCODE_TAB,
                    ),
                    Triple(
                        context.getString(R.string.group_system_cycle_back),
                        META_SHIFT_ON or META_ALT_ON,
                        KEYCODE_TAB,
                    ),
                )

            assertThat(shortcuts).containsAtLeastElementsIn(cycleThroughRecentAppsShortcuts)
        }
    }

    private companion object {
        private const val TEST_DEVICE_ID = 1234
    }
}
