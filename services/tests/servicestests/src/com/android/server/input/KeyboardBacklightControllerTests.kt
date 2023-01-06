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

package com.android.server.input

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.hardware.input.IInputManager
import android.hardware.input.IKeyboardBacklightListener
import android.hardware.input.IKeyboardBacklightState
import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.InputDevice
import androidx.test.core.app.ApplicationProvider
import com.android.server.input.KeyboardBacklightController.BRIGHTNESS_LEVELS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

private fun createKeyboard(deviceId: Int): InputDevice =
    InputDevice.Builder()
        .setId(deviceId)
        .setName("Device $deviceId")
        .setDescriptor("descriptor $deviceId")
        .setSources(InputDevice.SOURCE_KEYBOARD)
        .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        .setExternal(true)
        .build()

private fun createLight(lightId: Int, lightType: Int): Light =
    Light(
        lightId,
        "Light $lightId",
        1,
        lightType,
        Light.LIGHT_CAPABILITY_BRIGHTNESS
    )
/**
 * Tests for {@link KeyboardBacklightController}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:KeyboardBacklightControllerTests
 */
@Presubmit
class KeyboardBacklightControllerTests {
    companion object {
        const val DEVICE_ID = 1
        const val LIGHT_ID = 2
        const val SECOND_LIGHT_ID = 3
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var iInputManager: IInputManager
    @Mock
    private lateinit var native: NativeInputManagerService
    private lateinit var keyboardBacklightController: KeyboardBacklightController
    private lateinit var context: Context
    private lateinit var dataStore: PersistentDataStore
    private lateinit var testLooper: TestLooper
    private var lightColorMap: HashMap<Int, Int> = HashMap()
    private var lastBacklightState: KeyboardBacklightState? = null

    @Before
    fun setup() {
        context = spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        dataStore = PersistentDataStore(object : PersistentDataStore.Injector() {
            override fun openRead(): InputStream? {
                throw FileNotFoundException()
            }

            override fun startWrite(): FileOutputStream? {
                throw IOException()
            }

            override fun finishWrite(fos: FileOutputStream?, success: Boolean) {}
        })
        testLooper = TestLooper()
        keyboardBacklightController =
            KeyboardBacklightController(context, native, dataStore, testLooper.looper)
        val inputManager = InputManager.resetInstance(iInputManager)
        `when`(context.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager)
        `when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        `when`(native.setLightColor(anyInt(), anyInt(), anyInt())).then {
            val args = it.arguments
            lightColorMap.put(args[1] as Int, args[2] as Int)
        }
        `when`(native.getLightColor(anyInt(), anyInt())).then {
            val args = it.arguments
            lightColorMap.getOrDefault(args[1] as Int, -1)
        }
        lightColorMap.clear()
    }

    @After
    fun tearDown() {
        InputManager.clearInstance()
    }

    @Test
    fun testKeyboardBacklightIncrementDecrement() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        // Initially backlight is at min
        lightColorMap[LIGHT_ID] = Color.argb(BRIGHTNESS_LEVELS.first(), 0, 0, 0)

        val brightnessLevelsArray = BRIGHTNESS_LEVELS.toTypedArray()
        for (level in 1 until brightnessLevelsArray.size) {
            keyboardBacklightController.incrementKeyboardBacklight(DEVICE_ID)
            testLooper.dispatchNext()
            assertEquals(
                "Light value for level $level mismatched",
                Color.argb(brightnessLevelsArray[level], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
            assertEquals(
                "Light value for level $level must be correctly stored in the datastore",
                brightnessLevelsArray[level],
                dataStore.getKeyboardBacklightBrightness(
                    keyboardWithBacklight.descriptor,
                    LIGHT_ID
                ).asInt
            )
        }

        for (level in brightnessLevelsArray.size - 2 downTo 0) {
            keyboardBacklightController.decrementKeyboardBacklight(DEVICE_ID)
            testLooper.dispatchNext()
            assertEquals(
                "Light value for level $level mismatched",
                Color.argb(brightnessLevelsArray[level], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
            assertEquals(
                "Light value for level $level must be correctly stored in the datastore",
                brightnessLevelsArray[level],
                dataStore.getKeyboardBacklightBrightness(
                    keyboardWithBacklight.descriptor,
                    LIGHT_ID
                ).asInt
            )
        }
    }

    @Test
    fun testKeyboardBacklightIncrementAboveMaxLevel() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        // Initially backlight is at max
        lightColorMap[LIGHT_ID] = Color.argb(BRIGHTNESS_LEVELS.last(), 0, 0, 0)

        keyboardBacklightController.incrementKeyboardBacklight(DEVICE_ID)
        testLooper.dispatchNext()
        assertEquals(
            "Light value for max level mismatched",
            Color.argb(BRIGHTNESS_LEVELS.last(), 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
        assertEquals(
            "Light value for max level must be correctly stored in the datastore",
            BRIGHTNESS_LEVELS.last(),
            dataStore.getKeyboardBacklightBrightness(
                keyboardWithBacklight.descriptor,
                LIGHT_ID
            ).asInt
        )
    }

    @Test
    fun testKeyboardBacklightDecrementBelowMin() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        // Initially backlight is at min
        lightColorMap[LIGHT_ID] = Color.argb(BRIGHTNESS_LEVELS.first(), 0, 0, 0)

        keyboardBacklightController.decrementKeyboardBacklight(DEVICE_ID)
        testLooper.dispatchNext()
        assertEquals(
            "Light value for min level mismatched",
            Color.argb(BRIGHTNESS_LEVELS.first(), 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
        assertEquals(
            "Light value for min level must be correctly stored in the datastore",
            BRIGHTNESS_LEVELS.first(),
            dataStore.getKeyboardBacklightBrightness(
                keyboardWithBacklight.descriptor,
                LIGHT_ID
            ).asInt
        )
    }

    @Test
    fun testKeyboardWithoutBacklight() {
        val keyboardWithoutBacklight = createKeyboard(DEVICE_ID)
        val keyboardInputLight = createLight(LIGHT_ID, Light.LIGHT_TYPE_INPUT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithoutBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardInputLight))
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

        keyboardBacklightController.incrementKeyboardBacklight(DEVICE_ID)
        assertTrue("Non Keyboard backlights should not change", lightColorMap.isEmpty())
    }

    @Test
    fun testKeyboardWithMultipleLight() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        val keyboardInputLight = createLight(SECOND_LIGHT_ID, Light.LIGHT_TYPE_INPUT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(
            listOf(
                keyboardBacklight,
                keyboardInputLight
            )
        )
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

        keyboardBacklightController.incrementKeyboardBacklight(DEVICE_ID)
        testLooper.dispatchNext()
        assertEquals("Only keyboard backlights should change", 1, lightColorMap.size)
        assertNotNull("Keyboard backlight should change", lightColorMap[LIGHT_ID])
        assertNull("Input lights should not change", lightColorMap[SECOND_LIGHT_ID])
    }

    @Test
    fun testRestoreBacklightOnInputDeviceAdded() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))

        dataStore.setKeyboardBacklightBrightness(
            keyboardWithBacklight.descriptor,
            LIGHT_ID,
            BRIGHTNESS_LEVELS.last()
        )
        lightColorMap.clear()

        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        assertEquals(
            "Keyboard backlight level should be restored to the level saved in the data store",
            Color.argb(BRIGHTNESS_LEVELS.last(), 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
    }

    @Test
    fun testRestoreBacklightOnInputDeviceChanged() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        dataStore.setKeyboardBacklightBrightness(
            keyboardWithBacklight.descriptor,
            LIGHT_ID,
            BRIGHTNESS_LEVELS.last()
        )
        lightColorMap.clear()

        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        assertTrue(
            "Keyboard backlight should not be changed until its added",
            lightColorMap.isEmpty()
        )

        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceChanged(DEVICE_ID)
        assertEquals(
            "Keyboard backlight level should be restored to the level saved in the data store",
            Color.argb(BRIGHTNESS_LEVELS.last(), 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
    }

    @Test
    fun testKeyboardBacklightT_registerUnregisterListener() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        // Initially backlight is at min
        lightColorMap[LIGHT_ID] = Color.argb(BRIGHTNESS_LEVELS.first(), 0, 0, 0)

        // Register backlight listener
        val listener = KeyboardBacklightListener()
        keyboardBacklightController.registerKeyboardBacklightListener(listener, 0)

        lastBacklightState = null
        keyboardBacklightController.incrementKeyboardBacklight(DEVICE_ID)
        testLooper.dispatchNext()

        assertEquals(
            "Backlight state device Id should be $DEVICE_ID",
            DEVICE_ID,
            lastBacklightState!!.deviceId
        )
        assertEquals(
            "Backlight state brightnessLevel should be " + 1,
            1,
            lastBacklightState!!.brightnessLevel
        )
        assertEquals(
            "Backlight state maxBrightnessLevel should be " + (BRIGHTNESS_LEVELS.size - 1),
            (BRIGHTNESS_LEVELS.size - 1),
            lastBacklightState!!.maxBrightnessLevel
        )
        assertEquals(
            "Backlight state isTriggeredByKeyPress should be true",
            true,
            lastBacklightState!!.isTriggeredByKeyPress
        )

        // Unregister listener
        keyboardBacklightController.unregisterKeyboardBacklightListener(listener, 0)

        lastBacklightState = null
        keyboardBacklightController.incrementKeyboardBacklight(DEVICE_ID)
        testLooper.dispatchNext()

        assertNull("Listener should not receive any updates", lastBacklightState)
    }

    inner class KeyboardBacklightListener : IKeyboardBacklightListener.Stub() {
        override fun onBrightnessChanged(
            deviceId: Int,
            state: IKeyboardBacklightState,
            isTriggeredByKeyPress: Boolean
        ) {
            lastBacklightState = KeyboardBacklightState(
                deviceId,
                state.brightnessLevel,
                state.maxBrightnessLevel,
                isTriggeredByKeyPress
            )
        }
    }

    class KeyboardBacklightState(
        val deviceId: Int,
        val brightnessLevel: Int,
        val maxBrightnessLevel: Int,
        val isTriggeredByKeyPress: Boolean
    )
}
