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

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.mockWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppCategoriesShortcutsSourceTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val mockWindowManager = kosmos.mockWindowManager
    private val source =
        AppCategoriesShortcutsSource(kosmos.mockWindowManager, kosmos.testDispatcher)

    private var appCategoriesGroup: KeyboardShortcutGroup? = null

    @Before
    fun setUp() {
        whenever(mockWindowManager.getApplicationLaunchKeyboardShortcuts(TEST_DEVICE_ID))
            .thenAnswer { appCategoriesGroup }
    }

    @Test
    fun shortcutGroups_nullResult_returnsEmptyList() =
        testScope.runTest {
            appCategoriesGroup = null

            assertThat(source.shortcutGroups(TEST_DEVICE_ID)).isEmpty()
        }

    @Test
    fun shortcutGroups_returnsSortedList() =
        testScope.runTest {
            val testItems =
                listOf(
                    KeyboardShortcutInfo("Info 2", KeyEvent.KEYCODE_E, KeyEvent.META_META_ON),
                    KeyboardShortcutInfo("Info 1", KeyEvent.KEYCODE_E, KeyEvent.META_META_ON),
                    KeyboardShortcutInfo("Info 3", KeyEvent.KEYCODE_E, KeyEvent.META_META_ON),
                )
            appCategoriesGroup = KeyboardShortcutGroup("Test Group", testItems)

            val shortcuts = source.shortcutGroups(TEST_DEVICE_ID).first().items
            val shortcutLabels = shortcuts.map { it.label.toString() }
            assertThat(shortcutLabels).containsExactly("Info 1", "Info 2", "Info 3").inOrder()
        }

    companion object {
        private const val TEST_DEVICE_ID = 123
    }
}
