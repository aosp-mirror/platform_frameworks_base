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

package com.android.systemui.settings

import android.content.ContentResolver
import android.content.Context
import android.content.pm.UserInfo
import android.os.UserHandle
import android.test.mock.MockContentResolver
import com.android.systemui.util.mockito.mock
import java.util.concurrent.Executor

/** A fake [UserTracker] to be used in tests. */
class FakeUserTracker(
    private var _userId: Int = 0,
    private var _userHandle: UserHandle = UserHandle.of(_userId),
    private var _userInfo: UserInfo = mock(),
    private var _userProfiles: List<UserInfo> = emptyList(),
    userContentResolver: ContentResolver = MockContentResolver(),
    userContext: Context = mock(),
    private val onCreateCurrentUserContext: (Context) -> Context = { mock() },
) : UserTracker {
    val callbacks = mutableListOf<UserTracker.Callback>()

    override val userId: Int
        get() = _userId
    override val userHandle: UserHandle
        get() = _userHandle
    override val userInfo: UserInfo
        get() = _userInfo
    override val userProfiles: List<UserInfo>
        get() = _userProfiles

    override val userContentResolver: ContentResolver = userContentResolver
    override val userContext: Context = userContext

    override fun addCallback(callback: UserTracker.Callback, executor: Executor) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: UserTracker.Callback) {
        callbacks.remove(callback)
    }

    override fun createCurrentUserContext(context: Context): Context {
        return onCreateCurrentUserContext(context)
    }

    fun set(userInfos: List<UserInfo>, selectedUserIndex: Int) {
        _userProfiles = userInfos
        _userInfo = userInfos[selectedUserIndex]
        _userId = _userInfo.id
        _userHandle = UserHandle.of(_userId)

        val copy = callbacks.toList()
        copy.forEach { it.onUserChanged(_userId, userContext) }
    }

    fun onProfileChanged() {
        callbacks.forEach { it.onProfilesChanged(_userProfiles) }
    }
}
