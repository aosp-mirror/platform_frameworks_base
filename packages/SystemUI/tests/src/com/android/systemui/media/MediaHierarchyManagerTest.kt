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

import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsControllerImplTest.Companion.eq
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaHierarchyManagerTest : SysuiTestCase() {

    @Mock
    private lateinit var lockHost: MediaHost
    @Mock
    private lateinit var qsHost: MediaHost
    @Mock
    private lateinit var qqsHost: MediaHost
    @Mock
    private lateinit var bypassController: KeyguardBypassController
    @Mock
    private lateinit var mediaFrame: ViewGroup
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var notificationLockscreenUserManager: NotificationLockscreenUserManager
    @Mock
    private lateinit var mediaCarouselController: MediaCarouselController
    @Mock
    private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Captor
    private lateinit var wakefullnessObserver: ArgumentCaptor<(WakefulnessLifecycle.Observer)>
    @JvmField
    @Rule
    val mockito = MockitoJUnit.rule()
    private lateinit var mediaHiearchyManager: MediaHierarchyManager

    @Before
    fun setup() {
        `when`(mediaCarouselController.mediaFrame).thenReturn(mediaFrame)
        mediaHiearchyManager = MediaHierarchyManager(
                context,
                statusBarStateController,
                keyguardStateController,
                bypassController,
                mediaCarouselController,
                notificationLockscreenUserManager,
                wakefulnessLifecycle)
        verify(wakefulnessLifecycle).addObserver(wakefullnessObserver.capture())
        setupHost(lockHost, MediaHierarchyManager.LOCATION_LOCKSCREEN)
        setupHost(qsHost, MediaHierarchyManager.LOCATION_QS)
        setupHost(qqsHost, MediaHierarchyManager.LOCATION_QQS)
        `when`(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        // We'll use the viewmanager to verify a few calls below, let's reset this.
        clearInvocations(mediaCarouselController)

    }

    private fun setupHost(host: MediaHost, location: Int) {
        `when`(host.location).thenReturn(location)
        `when`(host.currentBounds).thenReturn(Rect())
        `when`(host.hostView).thenReturn(UniqueObjectHostView(context))
        mediaHiearchyManager.register(host)
    }

    @Test
    fun testHostViewSetOnRegister() {
        val host = mediaHiearchyManager.register(lockHost)
        verify(lockHost).hostView = eq(host)
    }

    @Test
    fun testBlockedWhenScreenTurningOff() {
        // Let's set it onto QS:
        mediaHiearchyManager.qsExpansion = 1.0f
        verify(mediaCarouselController).onDesiredLocationChanged(ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java), anyBoolean(), anyLong(), anyLong())
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        observer.onStartedGoingToSleep()
        clearInvocations(mediaCarouselController)
        mediaHiearchyManager.qsExpansion = 0.0f
        verify(mediaCarouselController, times(0)).onDesiredLocationChanged(ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java), anyBoolean(), anyLong(), anyLong())
    }

    @Test
    fun testAllowedWhenNotTurningOff() {
        // Let's set it onto QS:
        mediaHiearchyManager.qsExpansion = 1.0f
        verify(mediaCarouselController).onDesiredLocationChanged(ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java), anyBoolean(), anyLong(), anyLong())
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        clearInvocations(mediaCarouselController)
        mediaHiearchyManager.qsExpansion = 0.0f
        verify(mediaCarouselController).onDesiredLocationChanged(ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java), anyBoolean(), anyLong(), anyLong())
    }

    @Test
    fun testCloseGutsRelayToCarousel() {
        mediaHiearchyManager.closeGuts()

        verify(mediaCarouselController).closeGuts()
    }
}