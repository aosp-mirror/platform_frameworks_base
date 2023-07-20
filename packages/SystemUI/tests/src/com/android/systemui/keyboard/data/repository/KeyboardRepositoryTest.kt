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
import android.view.InputDevice
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class KeyboardRepositoryTest : SysuiTestCase() {

    @Captor
    private lateinit var deviceListenerCaptor: ArgumentCaptor<InputManager.InputDeviceListener>
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
            val initialState = underTest.keyboardConnected.first()
            assertThat(initialState).isFalse()
        }

    @Test
    fun emitsConnected_ifKeyboardAlreadyConnectedAtTheStart() =
        testScope.runTest {
            whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(PHYSICAL_FULL_KEYBOARD_ID))
            val initialValue = underTest.keyboardConnected.first()
            assertThat(initialValue).isTrue()
        }

    @Test
    fun emitsConnected_whenNewPhysicalKeyboardConnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.keyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun emitsDisconnected_whenKeyboardDisconnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.keyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isTrue()

            deviceListener.onInputDeviceRemoved(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    private suspend fun captureDeviceListener(): InputManager.InputDeviceListener {
        underTest.keyboardConnected.first()
        verify(inputManager).registerInputDeviceListener(deviceListenerCaptor.capture(), nullable())
        return deviceListenerCaptor.value
    }

    @Test
    fun emitsDisconnected_whenVirtualOrNotFullKeyboardConnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.keyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_NOT_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()

            deviceListener.onInputDeviceAdded(VIRTUAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsDisconnected_whenKeyboardDisconnectsAndWasAlreadyConnectedAtTheStart() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.keyboardConnected)

            deviceListener.onInputDeviceRemoved(PHYSICAL_FULL_KEYBOARD_ID)
            assertThat(isKeyboardConnected).isFalse()
        }

    @Test
    fun emitsConnected_whenAnotherDeviceDisconnects() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.keyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceRemoved(VIRTUAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun emitsConnected_whenOnePhysicalKeyboardDisconnectsButAnotherRemainsConnected() =
        testScope.runTest {
            val deviceListener = captureDeviceListener()
            val isKeyboardConnected by collectLastValue(underTest.keyboardConnected)

            deviceListener.onInputDeviceAdded(PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceAdded(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)
            deviceListener.onInputDeviceRemoved(ANOTHER_PHYSICAL_FULL_KEYBOARD_ID)

            assertThat(isKeyboardConnected).isTrue()
        }

    @Test
    fun passesKeyboardBacklightValues_fromBacklightListener() {
        // TODO(b/268645734): implement when implementing backlight listener
    }

    private companion object {
        private const val PHYSICAL_FULL_KEYBOARD_ID = 1
        private const val VIRTUAL_FULL_KEYBOARD_ID = 2
        private const val PHYSICAL_NOT_FULL_KEYBOARD_ID = 3
        private const val ANOTHER_PHYSICAL_FULL_KEYBOARD_ID = 4

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
            }
    }
}
