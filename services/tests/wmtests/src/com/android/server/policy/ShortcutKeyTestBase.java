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
package com.android.server.policy;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.KeyEvent.KEYCODE_ALT_LEFT;
import static android.view.KeyEvent.KEYCODE_ALT_RIGHT;
import static android.view.KeyEvent.KEYCODE_CTRL_LEFT;
import static android.view.KeyEvent.KEYCODE_CTRL_RIGHT;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_META_RIGHT;
import static android.view.KeyEvent.KEYCODE_SHIFT_LEFT;
import static android.view.KeyEvent.KEYCODE_SHIFT_RIGHT;
import static android.view.KeyEvent.META_ALT_LEFT_ON;
import static android.view.KeyEvent.META_ALT_ON;
import static android.view.KeyEvent.META_ALT_RIGHT_ON;
import static android.view.KeyEvent.META_CTRL_LEFT_ON;
import static android.view.KeyEvent.META_CTRL_ON;
import static android.view.KeyEvent.META_CTRL_RIGHT_ON;
import static android.view.KeyEvent.META_META_LEFT_ON;
import static android.view.KeyEvent.META_META_ON;
import static android.view.KeyEvent.META_META_RIGHT_ON;
import static android.view.KeyEvent.META_SHIFT_LEFT_ON;
import static android.view.KeyEvent.META_SHIFT_ON;
import static android.view.KeyEvent.META_SHIFT_RIGHT_ON;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.policy.WindowManagerPolicy.ACTION_PASS_TO_USER;

import static java.util.Collections.unmodifiableMap;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;

import java.util.Map;

class ShortcutKeyTestBase {
    TestPhoneWindowManager mPhoneWindowManager;
    final Context mContext = getInstrumentation().getTargetContext();

    /** Modifier key to meta state */
    private static final Map<Integer, Integer> MODIFIER;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(KEYCODE_CTRL_LEFT, META_CTRL_LEFT_ON | META_CTRL_ON);
        map.put(KEYCODE_CTRL_RIGHT, META_CTRL_RIGHT_ON | META_CTRL_ON);
        map.put(KEYCODE_ALT_LEFT, META_ALT_LEFT_ON | META_ALT_ON);
        map.put(KEYCODE_ALT_RIGHT, META_ALT_RIGHT_ON | META_ALT_ON);
        map.put(KEYCODE_SHIFT_LEFT, META_SHIFT_LEFT_ON | META_SHIFT_ON);
        map.put(KEYCODE_SHIFT_RIGHT, META_SHIFT_RIGHT_ON | META_SHIFT_ON);
        map.put(KEYCODE_META_LEFT, META_META_LEFT_ON | META_META_ON);
        map.put(KEYCODE_META_RIGHT, META_META_RIGHT_ON | META_META_ON);

        MODIFIER = unmodifiableMap(map);
    }

    @Before
    public void setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mPhoneWindowManager = new TestPhoneWindowManager(mContext);
    }

    @After
    public void tearDown() {
        mPhoneWindowManager.tearDown();
    }

    void sendKeyCombination(int[] keyCodes, long duration) {
        final long downTime = SystemClock.uptimeMillis();
        final int count = keyCodes.length;
        final KeyEvent[] events = new KeyEvent[count];
        int metaState = 0;
        for (int i = 0; i < count; i++) {
            final int keyCode = keyCodes[i];
            final KeyEvent event = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode,
                    0 /*repeat*/, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/,
                    0 /*flags*/, InputDevice.SOURCE_KEYBOARD);
            event.setDisplayId(DEFAULT_DISPLAY);
            events[i] = event;
            // The order is important here, metaState could be updated and applied to the next key.
            metaState |= MODIFIER.getOrDefault(keyCode, 0);
        }

        for (KeyEvent event: events) {
            interceptKey(event);
        }

        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        for (int i = count - 1; i >= 0; i--) {
            final long eventTime = SystemClock.uptimeMillis();
            final int keyCode = keyCodes[i];
            final KeyEvent upEvent = new KeyEvent(downTime, eventTime, KeyEvent.ACTION_UP, keyCode,
                    0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/, 0 /*flags*/,
                    InputDevice.SOURCE_KEYBOARD);
            interceptKey(upEvent);
            metaState &= ~MODIFIER.getOrDefault(keyCode, 0);
        }
    }

    void sendKey(int keyCode) {
        sendKey(keyCode, false);
    }

    void sendKey(int keyCode, boolean longPress) {
        final long downTime = SystemClock.uptimeMillis();
        final KeyEvent event = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode,
                0 /*repeat*/, 0 /*metaState*/, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/,
                0 /*flags*/, InputDevice.SOURCE_KEYBOARD);
        event.setDisplayId(DEFAULT_DISPLAY);
        interceptKey(event);

        if (longPress) {
            final long nextDownTime = downTime + ViewConfiguration.getLongPressTimeout();
            final KeyEvent nextDownevent = new KeyEvent(downTime, nextDownTime,
                    KeyEvent.ACTION_DOWN, keyCode, 1 /*repeat*/, 0 /*metaState*/,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/,
                    KeyEvent.FLAG_LONG_PRESS /*flags*/, InputDevice.SOURCE_KEYBOARD);
            interceptKey(nextDownevent);
        }

        final long eventTime = longPress
                ? SystemClock.uptimeMillis() + ViewConfiguration.getLongPressTimeout()
                : SystemClock.uptimeMillis();
        final KeyEvent upEvent = new KeyEvent(downTime, eventTime, KeyEvent.ACTION_UP, keyCode,
                0 /*repeat*/, 0 /*metaState*/, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/,
                0 /*flags*/, InputDevice.SOURCE_KEYBOARD);
        interceptKey(upEvent);
    }

    private void interceptKey(KeyEvent keyEvent) {
        int actions = mPhoneWindowManager.interceptKeyBeforeQueueing(keyEvent);
        if ((actions & ACTION_PASS_TO_USER) != 0) {
            if (0 == mPhoneWindowManager.interceptKeyBeforeDispatching(keyEvent)) {
                mPhoneWindowManager.dispatchUnhandledKey(keyEvent);
            }
        }
    }
}
