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
    private static int sHeapCount = 0;
    private static File sLatestBaselineHeapFile = null;

    // Ideally, we would do this in @BeforeClass just once, but we need mContext to get the files
    // dir, and that does not exist until @Before on each test method.
    @Before
    public void grabFilesDir() throws IOException {
        // This should happen only once per suite
        if (sFilesDir == null) {
            sFilesDir = mContext.getFilesDir();
        }

        // This will happen before the first test method in each class
        if (sLatestTestClassName == null) {
            sLatestTestClassName = getClass().getName();
            sLatestBaselineHeapFile = dump("baseline" + (++sHeapCount), "before-test");
        }
    }

    @AfterClass
    public static void dumpHeap() throws IOException {
        File afterTestHeap = dump(sLatestTestClassName, "after-test");
        if (sLatestBaselineHeapFile != null && afterTestHeap != null) {
            Log.w("MEMORY", "To compare heap to baseline (use go/ahat):");
            Log.w("MEMORY", "  adb pull " + sLatestBaselineHeapFile);
            Log.w("MEMORY", "  adb pull " + afterTestHeap);
            Log.w("MEMORY",
                    "  java -jar ahat.jar --baseline " + sLatestBaselineHeapFile.getName() + " "
                            + afterTestHeap.getName());
        }
        sLatestTestClassName = null;
    }

    private static File dump(String basename, String heapKind) throws IOException {
        if (sFilesDir == null) {
            Log.e("MEMORY", "Somehow no test cases??");
            return null;
        }
        mockitoTearDown();
        Log.w("MEMORY", "about to dump " + heapKind + " heap");
        File path = new File(sFilesDir, basename + ".ahprof");
        Debug.dumpHprofData(path.getPath());
        Log.w("MEMORY", "Success!  Location: " + path);
        return path;
    }
}
