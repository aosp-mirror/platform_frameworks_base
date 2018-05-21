/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.framework.multidexlegacytestservices.test2;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.TimeoutException;
import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Run the tests with: <code>adb shell am instrument -w
 * com.android.framework.multidexlegacytestservices.test2/android.support.test.runner.AndroidJUnitRunner
 * </code>
 */
@RunWith(AndroidJUnit4.class)
public class ServicesTests {
    private static final String TAG = "ServicesTests";

    static {
        Log.i(TAG, "Initializing");
    }

    private class ExtensionFilter implements FileFilter {
        private final String ext;

        public ExtensionFilter(String ext) {
            this.ext = ext;
        }

        @Override
        public boolean accept(File file) {
            return file.getName().endsWith(ext);
        }
    }

    private class ExtractedZipFilter extends ExtensionFilter {
        public  ExtractedZipFilter() {
            super(".zip");
        }

        @Override
        public boolean accept(File file) {
            return super.accept(file) && !file.getName().startsWith("tmp-");
        }
    }

    private static final int ENDHDR = 22;

    private static final String SERVICE_BASE_ACTION =
            "com.android.framework.multidexlegacytestservices.action.Service";
    private static final int MIN_SERVICE = 1;
    private static final int MAX_SERVICE = 19;
    private static final String COMPLETION_SUCCESS = "Success";

    private File targetFilesDir;

    @Before
    public void setup() throws Exception {
        Log.i(TAG, "setup");
        killServices();

        File applicationDataDir =
                new File(InstrumentationRegistry.getTargetContext().getApplicationInfo().dataDir);
        clearDirContent(applicationDataDir);
        targetFilesDir = InstrumentationRegistry.getTargetContext().getFilesDir();

        Log.i(TAG, "setup done");
    }

    @Test
    public void testStressConcurentLaunch() throws Exception {
        startServices();
        waitServicesCompletion();
        String completionStatus = getServicesCompletionStatus();
        if (completionStatus != COMPLETION_SUCCESS) {
            Assert.fail(completionStatus);
        }
    }

    @Test
    public void testRecoverFromZipCorruption() throws Exception {
        int serviceId = 1;
        // Ensure extraction.
        initServicesWorkFiles();
        startService(serviceId);
        waitServicesCompletion(serviceId);

        // Corruption of the extracted zips.
        tamperAllExtractedZips();

        killServices();
        checkRecover();
    }

    @Test
    public void testRecoverFromDexCorruption() throws Exception {
        int serviceId = 1;
        // Ensure extraction.
        initServicesWorkFiles();
        startService(serviceId);
        waitServicesCompletion(serviceId);

        // Corruption of the odex files.
        tamperAllOdex();

        killServices();
        checkRecover();
    }

    @Test
    public void testRecoverFromZipCorruptionStressTest() throws Exception {
        Thread startServices =
                new Thread() {
            @Override
            public void run() {
                startServices();
            }
        };

        startServices.start();

        // Start services lasts more than 80s, lets cause a few corruptions during this interval.
        for (int i = 0; i < 80; i++) {
            Thread.sleep(1000);
            tamperAllExtractedZips();
        }
        startServices.join();
        try {
            waitServicesCompletion();
        } catch (TimeoutException e) {
            // Can happen.
        }

        killServices();
        checkRecover();
    }

    @Test
    public void testRecoverFromDexCorruptionStressTest() throws Exception {
        Thread startServices =
                new Thread() {
            @Override
            public void run() {
                startServices();
            }
        };

        startServices.start();

        // Start services lasts more than 80s, lets cause a few corruptions during this interval.
        for (int i = 0; i < 80; i++) {
            Thread.sleep(1000);
            tamperAllOdex();
        }
        startServices.join();
        try {
            waitServicesCompletion();
        } catch (TimeoutException e) {
            // Will probably happen most of the time considering what we're doing...
        }

        killServices();
        checkRecover();
    }

    private static void clearDirContent(File dir) {
        for (File subElement : dir.listFiles()) {
            if (subElement.isDirectory()) {
                clearDirContent(subElement);
            }
            if (!subElement.delete()) {
                throw new AssertionError("Failed to clear '" + subElement.getAbsolutePath() + "'");
            }
        }
    }

