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

import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyboardBacklightListener
import android.hardware.input.KeyboardBacklightState
import android.view.InputDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyboard.data.model.Keyboard
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
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
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardRepositoryTest : SysuiTestCase() {

    @Captor
    private lateinit var deviceListenerCaptor: ArgumentCaptor<InputManager.InputDeviceListener>
    @Captor private lateinit var backlightListenerCaptor: ArgumentCaptor<KeyboardBacklightListener>
    @Mock private lateinit var inputManager: InputManager

    private lateinit var underTest: KeyboardRepository
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf())
        whenever(inputManager.getInputDevice(any())).then { invocation ->
            val id = invocation.arguments.first()
            INPUT_DEVICES_MAP[id]
        }
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        underTest = KeyboardRepositoryImpl(testScope.backgroundScope, dispatcher, inputManager)
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
            whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(PHYSICAL_FULL_KEYBOARD_ID))
            val initialValue = underTest.isAnyKeyboardConnected.first()
            assertThat(initialValue).isTrue()
        }

    @Test
    fun emitsConnected_whenNewPhysicalKeyboardConnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun emitsDisconnected_whenDeviceWithIdDoesNotExist() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceAdded(NULL_DEVICE_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenKeyboardDisconnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isTrue()

            deviceListener.onInputDeviceRemoved(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    private suspend fun captureDeviceListener(): InputManager.InputDeviceListener {
        underTest.isAnyKeyboardConnected.first()
        verify(inputManager).registerInputDeviceListener(deviceListenerCaptor.capture(), nullable())
        return deviceListenerCaptor.value
    }

    @Test
    fun emitsDisconnected_whenVirtualOrNotFullKeyboardConnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_NOT_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()

            deviceListener.onInputDeviceAdded(VIRTUAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenKeyboardDisconnectsAndWasAlreadyConnectedAtTheStart() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceRemoved(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsConnected_whenAnotherDeviceDisconnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceRemoved(VIRTUAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun emitsConnected_whenOnePhysicalKeyboardDisconnectsButAnotherRemainsConnected() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.isAnyKeyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceAdded(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceRemoved(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun passesKeyboardBacklightValues_fromBacklightListener() {
        testScope.runTest {
            // we want to capture backlight listener but this can only be done after Flow is
            // subscribed to and listener is actually registered in inputManager
            val backlight by collectLastValueImmediately(underTest.backlight)

            verify(inputManager)
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
            verify(inputManager)
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
            verify(inputManager)
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
            whenever(inputManager.inputDeviceIds)
                .thenReturn(
                    intArrayOf(
                        PHYSICAL_FULL_KEYBOARD_ID,
                        ANOTHER_PHYSICAL_FULL_KEYBOARD_ID,
                        VIRTUAL_FULL_KEYBOARD_ID // not a physical keyboard - that's why result is 2
                    )
                )
            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            assertThat(keyboards).hasSize(2)
        }
    }

    @Test
    fun passesNewlyConnectedKeyboard() {
        testScope.runTest {
            val deviceListener = captureDeviceListener()

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(underTest.newlyConnectedKeyboard.first())
                .isEqualTo(Keyboard(VENDOR_ID, PRODUCT_ID))
        }
    }

    @Test
    fun emitsOnlyNewlyConnectedKeyboards() {
        testScope.runTest {
            whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(PHYSICAL_FULL_KEYBOARD_ID))
            underTest.newlyConnectedKeyboard.first()
            verify(inputManager)
                .registerInputDeviceListener(deviceListenerCaptor.capture(), nullable())
            val deviceListener = deviceListenerCaptor.value

            deviceListener.onInputDeviceAdded(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            assertThat(keyboards).hasSize(1)
        }
    }

    @Test
    fun stillEmitsNewKeyboardEvenIfFlowWasSubscribedAfterOtherFlows() {
        testScope.runTest {
            whenever(inputManager.inputDeviceIds)
                .thenReturn(
                    intArrayOf(
                        PHYSICAL_FULL_KEYBOARD_ID,
                        ANOTHER_PHYSICAL_FULL_KEYBOARD_ID,
                        VIRTUAL_FULL_KEYBOARD_ID // not a physical keyboard - that's why result is 2
                    )
                )
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
            val deviceListener = captureDeviceListener()
            val keyboards by collectValues(underTest.newlyConnectedKeyboard)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceRemoved(PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)

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
        private const val VIRTUAL_FULL_KEYBOARD_ID = 2
        private const val PHYSICAL_NOT_FULL_KEYBOARD_ID = 3
        private const val ANOTHER_PHYSICAL_FULL_KEYBOARD_ID = 4
        private const val NULL_DEVICE_ID = 5

        private const val VENDOR_ID = 99
        private const val PRODUCT_ID = 101

        private val INPUT_DEVICES_MAP: Map<Int, InputDevice> =
            mapOf(
                PHYSICAL_FULL_KEYBOARD_ID to inputDevice(virtual = false, fullKeyboard = true),
                VIRTUAL_FULL_KEYBOARD_ID to inputDevice(virtual = true, fullKeyboard = true),
                PHYSICAL_NOT_FULL_KEYBOARD_ID to inputDevice(virtual = false, fullKeyboard = false),
                ANOTHER_PHYSICAL_FULL_KEYBOARD_ID to
                    inputDevice(virtual = false, fullKeyboard = true)
            )

        private fun inputDevice(virtual: Boolean, fullKeyboard: Boolean): InputDevice =
            mock<InputDevice>().also {
                whenever(it.isVirtual).thenReturn(virtual)
                whenever(it.isFullKeyboard).thenReturn(fullKeyboard)
                whenever(it.vendorId).thenReturn(VENDOR_ID)
                whenever(it.productId).thenReturn(PRODUCT_ID)
            }
    }

    private class TestBacklightState(
        private val brightnessLevel: Int,
        private val maxBrightnessLevel: Int
    ) : KeyboardBacklightState() {
        override fun getBrightnessLevel() = brightnessLevel
        override fun getMaxBrightnessLevel() = maxBrightnessLevel
    }
}
