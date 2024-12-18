/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.brightness.data.repository

import android.content.Context
import android.os.UserManager
import com.android.settingslib.RestrictedLockUtils
import com.android.systemui.Flags.enforceBrightnessBaseUserRestriction
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.utils.PolicyRestriction
import com.android.systemui.utils.UserRestrictionChecker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/** Checks whether the current user is restricted to change the brightness ([RESTRICTION]) */
interface BrightnessPolicyRepository {

    /**
     * Indicates whether the current user is restricted to change the brightness. As there is no way
     * to determine when a restriction has been added/removed. This value may be fetched eagerly and
     * not updated (unless the user changes) per flow.
     */
    val restrictionPolicy: Flow<PolicyRestriction>

    companion object {
        const val RESTRICTION = UserManager.DISALLOW_CONFIG_BRIGHTNESS
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class BrightnessPolicyRepositoryImpl
@Inject
constructor(
    userRepository: UserRepository,
    private val userRestrictionChecker: UserRestrictionChecker,
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : BrightnessPolicyRepository {
    override val restrictionPolicy =
        userRepository.selectedUserInfo
            .mapLatest { user ->
                userRestrictionChecker
                    .checkIfRestrictionEnforced(
                        applicationContext,
                        BrightnessPolicyRepository.RESTRICTION,
                        user.id
                    )
                    ?.let { PolicyRestriction.Restricted(it) }
                    ?: if (
                        enforceBrightnessBaseUserRestriction() &&
                            userRestrictionChecker.hasBaseUserRestriction(
                                applicationContext,
                                UserManager.DISALLOW_CONFIG_BRIGHTNESS,
                                user.id
                            )
                    ) {
                        PolicyRestriction.Restricted(RestrictedLockUtils.EnforcedAdmin())
                    } else {
                        PolicyRestriction.NoRestriction
                    }
            }
            .flowOn(backgroundDispatcher)
}
