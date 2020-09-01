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

package com.android.tests.rollback.host;

import static com.android.tests.rollback.host.WatchdogEventLogger.watchdogEventOccurred;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the network rollback tests.
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class NetworkStagedRollbackTest extends BaseHostJUnit4Test {
    /**
     * Runs the given phase of a test by calling into the device.
     * Throws an exception if the test phase fails.
     * <p>
     * For example, <code>runPhase("testApkOnlyEnableRollback");</code>
     */
    private void runPhase(String phase) throws Exception {
        assertTrue(runDeviceTests("com.android.tests.rollback",
                "com.android.tests.rollback.NetworkStagedRollbackTest",
                phase));
    }

    private static final String REASON_EXPLICIT_HEALTH_CHECK = "REASON_EXPLICIT_HEALTH_CHECK";

    private static final String ROLLBACK_INITIATE = "ROLLBACK_INITIATE";
    private static final String ROLLBACK_BOOT_TRIGGERED = "ROLLBACK_BOOT_TRIGGERED";
    private static final String ROLLBACK_SUCCESS = "ROLLBACK_SUCCESS";

    private WatchdogEventLogger mLogger = new WatchdogEventLogger();

    @Before
    public void setUp() throws Exception {
        runPhase("cleanUp");
        mLogger.start(getDevice());
    }

    @After
    public void tearDown() throws Exception {
        mLogger.stop();
        runPhase("cleanUp");
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
            // Wait for reboot to happen
            assertTrue(getDevice().waitForDeviceNotAvailable(TimeUnit.MINUTES.toMillis(5)));
            // Wait for reboot to complete and device to become available
            getDevice().waitForDeviceAvailable();
            // Verify rollback was executed after health check deadline
            runPhase("testNetworkFailedRollback_Phase3");

            List<String> watchdogEvents = mLogger.getWatchdogLoggingEvents();
            assertTrue(watchdogEventOccurred(watchdogEvents, ROLLBACK_INITIATE, null,
                    REASON_EXPLICIT_HEALTH_CHECK, null));
            assertTrue(watchdogEventOccurred(watchdogEvents, ROLLBACK_BOOT_TRIGGERED, null,
                    null, null));
            assertTrue(watchdogEventOccurred(watchdogEvents, ROLLBACK_SUCCESS, null, null, null));
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
    public void testNetworkPassedDoesNotRollback() throws Exception {
        runPhase("testNetworkPassedDoesNotRollback_Phase1");
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

        // Verify rollback was not executed after health check deadline
        runPhase("testNetworkPassedDoesNotRollback_Phase3");

        List<String> watchdogEvents = mLogger.getWatchdogLoggingEvents();
        assertEquals(watchdogEventOccurred(watchdogEvents, null, null,
                REASON_EXPLICIT_HEALTH_CHECK, null), false);
    }
}
