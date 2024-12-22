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

import android.app.IActivityManager
import android.app.IUserSwitchObserver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.os.Handler
import android.os.IRemoteCallback
import android.os.UserHandle
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(Parameterized::class)
class UserTrackerImplTest : SysuiTestCase() {

    companion object {

        @JvmStatic
        @Parameterized.Parameters
        fun isBackgroundUserTrackerEnabled(): Iterable<Boolean> = listOf(true, false)
    }

    @Mock private lateinit var context: Context

    @Mock private lateinit var userManager: UserManager

    @Mock private lateinit var iActivityManager: IActivityManager

    @Mock private lateinit var userSwitchingReply: IRemoteCallback

    @Mock(stubOnly = true) private lateinit var dumpManager: DumpManager

    @Mock(stubOnly = true) private lateinit var handler: Handler

    @Parameterized.Parameter @JvmField var isBackgroundUserTrackerEnabled: Boolean = false

    private val testScope = TestScope()
    private val testDispatcher = StandardTestDispatcher(testScope.testScheduler)
    private val executor = Executor(Runnable::run)
    private val featureFlags = FakeFeatureFlagsClassic()

    private lateinit var tracker: UserTrackerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.userId).thenReturn(UserHandle.USER_SYSTEM)
        whenever(context.user).thenReturn(UserHandle.SYSTEM)
        whenever(context.createContextAsUser(any(), anyInt())).thenAnswer { invocation ->
            val user = invocation.getArgument<UserHandle>(0)
            whenever(context.user).thenReturn(user)
            whenever(context.userId).thenReturn(user.identifier)
            context
        }
        whenever(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val info = UserInfo(invocation.getArgument<Int>(0), "", UserInfo.FLAG_FULL)
            listOf(info)
        }

        featureFlags.set(Flags.USER_TRACKER_BACKGROUND_CALLBACKS, isBackgroundUserTrackerEnabled)
        tracker =
            UserTrackerImpl(
                context,
                { featureFlags },
                userManager,
                iActivityManager,
                dumpManager,
                testScope.backgroundScope,
                testDispatcher,
                handler,
            )
    }

    @Test fun testNotInitialized() = testScope.runTest { assertThat(tracker.initialized).isFalse() }

    @Test(expected = IllegalStateException::class)
    fun testGetUserIdBeforeInitThrowsException() = testScope.runTest { tracker.userId }

    @Test(expected = IllegalStateException::class)
    fun testGetUserHandleBeforeInitThrowsException() = testScope.runTest { tracker.userHandle }

    @Test(expected = IllegalStateException::class)
    fun testGetUserContextBeforeInitThrowsException() = testScope.runTest { tracker.userContext }

    @Test(expected = IllegalStateException::class)
    fun testGetUserContentResolverBeforeInitThrowsException() =
        testScope.runTest { tracker.userContentResolver }

    @Test(expected = IllegalStateException::class)
    fun testGetUserProfilesBeforeInitThrowsException() = testScope.runTest { tracker.userProfiles }

    @Test
    fun testInitialize() =
        testScope.runTest {
            tracker.initialize(0)

            assertThat(tracker.initialized).isTrue()
        }

    @Test
    fun testReceiverRegisteredOnInitialize() =
        testScope.runTest {
            tracker.initialize(0)

            val captor = ArgumentCaptor.forClass(IntentFilter::class.java)

            verify(context)
                .registerReceiverForAllUsers(eq(tracker), capture(captor), isNull(), eq(handler))
            with(captor.value) {
                assertThat(countActions()).isEqualTo(11)
                assertThat(hasAction(Intent.ACTION_LOCALE_CHANGED)).isTrue()
                assertThat(hasAction(Intent.ACTION_USER_INFO_CHANGED)).isTrue()
                assertThat(hasAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)).isTrue()
                assertThat(hasAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)).isTrue()
                assertThat(hasAction(Intent.ACTION_MANAGED_PROFILE_ADDED)).isTrue()
                assertThat(hasAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)).isTrue()
                assertThat(hasAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)).isTrue()
                assertThat(hasAction(Intent.ACTION_PROFILE_ADDED)).isTrue()
                assertThat(hasAction(Intent.ACTION_PROFILE_REMOVED)).isTrue()
                assertThat(hasAction(Intent.ACTION_PROFILE_AVAILABLE)).isTrue()
                assertThat(hasAction(Intent.ACTION_PROFILE_UNAVAILABLE)).isTrue()
            }
        }

    @Test
    fun testInitialValuesSet() =
        testScope.runTest {
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
    fun testUserSwitch() =
        testScope.runTest {
            tracker.initialize(0)
            val newID = 5

            val captor = ArgumentCaptor.forClass(IUserSwitchObserver::class.java)
            verify(iActivityManager).registerUserSwitchObserver(capture(captor), anyString())
            captor.value.onBeforeUserSwitching(newID)
            captor.value.onUserSwitching(newID, userSwitchingReply)
            runCurrent()
            verify(userSwitchingReply).sendResult(any())

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
    fun testManagedProfileAvailable() =
        testScope.runTest {
            tracker.initialize(0)
            val profileID = tracker.userId + 10

            whenever(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
                val id = invocation.getArgument<Int>(0)
                val info = UserInfo(id, "", UserInfo.FLAG_FULL)
                val infoProfile =
                    UserInfo(
                        id + 10,
                        "",
                        "",
                        UserInfo.FLAG_MANAGED_PROFILE,
                        UserManager.USER_TYPE_PROFILE_MANAGED,
                    )
                infoProfile.profileGroupId = id
                listOf(info, infoProfile)
            }

            val intent =
                Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
            tracker.onReceive(context, intent)

            assertThat(tracker.userProfiles.map { it.id })
                .containsExactly(tracker.userId, profileID)
        }

    @Test
    fun testManagedProfileUnavailable() =
        testScope.runTest {
            tracker.initialize(0)
            val profileID = tracker.userId + 10

            whenever(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
                val id = invocation.getArgument<Int>(0)
                val info = UserInfo(id, "", UserInfo.FLAG_FULL)
                val infoProfile =
                    UserInfo(
                        id + 10,
                        "",
                        "",
                        UserInfo.FLAG_MANAGED_PROFILE or UserInfo.FLAG_QUIET_MODE,
                        UserManager.USER_TYPE_PROFILE_MANAGED,
                    )
                infoProfile.profileGroupId = id
                listOf(info, infoProfile)
            }

            val intent =
                Intent(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
            tracker.onReceive(context, intent)

            assertThat(tracker.userProfiles.map { it.id })
                .containsExactly(tracker.userId, profileID)
        }

    @Test
    fun testManagedProfileStartedAndRemoved() =
        testScope.runTest {
            tracker.initialize(0)
            val profileID = tracker.userId + 10

            whenever(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
                val id = invocation.getArgument<Int>(0)
                val info = UserInfo(id, "", UserInfo.FLAG_FULL)
                val infoProfile =
                    UserInfo(
                        id + 10,
                        "",
                        "",
                        UserInfo.FLAG_MANAGED_PROFILE,
                        UserManager.USER_TYPE_PROFILE_MANAGED,
                    )
                infoProfile.profileGroupId = id
                listOf(info, infoProfile)
            }

            // Managed profile started
            val intent =
                Intent(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
            tracker.onReceive(context, intent)

            assertThat(tracker.userProfiles.map { it.id })
                .containsExactly(tracker.userId, profileID)

            whenever(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
                listOf(UserInfo(invocation.getArgument(0), "", UserInfo.FLAG_FULL))
            }

            val intent2 =
                Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))
            tracker.onReceive(context, intent2)

            assertThat(tracker.userProfiles.map { it.id }).containsExactly(tracker.userId)
        }

    @Test
    fun testCallbackNotCalledOnAdd() =
        testScope.runTest {
            tracker.initialize(0)
            val callback = TestCallback()

            tracker.addCallback(callback, executor)

            assertThat(callback.calledOnProfilesChanged).isEqualTo(0)
            assertThat(callback.calledOnUserChanged).isEqualTo(0)
        }

    @Test
    fun testCallbackCalledOnUserChanging() =
        testScope.runTest {
            tracker.initialize(0)
            val callback = TestCallback()
            tracker.addCallback(callback, executor)

            val newID = 5

            val captor = ArgumentCaptor.forClass(IUserSwitchObserver::class.java)
            verify(iActivityManager).registerUserSwitchObserver(capture(captor), anyString())
            captor.value.onBeforeUserSwitching(newID)
            captor.value.onUserSwitching(newID, userSwitchingReply)
            runCurrent()

            verify(userSwitchingReply).sendResult(any())
            assertThat(callback.calledOnUserChanging).isEqualTo(1)
            assertThat(callback.lastUser).isEqualTo(newID)
            assertThat(callback.lastUserContext?.userId).isEqualTo(newID)
        }

    @Test
    fun testAsyncCallbackWaitsUserToChange() =
        testScope.runTest {
            // Skip this test for CountDownLatch variation. The problem is that there would be a
            // deadlock if the callbacks processing runs on the same thread as the callback (which
            // is blocked by the latch). Before the change it works because the callbacks are
            // processed on a binder thread which is always distinct.
            // This is the issue that this feature addresses.
            assume().that(isBackgroundUserTrackerEnabled).isTrue()

            tracker.initialize(0)
            val callback = TestCallback()
            val callbackExecutor = FakeExecutor(FakeSystemClock())
            tracker.addCallback(callback, callbackExecutor)

            val newID = 5

            val captor = ArgumentCaptor.forClass(IUserSwitchObserver::class.java)
            verify(iActivityManager).registerUserSwitchObserver(capture(captor), anyString())
            captor.value.onUserSwitching(newID, userSwitchingReply)

            assertThat(callback.calledOnUserChanging).isEqualTo(0)
            verify(userSwitchingReply, never()).sendResult(any())

            FakeExecutor.exhaustExecutors(callbackExecutor)
            runCurrent()
            FakeExecutor.exhaustExecutors(callbackExecutor)
            runCurrent()

            assertThat(callback.calledOnUserChanging).isEqualTo(1)
            verify(userSwitchingReply).sendResult(any())
        }

    @Test
    fun testCallbackCalledOnUserChanged() =
        testScope.runTest {
            tracker.initialize(0)
            val callback = TestCallback()
            tracker.addCallback(callback, executor)

            val newID = 5

            val captor = ArgumentCaptor.forClass(IUserSwitchObserver::class.java)
            verify(iActivityManager).registerUserSwitchObserver(capture(captor), anyString())
            captor.value.onBeforeUserSwitching(newID)
            captor.value.onUserSwitchComplete(newID)
            runCurrent()

            assertThat(callback.calledOnUserChanged).isEqualTo(1)
            assertThat(callback.lastUser).isEqualTo(newID)
            assertThat(callback.lastUserContext?.userId).isEqualTo(newID)
            assertThat(callback.calledOnProfilesChanged).isEqualTo(1)
            assertThat(callback.lastUserProfiles.map { it.id }).containsExactly(newID)
        }

    @Test
    fun testCallbackCalledOnUserInfoChanged() =
        testScope.runTest {
            tracker.initialize(0)
            val callback = TestCallback()
            tracker.addCallback(callback, executor)
            val profileID = tracker.userId + 10

            whenever(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
                val id = invocation.getArgument<Int>(0)
                val info = UserInfo(id, "", UserInfo.FLAG_FULL)
                val infoProfile =
                    UserInfo(
                        id + 10,
                        "",
                        "",
                        UserInfo.FLAG_MANAGED_PROFILE,
                        UserManager.USER_TYPE_PROFILE_MANAGED,
                    )
                infoProfile.profileGroupId = id
                listOf(info, infoProfile)
            }

            val intent =
                Intent(Intent.ACTION_USER_INFO_CHANGED)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))

            tracker.onReceive(context, intent)

            assertThat(callback.calledOnUserChanged).isEqualTo(0)
            assertThat(callback.calledOnProfilesChanged).isEqualTo(1)
            assertThat(callback.lastUserProfiles.map { it.id }).containsExactly(0, profileID)
        }

    @Test
    fun testCallbackRemoved() =
        testScope.runTest {
            tracker.initialize(0)
            val newID = 5
            val profileID = newID + 10

            val callback = TestCallback()
            tracker.addCallback(callback, executor)
            tracker.removeCallback(callback)

            val captor = ArgumentCaptor.forClass(IUserSwitchObserver::class.java)
            verify(iActivityManager).registerUserSwitchObserver(capture(captor), anyString())
            captor.value.onUserSwitching(newID, userSwitchingReply)
            runCurrent()
            verify(userSwitchingReply).sendResult(any())
            captor.value.onUserSwitchComplete(newID)

            val intentProfiles =
                Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(profileID))

            tracker.onReceive(context, intentProfiles)

            assertThat(callback.calledOnUserChanging).isEqualTo(0)
            assertThat(callback.calledOnUserChanged).isEqualTo(0)
            assertThat(callback.calledOnProfilesChanged).isEqualTo(0)
        }

    @Test
    fun testisUserSwitching() =
        testScope.runTest {
            tracker.initialize(0)
            val newID = 5
            val profileID = newID + 10

            val captor = ArgumentCaptor.forClass(IUserSwitchObserver::class.java)
            verify(iActivityManager).registerUserSwitchObserver(capture(captor), anyString())
            assertThat(tracker.isUserSwitching).isFalse()

            captor.value.onUserSwitching(newID, userSwitchingReply)
            assertThat(tracker.isUserSwitching).isTrue()

            captor.value.onUserSwitchComplete(newID)
            assertThat(tracker.isUserSwitching).isFalse()
        }

    private class TestCallback : UserTracker.Callback {
        var calledOnUserChanging = 0
        var calledOnUserChanged = 0
        var calledOnProfilesChanged = 0
        var lastUser: Int? = null
        var lastUserContext: Context? = null
        var lastUserProfiles = emptyList<UserInfo>()

        override fun onUserChanging(newUser: Int, userContext: Context) {
            calledOnUserChanging++
            lastUser = newUser
            lastUserContext = userContext
        }

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
