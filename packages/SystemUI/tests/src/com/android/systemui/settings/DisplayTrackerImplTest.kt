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

package com.android.systemui.settings

import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
import android.hardware.display.DisplayManagerGlobal
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DisplayTrackerImplTest : SysuiTestCase() {
    @Mock private lateinit var displayManager: DisplayManager
    @Mock private lateinit var handler: Handler

    private val executor = Executor(Runnable::run)
    private lateinit var mDefaultDisplay: Display
    private lateinit var mSecondaryDisplay: Display
    private lateinit var tracker: DisplayTrackerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mDefaultDisplay =
            Display(
                DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY,
                DisplayInfo(),
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
            )
        mSecondaryDisplay =
            Display(
                DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY + 1,
                DisplayInfo(),
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
            )

        `when`(displayManager.displays).thenReturn(arrayOf(mDefaultDisplay, mSecondaryDisplay))

        tracker = DisplayTrackerImpl(displayManager, handler)
    }

    @Test
    fun testGetDefaultDisplay() {
        assertThat(tracker.defaultDisplayId).isEqualTo(Display.DEFAULT_DISPLAY)
    }

    @Test
    fun testGetAllDisplays() {
        assertThat(tracker.allDisplays).isEqualTo(arrayOf(mDefaultDisplay, mSecondaryDisplay))
    }

    @Test
    fun registerCallback_registersDisplayListener() {
        tracker.addDisplayChangeCallback(TestCallback(), executor)
        verify(displayManager).registerDisplayListener(any(), any())
    }

    @Test
    fun registerBrightnessCallback_registersDisplayListener() {
        tracker.addBrightnessChangeCallback(TestCallback(), executor)
        verify(displayManager)
            .registerDisplayListener(any(), any(), eq(EVENT_FLAG_DISPLAY_BRIGHTNESS))
    }

    @Test
    fun unregisterCallback_displayListenerStillRegistered() {
        val callback1 = TestCallback()
        tracker.addDisplayChangeCallback(callback1, executor)
        tracker.addDisplayChangeCallback(TestCallback(), executor)
        tracker.removeCallback(callback1)

        verify(displayManager, never()).unregisterDisplayListener(any())
    }

    @Test
    fun unregisterLastCallback_unregistersDisplayListener() {
        val callback = TestCallback()
        tracker.addDisplayChangeCallback(callback, executor)
        tracker.removeCallback(callback)

        verify(displayManager).unregisterDisplayListener(any())
    }

    @Test
    fun callbackCalledOnDisplayAdd() {
        val testDisplay = 2
        val callback = TestCallback()
        tracker.addDisplayChangeCallback(callback, executor)
        tracker.displayChangedListener.onDisplayAdded(testDisplay)

        assertThat(callback.lastDisplayAdded).isEqualTo(testDisplay)
    }

    @Test
    fun callbackCalledOnDisplayRemoved() {
        val testDisplay = 2
        val callback = TestCallback()
        tracker.addDisplayChangeCallback(callback, executor)
        tracker.displayChangedListener.onDisplayRemoved(testDisplay)

        assertThat(callback.lastDisplayRemoved).isEqualTo(testDisplay)
    }

    @Test
    fun callbackCalledOnDisplayChanged() {
        val testDisplay = 2
        val callback = TestCallback()
        tracker.addDisplayChangeCallback(callback, executor)
        tracker.displayChangedListener.onDisplayChanged(testDisplay)

        assertThat(callback.lastDisplayChanged).isEqualTo(testDisplay)
    }

    @Test
    fun callbackCalledOnBrightnessChanged() {
        val testDisplay = 2
        val callback = TestCallback()
        tracker.addBrightnessChangeCallback(callback, executor)
        tracker.displayBrightnessChangedListener.onDisplayChanged(testDisplay)

        assertThat(callback.lastDisplayChanged).isEqualTo(testDisplay)
    }

    private class TestCallback : DisplayTracker.Callback {
        var lastDisplayAdded = -1
        var lastDisplayRemoved = -1
        var lastDisplayChanged = -1

        override fun onDisplayAdded(displayId: Int) {
            lastDisplayAdded = displayId
        }

        override fun onDisplayRemoved(displayId: Int) {
            lastDisplayRemoved = displayId
        }

        override fun onDisplayChanged(displayId: Int) {
            lastDisplayChanged = displayId
        }
    }
}
