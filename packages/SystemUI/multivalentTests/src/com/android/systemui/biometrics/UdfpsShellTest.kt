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

package com.android.systemui.biometrics

import android.graphics.Rect
import android.testing.TestableLooper
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.UdfpsController.UdfpsOverlayController
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.mockito.any
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenEver
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class UdfpsShellTest : SysuiTestCase() {

    @JvmField @Rule var rule = MockitoJUnit.rule()

    // Unit under test
    private lateinit var udfpsShell: UdfpsShell

    @Mock lateinit var commandRegistry: CommandRegistry
    @Mock lateinit var udfpsOverlayController: UdfpsOverlayController

    @Captor private lateinit var motionEvent: ArgumentCaptor<MotionEvent>

    private val sensorBounds = Rect()

    @Before
    fun setup() {
        whenEver(udfpsOverlayController.sensorBounds).thenReturn(sensorBounds)

        udfpsShell = UdfpsShell(commandRegistry)
        udfpsShell.udfpsOverlayController = udfpsOverlayController
    }

    @Test
    fun testSimFingerDown() {
        udfpsShell.simFingerDown()

        verify(udfpsOverlayController, times(2)).debugOnTouch(motionEvent.capture())

        assertEquals(motionEvent.allValues[0].action, MotionEvent.ACTION_DOWN) // ACTION_MOVE
        assertEquals(motionEvent.allValues[1].action, MotionEvent.ACTION_MOVE) // ACTION_MOVE
    }

    @Test
    fun testSimFingerUp() {
        udfpsShell.simFingerUp()

        verify(udfpsOverlayController).debugOnTouch(motionEvent.capture())

        assertEquals(motionEvent.value.action, MotionEvent.ACTION_UP)
    }

    @Test
    fun testOnUiReady() {
        udfpsShell.onUiReady()

        verify(udfpsOverlayController).debugOnUiReady(any())
    }
}
