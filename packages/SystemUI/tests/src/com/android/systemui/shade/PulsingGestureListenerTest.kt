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

package com.android.systemui.shade

import android.hardware.display.AmbientDisplayConfiguration
import android.provider.Settings.Secure.DOZE_DOUBLE_TAP_GESTURE
import android.provider.Settings.Secure.DOZE_TAP_SCREEN_GESTURE
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import com.android.systemui.util.mockito.eq
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyObject
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class PulsingGestureListenerTest : SysuiTestCase() {
    @Mock
    private lateinit var view: NotificationShadeWindowView
    @Mock
    private lateinit var centralSurfaces: CentralSurfaces
    @Mock
    private lateinit var dockManager: DockManager
    @Mock
    private lateinit var falsingManager: FalsingManager
    @Mock
    private lateinit var ambientDisplayConfiguration: AmbientDisplayConfiguration
    @Mock
    private lateinit var tunerService: TunerService
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController

    private lateinit var tunableCaptor: ArgumentCaptor<Tunable>
    private lateinit var underTest: PulsingGestureListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = PulsingGestureListener(
                view,
                falsingManager,
                dockManager,
                centralSurfaces,
                ambientDisplayConfiguration,
                statusBarStateController,
                tunerService,
                dumpManager
        )
        whenever(dockManager.isDocked).thenReturn(false)
    }

    @Test
    fun testGestureDetector_singleTapEnabled() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        // GIVEN tap is enabled, prox not covered
        whenever(ambientDisplayConfiguration.tapGestureEnabled(anyInt())).thenReturn(true)
        updateSettings()
        whenever(falsingManager.isProximityNear).thenReturn(false)

        // GIVEN the falsing manager does NOT think the tap is a false tap
        whenever(falsingManager.isFalseTap(anyInt())).thenReturn(false)

        // WHEN there's a tap
        underTest.onSingleTapUp(upEv)

        // THEN wake up device if dozing
        verify(centralSurfaces).wakeUpIfDozing(anyLong(), anyObject(), anyString())
    }

    @Test
    fun testGestureDetector_doubleTapEnabled() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        // GIVEN double tap is enabled, prox not covered
        whenever(ambientDisplayConfiguration.doubleTapGestureEnabled(anyInt())).thenReturn(true)
        updateSettings()
        whenever(falsingManager.isProximityNear).thenReturn(false)

        // GIVEN the falsing manager does NOT think the tap is a false tap
        whenever(falsingManager.isFalseDoubleTap).thenReturn(false)

        // WHEN there's a double tap
        underTest.onDoubleTapEvent(upEv)

        // THEN wake up device if dozing
        verify(centralSurfaces).wakeUpIfDozing(anyLong(), anyObject(), anyString())
    }

    @Test
    fun testGestureDetector_doubleTapEnabled_onDownEvent_noFalsingCheck() {
        // GIVEN tap is enabled
        whenever(ambientDisplayConfiguration.tapGestureEnabled(anyInt())).thenReturn(true)

        // WHEN there's a double tap on DOWN event
        underTest.onDoubleTapEvent(downEv)

        // THEN don't check the falsing manager, should only be checked on the UP event
        verify(falsingManager, never()).isFalseDoubleTap()
    }

    @Test
    fun testGestureDetector_singleTapEnabled_falsing() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        // GIVEN tap is enabled, prox not covered
        whenever(ambientDisplayConfiguration.tapGestureEnabled(anyInt())).thenReturn(true)
        updateSettings()
        whenever(falsingManager.isProximityNear).thenReturn(false)

        // GIVEN the falsing manager thinks the tap is a false tap
        whenever(falsingManager.isFalseTap(anyInt())).thenReturn(true)

        // WHEN there's a tap
        underTest.onSingleTapUp(upEv)

        // THEN the device doesn't wake up
        verify(centralSurfaces, never()).wakeUpIfDozing(anyLong(), anyObject(), anyString())
    }

    @Test
    fun testSingleTap_notDozing_noFalsingCheck() {
        whenever(statusBarStateController.isDozing).thenReturn(false)

        // GIVEN tap is enabled
        whenever(ambientDisplayConfiguration.tapGestureEnabled(anyInt())).thenReturn(true)
        // WHEN there's a tap
        underTest.onSingleTapUp(upEv)

        // THEN the falsing manager never gets a call (because the device wasn't dozing
        // during the tap)
        verify(falsingManager, never()).isFalseTap(anyInt())
    }

    @Test
    fun testDoubleTap_notDozing_noFalsingCheck() {
        whenever(statusBarStateController.isDozing).thenReturn(false)

        // GIVEN tap is enabled
        whenever(ambientDisplayConfiguration.tapGestureEnabled(anyInt())).thenReturn(true)
        // WHEN there's a tap
        underTest.onDoubleTapEvent(upEv)

        // THEN the falsing manager never gets a call (because the device wasn't dozing
        // during the tap)
        verify(falsingManager, never()).isFalseTap(anyInt())
    }

    @Test
    fun testGestureDetector_doubleTapEnabled_falsing() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        // GIVEN double tap is enabled, prox not covered
        whenever(ambientDisplayConfiguration.doubleTapGestureEnabled(anyInt())).thenReturn(true)
        updateSettings()
        whenever(falsingManager.isProximityNear).thenReturn(false)

        // GIVEN the falsing manager thinks the tap is a false tap
        whenever(falsingManager.isFalseDoubleTap).thenReturn(true)

        // WHEN there's a double tap ACTION_UP event
        underTest.onDoubleTapEvent(upEv)

        // THEN the device doesn't wake up
        verify(centralSurfaces, never()).wakeUpIfDozing(anyLong(), anyObject(), anyString())
    }

    @Test
    fun testGestureDetector_singleTapEnabled_proxCovered() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        // GIVEN tap is enabled, not a false tap based on classifiers
        whenever(ambientDisplayConfiguration.tapGestureEnabled(anyInt())).thenReturn(true)
        updateSettings()
        whenever(falsingManager.isFalseTap(anyInt())).thenReturn(false)

        // GIVEN prox is covered
        whenever(falsingManager.isProximityNear()).thenReturn(true)

        // WHEN there's a tap
        underTest.onSingleTapUp(upEv)

        // THEN the device doesn't wake up
        verify(centralSurfaces, never()).wakeUpIfDozing(anyLong(), anyObject(), anyString())
    }

    @Test
    fun testGestureDetector_doubleTapEnabled_proxCovered() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        // GIVEN double tap is enabled, not a false tap based on classifiers
        whenever(ambientDisplayConfiguration.doubleTapGestureEnabled(anyInt())).thenReturn(true)
        updateSettings()
        whenever(falsingManager.isFalseDoubleTap).thenReturn(false)

        // GIVEN prox is covered
        whenever(falsingManager.isProximityNear()).thenReturn(true)

        // WHEN there's a tap
        underTest.onDoubleTapEvent(upEv)

        // THEN the device doesn't wake up
        verify(centralSurfaces, never()).wakeUpIfDozing(anyLong(), anyObject(), anyString())
    }

    fun updateSettings() {
        tunableCaptor = ArgumentCaptor.forClass(Tunable::class.java)
        verify(tunerService).addTunable(
                tunableCaptor.capture(),
                eq(DOZE_DOUBLE_TAP_GESTURE),
                eq(DOZE_TAP_SCREEN_GESTURE))
        tunableCaptor.value.onTuningChanged(DOZE_DOUBLE_TAP_GESTURE, "")
        tunableCaptor.value.onTuningChanged(DOZE_TAP_SCREEN_GESTURE, "")
    }
}

private val downEv = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
private val upEv = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)
