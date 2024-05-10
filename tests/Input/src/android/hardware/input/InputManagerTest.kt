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
 */
package android.hardware.input

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.platform.test.annotations.Presubmit
import android.view.Display
import android.view.DisplayInfo
import android.view.InputDevice
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.eq
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for [InputManager].
 *
 * Build/Install/Run:
 * atest InputTests:InputManagerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class InputManagerTest {

    companion object {
        const val DEVICE_ID = 42
        const val SECOND_DEVICE_ID = 96
        const val THIRD_DEVICE_ID = 99
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    private lateinit var devicesChangedListener: IInputDevicesChangedListener
    private val deviceGenerationMap = mutableMapOf<Int /*deviceId*/, Int /*generation*/>()
    private lateinit var context: Context
    private lateinit var inputManager: InputManager

    @Mock
    private lateinit var iInputManager: IInputManager
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession

    @Before
    fun setUp() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManager)
        inputManager = InputManager(context)
        `when`(context.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager)
        `when`(iInputManager.inputDeviceIds).then {
            deviceGenerationMap.keys.toIntArray()
        }
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun notifyDeviceChanged(
        deviceId: Int,
        associatedDisplayId: Int,
        usiVersion: HostUsiVersion?,
    ) {
        val generation = deviceGenerationMap[deviceId]?.plus(1)
            ?: throw IllegalArgumentException("Device $deviceId was never added!")
        deviceGenerationMap[deviceId] = generation

        `when`(iInputManager.getInputDevice(deviceId))
            .thenReturn(createInputDevice(deviceId, associatedDisplayId, usiVersion, generation))
        val list = deviceGenerationMap.flatMap { listOf(it.key, it.value) }
        if (::devicesChangedListener.isInitialized) {
            devicesChangedListener.onInputDevicesChanged(list.toIntArray())
        }
    }

    private fun addInputDevice(
        deviceId: Int,
        associatedDisplayId: Int,
        usiVersion: HostUsiVersion?,
    ) {
        deviceGenerationMap[deviceId] = 0
        notifyDeviceChanged(deviceId, associatedDisplayId, usiVersion)
    }

    @Test
    fun testUsiVersionDisplayAssociation() {
        addInputDevice(DEVICE_ID, Display.DEFAULT_DISPLAY, null)
        addInputDevice(SECOND_DEVICE_ID, Display.INVALID_DISPLAY, HostUsiVersion(9, 8))
        addInputDevice(THIRD_DEVICE_ID, 42, HostUsiVersion(3, 1))

        val usiVersion = inputManager.getHostUsiVersion(createDisplay(42))
        assertNotNull(usiVersion)
        assertEquals(3, usiVersion!!.majorVersion)
        assertEquals(1, usiVersion.minorVersion)
    }

    @Test
    fun testUsiVersionFallBackToDisplayConfig() {
        addInputDevice(DEVICE_ID, Display.DEFAULT_DISPLAY, null)

        `when`(iInputManager.getHostUsiVersionFromDisplayConfig(eq(42)))
            .thenReturn(HostUsiVersion(9, 8))
        val usiVersion = inputManager.getHostUsiVersion(createDisplay(42))
        assertEquals(HostUsiVersion(9, 8), usiVersion)
    }
}

private fun createInputDevice(
    deviceId: Int,
    associatedDisplayId: Int,
    usiVersion: HostUsiVersion? = null,
    generation: Int = -1,
): InputDevice =
    InputDevice.Builder()
        .setId(deviceId)
        .setName("Device $deviceId")
        .setDescriptor("descriptor $deviceId")
        .setAssociatedDisplayId(associatedDisplayId)
        .setUsiVersion(usiVersion)
        .setGeneration(generation)
        .build()

private fun createDisplay(displayId: Int): Display {
    val res: Resources? = null
    return Display(null /* global */, displayId, DisplayInfo(), res)
}
