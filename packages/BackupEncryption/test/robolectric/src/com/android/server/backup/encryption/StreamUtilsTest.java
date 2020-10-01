/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@RunWith(RobolectricTestRunner.class)
public class StreamUtilsTest {
    private static final int SOURCE_DATA_SIZE = 64;

    private byte[] mSourceData;

    private InputStream mSource;
    private ByteArrayOutputStream mDestination;

    @Before
    public void setUp() {
        mSourceData = new byte[SOURCE_DATA_SIZE];
        for (byte i = 0; i < SOURCE_DATA_SIZE; i++) {
            mSourceData[i] = i;
        }
        mSource = new ByteArrayInputStream(mSourceData);
        mDestination = new ByteArrayOutputStream();
    }

    @Test
    public void copyStream_copiesAllBytesIfAsked() throws IOException {
        StreamUtils.copyStream(mSource, mDestination, mSourceData.length);
        assertOutputHasBytes(mSourceData.length);
    }

    @Test
    public void copyStream_stopsShortIfAsked() throws IOException {
        StreamUtils.copyStream(mSource, mDestination, mSourceData.length - 10);
        assertOutputHasBytes(mSourceData.length - 10);
    }

    @Test
    public void copyStream_stopsShortIfAskedToCopyMoreThanAvailable() throws IOException {
        StreamUtils.copyStream(mSource, mDestination, mSourceData.length + 10);
        assertOutputHasBytes(mSourceData.length);
    }

    private void assertOutputHasBytes(int count) {
        byte[] output = mDestination.toByteArray();
        assertThat(output.length).isEqualTo(count);
        for (int i = 0; i < count; i++) {
            assertThat(output[i]).isEqualTo(mSourceData[i]);
        }
    }
}
