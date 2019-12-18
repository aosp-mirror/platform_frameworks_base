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

package com.android.tests.rollback.host;

import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Runs the staged rollback tests.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedRollbackTest extends BaseHostJUnit4Test {
    private static final int NATIVE_CRASHES_THRESHOLD = 5;

    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testApkOnlyEnableRollback");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.rollback",
                    "com.android.tests.rollback.StagedRollbackTest",
                    phase));
    }

    @Before
    public void setUp() throws Exception {
        getDevice().reboot();
    }

    /**
     * Tests watchdog triggered staged rollbacks involving only apks.
     */
    @Test
    public void testBadApkOnly() throws Exception {
        runPhase("testBadApkOnly_Phase1");
        getDevice().reboot();
        runPhase("testBadApkOnly_Phase2");

        assertThrows(AssertionError.class, () -> runPhase("testBadApkOnly_Phase3"));
        getDevice().waitForDeviceAvailable();

        runPhase("testBadApkOnly_Phase4");
    }

    @Test
    public void testNativeWatchdogTriggersRollback() throws Exception {
        //Stage install ModuleMetadata package - this simulates a Mainline module update
        runPhase("testNativeWatchdogTriggersRollback_Phase1");

        // Reboot device to activate staged package
        getDevice().reboot();

        runPhase("testNativeWatchdogTriggersRollback_Phase2");

        // crash system_server enough times to trigger a rollback
        crashProcess("system_server", NATIVE_CRASHES_THRESHOLD);

        // Rollback should be committed automatically now.
        // Give time for rollback to be committed. This could take a while,
        // because we need all of the following to happen:
        // 1. system_server comes back up and boot completes.
        // 2. Rollback health observer detects updatable crashing signal.
        // 3. Staged rollback session becomes ready.
        // 4. Device actually reboots.
        // So we give a generous timeout here.
        assertTrue(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5)));
        getDevice().waitForDeviceAvailable();

        // verify rollback committed
        runPhase("testNativeWatchdogTriggersRollback_Phase3");
    }

    /**
     * Tests failed network health check triggers watchdog staged rollbacks.
     */
    @Test
    public void testNetworkFailedRollback() throws Exception {
        try {
            // Disconnect internet so we can test network health triggered rollbacks
            getDevice().executeShellCommand("svc wifi disable");
            getDevice().executeShellCommand("svc data disable");

            runPhase("testNetworkFailedRollback_Phase1");
            // Reboot device to activate staged package
            getDevice().reboot();

            // Verify rollback was enabled
            runPhase("testNetworkFailedRollback_Phase2");
            assertThrows(AssertionError.class, () -> runPhase("testNetworkFailedRollback_Phase3"));

            getDevice().waitForDeviceAvailable();
            // Verify rollback was executed after health check deadline
            runPhase("testNetworkFailedRollback_Phase4");
        } finally {
            // Reconnect internet again so we won't break tests which assume internet available
            getDevice().executeShellCommand("svc wifi enable");
            getDevice().executeShellCommand("svc data enable");
        }
    }

    /**
     * Tests passed network health check does not trigger watchdog staged rollbacks.
     */
    @Test
    @Ignore("b/143514090")
    public void testNetworkPassedDoesNotRollback() throws Exception {
        // Remove available rollbacks and uninstall NetworkStack on /data/
        runPhase("testNetworkPassedDoesNotRollback_Phase1");
        // Reduce health check deadline, here unlike the network failed case, we use
        // a longer deadline because joining a network can take a much longer time for
        // reasons external to the device than 'not joining'
        getDevice().executeShellCommand("device_config put rollback "
                + "watchdog_request_timeout_millis 300000");
        // Simulate re-installation of new NetworkStack with rollbacks enabled
        getDevice().executeShellCommand("pm install -r --staged --enable-rollback "
                + getNetworkStackPath());

        // Sleep to allow writes to disk before reboot
        Thread.sleep(5000);
        // Reboot device to activate staged package
        getDevice().reboot();

        // Verify rollback was enabled
        runPhase("testNetworkPassedDoesNotRollback_Phase2");

        // Connect to internet so network health check passes
        getDevice().executeShellCommand("svc wifi enable");
        getDevice().executeShellCommand("svc data enable");

        // Wait for device available because emulator device may restart after turning
        // on mobile data
        getDevice().waitForDeviceAvailable();

        // Sleep for > health check deadline
        Thread.sleep(310000);
        // Verify rollback was not executed after health check deadline
        runPhase("testNetworkPassedDoesNotRollback_Phase3");
    }

    /**
     * Tests rolling back user data where there are multiple rollbacks for that package.
     */
    @Test
    public void testPreviouslyAbandonedRollbacks() throws Exception {
        runPhase("testPreviouslyAbandonedRollbacks_Phase1");
        getDevice().reboot();
        runPhase("testPreviouslyAbandonedRollbacks_Phase2");
        getDevice().reboot();
        runPhase("testPreviouslyAbandonedRollbacks_Phase3");
    }

    /**
     * Tests we can enable rollback for a whitelisted app.
     */
    @Test
    public void testRollbackWhitelistedApp() throws Exception {
        runPhase("testRollbackWhitelistedApp_Phase1");
        getDevice().reboot();
        runPhase("testRollbackWhitelistedApp_Phase2");
    }

    private void crashProcess(String processName, int numberOfCrashes) throws Exception {
        String pid = "";
        String lastPid = "invalid";
        for (int i = 0; i < numberOfCrashes; ++i) {
            // This condition makes sure before we kill the process, the process is running AND
            // the last crash was finished.
            while ("".equals(pid) || lastPid.equals(pid)) {
                pid = getDevice().executeShellCommand("pidof " + processName);
            }
            getDevice().executeShellCommand("kill " + pid);
            lastPid = pid;
        }
    }

    private String getNetworkStackPath() throws Exception {
        // Find the NetworkStack path (can be NetworkStack.apk or NetworkStackNext.apk)
        return getDevice().executeShellCommand("ls /system/priv-app/NetworkStack*/*.apk");
    }
}
