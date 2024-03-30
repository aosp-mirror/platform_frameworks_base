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
@file:Suppress("MissingPermission")

package com.android.systemui.screenshot.data.repository

import android.annotation.UserIdInt
import android.os.UserManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenshot.data.model.ProfileType
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fetches profile types from [UserManager] as needed, caching results for a given user. */
class ProfileTypeRepositoryImpl
@Inject
constructor(
    private val userManager: UserManager,
    @Background private val background: CoroutineDispatcher
) : ProfileTypeRepository {
    /** Cache to avoid repeated requests to IActivityTaskManager for the same userId */
    private val cache = mutableMapOf<Int, ProfileType>()
    private val mutex = Mutex()

    override suspend fun getProfileType(@UserIdInt userId: Int): ProfileType {
        return mutex.withLock {
            cache[userId]
                ?: withContext(background) {
                        val userType = userManager.getUserInfo(userId).userType
                        when (userType) {
                            UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileType.WORK
                            UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileType.PRIVATE
                            UserManager.USER_TYPE_PROFILE_CLONE -> ProfileType.CLONE
                            UserManager.USER_TYPE_PROFILE_COMMUNAL -> ProfileType.COMMUNAL
                            else -> ProfileType.NONE
                        }
                    }
                    .also { cache[userId] = it }
        }
    }
}
