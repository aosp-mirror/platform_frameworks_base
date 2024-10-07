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
import android.os.PowerManager
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.ToAodFoldTransitionInteractor
import com.android.systemui.shade.ShadeFoldAnimator
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.unfold.util.FoldableDeviceStates
import com.android.systemui.unfold.util.FoldableTestUtils
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.FakeSystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class FoldAodAnimationControllerTest : SysuiTestCase() {

    @Mock lateinit var deviceStateManager: DeviceStateManager

    @Mock lateinit var wakefulnessLifecycle: WakefulnessLifecycle

    @Mock lateinit var globalSettings: GlobalSettings

    @Mock lateinit var latencyTracker: LatencyTracker

    @Mock lateinit var centralSurfaces: CentralSurfaces

    @Mock lateinit var lightRevealScrim: LightRevealScrim

    @Mock lateinit var shadeViewController: ShadeViewController

    @Mock lateinit var viewGroup: ViewGroup

    @Mock lateinit var viewTreeObserver: ViewTreeObserver

    @Mock lateinit var shadeFoldAnimator: ShadeFoldAnimator

    @Mock lateinit var foldTransitionInteractor: ToAodFoldTransitionInteractor

    @Captor private lateinit var foldStateListenerCaptor: ArgumentCaptor<FoldStateListener>

    private lateinit var deviceStates: FoldableDeviceStates

    lateinit var keyguardRepository: FakeKeyguardRepository

    lateinit var underTest: FoldAodAnimationController
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        deviceStates = FoldableTestUtils.findDeviceStates(context)

        // TODO(b/254878364): remove this call to NPVC.getView()
        whenever(shadeViewController.shadeFoldAnimator).thenReturn(shadeFoldAnimator)
        whenever(foldTransitionInteractor.foldAnimator).thenReturn(shadeFoldAnimator)
        whenever(shadeFoldAnimator.view).thenReturn(viewGroup)
        whenever(viewGroup.viewTreeObserver).thenReturn(viewTreeObserver)
        whenever(wakefulnessLifecycle.lastSleepReason)
            .thenReturn(PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD)
        whenever(shadeFoldAnimator.startFoldToAodAnimation(any(), any(), any())).then {
            val onActionStarted = it.arguments[0] as Runnable
            onActionStarted.run()
        }

        val withDeps = KeyguardInteractorFactory.create(featureFlags = FakeFeatureFlags())
        val keyguardInteractor = withDeps.keyguardInteractor
        keyguardRepository = withDeps.repository

        // Needs to be run on the main thread
        runBlocking(IMMEDIATE) {
            underTest =
                FoldAodAnimationController(
                        fakeExecutor,
                        context,
                        deviceStateManager,
                        wakefulnessLifecycle,
                        globalSettings,
                        latencyTracker,
                        { keyguardInteractor },
                        { foldTransitionInteractor },
                    )
                    .apply { initialize(centralSurfaces, shadeViewController, lightRevealScrim) }

            verify(deviceStateManager).registerCallback(any(), foldStateListenerCaptor.capture())

            setAodEnabled(enabled = true)
            sendFoldEvent(folded = false)
        }
    }

    @Test
    fun onFolded_aodDisabled_doesNotLogLatency() =
        runBlocking(IMMEDIATE) {
            keyguardRepository.setIsDozing(true)
            setAodEnabled(enabled = false)

            fold()
            simulateScreenTurningOn()

            verifyNoMoreInteractions(latencyTracker)
        }

    @Test
    fun onFolded_aodEnabled_logsLatency() =
        runBlocking(IMMEDIATE) {
            keyguardRepository.setIsDozing(true)
            setAodEnabled(enabled = true)

            fold()
            simulateScreenTurningOn()

            verify(latencyTracker).onActionStart(any())
            verify(latencyTracker).onActionEnd(any())
        }

    @Test
    fun onFolded_onScreenTurningOnInvokedTwice_doesNotLogLatency() =
        runBlocking(IMMEDIATE) {
            keyguardRepository.setIsDozing(true)
            setAodEnabled(enabled = true)

            fold()
            simulateScreenTurningOn()
            reset(latencyTracker)

            // This can happen > 1 time if the prox sensor is covered
            simulateScreenTurningOn()

            verify(latencyTracker, never()).onActionStart(any())
            verify(latencyTracker, never()).onActionEnd(any())
        }

    @Test
    fun onFolded_onScreenTurningOnWithoutDozingThenWithDozing_doesNotLogLatency() =
        runBlocking(IMMEDIATE) {
            keyguardRepository.setIsDozing(false)
            setAodEnabled(enabled = true)

            fold()
            simulateScreenTurningOn()
            reset(latencyTracker)

            // Now enable dozing and trigger a second run through the aod animation code. It should
            // not rerun the animation
            keyguardRepository.setIsDozing(true)
            yield()
            simulateScreenTurningOn()

            verify(latencyTracker, never()).onActionStart(any())
            verify(latencyTracker, never()).onActionEnd(any())
        }

    @Test
    fun onFolded_animationCancelled_doesNotLogLatency() =
        runBlocking(IMMEDIATE) {
            keyguardRepository.setIsDozing(true)
            setAodEnabled(enabled = true)

            fold()
            underTest.onScreenTurningOn({})
            // The body of onScreenTurningOn is executed on fakeExecutor,
            // run all pending tasks before calling the next method
            fakeExecutor.runAllReady()
            underTest.onStartedWakingUp()

            verify(latencyTracker).onActionStart(any())
            verify(latencyTracker).onActionCancel(any())
        }

    private fun simulateScreenTurningOn() {
        underTest.onScreenTurningOn({})
        underTest.onScreenTurnedOn()
        fakeExecutor.runAllReady()
    }

    private fun fold() = sendFoldEvent(folded = true)

    private fun setAodEnabled(enabled: Boolean) = underTest.onAlwaysOnChanged(alwaysOn = enabled)

    private fun sendFoldEvent(folded: Boolean) {
        val state = if (folded) deviceStates.folded else deviceStates.unfolded
        foldStateListenerCaptor.value.onDeviceStateChanged(state)
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
