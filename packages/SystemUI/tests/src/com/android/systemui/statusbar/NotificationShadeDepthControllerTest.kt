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

package com.android.systemui.statusbar

import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Choreographer
import android.view.View
import android.view.ViewRootImpl
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.WallpaperController
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import java.util.function.Consumer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.floatThat
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class NotificationShadeDepthControllerTest : SysuiTestCase() {

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var blurUtils: BlurUtils
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var choreographer: Choreographer
    @Mock private lateinit var wallpaperController: WallpaperController
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var root: View
    @Mock private lateinit var viewRootImpl: ViewRootImpl
    @Mock private lateinit var windowToken: IBinder
    @Mock private lateinit var shadeAnimation: NotificationShadeDepthController.DepthAnimation
    @Mock private lateinit var brightnessSpring: NotificationShadeDepthController.DepthAnimation
    @Mock private lateinit var listener: NotificationShadeDepthController.DepthListener
    @Mock private lateinit var dozeParameters: DozeParameters
    @Captor private lateinit var scrimVisibilityCaptor: ArgumentCaptor<Consumer<Int>>
    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var statusBarStateListener: StatusBarStateController.StateListener
    private var statusBarState = StatusBarState.SHADE
    private val maxBlur = 150
    private lateinit var notificationShadeDepthController: NotificationShadeDepthController
    private val configurationController = FakeConfigurationController()

    @Before
    fun setup() {
        `when`(root.viewRootImpl).thenReturn(viewRootImpl)
        `when`(root.windowToken).thenReturn(windowToken)
        `when`(root.isAttachedToWindow).thenReturn(true)
        `when`(statusBarStateController.state).then { statusBarState }
        `when`(blurUtils.blurRadiusOfRatio(anyFloat())).then { answer ->
            answer.arguments[0] as Float * maxBlur.toFloat()
        }
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).then { answer ->
            answer.arguments[0] as Float / maxBlur.toFloat()
        }
        `when`(blurUtils.supportsBlursOnWindows()).thenReturn(true)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)

        notificationShadeDepthController =
            NotificationShadeDepthController(
                statusBarStateController,
                blurUtils,
                biometricUnlockController,
                keyguardStateController,
                choreographer,
                wallpaperController,
                notificationShadeWindowController,
                dozeParameters,
                context,
                dumpManager,
                configurationController)
        notificationShadeDepthController.shadeAnimation = shadeAnimation
        notificationShadeDepthController.brightnessMirrorSpring = brightnessSpring
        notificationShadeDepthController.root = root

        val captor = ArgumentCaptor.forClass(StatusBarStateController.StateListener::class.java)
        verify(statusBarStateController).addCallback(captor.capture())
        statusBarStateListener = captor.value
        verify(notificationShadeWindowController)
            .setScrimsVisibilityListener(scrimVisibilityCaptor.capture())

        disableSplitShade()
    }

    @Test
    fun setupListeners() {
        verify(dumpManager).registerCriticalDumpable(
            anyString(), eq(notificationShadeDepthController)
        )
    }

    @Test
    fun onPanelExpansionChanged_apliesBlur_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        verify(shadeAnimation).animateTo(eq(maxBlur))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurIn_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 0.01f, expanded = false, tracking = false, dragDownPxAmount = 0f))
        verify(shadeAnimation).animateTo(eq(maxBlur))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurOut_ifShade() {
        onPanelExpansionChanged_animatesBlurIn_ifShade()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 0f, expanded = false, tracking = false, dragDownPxAmount = 0f))
        verify(shadeAnimation).animateTo(eq(0))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurOut_ifFlick() {
        val event =
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f)
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(event)
        verify(shadeAnimation, never()).animateTo(anyInt())

        notificationShadeDepthController.onPanelExpansionChanged(
            event.copy(fraction = 0.9f, tracking = true))
        verify(shadeAnimation, never()).animateTo(anyInt())

        notificationShadeDepthController.onPanelExpansionChanged(
            event.copy(fraction = 0.8f, tracking = false))
        verify(shadeAnimation).animateTo(eq(0))
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurIn_ifFlickCancelled() {
        onPanelExpansionChanged_animatesBlurOut_ifFlick()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 0.6f, expanded = true, tracking = true, dragDownPxAmount = 0f))
        verify(shadeAnimation).animateTo(eq(maxBlur))
    }

    @Test
    fun onPanelExpansionChanged_respectsMinPanelPullDownFraction() {
        val event =
            ShadeExpansionChangeEvent(
                fraction = 0.5f, expanded = true, tracking = true, dragDownPxAmount = 0f)
        notificationShadeDepthController.panelPullDownMinFraction = 0.5f
        notificationShadeDepthController.onPanelExpansionChanged(event)
        assertThat(notificationShadeDepthController.shadeExpansion).isEqualTo(0f)

        notificationShadeDepthController.onPanelExpansionChanged(event.copy(fraction = 0.75f))
        assertThat(notificationShadeDepthController.shadeExpansion).isEqualTo(0.5f)

        notificationShadeDepthController.onPanelExpansionChanged(event.copy(fraction = 1f))
        assertThat(notificationShadeDepthController.shadeExpansion).isEqualTo(1f)
    }

    @Test
    fun onStateChanged_reevalutesBlurs_ifSameRadiusAndNewState() {
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(choreographer)

        statusBarState = StatusBarState.KEYGUARD
        statusBarStateListener.onStateChanged(statusBarState)
        verify(shadeAnimation).animateTo(eq(0))
    }

    @Test
    fun setQsPanelExpansion_appliesBlur() {
        statusBarState = StatusBarState.KEYGUARD
        notificationShadeDepthController.qsPanelExpansion = 1f
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false))
    }

    @Test
    fun setQsPanelExpansion_easing() {
        statusBarState = StatusBarState.KEYGUARD
        notificationShadeDepthController.qsPanelExpansion = 0.25f
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(wallpaperController)
            .setNotificationShadeZoom(eq(ShadeInterpolation.getNotificationScrimAlpha(0.25f)))
    }

    @Test
    fun expandPanel_inSplitShade_setsZoomToZero() {
        enableSplitShade()

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(wallpaperController).setNotificationShadeZoom(0f)
    }

    @Test
    fun expandPanel_notInSplitShade_setsZoomValue() {
        disableSplitShade()

        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        verify(wallpaperController).setNotificationShadeZoom(floatThat { it > 0 })
    }

    @Test
    fun expandPanel_splitShadeEnabledChanged_setsCorrectZoomValueAfterChange() {
        disableSplitShade()
        val rawFraction = 1f
        val expanded = true
        val tracking = false
        val dragDownPxAmount = 0f
        val event = ShadeExpansionChangeEvent(rawFraction, expanded, tracking, dragDownPxAmount)
        val inOrder = Mockito.inOrder(wallpaperController)

        notificationShadeDepthController.onPanelExpansionChanged(event)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        inOrder.verify(wallpaperController).setNotificationShadeZoom(floatThat { it > 0 })

        enableSplitShade()
        notificationShadeDepthController.onPanelExpansionChanged(event)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        inOrder.verify(wallpaperController).setNotificationShadeZoom(0f)
    }

    @Test
    fun setFullShadeTransition_appliesBlur() {
        notificationShadeDepthController.transitionToFullShadeProgress = 1f
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false))
    }

    @Test
    fun onDozeAmountChanged_appliesBlur() {
        statusBarStateListener.onDozeAmountChanged(1f, 1f)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false))
    }

    @Test
    fun setFullShadeTransition_appliesBlur_onlyIfSupported() {
        reset(blurUtils)
        `when`(blurUtils.blurRadiusOfRatio(anyFloat())).then { answer ->
            answer.arguments[0] as Float * maxBlur
        }
        `when`(blurUtils.ratioOfBlurRadius(anyFloat())).then { answer ->
            answer.arguments[0] as Float / maxBlur.toFloat()
        }
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)

        notificationShadeDepthController.transitionToFullShadeProgress = 1f
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false))
        verify(wallpaperController).setNotificationShadeZoom(eq(1f))
    }

    @Test
    fun updateBlurCallback_setsBlurAndZoom() {
        notificationShadeDepthController.addListener(listener)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(wallpaperController).setNotificationShadeZoom(anyFloat())
        verify(listener).onWallpaperZoomOutChanged(anyFloat())
        verify(blurUtils).applyBlur(any(), anyInt(), eq(false))
    }

    @Test
    fun updateBlurCallback_setsOpaque_whenScrim() {
        scrimVisibilityCaptor.value.accept(ScrimController.OPAQUE)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), anyInt(), eq(true))
    }

    @Test
    fun updateBlurCallback_setsBlur_whenExpanded() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false))
    }

    @Test
    fun updateBlurCallback_ignoreShadeBlurUntilHidden_overridesZoom() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false))
    }

    @Test
    fun ignoreShadeBlurUntilHidden_schedulesFrame() {
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(choreographer)
            .postFrameCallback(eq(notificationShadeDepthController.updateBlurCallback))
    }

    @Test
    fun ignoreBlurForUnlock_ignores() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.blursDisabledForAppLaunch = false
        notificationShadeDepthController.blursDisabledForUnlock = true

        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        // Since we are ignoring blurs for unlock, we should be applying blur = 0 despite setting it
        // to maxBlur above.
        verify(blurUtils).applyBlur(any(), eq(0), eq(false))
    }

    @Test
    fun ignoreBlurForUnlock_doesNotIgnore() {
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.blursDisabledForAppLaunch = false
        notificationShadeDepthController.blursDisabledForUnlock = false

        notificationShadeDepthController.updateBlurCallback.doFrame(0)

        // Since we are not ignoring blurs for unlock (or app launch), we should apply the blur we
        // returned above (maxBlur).
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false))
    }

    @Test
    fun brightnessMirrorVisible_whenVisible() {
        notificationShadeDepthController.brightnessMirrorVisible = true
        verify(brightnessSpring).animateTo(eq(maxBlur))
    }

    @Test
    fun brightnessMirrorVisible_whenHidden() {
        notificationShadeDepthController.brightnessMirrorVisible = false
        verify(brightnessSpring).animateTo(eq(0))
    }

    @Test
    fun brightnessMirror_hidesShadeBlur() {
        // Brightness mirror is fully visible
        `when`(brightnessSpring.ratio).thenReturn(1f)
        // And shade is blurred
        notificationShadeDepthController.onPanelExpansionChanged(
            ShadeExpansionChangeEvent(
                fraction = 1f, expanded = true, tracking = false, dragDownPxAmount = 0f))
        `when`(shadeAnimation.radius).thenReturn(maxBlur.toFloat())

        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(notificationShadeWindowController).setBackgroundBlurRadius(eq(0))
        verify(wallpaperController).setNotificationShadeZoom(eq(1f))
        verify(blurUtils).applyBlur(eq(viewRootImpl), eq(0), eq(false))
    }

    @Test
    fun ignoreShadeBlurUntilHidden_whennNull_ignoresIfShadeHasNoBlur() {
        `when`(shadeAnimation.radius).thenReturn(0f)
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(shadeAnimation, never()).animateTo(anyInt())
    }

    private fun enableSplitShade() {
        setSplitShadeEnabled(true)
    }

    private fun disableSplitShade() {
        setSplitShadeEnabled(false)
    }

    private fun setSplitShadeEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_split_notification_shade, enabled)
        configurationController.notifyConfigurationChanged()
    }
}
