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

import android.app.WallpaperManager
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Choreographer
import android.view.View
import android.view.ViewRootImpl
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.eq
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import java.util.function.Consumer

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class NotificationShadeDepthControllerTest : SysuiTestCase() {

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var blurUtils: BlurUtils
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var choreographer: Choreographer
    @Mock private lateinit var wallpaperManager: WallpaperManager
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var root: View
    @Mock private lateinit var viewRootImpl: ViewRootImpl
    @Mock private lateinit var windowToken: IBinder
    @Mock private lateinit var shadeSpring: NotificationShadeDepthController.DepthAnimation
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

    @Before
    fun setup() {
        `when`(root.viewRootImpl).thenReturn(viewRootImpl)
        `when`(root.windowToken).thenReturn(windowToken)
        `when`(root.isAttachedToWindow).thenReturn(true)
        `when`(statusBarStateController.state).then { statusBarState }
        `when`(blurUtils.blurRadiusOfRatio(anyFloat())).then { answer ->
            (answer.arguments[0] as Float * maxBlur).toInt()
        }
        `when`(blurUtils.ratioOfBlurRadius(anyInt())).then { answer ->
            answer.arguments[0] as Int / maxBlur.toFloat()
        }
        `when`(blurUtils.supportsBlursOnWindows()).thenReturn(true)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)

        notificationShadeDepthController = NotificationShadeDepthController(
                statusBarStateController, blurUtils, biometricUnlockController,
                keyguardStateController, choreographer, wallpaperManager,
                notificationShadeWindowController, dozeParameters, dumpManager)
        notificationShadeDepthController.shadeSpring = shadeSpring
        notificationShadeDepthController.shadeAnimation = shadeAnimation
        notificationShadeDepthController.brightnessMirrorSpring = brightnessSpring
        notificationShadeDepthController.root = root

        val captor = ArgumentCaptor.forClass(StatusBarStateController.StateListener::class.java)
        verify(statusBarStateController).addCallback(captor.capture())
        statusBarStateListener = captor.value
        verify(notificationShadeWindowController)
                .setScrimsVisibilityListener(scrimVisibilityCaptor.capture())
    }

    @Test
    fun setupListeners() {
        verify(dumpManager).registerDumpable(anyString(), eq(notificationShadeDepthController))
    }

    @Test
    fun onPanelExpansionChanged_apliesBlur_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(1f /* expansion */,
                false /* tracking */)
        verify(shadeSpring).animateTo(eq(maxBlur), any())
        verify(shadeAnimation).animateTo(eq(maxBlur), any())
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurIn_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(0.01f /* expansion */,
                false /* tracking */)
        verify(shadeAnimation).animateTo(eq(maxBlur), any())
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurOut_ifShade() {
        onPanelExpansionChanged_animatesBlurIn_ifShade()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(0f /* expansion */,
                false /* tracking */)
        verify(shadeAnimation).animateTo(eq(0), any())
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurOut_ifFlick() {
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(1f /* expansion */,
                true /* tracking */)
        verify(shadeAnimation, never()).animateTo(anyInt(), any())

        notificationShadeDepthController.onPanelExpansionChanged(0.9f /* expansion */,
                true /* tracking */)
        verify(shadeAnimation, never()).animateTo(anyInt(), any())

        notificationShadeDepthController.onPanelExpansionChanged(0.8f /* expansion */,
                false /* tracking */)
        verify(shadeAnimation).animateTo(eq(0), any())
    }

    @Test
    fun onPanelExpansionChanged_animatesBlurIn_ifFlickCancelled() {
        onPanelExpansionChanged_animatesBlurOut_ifFlick()
        clearInvocations(shadeAnimation)
        notificationShadeDepthController.onPanelExpansionChanged(0.6f /* expansion */,
                true /* tracking */)
        verify(shadeAnimation).animateTo(eq(maxBlur), any())
    }

    @Test
    fun onStateChanged_reevalutesBlurs_ifSameRadiusAndNewState() {
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(shadeSpring)
        clearInvocations(shadeAnimation)

        statusBarState = StatusBarState.KEYGUARD
        statusBarStateListener.onStateChanged(statusBarState)
        verify(shadeSpring).animateTo(eq(0), any())
        verify(shadeAnimation).animateTo(eq(0), any())
    }

    @Test
    fun setQsPanelExpansion_appliesBlur() {
        notificationShadeDepthController.qsPanelExpansion = 1f
        notificationShadeDepthController.onPanelExpansionChanged(0.5f, tracking = false)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur / 2), eq(false))
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
            (answer.arguments[0] as Float * maxBlur).toInt()
        }
        `when`(blurUtils.ratioOfBlurRadius(anyInt())).then { answer ->
            answer.arguments[0] as Int / maxBlur.toFloat()
        }
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)
        `when`(blurUtils.maxBlurRadius).thenReturn(maxBlur)

        notificationShadeDepthController.transitionToFullShadeProgress = 1f
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false))
        verify(wallpaperManager).setWallpaperZoomOut(any(), eq(1f))
    }

    @Test
    fun updateBlurCallback_setsBlurAndZoom() {
        notificationShadeDepthController.addListener(listener)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(wallpaperManager).setWallpaperZoomOut(any(), anyFloat())
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
        `when`(shadeSpring.radius).thenReturn(maxBlur)
        `when`(shadeAnimation.radius).thenReturn(maxBlur)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(maxBlur), eq(false))
    }

    @Test
    fun updateBlurCallback_ignoreShadeBlurUntilHidden_overridesZoom() {
        `when`(shadeSpring.radius).thenReturn(maxBlur)
        `when`(shadeAnimation.radius).thenReturn(maxBlur)
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(blurUtils).applyBlur(any(), eq(0), eq(false))
    }

    @Test
    fun updateBlurCallback_invalidWindow() {
        `when`(root.isAttachedToWindow).thenReturn(false)
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(wallpaperManager, times(0)).setWallpaperZoomOut(any(), anyFloat())
    }

    @Test
    fun updateBlurCallback_exception() {
        doThrow(IllegalArgumentException("test exception")).`when`(wallpaperManager)
                .setWallpaperZoomOut(any(), anyFloat())
        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(wallpaperManager).setWallpaperZoomOut(any(), anyFloat())
    }

    @Test
    fun ignoreShadeBlurUntilHidden_schedulesFrame() {
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(choreographer).postFrameCallback(
                eq(notificationShadeDepthController.updateBlurCallback))
    }

    @Test
    fun brightnessMirrorVisible_whenVisible() {
        notificationShadeDepthController.brightnessMirrorVisible = true
        verify(brightnessSpring).animateTo(eq(maxBlur), any())
    }

    @Test
    fun brightnessMirrorVisible_whenHidden() {
        notificationShadeDepthController.brightnessMirrorVisible = false
        verify(brightnessSpring).animateTo(eq(0), any())
    }

    @Test
    fun brightnessMirror_hidesShadeBlur() {
        // Brightness mirror is fully visible
        `when`(brightnessSpring.ratio).thenReturn(1f)
        // And shade is blurred
        `when`(shadeSpring.radius).thenReturn(maxBlur)
        `when`(shadeAnimation.radius).thenReturn(maxBlur)

        notificationShadeDepthController.updateBlurCallback.doFrame(0)
        verify(notificationShadeWindowController).setBackgroundBlurRadius(eq(0))
        verify(wallpaperManager).setWallpaperZoomOut(any(), eq(1f))
        verify(blurUtils).applyBlur(eq(viewRootImpl), eq(0), eq(false))
    }

    @Test
    fun ignoreShadeBlurUntilHidden_whennNull_ignoresIfShadeHasNoBlur() {
        `when`(shadeSpring.radius).thenReturn(0)
        `when`(shadeAnimation.radius).thenReturn(0)
        notificationShadeDepthController.blursDisabledForAppLaunch = true
        verify(shadeSpring, never()).animateTo(anyInt(), any())
        verify(shadeAnimation, never()).animateTo(anyInt(), any())
    }
}