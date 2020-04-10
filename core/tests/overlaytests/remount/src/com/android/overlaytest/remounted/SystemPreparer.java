/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.overlaytest.remounted;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;

import org.junit.Assert;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

class SystemPreparer extends ExternalResource {
    private static final long OVERLAY_ENABLE_TIMEOUT_MS = 30000;

    // The paths of the files pushed onto the device through this rule.
    private ArrayList<String> mPushedFiles = new ArrayList<>();

    // The package names of packages installed through this rule.
    private ArrayList<String> mInstalledPackages = new ArrayList<>();

    private final TemporaryFolder mHostTempFolder;
    private final DeviceProvider mDeviceProvider;

    SystemPreparer(TemporaryFolder hostTempFolder, DeviceProvider deviceProvider) {
        mHostTempFolder = hostTempFolder;
        mDeviceProvider = deviceProvider;
    }

    /** Copies a file within the host test jar to a path on device. */
    SystemPreparer pushResourceFile(String resourcePath,
            String outputPath) throws DeviceNotAvailableException, IOException {
        final ITestDevice device = mDeviceProvider.getDevice();
        remount();
        assertTrue(device.pushFile(copyResourceToTemp(resourcePath), outputPath));
        mPushedFiles.add(outputPath);
        return this;
    }

    /** Installs an APK within the host test jar onto the device. */
    SystemPreparer installResourceApk(String resourcePath, String packageName)
            throws DeviceNotAvailableException, IOException {
        final ITestDevice device = mDeviceProvider.getDevice();
        final File tmpFile = copyResourceToTemp(resourcePath);
        final String result = device.installPackage(tmpFile, true /* reinstall */);
        Assert.assertNull(result);
        mInstalledPackages.add(packageName);
        return this;
    }

    /** Sets the enable state of an overlay package. */
    SystemPreparer setOverlayEnabled(String packageName, boolean enabled)
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
    SystemPreparer reboot() throws DeviceNotAvailableException {
        final ITestDevice device = mDeviceProvider.getDevice();
        device.reboot();
        return this;
    }

    SystemPreparer remount() throws DeviceNotAvailableException {
        mDeviceProvider.getDevice().executeAdbCommand("remount");
        return this;
    }

    /** Copies a file within the host test jar to a temporary file on the host machine. */
    private File copyResourceToTemp(String resourcePath) throws IOException {
        final File tempFile = mHostTempFolder.newFile(resourcePath);
        final ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream assetIs = classLoader.getResource(resourcePath).openStream();
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
    protected void after() {
        final ITestDevice device = mDeviceProvider.getDevice();
        try {
            remount();
            for (final String file : mPushedFiles) {
                device.deleteFile(file);
            }
            for (final String packageName : mInstalledPackages) {
                device.uninstallPackage(packageName);
            }
            device.reboot();
        } catch (DeviceNotAvailableException e) {
            Assert.fail(e.toString());
        }
    }

    interface DeviceProvider {
        ITestDevice getDevice();
    }
}
