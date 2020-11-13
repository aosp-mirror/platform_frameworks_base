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

package com.android.tests.stagedinstallinternal.host;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import com.android.ddmlib.Log;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.ProcessInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedInstallInternalTest extends BaseHostJUnit4Test {

    private static final String TAG = StagedInstallInternalTest.class.getSimpleName();
    private static final long SYSTEM_SERVER_TIMEOUT_MS = 60 * 1000;
    private boolean mWasRoot = false;

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testApkOnlyEnableRollback");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.stagedinstallinternal",
                "com.android.tests.stagedinstallinternal.StagedInstallInternalTest",
                phase));
    }

    // We do not assert the success of cleanup phase since it might fail due to flaky reasons.
    private void cleanUp() throws Exception {
        try {
            runDeviceTests("com.android.tests.stagedinstallinternal",
                    "com.android.tests.stagedinstallinternal.StagedInstallInternalTest",
                    "cleanUp");
        } catch (AssertionError e) {
            Log.e(TAG, e);
        }
    }

    @Before
    public void setUp() throws Exception {
        mWasRoot = getDevice().isAdbRoot();
        if (!mWasRoot) {
            getDevice().enableAdbRoot();
        }
        cleanUp();
        // Abandon all staged sessions
        getDevice().executeShellCommand("pm install-abandon $(pm get-stagedsessions --only-ready "
                + "--only-parent --only-sessionid)");
    }

    @After
    public void tearDown() throws Exception {
        if (!mWasRoot) {
            getDevice().disableAdbRoot();
        }
        cleanUp();
    }

    @Test
    public void testSystemServerRestartDoesNotAffectStagedSessions() throws Exception {
        runPhase("testSystemServerRestartDoesNotAffectStagedSessions_Commit");
        restartSystemServer();
        runPhase("testSystemServerRestartDoesNotAffectStagedSessions_Verify");
    }

    @Test
    public void testStagedInstallationShouldCleanUpOnValidationFailure() throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testStagedInstallationShouldCleanUpOnValidationFailure");
        List<String> after = getStagingDirectories();
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void testStagedInstallationShouldCleanUpOnValidationFailureMultiPackage()
            throws Exception {
        List<String> before = getStagingDirectories();
        runPhase("testStagedInstallationShouldCleanUpOnValidationFailureMultiPackage");
        List<String> after = getStagingDirectories();
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void testOrphanedStagingDirectoryGetsCleanedUpOnReboot() throws Exception {
        //create random directories in /data/app-staging folder
        getDevice().enableAdbRoot();
        getDevice().executeShellCommand("mkdir /data/app-staging/session_123");
        getDevice().executeShellCommand("mkdir /data/app-staging/random_name");
        getDevice().disableAdbRoot();

        assertThat(getStagingDirectories()).isNotEmpty();
        getDevice().reboot();
        assertThat(getStagingDirectories()).isEmpty();
    }

    private List<String> getStagingDirectories() throws DeviceNotAvailableException {
        String baseDir = "/data/app-staging";
        try {
            getDevice().enableAdbRoot();
            return getDevice().getFileEntry(baseDir).getChildren(false)
                    .stream().filter(entry -> entry.getName().matches("session_\\d+"))
                    .map(entry -> entry.getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // Return an empty list if any error
            return Collections.EMPTY_LIST;
        } finally {
            getDevice().disableAdbRoot();
        }
    }

    private void restartSystemServer() throws Exception {
        // Restart the system server
        long oldStartTime = getDevice().getProcessByName("system_server").getStartTime();
        assertThat(getDevice().executeShellCommand("am restart")).contains("Restart the system");

        // Wait for new system server process to start
        long start = System.currentTimeMillis();
        long newStartTime = oldStartTime;
        while (System.currentTimeMillis() < start + SYSTEM_SERVER_TIMEOUT_MS) {
            ProcessInfo newPs = getDevice().getProcessByName("system_server");
            if (newPs != null) {
                newStartTime = newPs.getStartTime();
                if (newStartTime != oldStartTime) {
                    break;
                }
            }
            Thread.sleep(500);
        }
        assertThat(newStartTime).isNotEqualTo(oldStartTime);
        getDevice().waitForDeviceAvailable();
    }
}
