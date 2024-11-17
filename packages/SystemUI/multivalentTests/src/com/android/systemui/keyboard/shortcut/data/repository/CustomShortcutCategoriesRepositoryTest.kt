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
import android.content.Context.INPUT_SERVICE
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.KeyGestureEvent
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.hardware.input.Flags.FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES
import com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.customShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.shared.model.Shortcut
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategory
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.AppCategories
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.MultiTasking
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType.System
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCommand
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutSubCategory
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class CustomShortcutCategoriesRepositoryTest : SysuiTestCase() {

    private val mockUserContext: Context = mock()
    private val kosmos =
        testKosmos().also {
            it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
        }

    private val fakeInputManager = kosmos.fakeInputManager
    private val testScope = kosmos.testScope
    private val helper = kosmos.shortcutHelperTestHelper
    private val repo = kosmos.customShortcutCategoriesRepository

    @Before
    fun setup() {
        whenever(mockUserContext.getSystemService(INPUT_SERVICE))
            .thenReturn(fakeInputManager.inputManager)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_emitsCorrectlyConvertedShortcutCategories() {
        testScope.runTest {
            whenever(
                    fakeInputManager.inputManager.getCustomInputGestures(/* filter= */ anyOrNull())
                )
                .thenReturn(customizableInputGesturesWithSimpleShortcutCombinations)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories)
                .containsExactlyElementsIn(expectedShortcutCategoriesWithSimpleShortcutCombination)
        }
    }

    @Test
    @DisableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_emitsEmptyListWhenFlagIsDisabled() {
        testScope.runTest {
            whenever(
                    fakeInputManager.inputManager.getCustomInputGestures(/* filter= */ anyOrNull())
                )
                .thenReturn(customizableInputGesturesWithSimpleShortcutCombinations)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories).isEmpty()
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_ignoresUnknownKeyGestureTypes() {
        testScope.runTest {
            whenever(
                    fakeInputManager.inputManager.getCustomInputGestures(/* filter= */ anyOrNull())
                )
                .thenReturn(customizableInputGestureWithUnknownKeyGestureType)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories).isEmpty()
        }
    }

    private fun simpleInputGestureData(
        keyCode: Int = KeyEvent.KEYCODE_A,
        modifiers: Int = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
        keyGestureType: Int,
    ): InputGestureData {
        val builder = InputGestureData.Builder()
        builder.setKeyGestureType(keyGestureType)
        builder.setTrigger(createKeyTrigger(keyCode, modifiers))
        return builder.build()
    }

    private fun simpleShortcutCategory(
        category: ShortcutCategoryType,
        subcategoryLabel: String,
        shortcutLabel: String,
    ): ShortcutCategory {
        return ShortcutCategory(
            type = category,
            subCategories =
                listOf(
                    ShortcutSubCategory(
                        label = subcategoryLabel,
                        shortcuts = listOf(simpleShortcut(shortcutLabel)),
                    )
                ),
        )
    }

    private fun simpleShortcut(label: String) =
        Shortcut(
            label = label,
            commands =
                listOf(
                    ShortcutCommand(
                        isCustom = true,
                        keys =
                            listOf(
                                ShortcutKey.Text("Ctrl"),
                                ShortcutKey.Text("Alt"),
                                ShortcutKey.Text("A"),
                            ),
                    )
                ),
        )

    private val customizableInputGestureWithUnknownKeyGestureType =
        // These key gesture events are currently not supported by shortcut helper customizer
        listOf(
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY),
        )

    private val expectedShortcutCategoriesWithSimpleShortcutCombination =
        listOf(
            simpleShortcutCategory(System, "System apps", "Open assistant"),
            simpleShortcutCategory(System, "System controls", "Go to home screen"),
            simpleShortcutCategory(System, "System apps", "Open settings"),
            simpleShortcutCategory(System, "System controls", "Lock screen"),
            simpleShortcutCategory(System, "System controls", "View notifications"),
            simpleShortcutCategory(System, "System apps", "Take a note"),
            simpleShortcutCategory(System, "System controls", "Take screenshot"),
            simpleShortcutCategory(System, "System controls", "Go back"),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Switch from split screen to full screen",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Use split screen with current app on the left",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Switch to app on left or above while using split screen",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Use split screen with current app on the right",
            ),
            simpleShortcutCategory(
                MultiTasking,
                "Split screen",
                "Switch to app on right or below while using split screen",
            ),
            simpleShortcutCategory(System, "System controls", "Show shortcuts"),
            simpleShortcutCategory(System, "System controls", "View recent apps"),
            simpleShortcutCategory(AppCategories, "Applications", "Calculator"),
            simpleShortcutCategory(AppCategories, "Applications", "Calendar"),
            simpleShortcutCategory(AppCategories, "Applications", "Browser"),
            simpleShortcutCategory(AppCategories, "Applications", "Contacts"),
            simpleShortcutCategory(AppCategories, "Applications", "Email"),
            simpleShortcutCategory(AppCategories, "Applications", "Maps"),
            simpleShortcutCategory(AppCategories, "Applications", "SMS"),
            simpleShortcutCategory(MultiTasking, "Recent apps", "Cycle forward through recent apps"),
        )

    private val customizableInputGesturesWithSimpleShortcutCombinations =
        listOf(
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_HOME),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_BACK),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER
            ),
            simpleInputGestureData(keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALCULATOR
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CALENDAR
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_BROWSER
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_CONTACTS
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_EMAIL
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MAPS
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_DEFAULT_MESSAGING
            ),
            simpleInputGestureData(
                keyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER
            ),
        )
}
