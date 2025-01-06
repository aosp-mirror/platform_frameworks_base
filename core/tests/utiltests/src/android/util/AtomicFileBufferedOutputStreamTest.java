/*
 * Copyright 2024 The Android Open Source Project
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

package android.util;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class AtomicFileBufferedOutputStreamTest {
    private static final String BASE_NAME = "base";
    private File mBaseFile;

    @Before
    public void setUp() throws Exception {
        File mDirectory = Files.createTempDirectory("AtomicFile").toFile();
        mBaseFile = new File(mDirectory, BASE_NAME);
    }

    @After
    public void deleteFiles() {
        mBaseFile.delete();
    }

    @Test
    public void testAtomicFileBufferedWrite() {
        AtomicFile atomicFile = new AtomicFile(mBaseFile);
        try (var oStream = new AtomicFileBufferedOutputStream(atomicFile)) {
            oStream.write(0);
            oStream.write(new byte[]{1, 2});
            oStream.write(new byte[]{1, 2, 3}, /*off=*/ 2, /*len=*/ 1);
            oStream.markSuccess();
        } catch (IOException e) {
            // Should never happen
            throw new RuntimeException(e);
        }

        try {
            assertThat(atomicFile.readFully()).isEqualTo(new byte[]{0, 1, 2, 3});
        } catch (IOException e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }


    @Test(expected = FileNotFoundException.class)
    public void testAtomicFileBufferedNotFinishedWriting() throws FileNotFoundException {
        AtomicFile atomicFile = new AtomicFile(mBaseFile);
        try (var oStream = new AtomicFileBufferedOutputStream(atomicFile)) {
            oStream.write(0);
            oStream.write(new byte[]{1, 2});
            oStream.write(new byte[]{1, 2, 3}, /*off=*/ 2, /*len=*/ 1);
        } catch (IOException e) {
            // Should never happen
            throw new RuntimeException(e);
        }

        try {
            // openRead should throw FileNotFoundException.
            // close should never be called, but added here just in case openRead never throws.
            atomicFile.openRead().close();
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }
}
