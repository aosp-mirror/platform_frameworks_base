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

package com.android.systemui.unfold

import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.DeviceStateManager.FoldStateListener
import android.os.Handler
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.unfold.util.FoldableDeviceStates
import com.android.systemui.unfold.util.FoldableTestUtils
import com.android.systemui.util.mockito.any
import com.android.systemui.util.settings.GlobalSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class FoldAodAnimationControllerTest : SysuiTestCase() {

    @Mock lateinit var deviceStateManager: DeviceStateManager

    @Mock lateinit var wakefulnessLifecycle: WakefulnessLifecycle

    @Mock lateinit var globalSettings: GlobalSettings

    @Mock lateinit var latencyTracker: LatencyTracker

    @Mock lateinit var centralSurfaces: CentralSurfaces

    @Mock lateinit var lightRevealScrim: LightRevealScrim

    @Mock lateinit var notificationPanelViewController: NotificationPanelViewController

    @Mock lateinit var viewGroup: ViewGroup

    @Mock lateinit var viewTreeObserver: ViewTreeObserver

    @Captor private lateinit var foldStateListenerCaptor: ArgumentCaptor<FoldStateListener>

    private lateinit var deviceStates: FoldableDeviceStates

    private lateinit var testableLooper: TestableLooper

    lateinit var foldAodAnimationController: FoldAodAnimationController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        foldAodAnimationController =
            FoldAodAnimationController(
                    Handler(testableLooper.looper),
                    context.mainExecutor,
                    context,
                    deviceStateManager,
                    wakefulnessLifecycle,
                    globalSettings,
                    latencyTracker,
                )
                .apply { initialize(centralSurfaces, lightRevealScrim) }
        deviceStates = FoldableTestUtils.findDeviceStates(context)

        whenever(notificationPanelViewController.view).thenReturn(viewGroup)
        whenever(viewGroup.viewTreeObserver).thenReturn(viewTreeObserver)
        whenever(wakefulnessLifecycle.lastSleepReason)
            .thenReturn(PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD)
        whenever(centralSurfaces.notificationPanelViewController)
            .thenReturn(notificationPanelViewController)
        whenever(notificationPanelViewController.startFoldToAodAnimation(any(), any(), any()))
            .then {
                val onActionStarted = it.arguments[0] as Runnable
                onActionStarted.run()
            }
        verify(deviceStateManager).registerCallback(any(), foldStateListenerCaptor.capture())

        foldAodAnimationController.setIsDozing(dozing = true)
        setAodEnabled(enabled = true)
        sendFoldEvent(folded = false)
    }

    @Test
    fun onFolded_aodDisabled_doesNotLogLatency() {
        setAodEnabled(enabled = false)

        fold()
        simulateScreenTurningOn()

        verifyNoMoreInteractions(latencyTracker)
    }

    @Test
    fun onFolded_aodEnabled_logsLatency() {
        setAodEnabled(enabled = true)

        fold()
        simulateScreenTurningOn()

        verify(latencyTracker).onActionStart(any())
        verify(latencyTracker).onActionEnd(any())
    }

    @Test
    fun onFolded_animationCancelled_doesNotLogLatency() {
        setAodEnabled(enabled = true)

        fold()
        foldAodAnimationController.onScreenTurningOn({})
        foldAodAnimationController.onStartedWakingUp()
        testableLooper.processAllMessages()

        verify(latencyTracker).onActionStart(any())
        verify(latencyTracker).onActionCancel(any())
    }

    private fun simulateScreenTurningOn() {
        foldAodAnimationController.onScreenTurningOn({})
        foldAodAnimationController.onScreenTurnedOn()
        testableLooper.processAllMessages()
    }

    private fun fold() = sendFoldEvent(folded = true)

    private fun setAodEnabled(enabled: Boolean) =
        foldAodAnimationController.onAlwaysOnChanged(alwaysOn = enabled)

    private fun sendFoldEvent(folded: Boolean) {
        val state = if (folded) deviceStates.folded else deviceStates.unfolded
        foldStateListenerCaptor.value.onStateChanged(state)
    }
}
