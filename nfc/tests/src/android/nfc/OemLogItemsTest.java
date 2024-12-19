/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.nfc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.time.Instant;

@RunWith(JUnit4.class)
public final class OemLogItemsTest {

    @Test
    public void testGetAction() {
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_RF_FIELD_STATE_CHANGED)
                .build();
        assertEquals(OemLogItems.LOG_ACTION_RF_FIELD_STATE_CHANGED, item.getAction());
    }

    @Test
    public void testGetEvent() {
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_NFC_TOGGLE)
                .setCallingEvent(OemLogItems.EVENT_ENABLE)
                .build();
        assertEquals(OemLogItems.EVENT_ENABLE, item.getEvent());
    }

    @Test
    public void testGetCallingPid() {
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_NFC_TOGGLE)
                .setCallingPid(1234)
                .build();
        assertEquals(1234, item.getCallingPid());
    }

    @Test
    public void testGetCommandApdu() {
        byte[] commandApdu = {0x01, 0x02, 0x03};
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_HCE_DATA)
                .setApduCommand(commandApdu)
                .build();
        assertArrayEquals(commandApdu, item.getCommandApdu());
    }

    @Test
    public void testGetResponseApdu() {
        byte[] responseApdu = {0x04, 0x05, 0x06};
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_HCE_DATA)
                .setApduResponse(responseApdu)
                .build();
        assertArrayEquals(responseApdu, item.getResponseApdu());
    }

    @Test
    public void testGetRfFieldEventTimeMillis() {
        Instant expectedTime = Instant.ofEpochSecond(1688768000, 123456789);
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_RF_FIELD_STATE_CHANGED)
                .setRfFieldOnTime(expectedTime)
                .build();
        assertEquals(expectedTime, item.getRfFieldEventTimeMillis());
    }

    @Test
    public void testGetTag() {
        Tag mockTag = mock(Tag.class);
        OemLogItems item = new OemLogItems.Builder(OemLogItems.LOG_ACTION_TAG_DETECTED)
                .setTag(mockTag)
                .build();
        assertEquals(mockTag, item.getTag());
    }
}
