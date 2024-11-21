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

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources.Strings.Settings
import android.app.admin.DevicePolicyResourcesManager
import android.app.admin.EnforcingAdmin
import android.content.Context
import android.content.pm.ApplicationInfo
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.security.Flags
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.spa.testutils.FakeNavControllerWrapper
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.framework.common.devicePolicyManager
import com.android.settingslib.spaprivileged.framework.compose.getPlaceholder
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdminImpl
import com.android.settingslib.spaprivileged.model.enterprise.NoRestricted
import com.android.settingslib.spaprivileged.tests.testutils.FakeRestrictionsProvider
import com.android.settingslib.spaprivileged.tests.testutils.TestAppRecord
import com.android.settingslib.spaprivileged.tests.testutils.TestTogglePermissionAppListModel
import com.android.settingslib.spaprivileged.tests.testutils.getEnforcingAdminAdvancedProtection
import com.android.settingslib.spaprivileged.tests.testutils.getEnforcingAdminNotAdvancedProtection
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class TogglePermissionAppListPageTest {
    @Rule
    @JvmField
    val mCheckFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var devicePolicyManager: DevicePolicyManager

    @Mock
    private lateinit var devicePolicyResourcesManager: DevicePolicyResourcesManager

    @Spy
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fakeNavControllerWrapper = FakeNavControllerWrapper()

    private val fakeRestrictionsProvider = FakeRestrictionsProvider()

    @Before
    fun setUp() {
        whenever(context.devicePolicyManager).thenReturn(devicePolicyManager)
        whenever(devicePolicyManager.resources).thenReturn(devicePolicyResourcesManager)
    }

    @Test
    fun pageTitle() {
        val listModel = TestTogglePermissionAppListModel()

        composeTestRule.setContent {
            listModel.TogglePermissionAppList(
                permissionType = PERMISSION_TYPE,
                restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
                appList = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(listModel.pageTitleResId))
            .assertIsDisplayed()
    }

    @Test
    fun summary_whenAllowed() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        val listModel = TestTogglePermissionAppListModel(isAllowed = true)

        val summary = getSummary(listModel)

        assertThat(summary).isEqualTo(context.getString(R.string.app_permission_summary_allowed))
    }

    @Test
    fun summary_whenNotAllowed() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        val listModel = TestTogglePermissionAppListModel(isAllowed = false)

        val summary = getSummary(listModel)

        assertThat(summary)
            .isEqualTo(context.getString(R.string.app_permission_summary_not_allowed))
    }

    @Test
    fun summary_whenComputingAllowed() {
        fakeRestrictionsProvider.restrictedMode = NoRestricted
        val listModel = TestTogglePermissionAppListModel(isAllowed = null)

        val summary = getSummary(listModel)

        assertThat(summary).isEqualTo(context.getPlaceholder())
    }

    @RequiresFlagsDisabled(Flags.FLAG_AAPM_API)
    @Test
    fun summary_whenAllowedButAdminOverrideToNotAllowed() {
        fakeRestrictionsProvider.restrictedMode =
            BlockedByAdminImpl(context = context, enforcedAdmin = ENFORCED_ADMIN, userId = USER_ID)
        val listModel =
            TestTogglePermissionAppListModel(
                isAllowed = true,
                switchifBlockedByAdminOverrideCheckedValueTo = false,
            )

        val summary = getSummary(listModel)

        assertThat(summary)
            .isEqualTo(
                context.getString(
                    com.android.settingslib.widget.restricted.R.string.disabled_by_admin
                )
            )
    }

    @RequiresFlagsEnabled(Flags.FLAG_AAPM_API)
    @Test
    fun summary_disabledByAdvancedProtection_whenAllowedButAdminOverrideToNotAllowed() {
        whenever(devicePolicyManager.getEnforcingAdmin(USER_ID, RESTRICTION))
            .thenReturn(ENFORCING_ADMIN_ADVANCED_PROTECTION)

        fakeRestrictionsProvider.restrictedMode =
            BlockedByAdminImpl(context = context, enforcedAdmin = ENFORCED_ADMIN, userId = USER_ID)
        val listModel =
            TestTogglePermissionAppListModel(
                isAllowed = true,
                switchifBlockedByAdminOverrideCheckedValueTo = false,
            )

        val summary = getSummary(listModel)

        assertThat(summary)
            .isEqualTo(
                context.getString(
                    com.android.settingslib.widget.restricted.R.string
                        .disabled_by_advanced_protection
                )
            )
    }

    @RequiresFlagsEnabled(Flags.FLAG_AAPM_API)
    @Test
    fun summary_notDisabledByAdvancedProtection_whenAllowedButAdminOverrideToNotAllowed() {
        val disabledByAdminText = context.getString(
            com.android.settingslib.widget.restricted.R.string.disabled_by_admin
        )
        whenever(devicePolicyManager.getEnforcingAdmin(USER_ID, RESTRICTION))
            .thenReturn(ENFORCING_ADMIN_NOT_ADVANCED_PROTECTION)
        whenever(devicePolicyResourcesManager.getString(
            eq(Settings.DISABLED_BY_ADMIN_SWITCH_SUMMARY), any())).thenReturn(disabledByAdminText)

        fakeRestrictionsProvider.restrictedMode =
            BlockedByAdminImpl(context = context, enforcedAdmin = ENFORCED_ADMIN, userId = USER_ID)
        val listModel =
            TestTogglePermissionAppListModel(
                isAllowed = true,
                switchifBlockedByAdminOverrideCheckedValueTo = false,
            )

        val summary = getSummary(listModel)

        assertThat(summary)
            .isEqualTo(
                context.getString(
                    com.android.settingslib.widget.restricted.R.string.disabled_by_admin
                )
            )
    }

    @Test
    fun appListItem_onClick_navigate() {
        val listModel = TestTogglePermissionAppListModel()
        composeTestRule.setContent {
            fakeNavControllerWrapper.Wrapper {
                with(createInternalAppListModel(listModel)) {
                    AppListItemModel(
                        record = listModel.transformItem(APP),
                        label = LABEL,
                        summary = { SUMMARY },
                    ).AppItem()
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

    private fun createInternalAppListModel(listModel: TestTogglePermissionAppListModel) =
        TogglePermissionInternalAppListModel(
            context = context,
            permissionType = PERMISSION_TYPE,
            listModel = listModel,
            restrictionsProviderFactory = { _, _ -> fakeRestrictionsProvider },
        )

    private fun getSummary(listModel: TestTogglePermissionAppListModel): String {
        lateinit var summary: () -> String
        composeTestRule.setContent {
            summary = createInternalAppListModel(listModel).getSummary(record = TestAppRecord(APP))
        }
        return summary()
    }

    private companion object {
        const val PERMISSION_TYPE = "test.PERMISSION"
        const val PACKAGE_NAME = "package.name"
        const val LABEL = "Label"
        const val SUMMARY = "Summary"
        val APP = ApplicationInfo().apply { packageName = PACKAGE_NAME }
        const val RESTRICTION = "restriction"
        const val USER_ID = 0
        val ENFORCED_ADMIN: RestrictedLockUtils.EnforcedAdmin =
            RestrictedLockUtils.EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(RESTRICTION)
        val ENFORCING_ADMIN_ADVANCED_PROTECTION: EnforcingAdmin =
            getEnforcingAdminAdvancedProtection(PACKAGE_NAME, USER_ID)
        val ENFORCING_ADMIN_NOT_ADVANCED_PROTECTION: EnforcingAdmin =
            getEnforcingAdminNotAdvancedProtection(PACKAGE_NAME, USER_ID)
    }
}
