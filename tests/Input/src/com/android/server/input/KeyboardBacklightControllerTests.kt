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

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.hardware.input.IInputManager
import android.hardware.input.IKeyboardBacklightListener
import android.hardware.input.IKeyboardBacklightState
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.hardware.lights.Light
import android.os.UEventObserver
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.InputDevice
import androidx.test.annotation.UiThreadTest
import androidx.test.core.app.ApplicationProvider
import com.android.server.input.KeyboardBacklightController.DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL
import com.android.server.input.KeyboardBacklightController.MAX_BRIGHTNESS_CHANGE_STEPS
import com.android.server.input.KeyboardBacklightController.USER_INACTIVITY_THRESHOLD_MILLIS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
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
    createLight(
        lightId,
        lightType,
        null
    )

private fun createLight(lightId: Int, lightType: Int, suggestedBrightnessLevels: IntArray?): Light =
    Light(
        lightId,
        "Light $lightId",
        1,
        lightType,
        Light.LIGHT_CAPABILITY_BRIGHTNESS,
        suggestedBrightnessLevels
    )
/**
 * Tests for {@link KeyboardBacklightController}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardBacklightControllerTests
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
    @Mock
    private lateinit var uEventManager: UEventManager
    private lateinit var keyboardBacklightController: KeyboardBacklightController
    private lateinit var context: Context
    private lateinit var dataStore: PersistentDataStore
    private lateinit var testLooper: TestLooper
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession
    private var lightColorMap: HashMap<Int, Int> = HashMap()
    private var lastBacklightState: KeyboardBacklightState? = null
    private var sysfsNodeChanges = 0
    private var lastAnimationValues = IntArray(2)

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
        keyboardBacklightController = KeyboardBacklightController(context, native, dataStore,
                testLooper.looper, FakeAnimatorFactory(), uEventManager)
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManager)
        val inputManager = InputManager(context)
        `when`(context.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager)
        `when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        `when`(native.setLightColor(anyInt(), anyInt(), anyInt())).then {
            val args = it.arguments
            lightColorMap.put(args[1] as Int, args[2] as Int)
        }
        `when`(native.getLightColor(anyInt(), anyInt())).thenAnswer {
            val args = it.arguments
            lightColorMap.getOrDefault(args[1] as Int, 0)
        }
        lightColorMap.clear()
        `when`(native.sysfsNodeChanged(any())).then {
            sysfsNodeChanges++
        }
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    @Test
    fun testKeyboardBacklightIncrementDecrement() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            assertIncrementDecrementForLevels(keyboardWithBacklight, keyboardBacklight,
                    DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL)
        }
    }

    @Test
    fun testKeyboardWithoutBacklight() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
            val keyboardWithoutBacklight = createKeyboard(DEVICE_ID)
            val keyboardInputLight = createLight(LIGHT_ID, Light.LIGHT_TYPE_INPUT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithoutBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardInputLight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            incrementKeyboardBacklight(DEVICE_ID)
            assertTrue("Non Keyboard backlights should not change", lightColorMap.isEmpty())
        }
    }

    @Test
    fun testKeyboardWithMultipleLight() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
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
    }

    @Test
    fun testRestoreBacklightOnInputDeviceAdded() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))

            for (level in 1 until DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL.size) {
                dataStore.setKeyboardBacklightBrightness(
                    keyboardWithBacklight.descriptor,
                    LIGHT_ID,
                    DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[level] - 1
                )

                keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
                keyboardBacklightController.notifyUserActivity()
                testLooper.dispatchNext()
                assertEquals(
                    "Keyboard backlight level should be restored to the level saved in the " +
                            "data store",
                    Color.argb(DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[level], 0, 0, 0),
                    lightColorMap[LIGHT_ID]
                )
                keyboardBacklightController.onInputDeviceRemoved(DEVICE_ID)
            }
        }
    }

    @Test
    fun testRestoreBacklightOnInputDeviceChanged() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
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
    }

    @Test
    fun testKeyboardBacklight_registerUnregisterListener() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            val maxLevel = DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL.size - 1
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
                "Backlight state brightnessLevel should be 1",
                1,
                lastBacklightState!!.brightnessLevel
            )
            assertEquals(
                "Backlight state maxBrightnessLevel should be $maxLevel",
                maxLevel,
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
    }

    @Test
    fun testKeyboardBacklight_userActivity() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
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
    }

    @Test
    fun testKeyboardBacklight_displayOnOff() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
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
    }

    @Test
    fun testKeyboardBacklightSysfsNodeAdded_AfterInputDeviceAdded() {
        var counter = sysfsNodeChanges
        keyboardBacklightController.onKeyboardBacklightUEvent(UEventObserver.UEvent(
            "ACTION=add\u0000SUBSYSTEM=leds\u0000DEVPATH=/xyz/leds/abc::no_backlight\u0000"
        ))
        assertEquals(
            "Should not reload sysfs node if UEvent path doesn't contain kbd_backlight",
            counter,
            sysfsNodeChanges
        )

        keyboardBacklightController.onKeyboardBacklightUEvent(UEventObserver.UEvent(
            "ACTION=add\u0000SUBSYSTEM=power\u0000DEVPATH=/xyz/leds/abc::kbd_backlight\u0000"
        ))
        assertEquals(
            "Should not reload sysfs node if UEvent doesn't belong to subsystem LED",
            counter,
            sysfsNodeChanges
        )

        keyboardBacklightController.onKeyboardBacklightUEvent(UEventObserver.UEvent(
            "ACTION=remove\u0000SUBSYSTEM=leds\u0000DEVPATH=/xyz/leds/abc::kbd_backlight\u0000"
        ))
        assertEquals(
            "Should not reload sysfs node if UEvent doesn't have ACTION(add)",
            counter,
            sysfsNodeChanges
        )

        keyboardBacklightController.onKeyboardBacklightUEvent(UEventObserver.UEvent(
            "ACTION=add\u0000SUBSYSTEM=leds\u0000DEVPATH=/xyz/pqr/abc::kbd_backlight\u0000"
        ))
        assertEquals(
            "Should not reload sysfs node if UEvent path doesn't belong to leds/ directory",
            counter,
            sysfsNodeChanges
        )

        keyboardBacklightController.onKeyboardBacklightUEvent(UEventObserver.UEvent(
            "ACTION=add\u0000SUBSYSTEM=leds\u0000DEVPATH=/xyz/leds/abc::kbd_backlight\u0000"
        ))
        assertEquals(
            "Should reload sysfs node if a valid Keyboard backlight LED UEvent occurs",
            ++counter,
            sysfsNodeChanges
        )

        keyboardBacklightController.onKeyboardBacklightUEvent(UEventObserver.UEvent(
            "ACTION=add\u0000SUBSYSTEM=leds\u0000DEVPATH=/xyz/leds/abc:kbd_backlight:red\u0000"
        ))
        assertEquals(
            "Should reload sysfs node if a valid Keyboard backlight LED UEvent occurs",
            ++counter,
            sysfsNodeChanges
        )
    }

    @Test
    @UiThreadTest
    fun testKeyboardBacklightAnimation_onChangeLevels() {
        KeyboardBacklightFlags(
            animationEnabled = true,
            customLevelsEnabled = false,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            incrementKeyboardBacklight(DEVICE_ID)
            assertEquals(
                "Should start animation from level 0",
                DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[0],
                lastAnimationValues[0]
            )
            assertEquals(
                "Should start animation to level 1",
                DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[1],
                lastAnimationValues[1]
            )
        }
    }

    @Test
    fun testKeyboardBacklightPreferredLevels() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = true,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val suggestedLevels = intArrayOf(0, 22, 63, 135, 196, 255)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT,
                    suggestedLevels)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            assertIncrementDecrementForLevels(keyboardWithBacklight, keyboardBacklight,
                    suggestedLevels)
        }
    }

    @Test
    fun testKeyboardBacklightPreferredLevels_moreThanMax_shouldUseDefault() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = true,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val suggestedLevels = IntArray(MAX_BRIGHTNESS_CHANGE_STEPS + 1) { 10 * (it + 1) }
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT,
                    suggestedLevels)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            assertIncrementDecrementForLevels(keyboardWithBacklight, keyboardBacklight,
                    DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL)
        }
    }

    @Test
    fun testKeyboardBacklightPreferredLevels_mustHaveZeroAndMaxBrightnessAsBounds() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = true,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val suggestedLevels = intArrayOf(22, 63, 135, 196)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT,
                    suggestedLevels)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            // Framework will add the lowest and maximum levels if not provided via config
            assertIncrementDecrementForLevels(keyboardWithBacklight, keyboardBacklight,
                    intArrayOf(0, 22, 63, 135, 196, 255))
        }
    }

    @Test
    fun testKeyboardBacklightPreferredLevels_dropsOutOfBoundsLevels() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = true,
            ambientControlEnabled = false
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val suggestedLevels = intArrayOf(22, 63, 135, 400, 196, 1000)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT,
                    suggestedLevels)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)

            // Framework will drop out of bound levels in the config
            assertIncrementDecrementForLevels(keyboardWithBacklight, keyboardBacklight,
                    intArrayOf(0, 22, 63, 135, 196, 255))
        }
    }

    @Test
    fun testAmbientBacklightControl_doesntRestoreBacklightLevel() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = true
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))

            dataStore.setKeyboardBacklightBrightness(
                keyboardWithBacklight.descriptor,
                LIGHT_ID,
                DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[1]
            )

            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
            keyboardBacklightController.notifyUserActivity()
            testLooper.dispatchNext()
            assertNull(
                "Keyboard backlight level should not be restored to the saved level",
                lightColorMap[LIGHT_ID]
            )
        }
    }

    @Test
    fun testAmbientBacklightControl_doesntBackupBacklightLevel() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = true
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))

            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
            incrementKeyboardBacklight(DEVICE_ID)
            assertFalse(
                "Light value should not be backed up if ambient control is enabled",
                dataStore.getKeyboardBacklightBrightness(
                    keyboardWithBacklight.descriptor, LIGHT_ID
                ).isPresent
            )
        }
    }

    @Test
    fun testAmbientBacklightControl_incrementLevel_afterAmbientChange() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = true
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
            sendAmbientBacklightValue(1)
            assertEquals(
                "Light value should be changed to ambient provided value",
                Color.argb(1, 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )

            incrementKeyboardBacklight(DEVICE_ID)

            assertEquals(
                "Light value for level after increment post Ambient change is mismatched",
                Color.argb(DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[1], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
        }
    }

    @Test
    fun testAmbientBacklightControl_decrementLevel_afterAmbientChange() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = true
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
            sendAmbientBacklightValue(254)
            assertEquals(
                "Light value should be changed to ambient provided value",
                Color.argb(254, 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )

            decrementKeyboardBacklight(DEVICE_ID)

            val numLevels = DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL.size
            assertEquals(
                "Light value for level after decrement post Ambient change is mismatched",
                Color.argb(DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[numLevels - 2], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
        }
    }

    @Test
    fun testAmbientBacklightControl_ambientChanges_afterManualChange() {
        KeyboardBacklightFlags(
            animationEnabled = false,
            customLevelsEnabled = false,
            ambientControlEnabled = true
        ).use {
            val keyboardWithBacklight = createKeyboard(DEVICE_ID)
            val keyboardBacklight = createLight(LIGHT_ID, Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT)
            `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardWithBacklight)
            `when`(iInputManager.getLights(DEVICE_ID)).thenReturn(listOf(keyboardBacklight))
            keyboardBacklightController.onInputDeviceAdded(DEVICE_ID)
            incrementKeyboardBacklight(DEVICE_ID)
            assertEquals(
                "Light value should be changed to the first level",
                Color.argb(DEFAULT_BRIGHTNESS_VALUE_FOR_LEVEL[1], 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )

            sendAmbientBacklightValue(100)
            assertNotEquals(
                "Light value should not change based on ambient changes after manual changes",
                Color.argb(100, 0, 0, 0),
                lightColorMap[LIGHT_ID]
            )
        }
    }

    private fun assertIncrementDecrementForLevels(
        device: InputDevice,
        light: Light,
        expectedLevels: IntArray
    ) {
        val deviceId = device.id
        val lightId = light.id
        for (level in 1 until expectedLevels.size) {
            incrementKeyboardBacklight(deviceId)
            assertEquals(
                "Light value for level $level mismatched",
                Color.argb(expectedLevels[level], 0, 0, 0),
                lightColorMap[lightId]
            )
            assertEquals(
                "Light value for level $level must be correctly stored in the datastore",
                expectedLevels[level],
                dataStore.getKeyboardBacklightBrightness(device.descriptor, lightId).asInt
            )
        }

        // Increment above max level
        incrementKeyboardBacklight(deviceId)
        assertEquals(
            "Light value for max level mismatched",
            Color.argb(MAX_BRIGHTNESS, 0, 0, 0),
            lightColorMap[lightId]
        )
        assertEquals(
            "Light value for max level must be correctly stored in the datastore",
            MAX_BRIGHTNESS,
            dataStore.getKeyboardBacklightBrightness(device.descriptor, lightId).asInt
        )

        for (level in expectedLevels.size - 2 downTo 0) {
            decrementKeyboardBacklight(deviceId)
            assertEquals(
                "Light value for level $level mismatched",
                Color.argb(expectedLevels[level], 0, 0, 0),
                lightColorMap[lightId]
            )
            assertEquals(
                "Light value for level $level must be correctly stored in the datastore",
                expectedLevels[level],
                dataStore.getKeyboardBacklightBrightness(device.descriptor, lightId).asInt
            )
        }

        // Decrement below min level
        decrementKeyboardBacklight(deviceId)
        assertEquals(
            "Light value for min level mismatched",
            Color.argb(0, 0, 0, 0),
            lightColorMap[lightId]
        )
        assertEquals(
            "Light value for min level must be correctly stored in the datastore",
            0,
            dataStore.getKeyboardBacklightBrightness(device.descriptor, lightId).asInt
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

    private fun sendAmbientBacklightValue(brightnessValue: Int) {
        keyboardBacklightController.handleAmbientLightValueChanged(brightnessValue)
        keyboardBacklightController.notifyUserActivity()
        testLooper.dispatchAll()
    }

    class KeyboardBacklightState(
        val deviceId: Int,
        val brightnessLevel: Int,
        val maxBrightnessLevel: Int,
        val isTriggeredByKeyPress: Boolean
    )

    private inner class KeyboardBacklightFlags constructor(
        animationEnabled: Boolean,
        customLevelsEnabled: Boolean,
        ambientControlEnabled: Boolean
    ) : AutoCloseable {
        init {
            InputFeatureFlagProvider.setKeyboardBacklightAnimationEnabled(animationEnabled)
            InputFeatureFlagProvider.setKeyboardBacklightCustomLevelsEnabled(customLevelsEnabled)
            InputFeatureFlagProvider
                .setAmbientKeyboardBacklightControlEnabled(ambientControlEnabled)
        }

        override fun close() {
            InputFeatureFlagProvider.clearOverrides()
        }
    }

    private inner class FakeAnimatorFactory : KeyboardBacklightController.AnimatorFactory {
        override fun makeIntAnimator(from: Int, to: Int): ValueAnimator {
            lastAnimationValues[0] = from
            lastAnimationValues[1] = to
            return ValueAnimator.ofInt(from, to)
        }
    }
}
