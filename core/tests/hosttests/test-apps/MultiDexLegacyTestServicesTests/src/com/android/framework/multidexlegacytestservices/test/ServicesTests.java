/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.framework.multidexlegacytestservices.test;

import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.io.File;

/**
 * Run the tests with: <code>adb shell am instrument -w
 com.android.framework.multidexlegacytestservices.test/android.test.InstrumentationTestRunner
</code>
 */
public class ServicesTests extends InstrumentationTestCase {
    private static final String SERVICE_BASE_ACTION =
            "com.android.framework.multidexlegacytestservices.action.Service";
    private static final int MIN_SERVICE = 1;
    private static final int MAX_SERVICE = 19;


    public void testStressConcurentFirstLaunch() {
        File targetFilesDir = getInstrumentation().getTargetContext().getFilesDir();
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            File resultFile = new File(targetFilesDir, "Service" + i);
            resultFile.delete();
            assertFalse("Failed to delete result file '" + resultFile.getAbsolutePath() + "'.",
                    resultFile.exists());
            File completeFile = new File(targetFilesDir, "Service" + i + ".complete");
            completeFile.delete();
            assertFalse("Failed to delete completion file '" + completeFile.getAbsolutePath() +
                    "'.", completeFile.exists());
       }
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            getInstrumentation().getContext().startService(new Intent(SERVICE_BASE_ACTION + i));
            try {
                Thread.sleep((i - 1) * (1 << (i / 5)));
            } catch (InterruptedException e) {
            }
       }


        Log.i("ServicesTests", "start sleeping");
        int attempt = 0;
        int maxAttempt = 50; // 10 is enough for a nexus S
        do {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
            }
            attempt ++;
            if (attempt >= maxAttempt) {
                fail();
            }
        } while (!areAllServicesRunning(targetFilesDir));

        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            File resultFile = new File(targetFilesDir, "Service" + i);
            assertTrue("Service" + i + " never completed.", resultFile.isFile());
            assertEquals("Service" + i + " was restarted.", 8L, resultFile.length());
        }
    }

    private static boolean areAllServicesRunning(File tagetFilesDir) {
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            File completeFile = new File(tagetFilesDir, "Service" + i + ".complete");
            if (!completeFile.exists()) {
                return false;
            }
        }
        return true;

    }
}
