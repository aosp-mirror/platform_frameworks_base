/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.storage;

import android.test.AndroidTestCase;
import com.android.server.storage.FileCollector.MeasurementResult;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.PrintStream;

import static com.google.common.truth.Truth.assertThat;

@RunWith(JUnit4.class)
public class FileCollectorTest extends AndroidTestCase {
    @Rule
    public TemporaryFolder temporaryFolder;

    @Before
    public void setUp() throws Exception {
        temporaryFolder = new TemporaryFolder();
        temporaryFolder.create();
    }

    @Test
    public void testEmpty() throws Exception {
        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());
        assertThat(result.totalAccountedSize()).isEqualTo(0L);
    }

    @Test
    public void testImageFile() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test.jpg"), "1234");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.imagesSize).isEqualTo(4);
    }

    @Test
    public void testVideoFile() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test.mp4"), "1234");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.videosSize).isEqualTo(4);
    }

    @Test
    public void testAudioFile() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test.mp3"), "1234");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.audioSize).isEqualTo(4);
    }

    @Test
    public void testMiscFile() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test"), "1234");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.miscSize).isEqualTo(4);
    }

    @Test
    public void testNestedFile() throws Exception {
        File directory = temporaryFolder.newFolder();
        writeDataToFile(new File(directory, "test"), "1234");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.miscSize).isEqualTo(4);
    }

    @Test
    public void testMultipleFiles() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test"), "1234");
        writeDataToFile(temporaryFolder.newFile("test2"), "12345");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.miscSize).isEqualTo(9);
    }

    @Test
    public void testTotalSize() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test.jpg"), "1");
        writeDataToFile(temporaryFolder.newFile("test.mp3"), "1");
        writeDataToFile(temporaryFolder.newFile("test.mp4"), "1");
        writeDataToFile(temporaryFolder.newFile("test"), "1");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.totalAccountedSize()).isEqualTo(4);
    }

    @Test
    public void testFileEndsWithPeriod() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test."), "1");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.miscSize).isEqualTo(1);
        assertThat(result.totalAccountedSize()).isEqualTo(1);
    }

    public void testIgnoreFileExtensionCase() throws Exception {
        writeDataToFile(temporaryFolder.newFile("test.JpG"), "1234");

        MeasurementResult result = FileCollector.getMeasurementResult(temporaryFolder.getRoot());

        assertThat(result.imagesSize).isEqualTo(4);
    }

    private void writeDataToFile(File f, String data) throws Exception{
        PrintStream out = new PrintStream(f);
        out.print(data);
        out.close();
    }
}
