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
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor

@SmallTest
@RunWith(AndroidTestingRunner::class)
class UserTrackerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var context: Context
    @Mock
    private lateinit var userManager: UserManager
    @Mock(stubOnly = true)
    private lateinit var dumpManager: DumpManager
    @Mock(stubOnly = true)
    private lateinit var handler: Handler

    private val executor = Executor(Runnable::run)
    private lateinit var tracker: UserTrackerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(context.userId).thenReturn(UserHandle.USER_SYSTEM)
        `when`(context.user).thenReturn(UserHandle.SYSTEM)
        `when`(context.createContextAsUser(any(), anyInt())).thenAnswer { invocation ->
            val user = invocation.getArgument<UserHandle>(0)
            `when`(context.user).thenReturn(user)
            `when`(context.userId).thenReturn(user.identifier)
            context
        }
        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val info = UserInfo(invocation.getArgument<Int>(0), "", UserInfo.FLAG_FULL)
            listOf(info)
        }

        tracker = UserTrackerImpl(context, userManager, dumpManager, handler)
    }

    @Test
    fun testNotInitialized() {
        assertThat(tracker.initialized).isFalse()
    }

    @Test(expected = IllegalStateException::class)
    fun testGetUserIdBeforeInitThrowsException() {
        tracker.userId
    }

    @Test(expected = IllegalStateException::class)
    fun testGetUserHandleBeforeInitThrowsException() {
        tracker.userHandle
    }

    @Test(expected = IllegalStateException::class)
    fun testGetUserContextBeforeInitThrowsException() {
        tracker.userContext
    }

    @Test(expected = IllegalStateException::class)
    fun testGetUserContentResolverBeforeInitThrowsException() {
        tracker.userContentResolver
    }

    @Test(expected = IllegalStateException::class)
    fun testGetUserProfilesBeforeInitThrowsException() {
        tracker.userProfiles
    }

    @Test
    fun testInitialize() {
        tracker.initialize(0)

        assertThat(tracker.initialized).isTrue()
    }

    @Test
    fun testReceiverRegisteredOnInitialize() {
        tracker.initialize(0)

        val captor = ArgumentCaptor.forClass(IntentFilter::class.java)

        verify(context).registerReceiverForAllUsers(
                eq(tracker), capture(captor), isNull(), eq(handler))
    }

    @Test
    fun testInitialValuesSet() {
        val testID = 4
        tracker.initialize(testID)

        verify(userManager).getProfiles(testID)

        assertThat(tracker.userId).isEqualTo(testID)
        assertThat(tracker.userHandle).isEqualTo(UserHandle.of(testID))
        assertThat(tracker.userContext.userId).isEqualTo(testID)
        assertThat(tracker.userContext.user).isEqualTo(UserHandle.of(testID))
        assertThat(tracker.userProfiles).hasSize(1)

        val info = tracker.userProfiles[0]
        assertThat(info.id).isEqualTo(testID)
    }

    @Test
    fun testUserSwitch() {
        tracker.initialize(0)
        val newID = 5

        val intent = Intent(Intent.ACTION_USER_SWITCHED).putExtra(Intent.EXTRA_USER_HANDLE, newID)
        tracker.onReceive(context, intent)

        verify(userManager).getProfiles(newID)

        assertThat(tracker.userId).isEqualTo(newID)
        assertThat(tracker.userHandle).isEqualTo(UserHandle.of(newID))
        assertThat(tracker.userContext.userId).isEqualTo(newID)
        assertThat(tracker.userContext.user).isEqualTo(UserHandle.of(newID))
        assertThat(tracker.userProfiles).hasSize(1)

        val info = tracker.userProfiles[0]
        assertThat(info.id).isEqualTo(newID)
    }

    @Test
    fun testManagedProfileAvailable() {
        tracker.initialize(0)
        val profileID = tracker.userId + 10

        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val info = UserInfo(id, "", UserInfo.FLAG_FULL)
            val infoProfile = UserInfo(
                    id + 10,
                    "",
                    "",
                    UserInfo.FLAG_MANAGED_PROFILE,
                    UserManager.USER_TYPE_PROFILE_MANAGED
            )
            infoProfile.profileGroupId = id
            listOf(info, infoProfile)
        }

        val intent = Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
        tracker.onReceive(context, intent)

        assertThat(tracker.userProfiles.map { it.id }).containsExactly(tracker.userId, profileID)
    }

    fun testManagedProfileUnavailable() {
        tracker.initialize(0)
        val profileID = tracker.userId + 10

        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val info = UserInfo(id, "", UserInfo.FLAG_FULL)
            val infoProfile = UserInfo(
                    id + 10,
                    "",
                    "",
                    UserInfo.FLAG_MANAGED_PROFILE or UserInfo.FLAG_QUIET_MODE,
                    UserManager.USER_TYPE_PROFILE_MANAGED
            )
            infoProfile.profileGroupId = id
            listOf(info, infoProfile)
        }

        val intent = Intent(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
        tracker.onReceive(context, intent)

        assertThat(tracker.userProfiles.map { it.id }).containsExactly(tracker.userId, profileID)
    }

    fun testManagedProfileStartedAndRemoved() {
        tracker.initialize(0)
        val profileID = tracker.userId + 10

        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val info = UserInfo(id, "", UserInfo.FLAG_FULL)
            val infoProfile = UserInfo(
                    id + 10,
                    "",
                    "",
                    UserInfo.FLAG_MANAGED_PROFILE,
                    UserManager.USER_TYPE_PROFILE_MANAGED
            )
            infoProfile.profileGroupId = id
            listOf(info, infoProfile)
        }

        // Managed profile started
        val intent = Intent(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
                .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
        tracker.onReceive(context, intent)

        assertThat(tracker.userProfiles.map { it.id }).containsExactly(tracker.userId, profileID)

        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            listOf(UserInfo(invocation.getArgument(0), "", UserInfo.FLAG_FULL))
        }

        val intent2 = Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
        tracker.onReceive(context, intent2)

        assertThat(tracker.userProfiles.map { it.id }).containsExactly(tracker.userId)
    }

    @Test
    fun testCallbackNotCalledOnAdd() {
        tracker.initialize(0)
        val callback = TestCallback()

        tracker.addCallback(callback, executor)

        assertThat(callback.calledOnProfilesChanged).isEqualTo(0)
        assertThat(callback.calledOnUserChanged).isEqualTo(0)
    }

    @Test
    fun testCallbackCalledOnUserChanged() {
        tracker.initialize(0)
        val callback = TestCallback()
        tracker.addCallback(callback, executor)

        val newID = 5

        val intent = Intent(Intent.ACTION_USER_SWITCHED).putExtra(Intent.EXTRA_USER_HANDLE, newID)
        tracker.onReceive(context, intent)

        assertThat(callback.calledOnUserChanged).isEqualTo(1)
        assertThat(callback.lastUser).isEqualTo(newID)
        assertThat(callback.lastUserContext?.userId).isEqualTo(newID)
        assertThat(callback.calledOnProfilesChanged).isEqualTo(1)
        assertThat(callback.lastUserProfiles.map { it.id }).containsExactly(newID)
    }

    @Test
    fun testCallbackCalledOnProfileChanged() {
        tracker.initialize(0)
        val callback = TestCallback()
        tracker.addCallback(callback, executor)
        val profileID = tracker.userId + 10

        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val info = UserInfo(id, "", UserInfo.FLAG_FULL)
            val infoProfile = UserInfo(
                    id + 10,
                    "",
                    "",
                    UserInfo.FLAG_MANAGED_PROFILE,
                    UserManager.USER_TYPE_PROFILE_MANAGED
            )
            infoProfile.profileGroupId = id
            listOf(info, infoProfile)
        }

        val intent = Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))

        tracker.onReceive(context, intent)

        assertThat(callback.calledOnUserChanged).isEqualTo(0)
        assertThat(callback.calledOnProfilesChanged).isEqualTo(1)
        assertThat(callback.lastUserProfiles.map { it.id }).containsExactly(0, profileID)
    }

    @Test
    fun testCallbackRemoved() {
        tracker.initialize(0)
        val newID = 5
        val profileID = newID + 10

        val callback = TestCallback()
        tracker.addCallback(callback, executor)
        tracker.removeCallback(callback)

        val intent = Intent(Intent.ACTION_USER_SWITCHED).putExtra(Intent.EXTRA_USER_HANDLE, 5)
        tracker.onReceive(context, intent)

        val intentProfiles = Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))

        tracker.onReceive(context, intentProfiles)

        assertThat(callback.calledOnUserChanged).isEqualTo(0)
        assertThat(callback.calledOnProfilesChanged).isEqualTo(0)
    }

    private class TestCallback : UserTracker.Callback {
        var calledOnUserChanged = 0
        var calledOnProfilesChanged = 0
        var lastUser: Int? = null
        var lastUserContext: Context? = null
        var lastUserProfiles = emptyList<UserInfo>()

        override fun onUserChanged(newUser: Int, userContext: Context) {
            calledOnUserChanged++
            lastUser = newUser
            lastUserContext = userContext
        }

        override fun onProfilesChanged(profiles: List<UserInfo>) {
            calledOnProfilesChanged++
            lastUserProfiles = profiles
        }
    }
}