/*
 * Copyright 2023 The Android Open Source Project
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
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManagerInternal
import android.hardware.input.InputSensorInfo
import android.os.Handler
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.util.TypedValue
import android.view.Display
import android.view.DisplayInfo
import androidx.test.core.app.ApplicationProvider
import com.android.internal.R
import com.android.server.LocalServices
import com.android.server.input.AmbientKeyboardBacklightController.HYSTERESIS_THRESHOLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

/**
 * Tests for {@link AmbientKeyboardBacklightController}.
 *
 * Build/Install/Run:
 * atest InputTests:AmbientKeyboardBacklightControllerTests
 */
@Presubmit
class AmbientKeyboardBacklightControllerTests {

    companion object {
        const val DEFAULT_DISPLAY_UNIQUE_ID = "uniqueId_1"
        const val SENSOR_NAME = "test_sensor_name"
        const val SENSOR_TYPE = "test_sensor_type"
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    private lateinit var context: Context
    private lateinit var testLooper: TestLooper
    private lateinit var ambientController: AmbientKeyboardBacklightController

    @Mock
    private lateinit var resources: Resources

    @Mock
    private lateinit var lightSensorInfo: InputSensorInfo

    @Mock
    private lateinit var sensorManager: SensorManager

    @Mock
    private lateinit var displayManagerInternal: DisplayManagerInternal
    private lateinit var lightSensor: Sensor

    private var currentDisplayInfo = DisplayInfo()
    private var lastBrightnessCallback: Int = 0
    private var listenerRegistered: Boolean = false
    private var listenerRegistrationCount: Int = 0

    @Before
    fun setup() {
        context = spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        `when`(context.resources).thenReturn(resources)
        setupBrightnessSteps()
        setupSensor()
        testLooper = TestLooper()
        ambientController = AmbientKeyboardBacklightController(context, testLooper.looper)
        ambientController.systemRunning()
        testLooper.dispatchAll()
    }

    private fun setupBrightnessSteps() {
        val brightnessValues = intArrayOf(100, 200, 0)
        val decreaseThresholds = intArrayOf(-1, 900, 1900)
        val increaseThresholds = intArrayOf(1000, 2000, -1)
        `when`(resources.getIntArray(R.array.config_autoKeyboardBacklightBrightnessValues))
            .thenReturn(brightnessValues)
        `when`(resources.getIntArray(R.array.config_autoKeyboardBacklightDecreaseLuxThreshold))
            .thenReturn(decreaseThresholds)
        `when`(resources.getIntArray(R.array.config_autoKeyboardBacklightIncreaseLuxThreshold))
            .thenReturn(increaseThresholds)
        `when`(
            resources.getValue(
                eq(R.dimen.config_autoKeyboardBrightnessSmoothingConstant),
                any(TypedValue::class.java),
                anyBoolean()
            )
        ).then {
            val args = it.arguments
            val outValue = args[1] as TypedValue
            outValue.data = java.lang.Float.floatToRawIntBits(1.0f)
            Unit
        }
    }

    private fun setupSensor() {
        LocalServices.removeServiceForTest(DisplayManagerInternal::class.java)
        LocalServices.addService(DisplayManagerInternal::class.java, displayManagerInternal)
        currentDisplayInfo.uniqueId = DEFAULT_DISPLAY_UNIQUE_ID
        `when`(displayManagerInternal.getDisplayInfo(Display.DEFAULT_DISPLAY)).thenReturn(
            currentDisplayInfo
        )
        val sensorData = DisplayManagerInternal.AmbientLightSensorData(SENSOR_NAME, SENSOR_TYPE)
        `when`(displayManagerInternal.getAmbientLightSensorData(Display.DEFAULT_DISPLAY))
            .thenReturn(sensorData)

        `when`(lightSensorInfo.name).thenReturn(SENSOR_NAME)
        `when`(lightSensorInfo.stringType).thenReturn(SENSOR_TYPE)
        lightSensor = Sensor(lightSensorInfo)
        `when`(context.getSystemService(eq(Context.SENSOR_SERVICE))).thenReturn(sensorManager)
        `when`(sensorManager.getSensorList(anyInt())).thenReturn(listOf(lightSensor))
        `when`(
            sensorManager.registerListener(
                any(),
                eq(lightSensor),
                anyInt(),
                any(Handler::class.java)
            )
        ).then {
            listenerRegistered = true
            listenerRegistrationCount++
            true
        }
        `when`(
            sensorManager.unregisterListener(
                any(SensorEventListener::class.java),
                eq(lightSensor)
            )
        ).then {
            listenerRegistered = false
            Unit
        }
    }

    private fun setupSensorWithInitialLux(luxValue: Float) {
        ambientController.registerAmbientBacklightListener { brightnessValue: Int ->
            lastBrightnessCallback = brightnessValue
        }
        sendAmbientLuxValue(luxValue)
        testLooper.dispatchAll()
    }

    @Test
    fun testInitialAmbientLux_sendsCallbackImmediately() {
        setupSensorWithInitialLux(500F)

        assertEquals(
            "Should receive immediate callback for first lux change",
            100,
            lastBrightnessCallback
        )
    }

    @Test
    fun testBrightnessIncrease_afterInitialLuxChanges() {
        setupSensorWithInitialLux(500F)

        // Current state: Step 1 [value = 100, increaseThreshold = 1000, decreaseThreshold = -1]
        repeat(HYSTERESIS_THRESHOLD) {
            sendAmbientLuxValue(1500F)
        }
        testLooper.dispatchAll()

        assertEquals(
            "Should receive brightness change callback for increasing lux change",
            200,
            lastBrightnessCallback
        )
    }

    @Test
    fun testBrightnessDecrease_afterInitialLuxChanges() {
        setupSensorWithInitialLux(1500F)

        // Current state: Step 2 [value = 200, increaseThreshold = 2000, decreaseThreshold = 900]
        repeat(HYSTERESIS_THRESHOLD) {
            sendAmbientLuxValue(500F)
        }
        testLooper.dispatchAll()

        assertEquals(
            "Should receive brightness change callback for decreasing lux change",
            100,
            lastBrightnessCallback
        )
    }

    @Test
    fun testRegisterAmbientListener_throwsExceptionOnRegisteringDuplicate() {
        val ambientListener =
            AmbientKeyboardBacklightController.AmbientKeyboardBacklightListener { }
        ambientController.registerAmbientBacklightListener(ambientListener)

        assertThrows(IllegalStateException::class.java) {
            ambientController.registerAmbientBacklightListener(
                ambientListener
            )
        }
    }

    @Test
    fun testUnregisterAmbientListener_throwsExceptionOnUnregisteringNonExistent() {
        val ambientListener =
            AmbientKeyboardBacklightController.AmbientKeyboardBacklightListener { }
        assertThrows(IllegalStateException::class.java) {
            ambientController.unregisterAmbientBacklightListener(
                ambientListener
            )
        }
    }

    @Test
    fun testSensorListenerRegistered_onRegisterUnregisterAmbientListener() {
        assertEquals(
            "Should not have a sensor listener registered at init",
            0,
            listenerRegistrationCount
        )
        assertFalse("Should not have a sensor listener registered at init", listenerRegistered)

        val ambientListener1 =
            AmbientKeyboardBacklightController.AmbientKeyboardBacklightListener { }
        ambientController.registerAmbientBacklightListener(ambientListener1)
        assertEquals(
            "Should register a new sensor listener", 1, listenerRegistrationCount
        )
        assertTrue("Should have sensor listener registered", listenerRegistered)

        val ambientListener2 =
            AmbientKeyboardBacklightController.AmbientKeyboardBacklightListener { }
        ambientController.registerAmbientBacklightListener(ambientListener2)
        assertEquals(
            "Should not register a new sensor listener when adding a second ambient listener",
            1,
            listenerRegistrationCount
        )
        assertTrue("Should have sensor listener registered", listenerRegistered)

        ambientController.unregisterAmbientBacklightListener(ambientListener1)
        assertTrue("Should have sensor listener registered", listenerRegistered)

        ambientController.unregisterAmbientBacklightListener(ambientListener2)
        assertFalse(
            "Should not have sensor listener registered if there are no ambient listeners",
            listenerRegistered
        )
    }

    @Test
    fun testDisplayChange_shouldNotReRegisterListener_ifUniqueIdSame() {
        setupSensorWithInitialLux(0F)

        val count = listenerRegistrationCount
        ambientController.onDisplayChanged(Display.DEFAULT_DISPLAY)
        testLooper.dispatchAll()

        assertEquals(
            "Should not re-register listener on display change if unique is same",
            count,
            listenerRegistrationCount
        )
    }

    @Test
    fun testDisplayChange_shouldReRegisterListener_ifUniqueIdChanges() {
        setupSensorWithInitialLux(0F)

        val count = listenerRegistrationCount
        currentDisplayInfo.uniqueId = "xyz"
        ambientController.onDisplayChanged(Display.DEFAULT_DISPLAY)
        testLooper.dispatchAll()

        assertEquals(
            "Should re-register listener on display change if unique id changed",
            count + 1,
            listenerRegistrationCount
        )
    }

    @Test
    fun testBrightnessDoesntChange_betweenIncreaseAndDecreaseThresholds() {
        setupSensorWithInitialLux(1001F)

        // Previous state: Step 1 [value = 100, increaseThreshold = 1000, decreaseThreshold = -1]
        // Current state: Step 2 [value = 200, increaseThreshold = 2000, decreaseThreshold = 900]
        lastBrightnessCallback = -1
        repeat(HYSTERESIS_THRESHOLD) {
            sendAmbientLuxValue(999F)
        }
        testLooper.dispatchAll()

        assertEquals(
            "Should not receive any callback for brightness change",
            -1,
            lastBrightnessCallback
        )
    }

    @Test
    fun testBrightnessDoesntChange_onChangeOccurringLessThanHysteresisThreshold() {
        setupSensorWithInitialLux(1001F)

        // Previous state: Step 1 [value = 100, increaseThreshold = 1000, decreaseThreshold = -1]
        // Current state: Step 2 [value = 200, increaseThreshold = 2000, decreaseThreshold = 900]
        lastBrightnessCallback = -1
        repeat(HYSTERESIS_THRESHOLD - 1) {
            sendAmbientLuxValue(2001F)
        }
        testLooper.dispatchAll()

        assertEquals(
            "Should not receive any callback for brightness change",
            -1,
            lastBrightnessCallback
        )
    }

    private fun sendAmbientLuxValue(luxValue: Float) {
        ambientController.onSensorChanged(SensorEvent(lightSensor, 0, 0, floatArrayOf(luxValue)))
    }
}
