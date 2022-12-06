/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.template.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spaprivileged.model.app.IPackageManagers
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.tests.testutils.FakeRestrictionsProvider
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListModel
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidJUnit4::class)
class TogglePermissionAppInfoPageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var packageManagers: IPackageManagers

    private val fakeRestrictionsProvider = FakeRestrictionsProvider()

    private val appListTemplate =
        TogglePermissionAppListTemplate(listOf(TestTogglePermissionAppListProvider))

    private val appInfoPageProvider = TogglePermissionAppInfoPageProvider(appListTemplate)

    @Before
    fun setUp() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        whenever(packageManagers.getPackageInfoAsUser(PACKAGE_NAME, USER_ID))
            .thenReturn(PACKAGE_INFO)
    }

    @Test
    fun buildEntry() {
        val entryList = appInfoPageProvider.buildEntry(null)

        assertThat(entryList).hasSize(1)
        assertThat(entryList[0].displayName).isEqualTo("AllowControl")
    }

    @Test
    fun title_isDisplayed() {
        val listModel = TestTogglePermissionAppListModel()

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.pageTitleResId))
            .assertIsDisplayed()
    }

    @Test
    fun whenAllowed_switchIsOn() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = true)

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.switchTitleResId))
            .assertIsOn()
    }

    @Test
    fun whenNotAllowed_switchIsOff() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false)

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.switchTitleResId))
            .assertIsOff()
    }

    @Test
    fun whenNotChangeable_switchNotEnabled() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false, isChangeable = false)

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.switchTitleResId))
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun footer_isDisplayed() {
        val listModel = TestTogglePermissionAppListModel()

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.footerResId))
            .assertIsDisplayed()
    }

    private fun setTogglePermissionAppInfoPage(listModel: TestTogglePermissionAppListModel) {
        composeTestRule.setContent {
            listModel.TogglePermissionAppInfoPage(
                packageName = PACKAGE_NAME,
                userId = USER_ID,
                packageManagers = packageManagers,
                restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
            )
        }
    }

    private companion object {
        const val USER_ID = 0
        const val PACKAGE_NAME = "package.name"
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
        }
        val PACKAGE_INFO = PackageInfo().apply {
            packageName = PACKAGE_NAME
            applicationInfo = APP
        }
    }
}
