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

import com.android.ddmlib.Log.LogLevel;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs the staged rollback tests.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class StagedRollbackTest extends BaseHostJUnit4Test {
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
        // Disconnect internet so we can test network health triggered rollbacks
        getDevice().executeShellCommand("svc wifi disable");
        getDevice().executeShellCommand("svc data disable");
    }

    @After
    public void tearDown() throws Exception {
        // Reconnect internet after testing network health triggered rollbacks
        getDevice().executeShellCommand("svc wifi enable");
        getDevice().executeShellCommand("svc data enable");
    }

    /**
     * Tests watchdog triggered staged rollbacks involving only apks.
     */
    @Test
    public void testBadApkOnly() throws Exception {
        runPhase("testBadApkOnlyEnableRollback");
        getDevice().reboot();
        runPhase("testBadApkOnlyConfirmEnableRollback");
        try {
            // This is expected to fail due to the device being rebooted out
            // from underneath the test. If this fails for reasons other than
            // the device reboot, those failures should result in failure of
            // the testApkOnlyConfirmRollback phase.
            CLog.logAndDisplay(LogLevel.INFO, "testBadApkOnlyTriggerRollback is expected to fail");
            runPhase("testBadApkOnlyTriggerRollback");
        } catch (AssertionError e) {
            // AssertionError is expected.
        }

        getDevice().waitForDeviceAvailable();

        runPhase("testBadApkOnlyConfirmRollback");
    }

    /**
     * Tests failed network health check triggers watchdog staged rollbacks.
     */
    @Test
    public void testNetworkFailedRollback() throws Exception {
        // Remove available rollbacks and uninstall NetworkStack on /data/
        runPhase("resetNetworkStack");
        // Reduce health check deadline
        getDevice().executeShellCommand("device_config put rollback "
                + "watchdog_request_timeout_millis 300000");
        // Simulate re-installation of new NetworkStack with rollbacks enabled
        getDevice().executeShellCommand("pm install -r --staged --enable-rollback "
                + "/system/priv-app/NetworkStack/NetworkStack.apk");

        // Sleep to allow writes to disk before reboot
        Thread.sleep(5000);
        // Reboot device to activate staged package
        getDevice().reboot();
        getDevice().waitForDeviceAvailable();

        // Verify rollback was enabled
        runPhase("assertNetworkStackRollbackAvailable");

        // Sleep for < health check deadline
        Thread.sleep(5000);
        // Verify rollback was not executed before health check deadline
        runPhase("assertNoNetworkStackRollbackCommitted");
        try {
            // This is expected to fail due to the device being rebooted out
            // from underneath the test. If this fails for reasons other than
            // the device reboot, those failures should result in failure of
            // the assertNetworkStackExecutedRollback phase.
            CLog.logAndDisplay(LogLevel.INFO, "Sleep and expect to fail while sleeping");
            // Sleep for > health check deadline
            Thread.sleep(260000);
        } catch (AssertionError e) {
            // AssertionError is expected.
        }

        getDevice().waitForDeviceAvailable();
        // Verify rollback was executed after health check deadline
        runPhase("assertNetworkStackRollbackCommitted");
    }

    /**
     * Tests passed network health check does not trigger watchdog staged rollbacks.
     */
    @Test
    public void testNetworkPassedDoesNotRollback() throws Exception {
        // Remove available rollbacks and uninstall NetworkStack on /data/
        runPhase("resetNetworkStack");
        // Reduce health check deadline, here unlike the network failed case, we use
        // a longer deadline because joining a network can take a much longer time for
        // reasons external to the device than 'not joining'
        getDevice().executeShellCommand("device_config put rollback "
                + "watchdog_request_timeout_millis 300000");
        // Simulate re-installation of new NetworkStack with rollbacks enabled
        getDevice().executeShellCommand("pm install -r --staged --enable-rollback "
                + "/system/priv-app/NetworkStack/NetworkStack.apk");

        // Sleep to allow writes to disk before reboot
        Thread.sleep(5000);
        // Reboot device to activate staged package
        getDevice().reboot();
        getDevice().waitForDeviceAvailable();

        // Verify rollback was enabled
        runPhase("assertNetworkStackRollbackAvailable");

        // Connect to internet so network health check passes
        getDevice().executeShellCommand("svc wifi enable");
        getDevice().executeShellCommand("svc data enable");

        // Wait for device available because emulator device may restart after turning
        // on mobile data
        getDevice().waitForDeviceAvailable();

        // Sleep for > health check deadline
        Thread.sleep(310000);
        // Verify rollback was not executed after health check deadline
        runPhase("assertNoNetworkStackRollbackCommitted");
    }
}
