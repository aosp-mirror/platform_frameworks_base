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

package com.android.bootimageprofile;

import static org.junit.Assert.assertTrue;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class BootImageProfileTest implements IDeviceTest {
    private ITestDevice mTestDevice;
    private static final String SYSTEM_SERVER_PROFILE =
            "/data/misc/profiles/cur/0/android/primary.prof";
    private static final boolean USE_PHENOTYPE = false;

    @Override
    public void setDevice(ITestDevice testDevice) {
        mTestDevice = testDevice;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    private String getProperty(String property) throws Exception {
        if (USE_PHENOTYPE) {
            return mTestDevice.getProperty("persist.device_config.runtime_native_boot."
                    + property);
        } else {
            return mTestDevice.executeShellCommand("getprop dalvik.vm." + property).trim();
        }
    }

    private String setProperty(String property, String value) throws Exception {
        if (USE_PHENOTYPE) {
            return mTestDevice.executeShellCommand(
                "device_config put runtime_native_boot " + property + " " + value);
        } else {
            return mTestDevice.executeShellCommand(
                "setprop dalvik.vm." + property + " " + value);
        }
    }

    /**
     * Validate that the boot image profile properties are set.
     */
    public void validateProperties() throws Exception {
        String res = getProperty("profilebootclasspath");
        assertTrue("profile boot class path not enabled: " + res, "true".equals(res));
        res = getProperty("profilesystemserver");
        assertTrue("profile system server not enabled: " + res, "true".equals(res));
    }

    private boolean forceSaveProfile(String pkg) throws Exception {
        String pid = mTestDevice.executeShellCommand("pidof " + pkg).trim();
        if (pid.length() == 0) {
            // Not yet running.
            return false;
        }
        String res = mTestDevice.executeShellCommand("kill -s SIGUSR1 " + pid).trim();
        return res.length() == 0;
    }

    @Test
    public void testSystemServerProfile() throws Exception {
        final int numIterations = 30;
        String res;
        // Set properties and wait for them to be readable.
        for (int i = 1; i <= numIterations; ++i) {
            String pbcp = getProperty("profilebootclasspath");
            boolean profileBootClassPath = "true".equals(pbcp);
            String pss = getProperty("profilesystemserver");
            boolean profileSystemServer = "true".equals(pss);
            if (profileBootClassPath && profileSystemServer) {
                break;
            }
            if (i == numIterations) {
                assertTrue("profile system server not enabled: " + pss, profileSystemServer);
                assertTrue("profile boot class path not enabled: " + pbcp, profileBootClassPath);
            }

            setProperty("profilebootclasspath", "true");
            setProperty("profilesystemserver", "true");
            Thread.sleep(1000);
        }

        // Restart shell and wait for system boot.
        res = mTestDevice.executeShellCommand("stop");
        assertTrue("stop shell: " + res, res.length() == 0);
        res = mTestDevice.executeShellCommand("start");
        assertTrue("start shell: " + res, res.length() == 0);
        for (int i = 1; i <= numIterations; ++i) {
            String pbcp = getProperty("profilebootclasspath");
            boolean profileBootClassPath = "true".equals(pbcp);
            String pss = getProperty("profilesystemserver");
            boolean profileSystemServer = "true".equals(pss);
            if (profileBootClassPath && profileSystemServer) {
                break;
            }
            if (i == numIterations) {
                assertTrue("profile system server not enabled: " + pss, profileSystemServer);
                assertTrue("profile boot class path not enabled: " + pbcp, profileBootClassPath);
            }
            Thread.sleep(1000);
        }

        // Trunacte the profile before force it to be saved to prevent previous profiles
        // causing the test to pass.
        res = mTestDevice.executeShellCommand("truncate -s 0 " + SYSTEM_SERVER_PROFILE).trim();
        assertTrue(res, res.length() == 0);
        // Wait up to 20 seconds for the profile to be saved.
        for (int i = 1; i <= numIterations; ++i) {
            // Force save the profile since we truncated it.
            if (forceSaveProfile("system_server")) {
                // Might fail if system server is not yet running.
                String s = mTestDevice.executeShellCommand(
                        "wc -c <" + SYSTEM_SERVER_PROFILE).trim();
                if ("0".equals(s)) {
                    Thread.sleep(1000);
                    continue;
                }
            }

            // In case the profile is partially saved, wait an extra second.
            Thread.sleep(1000);

            // Validate that properties are still set.
            validateProperties();

            // Validate that the profile is non empty.
            res = mTestDevice.executeShellCommand("profman --dump-only --profile-file="
                    + SYSTEM_SERVER_PROFILE);
            boolean sawFramework = false;
            boolean sawServices = false;
            for (String line : res.split("\n")) {
                if (line.contains("framework.jar")) {
                    sawFramework = true;
                } else if (line.contains("framework-minus-apex.jar")) {
                    sawFramework = true;
                } else if (line.contains("services.jar")) {
                    sawServices = true;
                }
            }
            if (i == numIterations) {
                // Only assert for last iteration since there are race conditions where the package
                // manager might not be started whewn the profile saves.
                assertTrue("Did not see framework.jar in " + res, sawFramework);
                assertTrue("Did not see services.jar in " + res, sawServices);
            }

            // Test the profile contents contain common methods for core-oj that would normally be
            // AOT compiled. Also test that services.jar has PackageManagerService.<init> since the
            // package manager service should always be created during boot.
            res = mTestDevice.executeShellCommand(
                    "profman --dump-classes-and-methods --profile-file="
                    + SYSTEM_SERVER_PROFILE + " --apk=/apex/com.android.art/javalib/core-oj.jar"
                    + " --apk=/system/framework/services.jar");
            boolean sawObjectInit = false;
            boolean sawPmInit = false;
            for (String line : res.split("\n")) {
                if (line.contains("Ljava/lang/Object;-><init>()V")) {
                    sawObjectInit = true;
                } else if (line.contains("Lcom/android/server/pm/PackageManagerService;-><init>")) {
                    sawPmInit = true;
                }
            }
            if (i == numIterations) {
                assertTrue("Did not see Object.<init> in " + res, sawObjectInit);
                assertTrue("Did not see PackageManagerService.<init> in " + res, sawPmInit);
            }

            if (sawFramework && sawServices && sawObjectInit && sawPmInit) {
                break;  // Asserts passed, exit.
            }
        }
    }
}
