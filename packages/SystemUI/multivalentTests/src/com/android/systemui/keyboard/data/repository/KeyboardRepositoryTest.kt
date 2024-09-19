/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyboard.data.repository

import android.hardware.input.FakeInputManager
import android.hardware.input.InputManager.InputDeviceListener
import android.hardware.input.InputManager.KeyboardBacklightListener
import android.hardware.input.KeyboardBacklightState
import android.hardware.input.fakeInputManager
import android.testing.TestableLooper
import android.view.InputDevice.SOURCE_KEYBOARD
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.inputdevice.data.repository.InputDeviceRepository
import com.android.systemui.keyboard.data.model.Keyboard
import com.android.systemui.testKosmos
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class KeyboardRepositoryTest : SysuiTestCase() {

    @Captor private lateinit var deviceListenerCaptor: ArgumentCaptor<InputDeviceListener>
    @Captor private lateinit var backlightListenerCaptor: ArgumentCaptor<KeyboardBacklightListener>
    private lateinit var fakeInputManager: FakeInputManager

    private lateinit var underTest: KeyboardRepository
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
            KeyboardRepositoryImpl(dispatcher, fakeInputManager.inputManager, inputDeviceRepo)
    }

    @Test
    fun emitsDisconnected_ifNothingIsConnected() =
        testScope.runTest {
            val initialState = underTest.isAnyKeyboardConnected.first()
            assertThat(initialState).isFalse()
        }

    @Test
    fun emitsConnected_ifKeyboardAlreadyConnectedAtTheStart() =
        testScope.runTest {
            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            val initialValue = underTest.isAnyKeyboardConnected.first()
            assertThat(initialValue).isTrue()
        }

    @Test
    fun emitsConnected_whenNewPhysicalKeyboardConnects() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun emitsDisconnected_whenDeviceNotFound() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)
            fakeInputManager.addDevice(NULL_DEVICE_ID, SOURCE_KEYBOARD, isNotFound = true)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenKeyboardDisconnects() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isTrue()

            fakeInputManager.removeDevice(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    private suspend fun captureDeviceListener() {
        underTest.isAnyKeyboardConnected.first()
        verify(fakeInputManager.inputManager)
            .registerInputDeviceListener(deviceListenerCaptor.capture(), anyOrNull())
        fakeInputManager.registerInputDeviceListener(deviceListenerCaptor.value)
    }

    @Test
    fun emitsDisconnected_whenVirtualOrNotFullKeyboardConnects() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            fakeInputManager.addPhysicalKeyboard(
                PHYSICAL_NOT_FULL_KEYBOARD_ID,
                isFullKeyboard = false
            )
            assertThat(isKeyboardConnected).isFalse()

            fakeInputManager.addDevice(VIRTUAL_FULL_KEYBOARD_ID, SOURCE_KEYBOARD)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenKeyboardDisconnectsAndWasAlreadyConnectedAtTheStart() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            fakeInputManager.removeDevice(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsConnected_whenAnotherDeviceDisconnects() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.removeDevice(VIRTUAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun emitsConnected_whenOnePhysicalKeyboardDisconnectsButAnotherRemainsConnected() =
        testScope.runTest {
            captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.addPhysicalKeyboard(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.removeDevice(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun passesKeyboardBacklightValues_fromBacklightListener() {
        testScope.runTest {
            // we want to capture backlight listener but this can only be done after Flow is
            // subscribed to and listener is actually registered in inputManager
            val backlight by collectLastValueImmediately(underTest.backlight)

            verify(fakeInputManager.inputManager)
                .registerKeyboardBacklightListener(any(), backlightListenerCaptor.capture())

            backlightListenerCaptor.value.onBacklightChanged(current = 1, max = 5)

            assertThat(backlight?.level).isEqualTo(1)
            assertThat(backlight?.maxLevel).isEqualTo(5)
        }
    }

    private fun <T> TestScope.collectLastValueImmediately(flow: Flow<T>): FlowValue<T?> {
        val lastValue = collectLastValue(flow)
        // runCurrent() makes us wait for collect that happens in collectLastValue and ensures
        // Flow is initialized
        runCurrent()
        return lastValue
    }

    @Test
    fun keyboardBacklightValuesNotPassed_fromBacklightListener_whenNotTriggeredByKeyPress() {
        testScope.runTest {
            val backlight by collectLastValueImmediately(underTest.backlight)
            verify(fakeInputManager.inputManager)
                .registerKeyboardBacklightListener(any(), backlightListenerCaptor.capture())

            backlightListenerCaptor.value.onBacklightChanged(
                current = 1,
                max = 5,
                triggeredByKeyPress = false
            )
            assertThat(backlight).isNull()
        }
    }

    @Test
    fun passesKeyboardBacklightValues_fromBacklightListener_whenTriggeredByKeyPress() {
        testScope.runTest {
            val backlight by collectLastValueImmediately(underTest.backlight)
            verify(fakeInputManager.inputManager)
                .registerKeyboardBacklightListener(any(), backlightListenerCaptor.capture())

            backlightListenerCaptor.value.onBacklightChanged(
                current = 1,
                max = 5,
                triggeredByKeyPress = true
            )
            assertThat(backlight).isNotNull()
        }
    }

    @Test
    fun passessAllKeyboards_thatWereAlreadyConnectedOnInitialization() {
        testScope.runTest {
            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.addPhysicalKeyboard(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            // not a physical keyboard - that's why result is 2
            fakeInputManager.addDevice(VIRTUAL_FULL_KEYBOARD_ID, SOURCE_KEYBOARD)

            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            assertThat(keyboards).hasSize(2)
        }
    }

    @Test
    fun passesNewlyConnectedKeyboard() {
        testScope.runTest {
            captureDeviceListener()

            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID, VENDOR_ID, PRODUCT_ID)

            assertThat(underTest.newlyConnectedKeyboard.first())
                .isEqualTo(Keyboard(VENDOR_ID, PRODUCT_ID))
        }
    }

    @Test
    fun emitsOnlyNewlyConnectedKeyboards() {
        testScope.runTest {
            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            underTest.newlyConnectedKeyboard.first()

            captureDeviceListener()

            fakeInputManager.addPhysicalKeyboard(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            assertThat(keyboards).hasSize(1)
        }
    }

    @Test
    fun stillEmitsNewKeyboardEvenIfFlowWasSubscribedAfterOtherFlows() {
        testScope.runTest {
            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.addPhysicalKeyboard(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            // not a physical keyboard - that's why result is 2
            fakeInputManager.addDevice(VIRTUAL_FULL_KEYBOARD_ID, SOURCE_KEYBOARD)

            collectLastValueImmediately(underTest.isAnyKeyboardConnected)

            // let's pretend second flow is subscribed after some delay
            advanceTimeBy(1000)
            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            assertThat(keyboards).hasSize(2)
        }
    }

    @Test
    fun emitsKeyboardWhenItWasReconnected() {
        testScope.runTest {
            captureDeviceListener()
            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.removeDevice(PHYSICAL_FULL_KEYBOARD_ID)
            fakeInputManager.addPhysicalKeyboard(PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(keyboards).hasSize(2)
        }
    }

    private fun KeyboardBacklightListener.onBacklightChanged(
        current: Int,
        max: Int,
        triggeredByKeyPress: Boolean = true
    ) {
        onKeyboardBacklightChanged(
            /* deviceId= */ 0,
            TestBacklightState(current, max),
            triggeredByKeyPress
        )
    }

    private companion object {
        private const val PHYSICAL_FULL_KEYBOARD_ID = 1
        private const val VIRTUAL_FULL_KEYBOARD_ID = -2 // Virtual keyboards has id with minus value
        private const val PHYSICAL_NOT_FULL_KEYBOARD_ID = 3
        private const val ANOTHER_PHYSICAL_FULL_KEYBOARD_ID = 4
        private const val NULL_DEVICE_ID = -5

        private const val VENDOR_ID = 99
        private const val PRODUCT_ID = 101
    }

    private class TestBacklightState(
        private val brightnessLevel: Int,
        private val maxBrightnessLevel: Int
    ) : KeyboardBacklightState() {
        override fun getBrightnessLevel() = brightnessLevel

        override fun getMaxBrightnessLevel() = maxBrightnessLevel
    }
}
