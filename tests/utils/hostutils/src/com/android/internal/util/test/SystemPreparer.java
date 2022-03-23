/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.util.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import javax.annotation.Nullable;

/**
 * Allows pushing files onto the device and various options for rebooting. Useful for installing
 * APKs/files to system partitions which otherwise wouldn't be easily changed.
 *
 * It's strongly recommended to pass in a {@link ClassRule} annotated {@link TestRuleDelegate} to
 * do a full reboot at the end of a test to ensure the device is in a valid state, assuming the
 * default {@link RebootStrategy#FULL} isn't used.
 */
public class SystemPreparer extends ExternalResource {
    private static final long OVERLAY_ENABLE_TIMEOUT_MS = 30000;

    // The paths of the files pushed onto the device through this rule to be removed after.
    private ArrayList<String> mPushedFiles = new ArrayList<>();

    // The package names of packages installed through this rule.
    private ArrayList<String> mInstalledPackages = new ArrayList<>();

    private final TemporaryFolder mHostTempFolder;
    private final DeviceProvider mDeviceProvider;
    private final RebootStrategy mRebootStrategy;
    private final TearDownRule mTearDownRule;

    // When debugging, it may be useful to run a test case without rebooting the device afterwards,
    // to manually verify the device state.
    private boolean mDebugSkipAfterReboot;

    public SystemPreparer(TemporaryFolder hostTempFolder, DeviceProvider deviceProvider) {
        this(hostTempFolder, RebootStrategy.FULL, null, deviceProvider);
    }

    public SystemPreparer(TemporaryFolder hostTempFolder, RebootStrategy rebootStrategy,
            @Nullable TestRuleDelegate testRuleDelegate, DeviceProvider deviceProvider) {
        this(hostTempFolder, rebootStrategy, testRuleDelegate, false, deviceProvider);
    }

    public SystemPreparer(TemporaryFolder hostTempFolder, RebootStrategy rebootStrategy,
            @Nullable TestRuleDelegate testRuleDelegate, boolean debugSkipAfterReboot,
            DeviceProvider deviceProvider) {
        mHostTempFolder = hostTempFolder;
        mDeviceProvider = deviceProvider;
        mRebootStrategy = rebootStrategy;
        mTearDownRule = new TearDownRule(mDeviceProvider);
        if (testRuleDelegate != null) {
            testRuleDelegate.setDelegate(mTearDownRule);
        }
        mDebugSkipAfterReboot = debugSkipAfterReboot;
    }

    /** Copies a file within the host test jar to a path on device. */
    public SystemPreparer pushResourceFile(String filePath, String outputPath)
            throws DeviceNotAvailableException, IOException {
        final ITestDevice device = mDeviceProvider.getDevice();
        remount();
        assertTrue(device.pushFile(copyResourceToTemp(filePath), outputPath));
        addPushedFile(device, outputPath);
        return this;
    }

    /** Copies a file directly from the host file system to a path on device. */
    public SystemPreparer pushFile(File file, String outputPath)
            throws DeviceNotAvailableException {
        final ITestDevice device = mDeviceProvider.getDevice();
        remount();
        assertTrue(device.pushFile(file, outputPath));
        addPushedFile(device, outputPath);
        return this;
    }

    private void addPushedFile(ITestDevice device, String outputPath)
            throws DeviceNotAvailableException {
        Path pathCreated = Paths.get(outputPath);

        // Find the top most parent that is new to the device
        while (pathCreated.getParent() != null
                && !device.doesFileExist(pathCreated.getParent().toString())) {
            pathCreated = pathCreated.getParent();
        }

        mPushedFiles.add(pathCreated.toString());
    }

    /** Deletes the given path from the device */
    public SystemPreparer deleteFile(String file) throws DeviceNotAvailableException {
        final ITestDevice device = mDeviceProvider.getDevice();
        remount();
        device.deleteFile(file);
        return this;
    }

    /** Installs an APK within the host test jar onto the device. */
    public SystemPreparer installResourceApk(String resourcePath, String packageName)
            throws DeviceNotAvailableException, IOException {
        final ITestDevice device = mDeviceProvider.getDevice();
        final File tmpFile = copyResourceToTemp(resourcePath);
        final String result = device.installPackage(tmpFile, true /* reinstall */);
        Assert.assertNull(result);
        mInstalledPackages.add(packageName);
        return this;
    }

