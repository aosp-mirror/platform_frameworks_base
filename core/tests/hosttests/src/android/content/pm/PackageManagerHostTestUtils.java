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
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.Log;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.SyncService.ISyncProgressMonitor;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.TestIdentifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.Runtime;
import java.lang.Process;
import java.util.Hashtable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Assert;

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
    private static final int MAX_WAIT_FOR_APP_LAUNCH_TIME = 60 * 1000;
    private static final int WAIT_FOR_APP_LAUNCH_POLL_TIME = 5 * 1000;

    // Install preference on the device-side
    public static enum InstallLocPreference {
        AUTO,
        INTERNAL,
        EXTERNAL
    }

    // Actual install location
    public static enum InstallLocation {
        DEVICE,
        SDCARD
    }

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
     * Helper method to run tests and return the listener that collected the results.
     *
     * For the optional params, pass null to use the default values.

     * @param pkgName Android application package for tests
     * @param className (optional) The class containing the method to test
     * @param methodName (optional) The method in the class of which to test
     * @param runnerName (optional) The name of the TestRunner of the test on the device to be run
     * @param params (optional) Any additional parameters to pass into the Test Runner
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @return the {@link CollectingTestRunListener}
     */
    private CollectingTestRunListener doRunTests(String pkgName, String className,
            String methodName, String runnerName, Map<String, String> params) throws IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        RemoteAndroidTestRunner testRunner = new RemoteAndroidTestRunner(pkgName, runnerName,
                mDevice);

        if (className != null && methodName != null) {
            testRunner.setMethodName(className, methodName);
        }

        // Add in any additional args to pass into the test
        if (params != null) {
            for (Entry<String, String> argPair : params.entrySet()) {
                testRunner.addInstrumentationArg(argPair.getKey(), argPair.getValue());
            }
        }

        CollectingTestRunListener listener = new CollectingTestRunListener();
        try {
            testRunner.run(listener);
        } catch (IOException ioe) {
            Log.w(LOG_TAG, "encountered IOException " + ioe);
        }
        return listener;
    }

    /**
     * Runs the specified packages tests, and returns whether all tests passed or not.
     *
     * @param pkgName Android application package for tests
     * @param className The class containing the method to test
     * @param methodName The method in the class of which to test
     * @param runnerName The name of the TestRunner of the test on the device to be run
     * @param params Any additional parameters to pass into the Test Runner
     * @return true if test passed, false otherwise.
     */
    public boolean runDeviceTestsDidAllTestsPass(String pkgName, String className,
            String methodName, String runnerName, Map<String, String> params) throws IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        CollectingTestRunListener listener = doRunTests(pkgName, className, methodName,
                runnerName, params);
        return listener.didAllTestsPass();
    }

    /**
     * Runs the specified packages tests, and returns whether all tests passed or not.
     *
     * @param pkgName Android application package for tests
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @return true if every test passed, false otherwise.
     */
    public boolean runDeviceTestsDidAllTestsPass(String pkgName) throws IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        CollectingTestRunListener listener = doRunTests(pkgName, null, null, null, null);
        return listener.didAllTestsPass();
    }

    /**
     * Helper method to push a file to device
     * @param apkAppPrivatePath
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     */
    public void pushFile(final String localFilePath, final String destFilePath)
            throws IOException, SyncException, TimeoutException, AdbCommandRejectedException {
        mDevice.getSyncService().pushFile(localFilePath,
                destFilePath, new NullSyncProgressMonitor());
    }

    /**
     * Helper method to install a file
     * @param localFilePath the absolute file system path to file on local host to install
     * @param reinstall set to <code>true</code> if re-install of app should be performed
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed
     */
    public void installFile(final String localFilePath, final boolean replace) throws IOException,
            InstallException {
        String result = mDevice.installPackage(localFilePath, replace);
        assertEquals(null, result);
    }

    /**
     * Helper method to install a file that should not be install-able
     * @param localFilePath the absolute file system path to file on local host to install
     * @param reinstall set to <code>true</code> if re-install of app should be performed
     * @return the string output of the failed install attempt
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed
     */
    public String installFileFail(final String localFilePath, final boolean replace)
            throws IOException, InstallException {
        String result = mDevice.installPackage(localFilePath, replace);
        assertNotNull(result);
        return result;
    }

    /**
     * Helper method to install a file to device as forward locked
     * @param localFilePath the absolute file system path to file on local host to install
     * @param reinstall set to <code>true</code> if re-install of app should be performed
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws SyncException if the sync failed for another reason.
     * @throws InstallException if the install failed.
     */
    public String installFileForwardLocked(final String localFilePath, final boolean replace)
            throws IOException, SyncException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException, InstallException {
        String remoteFilePath = mDevice.syncPackageToDevice(localFilePath);
        InstallReceiver receiver = new InstallReceiver();
        String cmd = String.format(replace ? "pm install -r -l \"%1$s\"" :
                "pm install -l \"%1$s\"", remoteFilePath);
        mDevice.executeShellCommand(cmd, receiver);
        mDevice.removeRemotePackage(remoteFilePath);
        return receiver.getErrorMessage();
    }

    /**
     * Helper method to determine if file on device exists.
     *
     * @param destPath the absolute path of file on device to check
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public boolean doesRemoteFileExist(String destPath) throws IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        String lsGrep = executeShellCommand(String.format("ls %s", destPath));
        return !lsGrep.contains("No such file or directory");
    }

    /**
     * Helper method to determine if file exists on the device containing a given string.
     *
     * @param destPath the absolute path of the file
     * @return <code>true</code> if file exists containing given string,
     *         <code>false</code> otherwise.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public boolean doesRemoteFileExistContainingString(String destPath, String searchString)
            throws IOException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        String lsResult = executeShellCommand(String.format("ls %s", destPath));
        return lsResult.contains(searchString);
    }

    /**
     * Helper method to determine if package on device exists.
     *
     * @param packageName the Android manifest package to check.
     * @return <code>true</code> if package exists, <code>false</code> otherwise
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public boolean doesPackageExist(String packageName) throws IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        String pkgGrep = executeShellCommand(String.format("pm path %s", packageName));
        return pkgGrep.contains("package:");
    }

    /**
     * Determines if app was installed on device.
     *
     * @param packageName package name to check for
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public boolean doesAppExistOnDevice(String packageName) throws IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        return doesRemoteFileExistContainingString(DEVICE_APP_PATH, packageName);
    }

    /**
     * Determines if app was installed on SD card.
     *
     * @param packageName package name to check for
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public boolean doesAppExistOnSDCard(String packageName) throws IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        return doesRemoteFileExistContainingString(SDCARD_APP_PATH, packageName);
    }

    /**
     * Helper method to determine if app was installed on SD card.
     *
     * @param packageName package name to check for
     * @return <code>true</code> if file exists, <code>false</code> otherwise.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public boolean doesAppExistAsForwardLocked(String packageName) throws IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        return doesRemoteFileExistContainingString(APP_PRIVATE_PATH, packageName);
    }

    /**
     * Waits for device's package manager to respond.
     *
     * @throws InterruptedException
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public void waitForPackageManager() throws InterruptedException, IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
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
     * Helper to determine if the device is currently online and visible via ADB.
     *
     * @return true iff the device is currently available to ADB and online, false otherwise.
     */
    private boolean deviceIsOnline() {
        AndroidDebugBridge bridge = AndroidDebugBridge.getBridge();
        IDevice[] devices = bridge.getDevices();

        for (IDevice device : devices) {
            // only online if the device appears in the devices list, and its state is online
            if ((mDevice != null) &&
                    mDevice.getSerialNumber().equals(device.getSerialNumber()) &&
                    device.isOnline()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Waits for device to be online (visible to ADB) before returning, or times out if we've
     * waited too long. Note that this only means the device is visible via ADB, not that
     * PackageManager is fully up and running yet.
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public void waitForDeviceToComeOnline() throws InterruptedException, IOException {
        Log.i(LOG_TAG, "waiting for device to be online");
        int currentWaitTime = 0;

        // poll ADB until we see the device is online
        while (!deviceIsOnline()) {
            Thread.sleep(WAIT_FOR_DEVICE_POLL_TIME);
            currentWaitTime += WAIT_FOR_DEVICE_POLL_TIME;
            if (currentWaitTime > MAX_WAIT_FOR_DEVICE_TIME) {
                Log.e(LOG_TAG, "time out waiting for device");
                throw new InterruptedException();
            }
        }
        // Note: if we try to access the device too quickly after it is "officially" online,
        // there are sometimes strange issues where it's actually not quite ready yet,
        // so we pause for a bit once more before actually returning.
        Thread.sleep(WAIT_FOR_DEVICE_POLL_TIME);
    }

    /**
     * Queries package manager and waits until a package is launched (or times out)
     *
     * @param packageName The name of the package to wait to load
     * @throws InterruptedException
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public void waitForApp(String packageName) throws InterruptedException, IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "waiting for app to launch");
        int currentWaitTime = 0;
        // poll the package manager until it returns something for the package we're looking for
        while (!doesPackageExist(packageName)) {
            Thread.sleep(WAIT_FOR_APP_LAUNCH_POLL_TIME);
            currentWaitTime += WAIT_FOR_APP_LAUNCH_POLL_TIME;
            if (currentWaitTime > MAX_WAIT_FOR_APP_LAUNCH_TIME) {
                Log.e(LOG_TAG, "time out waiting for app to launch: " + packageName);
                throw new InterruptedException();
            }
        }
    }

    /**
     * Helper method which executes a adb shell command and returns output as a {@link String}
     * @return the output of the command
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public String executeShellCommand(String command) throws IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, String.format("adb shell %s", command));
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        mDevice.executeShellCommand(command, receiver);
        String output = receiver.getOutput();
        Log.i(LOG_TAG, String.format("Result: %s", output));
        return output;
    }

    /**
     * Helper method ensures we are in root mode on the host side. It returns only after
     * PackageManager is actually up and running.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public void runAdbRoot() throws IOException, InterruptedException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        Log.i(LOG_TAG, "adb root");
        Runtime runtime = Runtime.getRuntime();
        Process process = runtime.exec("adb root"); // adb should be in the path
        BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));

        String nextLine = null;
        while (null != (nextLine = output.readLine())) {
            Log.i(LOG_TAG, nextLine);
        }
        process.waitFor();
        waitForDeviceToComeOnline();
        waitForPackageManager(); // now wait for package manager to actually load
    }

    /**
     * Helper method which reboots the device and returns once the device is online again
     * and package manager is up and running (note this function is synchronous to callers).
     * @throws InterruptedException
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public void rebootDevice() throws IOException, InterruptedException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
        String command = "reboot"; // no need for -s since mDevice is already tied to a device
        Log.i(LOG_TAG, command);
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        mDevice.executeShellCommand(command, receiver);
        String output = receiver.getOutput();
        Log.i(LOG_TAG, String.format("Result: %s", output));
        waitForDeviceToComeOnline(); // wait for device to come online
        runAdbRoot();
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

    // For collecting results from running device tests
    public static class CollectingTestRunListener implements ITestRunListener {

        private boolean mAllTestsPassed = true;
        private String mTestRunErrorMessage = null;

        public void testEnded(TestIdentifier test, Map<String, String> metrics) {
            // ignore
        }

        public void testFailed(TestFailure status, TestIdentifier test,
                String trace) {
            Log.w(LOG_TAG, String.format("%s#%s failed: %s", test.getClassName(),
                    test.getTestName(), trace));
            mAllTestsPassed = false;
        }

        public void testRunEnded(long elapsedTime, Map<String, String> resultBundle) {
            // ignore
        }

        public void testRunFailed(String errorMessage) {
            Log.w(LOG_TAG, String.format("test run failed: %s", errorMessage));
            mAllTestsPassed = false;
            mTestRunErrorMessage = errorMessage;
        }

        public void testRunStarted(String runName, int testCount) {
            // ignore
        }

        public void testRunStopped(long elapsedTime) {
            // ignore
        }

        public void testStarted(TestIdentifier test) {
            // ignore
        }

        boolean didAllTestsPass() {
            return mAllTestsPassed;
        }

        /**
         * Get the test run failure error message.
         * @return the test run failure error message or <code>null</code> if test run completed.
         */
        String getTestRunErrorMessage() {
            return mTestRunErrorMessage;
        }
    }

    /**
     * Output receiver for "pm install package.apk" command line.
     *
     */
    private static final class InstallReceiver extends MultiLineReceiver {

        private static final String SUCCESS_OUTPUT = "Success"; //$NON-NLS-1$
        private static final Pattern FAILURE_PATTERN = Pattern.compile("Failure\\s+\\[(.*)\\]"); //$NON-NLS-1$

        private String mErrorMessage = null;

        public InstallReceiver() {
        }

        @Override
        public void processNewLines(String[] lines) {
            for (String line : lines) {
                if (line.length() > 0) {
                    if (line.startsWith(SUCCESS_OUTPUT)) {
                        mErrorMessage = null;
                    } else {
                        Matcher m = FAILURE_PATTERN.matcher(line);
                        if (m.matches()) {
                            mErrorMessage = m.group(1);
                        }
                    }
                }
            }
        }

        public boolean isCancelled() {
            return false;
        }

        public String getErrorMessage() {
            return mErrorMessage;
        }
    }

    /**
     * Helper method for installing an app to wherever is specified in its manifest, and
     * then verifying the app was installed onto SD Card.
     * <p/>
     * Assumes adb is running as root in device under test.
     *
     * @param the path of the apk to install
     * @param the name of the package
     * @param <code>true</code> if the app should be overwritten, <code>false</code> otherwise
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void installAppAndVerifyExistsOnSDCard(String apkPath, String pkgName, boolean overwrite)
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
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
        waitForPackageManager();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for installing an app to wherever is specified in its manifest, and
     * then verifying the app was installed onto device.
     * <p/>
     * Assumes adb is running as root in device under test.
     *
     * @param the path of the apk to install
     * @param the name of the package
     * @param <code>true</code> if the app should be overwritten, <code>false</code> otherwise
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     */
    public void installAppAndVerifyExistsOnDevice(String apkPath, String pkgName, boolean overwrite)
            throws IOException, InterruptedException, InstallException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException {
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
        waitForPackageManager();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for installing an app as forward-locked, and
     * then verifying the app was installed in the proper forward-locked location.
     * <p/>
     * Assumes adb is running as root in device under test.
     *
     * @param the path of the apk to install
     * @param the name of the package
     * @param <code>true</code> if the app should be overwritten, <code>false</code> otherwise
     * @throws InterruptedException if the thread was interrupted
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the install failed.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     */
    public void installFwdLockedAppAndVerifyExists(String apkPath,
            String pkgName, boolean overwrite) throws IOException, InterruptedException,
            InstallException, SyncException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        // Start with a clean slate if we're not overwriting
        if (!overwrite) {
            // cleanup test app just in case it already exists
            mDevice.uninstallPackage(pkgName);
            // grep for package to make sure its not installed
            assertFalse(doesPackageExist(pkgName));
        }

        String result = installFileForwardLocked(apkPath, overwrite);
        assertEquals(null, result);
        assertTrue(doesAppExistAsForwardLocked(pkgName));
        assertFalse(doesAppExistOnSDCard(pkgName));
        waitForPackageManager();

        // grep for package to make sure it is installed
        assertTrue(doesPackageExist(pkgName));
    }

    /**
     * Helper method for uninstalling an app.
     * <p/>
     * Assumes adb is running as root in device under test.
     *
     * @param pkgName package name to uninstall
     * @throws InterruptedException if the thread was interrupted
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the uninstall failed.
     */
    public void uninstallApp(String pkgName) throws IOException, InterruptedException,
            InstallException, TimeoutException, AdbCommandRejectedException,
            ShellCommandUnresponsiveException {
        mDevice.uninstallPackage(pkgName);
        // make sure its not installed anymore
        assertFalse(doesPackageExist(pkgName));
    }

    /**
     * Helper method for clearing any installed non-system apps.
     * Useful ensuring no non-system apps are installed, and for cleaning up stale files that
     * may be lingering on the system for whatever reason.
     * <p/>
     * Assumes adb is running as root in device under test.
     *
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     * @throws InstallException if the uninstall failed.
     */
    public void wipeNonSystemApps() throws IOException, TimeoutException,
            AdbCommandRejectedException, ShellCommandUnresponsiveException, InstallException {
      String allInstalledPackages = executeShellCommand("pm list packages -f");
      BufferedReader outputReader = new BufferedReader(new StringReader(allInstalledPackages));

      // First use Package Manager to uninstall all non-system apps
      String currentLine = null;
      while ((currentLine = outputReader.readLine()) != null) {
          // Skip over any system apps...
          if (currentLine.contains("/system/")) {
              continue;
          }
          String packageName = currentLine.substring(currentLine.indexOf('=') + 1);
          mDevice.uninstallPackage(packageName);
      }
      // Make sure there are no stale app files under these directories
      executeShellCommand(String.format("rm %s*", SDCARD_APP_PATH, "*"));
      executeShellCommand(String.format("rm %s*", DEVICE_APP_PATH, "*"));
      executeShellCommand(String.format("rm %s*", APP_PRIVATE_PATH, "*"));
    }

    /**
     * Sets the device's install location preference.
     *
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public void setDevicePreferredInstallLocation(InstallLocPreference pref) throws IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        String command = "pm setInstallLocation %d";
        int locValue = 0;
        switch (pref) {
            case INTERNAL:
                locValue = 1;
                break;
            case EXTERNAL:
                locValue = 2;
                break;
            default: // AUTO
                locValue = 0;
                break;
        }
        executeShellCommand(String.format(command, locValue));
    }

    /**
     * Gets the device's install location preference.
     *
     * <p/>
     * Assumes adb is running as root in device under test.
     * @throws TimeoutException in case of a timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws ShellCommandUnresponsiveException if the device did not output anything for
     * a period longer than the max time to output.
     * @throws IOException if connection to device was lost.
     */
    public InstallLocPreference getDevicePreferredInstallLocation() throws IOException,
            TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException {
        String result = executeShellCommand("pm getInstallLocation");
        if (result.indexOf('0') != -1) {
            return InstallLocPreference.AUTO;
        }
        else if (result.indexOf('1') != -1) {
            return InstallLocPreference.INTERNAL;
        }
        else {
            return InstallLocPreference.EXTERNAL;
        }
    }
}
