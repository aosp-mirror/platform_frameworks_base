/*
 * Copyright 2024 The Android Open Source Project
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
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.hardware.input.KeyGlyphMap.KeyCombination
import android.os.Bundle
import android.os.test.TestLooper
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.android.hardware.input.Flags
import com.android.test.input.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit

/**
 * Tests for custom keyboard glyph map configuration.
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardGlyphManagerTests
 */
@Presubmit
class KeyboardGlyphManagerTests {

    companion object {
        const val DEVICE_ID = 1
        const val VENDOR_ID = 0x1234
        const val PRODUCT_ID = 0x3456
        const val PACKAGE_NAME = "KeyboardLayoutManagerTests"
        const val RECEIVER_NAME = "DummyReceiver"
    }

    @JvmField
    @Rule(order = 0)
    val setFlagsRule = SetFlagsRule()

    @JvmField
    @Rule(order = 1)
    val mockitoRule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var packageManager: PackageManager

    @Mock
    private lateinit var iInputManager: IInputManager

    private lateinit var keyboardGlyphManager: KeyboardGlyphManager
    private lateinit var context: Context
    private lateinit var testLooper: TestLooper
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession
    private lateinit var keyboardDevice: InputDevice

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManager)
        testLooper = TestLooper()
        keyboardGlyphManager = KeyboardGlyphManager(context, testLooper.looper)

        setupInputDevices()
        setupBroadcastReceiver()
        keyboardGlyphManager.systemRunning()
        testLooper.dispatchAll()
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun setupInputDevices() {
        val inputManager = InputManager(context)
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
            .thenReturn(inputManager)

        keyboardDevice = createKeyboard(DEVICE_ID, VENDOR_ID, PRODUCT_ID, 0, "", "")
        Mockito.`when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardDevice)
    }

    private fun setupBroadcastReceiver() {
        Mockito.`when`(context.packageManager).thenReturn(packageManager)

        val info = createMockReceiver()
        Mockito.`when`(packageManager.queryBroadcastReceiversAsUser(Mockito.any(), Mockito.anyInt(),
            Mockito.anyInt())).thenReturn(listOf(info))
        Mockito.`when`(packageManager.getReceiverInfo(Mockito.any(), Mockito.anyInt()))
            .thenReturn(info.activityInfo)

        val resources = context.resources
        Mockito.`when`(
            packageManager.getResourcesForApplication(
                Mockito.any(
                    ApplicationInfo::class.java
                )
            )
        ).thenReturn(resources)
    }

    private fun createMockReceiver(): ResolveInfo {
        val info = ResolveInfo()
        info.activityInfo = ActivityInfo()
        info.activityInfo.packageName = PACKAGE_NAME
        info.activityInfo.name = RECEIVER_NAME
        info.activityInfo.applicationInfo = ApplicationInfo()
        info.activityInfo.metaData = Bundle()
        info.activityInfo.metaData.putInt(
            InputManager.META_DATA_KEYBOARD_GLYPH_MAPS,
            R.xml.keyboard_glyph_maps
        )
        info.serviceInfo = ServiceInfo()
        info.serviceInfo.packageName = PACKAGE_NAME
        info.serviceInfo.name = RECEIVER_NAME
        return info
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYBOARD_GLYPH_MAP)
    fun testGlyphMapsLoaded() {
        assertNotNull(
            "Glyph map for test keyboard(deviceId=$DEVICE_ID) must exist",
            keyboardGlyphManager.getKeyGlyphMap(DEVICE_ID)
        )
        assertNull(
            "Glyph map for non-existing keyboard must be null",
            keyboardGlyphManager.getKeyGlyphMap(-2)
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYBOARD_GLYPH_MAP)
    fun testGlyphMapCorrectlyLoaded() {
        val glyphMap = keyboardGlyphManager.getKeyGlyphMap(DEVICE_ID)
        // Test glyph map used in this test: {@see test_glyph_map.xml}
        assertNotNull(glyphMap!!.getDrawableForKeycode(context, KeyEvent.KEYCODE_BACK))

        assertNotNull(glyphMap.getDrawableForModifier(context, KeyEvent.KEYCODE_META_LEFT))
        assertNotNull(glyphMap.getDrawableForModifier(context, KeyEvent.KEYCODE_META_RIGHT))

        val functionRowKeys = glyphMap.functionRowKeys
        assertEquals(1, functionRowKeys.size)
        assertEquals(KeyEvent.KEYCODE_EMOJI_PICKER, functionRowKeys[0])

        val hardwareShortcuts = glyphMap.hardwareShortcuts
        assertEquals(2, hardwareShortcuts.size)
        assertEquals(
            KeyEvent.KEYCODE_BACK,
            hardwareShortcuts[KeyCombination(KeyEvent.META_FUNCTION_ON, KeyEvent.KEYCODE_1)]
        )
        assertEquals(
            KeyEvent.KEYCODE_HOME,
            hardwareShortcuts[
                KeyCombination(
                    KeyEvent.META_FUNCTION_ON or KeyEvent.META_META_ON,
                    KeyEvent.KEYCODE_2
                )
            ]
        )
    }
}
