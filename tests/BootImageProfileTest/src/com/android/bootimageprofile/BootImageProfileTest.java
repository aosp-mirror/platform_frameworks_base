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

    @Override
    public void setDevice(ITestDevice testDevice) {
        mTestDevice = testDevice;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }

    /**
     * Test that the boot image profile properties are set.
     */
    @Test
    public void testProperties() throws Exception {
        String res = mTestDevice.getProperty("dalvik.vm.profilebootclasspath");
        assertTrue("profile boot class path not enabled", res != null && res.equals("true"));
        res = mTestDevice.getProperty("dalvik.vm.profilesystemserver");
        assertTrue("profile system server not enabled", res != null && res.equals("true"));
    }

    private void forceSaveProfile(String pkg) throws Exception {
        String pid = mTestDevice.executeShellCommand("pidof " + pkg).trim();
        assertTrue("Invalid pid " + pid, pid.length() > 0);
        String res = mTestDevice.executeShellCommand("kill -s SIGUSR1 " + pid).trim();
        assertTrue("kill SIGUSR1: " + res, res.length() == 0);
    }

    @Test
    public void testSystemServerProfile() throws Exception {
        // Trunacte the profile before force it to be saved to prevent previous profiles
        // causing the test to pass.
        String res;
        res = mTestDevice.executeShellCommand("truncate -s 0 " + SYSTEM_SERVER_PROFILE).trim();
        assertTrue(res, res.length() == 0);
        // Wait up to 20 seconds for the profile to be saved.
        for (int i = 0; i < 20; ++i) {
            // Force save the profile since we truncated it.
            forceSaveProfile("system_server");
            String s = mTestDevice.executeShellCommand("wc -c <" + SYSTEM_SERVER_PROFILE).trim();
            if (!"0".equals(s)) {
                break;
            }
            Thread.sleep(1000);
        }
        // In case the profile is partially saved, wait an extra second.
        Thread.sleep(1000);
        // Validate that the profile is non empty.
        res = mTestDevice.executeShellCommand("profman --dump-only --profile-file="
                + SYSTEM_SERVER_PROFILE);
        boolean sawFramework = false;
        boolean sawServices = false;
        for (String line : res.split("\n")) {
            if (line.contains("framework.jar")) {
                sawFramework = true;
            } else if (line.contains("services.jar")) {
                sawServices = true;
            }
        }
        assertTrue("Did not see framework.jar in " + res, sawFramework);
        assertTrue("Did not see services.jar in " + res, sawServices);
    }
}
