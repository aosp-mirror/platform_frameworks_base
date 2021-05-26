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
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsControllerImplTest.Companion.eq
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import junit.framework.Assert
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
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var notificationLockscreenUserManager: NotificationLockscreenUserManager
    @Mock
    private lateinit var mediaCarouselController: MediaCarouselController
    @Mock
    private lateinit var mediaCarouselScrollHandler: MediaCarouselScrollHandler
    @Mock
    private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock
    private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Captor
    private lateinit var wakefullnessObserver: ArgumentCaptor<(WakefulnessLifecycle.Observer)>
    @Captor
    private lateinit var statusBarCallback: ArgumentCaptor<(StatusBarStateController.StateListener)>
    @JvmField
    @Rule
    val mockito = MockitoJUnit.rule()
    private lateinit var mediaHiearchyManager: MediaHierarchyManager
    private lateinit var mediaFrame: ViewGroup

    @Before
    fun setup() {
        mediaFrame = FrameLayout(context)
        `when`(mediaCarouselController.mediaFrame).thenReturn(mediaFrame)
        mediaHiearchyManager = MediaHierarchyManager(
                context,
                statusBarStateController,
                keyguardStateController,
                bypassController,
                mediaCarouselController,
                notificationLockscreenUserManager,
                configurationController,
                wakefulnessLifecycle,
                statusBarKeyguardViewManager)
        verify(wakefulnessLifecycle).addObserver(wakefullnessObserver.capture())
        verify(statusBarStateController).addCallback(statusBarCallback.capture())
        setupHost(lockHost, MediaHierarchyManager.LOCATION_LOCKSCREEN)
        setupHost(qsHost, MediaHierarchyManager.LOCATION_QS)
        setupHost(qqsHost, MediaHierarchyManager.LOCATION_QQS)
        `when`(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        `when`(mediaCarouselController.mediaCarouselScrollHandler)
                .thenReturn(mediaCarouselScrollHandler)
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        observer.onFinishedWakingUp()
        // We'll use the viewmanager to verify a few calls below, let's reset this.
        clearInvocations(mediaCarouselController)
    }

    private fun setupHost(host: MediaHost, location: Int) {
        `when`(host.location).thenReturn(location)
        `when`(host.currentBounds).thenReturn(Rect())
        `when`(host.hostView).thenReturn(UniqueObjectHostView(context))
        `when`(host.visible).thenReturn(true)
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
        verify(mediaCarouselController, times(0))
                .onDesiredLocationChanged(ArgumentMatchers.anyInt(),
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
    fun testGoingToFullShade() {
        // Let's set it onto Lock screen
        `when`(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        `when`(notificationLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(
            true)
        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        clearInvocations(mediaCarouselController)

        // Let's transition all the way to full shade
        mediaHiearchyManager.setTransitionToFullShadeAmount(100000f)
        verify(mediaCarouselController).onDesiredLocationChanged(
            eq(MediaHierarchyManager.LOCATION_QQS),
            any(MediaHostState::class.java),
            eq(false),
            anyLong(),
            anyLong())
        clearInvocations(mediaCarouselController)

        // Let's go back to the lock screen
        mediaHiearchyManager.setTransitionToFullShadeAmount(0.0f)
        verify(mediaCarouselController).onDesiredLocationChanged(
            eq(MediaHierarchyManager.LOCATION_LOCKSCREEN),
            any(MediaHostState::class.java),
            eq(false),
            anyLong(),
            anyLong())

        // Let's make sure alpha is set
        mediaHiearchyManager.setTransitionToFullShadeAmount(2.0f)
        Assert.assertTrue("alpha should not be 1.0f when cross fading", mediaFrame.alpha != 1.0f)
    }

    @Test
    fun testTransformationOnLockScreenIsFading() {
        // Let's set it onto Lock screen
        `when`(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        `when`(notificationLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(
            true)
        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        clearInvocations(mediaCarouselController)

        // Let's transition from lockscreen to qs
        mediaHiearchyManager.qsExpansion = 1.0f
        val transformType = mediaHiearchyManager.calculateTransformationType()
        Assert.assertTrue("media isn't transforming to qs with a fade",
            transformType == MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun testTransformationOnLockScreenToQQSisFading() {
        // Let's set it onto Lock screen
        `when`(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        `when`(notificationLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(
            true)
        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        clearInvocations(mediaCarouselController)

        // Let's transition from lockscreen to qs
        `when`(statusBarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        statusBarCallback.value.onStatePreChange(StatusBarState.KEYGUARD,
            StatusBarState.SHADE_LOCKED)
        val transformType = mediaHiearchyManager.calculateTransformationType()
        Assert.assertTrue("media isn't transforming to qqswith a fade",
            transformType == MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun testCloseGutsRelayToCarousel() {
        mediaHiearchyManager.closeGuts()

        verify(mediaCarouselController).closeGuts()
    }

    @Test
    fun testCloseGutsWhenDoze() {
        statusBarCallback.value.onDozingChanged(true)

        verify(mediaCarouselController).closeGuts()
    }
}