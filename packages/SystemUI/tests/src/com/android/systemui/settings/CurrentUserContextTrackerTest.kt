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
 * limitations under the License
 */

package com.android.systemui.settings

import android.content.Context
import android.content.ContextWrapper
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class CurrentUserContextTrackerTest : SysuiTestCase() {

    private lateinit var tracker: CurrentUserContextTracker
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        allowTestableLooperAsMainThread()

        // wrap Context so that tests don't throw for missing package errors
        val wrapped = object : ContextWrapper(context) {
            override fun createContextAsUser(user: UserHandle, flags: Int): Context {
                val mockContext = mock(Context::class.java)
                `when`(mockContext.user).thenReturn(user)
                `when`(mockContext.userId).thenReturn(user.identifier)
                return mockContext
            }
        }

        tracker = CurrentUserContextTracker(wrapped, broadcastDispatcher)
        tracker.initialize()
    }

    @Test
    fun testContextExistsAfterInit_noCrash() {
        tracker.currentUserContext
    }

    @Test
    fun testUserContextIsCorrectAfterUserSwitch() {
        // We always start out with system ui test
        assertTrue("Starting userId should be 0", tracker.currentUserContext.userId == 0)

        // WHEN user changes
        tracker.handleUserSwitched(1)

        // THEN user context should have the correct userId
        assertTrue("User has changed to userId 1, the context should reflect that",
                tracker.currentUserContext.userId == 1)
    }

    @Suppress("UNUSED_PARAMETER")
    @Test(expected = IllegalStateException::class)
    fun testContextTrackerThrowsExceptionWhenNotInitialized() {
        // GIVEN an uninitialized CurrentUserContextTracker
        val userTracker = CurrentUserContextTracker(context, broadcastDispatcher)

        // WHEN client asks for a context
        val userContext = userTracker.currentUserContext

        // THEN an exception is thrown
    }
}