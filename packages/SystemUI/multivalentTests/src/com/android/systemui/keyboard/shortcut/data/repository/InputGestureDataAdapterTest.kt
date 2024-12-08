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

import android.app.role.RoleManager
import android.app.role.roleManager
import android.content.Context
import android.content.Intent
import android.content.mockedContext
import android.content.packageManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.input.AppLaunchData
import android.hardware.input.AppLaunchData.RoleData
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.app.ResolverActivity
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.shortcut.data.model.InternalGroupsSource
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutGroup
import com.android.systemui.keyboard.shortcut.data.model.InternalKeyboardShortcutInfo
import com.android.systemui.keyboard.shortcut.inputGestureDataAdapter
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.kosmos.runTest
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever


@SmallTest
@RunWith(AndroidJUnit4::class)
class InputGestureDataAdapterTest : SysuiTestCase() {

    private val kosmos = testKosmos().also { kosmos ->
        kosmos.userTracker = FakeUserTracker(onCreateCurrentUserContext = { kosmos.mockedContext })
    }
    private val adapter = kosmos.inputGestureDataAdapter
    private val roleManager = kosmos.roleManager
    private val packageManager: PackageManager = kosmos.packageManager
    private val mockUserContext: Context = kosmos.mockedContext
    private val intent: Intent = mock()
    private val fakeResolverActivityInfo =
        ActivityInfo().apply { name = ResolverActivity::class.qualifiedName }
    private val fakeActivityInfo: ActivityInfo =
        ActivityInfo().apply {
            name = FAKE_ACTIVITY_NAME
            icon = 0x1
            nonLocalizedLabel = TEST_SHORTCUT_LABEL
        }
    private val mockSelectorIntent: Intent = mock()

    @Before
    fun setup() {
        whenever(mockUserContext.packageManager).thenReturn(packageManager)
        whenever(mockUserContext.getSystemService(RoleManager::class.java)).thenReturn(roleManager)
        whenever(roleManager.isRoleAvailable(TEST_ROLE)).thenReturn(true)
        whenever(roleManager.getDefaultApplication(TEST_ROLE)).thenReturn(TEST_ROLE_PACKAGE)
        whenever(packageManager.getActivityInfo(any(), anyInt())).thenReturn(mock())
        whenever(packageManager.getLaunchIntentForPackage(TEST_ROLE_PACKAGE)).thenReturn(intent)
        whenever(intent.selector).thenReturn(mockSelectorIntent)
        whenever(mockSelectorIntent.categories).thenReturn(setOf(TEST_ACTIVITY_CATEGORY))
    }

    @Test
    fun shortcutLabel_whenDefaultAppForCategoryIsNotSet_loadsLabelFromFirstAppMatchingIntent() =
        kosmos.runTest {
            setApiToRetrieveResolverActivity()

            val inputGestureData = buildInputGestureDataForAppLaunchShortcut()
            val internalGroups = adapter.toInternalGroupSources(listOf(inputGestureData))
            val label =
                internalGroups.firstOrNull()?.groups?.firstOrNull()?.items?.firstOrNull()?.label

            assertThat(label).isEqualTo(expectedShortcutLabelForFirstAppMatchingIntent)
        }

    @Test
    fun shortcutLabel_whenDefaultAppForCategoryIsSet_loadsLabelOfDefaultApp() {
        kosmos.runTest {
            setApiToRetrieveSpecificActivity()

            val inputGestureData = buildInputGestureDataForAppLaunchShortcut()
            val internalGroups = adapter.toInternalGroupSources(listOf(inputGestureData))
            val label =
                internalGroups.firstOrNull()?.groups?.firstOrNull()?.items?.firstOrNull()?.label

            assertThat(label).isEqualTo(TEST_SHORTCUT_LABEL)
        }
    }

    @Test
    fun shortcutIcon_whenDefaultAppForCategoryIsSet_loadsIconOfDefaultApp() {
        kosmos.runTest {
            setApiToRetrieveSpecificActivity()

            val inputGestureData = buildInputGestureDataForAppLaunchShortcut()
            val internalGroups = adapter.toInternalGroupSources(listOf(inputGestureData))
            val icon =
                internalGroups.firstOrNull()?.groups?.firstOrNull()?.items?.firstOrNull()?.icon

            assertThat(icon).isNotNull()
        }
    }

    @Test
    fun internalGroupSource_isCorrectlyConvertedWithSimpleInputGestureData() =
        kosmos.runTest {
            setApiToRetrieveResolverActivity()

            val inputGestureData = buildInputGestureDataForAppLaunchShortcut()
            val internalGroups = adapter.toInternalGroupSources(listOf(inputGestureData))

            assertThat(internalGroups).containsExactly(
                InternalGroupsSource(
                    type = ShortcutCategoryType.AppCategories,
                    groups = listOf(
                        InternalKeyboardShortcutGroup(
                            label = APPLICATION_SHORTCUT_GROUP_LABEL,
                            items = listOf(
                                InternalKeyboardShortcutInfo(
                                    label = expectedShortcutLabelForFirstAppMatchingIntent,
                                    keycode = KEYCODE_A,
                                    modifiers = META_CTRL_ON or META_ALT_ON,
                                    isCustomShortcut = true
                                )
                            )
                        )
                    )
                )
            )
        }

    private fun setApiToRetrieveResolverActivity() {
        whenever(intent.resolveActivityInfo(eq(packageManager), anyInt()))
            .thenReturn(fakeResolverActivityInfo)
    }

    private fun setApiToRetrieveSpecificActivity() {
        whenever(intent.resolveActivityInfo(eq(packageManager), anyInt()))
            .thenReturn(fakeActivityInfo)
    }


    private fun buildInputGestureDataForAppLaunchShortcut(
        keyCode: Int = KEYCODE_A,
        modifiers: Int = META_CTRL_ON or META_ALT_ON,
        appLaunchData: AppLaunchData = RoleData(TEST_ROLE)
    ): InputGestureData {
        return InputGestureData.Builder()
            .setTrigger(createKeyTrigger(keyCode, modifiers))
            .setAppLaunchData(appLaunchData)
            .build()
    }

    private val expectedShortcutLabelForFirstAppMatchingIntent =
        context.getString(R.string.keyboard_shortcut_group_applications_browser)

    private companion object {
        private const val TEST_ROLE = "Test Browser Role"
        private const val TEST_ROLE_PACKAGE = "test.browser.package"
        private const val APPLICATION_SHORTCUT_GROUP_LABEL = "Applications"
        private const val FAKE_ACTIVITY_NAME = "Fake activity"
        private const val TEST_SHORTCUT_LABEL = "Test shortcut label"
        private const val TEST_ACTIVITY_CATEGORY = Intent.CATEGORY_APP_BROWSER
    }
}
