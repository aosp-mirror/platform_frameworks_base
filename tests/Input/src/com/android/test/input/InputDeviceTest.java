/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputDeviceTest {
    private static final float DELTA = 0.01f;
    private static final int DEVICE_ID = 1000;

    private void assertMotionRangeEquals(InputDevice.MotionRange range,
            InputDevice.MotionRange outRange) {
        assertEquals(range.getAxis(), outRange.getAxis());
        assertEquals(range.getSource(), outRange.getSource());
        assertEquals(range.getMin(), outRange.getMin(), DELTA);
        assertEquals(range.getMax(), outRange.getMax(), DELTA);
        assertEquals(range.getFlat(), outRange.getFlat(), DELTA);
        assertEquals(range.getFuzz(), outRange.getFuzz(), DELTA);
        assertEquals(range.getResolution(), outRange.getResolution(), DELTA);
    }

    private void assertDeviceEquals(InputDevice device, InputDevice outDevice) {
        assertEquals(device.getId(), outDevice.getId());
        assertEquals(device.getGeneration(), outDevice.getGeneration());
        assertEquals(device.getControllerNumber(), outDevice.getControllerNumber());
        assertEquals(device.getName(), outDevice.getName());
        assertEquals(device.getVendorId(), outDevice.getVendorId());
        assertEquals(device.getProductId(), outDevice.getProductId());
        assertEquals(device.getDescriptor(), outDevice.getDescriptor());
        assertEquals(device.isExternal(), outDevice.isExternal());
        assertEquals(device.getSources(), outDevice.getSources());
        assertEquals(device.getKeyboardType(), outDevice.getKeyboardType());
        assertEquals(device.getKeyboardLanguageTag(), outDevice.getKeyboardLanguageTag());
        assertEquals(device.getKeyboardLayoutType(), outDevice.getKeyboardLayoutType());
        assertEquals(device.getMotionRanges().size(), outDevice.getMotionRanges().size());

        KeyCharacterMap keyCharacterMap = device.getKeyCharacterMap();
        KeyCharacterMap outKeyCharacterMap = outDevice.getKeyCharacterMap();
        assertEquals("keyCharacterMap not equal", keyCharacterMap, outKeyCharacterMap);

        for (int j = 0; j < device.getMotionRanges().size(); j++) {
            assertMotionRangeEquals(device.getMotionRanges().get(j),
                    outDevice.getMotionRanges().get(j));
        }
    }

    private void assertInputDeviceParcelUnparcel(KeyCharacterMap keyCharacterMap) {
        final InputDevice.Builder deviceBuilder = new InputDevice.Builder()
                .setId(DEVICE_ID)
                .setGeneration(42)
                .setControllerNumber(43)
                .setName("Test Device " + DEVICE_ID)
                .setVendorId(44)
                .setProductId(45)
                .setDescriptor("descriptor")
                .setExternal(true)
                .setSources(InputDevice.SOURCE_HDMI)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)
                .setKeyCharacterMap(keyCharacterMap)
                .setHasVibrator(true)
                .setHasMicrophone(true)
                .setHasButtonUnderPad(true)
                .setHasSensor(true)
                .setHasBattery(true)
                .setKeyboardLanguageTag("en-US")
                .setKeyboardLayoutType("qwerty")
                .setSupportsUsi(true);

        for (int i = 0; i < 30; i++) {
            deviceBuilder.addMotionRange(
                    MotionEvent.AXIS_GENERIC_1,
                    InputDevice.SOURCE_UNKNOWN,
                    i,
                    i + 1,
                    i + 2,
                    i + 3,
                    i + 4);
        }

        final InputDevice device = deviceBuilder.build();

        Parcel parcel = Parcel.obtain();
        device.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        InputDevice outDevice = InputDevice.CREATOR.createFromParcel(parcel);
        assertDeviceEquals(device, outDevice);
    }

    @Test
    public void testParcelUnparcelInputDevice_VirtualCharacterMap() {
        final KeyCharacterMap keyCharacterMap =
                KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        assertInputDeviceParcelUnparcel(keyCharacterMap);
    }

    @Test
    public void testParcelUnparcelInputDevice_EmptyCharacterMap() {
        final KeyCharacterMap keyCharacterMap = KeyCharacterMap.obtainEmptyMap(DEVICE_ID);
        assertInputDeviceParcelUnparcel(keyCharacterMap);
    }
}
