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

package com.android.test.stress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test to exercise Android Framework parts related to creating, starting, stopping, and deleting
 * a managed profile as much as possible. The aim is to catch any issues in this code before it
 * affects managed profile CTS tests.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class ManagedProfileLifecycleStressTest extends BaseHostJUnit4Test {
    // Stop the test once this time limit has been reached. 25 minutes used as a limit to make total
    // test time less than 30 minutes, so that it can be put into presubmit.
    private static final int TIME_LIMIT_MINUTES = 25;

    private static final String DUMMY_DPC_APK = "DummyDPC.apk";
    private static final String DUMMY_DPC_COMPONENT =
            "com.android.dummydpc/com.android.dummydpc.DummyDeviceAdminReceiver";
    private static final Pattern CREATE_USER_OUTPUT_REGEX =
            Pattern.compile("Success: created user id (\\d+)");

    /**
     * Create, start, and kill managed profiles in a loop.
     */
    @Test
    public void testCreateStartDelete() throws Exception {
        // Disable package verifier for ADB installs.
        getDevice().executeShellCommand("settings put global verifier_verify_adb_installs 0");
        int iteration = 0;
        final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIME_LIMIT_MINUTES);
        while (System.nanoTime() < deadline) {
            iteration++;
            CLog.w("Iteration N" + iteration);
            final int userId = createManagedProfile();
            startUser(userId);
            installPackageAsUser(DUMMY_DPC_APK, true /* grantPermissions */, userId, "-t");
            setProfileOwner(DUMMY_DPC_COMPONENT, userId);
            removeUser(userId);
        }
        CLog.w("Completed " + iteration + " iterations.");
    }

    private int createManagedProfile() throws Exception {
        final String output = getDevice().executeShellCommand(
                "pm create-user --profileOf 0 --managed TestProfile");
        final Matcher matcher = CREATE_USER_OUTPUT_REGEX.matcher(output.trim());
        if (!matcher.matches() || matcher.groupCount() != 1) {
            fail("user creation failed, output: " + output);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private void setProfileOwner(String componentName, int userId) throws Exception {
        String command = "dpm set-profile-owner --user " + userId + " '" + componentName + "'";
        String commandOutput = getDevice().executeShellCommand(command);
        assertTrue("Unexpected dpm output: " + commandOutput, commandOutput.startsWith("Success:"));
    }

    private void removeUser(int userId) throws Exception {
        final String output = getDevice().executeShellCommand("pm remove-user " + userId).trim();
        assertEquals("Unexpected pm output: " + output, "Success: removed user", output);
    }

    private void startUser(int userId) throws Exception {
        final String output = getDevice().executeShellCommand("am start-user -w " + userId).trim();
        assertEquals("Unexpected am output: " + output, "Success: user started", output);
    }
}
