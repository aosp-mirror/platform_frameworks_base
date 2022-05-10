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

package com.android.systemui.statusbar.phone.userswitcher

import android.content.Intent
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class StatusBarUserSwitcherControllerTest : SysuiTestCase() {
    @Mock
    private lateinit var tracker: StatusBarUserInfoTracker

    @Mock
    private lateinit var featureController: StatusBarUserSwitcherFeatureController

    @Mock
    private lateinit var userSwitcherDialogController: UserSwitchDialogController

    @Mock
    private lateinit var featureFlags: FeatureFlags

    @Mock
    private lateinit var activityStarter: ActivityStarter

    @Mock
    private lateinit var falsingManager: FalsingManager

    private lateinit var statusBarUserSwitcherContainer: StatusBarUserSwitcherContainer
    private lateinit var controller: StatusBarUserSwitcherControllerImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        statusBarUserSwitcherContainer = StatusBarUserSwitcherContainer(mContext, null)
        statusBarUserSwitcherContainer
        controller = StatusBarUserSwitcherControllerImpl(
                statusBarUserSwitcherContainer,
                tracker,
                featureController,
                userSwitcherDialogController,
                featureFlags,
                activityStarter,
                falsingManager
        )
        controller.init()
        controller.onViewAttached()
    }

    @Test
    fun testFalsingManager() {
        statusBarUserSwitcherContainer.callOnClick()
        verify(falsingManager).isFalseTap(FalsingManager.LOW_PENALTY)
    }

    @Test
    fun testStartActivity() {
        `when`(featureFlags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)).thenReturn(false)
        statusBarUserSwitcherContainer.callOnClick()
        verify(userSwitcherDialogController).showDialog(any(View::class.java))
        `when`(featureFlags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)).thenReturn(true)
        statusBarUserSwitcherContainer.callOnClick()
        verify(activityStarter).startActivity(any(Intent::class.java),
                eq(true) /* dismissShade */,
                eq(null) /* animationController */,
                eq(true) /* showOverLockscreenWhenLocked */,
                eq(UserHandle.SYSTEM))
    }
}
