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

import android.content.Context
import android.os.UserHandle
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.util.Assert
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks a reference to the context for the current user
 */
@Singleton
class CurrentUserContextTracker @Inject constructor(
    private val sysuiContext: Context,
    broadcastDispatcher: BroadcastDispatcher
) {
    private val userTracker: CurrentUserTracker
    var currentUserContext: Context

    init {
        userTracker = object : CurrentUserTracker(broadcastDispatcher) {
            override fun onUserSwitched(newUserId: Int) {
                handleUserSwitched(newUserId)
            }
        }

        currentUserContext = makeUserContext(userTracker.currentUserId)
    }

    fun initialize() {
        userTracker.startTracking()
    }

    private fun handleUserSwitched(newUserId: Int) {
        currentUserContext = makeUserContext(newUserId)
    }

    private fun makeUserContext(uid: Int): Context {
        Assert.isMainThread()
        return sysuiContext.createContextAsUser(
                UserHandle.getUserHandleForUid(userTracker.currentUserId), 0)
    }
}