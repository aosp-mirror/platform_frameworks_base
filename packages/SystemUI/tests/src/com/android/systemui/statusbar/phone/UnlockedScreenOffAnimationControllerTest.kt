/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Handler
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarStateControllerImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.settings.GlobalSettings
import junit.framework.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class UnlockedScreenOffAnimationControllerTest : SysuiTestCase() {

    private lateinit var controller: UnlockedScreenOffAnimationController
    @Mock
    private lateinit var keyguardViewMediator: KeyguardViewMediator
    @Mock
    private lateinit var dozeParameters: DozeParameters
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var globalSettings: GlobalSettings
    @Mock
    private lateinit var centralSurfaces: CentralSurfaces
    @Mock
    private lateinit var shadeViewController: ShadeViewController
    @Mock
    private lateinit var notifShadeWindowController: NotificationShadeWindowController
    @Mock
    private lateinit var lightRevealScrim: LightRevealScrim
    @Mock
    private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock
    private lateinit var statusBarStateController: StatusBarStateControllerImpl
    @Mock
    private lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock
    private lateinit var powerManager: PowerManager
    @Mock
    private lateinit var handler: Handler

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller = UnlockedScreenOffAnimationController(
                context,
                wakefulnessLifecycle,
                statusBarStateController,
                dagger.Lazy<KeyguardViewMediator> { keyguardViewMediator },
                keyguardStateController,
                dagger.Lazy<DozeParameters> { dozeParameters },
                globalSettings,
                dagger.Lazy<NotificationShadeWindowController> { notifShadeWindowController },
                interactionJankMonitor,
                powerManager,
                handler = handler,
        )
        controller.initialize(centralSurfaces, shadeViewController, lightRevealScrim)

        // Screen off does not run if the panel is expanded, so we should say it's collapsed to test
        // screen off.
        `when`(shadeViewController.isFullyCollapsed).thenReturn(true)
    }

    @After
    fun cleanUp() {
        // Tell the screen off controller to cancel the animations and clean up its state, or
        // subsequent tests will act unpredictably as the animator continues running.
        controller.onStartedWakingUp()
    }

    /**
     * The AOD UI is shown during the screen off animation, after a delay to allow the light reveal
     * animation to start. If the device is woken up during the screen off, we should *never* do
     * this.
     *
     * This test confirms that we do show the AOD UI when the device is not woken up
     * (PowerManager#isInteractive = false).
     */
    @Test
    fun testAodUiShownIfNotInteractive() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive).thenReturn(false)

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())

        callbackCaptor.value.run()

        verify(shadeViewController, times(1)).showAodUi()
    }

    @Test
    fun testAodUiShowNotInvokedIfWakingUp() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive).thenReturn(false)

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()
        controller.onStartedWakingUp()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())

        callbackCaptor.value.run()

        verify(shadeViewController, never()).showAodUi()
    }

    /**
     * The AOD UI is shown during the screen off animation, after a delay to allow the light reveal
     * animation to start. If the device is woken up during the screen off, we should *never* do
     * this.
     *
     * This test confirms that we do not show the AOD UI when the device is woken up during screen
     * off (PowerManager#isInteractive = true).
     */
    @Test
    fun testAodUiNotShownIfInteractive() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive(eq(Display.DEFAULT_DISPLAY))).thenReturn(true)

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())
        callbackCaptor.value.run()

        verify(shadeViewController, never()).showAodUi()
    }

    @Test
    fun testAodUiShownIfGloballyInteractiveButDefaultDisplayNotInteractive() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive()).thenReturn(false)
        `when`(powerManager.isInteractive(eq(Display.DEFAULT_DISPLAY))).thenReturn(false)

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())
        callbackCaptor.value.run()

        verify(shadeViewController).showAodUi()
    }

    @Test
    fun testNoAnimationPlaying_dozeParamsCanNotControlScreenOff() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(false)

        assertFalse(controller.shouldPlayUnlockedScreenOffAnimation())
        controller.startAnimation()
        assertFalse(controller.isAnimationPlaying())
    }
}
