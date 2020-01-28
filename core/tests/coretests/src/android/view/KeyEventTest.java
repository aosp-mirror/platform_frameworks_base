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

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

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
    private static final byte[] HMAC = null;
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
        compareKeys(keyEvent, keyEvent2);
    }

    @Test
    public void testObtainWithDisplayId() {
        final int displayId = 5;
        KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, displayId, null /* hmac*/,
                CHARACTERS);
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

    @Test
    public void testParcelUnparcel() {
        KeyEvent key1 = createKey();
        Parcel parcel = Parcel.obtain();
        key1.writeToParcel(parcel, 0 /*flags*/);
        parcel.setDataPosition(0);

        KeyEvent key2 = KeyEvent.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        compareKeys(key1, key2);
    }

    @Test
    public void testConstructor() {
        KeyEvent key1 = createKey();
        KeyEvent key2 = new KeyEvent(key1);
        compareKeys(key1, key2);
    }

    private static KeyEvent createKey() {
        return KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, INVALID_DISPLAY, HMAC, CHARACTERS);
    }

    private static void compareKeys(KeyEvent key1, KeyEvent key2) {
        assertEquals(key1.getDownTime(), key2.getDownTime());
        assertEquals(key1.getEventTime(), key2.getEventTime());
        assertEquals(key1.getAction(), key2.getAction());
        assertEquals(key1.getKeyCode(), key2.getKeyCode());
        assertEquals(key1.getRepeatCount(), key2.getRepeatCount());
        assertEquals(key1.getMetaState(), key2.getMetaState());
        assertEquals(key1.getDeviceId(), key2.getDeviceId());
        assertEquals(key1.getScanCode(), key2.getScanCode());
        assertEquals(key1.getFlags(), key2.getFlags());
        assertEquals(key1.getSource(), key2.getSource());
        assertEquals(key1.getDisplayId(), key2.getDisplayId());
        assertEquals(key1.getCharacters(), key2.getCharacters());
    }
}
