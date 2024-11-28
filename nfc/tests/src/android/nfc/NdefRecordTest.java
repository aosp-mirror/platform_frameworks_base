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
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NdefRecordTest {

    @Test
    public void testNdefRecordConstructor() throws FormatException {
        NdefRecord applicationRecord = NdefRecord
                .createApplicationRecord("com.android.test");
        NdefRecord ndefRecord = new NdefRecord(applicationRecord.toByteArray());
        assertThat(ndefRecord).isNotNull();
        assertThat(ndefRecord.toByteArray().length).isGreaterThan(0);
        assertThat(ndefRecord.getType()).isEqualTo("android.com:pkg".getBytes());
        assertThat(ndefRecord.getPayload()).isEqualTo("com.android.test".getBytes());
    }

    @Test
    public void testCreateExternal() {
        NdefRecord ndefRecord = NdefRecord.createExternal("test",
                "android.com:pkg", "com.android.test".getBytes());
        assertThat(ndefRecord).isNotNull();
        assertThat(ndefRecord.getType()).isEqualTo("test:android.com:pkg".getBytes());
        assertThat(ndefRecord.getPayload()).isEqualTo("com.android.test".getBytes());
    }

    @Test
    public void testCreateUri() {
        NdefRecord ndefRecord = NdefRecord.createUri("http://www.example.com");
        assertThat(ndefRecord).isNotNull();
        assertThat(ndefRecord.getTnf()).isEqualTo(NdefRecord.TNF_WELL_KNOWN);
        assertThat(ndefRecord.getType()).isEqualTo(NdefRecord.RTD_URI);
    }

}
