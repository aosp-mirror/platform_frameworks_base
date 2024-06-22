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

package com.android.systemui.statusbar.phone

import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.FakeSystemClock
import junit.framework.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ManagedProfileControllerImplTest : SysuiTestCase() {

    private val mainExecutor: FakeExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var controller: ManagedProfileControllerImpl

    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var userManager: UserManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        controller = ManagedProfileControllerImpl(context, mainExecutor, userTracker, userManager)
    }

    @Test
    fun hasWorkingProfile_isWorkModeEnabled_returnsTrue() {
        `when`(userTracker.userId).thenReturn(1)
        setupWorkingProfile(1)

        Assert.assertEquals(true, controller.hasActiveProfile())
    }

    @Test
    fun noWorkingProfile_isWorkModeEnabled_returnsFalse() {
        `when`(userTracker.userId).thenReturn(1)

        Assert.assertEquals(false, controller.hasActiveProfile())
    }

    @Test
    fun listeningUserChanges_isWorkModeEnabled_returnsTrue() {
        `when`(userTracker.userId).thenReturn(1)
        controller.addCallback(TestCallback)
        `when`(userTracker.userId).thenReturn(2)
        setupWorkingProfile(2)

        Assert.assertEquals(true, controller.hasActiveProfile())
    }

    @Test
    fun callbackRemovedWhileDispatching_doesntCrash() {
        var remove = false
        val callback =
            object : ManagedProfileController.Callback {
                override fun onManagedProfileChanged() {
                    if (remove) {
                        controller.removeCallback(this)
                    }
                }

                override fun onManagedProfileRemoved() {
                    if (remove) {
                        controller.removeCallback(this)
                    }
                }
            }
        controller.addCallback(callback)
        controller.addCallback(TestCallback)

        remove = true
        setupWorkingProfile(1)

        val captor = argumentCaptor<UserTracker.Callback>()
        verify(userTracker).addCallback(capture(captor), any())
        captor.value.onProfilesChanged(userManager.getEnabledProfiles(1))
    }

    private fun setupWorkingProfile(userId: Int) {
        `when`(userManager.getEnabledProfiles(userId))
            .thenReturn(
                listOf(UserInfo(userId, "test_user", "", 0, UserManager.USER_TYPE_PROFILE_MANAGED))
            )
    }

    private object TestCallback : ManagedProfileController.Callback {

        override fun onManagedProfileChanged() = Unit

        override fun onManagedProfileRemoved() = Unit
    }
}
