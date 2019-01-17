/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import static android.view.Display.INVALID_DISPLAY;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyEventTest {

    private static final int DOWN_TIME = 50;
    private static final long EVENT_TIME = 100;
    private static final int ACTION = KeyEvent.ACTION_DOWN;
    private static final int KEYCODE = KeyEvent.KEYCODE_0;
    private static final int REPEAT = 0;
    private static final int METASTATE = 0;
    private static final int DEVICE_ID = 0;
    private static final int SCAN_CODE = 0;
    private static final int FLAGS = 0;
    private static final int SOURCE = InputDevice.SOURCE_KEYBOARD;
    private static final String CHARACTERS = null;

    @Test
    public void testObtain() {
        KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, CHARACTERS);
        assertEquals(DOWN_TIME, keyEvent.getDownTime());
        assertEquals(EVENT_TIME, keyEvent.getEventTime());
        assertEquals(ACTION, keyEvent.getAction());
        assertEquals(KEYCODE, keyEvent.getKeyCode());
        assertEquals(REPEAT, keyEvent.getRepeatCount());
        assertEquals(METASTATE, keyEvent.getMetaState());
        assertEquals(DEVICE_ID, keyEvent.getDeviceId());
        assertEquals(SCAN_CODE, keyEvent.getScanCode());
        assertEquals(FLAGS, keyEvent.getFlags());
        assertEquals(SOURCE, keyEvent.getSource());
        assertEquals(INVALID_DISPLAY, keyEvent.getDisplayId());
        assertEquals(CHARACTERS, keyEvent.getCharacters());
    }

    @Test
    public void testObtainFromKeyEvent() {
        KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, CHARACTERS);
        KeyEvent keyEvent2 = KeyEvent.obtain(keyEvent);
        assertEquals(keyEvent.getDownTime(), keyEvent2.getDownTime());
        assertEquals(keyEvent.getEventTime(), keyEvent2.getEventTime());
        assertEquals(keyEvent.getAction(), keyEvent2.getAction());
        assertEquals(keyEvent.getKeyCode(), keyEvent2.getKeyCode());
        assertEquals(keyEvent.getRepeatCount(), keyEvent2.getRepeatCount());
        assertEquals(keyEvent.getMetaState(), keyEvent2.getMetaState());
        assertEquals(keyEvent.getDeviceId(), keyEvent2.getDeviceId());
        assertEquals(keyEvent.getScanCode(), keyEvent2.getScanCode());
        assertEquals(keyEvent.getFlags(), keyEvent2.getFlags());
        assertEquals(keyEvent.getSource(), keyEvent2.getSource());
        assertEquals(keyEvent.getDisplayId(), keyEvent2.getDisplayId());
        assertEquals(keyEvent.getCharacters(), keyEvent2.getCharacters());
    }

    @Test
    public void testObtainWithDisplayId() {
        final int displayId = 5;
        KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, displayId, CHARACTERS);
        assertEquals(DOWN_TIME, keyEvent.getDownTime());
        assertEquals(EVENT_TIME, keyEvent.getEventTime());
        assertEquals(ACTION, keyEvent.getAction());
        assertEquals(KEYCODE, keyEvent.getKeyCode());
        assertEquals(REPEAT, keyEvent.getRepeatCount());
        assertEquals(METASTATE, keyEvent.getMetaState());
        assertEquals(DEVICE_ID, keyEvent.getDeviceId());
        assertEquals(SCAN_CODE, keyEvent.getScanCode());
        assertEquals(FLAGS, keyEvent.getFlags());
        assertEquals(SOURCE, keyEvent.getSource());
        assertEquals(displayId, keyEvent.getDisplayId());
        assertEquals(CHARACTERS, keyEvent.getCharacters());
    }
}
