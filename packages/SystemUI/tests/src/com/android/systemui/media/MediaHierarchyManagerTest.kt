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

import org.mockito.Mockito.`when` as whenever
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardViewController
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsControllerImplTest.Companion.eq
import com.android.systemui.dreams.DreamOverlayStateController
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
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
    private lateinit var keyguardViewController: KeyguardViewController
    @Mock
    private lateinit var uniqueObjectHostView: UniqueObjectHostView
    @Mock
    private lateinit var dreamOverlayStateController: DreamOverlayStateController
    @Captor
    private lateinit var wakefullnessObserver: ArgumentCaptor<(WakefulnessLifecycle.Observer)>
    @Captor
    private lateinit var statusBarCallback: ArgumentCaptor<(StatusBarStateController.StateListener)>
    @JvmField
    @Rule
    val mockito = MockitoJUnit.rule()
    private lateinit var mediaHiearchyManager: MediaHierarchyManager
    private lateinit var mediaFrame: ViewGroup
    private val configurationController = FakeConfigurationController()

    @Before
    fun setup() {
        context.getOrCreateTestableResources().addOverride(
                R.bool.config_use_split_notification_shade, false)
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
                keyguardViewController,
                dreamOverlayStateController)
        verify(wakefulnessLifecycle).addObserver(wakefullnessObserver.capture())
        verify(statusBarStateController).addCallback(statusBarCallback.capture())
        setupHost(lockHost, MediaHierarchyManager.LOCATION_LOCKSCREEN, LOCKSCREEN_TOP)
        setupHost(qsHost, MediaHierarchyManager.LOCATION_QS, QS_TOP)
        setupHost(qqsHost, MediaHierarchyManager.LOCATION_QQS, QQS_TOP)
        `when`(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        `when`(mediaCarouselController.mediaCarouselScrollHandler)
                .thenReturn(mediaCarouselScrollHandler)
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        observer.onFinishedWakingUp()
        // We'll use the viewmanager to verify a few calls below, let's reset this.
        clearInvocations(mediaCarouselController)
    }

    private fun setupHost(host: MediaHost, location: Int, top: Int) {
        `when`(host.location).thenReturn(location)
        `when`(host.currentBounds).thenReturn(Rect(0, top, 0, top))
        `when`(host.hostView).thenReturn(uniqueObjectHostView)
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
        goToLockscreen()

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
        assertThat(mediaFrame.alpha).isNotEqualTo(1.0f)
    }

    @Test
    fun testTransformationOnLockScreenIsFading() {
        goToLockscreen()
        expandQS()

        val transformType = mediaHiearchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun calculateTransformationType_onLockShade_inSplitShade_goingToFullShade_returnsTransition() {
        enableSplitShade()
        goToLockscreen()
        expandQS()
        mediaHiearchyManager.setTransitionToFullShadeAmount(10000f)

        val transformType = mediaHiearchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_TRANSITION)
    }

    @Test
    fun calculateTransformationType_onLockSplitShade_goingToFullShade_mediaInvisible_returnsFade() {
        enableSplitShade()
        goToLockscreen()
        expandQS()
        whenever(lockHost.visible).thenReturn(false)
        mediaHiearchyManager.setTransitionToFullShadeAmount(10000f)

        val transformType = mediaHiearchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun calculateTransformationType_onLockShade_inSplitShade_notExpanding_returnsFade() {
        enableSplitShade()
        goToLockscreen()
        goToLockedShade()
        expandQS()
        mediaHiearchyManager.setTransitionToFullShadeAmount(0f)

        val transformType = mediaHiearchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun testTransformationOnLockScreenToQQSisFading() {
        goToLockscreen()
        goToLockedShade()

        val transformType = mediaHiearchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
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

    @Test
    fun getGuidedTransformationTranslationY_notInGuidedTransformation_returnsNegativeNumber() {
        assertThat(mediaHiearchyManager.getGuidedTransformationTranslationY()).isLessThan(0)
    }

    @Test
    fun getGuidedTransformationTranslationY_inGuidedTransformation_returnsCurrentTranslation() {
        enterGuidedTransformation()

        val expectedTranslation = LOCKSCREEN_TOP - QS_TOP
        assertThat(mediaHiearchyManager.getGuidedTransformationTranslationY())
                .isEqualTo(expectedTranslation)
    }

    @Test
    fun isCurrentlyInGuidedTransformation_hostsVisible_returnsTrue() {
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(true)
        whenever(qsHost.visible).thenReturn(true)
        whenever(qqsHost.visible).thenReturn(true)

        assertThat(mediaHiearchyManager.isCurrentlyInGuidedTransformation()).isTrue()
    }

    @Test
    fun isCurrentlyInGuidedTransformation_hostNotVisible_returnsTrue() {
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(false)
        whenever(qsHost.visible).thenReturn(true)
        whenever(qqsHost.visible).thenReturn(true)

        assertThat(mediaHiearchyManager.isCurrentlyInGuidedTransformation()).isFalse()
    }

    private fun enableSplitShade() {
        context.getOrCreateTestableResources().addOverride(
            R.bool.config_use_split_notification_shade, true
        )
        configurationController.notifyConfigurationChanged()
    }

    private fun goToLockscreen() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(notificationLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(
            true
        )
        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        clearInvocations(mediaCarouselController)
    }

    private fun goToLockedShade() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        statusBarCallback.value.onStatePreChange(
            StatusBarState.KEYGUARD,
            StatusBarState.SHADE_LOCKED
        )
    }

    private fun expandQS() {
        mediaHiearchyManager.qsExpansion = 1.0f
    }

    private fun enterGuidedTransformation() {
        mediaHiearchyManager.qsExpansion = 1.0f
        goToLockscreen()
        mediaHiearchyManager.setTransitionToFullShadeAmount(123f)
    }

    companion object {
        private const val QQS_TOP = 123
        private const val QS_TOP = 456
        private const val LOCKSCREEN_TOP = 789
    }
}
