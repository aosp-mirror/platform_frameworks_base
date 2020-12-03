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

import android.content.ContentResolver
import android.content.Context
import android.os.UserHandle
import androidx.annotation.VisibleForTesting
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.util.Assert
import java.lang.IllegalStateException

/**
 * Tracks a reference to the context for the current user
 *
 * Constructor is injected at SettingsModule
 */
class CurrentUserContextTracker internal constructor(
    private val sysuiContext: Context,
    broadcastDispatcher: BroadcastDispatcher
) : CurrentUserContentResolverProvider {
    private val userTracker: CurrentUserTracker
    private var initialized = false

    private var _curUserContext: Context? = null
    val currentUserContext: Context
        get() {
            if (!initialized) {
                throw IllegalStateException("Must initialize before getting context")
            }
            return _curUserContext!!
        }

    override val currentUserContentResolver: ContentResolver
        get() = currentUserContext.contentResolver

    init {
        userTracker = object : CurrentUserTracker(broadcastDispatcher) {
            override fun onUserSwitched(newUserId: Int) {
                handleUserSwitched(newUserId)
            }
        }
    }

    fun initialize() {
        initialized = true
        userTracker.startTracking()
        _curUserContext = makeUserContext(userTracker.currentUserId)
    }

    @VisibleForTesting
    fun handleUserSwitched(newUserId: Int) {
        _curUserContext = makeUserContext(newUserId)
    }

    private fun makeUserContext(uid: Int): Context {
        Assert.isMainThread()
        return sysuiContext.createContextAsUser(UserHandle.of(uid), 0)
    }
}