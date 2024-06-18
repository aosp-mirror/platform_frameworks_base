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

package android.os;

import static org.junit.Assert.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public class StrictModeTest {
    private File mFile;

    @Before
    public void setUp() throws Exception {
        mFile = File.createTempFile("StrictModeTest", "tmp");
    }

    @Test
    public void testAllowThreadDiskReadsMask() throws Exception {
        final int mask = StrictMode.allowThreadDiskReadsMask();
        try {
            mFile.exists();
        } finally {
            StrictMode.setThreadPolicyMask(mask);
        }
    }

    @Test
    public void testAllowThreadDiskWritesMask() throws Exception {
        final int mask = StrictMode.allowThreadDiskReadsMask();
        try {
            mFile.delete();
        } finally {
            StrictMode.setThreadPolicyMask(mask);
        }
    }

    @Test
    public void testThreadMask() throws Exception {
        StrictMode.setThreadPolicyMask(0);
        assertEquals(0, StrictMode.getThreadPolicyMask());
    }
}
