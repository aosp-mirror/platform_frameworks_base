/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.integrity.model;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;

@RunWith(JUnit4.class)
public class ByteTrackedOutputStreamTest {

    @Test
    public void testConstructorStartsWithZeroBytesWritten() {
        ByteTrackedOutputStream byteTrackedOutputStream =
                new ByteTrackedOutputStream(new ByteArrayOutputStream());

        assertThat(byteTrackedOutputStream.getWrittenBytesCount()).isEqualTo(0);
    }

    @Test
    public void testSuccessfulWriteAndValidateWrittenBytesCount_directFromByteArray()
            throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteTrackedOutputStream byteTrackedOutputStream = new ByteTrackedOutputStream(outputStream);

        byte[] outputContent = "This is going to be outputed for tests.".getBytes();
        byteTrackedOutputStream.write(outputContent);

        assertThat(byteTrackedOutputStream.getWrittenBytesCount()).isEqualTo(outputContent.length);
        assertThat(outputStream.toByteArray().length).isEqualTo(outputContent.length);
    }

    @Test
    public void testSuccessfulWriteAndValidateWrittenBytesCount_fromBitStream() throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteTrackedOutputStream byteTrackedOutputStream = new ByteTrackedOutputStream(outputStream);

        BitOutputStream bitOutputStream = new BitOutputStream(byteTrackedOutputStream);
        bitOutputStream.setNext(/* numOfBits= */5, /* value= */1);
        bitOutputStream.flush();

        // Even though we wrote 5 bits, this will complete to 1 byte.
        assertThat(byteTrackedOutputStream.getWrittenBytesCount()).isEqualTo(1);

        // Add a bit less than 2 bytes (10 bits).
        bitOutputStream.setNext(/* numOfBits= */10, /* value= */1);
        bitOutputStream.flush();
        assertThat(byteTrackedOutputStream.getWrittenBytesCount()).isEqualTo(3);

        assertThat(outputStream.toByteArray().length).isEqualTo(3);
    }
}
