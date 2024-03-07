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

import android.app.admin.DevicePolicyResources.Strings.Settings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.RestrictedLockUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestrictedModeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fakeEnterpriseRepository = object : IEnterpriseRepository {
        override fun getEnterpriseString(updatableStringId: String, resId: Int): String =
            when (updatableStringId) {
                Settings.ENABLED_BY_ADMIN_SWITCH_SUMMARY -> ENABLED_BY_ADMIN
                Settings.DISABLED_BY_ADMIN_SWITCH_SUMMARY -> DISABLED_BY_ADMIN
                else -> ""
            }
    }

    @Test
    fun blockedByAdmin_getSummaryWhenChecked() {
        val blockedByAdmin = BlockedByAdminImpl(context, ENFORCED_ADMIN, fakeEnterpriseRepository)

        val summary = blockedByAdmin.getSummary(true)

        assertThat(summary).isEqualTo(ENABLED_BY_ADMIN)
    }

    @Test
    fun blockedByAdmin_getSummaryNotWhenChecked() {
        val blockedByAdmin = BlockedByAdminImpl(context, ENFORCED_ADMIN, fakeEnterpriseRepository)

        val summary = blockedByAdmin.getSummary(false)

        assertThat(summary).isEqualTo(DISABLED_BY_ADMIN)
    }

    private companion object {
        const val RESTRICTION = "restriction"
        val ENFORCED_ADMIN: RestrictedLockUtils.EnforcedAdmin =
            RestrictedLockUtils.EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(RESTRICTION)

        const val ENABLED_BY_ADMIN = "Enabled by admin"
        const val DISABLED_BY_ADMIN = "Disabled by admin"
    }
}