    /** Stages multiple APEXs within the host test jar onto the device. */
    public SystemPreparer stageMultiplePackages(String[] resourcePaths, String[] packageNames)
            throws DeviceNotAvailableException, IOException {
        assertEquals(resourcePaths.length, packageNames.length);
        final ITestDevice device = mDeviceProvider.getDevice();
        final String[] adbCommandLine = new String[resourcePaths.length + 2];
        adbCommandLine[0] = "install-multi-package";
        adbCommandLine[1] = "--staged";
        for (int i = 0; i < resourcePaths.length; i++) {
            final File tmpFile = copyResourceToTemp(resourcePaths[i]);
            adbCommandLine[i + 2] = tmpFile.getAbsolutePath();
            mInstalledPackages.add(packageNames[i]);
        }
        final String output = device.executeAdbCommand(adbCommandLine);
        assertTrue(output.contains("Success. Reboot device to apply staged session"));
        return this;
    }

    /** Sets the enable state of an overlay package. */
    public SystemPreparer setOverlayEnabled(String packageName, boolean enabled)
            throws DeviceNotAvailableException {
        final ITestDevice device = mDeviceProvider.getDevice();
        final String enable = enabled ? "enable" : "disable";

        // Wait for the overlay to change its enabled state.
        final long endMillis = System.currentTimeMillis() + OVERLAY_ENABLE_TIMEOUT_MS;
        String result;
        while (System.currentTimeMillis() <= endMillis) {
            device.executeShellCommand(String.format("cmd overlay %s %s", enable, packageName));
            result = device.executeShellCommand("cmd overlay dump isenabled "
                    + packageName);
            if (((enabled) ? "true\n" : "false\n").equals(result)) {
                return this;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
            }
        }

        throw new IllegalStateException(String.format("Failed to %s overlay %s:\n%s", enable,
                packageName, device.executeShellCommand("cmd overlay list")));
    }

    /** Restarts the device and waits until after boot is completed. */
    public SystemPreparer reboot() throws DeviceNotAvailableException {
        ITestDevice device = mDeviceProvider.getDevice();
        switch (mRebootStrategy) {
            case FULL:
                device.reboot();
                break;
            case UNTIL_ONLINE:
                device.rebootUntilOnline();
                break;
            case USERSPACE:
                device.rebootUserspace();
                break;
            case USERSPACE_UNTIL_ONLINE:
                device.rebootUserspaceUntilOnline();
                break;
            // TODO(b/159540015): Make this START_STOP instead of default once it's fixed. Can't
            //  currently be done because START_STOP is commented out.
            default:
                device.executeShellCommand("stop");
                device.executeShellCommand("start");
                ITestDevice.RecoveryMode cachedRecoveryMode = device.getRecoveryMode();
                device.setRecoveryMode(ITestDevice.RecoveryMode.ONLINE);

                if (device.isEncryptionSupported()) {
                    if (device.isDeviceEncrypted()) {
                        LogUtil.CLog.e("Device is encrypted after userspace reboot!");
                        device.unlockDevice();
                    }
                }

                device.setRecoveryMode(cachedRecoveryMode);
                device.waitForDeviceAvailable();
                break;
        }
        return this;
    }

    public SystemPreparer remount() throws DeviceNotAvailableException {
        mTearDownRule.remount();
        return this;
    }

    private static @Nullable String getFileExtension(@Nullable String path) {
        if (path == null) {
            return null;
        }
        final int lastDot = path.lastIndexOf('.');
        if (lastDot >= 0) {
            return path.substring(lastDot + 1);
        } else {
            return null;
        }
    }

    /** Copies a file within the host test jar to a temporary file on the host machine. */
    private File copyResourceToTemp(String resourcePath) throws IOException {
        final String ext = getFileExtension(resourcePath);
        final File tempFile;
        if (ext != null) {
            tempFile = File.createTempFile("junit", "." + ext, mHostTempFolder.getRoot());
        } else {
            tempFile = mHostTempFolder.newFile();
        }
        final ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream assetIs = classLoader.getResourceAsStream(resourcePath);
             FileOutputStream assetOs = new FileOutputStream(tempFile)) {
            if (assetIs == null) {
                throw new IllegalStateException("Failed to find resource " + resourcePath);
            }

            int b;
            while ((b = assetIs.read()) >= 0) {
                assetOs.write(b);
            }
        }

