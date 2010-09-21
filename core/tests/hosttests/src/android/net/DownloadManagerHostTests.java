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

package android.net;

import android.content.pm.PackageManagerHostTestUtils;

import com.android.ddmlib.Log;
import com.android.hosttest.DeviceTestCase;
import com.android.hosttest.DeviceTestSuite;

import java.io.File;
import java.util.Hashtable;

import junit.framework.Test;

/**
 * Host-based tests of the DownloadManager API. (Uses a device-based app to actually invoke the
 * various tests.)
 */
public class DownloadManagerHostTests extends DeviceTestCase {
    protected PackageManagerHostTestUtils mPMUtils = null;

    private static final String LOG_TAG = "android.net.DownloadManagerHostTests";
    private static final String FILE_DOWNLOAD_APK = "DownloadManagerTestApp.apk";
    private static final String FILE_DOWNLOAD_PKG = "com.android.frameworks.downloadmanagertests";
    private static final String FILE_DOWNLOAD_CLASS =
            "com.android.frameworks.downloadmanagertests.DownloadManagerTestApp";
    private static final String DOWNLOAD_TEST_RUNNER_NAME =
            "com.android.frameworks.downloadmanagertests.DownloadManagerTestRunner";

    // Extra parameters to pass to the TestRunner
    private static final String EXTERNAL_DOWNLOAD_URI_KEY = "external_download_uri";
    // Note: External environment variable ANDROID_TEST_EXTERNAL_URI must be set to point to the
    // external URI under which the files downloaded by the tests can be found. Note that the Uri
    // must be accessible by the device during a test run. Correspondingly,
    // ANDROID_TEST_EXTERNAL_LARGE_URI should point to the external URI of the folder containing
    // large files.
    private static String externalDownloadUriValue = null;

    Hashtable<String, String> mExtraParams = null;

    public static Test suite() {
        return new DeviceTestSuite(DownloadManagerHostTests.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // ensure apk path has been set before test is run
        assertNotNull(getTestAppPath());
        mPMUtils = new PackageManagerHostTestUtils(getDevice());
        externalDownloadUriValue = System.getenv("ANDROID_TEST_EXTERNAL_URI");
        assertNotNull(externalDownloadUriValue);
        mExtraParams = getExtraParams();
    }

    /**
     * Helper function to get extra params that can be used to pass into the helper app.
     */
    protected Hashtable<String, String> getExtraParams() {
        Hashtable<String, String> extraParams = new Hashtable<String, String>();
        extraParams.put(EXTERNAL_DOWNLOAD_URI_KEY, externalDownloadUriValue);
        return extraParams;
    }

    /**
     * Tests that a large download over WiFi
     * @throws Exception if the test failed at any point
     */
    public void testLargeDownloadOverWiFi() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "runLargeDownloadOverWiFi", DOWNLOAD_TEST_RUNNER_NAME,
                mExtraParams);

        assertTrue("Failed to install large file over WiFi in < 10 minutes!", testPassed);
    }

    /**
     * Spawns a device-based function to initiate a download on the device, reboots the device,
     * then waits and verifies the download succeeded.
     *
     * @throws Exception if the test failed at any point
     */
    public void testDownloadManagerSingleReboot() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "initiateDownload", DOWNLOAD_TEST_RUNNER_NAME,
                mExtraParams);

        assertTrue("Failed to initiate download properly!", testPassed);
        mPMUtils.rebootDevice();
        testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "verifyFileDownloadSucceeded", DOWNLOAD_TEST_RUNNER_NAME,
                mExtraParams);
        assertTrue("Failed to verify initiated download completed properyly!", testPassed);
    }

    /**
     * Spawns a device-based function to initiate a download on the device, reboots the device three
     * times (using different intervals), then waits and verifies the download succeeded.
     *
     * @throws Exception if the test failed at any point
     */
    public void testDownloadManagerMultipleReboots() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "initiateDownload", DOWNLOAD_TEST_RUNNER_NAME,
                mExtraParams);

        assertTrue("Failed to initiate download properly!", testPassed);
        Thread.sleep(5000);

        // Do 3 random reboots - after 13, 9, and 19 seconds
        Log.i(LOG_TAG, "First reboot...");
        mPMUtils.rebootDevice();
        Thread.sleep(13000);
        Log.i(LOG_TAG, "Second reboot...");
        mPMUtils.rebootDevice();
        Thread.sleep(9000);
        Log.i(LOG_TAG, "Third reboot...");
        mPMUtils.rebootDevice();
        Thread.sleep(19000);
        testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "verifyFileDownloadSucceeded", DOWNLOAD_TEST_RUNNER_NAME,
                mExtraParams);
        assertTrue("Failed to verify initiated download completed properyly!", testPassed);
    }

    /**
     * Spawns a device-based function to test download while WiFi is enabled/disabled multiple times
     * during the download.
     *
     * @throws Exception if the test failed at any point
     */
    public void testDownloadMultipleWiFiEnableDisable() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "runDownloadMultipleWiFiEnableDisable",
                DOWNLOAD_TEST_RUNNER_NAME, mExtraParams);
        assertTrue(testPassed);
    }

    /**
     * Spawns a device-based function to test switching on/off both airplane mode and WiFi
     *
     * @throws Exception if the test failed at any point
     */
    public void testDownloadMultipleSwitching() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "runDownloadMultipleSwitching",
                DOWNLOAD_TEST_RUNNER_NAME, mExtraParams);
        assertTrue(testPassed);
    }

    /**
     * Spawns a device-based function to test switching on/off airplane mode multiple times
     *
     * @throws Exception if the test failed at any point
     */
    public void testDownloadMultipleAirplaneModeEnableDisable() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "runDownloadMultipleAirplaneModeEnableDisable",
                DOWNLOAD_TEST_RUNNER_NAME, mExtraParams);
        assertTrue(testPassed);
    }

    /**
     * Spawns a device-based function to test 15 concurrent downloads of 5,000,000-byte files
     *
     * @throws Exception if the test failed at any point
     */
    public void testDownloadMultipleSimultaneously() throws Exception {
        mPMUtils.installAppAndVerifyExistsOnDevice(String.format("%s%s%s", getTestAppPath(),
                File.separator, FILE_DOWNLOAD_APK), FILE_DOWNLOAD_PKG, true);

        boolean testPassed = mPMUtils.runDeviceTestsDidAllTestsPass(FILE_DOWNLOAD_PKG,
                FILE_DOWNLOAD_CLASS, "runDownloadMultipleSimultaneously",
                DOWNLOAD_TEST_RUNNER_NAME, mExtraParams);
        assertTrue(testPassed);
    }
}
