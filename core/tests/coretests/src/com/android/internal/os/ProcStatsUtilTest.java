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

package com.android.internal.os;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.FileUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ProcStatsUtilTest {

    private File mProcDirectory;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getContext();
        mProcDirectory = context.getDir("proc", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mProcDirectory);
    }

    @Test
    public void testReadNullSeparatedFile_empty() throws IOException {
        assertEquals(
                "",
                runReadNullSeparatedFile(""));
    }

    @Test
    public void testReadNullSeparatedFile_simple() throws IOException {
        assertEquals(
                "abc def ghi",
                runReadNullSeparatedFile("abc\0def\0ghi"));
    }

    @Test
    public void testReadNullSeparatedFile_trailingNulls() throws IOException {
        assertEquals(
                "abc",
                runReadNullSeparatedFile("abc\0\0\0\0"));
    }

    @Test
    public void testReadNullSeparatedFile_doubleNullEnds() throws IOException {
        assertEquals(
                "abc",
                runReadNullSeparatedFile("abc\0\0def"));
    }

    @Test
    public void testReadSingleLineProcFile_simple() throws IOException {
        assertEquals(
                "abc",
                runReadSingleLineProcFile("abc"));
    }

    @Test
    public void testReadSingleLineProcFile_empty() throws IOException {
        assertEquals(
                "",
                runReadSingleLineProcFile(""));
    }

    @Test
    public void testReadSingleLineProcFile_newLine() throws IOException {
        assertEquals(
                "abc",
                runReadSingleLineProcFile("abc\ndef"));
    }

    @Test
    public void testReadSingleLineProcFile_doubleNewLine() throws IOException {
        assertEquals(
                "abc",
                runReadSingleLineProcFile("abc\n\ndef"));
    }

    @Test
    public void testReadTerminatedProcFile_simple() throws IOException {
        assertEquals(
                "abc",
                runReadTerminatedProcFile("abc\0", (byte) '\0'));
    }

    @Test
    public void testReadTerminatedProcFile_withExtra() throws IOException {
        assertEquals(
                "123",
                runReadTerminatedProcFile("123\0extra", (byte) '\0'));
    }

    @Test
    public void testReadTerminatedProcFile_noTerminator() throws IOException {
        assertEquals(
                "noterm",
                runReadTerminatedProcFile("noterm", (byte) '\0'));
    }

    @Test
    public void testReadTerminatedProcFile_newLineTerm() throws IOException {
        assertEquals(
                "123",
                runReadTerminatedProcFile("123\n456", (byte) '\n'));
    }

    @Test
    public void testReadTerminatedProcFile_normalCharTerm() throws IOException {
        assertEquals(
                "abc",
                runReadTerminatedProcFile("abcdef", (byte) 'd'));
    }

    @Test
    public void testReadTerminatedProcFile_largeUnterminated() throws IOException {
        String longString = new String(new char[10000]).replace('\0', 'a');
        assertEquals(
                longString,
                runReadTerminatedProcFile(longString, (byte) '\0'));
    }

    @Test
    public void testReadTerminatedProcFile_largeTerminated() throws IOException {
        String longString = new String(new char[10000]).replace('\0', 'a');
        assertEquals(
                longString,
                runReadTerminatedProcFile(longString + "\0", (byte) '\0'));
    }

    @Test
    public void testReadTerminatedProcFile_largeExtra() throws IOException {
        String longString = new String(new char[10000]).replace('\0', 'a');
        assertEquals(
                longString,
                runReadTerminatedProcFile(longString + "\0abc", (byte) '\0'));
    }

    private String runReadNullSeparatedFile(String fileContents) throws IOException {
        File tempFile = File.createTempFile("null-separated-file", null, mProcDirectory);
        Files.write(tempFile.toPath(), fileContents.getBytes());
        String result = ProcStatsUtil.readNullSeparatedFile(tempFile.toString());
        Files.delete(tempFile.toPath());
        return result;
    }

    private String runReadSingleLineProcFile(String fileContents) throws IOException {
        File tempFile = File.createTempFile("single-line-proc-file", null, mProcDirectory);
        Files.write(tempFile.toPath(), fileContents.getBytes());
        String result = ProcStatsUtil.readSingleLineProcFile(tempFile.toString());
        Files.delete(tempFile.toPath());
        return result;
    }

    private String runReadTerminatedProcFile(
            String fileContents, byte terminator) throws IOException {
        File tempFile = File.createTempFile("terminated-proc-file", null, mProcDirectory);
        Files.write(tempFile.toPath(), fileContents.getBytes());
        String result = ProcStatsUtil.readTerminatedProcFile(tempFile.toString(), terminator);
        Files.delete(tempFile.toPath());
        return result;
    }
}
