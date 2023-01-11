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
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
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

/**
 * Tests for {@link KeyRemapper}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:KeyRemapperTests
 */
@Presubmit
class KeyRemapperTests {

    companion object {
        const val DEVICE_ID = 1
        val REMAPPABLE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK
        )
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var iInputManager: IInputManager
    @Mock
    private lateinit var native: NativeInputManagerService
    private lateinit var mKeyRemapper: KeyRemapper
    private lateinit var context: Context
    private lateinit var dataStore: PersistentDataStore
    private lateinit var testLooper: TestLooper

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
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
        mKeyRemapper = KeyRemapper(
            context,
            native,
            dataStore,
            testLooper.looper
        )
        val inputManager = InputManager.resetInstance(iInputManager)
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
            .thenReturn(inputManager)
        Mockito.`when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
    }

    @After
    fun tearDown() {
        InputManager.clearInstance()
    }

    @Test
    fun testKeyRemapping_whenRemappingEnabled() {
        ModifierRemappingFlag(true).use {
            val keyboard = createKeyboard(DEVICE_ID)
            Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboard)

            for (i in REMAPPABLE_KEYS.indices) {
                val fromKeyCode = REMAPPABLE_KEYS[i]
                val toKeyCode = REMAPPABLE_KEYS[(i + 1) % REMAPPABLE_KEYS.size]
                mKeyRemapper.remapKey(fromKeyCode, toKeyCode)
                testLooper.dispatchNext()
            }

            val remapping = mKeyRemapper.keyRemapping
            val expectedSize = REMAPPABLE_KEYS.size
            assertEquals("Remapping size should be $expectedSize", expectedSize, remapping.size)

            for (i in REMAPPABLE_KEYS.indices) {
                val fromKeyCode = REMAPPABLE_KEYS[i]
                val toKeyCode = REMAPPABLE_KEYS[(i + 1) % REMAPPABLE_KEYS.size]
                assertEquals(
                    "Remapping should include mapping from $fromKeyCode to $toKeyCode",
                    toKeyCode,
                    remapping.getOrDefault(fromKeyCode, -1)
                )
            }

            mKeyRemapper.clearAllKeyRemappings()
            testLooper.dispatchNext()

            assertEquals(
                "Remapping size should be 0 after clearAllModifierKeyRemappings",
                0,
                mKeyRemapper.keyRemapping.size
            )
        }
    }

    @Test
    fun testKeyRemapping_whenRemappingDisabled() {
        ModifierRemappingFlag(false).use {
            val keyboard = createKeyboard(DEVICE_ID)
            Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboard)

            mKeyRemapper.remapKey(REMAPPABLE_KEYS[0], REMAPPABLE_KEYS[1])
            testLooper.dispatchAll()

            val remapping = mKeyRemapper.keyRemapping
            assertEquals(
                "Remapping should not be done if modifier key remapping is disabled",
                0,
                remapping.size
            )
        }
    }

    private inner class ModifierRemappingFlag constructor(enabled: Boolean) : AutoCloseable {
        init {
            Settings.Global.putString(
                context.contentResolver,
                "settings_new_keyboard_modifier_key", enabled.toString()
            )
        }

        override fun close() {
            Settings.Global.putString(
                context.contentResolver,
                "settings_new_keyboard_modifier_key",
                ""
            )
        }
    }
}