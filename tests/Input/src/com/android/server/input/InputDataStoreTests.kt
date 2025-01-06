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

import android.app.role.RoleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.input.AppLaunchData
import android.hardware.input.InputGestureData
import android.hardware.input.KeyGestureEvent
import android.platform.test.annotations.Presubmit
import android.util.AtomicFile
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Tests for {@link InputDataStore}.
 *
 * Build/Install/Run:
 * atest InputTests:InputDataStoreTests
 */
@Presubmit
class InputDataStoreTests {

    companion object {
        const val USER_ID = 1
    }

    private lateinit var context: Context
    private lateinit var inputDataStore: InputDataStore
    private lateinit var tempFile: File

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        setupInputDataStore()
    }

    private fun setupInputDataStore() {
        tempFile = File.createTempFile("input_gestures", ".xml")
        inputDataStore = InputDataStore(object : InputDataStore.FileInjector("input_gestures") {
            private val atomicFile: AtomicFile = AtomicFile(tempFile)

            override fun openRead(userId: Int): InputStream? {
                return atomicFile.openRead()
            }

            override fun startWrite(userId: Int): FileOutputStream? {
                return atomicFile.startWrite()
            }

            override fun finishWrite(userId: Int, fos: FileOutputStream?, success: Boolean) {
                if (success) {
                    atomicFile.finishWrite(fos)
                } else {
                    atomicFile.failWrite(fos)
                }
            }
        })
    }

    private fun getPrintableXml(inputGestures: List<InputGestureData>): String {
        val outputStream = ByteArrayOutputStream()
        inputDataStore.writeInputGestureXml(outputStream, true, inputGestures)
        return outputStream.toString(StandardCharsets.UTF_8).trimIndent()
    }

    @Test
    fun saveToDiskKeyGesturesOnly() {
        val inputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_H,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS)
                .build()
        )

        inputDataStore.saveInputGestures(USER_ID, inputGestures)
        assertEquals(
            inputGestures,
            inputDataStore.loadInputGestures(USER_ID),
            getPrintableXml(inputGestures)
        )
    }

    @Test
    fun saveToDiskTouchpadGestures() {
        val inputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createTouchpadTrigger(
                        InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .build()
        )

        inputDataStore.saveInputGestures(USER_ID, inputGestures)
        assertEquals(
            inputGestures,
            inputDataStore.loadInputGestures(USER_ID),
            getPrintableXml(inputGestures)
        )
    }

    @Test
    fun saveToDiskAppLaunchGestures() {
        val inputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createTouchpadTrigger(
                        InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                .setAppLaunchData(AppLaunchData.createLaunchDataForRole(RoleManager.ROLE_BROWSER))
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                .setAppLaunchData(AppLaunchData.createLaunchDataForCategory(Intent.CATEGORY_APP_CONTACTS))
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                .setAppLaunchData(
                    AppLaunchData.createLaunchDataForComponent(
                        "com.test",
                        "com.test.BookmarkTest"
                    )
                )
                .build()
        )

        inputDataStore.saveInputGestures(USER_ID, inputGestures)
        assertEquals(
            inputGestures,
            inputDataStore.loadInputGestures(USER_ID),
            getPrintableXml(inputGestures)
        )
    }

    @Test
    fun saveToDiskCombinedGestures() {
        val inputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createTouchpadTrigger(
                        InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_9,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                .setAppLaunchData(AppLaunchData.createLaunchDataForCategory(Intent.CATEGORY_APP_CONTACTS))
                .build(),
        )

        inputDataStore.saveInputGestures(USER_ID, inputGestures)
        assertEquals(
            inputGestures,
            inputDataStore.loadInputGestures(USER_ID),
            getPrintableXml(inputGestures)
        )
    }

    @Test
    fun validXmlParse() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <root>
                <input_gesture_list>
                    <input_gesture key_gesture_type="3">
                        <key_trigger keycode="8" modifiers="69632" />
                    </input_gesture>
                    <input_gesture key_gesture_type="21">
                        <key_trigger keycode="9" modifiers="65536" />
                    </input_gesture>
                    <input_gesture key_gesture_type="1">
                        <touchpad_trigger touchpad_gesture_type="1" />
                    </input_gesture>
                </input_gesture_list>
            </root>""".trimIndent()
        val validInputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createTouchpadTrigger(
                        InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .build()
        )
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            validInputGestures,
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }

    @Test
    fun missingTriggerData() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <root>
                <input_gesture_list>
                    <input_gesture key_gesture_type="3">
                    </input_gesture>
                    <input_gesture key_gesture_type="21">
                        <key_trigger keycode="9" modifiers="65536" />
                    </input_gesture>
                    <input_gesture key_gesture_type="1">
                        <touchpad_trigger touchpad_gesture_type="1" />
                    </input_gesture>
                </input_gesture_list>
            </root>""".trimIndent()
        val validInputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createTouchpadTrigger(
                        InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .build()
        )
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            validInputGestures,
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }

    @Test
    fun invalidKeycode() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <root>
                <input_gesture_list>
                    <input_gesture key_gesture_type="3">
                        <key_trigger keycode="8" modifiers="69632" />
                    </input_gesture>
                    <input_gesture key_gesture_type="21">
                        <key_trigger keycode="9999999" modifiers="65536" />
                    </input_gesture>
                    <input_gesture key_gesture_type="1">
                        <touchpad_trigger touchpad_gesture_type="1" />
                    </input_gesture>
                </input_gesture_list>
            </root>""".trimIndent()
        val validInputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createTouchpadTrigger(
                        InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
                .build()
        )
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            validInputGestures,
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }

    @Test
    fun invalidTriggerName() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <root>
                <input_gesture_list>
                    <input_gesture key_gesture_type="3">
                        <key_trigger keycode="8" modifiers="69632" />
                    </input_gesture>
                    <input_gesture key_gesture_type="21">
                        <key_trigger keycode="9" modifiers="65536" />
                    </input_gesture>
                    <input_gesture key_gesture_type="1">
                        <invalid_trigger_name touchpad_gesture_type="1" />
                    </input_gesture>
                </input_gesture_list>
            </root>""".trimIndent()
        val validInputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS)
                .build(),
        )
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            validInputGestures,
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }

    @Test
    fun invalidTouchpadGestureType() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <root>
                <input_gesture_list>
                    <input_gesture key_gesture_type="3">
                        <key_trigger keycode="8" modifiers="69632" />
                    </input_gesture>
                    <input_gesture key_gesture_type="21">
                    <key_trigger keycode="9" modifiers="65536" />
                    </input_gesture>
                    <input_gesture key_gesture_type="1">
                        <touchpad_trigger touchpad_gesture_type="9999" />
                    </input_gesture>
                </input_gesture_list>
            </root>""".trimIndent()
        val validInputGestures = listOf(
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_1,
                        KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
                .build(),
            InputGestureData.Builder()
                .setTrigger(
                    InputGestureData.createKeyTrigger(
                        KeyEvent.KEYCODE_2,
                        KeyEvent.META_META_ON
                    )
                )
                .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS)
                .build(),
        )
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            validInputGestures,
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }

    @Test
    fun emptyInputGestureList() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <root>
                <input_gesture_list>
                </input_gesture_list>
            </root>""".trimIndent()
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            listOf(),
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }

    @Test
    fun invalidTag() {
        val xmlData = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <root>
                <invalid_tag_name>
                </invalid_tag_name>
            </root>""".trimIndent()
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(Charsets.UTF_8))
        assertEquals(
            listOf(),
            inputDataStore.readInputGesturesXml(inputStream, true)
        )
    }
}