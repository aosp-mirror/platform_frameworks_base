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

package com.android.systemui.keyboard.shortcut.data.source

import android.content.res.mainResources
import android.hardware.input.KeyGlyphMap
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent.KEYCODE_EMOJI_PICKER
import android.view.KeyboardShortcutGroup
import android.view.WindowManager.KeyboardShortcutsReceiver
import android.view.mockWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_SHORTCUT_HELPER_KEY_GLYPH
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class InputShortcutsSourceTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mockWindowManager = kosmos.mockWindowManager
    private val inputManager = kosmos.fakeInputManager.inputManager
    private val source = InputShortcutsSource(kosmos.mainResources, mockWindowManager, inputManager)

    private var wmImeShortcutGroups: List<KeyboardShortcutGroup>? = null

    @Before
    fun setUp() {
        whenever(mockWindowManager.requestImeKeyboardShortcuts(any(), any())).thenAnswer {
            val receiver = it.arguments[0] as KeyboardShortcutsReceiver
            receiver.onKeyboardShortcutsReceived(wmImeShortcutGroups)
        }
    }

    @Test
    fun shortcutGroups_wmReturnsNullList_returnsSingleGroup() =
        testScope.runTest {
            wmImeShortcutGroups = null

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            assertThat(groups).hasSize(1)
        }

    @Test
    fun shortcutGroups_wmReturnsEmptyList_returnsSingleGroup() =
        testScope.runTest {
            wmImeShortcutGroups = emptyList()

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            assertThat(groups).hasSize(1)
        }

    @Test
    fun shortcutGroups_wmReturnsGroups_returnsWmGroupsPlusOne() =
        testScope.runTest {
            wmImeShortcutGroups =
                listOf(
                    KeyboardShortcutGroup("wm ime group 1"),
                    KeyboardShortcutGroup("wm ime group 2"),
                    KeyboardShortcutGroup("wm ime group 3"),
                )

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            assertThat(groups).hasSize(4)
        }

    @Test
    @EnableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagEnabled_inputManagerReturnsKeyGlyph_returnsEmojiShortcut() =
        testScope.runTest {
            val mockKeyGlyph = mock(KeyGlyphMap::class.java)
            whenever(mockKeyGlyph.functionRowKeys).thenReturn(intArrayOf(KEYCODE_EMOJI_PICKER))
            whenever(inputManager.getKeyGlyphMap(TEST_DEVICE_ID)).thenReturn(mockKeyGlyph)
            wmImeShortcutGroups = null

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val keyCodes = groups[0].items.map { it.keycode }
            assertThat(keyCodes).contains(KEYCODE_EMOJI_PICKER)
        }

    @Test
    @DisableFlags(FLAG_SHORTCUT_HELPER_KEY_GLYPH)
    fun shortcutGroups_flagDisabled_inputManagerReturnsKeyGlyph_returnsNoEmojiShortcut() =
        testScope.runTest {
            val mockKeyGlyph = mock(KeyGlyphMap::class.java)
            whenever(mockKeyGlyph.functionRowKeys).thenReturn(intArrayOf(KEYCODE_EMOJI_PICKER))
            whenever(inputManager.getKeyGlyphMap(TEST_DEVICE_ID)).thenReturn(mockKeyGlyph)
            wmImeShortcutGroups = null

            val groups = source.shortcutGroups(TEST_DEVICE_ID)

            val keyCodes = groups[0].items.map { it.keycode }
            assertThat(keyCodes).doesNotContain(KEYCODE_EMOJI_PICKER)
        }

    companion object {
        private const val TEST_DEVICE_ID = 1234
    }
}
