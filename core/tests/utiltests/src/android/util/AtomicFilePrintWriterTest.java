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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class AtomicFilePrintWriterTest {
    private static final String BASE_NAME = "base";
    private static final String HELLO_WORLD_STRING = "你好世界！ ";
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
    public void testAtomicFileWrite() {
        AtomicFile atomicFile = new AtomicFile(mBaseFile);
        try (var pw = new AtomicFilePrintWriter(atomicFile, StandardCharsets.UTF_8)) {
            pw.write(HELLO_WORLD_STRING);
            pw.write(HELLO_WORLD_STRING, /*off=*/ 0, /*len=*/ HELLO_WORLD_STRING.length() - 1);
            pw.markSuccess();
        } catch (IOException e) {
            // Should never happen
            throw new RuntimeException(e);
        }

        try {
            assertThat(new String(atomicFile.readFully(), StandardCharsets.UTF_8))
                    .isEqualTo("你好世界！ 你好世界！");
        } catch (IOException e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }

    @Test(expected = FileNotFoundException.class)
    public void testAtomicFileWriteNotFinished() throws FileNotFoundException {
        AtomicFile atomicFile = new AtomicFile(mBaseFile);
        try (var pw = new AtomicFilePrintWriter(atomicFile, StandardCharsets.UTF_8)) {
            pw.write(HELLO_WORLD_STRING);
            pw.write(HELLO_WORLD_STRING, /*off=*/ 0, /*len=*/ HELLO_WORLD_STRING.length() - 1);
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
