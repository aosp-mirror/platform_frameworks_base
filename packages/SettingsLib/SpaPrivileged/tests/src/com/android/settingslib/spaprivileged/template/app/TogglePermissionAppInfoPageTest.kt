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
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.FakeNavControllerWrapper
import com.android.settingslib.spa.testutils.waitUntilExists
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.IPackageManagers
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.tests.testutils.FakeRestrictionsProvider
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListModel
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class TogglePermissionAppInfoPageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val packageManagers = mock<IPackageManagers> {
        on { getPackageInfoAsUser(PACKAGE_NAME, USER_ID) } doReturn PACKAGE_INFO
    }

    private val fakeNavControllerWrapper = FakeNavControllerWrapper()

    private val fakeRestrictionsProvider = FakeRestrictionsProvider().apply {
        restrictedMode = NoRestricted
    }

    private val appListTemplate =
        TogglePermissionAppListTemplate(listOf(TestTogglePermissionAppListProvider))

    private val appInfoPageProvider = TogglePermissionAppInfoPageProvider(appListTemplate)

    @Test
    fun buildEntry() {
        val entryList = appInfoPageProvider.buildEntry(null)

        assertThat(entryList).hasSize(1)
        assertThat(entryList[0].label).isEqualTo("AllowControl")
    }

    @Test
    fun entryItem_whenNotChangeable_notDisplayed() {
        val listModel = TestTogglePermissionAppListModel(isChangeable = false)

        setEntryItem(listModel)

        composeTestRule.onRoot().assertIsNotDisplayed()
    }

    @Test
    fun entryItem_whenChangeable_titleDisplayed() {
        val listModel = TestTogglePermissionAppListModel(isChangeable = true)

        setEntryItem(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.pageTitleResId))
            .assertIsDisplayed()
    }

    @Test
    fun entryItem_whenAllowed_summaryIsAllowed() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = true, isChangeable = true)

        setEntryItem(listModel)

        composeTestRule.waitUntilExists(
            hasText(context.getString(R.string.app_permission_summary_allowed)))
    }

    @Test
    fun entryItem_whenNotAllowed_summaryIsNotAllowed() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false, isChangeable = true)

        setEntryItem(listModel)

        composeTestRule.onNodeWithText(
            context.getString(R.string.app_permission_summary_not_allowed)
        ).assertIsDisplayed()
    }

    @Test
    fun entryItem_onClick() {
        val listModel = TestTogglePermissionAppListModel(isChangeable = true)

        setEntryItem(listModel)
        composeTestRule.onRoot().performClick()

        assertThat(fakeNavControllerWrapper.navigateCalledWith)
            .isEqualTo("TogglePermissionAppInfoPage/test.PERMISSION/package.name/0")
    }

    @Test
    fun infoPage_title_isDisplayed() {
        val listModel = TestTogglePermissionAppListModel()

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.pageTitleResId))
            .assertIsDisplayed()
    }

    @Test
    fun infoPage_whenAllowed_switchIsOn() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = true)

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.waitUntilExists(
            hasText(context.getString(listModel.switchTitleResId)) and isOn())
    }

    @Test
    fun infoPage_whenNotAllowed_switchIsOff() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false)

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.waitUntilExists(
            hasText(context.getString(listModel.switchTitleResId)) and isOff())
    }

    @Test
    fun infoPage_whenChangeableAndClick() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false, isChangeable = true)

        setTogglePermissionAppInfoPage(listModel)
        composeTestRule.onNodeWithText(context.getString(listModel.switchTitleResId)).performClick()

        composeTestRule.waitUntilExists(
            hasText(context.getString(listModel.switchTitleResId)) and isOn())
    }

    @Test
    fun infoPage_whenNotChangeableAndClick() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false, isChangeable = false)

        setTogglePermissionAppInfoPage(listModel)
        composeTestRule.onNodeWithText(context.getString(listModel.switchTitleResId)).performClick()

        composeTestRule.waitUntilExists(
            hasText(context.getString(listModel.switchTitleResId)) and isOff())
    }

    @Test
    fun infoPage_whenNotChangeable_switchNotEnabled() {
        val listModel = TestTogglePermissionAppListModel(isAllowed = false, isChangeable = false)

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.switchTitleResId))
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun infoPage_footer_isDisplayed() {
        val listModel = TestTogglePermissionAppListModel()

        setTogglePermissionAppInfoPage(listModel)

        composeTestRule.onNodeWithText(context.getString(listModel.footerResId))
            .assertIsDisplayed()
    }

    private fun setEntryItem(listModel: TestTogglePermissionAppListModel) {
        composeTestRule.setContent {
            fakeNavControllerWrapper.Wrapper {
                listModel.TogglePermissionAppInfoPageEntryItem(
                    permissionType = PERMISSION_TYPE,
                    app = APP,
                )
            }
        }
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
        const val PERMISSION_TYPE = "test.PERMISSION"
        const val USER_ID = 0
        const val PACKAGE_NAME = "package.name"
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
            uid = 11000
        }
        val PACKAGE_INFO = PackageInfo().apply {
            packageName = PACKAGE_NAME
            applicationInfo = APP
        }
    }
}
