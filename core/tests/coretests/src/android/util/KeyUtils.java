/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase;
import android.test.InstrumentationTestCase;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Reusable methods for generating key events.
 * <p>
 * Definitions:
 * <li> Tap refers to pushing and releasing a button (down and up event).
 * <li> Chord refers to pushing a modifier key, tapping a regular key, and
 * releasing the modifier key.
 */
public class KeyUtils {
    /**
     * Simulates tapping the menu key.
     * 
     * @param test The test case that is being run.
     */
    public static void tapMenuKey(ActivityInstrumentationTestCase test) {
        final Instrumentation inst = test.getInstrumentation();

        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU));
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU));
    }

    /**
     * Simulates chording the menu key.
     * 
     * @param test The test case that is being run.
     * @param shortcutKey The shortcut key to tap while chording the menu key.
     */
    public static void chordMenuKey(ActivityInstrumentationTestCase test, char shortcutKey) {
        final Instrumentation inst = test.getInstrumentation();

        final KeyEvent pushMenuKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        final KeyCharacterMap keyCharMap = KeyCharacterMap.load(pushMenuKey.getDeviceId());
        final KeyEvent shortcutKeyEvent = keyCharMap.getEvents(new char[] { shortcutKey })[0];
        final int shortcutKeyCode = shortcutKeyEvent.getKeyCode();
        
        inst.sendKeySync(pushMenuKey);
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, shortcutKeyCode));
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, shortcutKeyCode));
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU));
    }

    /**
     * Simulates a long click via the keyboard.
     * 
     * @param test The test case that is being run. 
     */
    public static void longClick(ActivityInstrumentationTestCase test) {
        final Instrumentation inst = test.getInstrumentation();

        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        try {
            Thread.sleep((long)(ViewConfiguration.getLongPressTimeout() * 1.5f));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        inst.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
    }
}
