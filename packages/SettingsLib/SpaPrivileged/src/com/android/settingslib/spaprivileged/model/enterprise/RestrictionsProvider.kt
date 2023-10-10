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

package com.android.settingslib.spaprivileged.model.enterprise

import android.app.admin.DevicePolicyResources.Strings.Settings
import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.settingslib.RestrictedLockUtilsInternal
import com.android.settingslib.spaprivileged.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class Restrictions(
    val userId: Int,
    val keys: List<String>,
)

sealed interface RestrictedMode

object NoRestricted : RestrictedMode

object BaseUserRestricted : RestrictedMode

interface BlockedByAdmin : RestrictedMode {
    fun getSummary(checked: Boolean?): String
    fun sendShowAdminSupportDetailsIntent()
}

private data class BlockedByAdminImpl(
    private val context: Context,
    private val enforcedAdmin: EnforcedAdmin,
) : BlockedByAdmin {
    private val enterpriseRepository by lazy { EnterpriseRepository(context) }

    override fun getSummary(checked: Boolean?) = when (checked) {
        true -> enterpriseRepository.getEnterpriseString(
            Settings.ENABLED_BY_ADMIN_SWITCH_SUMMARY, R.string.enabled_by_admin
        )
        false -> enterpriseRepository.getEnterpriseString(
            Settings.DISABLED_BY_ADMIN_SWITCH_SUMMARY, R.string.disabled_by_admin
        )
        else -> ""
    }

    override fun sendShowAdminSupportDetailsIntent() {
        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(context, enforcedAdmin)
    }
}

interface RestrictionsProvider {
    @Composable
    fun restrictedModeState(): State<RestrictedMode?>
}

typealias RestrictionsProviderFactory = (Context, Restrictions) -> RestrictionsProvider

internal class RestrictionsProviderImpl(
    private val context: Context,
    private val restrictions: Restrictions,
) : RestrictionsProvider {
    private val userManager by lazy { UserManager.get(context) }

    private val restrictedMode = flow {
        emit(getRestrictedMode())
    }.flowOn(Dispatchers.IO)

    @Composable
    override fun restrictedModeState() =
        restrictedMode.collectAsStateWithLifecycle(initialValue = null)

    private fun getRestrictedMode(): RestrictedMode {
        for (key in restrictions.keys) {
            if (userManager.hasBaseUserRestriction(key, UserHandle.of(restrictions.userId))) {
                return BaseUserRestricted
            }
        }
        for (key in restrictions.keys) {
            RestrictedLockUtilsInternal
                .checkIfRestrictionEnforced(context, key, restrictions.userId)
                ?.let { return BlockedByAdminImpl(context = context, enforcedAdmin = it) }
        }
        return NoRestricted
    }
}
