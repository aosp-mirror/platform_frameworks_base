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
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.widget.restricted.R

sealed interface RestrictedMode

data object NoRestricted : RestrictedMode

data object BaseUserRestricted : RestrictedMode

interface BlockedByAdmin : RestrictedMode {
    fun getSummary(checked: Boolean?): String
    fun sendShowAdminSupportDetailsIntent()
}

internal data class BlockedByAdminImpl(
    private val context: Context,
    private val enforcedAdmin: RestrictedLockUtils.EnforcedAdmin,
    private val enterpriseRepository: IEnterpriseRepository = EnterpriseRepository(context),
) : BlockedByAdmin {
    override fun getSummary(checked: Boolean?) = when (checked) {
        true -> enterpriseRepository.getEnterpriseString(
            updatableStringId = Settings.ENABLED_BY_ADMIN_SWITCH_SUMMARY,
            resId = R.string.enabled_by_admin,
        )

        false -> enterpriseRepository.getEnterpriseString(
            updatableStringId = Settings.DISABLED_BY_ADMIN_SWITCH_SUMMARY,
            resId = R.string.disabled_by_admin,
        )

        else -> ""
    }

    override fun sendShowAdminSupportDetailsIntent() {
        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(context, enforcedAdmin)
    }
}