        return tempFile;
    }

    /** Removes installed packages and files that were pushed to the device. */
    @Override
    public void after() {
        final ITestDevice device = mDeviceProvider.getDevice();
        try {
            remount();
            for (final String file : mPushedFiles) {
                device.deleteFile(file);
            }
            for (final String packageName : mInstalledPackages) {
                device.uninstallPackage(packageName);
            }
            if (!mDebugSkipAfterReboot) {
                reboot();
            }
        } catch (DeviceNotAvailableException e) {
            Assert.fail(e.toString());
        }
    }

    /**
     * A hacky workaround since {@link org.junit.AfterClass} and {@link ClassRule} require static
     * members. Will defer assignment of the actual {@link TestRule} to execute until after any
     * test case has been run.
     *
     * In effect, this makes the {@link ITestDevice} to be accessible after all test cases have
     * been executed, allowing {@link ITestDevice#reboot()} to be used to fully restore the device.
     */
    public static class TestRuleDelegate implements TestRule {

        private boolean mThrowOnNull;

        @Nullable
        private TestRule mTestRule;

        public TestRuleDelegate(boolean throwOnNull) {
            mThrowOnNull = throwOnNull;
        }

        public void setDelegate(TestRule testRule) {
            mTestRule = testRule;
        }

        @Override
        public Statement apply(Statement base, Description description) {
            if (mTestRule == null) {
                if (mThrowOnNull) {
                    throw new IllegalStateException("TestRule delegate was not set");
                } else {
                    return new Statement() {
                        @Override
                        public void evaluate() throws Throwable {
                            base.evaluate();
                        }
                    };
                }
            }

            Statement statement = mTestRule.apply(base, description);
            mTestRule = null;
            return statement;
        }
    }

    /**
     * Forces a full reboot at the end of the test class to restore any device state.
     */
    private static class TearDownRule extends ExternalResource {

        private DeviceProvider mDeviceProvider;
        private boolean mInitialized;
        private boolean mWasVerityEnabled;
        private boolean mWasAdbRoot;
        private boolean mIsVerityEnabled;

        TearDownRule(DeviceProvider deviceProvider) {
            mDeviceProvider = deviceProvider;
        }

        @Override
        protected void before() {
            // This method will never be run
        }

        @Override
        protected void after() {
            try {
                initialize();
                ITestDevice device = mDeviceProvider.getDevice();
                if (mWasVerityEnabled != mIsVerityEnabled) {
                    device.executeShellCommand(
                            mWasVerityEnabled ? "enable-verity" : "disable-verity");
                }
                device.reboot();
                if (!mWasAdbRoot) {
                    device.disableAdbRoot();
                }
            } catch (DeviceNotAvailableException e) {
                Assert.fail(e.toString());
            }
        }

        /**
         * Remount is done inside this class so that the verity state can be tracked.
         */
        public void remount() throws DeviceNotAvailableException {
            initialize();
            ITestDevice device = mDeviceProvider.getDevice();
            device.enableAdbRoot();
            if (mIsVerityEnabled) {
                mIsVerityEnabled = false;
                device.executeShellCommand("disable-verity");
                device.reboot();
            }
            device.executeShellCommand("remount");
            device.waitForDeviceAvailable();
        }

        private void initialize() throws DeviceNotAvailableException {
            if (mInitialized) {
                return;
            }
            mInitialized = true;
            ITestDevice device = mDeviceProvider.getDevice();
            mWasAdbRoot = device.isAdbRoot();
            device.enableAdbRoot();
            String veritySystem = device.getProperty("partition.system.verified");
            String verityVendor = device.getProperty("partition.vendor.verified");
            mWasVerityEnabled = (veritySystem != null && !veritySystem.isEmpty())
                    || (verityVendor != null && !verityVendor.isEmpty());
            mIsVerityEnabled = mWasVerityEnabled;
        }
    }

    public interface DeviceProvider {
        ITestDevice getDevice();
    }

    /**
     * How to reboot the device. Ordered from slowest to fastest.
     */
    @SuppressWarnings("DanglingJavadoc")
    public enum RebootStrategy {
        /** @see ITestDevice#reboot() */
        FULL,

        /** @see ITestDevice#rebootUntilOnline() () */
        UNTIL_ONLINE,

        /** @see ITestDevice#rebootUserspace() */
        USERSPACE,

        /** @see ITestDevice#rebootUserspaceUntilOnline() () */
        USERSPACE_UNTIL_ONLINE,

        /**
         * Uses shell stop && start to "reboot" the device. May leave invalid state after each test.
         * Whether this matters or not depends on what's being tested.
         *
         * TODO(b/159540015): There's a bug with this causing unnecessary disk space usage, which
         *  can eventually lead to an insufficient storage space error.
         *
         * This can be uncommented for local development, but should be left out when merging.
         * It is done this way to hopefully be caught by code review, since merging this will
         * break all of postsubmit. But the nearly 50% reduction in test runtime is worth having
         * this option exist.
         *
         * @deprecated do not use this in merged code until bug is resolved
         */
//        @Deprecated
//        START_STOP
    }
}
