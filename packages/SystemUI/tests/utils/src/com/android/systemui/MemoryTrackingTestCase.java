/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui;

import android.os.Debug;
import android.util.Log;

import org.junit.AfterClass;
import org.junit.Before;

import java.io.File;
import java.io.IOException;

/**
 * Convenience class for grabbing a heap dump after a test class is run.
 *
 * To use:
 * - locally edit your test class to inherit from MemoryTrackingTestCase instead of SysuiTestCase
 * - Watch the logcat with tag MEMORY to see the path to the .ahprof file
 * - adb pull /path/to/something.ahprof
 * - Download ahat from https://sites.google.com/corp/google.com/ahat/home
 * - java -jar ~/Downloads/ahat-1.7.2.jar something.hprof
 * - Watch output for next steps
 * - Profit and fix leaks!
 */
public class MemoryTrackingTestCase extends SysuiTestCase {
    private static File sFilesDir = null;
    private static String sLatestTestClassName = null;

    @Before public void grabFilesDir() {
        if (sFilesDir == null) {
            sFilesDir = mContext.getFilesDir();
        }
        sLatestTestClassName = getClass().getName();
    }

    @AfterClass
    public static void dumpHeap() throws IOException {
        if (sFilesDir == null) {
            Log.e("MEMORY", "Somehow no test cases??");
            return;
        }
        mockitoTearDown();
        Log.w("MEMORY", "about to dump heap");
        File path = new File(sFilesDir, sLatestTestClassName + ".ahprof");
        Debug.dumpHprofData(path.getPath());
        Log.w("MEMORY", "did it!  Location: " + path);
    }
}
