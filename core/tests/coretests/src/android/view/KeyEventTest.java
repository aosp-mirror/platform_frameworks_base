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
import static org.junit.Assert.assertFalse;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

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

    private static final int ID_SOURCE_MASK = 0x3 << 30;

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

    /**
     * Tests that it can generate 500 consecutive distinct numbers. This is a non-deterministic test
     * but with 30 bits randomness the failure rate is roughly 4.52e-5, which is negligible enough.
     * Probability formula: N * (N - 1) * ... * (N - n + 1) / N^n, where N = 2^30 and n = 500 for
     * this test.
     */
    @Test
    public void testObtainGeneratesUniqueId() {
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                    METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, CHARACTERS);
            assertFalse("Found duplicate ID in round " + i,
                    set.contains(keyEvent.getId()));
            set.add(keyEvent.getSequenceNumber());
        }
    }

    @Test
    public void testConstructorGeneratesUniqueId() {
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT);
            assertFalse("Found duplicate sequence number in round " + i,
                    set.contains(keyEvent.getId()));
            set.add(keyEvent.getSequenceNumber());
        }
    }

    @Test
    public void testObtainGeneratesIdWithRightSource() {
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                    METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, CHARACTERS);
            assertEquals(0x3 << 30, ID_SOURCE_MASK & keyEvent.getId());
        }
    }

    @Test
    public void testConstructorGeneratesIdWithRightSource() {
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT);
            assertEquals(0x3 << 30, ID_SOURCE_MASK & keyEvent.getId());
        }
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
        return KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, INVALID_DISPLAY, CHARACTERS);
    }

    private static void compareKeys(KeyEvent key1, KeyEvent key2) {
        assertEquals(key1.getId(), key2.getId());
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
