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

package com.android.systemui.media.controls.ui

import android.graphics.Rect
import android.provider.Settings
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
import com.android.systemui.media.controls.pipeline.MediaDataManager
import com.android.systemui.media.dream.MediaDreamComplication
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.utils.os.FakeHandler
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
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaHierarchyManagerTest : SysuiTestCase() {

    @Mock private lateinit var lockHost: MediaHost
    @Mock private lateinit var qsHost: MediaHost
    @Mock private lateinit var qqsHost: MediaHost
    @Mock private lateinit var bypassController: KeyguardBypassController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var mediaCarouselController: MediaCarouselController
    @Mock private lateinit var mediaCarouselScrollHandler: MediaCarouselScrollHandler
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var keyguardViewController: KeyguardViewController
    @Mock private lateinit var mediaDataManager: MediaDataManager
    @Mock private lateinit var uniqueObjectHostView: UniqueObjectHostView
    @Mock private lateinit var dreamOverlayStateController: DreamOverlayStateController
    @Captor
    private lateinit var wakefullnessObserver: ArgumentCaptor<(WakefulnessLifecycle.Observer)>
    @Captor
    private lateinit var statusBarCallback: ArgumentCaptor<(StatusBarStateController.StateListener)>
    @Captor
    private lateinit var dreamOverlayCallback:
        ArgumentCaptor<(DreamOverlayStateController.Callback)>
    @JvmField @Rule val mockito = MockitoJUnit.rule()
    private lateinit var mediaHierarchyManager: MediaHierarchyManager
    private lateinit var mediaFrame: ViewGroup
    private val configurationController = FakeConfigurationController()
    private val notifPanelEvents = ShadeExpansionStateManager()
    private val settings = FakeSettings()
    private lateinit var testableLooper: TestableLooper
    private lateinit var fakeHandler: FakeHandler

    @Before
    fun setup() {
        context
            .getOrCreateTestableResources()
            .addOverride(R.bool.config_use_split_notification_shade, false)
        mediaFrame = FrameLayout(context)
        testableLooper = TestableLooper.get(this)
        fakeHandler = FakeHandler(testableLooper.looper)
        whenever(mediaCarouselController.mediaFrame).thenReturn(mediaFrame)
        mediaHierarchyManager =
            MediaHierarchyManager(
                context,
                statusBarStateController,
                keyguardStateController,
                bypassController,
                mediaCarouselController,
                mediaDataManager,
                keyguardViewController,
                dreamOverlayStateController,
                configurationController,
                wakefulnessLifecycle,
                notifPanelEvents,
                settings,
                fakeHandler,
            )
        verify(wakefulnessLifecycle).addObserver(wakefullnessObserver.capture())
        verify(statusBarStateController).addCallback(statusBarCallback.capture())
        verify(dreamOverlayStateController).addCallback(dreamOverlayCallback.capture())
        whenever(mediaCarouselController.updateHostVisibility).thenReturn({})
        setupHost(lockHost, MediaHierarchyManager.LOCATION_LOCKSCREEN, LOCKSCREEN_TOP)
        setupHost(qsHost, MediaHierarchyManager.LOCATION_QS, QS_TOP)
        setupHost(qqsHost, MediaHierarchyManager.LOCATION_QQS, QQS_TOP)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        whenever(mediaDataManager.hasActiveMedia()).thenReturn(true)
        whenever(mediaCarouselController.mediaCarouselScrollHandler)
            .thenReturn(mediaCarouselScrollHandler)
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        observer.onFinishedWakingUp()
        // We'll use the viewmanager to verify a few calls below, let's reset this.
        clearInvocations(mediaCarouselController)
    }

    private fun setupHost(host: MediaHost, location: Int, top: Int) {
        whenever(host.location).thenReturn(location)
        whenever(host.currentBounds).thenReturn(Rect(0, top, 0, top))
        whenever(host.hostView).thenReturn(uniqueObjectHostView)
        whenever(host.visible).thenReturn(true)
        mediaHierarchyManager.register(host)
    }

    @Test
    fun testHostViewSetOnRegister() {
        val host = mediaHierarchyManager.register(lockHost)
        verify(lockHost).hostView = eq(host)
    }

    @Test
    fun testBlockedWhenScreenTurningOff() {
        // Let's set it onto QS:
        mediaHierarchyManager.qsExpansion = 1.0f
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        observer.onStartedGoingToSleep()
        clearInvocations(mediaCarouselController)
        mediaHierarchyManager.qsExpansion = 0.0f
        verify(mediaCarouselController, times(0))
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
    }

    @Test
    fun testBlockedWhenConfigurationChangesAndScreenOff() {
        // Let's set it onto QS:
        mediaHierarchyManager.qsExpansion = 1.0f
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        observer.onStartedGoingToSleep()
        clearInvocations(mediaCarouselController)
        configurationController.notifyConfigurationChanged()
        verify(mediaCarouselController, times(0))
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
    }

    @Test
    fun testAllowedWhenConfigurationChanges() {
        // Let's set it onto QS:
        mediaHierarchyManager.qsExpansion = 1.0f
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
        clearInvocations(mediaCarouselController)
        configurationController.notifyConfigurationChanged()
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
    }

    @Test
    fun testAllowedWhenNotTurningOff() {
        // Let's set it onto QS:
        mediaHierarchyManager.qsExpansion = 1.0f
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
        val observer = wakefullnessObserver.value
        assertNotNull("lifecycle observer wasn't registered", observer)
        clearInvocations(mediaCarouselController)
        mediaHierarchyManager.qsExpansion = 0.0f
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                ArgumentMatchers.anyInt(),
                any(MediaHostState::class.java),
                anyBoolean(),
                anyLong(),
                anyLong()
            )
    }

    @Test
    fun testGoingToFullShade() {
        goToLockscreen()

        // Let's transition all the way to full shade
        mediaHierarchyManager.setTransitionToFullShadeAmount(100000f)
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                eq(MediaHierarchyManager.LOCATION_QQS),
                any(MediaHostState::class.java),
                eq(false),
                anyLong(),
                anyLong()
            )
        clearInvocations(mediaCarouselController)

        // Let's go back to the lock screen
        mediaHierarchyManager.setTransitionToFullShadeAmount(0.0f)
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                eq(MediaHierarchyManager.LOCATION_LOCKSCREEN),
                any(MediaHostState::class.java),
                eq(false),
                anyLong(),
                anyLong()
            )

        // Let's make sure alpha is set
        mediaHierarchyManager.setTransitionToFullShadeAmount(2.0f)
        assertThat(mediaFrame.alpha).isNotEqualTo(1.0f)
    }

    @Test
    fun testTransformationOnLockScreenIsFading() {
        goToLockscreen()
        expandQS()

        val transformType = mediaHierarchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun calculateTransformationType_notOnLockscreen_returnsTransition() {
        expandQS()

        val transformType = mediaHierarchyManager.calculateTransformationType()

        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_TRANSITION)
    }

    @Test
    fun calculateTransformationType_onLockscreen_returnsTransition() {
        goToLockscreen()
        expandQS()

        val transformType = mediaHierarchyManager.calculateTransformationType()

        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun calculateTransformationType_onLockShade_inSplitShade_goingToFullShade_returnsTransition() {
        enableSplitShade()
        goToLockscreen()
        expandQS()
        mediaHierarchyManager.setTransitionToFullShadeAmount(10000f)

        val transformType = mediaHierarchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_TRANSITION)
    }

    @Test
    fun calculateTransformationType_onLockSplitShade_goingToFullShade_mediaInvisible_returnsFade() {
        enableSplitShade()
        goToLockscreen()
        expandQS()
        whenever(lockHost.visible).thenReturn(false)
        mediaHierarchyManager.setTransitionToFullShadeAmount(10000f)

        val transformType = mediaHierarchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun calculateTransformationType_onLockShade_inSplitShade_notExpanding_returnsFade() {
        enableSplitShade()
        goToLockscreen()
        goToLockedShade()
        expandQS()
        mediaHierarchyManager.setTransitionToFullShadeAmount(0f)

        val transformType = mediaHierarchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun testTransformationOnLockScreenToQQSisFading() {
        goToLockscreen()
        goToLockedShade()

        val transformType = mediaHierarchyManager.calculateTransformationType()
        assertThat(transformType).isEqualTo(MediaHierarchyManager.TRANSFORMATION_TYPE_FADE)
    }

    @Test
    fun testCloseGutsRelayToCarousel() {
        mediaHierarchyManager.closeGuts()

        verify(mediaCarouselController).closeGuts()
    }

    @Test
    fun testCloseGutsWhenDoze() {
        statusBarCallback.value.onDozingChanged(true)

        verify(mediaCarouselController).closeGuts()
    }

    @Test
    fun getGuidedTransformationTranslationY_notInGuidedTransformation_returnsNegativeNumber() {
        assertThat(mediaHierarchyManager.getGuidedTransformationTranslationY()).isLessThan(0)
    }

    @Test
    fun getGuidedTransformationTranslationY_inGuidedTransformation_returnsCurrentTranslation() {
        enterGuidedTransformation()

        val expectedTranslation = LOCKSCREEN_TOP - QS_TOP
        assertThat(mediaHierarchyManager.getGuidedTransformationTranslationY())
            .isEqualTo(expectedTranslation)
    }

    @Test
    fun getGuidedTransformationTranslationY_previousHostInvisible_returnsZero() {
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(false)

        assertThat(mediaHierarchyManager.getGuidedTransformationTranslationY()).isEqualTo(0)
    }

    @Test
    fun isCurrentlyInGuidedTransformation_hostsVisible_returnsTrue() {
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(true)
        whenever(qsHost.visible).thenReturn(true)
        whenever(qqsHost.visible).thenReturn(true)

        assertThat(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).isTrue()
    }

    @Test
    fun isCurrentlyInGuidedTransformation_hostsVisible_expandImmediateEnabled_returnsFalse() {
        notifPanelEvents.notifyExpandImmediateChange(true)
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(true)
        whenever(qsHost.visible).thenReturn(true)
        whenever(qqsHost.visible).thenReturn(true)

        assertThat(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).isFalse()
    }

    @Test
    fun isCurrentlyInGuidedTransformation_hostNotVisible_returnsFalse_with_active() {
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(false)
        whenever(qsHost.visible).thenReturn(true)
        whenever(qqsHost.visible).thenReturn(true)
        whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(true)

        assertThat(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).isFalse()
    }

    @Test
    fun isCurrentlyInGuidedTransformation_hostNotVisible_returnsTrue_without_active() {
        // To keep the appearing behavior, we need to be in a guided transition
        goToLockscreen()
        enterGuidedTransformation()
        whenever(lockHost.visible).thenReturn(false)
        whenever(qsHost.visible).thenReturn(true)
        whenever(qqsHost.visible).thenReturn(true)
        whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(false)

        assertThat(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).isTrue()
    }

    @Test
    fun testDream() {
        goToDream()
        setMediaDreamComplicationEnabled(true)
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                eq(MediaHierarchyManager.LOCATION_DREAM_OVERLAY),
                nullable(),
                eq(false),
                anyLong(),
                anyLong()
            )
        clearInvocations(mediaCarouselController)

        setMediaDreamComplicationEnabled(false)
        verify(mediaCarouselController)
            .onDesiredLocationChanged(
                eq(MediaHierarchyManager.LOCATION_QQS),
                any(MediaHostState::class.java),
                eq(false),
                anyLong(),
                anyLong()
            )
    }

    @Test
    fun testQsExpandedChanged_noQqsMedia() {
        // When we are looking at QQS with active media
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE)
        whenever(statusBarStateController.isExpanded).thenReturn(true)

        // When there is no longer any active media
        whenever(mediaDataManager.hasActiveMediaOrRecommendation()).thenReturn(false)
        mediaHierarchyManager.qsExpanded = false

        // Then the carousel is set to not visible
        verify(mediaCarouselScrollHandler).visibleToUser = false
        assertThat(mediaCarouselScrollHandler.visibleToUser).isFalse()
    }

    @Test
    fun keyguardState_allowedOnLockscreen_updateVisibility() {
        settings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, true)
        clearInvocations(mediaCarouselController)

        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        statusBarCallback.value.onStateChanged(StatusBarState.KEYGUARD)

        verify(mediaCarouselController).updateHostVisibility
        assertThat(mediaHierarchyManager.isLockedAndHidden()).isFalse()
    }

    @Test
    fun keyguardState_notAllowedOnLockscreen_updateVisibility() {
        settings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, false)
        clearInvocations(mediaCarouselController)

        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        statusBarCallback.value.onStateChanged(StatusBarState.KEYGUARD)

        verify(mediaCarouselController).updateHostVisibility
        assertThat(mediaHierarchyManager.isLockedAndHidden()).isTrue()
    }

    @Test
    fun keyguardGone_updateVisibility() {
        settings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, false)
        clearInvocations(mediaCarouselController)

        statusBarCallback.value.onStatePreChange(StatusBarState.KEYGUARD, StatusBarState.SHADE)
        statusBarCallback.value.onStateChanged(StatusBarState.SHADE)

        verify(mediaCarouselController).updateHostVisibility
        assertThat(mediaHierarchyManager.isLockedAndHidden()).isFalse()
    }

    @Test
    fun lockscreenSettingChanged_updateVisibility() {
        settings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, true)
        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        statusBarCallback.value.onStateChanged(StatusBarState.KEYGUARD)
        clearInvocations(mediaCarouselController)

        settings.putBool(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, false)

        verify(mediaCarouselController).updateHostVisibility
        assertThat(mediaHierarchyManager.isLockedAndHidden()).isTrue()
    }

    private fun enableSplitShade() {
        context
            .getOrCreateTestableResources()
            .addOverride(R.bool.config_use_split_notification_shade, true)
        configurationController.notifyConfigurationChanged()
    }

    private fun goToLockscreen() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        settings.putInt(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, 1)
        statusBarCallback.value.onStatePreChange(StatusBarState.SHADE, StatusBarState.KEYGUARD)
        whenever(dreamOverlayStateController.isOverlayActive).thenReturn(false)
        dreamOverlayCallback.value.onStateChanged()
        clearInvocations(mediaCarouselController)
    }

    private fun goToLockedShade() {
        whenever(statusBarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        statusBarCallback.value.onStatePreChange(
            StatusBarState.KEYGUARD,
            StatusBarState.SHADE_LOCKED
        )
    }

    private fun goToDream() {
        whenever(dreamOverlayStateController.isOverlayActive).thenReturn(true)
        dreamOverlayCallback.value.onStateChanged()
    }

    private fun setMediaDreamComplicationEnabled(enabled: Boolean) {
        val complications = if (enabled) listOf(mock<MediaDreamComplication>()) else emptyList()
        whenever(dreamOverlayStateController.complications).thenReturn(complications)
        dreamOverlayCallback.value.onComplicationsChanged()
    }

    private fun expandQS() {
        mediaHierarchyManager.qsExpansion = 1.0f
    }

    private fun enterGuidedTransformation() {
        mediaHierarchyManager.qsExpansion = 1.0f
        goToLockscreen()
        mediaHierarchyManager.setTransitionToFullShadeAmount(123f)
    }

    companion object {
        private const val QQS_TOP = 123
        private const val QS_TOP = 456
        private const val LOCKSCREEN_TOP = 789
    }
}