    private void startServices() {
        Log.i(TAG, "start services");
        initServicesWorkFiles();
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            startService(i);
            try {
                Thread.sleep((i - 1) * (1 << (i / 5)));
            } catch (InterruptedException e) {
            }
        }
    }

    private void startService(int serviceId) {
        Log.i(TAG, "start service " + serviceId);
        InstrumentationRegistry.getContext().startService(new Intent(SERVICE_BASE_ACTION + serviceId));
    }

    private void initServicesWorkFiles() {
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            File resultFile = new File(targetFilesDir, "Service" + i);
            resultFile.delete();
            Assert.assertFalse(
                    "Failed to delete result file '" + resultFile.getAbsolutePath() + "'.",
                    resultFile.exists());
            File completeFile = new File(targetFilesDir, "Service" + i + ".complete");
            completeFile.delete();
            Assert.assertFalse(
                    "Failed to delete completion file '" + completeFile.getAbsolutePath() + "'.",
                    completeFile.exists());
        }
    }

    private void waitServicesCompletion() throws TimeoutException {
        Log.i(TAG, "start sleeping");
        int attempt = 0;
        int maxAttempt = 50; // 10 is enough for a nexus S
        do {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
            }
            attempt++;
            if (attempt >= maxAttempt) {
                throw new TimeoutException();
            }
        } while (!areAllServicesCompleted());
    }

    private void waitServicesCompletion(int serviceId) throws TimeoutException {
        Log.i(TAG, "start sleeping");
        int attempt = 0;
        int maxAttempt = 50; // 10 is enough for a nexus S
        do {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
            }
            attempt++;
            if (attempt >= maxAttempt) {
                throw new TimeoutException();
            }
        } while (isServiceRunning(serviceId));
    }

    private String getServicesCompletionStatus() {
        String status = COMPLETION_SUCCESS;
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            File resultFile = new File(targetFilesDir, "Service" + i);
            if (!resultFile.isFile()) {
                status = "Service" + i + " never completed.";
                break;
            }
            if (resultFile.length() != 8) {
                status = "Service" + i + " was restarted.";
                break;
            }
        }
        Log.i(TAG, "Services completion status: " + status);
        return status;
    }

    private String getServiceCompletionStatus(int serviceId) {
        String status = COMPLETION_SUCCESS;
        File resultFile = new File(targetFilesDir, "Service" + serviceId);
        if (!resultFile.isFile()) {
            status = "Service" + serviceId + " never completed.";
        } else if (resultFile.length() != 8) {
            status = "Service" + serviceId + " was restarted.";
        }
        Log.i(TAG, "Service " + serviceId + " completion status: " + status);
        return status;
    }

    private boolean areAllServicesCompleted() {
        for (int i = MIN_SERVICE; i <= MAX_SERVICE; i++) {
            if (isServiceRunning(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean isServiceRunning(int i) {
        File completeFile = new File(targetFilesDir, "Service" + i + ".complete");
        return !completeFile.exists();
    }

    private File getSecondaryFolder() {
        File dir =
                new File(
                        new File(
                                InstrumentationRegistry.getTargetContext().getApplicationInfo().dataDir,
                                "code_cache"),
                        "secondary-dexes");
        Assert.assertTrue(dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    private void tamperAllExtractedZips() throws IOException {
        // First attempt was to just overwrite zip entries but keep central directory, this was no
        // trouble for Dalvik that was just ignoring those zip and using the odex files.
        Log.i(TAG, "Tamper extracted zip files by overwriting all content by '\\0's.");
        byte[] zeros = new byte[4 * 1024];
        // Do not tamper tmp zip during their extraction.
        for (File zip : getSecondaryFolder().listFiles(new ExtractedZipFilter())) {
            long fileLength = zip.length();
            Assert.assertTrue(fileLength > ENDHDR);
            zip.setWritable(true);
            RandomAccessFile raf = new RandomAccessFile(zip, "rw");
            try {
                int index = 0;
                while (index < fileLength) {
                    int length = (int) Math.min(zeros.length, fileLength - index);
                    raf.write(zeros, 0, length);
                    index += length;
                }
            } finally {
                raf.close();
            }
        }
    }

    private void tamperAllOdex() throws IOException {
        Log.i(TAG, "Tamper odex files by overwriting some content by '\\0's.");
        byte[] zeros = new byte[4 * 1024];
        // I think max size would be 40 (u1[8] + 8 u4) but it's a test so lets take big margins.
        int savedSizeForOdexHeader = 80;
        for (File odex : getSecondaryFolder().listFiles(new ExtensionFilter(".dex"))) {
            long fileLength = odex.length();
            Assert.assertTrue(fileLength > zeros.length + savedSizeForOdexHeader);
            odex.setWritable(true);
            RandomAccessFile raf = new RandomAccessFile(odex, "rw");
            try {
                raf.seek(savedSizeForOdexHeader);
                raf.write(zeros, 0, zeros.length);
            } finally {
                raf.close();
            }
        }
    }

    private void checkRecover() throws TimeoutException {
        Log.i(TAG, "Check recover capability");
        int serviceId = 1;
        // Start one service and check it was able to run correctly even if a previous run failed.
        initServicesWorkFiles();
        startService(serviceId);
        waitServicesCompletion(serviceId);
        String completionStatus = getServiceCompletionStatus(serviceId);
        if (completionStatus != COMPLETION_SUCCESS) {
            Assert.fail(completionStatus);
        }
    }

    private void killServices() {
        ((ActivityManager)
                InstrumentationRegistry.getContext().getSystemService(Context.ACTIVITY_SERVICE))
        .killBackgroundProcesses("com.android.framework.multidexlegacytestservices");
    }
}
