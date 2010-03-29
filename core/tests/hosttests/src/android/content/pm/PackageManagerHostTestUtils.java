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

import com.android.ddmlib.IDevice;
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

import junit.framework.Assert;
import com.android.hosttest.DeviceTestCase;

/**
 * Set of tests that verify host side install cases
 */
public class PackageManagerHostTestUtils extends Assert {

    private static final String LOG_TAG = "PackageManagerHostTests";
    private IDevice mDevice = null;

    // TODO: get this value from Android Environment instead of hardcoding
    private static final String APP_PRIVATE_PATH = "/data/app-private/";
    private static final String DEVICE_APP_PATH = "/data/app/";
    private static final String SDCARD_APP_PATH = "/mnt/secure/asec/";

    private static final int MAX_WAIT_FOR_DEVICE_TIME = 120 * 1000;
    private static final int WAIT_FOR_DEVICE_POLL_TIME = 10 * 1000;

    /**
     * Constructor takes the device to use
     * @param the device to use when performing operations
     */
    public PackageManagerHostTestUtils(IDevice device)
    {
          mDevice = device;
    }

    /**
     * Disable default constructor
     */
    private PackageManagerHostTestUtils() {}

    /**
     * Returns the path on the device of forward-locked apps.
     *
     * @return path of forward-locked apps on the device
     */
    public static String getAppPrivatePath() {
        return APP_PRIVATE_PATH;
    }

    /**
     * Returns the path on the device of normal apps.
     *
     * @return path of forward-locked apps on the device
     */
    public static String getDeviceAppPath() {
        return DEVICE_APP_PATH;
    }

    /**
     * Returns the path of apps installed on the SD card.
     *
     * @return path of forward-locked apps on the device
     */
    public static String getSDCardAppPath() {
        return SDCARD_APP_PATH;
    }

    /**
     * Helper method to push a file to device
     * @param apkAppPrivatePath
     * @throws IOException
     */
    public void pushFile(final String localFilePath, final String destFilePath)
            throws IOException {
        SyncResult result = mDevice.getSyncService().pushFile(
                localFilePath, destFilePath, new NullSyncProgressMonitor());
        assertEquals(SyncService.RESULT_OK, result.getCode());
    }

    /**
     * Helper method to install a file to device
     * @param localFilePath the absolute file system path to file on local host to install
     * @param reinstall set to <code>true</code> if re-install of app should be performed
     * @throws IOException
     */
    public void installFile(final String localFilePath, final boolean replace)
            throws IOException {
        String result = mDevice.installPackage(localFilePath, replace);
        assertEquals(null, result);
    }

    /**
     * Helper method to determine if file on device exists.
     *
     * @param destPath the absolute path of file on device to check
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws IOException if adb shell command failed
     */
    public boolean doesRemoteFileExist(String destPath) throws IOException {
        String lsGrep = executeShellCommand(String.format("ls %s", destPath));
        return !lsGrep.contains("No such file or directory");
    }

    /**
     * Helper method to determine if file exists on the device containing a given string.
     *
     * @param destPath the
     * @return <code>true</code> if file exists containing given string,
     *         <code>false</code> otherwise.
     * @throws IOException if adb shell command failed
     */
    public boolean doesRemoteFileExistContainingString(String destPath, String searchString)
            throws IOException {
        String lsResult = executeShellCommand(String.format("ls %s", destPath));
        return lsResult.contains(searchString);
    }

    /**
     * Helper method to determine if package on device exists.
     *
     * @param packageName the Android manifest package to check.
     * @return <code>true</code> if package exists, <code>false</code> otherwise
     * @throws IOException if adb shell command failed
     */
    public boolean doesPackageExist(String packageName) throws IOException {
        String pkgGrep = executeShellCommand(String.format("pm path %s", packageName));
        return pkgGrep.contains("package:");
    }

    /**
     * Helper method to determine if app was installed on device.
     *
     * @param packageName package name to check for
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws IOException if adb shell command failed
     */
    private boolean doesAppExistOnDevice(String packageName) throws IOException {
        return doesRemoteFileExistContainingString(DEVICE_APP_PATH, packageName);
    }

    /**
     * Helper method to determine if app was installed on SD card.
     *
     * @param packageName package name to check for
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws IOException if adb shell command failed
     */
    public boolean doesAppExistOnSDCard(String packageName) throws IOException {
        return doesRemoteFileExistContainingString(SDCARD_APP_PATH, packageName);
    }

    /**
     * Waits for device's package manager to respond.
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public void waitForDevice() throws InterruptedException, IOException {
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
     * @return the output of the command
     * @throws IOException
     */
    public String executeShellCommand(String command) throws IOException {
        Log.d(LOG_TAG, String.format("adb shell %s", command));
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        mDevice.executeShellCommand(command, receiver);
        String output = receiver.getOutput();
        Log.d(LOG_TAG, String.format("Result: %s", output));
        return output;
    }

    /**
     * A {@link IShellOutputReceiver} which collects the whole shell output into one {@link String}
     */
    private class CollectingOutputReceiver extends MultiLineReceiver {

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

    private class NullSyncProgressMonitor implements ISyncProgressMonitor {
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

    /**
     * Helper method for installing an app to wherever is specified in its manifest, and
     * then verifying the app was installed onto SD Card.
     *
     * @param the path of the apk to install
     * @param the name of the package
     * @param <code>true</code> if the app should be overwritten, <code>false</code> otherwise
     * @throws IOException if adb shell command failed
     * @throws InterruptedException if the thread was interrupted
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void installAppAndVerifyExistsOnSDCard(String apkPath, String pkgName, boolean overwrite)
            throws IOException, InterruptedException {
        // Start with a clean slate if we're not overwriting
        if (!overwrite) {
            // cleanup test app just in case it already exists
            mDevice.uninstallPackage(pkgName);
            // grep for package to make sure its not installed
            assertFalse(doesPackageExist(pkgName));
        }

        installFile(apkPath, overwrite);
        assertTrue(doesAppExistOnSDCard(pkgName));
        assertFalse(doesAppExistOnDevice(pkgName));
        waitForDevice();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for installing an app to wherever is specified in its manifest, and
     * then verifying the app was installed onto device.
     *
     * @param the path of the apk to install
     * @param the name of the package
     * @param <code>true</code> if the app should be overwritten, <code>false</code> otherwise
     * @throws IOException if adb shell command failed
     * @throws InterruptedException if the thread was interrupted
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void installAppAndVerifyExistsOnDevice(String apkPath, String pkgName, boolean overwrite)
            throws IOException, InterruptedException {
        // Start with a clean slate if we're not overwriting
        if (!overwrite) {
            // cleanup test app just in case it already exists
            mDevice.uninstallPackage(pkgName);
            // grep for package to make sure its not installed
            assertFalse(doesPackageExist(pkgName));
        }

        installFile(apkPath, overwrite);
        assertFalse(doesAppExistOnSDCard(pkgName));
        assertTrue(doesAppExistOnDevice(pkgName));
        waitForDevice();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for uninstalling an app.
     *
     * @param pkgName package name to uninstall
     * @throws IOException if adb shell command failed
     * @throws InterruptedException if the thread was interrupted
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void uninstallApp(String pkgName) throws IOException, InterruptedException {
        mDevice.uninstallPackage(pkgName);
        // make sure its not installed anymore
        assertFalse(doesPackageExist(pkgName));
    }

}
