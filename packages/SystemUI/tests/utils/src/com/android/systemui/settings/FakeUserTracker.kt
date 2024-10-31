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
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.util.concurrent.Executor

/** A fake [UserTracker] to be used in tests. */
class FakeUserTracker(
    private var _userId: Int = 0,
    private var _userHandle: UserHandle = UserHandle.of(_userId),
    private var _userInfo: UserInfo = mock(),
    private var _userProfiles: List<UserInfo> = emptyList(),
    private var _isUserSwitching: Boolean = false,
    userContentResolverProvider: () -> ContentResolver = { MockContentResolver() },
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

    override val isUserSwitching: Boolean
        get() = _isUserSwitching

    // userContentResolver is lazy because Ravenwood doesn't support MockContentResolver()
    // and we still want to allow people use this class for tests that don't use it.
    override val userContentResolver: ContentResolver by lazy { userContentResolverProvider() }
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

        onBeforeUserSwitching()
        onUserChanging()
        onUserChanged()
        onProfileChanged()
    }

    fun onBeforeUserSwitching(userId: Int = _userId) {
        val copy = callbacks.toList()
        copy.forEach { it.onBeforeUserSwitching(userId) }
    }

    fun onUserChanging(userId: Int = _userId) {
        _isUserSwitching = true
        val copy = callbacks.toList()
        copy.forEach { it.onUserChanging(userId, userContext) {} }
    }

    fun onUserChanged(userId: Int = _userId) {
        _isUserSwitching = false
        val copy = callbacks.toList()
        copy.forEach { it.onUserChanged(userId, userContext) }
    }

    fun onProfileChanged() {
        callbacks.forEach { it.onProfilesChanged(_userProfiles) }
    }
}

@Module(includes = [FakeUserTrackerModule.Bindings::class])
class FakeUserTrackerModule(
    @get:Provides val fakeUserTracker: FakeUserTracker = FakeUserTracker()
) {
    @Module
    interface Bindings {
        @Binds fun bindFake(fake: FakeUserTracker): UserTracker
    }
}
