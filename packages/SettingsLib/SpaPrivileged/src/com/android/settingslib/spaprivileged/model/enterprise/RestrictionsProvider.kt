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

import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.RestrictedLockUtilsInternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class Restrictions(
    val userId: Int = UserHandle.myUserId(),
    val keys: List<String>,
)

interface RestrictionsProvider {
    @Composable
    fun restrictedModeState(): State<RestrictedMode?>
}

typealias RestrictionsProviderFactory = (Context, Restrictions) -> RestrictionsProvider

@Composable
internal fun RestrictionsProviderFactory.rememberRestrictedMode(
    restrictions: Restrictions,
): State<RestrictedMode?> {
    val context = LocalContext.current
    val restrictionsProvider = remember(restrictions) {
        this(context, restrictions)
    }
    return restrictionsProvider.restrictedModeState()
}

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
