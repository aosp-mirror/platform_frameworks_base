/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.os.FileUtils;
import android.test.AndroidTestCase;

import java.io.File;

/**
 * Tests for {@link com.android.server.EntropyMixer}
 */
public class EntropyMixerTest extends AndroidTestCase {

    public void testInitialWrite() throws Exception {
        File dir = getContext().getDir("testInitialWrite", Context.MODE_PRIVATE);
        File file = File.createTempFile("testInitialWrite", "dat", dir);
        file.deleteOnExit();
        assertEquals(0, FileUtils.readTextFile(file, 0, null).length());

        // The constructor has the side effect of writing to file
        new EntropyMixer(getContext(), "/dev/null", file.getCanonicalPath(), "/dev/null");

        assertTrue(FileUtils.readTextFile(file, 0, null).length() > 0);
    }
}
