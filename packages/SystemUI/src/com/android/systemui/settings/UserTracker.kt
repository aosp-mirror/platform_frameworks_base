/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings

import com.android.systemui.util.annotations.WeaklyReferencedCallback

import android.content.Context
import android.content.pm.UserInfo
import android.os.UserHandle
import java.util.concurrent.Executor

/**
 * User tracker for SystemUI.
 *
 * This tracker provides async access to current user information, as well as callbacks for
 * user/profile change.
 */
interface UserTracker : UserContentResolverProvider, UserContextProvider {

    /**
     * Current user's id.
     */
    val userId: Int

    /**
     * [UserHandle] for current user
     */
    val userHandle: UserHandle

    /**
     * [UserInfo] for current user
     */
    val userInfo: UserInfo

    /**
     * List of profiles associated with the current user.
     *
     * Quiet work profiles will still appear here, but will have the `QUIET_MODE` flag.
     *
     * Disabled work profiles will also appear here. Listeners will be notified when profiles go
     * from disabled to enabled (as UserInfo are immutable) with the updated list.
     */
    val userProfiles: List<UserInfo>

    /**
     * Add a [Callback] to be notified of chances, on a particular [Executor]
     */
    fun addCallback(callback: Callback, executor: Executor)

    /**
     * Remove a [Callback] previously added.
     */
    fun removeCallback(callback: Callback)

    /**
     * Callback for notifying of changes.
     */
    @WeaklyReferencedCallback
    interface Callback {
        /**
         * Notifies that the current user will be changed.
         */
        fun onBeforeUserSwitching(newUser: Int) {}

        /**
         * Same as {@link onUserChanging(Int, Context, Runnable)} but the callback will be
         * called automatically after the completion of this method.
         */
        fun onUserChanging(newUser: Int, userContext: Context) {}

        /**
         * Notifies that the current user is being changed.
         * Override this method to run things while the screen is frozen for the user switch.
         * Please use {@link #onUserChanged} if the task doesn't need to push the unfreezing of the
         * screen further. Please be aware that code executed in this callback will lengthen the
         * user switch duration. When overriding this method, resultCallback#run() MUST be called
         * once the  execution is complete.
         */
        fun onUserChanging(newUser: Int, userContext: Context, resultCallback: Runnable) {
            onUserChanging(newUser, userContext)
            resultCallback.run()
        }

        /**
         * Notifies that the current user has changed.
         * Override this method to run things after the screen is unfrozen for the user switch.
         * Please see {@link #onUserChanging} if you need to hide jank.
         */
        fun onUserChanged(newUser: Int, userContext: Context) {}

        /**
         * Notifies that the current user's profiles have changed.
         */
        fun onProfilesChanged(profiles: List<@JvmSuppressWildcards UserInfo>) {}
    }
}
