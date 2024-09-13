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
import android.view.KeyboardShortcutGroup
import android.view.WindowManager.KeyboardShortcutsReceiver
import android.view.mockWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class InputShortcutsSourceTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val mockWindowManager = kosmos.mockWindowManager
    private val source = InputShortcutsSource(kosmos.mainResources, mockWindowManager)

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

    companion object {
        private const val TEST_DEVICE_ID = 1234
    }
}
