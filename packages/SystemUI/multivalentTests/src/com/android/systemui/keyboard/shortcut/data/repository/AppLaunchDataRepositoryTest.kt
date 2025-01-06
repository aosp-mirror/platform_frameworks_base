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

import android.hardware.input.AppLaunchData
import android.hardware.input.AppLaunchData.RoleData
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.fakeInputManager
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.shortcut.appLaunchDataRepository
import com.android.systemui.keyboard.shortcut.shared.model.shortcutCommand
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AppLaunchDataRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val inputManager = kosmos.fakeInputManager.inputManager
    private val testHelper = kosmos.shortcutHelperTestHelper
    private val repo = kosmos.appLaunchDataRepository

    @Test
    fun appLaunchData_returnsDataRetrievedFromApiBasedOnShortcutCommand() =
        kosmos.runTest {
            val inputGesture = simpleInputGestureDataForAppLaunchShortcut()
            setApiAppLaunchBookmarks(listOf(inputGesture))

            testHelper.toggle(TEST_DEVICE_ID)

            val appLaunchData =
                repo.getAppLaunchDataForShortcutWithCommand(
                    shortcutCommand =
                        shortcutCommand {
                            key("Ctrl")
                            key("Alt")
                            key("A")
                        }
                )

            assertThat(appLaunchData).isEqualTo(inputGesture.action.appLaunchData())
        }

    @Test
    fun appLaunchData_returnsSameDataForAnyOrderOfShortcutCommandKeys() =
        kosmos.runTest {
            val inputGesture = simpleInputGestureDataForAppLaunchShortcut()
            setApiAppLaunchBookmarks(listOf(inputGesture))

            testHelper.toggle(TEST_DEVICE_ID)

            val shortcutCommandCtrlAltA = shortcutCommand {
                key("Ctrl")
                key("Alt")
                key("A")
            }

            val shortcutCommandCtrlAAlt = shortcutCommand {
                key("Ctrl")
                key("A")
                key("Alt")
            }

            val shortcutCommandAltCtrlA = shortcutCommand {
                key("Alt")
                key("Ctrl")
                key("A")
            }

            assertThat(repo.getAppLaunchDataForShortcutWithCommand(shortcutCommandCtrlAltA))
                .isEqualTo(inputGesture.action.appLaunchData())

            assertThat(repo.getAppLaunchDataForShortcutWithCommand(shortcutCommandCtrlAAlt))
                .isEqualTo(inputGesture.action.appLaunchData())

            assertThat(repo.getAppLaunchDataForShortcutWithCommand(shortcutCommandAltCtrlA))
                .isEqualTo(inputGesture.action.appLaunchData())
        }

    private fun setApiAppLaunchBookmarks(appLaunchBookmarks: List<InputGestureData>) {
        whenever(inputManager.appLaunchBookmarks).thenReturn(appLaunchBookmarks)
    }

    private fun simpleInputGestureDataForAppLaunchShortcut(
        keyCode: Int = KEYCODE_A,
        modifiers: Int = META_CTRL_ON or META_ALT_ON,
        appLaunchData: AppLaunchData = RoleData(TEST_ROLE),
    ): InputGestureData {
        return InputGestureData.Builder()
            .setTrigger(createKeyTrigger(keyCode, modifiers))
            .setAppLaunchData(appLaunchData)
            .build()
    }

    private companion object {
        private const val TEST_ROLE = "Test role"
        private const val TEST_DEVICE_ID = 123
    }
}
