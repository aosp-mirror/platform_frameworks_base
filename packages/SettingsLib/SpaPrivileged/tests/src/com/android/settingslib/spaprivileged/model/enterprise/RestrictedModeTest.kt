/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.enterprise

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources.Strings.Settings
import android.app.admin.EnforcingAdmin
import android.content.Context
import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.security.Flags
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.RestrictedLockUtilsInternal
import com.android.settingslib.spaprivileged.framework.common.devicePolicyManager
import com.android.settingslib.spaprivileged.tests.testutils.getEnforcingAdminAdvancedProtection
import com.android.settingslib.spaprivileged.tests.testutils.getEnforcingAdminNotAdvancedProtection
import com.android.settingslib.widget.restricted.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class RestrictedModeTest {
    @Rule
    @JvmField
    val mCheckFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Spy
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var devicePolicyManager: DevicePolicyManager

    private val fakeEnterpriseRepository = object : IEnterpriseRepository {
        override fun getEnterpriseString(updatableStringId: String, resId: Int): String =
            when (updatableStringId) {
                Settings.ENABLED_BY_ADMIN_SWITCH_SUMMARY -> ENABLED_BY_ADMIN
                Settings.DISABLED_BY_ADMIN_SWITCH_SUMMARY -> DISABLED_BY_ADMIN
                else -> ""
            }

        override fun getAdminSummaryString(
            advancedProtectionStringId: Int,
            updatableStringId: String,
            resId: Int,
            enforcedAdmin: RestrictedLockUtils.EnforcedAdmin?,
            userId: Int
        ): String {
            if (RestrictedLockUtilsInternal.isPolicyEnforcedByAdvancedProtection(context,
                    RESTRICTION, userId)) {
                return when (advancedProtectionStringId) {
                    R.string.enabled_by_advanced_protection -> ENABLED_BY_ADVANCED_PROTECTION
                    R.string.disabled_by_advanced_protection -> DISABLED_BY_ADVANCED_PROTECTION
                    else -> ""
                }
            }
            return getEnterpriseString(updatableStringId, resId)
        }
    }

    @Before
    fun setUp() {
        whenever(context.devicePolicyManager).thenReturn(devicePolicyManager)
    }

    @RequiresFlagsDisabled(Flags.FLAG_AAPM_API)
    @Test
    fun blockedByAdmin_getSummaryWhenChecked() {
        val blockedByAdmin = BlockedByAdminImpl(context, ENFORCED_ADMIN, USER_ID,
            fakeEnterpriseRepository)

        val summary = blockedByAdmin.getSummary(true)

        assertThat(summary).isEqualTo(ENABLED_BY_ADMIN)
    }

    @RequiresFlagsDisabled(Flags.FLAG_AAPM_API)
    @Test
    fun blockedByAdmin_getSummaryNotWhenChecked() {
        val blockedByAdmin = BlockedByAdminImpl(context, ENFORCED_ADMIN, USER_ID,
            fakeEnterpriseRepository)

        val summary = blockedByAdmin.getSummary(false)

        assertThat(summary).isEqualTo(DISABLED_BY_ADMIN)
    }

    @RequiresFlagsEnabled(Flags.FLAG_AAPM_API)
    @Test
    fun blockedByAdmin_disabledByAdvancedProtection_getSummaryWhenChecked() {
        val blockedByAdmin =
            BlockedByAdminImpl(
                context = context,
                enforcedAdmin = ENFORCED_ADMIN,
                enterpriseRepository = fakeEnterpriseRepository,
                userId = USER_ID,
            )

        whenever(devicePolicyManager.getEnforcingAdmin(USER_ID, RESTRICTION))
            .thenReturn(ENFORCING_ADMIN_ADVANCED_PROTECTION)

        val summary = blockedByAdmin.getSummary(true)

        assertThat(summary).isEqualTo(ENABLED_BY_ADVANCED_PROTECTION)
    }

    @RequiresFlagsEnabled(Flags.FLAG_AAPM_API)
    @Test
    fun blockedByAdmin_disabledByAdvancedProtection_getSummaryWhenNotChecked() {
        val blockedByAdmin =
            BlockedByAdminImpl(
                context = context,
                enforcedAdmin = ENFORCED_ADMIN,
                enterpriseRepository = fakeEnterpriseRepository,
                userId = USER_ID,
            )

        whenever(devicePolicyManager.getEnforcingAdmin(USER_ID, RESTRICTION))
            .thenReturn(ENFORCING_ADMIN_ADVANCED_PROTECTION)

        val summary = blockedByAdmin.getSummary(false)

        assertThat(summary).isEqualTo(DISABLED_BY_ADVANCED_PROTECTION)
    }

    @RequiresFlagsEnabled(Flags.FLAG_AAPM_API)
    @Test
    fun blockedByAdmin_notDisabledByAdvancedProtection_getSummaryWhenChecked() {
        val blockedByAdmin =
            BlockedByAdminImpl(
                context = context,
                enforcedAdmin = ENFORCED_ADMIN,
                enterpriseRepository = fakeEnterpriseRepository,
                userId = USER_ID,
            )

        whenever(devicePolicyManager.getEnforcingAdmin(USER_ID, RESTRICTION))
            .thenReturn(ENFORCING_ADMIN_NOT_ADVANCED_PROTECTION)

        val summary = blockedByAdmin.getSummary(true)

        assertThat(summary).isEqualTo(ENABLED_BY_ADMIN)
    }

    @RequiresFlagsEnabled(Flags.FLAG_AAPM_API)
    @Test
    fun blockedByAdmin_notDisabledByAdvancedProtection_getSummaryWhenNotChecked() {
        val blockedByAdmin =
            BlockedByAdminImpl(
                context = context,
                enforcedAdmin = ENFORCED_ADMIN,
                enterpriseRepository = fakeEnterpriseRepository,
                userId = USER_ID,
            )

        whenever(devicePolicyManager.getEnforcingAdmin(USER_ID, RESTRICTION))
            .thenReturn(ENFORCING_ADMIN_NOT_ADVANCED_PROTECTION)

        val summary = blockedByAdmin.getSummary(false)

        assertThat(summary).isEqualTo(DISABLED_BY_ADMIN)
    }

    private companion object {
        const val PACKAGE_NAME = "package.name"
        const val RESTRICTION = "restriction"
        const val USER_ID = 0
        val ENFORCED_ADMIN: RestrictedLockUtils.EnforcedAdmin =
            RestrictedLockUtils.EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(RESTRICTION)
        val ENFORCING_ADMIN_ADVANCED_PROTECTION: EnforcingAdmin =
            getEnforcingAdminAdvancedProtection(PACKAGE_NAME, USER_ID)
        val ENFORCING_ADMIN_NOT_ADVANCED_PROTECTION: EnforcingAdmin =
            getEnforcingAdminNotAdvancedProtection(PACKAGE_NAME, USER_ID)

        const val ENABLED_BY_ADMIN = "Enabled by admin"
        const val DISABLED_BY_ADMIN = "Disabled by admin"
        const val ENABLED_BY_ADVANCED_PROTECTION = "Enabled by advanced protection"
        const val DISABLED_BY_ADVANCED_PROTECTION = "Disabled by advanced protection"
    }
}
