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

import junit.framework.Test;

/**
 * Set of tests that verify host side install cases
 */
public class PackageManagerHostTests extends DeviceTestCase {

    private static final String LOG_TAG = "PackageManagerHostTests";

    // TODO: get this value from Android Environment instead of hardcoding
    private static final String APP_PRIVATE_PATH = "/data/app-private/";
    private static final String DEVICE_APP_PATH = "/data/app/";
    private static final String SDCARD_APP_PATH = "/mnt/secure/asec/";

    private static final int MAX_WAIT_FOR_DEVICE_TIME = 120 * 1000;
    private static final int WAIT_FOR_DEVICE_POLL_TIME = 10 * 1000;

    // Various test files and their corresponding package names...

    // testPushAppPrivate constants
    // these constants must match values defined in test-apps/SimpleTestApp
    private static final String SIMPLE_APK = "SimpleTestApp.apk";
    private static final String SIMPLE_PKG = "com.android.framework.simpletestapp";

    // Apk with install location set to auto
    private static final String AUTO_LOC_APK = "AutoLocTestApp.apk";
    private static final String AUTO_LOC_PKG = "com.android.framework.autoloctestapp";
    // Apk with install location set to internalOnly
    private static final String INTERNAL_LOC_APK = "InternalLocTestApp.apk";
    private static final String INTERNAL_LOC_PKG = "com.android.framework.internalloctestapp";
    // Apk with install location set to preferExternal
    private static final String EXTERNAL_LOC_APK = "ExternalLocTestApp.apk";
    private static final String EXTERNAL_LOC_PKG = "com.android.framework.externalloctestapp";
    // Apk with no install location set
    private static final String NO_LOC_APK = "NoLocTestApp.apk";
    private static final String NO_LOC_PKG = "com.android.framework.noloctestapp";
    // Apk with 2 different versions - v1 is set to external, v2 has no location setting
    private static final String UPDATE_EXTERNAL_LOC_V1_EXT_APK
            = "UpdateExternalLocTestApp_v1_ext.apk";
    private static final String UPDATE_EXTERNAL_LOC_V2_NONE_APK
            = "UpdateExternalLocTestApp_v2_none.apk";
    private static final String UPDATE_EXTERNAL_LOC_PKG
            = "com.android.framework.updateexternalloctestapp";
    // Apk with 2 different versions - v1 is set to external, v2 is set to internalOnly
    private static final String UPDATE_EXT_TO_INT_LOC_V1_EXT_APK
            = "UpdateExtToIntLocTestApp_v1_ext.apk";
    private static final String UPDATE_EXT_TO_INT_LOC_V2_INT_APK
            = "UpdateExtToIntLocTestApp_v2_int.apk";
    private static final String UPDATE_EXT_TO_INT_LOC_PKG
            = "com.android.framework.updateexttointloctestapp";
    // Apks with the same package name, but install location set to
    // one of: Internal, External, Auto, or None
    private static final String VERSATILE_LOC_PKG = "com.android.framework.versatiletestapp";
    private static final String VERSATILE_LOC_INTERNAL_APK = "VersatileTestApp_Internal.apk";
    private static final String VERSATILE_LOC_EXTERNAL_APK = "VersatileTestApp_External.apk";
    private static final String VERSATILE_LOC_AUTO_APK = "VersatileTestApp_Auto.apk";
    private static final String VERSATILE_LOC_NONE_APK = "VersatileTestApp_None.apk";

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
     * Helper method to install a file to device
     * @param localFilePath the absolute file system path to file on local host to install
     * @param reinstall set to <code>true</code> if re-install of app should be performed
     * @throws IOException
     */
    private void installFile(final String localFilePath, final boolean replace)
            throws IOException {
        String result = getDevice().installPackage(localFilePath, replace);
        assertEquals(null, result);
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
     * Helper method to determine if file exists on the device containing a given string.
     *
     * @param destPath the
     * @return <code>true</code> if file exists containing given string,
     *         <code>false</code> otherwise.
     * @throws IOException if adb shell command failed
     */
    private boolean doesRemoteFileExistContainingString(String destPath, String searchString)
            throws IOException {
        String lsResult = executeShellCommand(String.format("ls %s",
                destPath));
        return lsResult.contains(searchString);
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
    private boolean doesAppExistOnSDCard(String packageName) throws IOException {
        return doesRemoteFileExistContainingString(SDCARD_APP_PATH, packageName);
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

    /**
     * Helper method for installing an app to wherever is specified in its manifest, and
     * then verifying the app was installed onto SD Card.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    void installAppAndVerifyExistsOnSDCard(String apkName, String pkgName, boolean overwrite)
            throws IOException, InterruptedException {
        // Start with a clean slate if we're not overwriting
        if (!overwrite) {
            // cleanup test app just in case it already exists
            getDevice().uninstallPackage(pkgName);
            // grep for package to make sure its not installed
            assertFalse(doesPackageExist(pkgName));
        }

        installFile(getTestAppFilePath(apkName), overwrite);
        assertTrue(doesAppExistOnSDCard(pkgName));
        assertFalse(doesAppExistOnDevice(pkgName));
        waitForDevice();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for installing an app to wherever is specified in its manifest, and
     * then verifying the app was installed onto device.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    void installAppAndVerifyExistsOnDevice(String apkName, String pkgName, boolean overwrite)
            throws IOException, InterruptedException {
        // Start with a clean slate if we're not overwriting
        if (!overwrite) {
            // cleanup test app just in case it already exists
            getDevice().uninstallPackage(pkgName);
            // grep for package to make sure its not installed
            assertFalse(doesPackageExist(pkgName));
        }

        installFile(getTestAppFilePath(apkName), overwrite);
        assertFalse(doesAppExistOnSDCard(pkgName));
        assertTrue(doesAppExistOnDevice(pkgName));
        waitForDevice();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for uninstalling an app.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    void uninstallApp(String pkgName) throws IOException, InterruptedException {
        getDevice().uninstallPackage(pkgName);
        // make sure its not installed anymore
        assertFalse(doesPackageExist(pkgName));
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=auto
     * will install the app to the device.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testInstallAppAutoLoc() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test an app with installLocation=auto gets installed on device");

        try {
            installAppAndVerifyExistsOnDevice(AUTO_LOC_APK, AUTO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            uninstallApp(AUTO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=internalOnly
     * will install the app to the device.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testInstallAppInternalLoc() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test an app with installLocation=internalOnly gets installed on device");

        try {
            installAppAndVerifyExistsOnDevice(INTERNAL_LOC_APK, INTERNAL_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            uninstallApp(INTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=preferExternal
     * will install the app to the SD card.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testInstallAppExternalLoc() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test an app with installLocation=preferExternal gets installed on SD Card");

        try {
            installAppAndVerifyExistsOnSDCard(EXTERNAL_LOC_APK, EXTERNAL_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            uninstallApp(EXTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that we can install an app onto the device,
     * uninstall it, and reinstall it onto the SD card.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    // TODO: This currently relies on the app's manifest to switch from device to
    // SD card install locations. We might want to make Device's installPackage()
    // accept a installLocation flag so we can install a package to the
    // destination of our choosing.
    public void testReinstallInternalToExternal() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test installing an app first to the device, then to the SD Card");

        try {
            installAppAndVerifyExistsOnDevice(VERSATILE_LOC_INTERNAL_APK, VERSATILE_LOC_PKG, false);
            uninstallApp(VERSATILE_LOC_PKG);
            installAppAndVerifyExistsOnSDCard(VERSATILE_LOC_EXTERNAL_APK, VERSATILE_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            uninstallApp(VERSATILE_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that we can install an app onto the SD Card,
     * uninstall it, and reinstall it onto the device.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    // TODO: This currently relies on the app's manifest to switch from device to
    // SD card install locations. We might want to make Device's installPackage()
    // accept a installLocation flag so we can install a package to the
    // destination of our choosing.
    public void testReinstallExternalToInternal() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test installing an app first to the SD Care, then to the device");

        try {
            // install the app externally
            installAppAndVerifyExistsOnSDCard(VERSATILE_LOC_EXTERNAL_APK, VERSATILE_LOC_PKG, false);
            uninstallApp(VERSATILE_LOC_PKG);
            // then replace the app with one marked for internalOnly
            installAppAndVerifyExistsOnDevice(VERSATILE_LOC_INTERNAL_APK, VERSATILE_LOC_PKG, true);
        }
        // cleanup test app
        finally {
            uninstallApp(VERSATILE_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that updating an app on the SD card will install
     * the update onto the SD card as well when location is not explicitly set in the
     * updated apps' manifest file.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testUpdateToSDCard() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test updating an app on the SD card stays on the SD card");

        try {
            // install the app externally
            installAppAndVerifyExistsOnSDCard(UPDATE_EXTERNAL_LOC_V1_EXT_APK,
                    UPDATE_EXTERNAL_LOC_PKG, false);
            // now replace the app with one where the location is blank (app should stay external)
            installAppAndVerifyExistsOnSDCard(UPDATE_EXTERNAL_LOC_V2_NONE_APK,
                    UPDATE_EXTERNAL_LOC_PKG, true);
        }
        // cleanup test app
        finally {
            uninstallApp(UPDATE_EXTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that updating an app on the SD card will install
     * the update onto the SD card as well when location is not explicitly set in the
     * updated apps' manifest file.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testUpdateSDCardToDevice() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "Test updating an app on the SD card to the Device through manifest change");

        try {
            // install the app externally
            installAppAndVerifyExistsOnSDCard(UPDATE_EXT_TO_INT_LOC_V1_EXT_APK,
                    UPDATE_EXT_TO_INT_LOC_PKG, false);
            // now replace the app with an update marked for internalOnly...
            installAppAndVerifyExistsOnDevice(UPDATE_EXT_TO_INT_LOC_V2_INT_APK,
                    UPDATE_EXT_TO_INT_LOC_PKG, true);
        }
        // cleanup test app
        finally {
            uninstallApp(UPDATE_EXT_TO_INT_LOC_PKG);
        }
    }

}
