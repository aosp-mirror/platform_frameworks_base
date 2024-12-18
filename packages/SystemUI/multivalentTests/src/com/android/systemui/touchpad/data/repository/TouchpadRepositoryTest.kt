/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.touchpad.data.repository

import android.hardware.input.FakeInputManager
import android.hardware.input.InputManager.InputDeviceListener
import android.hardware.input.fakeInputManager
import android.testing.TestableLooper
import android.view.InputDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository
import com.android.systemui.testKosmos
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class TouchpadRepositoryTest : SysuiTestCase() {

    @Captor private lateinit var deviceListenerCaptor: ArgumentCaptor<InputDeviceListener>
    private lateinit var fakeInputManager: FakeInputManager

    private lateinit var underTest: TouchpadRepository
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var inputDeviceRepo: InputDeviceRepository
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        fakeInputManager = testKosmos().fakeInputManager
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        val handler = FakeHandler(TestableLooper.get(this).looper)
        inputDeviceRepo =
            InputDeviceRepository(handler, testScope.backgroundScope, fakeInputManager.inputManager)
        underTest =
            TouchpadRepositoryImpl(dispatcher, fakeInputManager.inputManager, inputDeviceRepo)
    }

    @Test
    fun emitsDisconnected_ifNothingIsConnected() =
        testScope.runTest {
            val initialState = underTest.isAnyTouchpadConnected.first()
            assertThat(initialState).isFalse()
        }

    @Test
    fun emitsConnected_ifTouchpadAlreadyConnectedAtTheStart() =
        testScope.runTest {
            fakeInputManager.addDevice(TOUCHPAD_ID, TOUCHPAD)
            val initialValue = underTest.isAnyTouchpadConnected.first()
            assertThat(initialValue).isTrue()
        }

    @Test
    fun emitsConnected_whenNewTouchpadConnects() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)

            fakeInputManager.addDevice(TOUCHPAD_ID, TOUCHPAD)

            assertThat(isTouchpadConnected).isTrue()
        }

    @Test
    fun emitsDisconnected_whenDeviceWithIdDoesNotExist() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)
            whenever(fakeInputManager.inputManager.getInputDevice(eq(NULL_DEVICE_ID)))
                .thenReturn(null)
            fakeInputManager.addDevice(NULL_DEVICE_ID, InputDevice.SOURCE_UNKNOWN)
            assertThat(isTouchpadConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenTouchpadDisconnects() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)

            fakeInputManager.addDevice(TOUCHPAD_ID, TOUCHPAD)
            assertThat(isTouchpadConnected).isTrue()

            fakeInputManager.removeDevice(TOUCHPAD_ID)
            assertThat(isTouchpadConnected).isFalse()
        }

    private suspend fun captureDeviceListener() {
        underTest.isAnyTouchpadConnected.first()
        Mockito.verify(fakeInputManager.inputManager)
            .registerInputDeviceListener(deviceListenerCaptor.capture(), anyOrNull())
        fakeInputManager.registerInputDeviceListener(deviceListenerCaptor.value)
    }

    @Test
    fun emitsDisconnected_whenNonTouchpadConnects() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)

            fakeInputManager.addDevice(NON_TOUCHPAD_ID, InputDevice.SOURCE_KEYBOARD)
            assertThat(isTouchpadConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenTouchpadDisconnectsAndWasAlreadyConnectedAtTheStart() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)

            fakeInputManager.removeDevice(TOUCHPAD_ID)
            assertThat(isTouchpadConnected).isFalse()
        }

    @Test
    fun emitsConnected_whenAnotherDeviceDisconnects() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)

            fakeInputManager.addDevice(TOUCHPAD_ID, TOUCHPAD)
            fakeInputManager.removeDevice(NON_TOUCHPAD_ID)

            assertThat(isTouchpadConnected).isTrue()
        }

    @Test
    fun emitsConnected_whenOneTouchpadDisconnectsButAnotherRemainsConnected() =
        testScope.runTest {
            captureDeviceListener()
            val isTouchpadConnected by collectLastValue(underTest.isAnyTouchpadConnected)

            fakeInputManager.addDevice(TOUCHPAD_ID, TOUCHPAD)
            fakeInputManager.addDevice(ANOTHER_TOUCHPAD_ID, TOUCHPAD)
            fakeInputManager.removeDevice(TOUCHPAD_ID)

            assertThat(isTouchpadConnected).isTrue()
        }

    private companion object {
        private const val TOUCHPAD_ID = 1
        private const val NON_TOUCHPAD_ID = 2
        private const val ANOTHER_TOUCHPAD_ID = 3
        private const val NULL_DEVICE_ID = 4

        private const val TOUCHPAD = InputDevice.SOURCE_TOUCHPAD
    }
}
