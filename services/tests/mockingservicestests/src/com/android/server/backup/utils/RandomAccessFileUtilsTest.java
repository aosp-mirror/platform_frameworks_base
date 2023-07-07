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

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RandomAccessFileUtilsTest {
    private File mTemporaryFile;

    @Before
    public void setUp() throws Exception {
        mTemporaryFile = File.createTempFile("fileutilstest", ".txt");
    }

    @After
    public void tearDown() throws Exception {
        if (mTemporaryFile != null) {
            mTemporaryFile.delete();
        }
    }

    /**
     * Test that if we write true, we read back true.
     */
    @Test
    public void testWriteTrue_readReturnsTrue() {
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, true);

        assertThat(RandomAccessFileUtils.readBoolean(mTemporaryFile, false)).isEqualTo(true);
    }

    /**
     * Test that if we write false, we read back false.
     */
    @Test
    public void testWriteFalse_readReturnsFalse() {
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, false);

        assertThat(RandomAccessFileUtils.readBoolean(mTemporaryFile, true)).isEqualTo(false);
    }

    /**
     * Test that if we write true twice, we read back true.
     */
    @Test
    public void testWriteTrueTwice_readReturnsTrue() {
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, true);
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, true);

        assertThat(RandomAccessFileUtils.readBoolean(mTemporaryFile, false)).isEqualTo(true);
    }

    /**
     * Test that if we write false twice, we read back false.
     */
    @Test
    public void testWriteFalseTwice_readReturnsFalse() {
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, false);
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, false);

        assertThat(RandomAccessFileUtils.readBoolean(mTemporaryFile, true)).isEqualTo(false);
    }

    /**
     * Test that if we write true and then false, we read back false.
     */
    @Test
    public void testWriteTrueFalse_readReturnsFalse() {
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, true);
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, false);

        assertThat(RandomAccessFileUtils.readBoolean(mTemporaryFile, true)).isEqualTo(false);
    }

    /**
     * Test that if we write false and then true, we read back true.
     */
    @Test
    public void testWriteFalseTrue_readReturnsTrue() {
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, false);
        RandomAccessFileUtils.writeBoolean(mTemporaryFile, true);

        assertThat(RandomAccessFileUtils.readBoolean(mTemporaryFile, false)).isEqualTo(true);
    }
}
