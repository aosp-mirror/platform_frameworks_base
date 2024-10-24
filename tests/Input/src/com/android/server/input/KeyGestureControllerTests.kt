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
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.input.IInputManager
import android.hardware.input.AidlKeyGestureEvent
import android.hardware.input.IKeyGestureEventListener
import android.hardware.input.IKeyGestureHandler
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.hardware.input.KeyGestureEvent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.SystemProperties
import android.os.test.TestLooper
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManagerPolicyConstants.FLAG_INTERACTIVE
import androidx.test.core.app.ApplicationProvider
import com.android.internal.R
import com.android.internal.annotations.Keep
import com.android.internal.util.FrameworkStatsLog
import com.android.modules.utils.testing.ExtendedMockitoRule
import junitparams.JUnitParamsRunner
import junitparams.Parameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito

/**
 * Tests for {@link KeyGestureController}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyGestureControllerTests
 */
@Presubmit
@RunWith(JUnitParamsRunner::class)
class KeyGestureControllerTests {

    companion object {
        const val DEVICE_ID = 1
        val HOME_GESTURE_COMPLETE_EVENT = KeyGestureEvent.Builder()
            .setDeviceId(DEVICE_ID)
            .setKeycodes(intArrayOf(KeyEvent.KEYCODE_H))
            .setModifierState(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            .build()
        val MODIFIER = mapOf(
            KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON),
            KeyEvent.KEYCODE_CTRL_RIGHT to (KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_CTRL_ON),
            KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON),
            KeyEvent.KEYCODE_ALT_RIGHT to (KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON),
            KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON),
            KeyEvent.KEYCODE_SHIFT_RIGHT to (KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON),
            KeyEvent.KEYCODE_META_LEFT to (KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_ON),
            KeyEvent.KEYCODE_META_RIGHT to (KeyEvent.META_META_RIGHT_ON or KeyEvent.META_META_ON),
        )
        const val SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH = 0
        const val SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY = 1
        const val SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY = 0
        const val SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL = 1
        const val SETTINGS_KEY_BEHAVIOR_NOTHING = 2
    }

    @JvmField
    @Rule
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
        .mockStatic(FrameworkStatsLog::class.java)
        .mockStatic(SystemProperties::class.java).build()!!

    @JvmField
    @Rule
    val rule = SetFlagsRule()

    @Mock
    private lateinit var iInputManager: IInputManager

    @Mock
    private lateinit var resources: Resources

    @Mock
    private lateinit var packageManager: PackageManager

    private var currentPid = 0
    private lateinit var context: Context
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession
    private lateinit var testLooper: TestLooper
    private var events = mutableListOf<KeyGestureEvent>()

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        Mockito.`when`(context.resources).thenReturn(resources)
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManager)
        setupInputDevices()
        setupBehaviors()
        testLooper = TestLooper()
        currentPid = Process.myPid()
    }

    @After
    fun teardown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun setupBehaviors() {
        Mockito.`when`(
            resources.getBoolean(
                com.android.internal.R.bool.config_enableScreenshotChord
            )
        ).thenReturn(true)
        Mockito.`when`(packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
            .thenReturn(true)
        Mockito.`when`(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            .thenReturn(true)
        Mockito.`when`(context.packageManager).thenReturn(packageManager)
    }

    private fun setupInputDevices() {
        val inputManager = InputManager(context)
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
            .thenReturn(inputManager)

        val keyboardDevice = InputDevice.Builder().setId(DEVICE_ID).build()
        Mockito.`when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardDevice)
    }

    private fun notifyHomeGestureCompleted(keyGestureController: KeyGestureController) {
        keyGestureController.notifyKeyGestureCompleted(
            DEVICE_ID, intArrayOf(KeyEvent.KEYCODE_H),
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyGestureEvent.KEY_GESTURE_TYPE_HOME
        )
    }

    @Test
    fun testKeyGestureEvent_registerUnregisterListener() {
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        val listener = KeyGestureEventListener()

        // Register key gesture event listener
        keyGestureController.registerKeyGestureEventListener(listener, 0)
        notifyHomeGestureCompleted(keyGestureController)
        testLooper.dispatchAll()
        assertEquals(
            "Listener should get callbacks on key gesture event completed",
            1,
            events.size
        )
        assertEquals(
            "Listener should get callback for key gesture complete event",
            HOME_GESTURE_COMPLETE_EVENT,
            events[0]
        )

        // Unregister listener
        events.clear()
        keyGestureController.unregisterKeyGestureEventListener(listener, 0)
        notifyHomeGestureCompleted(keyGestureController)
        testLooper.dispatchAll()
        assertEquals(
            "Listener should not get callback after being unregistered",
            0,
            events.size
        )
    }

    @Test
    fun testKeyGestureEvent_multipleGestureHandlers() {
        val keyGestureController = KeyGestureController(context, testLooper.looper)

        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        var selfCallback = 0
        val externalHandler1 = KeyGestureHandler { _, _ ->
            callbackCount1++
            true
        }
        val externalHandler2 = KeyGestureHandler { _, _ ->
            callbackCount2++
            true
        }
        val selfHandler = KeyGestureHandler { _, _ ->
            selfCallback++
            false
        }

        // Register key gesture handler: External process (last in priority)
        keyGestureController.registerKeyGestureHandler(externalHandler1, currentPid + 1)

        // Register key gesture handler: External process (second in priority)
        keyGestureController.registerKeyGestureHandler(externalHandler2, currentPid - 1)

        // Register key gesture handler: Self process (first in priority)
        keyGestureController.registerKeyGestureHandler(selfHandler, currentPid)

        keyGestureController.handleKeyGesture(/* deviceId = */ 0, intArrayOf(KeyEvent.KEYCODE_HOME),
            /* modifierState = */ 0, KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
            KeyGestureEvent.ACTION_GESTURE_COMPLETE, /* displayId */ 0,
            /* focusedToken = */ null, /* flags = */ 0
        )

        assertEquals(
            "Self handler should get callbacks first",
            1,
            selfCallback
        )
        assertEquals(
            "Higher priority handler should get callbacks first",
            1,
            callbackCount2
        )
        assertEquals(
            "Lower priority handler should not get callbacks if already handled",
            0,
            callbackCount1
        )
    }

    class TestData(
        val name: String,
        val keys: IntArray,
        val expectedKeyGestureType: Int,
        val expectedKeys: IntArray,
        val expectedModifierState: Int,
        val expectedActions: IntArray,
    ) {
        override fun toString(): String = name
    }

    @Keep
    private fun keyGestureEventHandlerTestArguments(): Array<TestData> {
        return arrayOf(
            TestData(
                "META + A -> Launch Assistant",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_A),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT,
                intArrayOf(KeyEvent.KEYCODE_A),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "RECENT_APPS -> Show Overview",
                intArrayOf(KeyEvent.KEYCODE_RECENT_APPS),
                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                intArrayOf(KeyEvent.KEYCODE_RECENT_APPS),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "APP_SWITCH -> App Switch",
                intArrayOf(KeyEvent.KEYCODE_APP_SWITCH),
                KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                intArrayOf(KeyEvent.KEYCODE_APP_SWITCH),
                0,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
            TestData(
                "META + H -> Go Home",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_H),
                KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                intArrayOf(KeyEvent.KEYCODE_H),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + ENTER -> Go Home",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ENTER),
                KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                intArrayOf(KeyEvent.KEYCODE_ENTER),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + I -> Launch System Settings",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_I),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
                intArrayOf(KeyEvent.KEYCODE_I),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + L -> Lock",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_L),
                KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN,
                intArrayOf(KeyEvent.KEYCODE_L),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + N -> Toggle Notification",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_N),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                intArrayOf(KeyEvent.KEYCODE_N),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + N -> Open Notes",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_N
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES,
                intArrayOf(KeyEvent.KEYCODE_N),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + S -> Take Screenshot",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_S
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_S),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + DEL -> Back",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_DEL),
                KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                intArrayOf(KeyEvent.KEYCODE_DEL),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + ESC -> Back",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ESCAPE),
                KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                intArrayOf(KeyEvent.KEYCODE_ESCAPE),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + DPAD_LEFT -> Back",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_DPAD_LEFT),
                KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + DPAD_UP -> Multi Window Navigation",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_DPAD_UP
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION,
                intArrayOf(KeyEvent.KEYCODE_DPAD_UP),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + DPAD_DOWN -> Desktop Mode",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_DPAD_DOWN
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE,
                intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + DPAD_LEFT -> Splitscreen Navigation Left",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_DPAD_LEFT
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT,
                intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + DPAD_RIGHT -> Splitscreen Navigation Right",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT,
                intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + ALT + DPAD_LEFT -> Change Splitscreen Focus Left",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_ALT_LEFT,
                    KeyEvent.KEYCODE_DPAD_LEFT
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT,
                intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT),
                KeyEvent.META_META_ON or KeyEvent.META_ALT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + CTRL + DPAD_RIGHT -> Change Splitscreen Focus Right",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_ALT_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT,
                intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT),
                KeyEvent.META_META_ON or KeyEvent.META_ALT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + / -> Open Shortcut Helper",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_SLASH),
                KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER,
                intArrayOf(KeyEvent.KEYCODE_SLASH),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "BRIGHTNESS_UP -> Brightness Up",
                intArrayOf(KeyEvent.KEYCODE_BRIGHTNESS_UP),
                KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP,
                intArrayOf(KeyEvent.KEYCODE_BRIGHTNESS_UP),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "BRIGHTNESS_DOWN -> Brightness Down",
                intArrayOf(KeyEvent.KEYCODE_BRIGHTNESS_DOWN),
                KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN,
                intArrayOf(KeyEvent.KEYCODE_BRIGHTNESS_DOWN),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "KEYBOARD_BACKLIGHT_UP -> Keyboard Backlight Up",
                intArrayOf(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP),
                KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP,
                intArrayOf(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "KEYBOARD_BACKLIGHT_DOWN -> Keyboard Backlight Down",
                intArrayOf(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN),
                KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN,
                intArrayOf(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "KEYBOARD_BACKLIGHT_TOGGLE -> Keyboard Backlight Toggle",
                intArrayOf(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE),
                KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE,
                intArrayOf(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "ALL_APPS -> Open App Drawer",
                intArrayOf(KeyEvent.KEYCODE_ALL_APPS),
                KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                intArrayOf(KeyEvent.KEYCODE_ALL_APPS),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "NOTIFICATION -> Toggle Notification Panel",
                intArrayOf(KeyEvent.KEYCODE_NOTIFICATION),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                intArrayOf(KeyEvent.KEYCODE_NOTIFICATION),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "LANGUAGE_SWITCH -> Switch Language Forward",
                intArrayOf(KeyEvent.KEYCODE_LANGUAGE_SWITCH),
                KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                intArrayOf(KeyEvent.KEYCODE_LANGUAGE_SWITCH),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "SHIFT + LANGUAGE_SWITCH -> Switch Language Backward",
                intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_LANGUAGE_SWITCH),
                KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                intArrayOf(KeyEvent.KEYCODE_LANGUAGE_SWITCH),
                KeyEvent.META_SHIFT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "SCREENSHOT -> Take Screenshot",
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SCREENSHOT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META -> Open Apps Drawer",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT),
                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS,
                intArrayOf(KeyEvent.KEYCODE_META_LEFT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + ALT -> Toggle Caps Lock",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ALT_LEFT),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ALT_LEFT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "ALT + META -> Toggle Caps Lock",
                intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ALT_LEFT),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "META + TAB -> Open Overview",
                intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_TAB),
                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                intArrayOf(KeyEvent.KEYCODE_TAB),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "ALT + TAB -> Toggle Recent Apps Switcher",
                intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB),
                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER,
                intArrayOf(KeyEvent.KEYCODE_TAB),
                KeyEvent.META_ALT_ON,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
            TestData(
                "CTRL + SPACE -> Switch Language Forward",
                intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SPACE),
                KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                intArrayOf(KeyEvent.KEYCODE_SPACE),
                KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "CTRL + SHIFT + SPACE -> Switch Language Backward",
                intArrayOf(
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_SHIFT_LEFT,
                    KeyEvent.KEYCODE_SPACE
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                intArrayOf(KeyEvent.KEYCODE_SPACE),
                KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "CTRL + ALT + Z -> Accessibility Shortcut",
                intArrayOf(
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_ALT_LEFT,
                    KeyEvent.KEYCODE_Z
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT,
                intArrayOf(KeyEvent.KEYCODE_Z),
                KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "SYSRQ -> Take screenshot",
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                intArrayOf(KeyEvent.KEYCODE_SYSRQ),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
            TestData(
                "ESC -> Close All Dialogs",
                intArrayOf(KeyEvent.KEYCODE_ESCAPE),
                KeyGestureEvent.KEY_GESTURE_TYPE_CLOSE_ALL_DIALOGS,
                intArrayOf(KeyEvent.KEYCODE_ESCAPE),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            ),
        )
    }

    @Test
    @Parameters(method = "keyGestureEventHandlerTestArguments")
    fun testKeyGestures(test: TestData) {
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(keyGestureController, test)
    }

    @Test
    fun testKeycodesFullyConsumed_irrespectiveOfHandlers() {
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        val testKeys = intArrayOf(
            KeyEvent.KEYCODE_RECENT_APPS,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_BRIGHTNESS_UP,
            KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
            KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN,
            KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP,
            KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE,
            KeyEvent.KEYCODE_ALL_APPS,
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_LANGUAGE_SWITCH,
            KeyEvent.KEYCODE_SCREENSHOT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
            KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
        )

        val handler = KeyGestureHandler { _, _ -> false }
        keyGestureController.registerKeyGestureHandler(handler, 0)

        for (key in testKeys) {
            sendKeys(keyGestureController, intArrayOf(key), assertNotSentToApps = true)
        }
    }

    @Test
    fun testSearchKeyGestures_defaultSearch() {
        Mockito.`when`(resources.getInteger(R.integer.config_searchKeyBehavior))
            .thenReturn(SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH)
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureNotProduced(
            keyGestureController,
            "SEARCH -> Default Search",
            intArrayOf(KeyEvent.KEYCODE_SEARCH),
        )
    }

    @Test
    fun testSearchKeyGestures_searchActivity() {
        Mockito.`when`(resources.getInteger(R.integer.config_searchKeyBehavior))
            .thenReturn(SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY)
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(
            keyGestureController,
            TestData(
                "SEARCH -> Launch Search Activity",
                intArrayOf(KeyEvent.KEYCODE_SEARCH),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH,
                intArrayOf(KeyEvent.KEYCODE_SEARCH),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            )
        )
    }

    @Test
    fun testSettingKeyGestures_doNothing() {
        Mockito.`when`(resources.getInteger(R.integer.config_settingsKeyBehavior))
            .thenReturn(SETTINGS_KEY_BEHAVIOR_NOTHING)
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureNotProduced(
            keyGestureController,
            "SETTINGS -> Do Nothing",
            intArrayOf(KeyEvent.KEYCODE_SETTINGS),
        )
    }

    @Test
    fun testSettingKeyGestures_settingsActivity() {
        Mockito.`when`(resources.getInteger(R.integer.config_settingsKeyBehavior))
            .thenReturn(SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY)
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(
            keyGestureController,
            TestData(
                "SETTINGS -> Launch Settings Activity",
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            )
        )
    }

    @Test
    fun testSettingKeyGestures_notificationPanel() {
        Mockito.`when`(resources.getInteger(R.integer.config_settingsKeyBehavior))
            .thenReturn(SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL)
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(
            keyGestureController,
            TestData(
                "SETTINGS -> Toggle Notification Panel",
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                intArrayOf(KeyEvent.KEYCODE_SETTINGS),
                0,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            )
        )
    }

    @Test
    @EnableFlags(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    fun testTriggerBugReport() {
        Mockito.`when`(SystemProperties.get("ro.debuggable")).thenReturn("1")
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(
            keyGestureController,
            TestData(
                "META + CTRL + DEL -> Trigger Bug Report",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_DEL
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT,
                intArrayOf(KeyEvent.KEYCODE_DEL),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            )
        )
    }

    @Test
    @DisableFlags(com.android.server.flags.Flags.FLAG_NEW_BUGREPORT_KEYBOARD_SHORTCUT)
    fun testTriggerBugReport_flagDisabled() {
        Mockito.`when`(SystemProperties.get("ro.debuggable")).thenReturn("1")
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(
            keyGestureController,
            TestData(
                "META + CTRL + DEL -> Not Trigger Bug Report (Fallback to BACK)",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_DEL
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                intArrayOf(KeyEvent.KEYCODE_DEL),
                KeyEvent.META_META_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            )
        )
    }

    @Test
    @EnableFlags(com.android.window.flags.Flags.FLAG_ENABLE_MOVE_TO_NEXT_DISPLAY_SHORTCUT)
    fun testMoveToNextDisplay() {
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(
            keyGestureController,
            TestData(
                "META + CTRL + D -> Move a task to next display",
                intArrayOf(
                    KeyEvent.KEYCODE_META_LEFT,
                    KeyEvent.KEYCODE_CTRL_LEFT,
                    KeyEvent.KEYCODE_D
                ),
                KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY,
                intArrayOf(KeyEvent.KEYCODE_D),
                KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON,
                intArrayOf(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            )
        )
    }

    @Test
    fun testCapsLockPressNotified() {
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        val listener = KeyGestureEventListener()

        keyGestureController.registerKeyGestureEventListener(listener, 0)
        sendKeys(keyGestureController, intArrayOf(KeyEvent.KEYCODE_CAPS_LOCK))
        testLooper.dispatchAll()
        assertEquals(
            "Listener should get callbacks on key gesture event completed",
            1,
            events.size
        )
        assertEquals(
            "Listener should get callback for Toggle Caps Lock key gesture complete event",
            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
            events[0].keyGestureType
        )
    }

    @Keep
    private fun keyGestureEventHandlerTestArguments_forKeyCombinations(): Array<TestData> {
        return arrayOf(
            TestData(
                "VOLUME_DOWN + POWER -> Screenshot Chord",
                intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER),
                KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD,
                intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER),
                0,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
            TestData(
                "POWER + STEM_PRIMARY -> Screenshot Chord",
                intArrayOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_STEM_PRIMARY),
                KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD,
                intArrayOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_STEM_PRIMARY),
                0,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
            TestData(
                "VOLUME_DOWN + VOLUME_UP -> Accessibility Chord",
                intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP),
                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD,
                intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP),
                0,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
            TestData(
                "BACK + DPAD_DOWN -> TV Accessibility Chord",
                intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN),
                KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD,
                intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN),
                0,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
            TestData(
                "BACK + DPAD_CENTER -> TV Trigger Bug Report",
                intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER),
                KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT,
                intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER),
                0,
                intArrayOf(
                    KeyGestureEvent.ACTION_GESTURE_START,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE
                )
            ),
        )
    }

    @Test
    @Parameters(method = "keyGestureEventHandlerTestArguments_forKeyCombinations")
    @EnableFlags(
        com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER,
        com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER_MULTI_PRESS_GESTURES
    )
    fun testKeyCombinationGestures(test: TestData) {
        val keyGestureController = KeyGestureController(context, testLooper.looper)
        testKeyGestureInternal(keyGestureController, test)
    }

    private fun testKeyGestureInternal(keyGestureController: KeyGestureController, test: TestData) {
        var handleEvents = mutableListOf<KeyGestureEvent>()
        val handler = KeyGestureHandler { event, _ ->
            handleEvents.add(KeyGestureEvent(event))
            true
        }
        keyGestureController.registerKeyGestureHandler(handler, 0)
        handleEvents.clear()

        sendKeys(keyGestureController, test.keys)

        assertEquals(
            "Test: $test doesn't produce correct number of key gesture events",
            test.expectedActions.size,
            handleEvents.size
        )
        for (i in handleEvents.indices) {
            val event = handleEvents[i]
            assertArrayEquals(
                "Test: $test doesn't produce correct key gesture keycodes",
                test.expectedKeys,
                event.keycodes
            )
            assertEquals(
                "Test: $test doesn't produce correct key gesture modifier state",
                test.expectedModifierState,
                event.modifierState
            )
            assertEquals(
                "Test: $test doesn't produce correct key gesture type",
                test.expectedKeyGestureType,
                event.keyGestureType
            )
            assertEquals(
                "Test: $test doesn't produce correct key gesture action",
                test.expectedActions[i],
                event.action
            )
        }

        keyGestureController.unregisterKeyGestureHandler(handler, 0)
    }

    private fun testKeyGestureNotProduced(
        keyGestureController: KeyGestureController,
        testName: String,
        testKeys: IntArray
    ) {
        var handleEvents = mutableListOf<KeyGestureEvent>()
        val handler = KeyGestureHandler { event, _ ->
            handleEvents.add(KeyGestureEvent(event))
            true
        }
        keyGestureController.registerKeyGestureHandler(handler, 0)
        handleEvents.clear()

        sendKeys(keyGestureController, testKeys)
        assertEquals("Test: $testName should not produce Key gesture", 0, handleEvents.size)
    }

    private fun sendKeys(
        keyGestureController: KeyGestureController,
        testKeys: IntArray,
        assertNotSentToApps: Boolean = false
    ) {
        var metaState = 0
        val now = SystemClock.uptimeMillis()
        for (key in testKeys) {
            val downEvent = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, key, 0 /*repeat*/, metaState,
                DEVICE_ID, 0 /*scancode*/, 0 /*flags*/,
                InputDevice.SOURCE_KEYBOARD
            )
            interceptKey(keyGestureController, downEvent, assertNotSentToApps)
            metaState = metaState or MODIFIER.getOrDefault(key, 0)

            downEvent.recycle()
            testLooper.dispatchAll()
        }

        for (key in testKeys.reversed()) {
            val upEvent = KeyEvent(
                now, now, KeyEvent.ACTION_UP, key, 0 /*repeat*/, metaState,
                DEVICE_ID, 0 /*scancode*/, 0 /*flags*/,
                InputDevice.SOURCE_KEYBOARD
            )
            interceptKey(keyGestureController, upEvent, assertNotSentToApps)
            metaState = metaState and MODIFIER.getOrDefault(key, 0).inv()

            upEvent.recycle()
            testLooper.dispatchAll()
        }
    }

    private fun interceptKey(
        keyGestureController: KeyGestureController,
        event: KeyEvent,
        assertNotSentToApps: Boolean
    ) {
        keyGestureController.interceptKeyBeforeQueueing(event, FLAG_INTERACTIVE)
        testLooper.dispatchAll()

        val consumed =
            keyGestureController.interceptKeyBeforeDispatching(null, event, 0) == -1L
        if (assertNotSentToApps) {
            assertTrue(
                "interceptKeyBeforeDispatching should consume all events $event",
                consumed
            )
        }
        if (!consumed) {
            keyGestureController.interceptUnhandledKey(event, null)
        }
    }

    inner class KeyGestureEventListener : IKeyGestureEventListener.Stub() {
        override fun onKeyGestureEvent(event: AidlKeyGestureEvent) {
            events.add(KeyGestureEvent(event))
        }
    }

    inner class KeyGestureHandler(
        private var handler: (event: AidlKeyGestureEvent, token: IBinder?) -> Boolean
    ) : IKeyGestureHandler.Stub() {
        override fun handleKeyGesture(event: AidlKeyGestureEvent, token: IBinder?): Boolean {
            return handler(event, token)
        }

        override fun isKeyGestureSupported(gestureType: Int): Boolean {
            return true
        }
    }
}