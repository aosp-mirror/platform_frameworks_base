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
import com.android.server.input.KeyboardBacklightController.BRIGHTNESS_VALUE_FOR_LEVEL
import com.android.server.input.KeyboardBacklightController.USER_INACTIVITY_THRESHOLD_MILLIS
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
        const val MAX_BRIGHTNESS = 255
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

        for (level in 1 until BRIGHTNESS_VALUE_FOR_LEVEL.size) {
            incrementKeyboardBacklight(DEVICE_ID)
            assertEquals(
                "Light value for level $level mismatched",
                Color.argb(BRIGHTNESS_VALUE_FOR_LEVEL[level], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
            assertEquals(
                "Light value for level $level must be correctly stored in the datastore",
                BRIGHTNESS_VALUE_FOR_LEVEL[level],
                dataStore.getKeyboardBacklightBrightness(
                    keyboardWithBacklight.descriptor,
                    LIGHT_ID
                ).asInt
            )
        }

        // Increment above max level
        incrementKeyboardBacklight(DEVICE_ID)
        assertEquals(
            "Light value for max level mismatched",
            Color.argb(MAX_BRIGHTNESS, 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
        assertEquals(
            "Light value for max level must be correctly stored in the datastore",
            MAX_BRIGHTNESS,
            dataStore.getKeyboardBacklightBrightness(
                keyboardWithBacklight.descriptor,
                LIGHT_ID
            ).asInt
        )

        for (level in BRIGHTNESS_VALUE_FOR_LEVEL.size - 2 downTo 0) {
            decrementKeyboardBacklight(DEVICE_ID)
            assertEquals(
                "Light value for level $level mismatched",
                Color.argb(BRIGHTNESS_VALUE_FOR_LEVEL[level], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
            assertEquals(
                "Light value for level $level must be correctly stored in the datastore",
                BRIGHTNESS_VALUE_FOR_LEVEL[level],
                dataStore.getKeyboardBacklightBrightness(
                    keyboardWithBacklight.descriptor,
                    LIGHT_ID
                ).asInt
            )
        }

        // Decrement below min level
        decrementKeyboardBacklight(DEVICE_ID)
        assertEquals(
            "Light value for min level mismatched",
            Color.argb(0, 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
        assertEquals(
            "Light value for min level must be correctly stored in the datastore",
            0,
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

        incrementKeyboardBacklight(DEVICE_ID)
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

        incrementKeyboardBacklight(DEVICE_ID)
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
            MAX_BRIGHTNESS
        )

        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchNext()
        assertEquals(
            "Keyboard backlight level should be restored to the level saved in the data store",
            Color.argb(MAX_BRIGHTNESS, 0, 0, 0),
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
            MAX_BRIGHTNESS
        )

        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchNext()
        assertTrue(
            "Keyboard backlight should not be changed until its added",
            lightColorMap.isEmpty()
        )

        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceChanged(DEVICE_ID)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchNext()
        assertEquals(
            "Keyboard backlight level should be restored to the level saved in the data store",
            Color.argb(MAX_BRIGHTNESS, 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )
    }

    @Test
    fun testKeyboardBacklight_registerUnregisterListener() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

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
            "Backlight state maxBrightnessLevel should be " + (BRIGHTNESS_VALUE_FOR_LEVEL.size - 1),
            (BRIGHTNESS_VALUE_FOR_LEVEL.size - 1),
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
        incrementKeyboardBacklight(DEVICE_ID)

        assertNull("Listener should not receive any updates", lastBacklightState)
    }

    @Test
    fun testKeyboardBacklight_userActivity() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        dataStore.setKeyboardBacklightBrightness(
            keyboardWithBacklight.descriptor,
            LIGHT_ID,
            MAX_BRIGHTNESS
        )

        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchNext()
        assertEquals(
            "Keyboard backlight level should be restored to the level saved in the data store",
            Color.argb(MAX_BRIGHTNESS, 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )

        testLooper.moveTimeForward(USER_INACTIVITY_THRESHOLD_MILLIS + 1000)
        testLooper.dispatchNext()
        assertEquals(
            "Keyboard backlight level should be turned off after inactivity",
            0,
            lightColorMap[LIGHT_ID]
        )
    }

    @Test
    fun testKeyboardBacklight_displayOnOff() {
        val keyboardWithBacklight = createKeyboard(DEVICE_ID)
        val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
        `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
        dataStore.setKeyboardBacklightBrightness(
            keyboardWithBacklight.descriptor,
            LIGHT_ID,
            MAX_BRIGHTNESS
        )

        keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
        keyboardBacklightController.handleInteractiveStateChange(true /* isDisplayOn */)
        assertEquals(
            "Keyboard backlight level should be restored to the level saved in the data " +
                    "store when display turned on",
            Color.argb(MAX_BRIGHTNESS, 0, 0, 0),
            lightColorMap[LIGHT_ID]
        )

        keyboardBacklightController.handleInteractiveStateChange(false /* isDisplayOn */)
        assertEquals(
            "Keyboard backlight level should be turned off after display is turned off",
            0,
            lightColorMap[LIGHT_ID]
        )
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

    private fun incrementKeyboardBacklight(deviceId: Int) {
        keyboardBacklightController.incrementKeyboardBacklight(deviceId)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchAll()
    }

    private fun decrementKeyboardBacklight(deviceId: Int) {
        keyboardBacklightController.decrementKeyboardBacklight(deviceId)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchAll()
    }

    class KeyboardBacklightState(
        val deviceId: Int,
        val brightnessLevel: Int,
        val maxBrightnessLevel: Int,
        val isTriggeredByKeyPress: Boolean
    )
}
