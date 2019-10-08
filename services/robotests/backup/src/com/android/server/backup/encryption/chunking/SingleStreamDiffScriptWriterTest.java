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

package com.android.server.backup.encryption.chunking;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Tests for {@link SingleStreamDiffScriptWriter}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class SingleStreamDiffScriptWriterTest {
    private static final int MAX_CHUNK_SIZE_IN_BYTES = 256;
    /** By default this Locale does not use Arabic numbers for %d formatting. */
    private static final Locale HINDI = new Locale("hi", "IN");

    private Locale mDefaultLocale;
    private ByteArrayOutputStream mOutputStream;
    private SingleStreamDiffScriptWriter mDiffScriptWriter;

    @Before
    public void setUp() {
        mDefaultLocale = Locale.getDefault();
        mOutputStream = new ByteArrayOutputStream();
        mDiffScriptWriter =
                new SingleStreamDiffScriptWriter(mOutputStream, MAX_CHUNK_SIZE_IN_BYTES);
    }

    @After
    public void tearDown() {
        Locale.setDefault(mDefaultLocale);
    }

    @Test
    public void writeChunk_withNegativeStart_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mDiffScriptWriter.writeChunk(-1, 50));
    }

    @Test
    public void writeChunk_withZeroLength_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mDiffScriptWriter.writeChunk(0, 0));
    }

    @Test
    public void writeChunk_withExistingBytesInBuffer_writesBufferFirst()
            throws IOException {
        String testString = "abcd";
        writeStringAsBytesToWriter(testString, mDiffScriptWriter);

        mDiffScriptWriter.writeChunk(0, 20);
        mDiffScriptWriter.flush();

        // Expected format: length of abcd, newline, abcd, newline, chunk start - chunk end
        assertThat(mOutputStream.toString("UTF-8")).isEqualTo(
                String.format("%d\n%s\n%d-%d\n", testString.length(), testString, 0, 19));
    }

    @Test
    public void writeChunk_overlappingPreviousChunk_combinesChunks() throws IOException {
        mDiffScriptWriter.writeChunk(3, 4);

        mDiffScriptWriter.writeChunk(7, 5);
        mDiffScriptWriter.flush();

        assertThat(mOutputStream.toString("UTF-8")).isEqualTo(String.format("3-11\n"));
    }

    @Test
    public void writeChunk_formatsByteIndexesUsingArabicNumbers() throws Exception {
        Locale.setDefault(HINDI);

        mDiffScriptWriter.writeChunk(0, 12345);
        mDiffScriptWriter.flush();

        assertThat(mOutputStream.toString("UTF-8")).isEqualTo("0-12344\n");
    }

    @Test
    public void flush_flushesOutputStream() throws IOException {
        ByteArrayOutputStream mockOutputStream = mock(ByteArrayOutputStream.class);
        SingleStreamDiffScriptWriter diffScriptWriter =
                new SingleStreamDiffScriptWriter(mockOutputStream, MAX_CHUNK_SIZE_IN_BYTES);

        diffScriptWriter.flush();

        verify(mockOutputStream).flush();
    }

    private void writeStringAsBytesToWriter(String string, SingleStreamDiffScriptWriter writer)
            throws IOException {
        byte[] bytes = string.getBytes("UTF-8");
        for (int i = 0; i < bytes.length; i++) {
            writer.writeByte(bytes[i]);
        }
    }
}
