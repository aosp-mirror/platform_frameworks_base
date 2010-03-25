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
    private PackageManagerHostTestUtils mPMHostUtils = null;

    private String appPrivatePath = null;
    private String deviceAppPath = null;
    private String sdcardAppPath = null;

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

        // setup the PackageManager host tests utilities class, and get various paths we'll need...
        mPMHostUtils = new PackageManagerHostTestUtils(getDevice());
        appPrivatePath = mPMHostUtils.getAppPrivatePath();
        deviceAppPath = mPMHostUtils.getDeviceAppPath();
        sdcardAppPath = mPMHostUtils.getSDCardAppPath();
    }

    /**
     * Get the absolute file system location of test app with given filename
     * @param fileName the file name of the test app apk
     * @return {@link String} of absolute file path
     */
    public String getTestAppFilePath(String fileName) {
        return String.format("%s%s%s", getTestAppPath(), File.separator, fileName);
    }

    public static Test suite() {
        return new DeviceTestSuite(PackageManagerHostTests.class);
    }

    /**
     * Regression test to verify that pushing an apk to the private app directory doesn't install
     * the app, and otherwise cause the system to blow up.
     * <p/>
     * Assumes adb is running as root in device under test.
     */
    public void testPushAppPrivate() throws IOException, InterruptedException {
        Log.i(LOG_TAG, "testing pushing an apk to /data/app-private");
        final String apkAppPrivatePath =  appPrivatePath + SIMPLE_APK;

        // cleanup test app just in case it was accidently installed
        getDevice().uninstallPackage(SIMPLE_PKG);
        mPMHostUtils.executeShellCommand("stop");
        mPMHostUtils.pushFile(getTestAppFilePath(SIMPLE_APK), apkAppPrivatePath);

        // sanity check to make sure file is there
        assertTrue(mPMHostUtils.doesRemoteFileExist(apkAppPrivatePath));
        mPMHostUtils.executeShellCommand("start");

        mPMHostUtils.waitForDevice();

        // grep for package to make sure its not installed
        assertFalse(mPMHostUtils.doesPackageExist(SIMPLE_PKG));
        // ensure it has been deleted from app-private
        assertFalse(mPMHostUtils.doesRemoteFileExist(apkAppPrivatePath));
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
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(AUTO_LOC_APK), AUTO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(AUTO_LOC_PKG);
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
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(INTERNAL_LOC_APK), INTERNAL_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(INTERNAL_LOC_PKG);
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
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(
                    getTestAppFilePath(EXTERNAL_LOC_APK), EXTERNAL_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(EXTERNAL_LOC_PKG);
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
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(VERSATILE_LOC_INTERNAL_APK), VERSATILE_LOC_PKG, false);
            mPMHostUtils.uninstallApp(VERSATILE_LOC_PKG);
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(
                    getTestAppFilePath(VERSATILE_LOC_EXTERNAL_APK), VERSATILE_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(VERSATILE_LOC_PKG);
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
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(
                    getTestAppFilePath(VERSATILE_LOC_EXTERNAL_APK), VERSATILE_LOC_PKG, false);
            mPMHostUtils.uninstallApp(VERSATILE_LOC_PKG);
            // then replace the app with one marked for internalOnly
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(VERSATILE_LOC_INTERNAL_APK), VERSATILE_LOC_PKG, false);
        }
        // cleanup test app
        finally {
          mPMHostUtils.uninstallApp(VERSATILE_LOC_PKG);
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
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    UPDATE_EXTERNAL_LOC_V1_EXT_APK), UPDATE_EXTERNAL_LOC_PKG, false);
            // now replace the app with one where the location is blank (app should stay external)
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    UPDATE_EXTERNAL_LOC_V2_NONE_APK), UPDATE_EXTERNAL_LOC_PKG, true);
        }
        // cleanup test app
        finally {
          mPMHostUtils.uninstallApp(UPDATE_EXTERNAL_LOC_PKG);
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
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    UPDATE_EXT_TO_INT_LOC_V1_EXT_APK), UPDATE_EXT_TO_INT_LOC_PKG, false);
            // now replace the app with an update marked for internalOnly...
            mPMHostUtils.installAppAndVerifyExistsOnDevice(getTestAppFilePath(
                    UPDATE_EXT_TO_INT_LOC_V2_INT_APK), UPDATE_EXT_TO_INT_LOC_PKG, true);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(UPDATE_EXT_TO_INT_LOC_PKG);
        }
    }
}
