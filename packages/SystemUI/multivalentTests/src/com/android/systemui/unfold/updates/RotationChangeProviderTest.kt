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

package com.android.systemui.unfold.updates

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.updates.RotationChangeProvider.RotationListener
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.os.FakeHandler
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
@RunWithLooper
class RotationChangeProviderTest : SysuiTestCase() {

    private lateinit var rotationChangeProvider: RotationChangeProvider

    @Mock lateinit var displayManager: DisplayManager
    @Mock lateinit var listener: RotationListener
    @Mock lateinit var display: Display
    @Captor lateinit var displayListener: ArgumentCaptor<DisplayManager.DisplayListener>
    private val bgThread =
        HandlerThread("UnfoldBgTest", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val bgHandler = FakeHandler(bgThread.looper)
    private val callbackHandler = FakeHandler(Looper.getMainLooper())

    private lateinit var spyContext: Context

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        spyContext = spy(context)
        whenever(spyContext.display).thenReturn(display)
        rotationChangeProvider =
            RotationChangeProvider(displayManager, spyContext, bgHandler, callbackHandler)
        rotationChangeProvider.addCallback(listener)
        bgHandler.dispatchQueuedMessages()
        verify(displayManager).registerDisplayListener(displayListener.capture(), any())
    }

    @Test
    fun onRotationChanged_rotationUpdated_listenerReceivesIt() {
        sendRotationUpdate(42)

        verify(listener).onRotationChanged(42)
    }

    @Test
    fun onRotationChanged_rotationSentMultipleWithTheSameValue_listenerReceivesUpdateOnce() {
        sendRotationUpdate(42)
        sendRotationUpdate(42)
        sendRotationUpdate(42)

        verify(listener).onRotationChanged(42)
    }

    @Test
    fun onRotationChanged_rotationSentMultipleTimesWithDifferentValues_listenerReceivesUpdates() {
        sendRotationUpdate(0)
        sendRotationUpdate(1)

        with(inOrder(listener)) {
            verify(listener).onRotationChanged(0)
            verify(listener).onRotationChanged(1)
        }
    }

    @Test
    fun onRotationChanged_subscribersRemoved_noRotationChangeReceived() {
        sendRotationUpdate(42)
        verify(listener).onRotationChanged(42)

        rotationChangeProvider.removeCallback(listener)
        bgHandler.dispatchQueuedMessages()
        sendRotationUpdate(43)

        verify(displayManager).unregisterDisplayListener(any())
        verifyNoMoreInteractions(listener)
    }

    private fun sendRotationUpdate(newRotation: Int) {
        whenever(display.rotation).thenReturn(newRotation)
        displayListener.allValues.forEach { it.onDisplayChanged(display.displayId) }
        callbackHandler.dispatchQueuedMessages()
    }
}
