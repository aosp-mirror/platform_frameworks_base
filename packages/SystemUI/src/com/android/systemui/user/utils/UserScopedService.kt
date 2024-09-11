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

package com.android.systemui.user.utils

import android.content.Context
import android.os.UserHandle
import androidx.core.content.getSystemService
import com.android.systemui.dagger.qualifiers.Application

/**
 * Provides instances of a [system service][Context.getSystemService] created with
 * [the context of a specified user][Context.createContextAsUser].
 *
 * Some services which have only `@UserHandleAware` APIs operate on the user id available from
 * [Context.getUser], the context used to retrieve the service. This utility helps adapt a per-user
 * API model to work in multi-user manner.
 *
 * Example usage:
 * ```
 *     @Provides
 *     fun scopedUserManager(@Application ctx: Context): UserScopedService<UserManager> {
 *         return UserScopedServiceImpl(ctx, UserManager::class)
 *     }
 *
 *     class MyUserHelper @Inject constructor(
 *         private val userMgr: UserScopedService<UserManager>,
 *     ) {
 *         fun isPrivateProfile(user: UserHandle): UserManager {
 *             return userMgr.forUser(user).isPrivateProfile()
 *         }
 *     }
 * ```
 */
fun interface UserScopedService<T> {
    /** Create a service instance for the given user. */
    fun forUser(user: UserHandle): T
}

class UserScopedServiceImpl<T : Any>(
    @Application private val context: Context,
    private val serviceType: Class<T>,
) : UserScopedService<T> {
    override fun forUser(user: UserHandle): T {
        val context =
            if (context.user == user) {
                context
            } else {
                context.createContextAsUser(user, 0)
            }
        return requireNotNull(context.getSystemService(serviceType))
    }
}
