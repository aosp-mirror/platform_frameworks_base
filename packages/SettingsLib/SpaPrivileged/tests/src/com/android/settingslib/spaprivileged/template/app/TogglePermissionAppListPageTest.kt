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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.testutils.FakeNavControllerWrapper
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.tests.testutils.FakeRestrictionsProvider
import com.android.settingslib.spaprivileged.tests.testutils.TestAppRecord
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TogglePermissionAppListPageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fakeNavControllerWrapper = FakeNavControllerWrapper()

    private val fakeRestrictionsProvider = FakeRestrictionsProvider()

    @Test
    fun internalAppListModel_whenAllowed() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        val listModel = TestTogglePermissionAppListModel(isAllowed = true)
        val internalAppListModel = TogglePermissionInternalAppListModel(
            context = context,
            listModel = listModel,
            restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
        )

        val summaryState = getSummary(internalAppListModel)

        assertThat(summaryState.value).isEqualTo(
            context.getString(R.string.app_permission_summary_allowed)
        )
    }

    @Test
    fun internalAppListModel_whenNotAllowed() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        val listModel = TestTogglePermissionAppListModel(isAllowed = false)
        val internalAppListModel = TogglePermissionInternalAppListModel(
            context = context,
            listModel = listModel,
            restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
        )

        val summaryState = getSummary(internalAppListModel)

        assertThat(summaryState.value).isEqualTo(
            context.getString(R.string.app_permission_summary_not_allowed)
        )
    }

    @Test
    fun internalAppListModel_whenComputingAllowed() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        val listModel = TestTogglePermissionAppListModel(isAllowed = null)
        val internalAppListModel = TogglePermissionInternalAppListModel(
            context = context,
            listModel = listModel,
            restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
        )

        val summaryState = getSummary(internalAppListModel)

        assertThat(summaryState.value).isEqualTo(
            context.getString(R.string.summary_placeholder)
        )
    }

    @Test
    fun appListItem_onClick_navigate() {
        val listModel = TestTogglePermissionAppListModel()
        composeTestRule.setContent {
            listModel.TogglePermissionAppList(
                permissionType = PERMISSION_TYPE,
                restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
            ) {
                fakeNavControllerWrapper.Wrapper {
                    AppListItemModel(
                        record = listModel.transformItem(APP),
                        label = LABEL,
                        summary = stateOf(SUMMARY),
                    ).appItem()
                }
            }
        }

        composeTestRule.onNodeWithText(LABEL).performClick()

        assertThat(fakeNavControllerWrapper.navigateCalledWith)
            .isEqualTo("TogglePermissionAppInfoPage/test.PERMISSION/package.name/0")
    }

    @Test
    fun getRoute() {
        val route = TogglePermissionAppListPageProvider.getRoute(PERMISSION_TYPE)

        assertThat(route).isEqualTo("TogglePermissionAppList/test.PERMISSION")
    }

    @Test
    fun buildInjectEntry_titleDisplayed() {
        val listModel = TestTogglePermissionAppListModel()
        val entry = TogglePermissionAppListPageProvider.buildInjectEntry(PERMISSION_TYPE) {
            listModel
        }.build()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                entry.UiLayout()
            }
        }

        composeTestRule.onNodeWithText(context.getString(listModel.pageTitleResId))
            .assertIsDisplayed()
    }

    private fun getSummary(
        internalAppListModel: TogglePermissionInternalAppListModel<TestAppRecord>,
    ): State<String> {
        lateinit var summary: State<String>
        composeTestRule.setContent {
            summary = internalAppListModel.getSummary(record = TestAppRecord(APP))
        }
        return summary
    }

    private companion object {
        const val PERMISSION_TYPE = "test.PERMISSION"
        const val PACKAGE_NAME = "package.name"
        const val LABEL = "Label"
        const val SUMMARY = "Summary"
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
        }
    }
}
