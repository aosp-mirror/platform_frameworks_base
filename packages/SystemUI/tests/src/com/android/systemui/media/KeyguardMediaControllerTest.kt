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

package com.android.systemui.media

import android.testing.AndroidTestingRunner
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsControllerImplTest.Companion.eq
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaHeaderView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardMediaControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var mediaHost: MediaHost
    @Mock
    private lateinit var bypassController: KeyguardBypassController
    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var notificationLockscreenUserManager: NotificationLockscreenUserManager
    @Mock
    private lateinit var mediaHeaderView: MediaHeaderView
    @Captor
    private lateinit var visibilityListener: ArgumentCaptor<((Boolean) -> Unit)>
    @JvmField @Rule
    val mockito = MockitoJUnit.rule()
    private lateinit var keyguardMediaController: KeyguardMediaController

    @Before
    fun setup() {
        keyguardMediaController = KeyguardMediaController(mediaHost, bypassController,
                statusBarStateController, notificationLockscreenUserManager)
    }

    @Test
    fun testAttach_hiddenWhenHostIsHidden() {
        `when`(mediaHost.visible).thenReturn(false)
        triggerVisibilityListener()

        verify(mediaHeaderView).visibility = eq(GONE)
    }
    @Test
    fun testAttach_visibleOnKeyguard() {
        `when`(mediaHost.visible).thenReturn(true)
        `when`(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        `when`(notificationLockscreenUserManager.shouldShowLockscreenNotifications())
                .thenReturn(true)
        triggerVisibilityListener()

        verify(mediaHeaderView).visibility = eq(VISIBLE)
    }
    @Test
    fun testAttach_hiddenOnKeyguard_whenNotificationsAreHidden() {
        `when`(mediaHost.visible).thenReturn(true)
        `when`(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        `when`(notificationLockscreenUserManager.shouldShowLockscreenNotifications())
                .thenReturn(false)
        triggerVisibilityListener()

        verify(mediaHeaderView).visibility = eq(GONE)
    }

    private fun triggerVisibilityListener() {
        keyguardMediaController.attach(mediaHeaderView)
        verify(mediaHost).visibleChangedListener = visibilityListener.capture()
        visibilityListener.value.invoke(true)
    }
}