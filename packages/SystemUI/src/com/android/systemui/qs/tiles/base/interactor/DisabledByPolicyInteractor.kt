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

package com.android.systemui.qs.tiles.base.interactor

import android.content.Context
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.settingslib.RestrictedLockUtilsInternal
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor.PolicyResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Provides restrictions data for the tiles. This is used in
 * [com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelImpl] to determine if the tile is
 * disabled based on the [com.android.systemui.qs.tiles.viewmodel.QSTileConfig.policy].
 */
interface DisabledByPolicyInteractor {

    /**
     * Checks if the tile is restricted by the policy for a specific user. Pass the result to the
     * [handlePolicyResult] to let the user know that the tile is disable by the admin.
     */
    suspend fun isDisabled(user: UserHandle, userRestriction: String?): PolicyResult

    /**
     * Returns true when [policyResult] is [PolicyResult.TileDisabled] and has been handled by this
     * method. No further handling is required and the input event can be skipped at this point.
     *
     * Returns false when [policyResult] is [PolicyResult.TileEnabled] and this method has done
     * nothing.
     */
    fun handlePolicyResult(policyResult: PolicyResult): Boolean

    sealed interface PolicyResult {
        /** Tile has no policy restrictions. */
        data object TileEnabled : PolicyResult

        /**
         * Tile is disabled by policy. Pass this to [DisabledByPolicyInteractor.handlePolicyResult]
         * to show the user info screen using
         * [RestrictedLockUtils.getShowAdminSupportDetailsIntent].
         */
        data class TileDisabled(val admin: EnforcedAdmin) : PolicyResult
    }
}

@SysUISingleton
class DisabledByPolicyInteractorImpl
@Inject
constructor(
    private val context: Context,
    private val activityStarter: ActivityStarter,
    private val restrictedLockProxy: RestrictedLockProxy,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : DisabledByPolicyInteractor {

    override suspend fun isDisabled(user: UserHandle, userRestriction: String?): PolicyResult =
        withContext(backgroundDispatcher) {
            val admin: EnforcedAdmin =
                restrictedLockProxy.getEnforcedAdmin(user.identifier, userRestriction)
                    ?: return@withContext PolicyResult.TileEnabled

            return@withContext if (
                !restrictedLockProxy.hasBaseUserRestriction(user.identifier, userRestriction)
            ) {
                PolicyResult.TileDisabled(admin)
            } else {
                PolicyResult.TileEnabled
            }
        }

    override fun handlePolicyResult(policyResult: PolicyResult): Boolean =
        when (policyResult) {
            is PolicyResult.TileEnabled -> false
            is PolicyResult.TileDisabled -> {
                val intent =
                    RestrictedLockUtils.getShowAdminSupportDetailsIntent(policyResult.admin)
                activityStarter.postStartActivityDismissingKeyguard(intent, 0)
                true
            }
        }
}

/** Mockable proxy for [RestrictedLockUtilsInternal] static methods. */
@VisibleForTesting
class RestrictedLockProxy @Inject constructor(private val context: Context) {

    @WorkerThread
    fun hasBaseUserRestriction(userId: Int, userRestriction: String?): Boolean =
        RestrictedLockUtilsInternal.hasBaseUserRestriction(
            context,
            userRestriction,
            userId,
        )

    @WorkerThread
    fun getEnforcedAdmin(userId: Int, userRestriction: String?): EnforcedAdmin? =
        RestrictedLockUtilsInternal.checkIfRestrictionEnforced(
            context,
            userRestriction,
            userId,
        )
}
