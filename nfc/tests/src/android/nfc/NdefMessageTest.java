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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NdefMessageTest {
    private NdefMessage mNdefMessage;
    private NdefRecord mNdefRecord;

    @Before
    public void setUp() {
        mNdefRecord = NdefRecord.createUri("http://www.example.com");
        mNdefMessage = new NdefMessage(mNdefRecord);
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testGetRecords() {
        NdefRecord[] records = mNdefMessage.getRecords();
        assertThat(records).isNotNull();
        assertThat(records).hasLength(1);
        assertThat(records[0]).isEqualTo(mNdefRecord);
    }

    @Test
    public void testToByteArray() throws FormatException {
        byte[] bytes = mNdefMessage.toByteArray();
        assertThat(bytes).isNotNull();
        assertThat(bytes.length).isGreaterThan(0);
        NdefMessage ndefMessage = new NdefMessage(bytes);
        assertThat(ndefMessage).isNotNull();
    }
}
