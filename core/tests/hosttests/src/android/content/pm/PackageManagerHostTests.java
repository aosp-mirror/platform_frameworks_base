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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.Log;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.TimeoutException;
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
    // Apk with install location set to auto (2 versions, for update testing)
    private static final String AUTO_LOC_VERSION_V1_APK = "AutoLocVersionedTestApp_v1.apk";
    private static final String AUTO_LOC_VERSION_V2_APK = "AutoLocVersionedTestApp_v2.apk";
    private static final String AUTO_LOC_VERSION_PKG =
            "com.android.framework.autolocversionedtestapp";
    // Apk with install location set to preferExternal (2 versions, for update testing)
    private static final String EXTERNAL_LOC_VERSION_V1_APK = "ExternalLocVersionedTestApp_v1.apk";
    private static final String EXTERNAL_LOC_VERSION_V2_APK = "ExternalLocVersionedTestApp_v2.apk";
    private static final String EXTERNAL_LOC_VERSION_PKG =
            "com.android.framework.externallocversionedtestapp";
    // Apk with install location set to auto (2 versions, for update testing)
    private static final String NO_LOC_VERSION_V1_APK = "NoLocVersionedTestApp_v1.apk";
    private static final String NO_LOC_VERSION_V2_APK = "NoLocVersionedTestApp_v2.apk";
    private static final String NO_LOC_VERSION_PKG =
            "com.android.framework.nolocversionedtestapp";
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
    // Apk set to preferExternal, with Access Fine Location permissions set in its manifest
    private static final String FL_PERMS_APK = "ExternalLocPermsFLTestApp.apk";
    private static final String FL_PERMS_PKG = "com.android.framework.externallocpermsfltestapp";
    // Apk set to preferExternal, with all permissions set in manifest
    private static final String ALL_PERMS_APK = "ExternalLocAllPermsTestApp.apk";
    private static final String ALL_PERMS_PKG = "com.android.framework.externallocallpermstestapp";
    // Apks with the same package name, but install location set to
    // one of: Internal, External, Auto, or None
    private static final String VERSATILE_LOC_PKG = "com.android.framework.versatiletestapp";
    private static final String VERSATILE_LOC_INTERNAL_APK = "VersatileTestApp_Internal.apk";
    private static final String VERSATILE_LOC_EXTERNAL_APK = "VersatileTestApp_External.apk";
    private static final String VERSATILE_LOC_AUTO_APK = "VersatileTestApp_Auto.apk";
    private static final String VERSATILE_LOC_NONE_APK = "VersatileTestApp_None.apk";
    // Apks with shared UserID
    private static final String SHARED_PERMS_APK = "ExternalSharedPermsTestApp.apk";
    private static final String SHARED_PERMS_PKG
            = "com.android.framework.externalsharedpermstestapp";
    private static final String SHARED_PERMS_FL_APK = "ExternalSharedPermsFLTestApp.apk";
    private static final String SHARED_PERMS_FL_PKG
            = "com.android.framework.externalsharedpermsfltestapp";
    private static final String SHARED_PERMS_BT_APK = "ExternalSharedPermsBTTestApp.apk";
    private static final String SHARED_PERMS_BT_PKG
            = "com.android.framework.externalsharedpermsbttestapp";
    // Apk with shared UserID, but signed with a different cert (the media cert)
    private static final String SHARED_PERMS_DIFF_KEY_APK = "ExternalSharedPermsDiffKeyTestApp.apk";
    private static final String SHARED_PERMS_DIFF_KEY_PKG
            = "com.android.framework.externalsharedpermsdiffkeytestapp";

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

        // Ensure the default is set to let the system decide where to install apps
        // (It's ok for individual tests to override and change this during their test, but should
        // reset it back when they're done)
        mPMHostUtils.setDevicePreferredInstallLocation(
                PackageManagerHostTestUtils.InstallLocPreference.AUTO);
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
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testPushAppPrivate() throws IOException, InterruptedException, InstallException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
            SyncException {
        Log.i(LOG_TAG, "testing pushing an apk to /data/app-private");
        final String apkAppPrivatePath =  appPrivatePath + SIMPLE_APK;

        // cleanup test app just in case it was accidently installed
        getDevice().uninstallPackage(SIMPLE_PKG);
        mPMHostUtils.executeShellCommand("stop");
        mPMHostUtils.pushFile(getTestAppFilePath(SIMPLE_APK), apkAppPrivatePath);

        // sanity check to make sure file is there
        assertTrue(mPMHostUtils.doesRemoteFileExist(apkAppPrivatePath));
        mPMHostUtils.executeShellCommand("start");

        mPMHostUtils.waitForPackageManager();

        // grep for package to make sure its not installed
        assertFalse(mPMHostUtils.doesPackageExist(SIMPLE_PKG));
        // ensure it has been deleted from app-private
        assertFalse(mPMHostUtils.doesRemoteFileExist(apkAppPrivatePath));
    }

    /**
     * Helper to do a standard install of an apk and verify it installed to the correct location.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @param apkName the file name of the test app apk
     * @param pkgName the package name of the test app apk
     * @param expectedLocation the file name of the test app apk
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    private void doStandardInstall(String apkName, String pkgName,
            PackageManagerHostTestUtils.InstallLocation expectedLocation)
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {

        if (expectedLocation == PackageManagerHostTestUtils.InstallLocation.DEVICE) {
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(apkName), pkgName, false);
        }
        else {
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(
                    getTestAppFilePath(apkName), pkgName, false);
        }
    }

    /**
     * Installs the Auto app using the preferred device install location specified,
     * and verifies it was installed on the device.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @param preference the device's preferred location of where to install apps
     * @param expectedLocation the expected location of where the apk was installed
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void installAppAutoLoc(PackageManagerHostTestUtils.InstallLocPreference preference,
            PackageManagerHostTestUtils.InstallLocation expectedLocation)
            throws IOException, InterruptedException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException, InstallException {

        PackageManagerHostTestUtils.InstallLocPreference savedPref =
                PackageManagerHostTestUtils.InstallLocPreference.AUTO;

        try {
            savedPref = mPMHostUtils.getDevicePreferredInstallLocation();
            mPMHostUtils.setDevicePreferredInstallLocation(preference);

            doStandardInstall(AUTO_LOC_APK, AUTO_LOC_PKG, expectedLocation);
        }
        // cleanup test app
        finally {
            mPMHostUtils.setDevicePreferredInstallLocation(savedPref);
            mPMHostUtils.uninstallApp(AUTO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=auto
     * will install the app to the device when device's preference is auto.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppAutoLocPrefIsAuto() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=auto, prefer=auto gets installed on device");
        installAppAutoLoc(PackageManagerHostTestUtils.InstallLocPreference.AUTO,
                PackageManagerHostTestUtils.InstallLocation.DEVICE);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=auto
     * will install the app to the device when device's preference is internal.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppAutoLocPrefIsInternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=auto, prefer=internal gets installed on device");
        installAppAutoLoc(PackageManagerHostTestUtils.InstallLocPreference.INTERNAL,
                PackageManagerHostTestUtils.InstallLocation.DEVICE);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=auto
     * will install the app to the SD card when device's preference is external.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppAutoLocPrefIsExternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=auto, prefer=external gets installed on device");
        installAppAutoLoc(PackageManagerHostTestUtils.InstallLocPreference.EXTERNAL,
                PackageManagerHostTestUtils.InstallLocation.DEVICE);
    }

    /**
     * Installs the Internal app using the preferred device install location specified,
     * and verifies it was installed to the location expected.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @param preference the device's preferred location of where to install apps
     * @param expectedLocation the expected location of where the apk was installed
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the (un)install failed.
     */
    public void installAppInternalLoc(PackageManagerHostTestUtils.InstallLocPreference preference,
            PackageManagerHostTestUtils.InstallLocation expectedLocation)
            throws IOException, InterruptedException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException, InstallException {

        PackageManagerHostTestUtils.InstallLocPreference savedPref =
            PackageManagerHostTestUtils.InstallLocPreference.AUTO;

        try {
            savedPref = mPMHostUtils.getDevicePreferredInstallLocation();
            mPMHostUtils.setDevicePreferredInstallLocation(preference);

            doStandardInstall(INTERNAL_LOC_APK, INTERNAL_LOC_PKG, expectedLocation);
        }
        // cleanup test app
        finally {
            mPMHostUtils.setDevicePreferredInstallLocation(savedPref);
            mPMHostUtils.uninstallApp(INTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=internalOnly
     * will install the app to the device when device's preference is auto.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppInternalLocPrefIsAuto() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=internal, prefer=auto gets installed on device");
        installAppInternalLoc(PackageManagerHostTestUtils.InstallLocPreference.AUTO,
                PackageManagerHostTestUtils.InstallLocation.DEVICE);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=internalOnly
     * will install the app to the device when device's preference is internal.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppInternalLocPrefIsInternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=internal, prefer=internal is installed on device");
        installAppInternalLoc(PackageManagerHostTestUtils.InstallLocPreference.INTERNAL,
                PackageManagerHostTestUtils.InstallLocation.DEVICE);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=internalOnly
     * will install the app to the device when device's preference is external.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppInternalLocPrefIsExternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=internal, prefer=external is installed on device");
        installAppInternalLoc(PackageManagerHostTestUtils.InstallLocPreference.EXTERNAL,
                PackageManagerHostTestUtils.InstallLocation.DEVICE);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=preferExternal
     * will install the app to the SD card.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @param preference the device's preferred location of where to install apps
     * @param expectedLocation the expected location of where the apk was installed
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void installAppExternalLoc(PackageManagerHostTestUtils.InstallLocPreference preference,
            PackageManagerHostTestUtils.InstallLocation expectedLocation)
            throws IOException, InterruptedException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException, InstallException {

        PackageManagerHostTestUtils.InstallLocPreference savedPref =
            PackageManagerHostTestUtils.InstallLocPreference.AUTO;

        try {
            savedPref = mPMHostUtils.getDevicePreferredInstallLocation();
            mPMHostUtils.setDevicePreferredInstallLocation(preference);

            doStandardInstall(EXTERNAL_LOC_APK, EXTERNAL_LOC_PKG, expectedLocation);

        }
        // cleanup test app
        finally {
            mPMHostUtils.setDevicePreferredInstallLocation(savedPref);
            mPMHostUtils.uninstallApp(EXTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=preferExternal
     * will install the app to the device when device's preference is auto.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppExternalLocPrefIsAuto() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=external, pref=auto gets installed on SD Card");
        installAppExternalLoc(PackageManagerHostTestUtils.InstallLocPreference.AUTO,
                PackageManagerHostTestUtils.InstallLocation.SDCARD);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=preferExternal
     * will install the app to the device when device's preference is internal.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppExternalLocPrefIsInternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=external, pref=internal gets installed on SD Card");
        installAppExternalLoc(PackageManagerHostTestUtils.InstallLocPreference.INTERNAL,
                PackageManagerHostTestUtils.InstallLocation.SDCARD);
    }

    /**
     * Regression test to verify that an app with its manifest set to installLocation=preferExternal
     * will install the app to the device when device's preference is external.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppExternalLocPrefIsExternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test installLocation=external, pref=external gets installed on SD Card");
        installAppExternalLoc(PackageManagerHostTestUtils.InstallLocPreference.EXTERNAL,
                PackageManagerHostTestUtils.InstallLocation.SDCARD);
    }

    /**
     * Regression test to verify that an app without installLocation in its manifest
     * will install the app to the device by default when the system default pref is to let the
     * system decide.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppNoLocPrefIsAuto() throws IOException, InterruptedException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
            InstallException {
        Log.i(LOG_TAG, "Test an app with no installLocation gets installed on device");

        PackageManagerHostTestUtils.InstallLocPreference savedPref =
            PackageManagerHostTestUtils.InstallLocPreference.AUTO;

        try {
            savedPref = mPMHostUtils.getDevicePreferredInstallLocation();
            mPMHostUtils.setDevicePreferredInstallLocation(
                    PackageManagerHostTestUtils.InstallLocPreference.AUTO);
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(NO_LOC_APK), NO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.setDevicePreferredInstallLocation(savedPref);
            mPMHostUtils.uninstallApp(NO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app without installLocation in its manifest
     * will install the app to the device by default when the system default pref is to install
     * external.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppNoLocPrefIsExternal() throws IOException, InterruptedException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
            InstallException {
        Log.i(LOG_TAG, "Test an app with no installLocation gets installed on SD card");

        PackageManagerHostTestUtils.InstallLocPreference savedPref =
            PackageManagerHostTestUtils.InstallLocPreference.AUTO;

        try {
            savedPref = mPMHostUtils.getDevicePreferredInstallLocation();
            mPMHostUtils.setDevicePreferredInstallLocation(
                    PackageManagerHostTestUtils.InstallLocPreference.EXTERNAL);
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(
                    getTestAppFilePath(NO_LOC_APK), NO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.setDevicePreferredInstallLocation(savedPref);
            mPMHostUtils.uninstallApp(NO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app without installLocation in its manifest
     * will install the app to the device by default when the system default pref is to install
     * internal.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAppNoLocPrefIsInternal() throws IOException, InterruptedException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
            InstallException {
        Log.i(LOG_TAG, "Test an app with no installLocation gets installed on device");

        PackageManagerHostTestUtils.InstallLocPreference savedPref =
            PackageManagerHostTestUtils.InstallLocPreference.AUTO;

        try {
            savedPref = mPMHostUtils.getDevicePreferredInstallLocation();
            mPMHostUtils.setDevicePreferredInstallLocation(
                    PackageManagerHostTestUtils.InstallLocPreference.INTERNAL);
            mPMHostUtils.installAppAndVerifyExistsOnDevice(
                    getTestAppFilePath(NO_LOC_APK), NO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.setDevicePreferredInstallLocation(savedPref);
            mPMHostUtils.uninstallApp(NO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its installLocation set to internal that is
     * forward-locked will get installed to the correct location.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallFwdLockedAppInternal() throws IOException, InterruptedException,
            InstallException, SyncException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test an app with installLoc set to Internal gets installed to app-private");

        try {
            mPMHostUtils.installFwdLockedAppAndVerifyExists(
                    getTestAppFilePath(INTERNAL_LOC_APK), INTERNAL_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(INTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its installLocation set to external that is
     * forward-locked will get installed to the correct location.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallFwdLockedAppExternal() throws IOException, InterruptedException,
            InstallException, SyncException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test an app with installLoc set to Internal gets installed to app-private");

        try {
            mPMHostUtils.installFwdLockedAppAndVerifyExists(
                    getTestAppFilePath(INTERNAL_LOC_APK), INTERNAL_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(INTERNAL_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with its installLocation set to external that is
     * forward-locked will get installed to the correct location.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallFwdLockedAppAuto() throws IOException, InterruptedException,
            InstallException, SyncException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test an app with installLoc set to Auto gets installed to app-private");

        try {
            mPMHostUtils.installFwdLockedAppAndVerifyExists(
                    getTestAppFilePath(AUTO_LOC_APK), AUTO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(AUTO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that an app with no installLocation set and is
     * forward-locked installed will get installed to the correct location.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallFwdLockedAppNone() throws IOException, InterruptedException,
            InstallException, SyncException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test an app with no installLoc set gets installed to app-private");

        try {
            mPMHostUtils.installFwdLockedAppAndVerifyExists(
                    getTestAppFilePath(NO_LOC_APK), NO_LOC_PKG, false);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(NO_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that we can install an app onto the device,
     * uninstall it, and reinstall it onto the SD card.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    // TODO: This currently relies on the app's manifest to switch from device to
    // SD card install locations. We might want to make Device's installPackage()
    // accept a installLocation flag so we can install a package to the
    // destination of our choosing.
    public void testReinstallInternalToExternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
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
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    // TODO: This currently relies on the app's manifest to switch from device to
    // SD card install locations. We might want to make Device's installPackage()
    // accept a installLocation flag so we can install a package to the
    // destination of our choosing.
    public void testReinstallExternalToInternal() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
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
     * the update onto the SD card as well when location is set to external for both versions
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testUpdateBothExternal() throws IOException, InterruptedException, InstallException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test updating an app on the SD card stays on the SD card");

        try {
            // install the app externally
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    EXTERNAL_LOC_VERSION_V1_APK), EXTERNAL_LOC_VERSION_PKG, false);
            // now replace the app with one where the location is still set to preferExternal
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    EXTERNAL_LOC_VERSION_V2_APK), EXTERNAL_LOC_VERSION_PKG, true);
        }
        // cleanup test app
        finally {
          mPMHostUtils.uninstallApp(EXTERNAL_LOC_VERSION_PKG);
        }
    }

    /**
     * Regression test to verify that updating an app on the SD card will install
     * the update onto the SD card as well when location is not explicitly set in the
     * updated apps' manifest file.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testUpdateToSDCard() throws IOException, InterruptedException, InstallException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
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
     * the update onto the device if the manifest has changed to installLocation=internalOnly
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testUpdateSDCardToDevice() throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test updating an app on the SD card to the Device through manifest change");

        try {
            // install the app externally
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    UPDATE_EXT_TO_INT_LOC_V1_EXT_APK), UPDATE_EXT_TO_INT_LOC_PKG, false);
            // now replace the app with an update marked for internalOnly...(should move internal)
            mPMHostUtils.installAppAndVerifyExistsOnDevice(getTestAppFilePath(
                    UPDATE_EXT_TO_INT_LOC_V2_INT_APK), UPDATE_EXT_TO_INT_LOC_PKG, true);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(UPDATE_EXT_TO_INT_LOC_PKG);
        }
    }

    /**
     * Regression test to verify that installing and updating a forward-locked app will install
     * the update onto the device's forward-locked location
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndUpdateExternalLocForwardLockedApp()
            throws IOException, InterruptedException, InstallException, SyncException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test updating a forward-locked app marked preferExternal");

        try {
            // first try to install the forward-locked app externally
            mPMHostUtils.installFwdLockedAppAndVerifyExists(getTestAppFilePath(
                    EXTERNAL_LOC_VERSION_V1_APK), EXTERNAL_LOC_VERSION_PKG, false);
            // now replace the app with an update marked for internalOnly and as forward locked
            mPMHostUtils.installFwdLockedAppAndVerifyExists(getTestAppFilePath(
                    EXTERNAL_LOC_VERSION_V2_APK), EXTERNAL_LOC_VERSION_PKG, true);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(EXTERNAL_LOC_VERSION_PKG);
        }
    }

    /**
     * Regression test to verify that updating a forward-locked app will install
     * the update onto the device's forward-locked location
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndUpdateNoLocForwardLockedApp()
            throws IOException, InterruptedException, InstallException, SyncException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test updating a forward-locked app with no installLocation pref set");

        try {
            // install the app
            mPMHostUtils.installFwdLockedAppAndVerifyExists(getTestAppFilePath(
                    NO_LOC_VERSION_V1_APK), NO_LOC_VERSION_PKG, false);
            // now replace the app with an update marked for internalOnly...
            mPMHostUtils.installFwdLockedAppAndVerifyExists(getTestAppFilePath(
                    NO_LOC_VERSION_V2_APK), NO_LOC_VERSION_PKG, true);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(NO_LOC_VERSION_PKG);
        }
    }

    /**
     * Regression test to verify that an app with all permissions set can be installed on SD card
     * and then launched without crashing.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchAllPermsAppOnSD()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app with all perms set, installed on SD card");

        try {
            // install the app
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    ALL_PERMS_APK), ALL_PERMS_PKG, false);
            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(ALL_PERMS_PKG);
            assert(testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(ALL_PERMS_PKG);
        }
    }

    /**
     * Regression test to verify that an app with ACCESS_FINE_LOCATION (GPS) permissions can
     * run without permissions errors.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchFLPermsAppOnSD()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app with location perms set, installed on SD card");

        try {
            // install the app and verify we can launch it without permissions errors
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_FL_APK), SHARED_PERMS_FL_PKG, false);
            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_FL_PKG);
            assert(testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(SHARED_PERMS_FL_PKG);
        }
    }

    /**
     * Regression test to verify that an app with BLUE_TOOTH permissions can
     * run without permissions errors.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchBTPermsAppOnSD()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app with bluetooth perms set, installed on SD card");

        try {
            // install the app and verify we can launch it without permissions errors
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_BT_APK), SHARED_PERMS_BT_PKG, false);
            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_BT_PKG);
            assert(testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(SHARED_PERMS_BT_PKG);
        }
    }

    /**
     * Regression test to verify that a shared app with no explicit permissions throws a
     * SecurityException when launched if its other shared apps are not installed.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchSharedPermsAppOnSD_NoPerms()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app with no explicit perms set, installed on SD card");

        try {
            // Make sure the 2 shared apps with needed permissions are not installed...
            mPMHostUtils.uninstallApp(SHARED_PERMS_FL_PKG);
            mPMHostUtils.uninstallApp(SHARED_PERMS_BT_PKG);

            // now install the app and see if when we launch it we get a permissions error
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_APK), SHARED_PERMS_PKG, false);

            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_PKG);
            assertEquals("Shared perms app should fail to run", false, testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(SHARED_PERMS_PKG);
        }
    }

    /**
     * Regression test to verify that a shared app with no explicit permissions can run if its other
     * shared apps are installed.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchSharedPermsAppOnSD_GrantedPerms()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app with no explicit perms set, installed on SD card");

        try {
            // install the 2 shared apps with needed permissions first
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_FL_APK), SHARED_PERMS_FL_PKG, false);
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_BT_APK), SHARED_PERMS_BT_PKG, false);

            // now install the test app and see if we can launch it without errors
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_APK), SHARED_PERMS_PKG, false);
            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_PKG);
            assert(testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(SHARED_PERMS_PKG);
            mPMHostUtils.uninstallApp(SHARED_PERMS_BT_PKG);
            mPMHostUtils.uninstallApp(SHARED_PERMS_FL_PKG);
        }
    }

    /**
     * Regression test to verify that an app with ACCESS_FINE_LOCATION (GPS) permissions can
     * run without permissions errors even after a reboot
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchFLPermsAppOnSD_Reboot()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app with location perms set, installed on SD card");

        try {
            // install the app and verify we can launch it without permissions errors
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_FL_APK), SHARED_PERMS_FL_PKG, false);
            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_FL_PKG);
            assert(testsPassed);

            mPMHostUtils.rebootDevice();

            testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_FL_PKG);
            assert(testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(SHARED_PERMS_FL_PKG);
        }
    }

    /**
     * Regression test to verify that a shared app with no explicit permissions can run if its other
     * shared apps are installed, even after a reboot.
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void testInstallAndLaunchSharedPermsAppOnSD_Reboot()
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "Test launching an app on SD, with no explicit perms set after reboot");

        try {
            // install the 2 shared apps with needed permissions first
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_FL_APK), SHARED_PERMS_FL_PKG, false);
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_BT_APK), SHARED_PERMS_BT_PKG, false);

            // now install the test app and see if we can launch it without errors
            mPMHostUtils.installAppAndVerifyExistsOnSDCard(getTestAppFilePath(
                    SHARED_PERMS_APK), SHARED_PERMS_PKG, false);
            boolean testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_PKG);
            assert(testsPassed);

            // reboot
            mPMHostUtils.rebootDevice();

            // Verify we can still launch the app
            testsPassed = mPMHostUtils.runDeviceTestsDidAllTestsPass(SHARED_PERMS_PKG);
            assert(testsPassed);
        }
        // cleanup test app
        finally {
            mPMHostUtils.uninstallApp(SHARED_PERMS_PKG);
            mPMHostUtils.uninstallApp(SHARED_PERMS_BT_PKG);
            mPMHostUtils.uninstallApp(SHARED_PERMS_FL_PKG);
        }
    }
}
