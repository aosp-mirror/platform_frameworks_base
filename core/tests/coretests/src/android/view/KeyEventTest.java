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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class KeyEventTest {

    private static final int DOWN_TIME = 50;
    private static final long EVENT_TIME = 100;
    private static final long ANOTHER_EVENT_TIME = 108;
    private static final int ACTION = KeyEvent.ACTION_DOWN;
    private static final int ANOTHER_ACTION = KeyEvent.ACTION_UP;
    private static final int KEYCODE = KeyEvent.KEYCODE_0;
    private static final int REPEAT = 4;
    private static final int ANOTHER_REPEAT = 8;
    private static final int METASTATE = 15;
    private static final int DEVICE_ID = 16;
    private static final int SCAN_CODE = 23;
    private static final int FLAGS = 42;
    private static final int SOURCE = InputDevice.SOURCE_KEYBOARD;
    private static final String CHARACTERS = "CHARACTERS, Y U NO @NONNULL?";

    private static final int ID_SOURCE_MASK = 0x3 << 30;

    @Rule public final Expect expect = Expect.create();

    @Test
    public void testObtain() {
        KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, CHARACTERS);

        assertHasDefaultFields(keyEvent, INVALID_DISPLAY);
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

        assertHasDefaultFields(keyEvent, displayId);
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
            assertWithMessage("event IDs (event generated on round %s: %s)", i, keyEvent)
                    .that(set).doesNotContain(keyEvent.getId());
            set.add(keyEvent.getSequenceNumber());
        }
    }

    @Test
    public void testConstructorGeneratesUniqueId() {
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT);
            assertWithMessage("sequence numbers (event generated on round %s: %s)", i, keyEvent)
                    .that(set).doesNotContain(keyEvent.getSequenceNumber());
            set.add(keyEvent.getSequenceNumber());
        }
    }

    @Test
    public void testObtainGeneratesIdWithRightSource() {
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                    METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, CHARACTERS);
            assertThat((ID_SOURCE_MASK & keyEvent.getId())).isEqualTo((0x3 << 30));
        }
    }

    @Test
    public void testConstructorGeneratesIdWithRightSource() {
        for (int i = 0; i < 500; ++i) {
            KeyEvent keyEvent = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT);
            assertThat((ID_SOURCE_MASK & keyEvent.getId())).isEqualTo((0x3 << 30));
        }
    }

    @Test
    public void testParcelUnparcel() {
        KeyEvent key1 = createKey();
        KeyEvent key2;

        Parcel parcel = Parcel.obtain();
        try {
            key1.writeToParcel(parcel, /* flags= */ 0);
            parcel.setDataPosition(0);

            key2 = KeyEvent.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }

        compareKeys(key1, key2);
    }

    @Test
    public void testCopyConstructor() {
        KeyEvent key1 = createKey();
        KeyEvent key2 = new KeyEvent(key1);

        compareKeys(key1, key2);
    }

    @Test
    public void testCopyConstructorWith2ChangedFields() {
        KeyEvent key1 = createKey();
        @SuppressWarnings("deprecation")
        KeyEvent key2 = new KeyEvent(key1, ANOTHER_EVENT_TIME, ANOTHER_REPEAT);


        assertKeyEventFields(key2, DOWN_TIME, ANOTHER_EVENT_TIME, ACTION, KEYCODE, ANOTHER_REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, InputDevice.SOURCE_KEYBOARD,
                INVALID_DISPLAY, CHARACTERS);
        expect.withMessage("id (key1=%s, key2=%s", key1, key2).that(key2.getId())
                .isNotEqualTo(key1.getId());
    }

    @Test
    public void testConstructorWith10Args() {
        KeyEvent key = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, FLAGS, SOURCE);

        assertKeyEventFields(key, DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, FLAGS, SOURCE, INVALID_DISPLAY, /* characters= */ null);
    }

    @Test
    public void testConstructorWith9Args() {
        KeyEvent key = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, FLAGS);

        assertKeyEventFields(key, DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, FLAGS, /* source= */ 0, INVALID_DISPLAY,
                /* characters= */ null);
    }

    @Test
    public void testConstructorWith8Args() {
        KeyEvent key = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE);

        assertKeyEventFields(key, DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, /* flags= */ 0, /* source= */ 0, INVALID_DISPLAY,
                /* characters= */ null);
    }

    @Test
    public void testConstructorWith6Args() {
        KeyEvent key = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE);

        assertKeyEventFields(key, DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                /* deviceId= */ KeyCharacterMap.VIRTUAL_KEYBOARD, /* scanCode= */ 0, /* flags= */ 0,
                /* source= */ 0, INVALID_DISPLAY, /* characters= */ null);
    }

    @Test
    public void testConstructorWith5Args() {
        KeyEvent key = new KeyEvent(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT);

        assertKeyEventFields(key, DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT,
                /* metaState= */ 0, /* deviceId= */ KeyCharacterMap.VIRTUAL_KEYBOARD,
                /* scanCode= */ 0, /* flags= */ 0, /* source= */ 0, INVALID_DISPLAY,
                /* characters= */ null);
    }

    @Test
    public void testConstructorWith4Args() {
        KeyEvent key = new KeyEvent(42, CHARACTERS, DEVICE_ID, FLAGS);

        assertKeyEventFields(key, /* downTime= */ 42, /* eventTime= */ 42, KeyEvent.ACTION_MULTIPLE,
                KeyEvent.KEYCODE_UNKNOWN, /* repeat= */ 0, /* metaState= */ 0, DEVICE_ID,
                /* scanCode= */ 0, FLAGS, InputDevice.SOURCE_KEYBOARD, INVALID_DISPLAY, CHARACTERS);
    }

    @Test
    public void testConstructorWith2Args() {
        KeyEvent key = new KeyEvent(ACTION, KEYCODE);

        assertKeyEventFields(key, /* downTime= */ 0, /* eventTime= */ 0, ACTION, KEYCODE,
                /* repeat= */ 0, /* metaState= */ 0,
                /* deviceId= */ KeyCharacterMap.VIRTUAL_KEYBOARD, /* scanCode= */ 0, /* flags= */ 0,
                /* source= */ 0, INVALID_DISPLAY, /* characters= */ null);
    }

    @Test
    public void testCopyChangeAction() {
        KeyEvent key1 = createKey();
        KeyEvent key2 = KeyEvent.changeAction(key1, ANOTHER_ACTION);

        assertKeyEventFields(key2, DOWN_TIME, EVENT_TIME, ANOTHER_ACTION, KEYCODE, REPEAT,
                METASTATE, DEVICE_ID, SCAN_CODE, FLAGS, InputDevice.SOURCE_KEYBOARD,
                INVALID_DISPLAY, /* characters= */ null);
        expect.withMessage("id (key1=%s, key2=%s", key1, key2).that(key2.getId())
                .isNotEqualTo(key1.getId());
    }

    private static KeyEvent createKey() {
        return KeyEvent.obtain(DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE, DEVICE_ID,
                SCAN_CODE, FLAGS, SOURCE, INVALID_DISPLAY, CHARACTERS);
    }

    private void compareKeys(KeyEvent key1, KeyEvent key2) {
        expect.withMessage("id (key1=%s, key2=%s)", key1, key2)
                .that(key2.getId()).isEqualTo(key1.getId());
        expect.withMessage("downTime (key1=%s, key2=%s)", key1, key2)
                .that(key2.getDownTime()).isEqualTo(key1.getDownTime());
        expect.withMessage("eventTime (key1=%s, key2=%s)", key1, key2)
                .that(key2.getEventTime()).isEqualTo(key1.getEventTime());
        expect.withMessage("action (key1=%s, key2=%s)", key1, key2)
                .that(key2.getAction()).isEqualTo(key1.getAction());
        expect.withMessage("keyCode (key1=%s, key2=%s)", key1, key2)
                .that(key2.getKeyCode()).isEqualTo(key1.getKeyCode());
        expect.withMessage("repatCount (key1=%s, key2=%s)", key1, key2)
                .that(key2.getRepeatCount()).isEqualTo(key1.getRepeatCount());
        expect.withMessage("metaState (key1=%s, key2=%s)", key1, key2)
                .that(key2.getMetaState()).isEqualTo(key1.getMetaState());
        expect.withMessage("deviceId (key1=%s, key2=%s)", key1, key2)
                .that(key2.getDeviceId()).isEqualTo(key1.getDeviceId());
        expect.withMessage("scanCode (key1=%s, key2=%s)", key1, key2)
                .that(key2.getScanCode()).isEqualTo(key1.getScanCode());
        expect.withMessage("flags (key1=%s, key2=%s)", key1, key2)
                .that(key2.getFlags()).isEqualTo(key1.getFlags());
        expect.withMessage("source (key1=%s, key2=%s)", key1, key2)
                .that(key2.getSource()).isEqualTo(key1.getSource());
        expect.withMessage("displayId (key1=%s, key2=%s)", key1, key2)
                .that(key2.getDisplayId()).isEqualTo(key1.getDisplayId());
        expect.withMessage("characters (key1=%s, key2=%s)", key1, key2)
                .that(key2.getCharacters()).isEqualTo(key1.getCharacters());
    }

    private void assertHasDefaultFields(KeyEvent keyEvent, int displayId) {
        assertKeyEventFields(keyEvent, DOWN_TIME, EVENT_TIME, ACTION, KEYCODE, REPEAT, METASTATE,
                DEVICE_ID, SCAN_CODE, FLAGS, InputDevice.SOURCE_KEYBOARD, displayId, CHARACTERS);
    }

    private void assertKeyEventFields(KeyEvent keyEvent, long downTime, long eventTime,
            int action, int keyCode, int repeat, int metaState, int deviceId, int scanCode,
            int flags, int source, int displayId, String characters) {
        expect.withMessage("downTime on %s", keyEvent)
                .that(keyEvent.getDownTime()).isEqualTo(downTime);
        expect.withMessage("eventTime on %s", keyEvent)
                .that(keyEvent.getEventTime()).isEqualTo(eventTime);
        expect.withMessage("action on %s", keyEvent)
                .that(keyEvent.getAction()).isEqualTo(action);
        expect.withMessage("keyCode on %s", keyEvent)
                .that(keyEvent.getKeyCode()).isEqualTo(keyCode);
        expect.withMessage("repeatCount on %s", keyEvent)
                .that(keyEvent.getRepeatCount()).isEqualTo(repeat);
        expect.withMessage("metaState on %s", keyEvent)
                .that(keyEvent.getMetaState()).isEqualTo(metaState);
        expect.withMessage("deviceId on %s", keyEvent)
                .that(keyEvent.getDeviceId()).isEqualTo(deviceId);
        expect.withMessage("scanCode on %s", keyEvent)
                .that(keyEvent.getScanCode()).isEqualTo(scanCode);
        expect.withMessage("flags on %s", keyEvent)
                .that(keyEvent.getFlags()).isEqualTo(flags);
        expect.withMessage("source on %s", keyEvent)
                .that(keyEvent.getSource()).isEqualTo(source);
        expect.withMessage("displayId on %s", keyEvent)
                .that(keyEvent.getDisplayId()).isEqualTo(displayId);
        expect.withMessage("characters on %s", keyEvent)
                .that(keyEvent.getCharacters()).isEqualTo(characters);
    }
}
