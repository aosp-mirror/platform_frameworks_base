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

package com.android.server.backup.utils;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.io.Files;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class FileUtilsTest {
    private static File sTemporaryDir;
    private File mTemporaryFile;

    @AfterClass
    public static void tearDownClass() {
        if (sTemporaryDir != null) {
            sTemporaryDir.delete();
        }
    }

    @Before
    public void setUp() throws Exception {
        if (sTemporaryDir != null) {
            sTemporaryDir.delete();
        }
        sTemporaryDir = Files.createTempDir();
        mTemporaryFile = new File(sTemporaryDir, "fileutilstest.txt");
    }

    /** Test that if file does not exist, {@link FileUtils#createNewFile()} creates the file. */
    @Test
    public void testEnsureFileExists_fileDoesNotAlreadyExist_getsCreated() {
        assertThat(mTemporaryFile.exists()).isFalse();

        FileUtils.createNewFile(mTemporaryFile);

        assertThat(mTemporaryFile.exists()).isTrue();
    }

    /** Test that if file does exist, {@link FileUtils#createNewFile()} does not error out. */
    @Test
    public void testEnsureFileExists_fileAlreadyExists_doesNotErrorOut() throws IOException {
        mTemporaryFile.createNewFile();

        FileUtils.createNewFile(mTemporaryFile);

        assertThat(mTemporaryFile.exists()).isTrue();
    }
}
