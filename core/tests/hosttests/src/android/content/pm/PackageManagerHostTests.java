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

package android.content.pm;

import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.Log;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.SyncService.ISyncProgressMonitor;
import com.android.ddmlib.SyncService.SyncResult;
import com.android.hosttest.DeviceTestCase;
import com.android.hosttest.DeviceTestSuite;

import java.io.File;
import java.io.IOException;

import junit.framework.Test;

/**
 * Set of tests that verify host side install cases
 */
public class PackageManagerHostTests extends DeviceTestCase {

    // testPushAppPrivate constants
    // these constants must match values defined in test-apps/SimpleTestApp
    private static final String SIMPLE_APK = "SimpleTestApp.apk";
    private static final String SIMPLE_PKG = "com.android.framework.simpletestapp";
    // TODO: get this value from Android Environment instead of hardcoding
    private static final String APP_PRIVATE_PATH = "/data/app-private/";

    private static final String LOG_TAG = "PackageManagerHostTests";

    private static final int MAX_WAIT_FOR_DEVICE_TIME = 120 * 1000;
    private static final int WAIT_FOR_DEVICE_POLL_TIME = 10 * 1000;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // ensure apk path has been set before test is run
        assertNotNull(getTestAppPath());
    }

    /**
     * Regression test to verify that pushing an apk to the private app directory doesn't install
     * the app, and otherwise cause the system to blow up.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testPushAppPrivate() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "testing pushing an apk to /data/app-private");
        final String apkAppPrivatePath =  APP_PRIVATE_PATH + SIMPLE_APK;

        // cleanup test app just in case it was accidently installed
        getDevice().uninstallPackage(SIMPLE_PKG);
        executeShellCommand("stop");
        pushFile(getTestAppFilePath(SIMPLE_APK), apkAppPrivatePath);
        // sanity check to make sure file is there
        assertTrue(doesRemoteFileExist(apkAppPrivatePath));
        executeShellCommand("start");

        waitForDevice();

        // grep for package to make sure its not installed
        assertFalse(doesPackageExist(SIMPLE_PKG));
        // ensure it has been deleted from app-private
        assertFalse(doesRemoteFileExist(apkAppPrivatePath));
    }

    /**
     * Helper method to push a file to device
     * @param apkAppPrivatePath
     * @throws IOException
     */
    private void pushFile(final String localFilePath, final String destFilePath)
            throws IOException {
        SyncResult result = getDevice().getSyncService().pushFile(
                localFilePath, destFilePath,
                new NullSyncProgressMonitor());
        assertEquals(SyncService.RESULT_OK, result.getCode());
    }

    /**
     * Helper method to determine if file on device exists.
     *
     * @param destPath the absolute path of file on device to check
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws IOException if adb shell command failed
     */
    private boolean doesRemoteFileExist(String destPath) throws IOException {
        String lsGrep = executeShellCommand(String.format("ls %s",
                destPath));
        return !lsGrep.contains("No such file or directory");
    }

    /**
     * Helper method to determine if package on device exists.
     *
     * @param packageName the Android manifest package to check.
     * @return <code>true</code> if package exists, <code>false</code> otherwise
     * @throws IOException if adb shell command failed
     */
    private boolean doesPackageExist(String packageName) throws IOException {
        String pkgGrep = executeShellCommand(String.format("pm path %s",
                packageName));
        return pkgGrep.contains("package:");
    }

    /**
     * Waits for device's package manager to respond.
     *
     * @throws InterruptedException
     * @throws IOException
     */
    private void waitForDevice() throws InterruptedException, IOException {
        Log.i(LOG_TAG, "waiting for device");
        int currentWaitTime = 0;
        // poll the package manager until it returns something for android
        while (!doesPackageExist("android")) {
            Thread.sleep(WAIT_FOR_DEVICE_POLL_TIME);
            currentWaitTime += WAIT_FOR_DEVICE_POLL_TIME;
            if (currentWaitTime > MAX_WAIT_FOR_DEVICE_TIME) {
                Log.e(LOG_TAG, "time out waiting for device");
                throw new InterruptedException();
            }
        }
    }

    /**
     * Helper method which executes a adb shell command and returns output as a {@link String}
     * @return
     * @throws IOException
     */
    private String executeShellCommand(String command) throws IOException {
        Log.d(LOG_TAG, String.format("adb shell %s", command));
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        getDevice().executeShellCommand(command, receiver);
        String output = receiver.getOutput();
        Log.d(LOG_TAG, String.format("Result: %s", output));
        return output;
    }

    /**
     * Get the absolute file system location of test app with given filename
     * @param fileName the file name of the test app apk
     * @return {@link String} of absolute file path
     */
    private String getTestAppFilePath(String fileName) {
        return String.format("%s%s%s", getTestAppPath(), File.separator, fileName);
    }

    public static Test suite() {
        return new DeviceTestSuite(PackageManagerHostTests.class);
    }

    /**
     * A {@link IShellOutputReceiver} which collects the whole shell output into one {@link String}
     */
    private static class CollectingOutputReceiver extends MultiLineReceiver {

        private StringBuffer mOutputBuffer = new StringBuffer();

        public String getOutput() {
            return mOutputBuffer.toString();
        }

        @Override
        public void processNewLines(String[] lines) {
            for (String line: lines) {
                mOutputBuffer.append(line);
                mOutputBuffer.append("\n");
            }
        }

        public boolean isCancelled() {
            return false;
        }
    }

    private static class NullSyncProgressMonitor implements ISyncProgressMonitor {
        public void advance(int work) {
            // ignore
        }

        public boolean isCanceled() {
            // ignore
            return false;
        }

        public void start(int totalWork) {
            // ignore

        }

        public void startSubTask(String name) {
            // ignore
        }

        public void stop() {
            // ignore
        }
    }
}
