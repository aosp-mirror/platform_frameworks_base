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

package com.android.systemui.unfold

import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.DeviceStateManager.FoldStateListener
import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.unfold.util.FoldableDeviceStates
import com.android.systemui.unfold.util.FoldableTestUtils
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import java.util.Optional

@RunWith(AndroidTestingRunner::class)
@SmallTest
class UnfoldLatencyTrackerTest : SysuiTestCase() {

    @Mock
    lateinit var latencyTracker: LatencyTracker

    @Mock
    lateinit var deviceStateManager: DeviceStateManager

    @Mock
    lateinit var screenLifecycle: ScreenLifecycle

    @Captor
    private lateinit var foldStateListenerCaptor: ArgumentCaptor<FoldStateListener>

    @Captor
    private lateinit var screenLifecycleCaptor: ArgumentCaptor<ScreenLifecycle.Observer>

    private lateinit var deviceStates: FoldableDeviceStates

    private lateinit var unfoldLatencyTracker: UnfoldLatencyTracker

    private val transitionProgressProvider = TestUnfoldTransitionProvider()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        unfoldLatencyTracker = UnfoldLatencyTracker(
            latencyTracker,
            deviceStateManager,
            Optional.of(transitionProgressProvider),
            context.mainExecutor,
            context,
            context.contentResolver,
            screenLifecycle
        ).apply { init() }
        deviceStates = FoldableTestUtils.findDeviceStates(context)

        verify(deviceStateManager).registerCallback(any(), foldStateListenerCaptor.capture())
        verify(screenLifecycle).addObserver(screenLifecycleCaptor.capture())
    }

    @Test
    fun unfold_startedFolded_animationsDisabled_eventPropagatedOnScreenTurnedOnEvent() {
        setAnimationsEnabled(false)
        sendFoldEvent(folded = true)
        sendFoldEvent(folded = false)

        sendScreenTurnedOnEvent()

        verify(latencyTracker).onActionStart(any())
        verify(latencyTracker).onActionEnd(any())
    }

    @Test
    fun unfold_startedFolded_animationsEnabledOnScreenTurnedOn_eventNotFinished() {
        setAnimationsEnabled(true)
        sendFoldEvent(folded = true)
        sendFoldEvent(folded = false)

        sendScreenTurnedOnEvent()

        verify(latencyTracker).onActionStart(any())
        verify(latencyTracker, never()).onActionEnd(any())
    }

    @Test
    fun unfold_firstFoldEventAnimationsEnabledOnScreenTurnedOnAndTransitionStarted_eventNotPropagated() {
        setAnimationsEnabled(true)
        sendFoldEvent(folded = false)

        sendScreenTurnedOnEvent()
        transitionProgressProvider.onTransitionStarted()

        verifyNoMoreInteractions(latencyTracker)
    }

    @Test
    fun unfold_secondFoldEventAnimationsEnabledOnScreenTurnedOnAndTransitionStarted_eventPropagated() {
        setAnimationsEnabled(true)
        sendFoldEvent(folded = true)
        sendFoldEvent(folded = false)

        sendScreenTurnedOnEvent()
        transitionProgressProvider.onTransitionStarted()

        verify(latencyTracker).onActionStart(any())
        verify(latencyTracker).onActionEnd(any())
    }

    @Test
    fun unfold_unfoldFoldUnfoldAnimationsEnabledOnScreenTurnedOnAndTransitionStarted_eventPropagated() {
        setAnimationsEnabled(true)
        sendFoldEvent(folded = false)
        sendFoldEvent(folded = true)
        sendFoldEvent(folded = false)

        sendScreenTurnedOnEvent()
        transitionProgressProvider.onTransitionStarted()

        verify(latencyTracker).onActionStart(any())
        verify(latencyTracker).onActionEnd(any())
    }

    @Test
    fun fold_animationsDisabled_screenTurnedOn_eventNotPropagated() {
        setAnimationsEnabled(false)
        sendFoldEvent(folded = true)

        sendScreenTurnedOnEvent() // outer display on.

        verifyNoMoreInteractions(latencyTracker)
    }

    @Test
    fun fold_animationsEnabled_screenTurnedOn_eventNotPropagated() {
        setAnimationsEnabled(true)
        sendFoldEvent(folded = true)

        sendScreenTurnedOnEvent() // outer display on.
        transitionProgressProvider.onTransitionStarted()

        verifyNoMoreInteractions(latencyTracker)
    }

    @Test
    fun onScreenTurnedOn_stateNeverSet_eventNotPropagated() {
        sendScreenTurnedOnEvent()

        verifyNoMoreInteractions(latencyTracker)
    }

    private fun sendFoldEvent(folded: Boolean) {
        val state = if (folded) deviceStates.folded else deviceStates.unfolded
        foldStateListenerCaptor.value.onDeviceStateChanged(state)
    }

    private fun sendScreenTurnedOnEvent() {
        screenLifecycleCaptor.value.onScreenTurnedOn()
    }

    private fun setAnimationsEnabled(enabled: Boolean) {
        val durationScale =
            if (enabled) {
                1f
            } else {
                0f
            }

        // It uses [TestableSettingsProvider] and it will be cleared after the test
        Settings.Global.putString(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            durationScale.toString()
        )
    }
}