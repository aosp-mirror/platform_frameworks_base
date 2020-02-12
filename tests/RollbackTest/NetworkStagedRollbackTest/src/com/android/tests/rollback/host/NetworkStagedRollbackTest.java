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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import com.android.tradefed.device.LogcatReceiver;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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

    private LogcatReceiver mReceiver;

    @Before
    public void setUp() throws Exception {
        mReceiver =  new LogcatReceiver(getDevice(), "logcat -s WatchdogRollbackLogger",
                getDevice().getOptions().getMaxLogcatDataSize(), 0);
        mReceiver.start();
    }

    @After
    public void tearDown() throws Exception {
        mReceiver.stop();
        mReceiver.clear();
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
            InputStreamSource logcatStream = mReceiver.getLogcatData();
            try {
                List<String> watchdogEvents = getWatchdogLoggingEvents(logcatStream);
                assertTrue(watchdogEventOccurred(watchdogEvents, ROLLBACK_INITIATE, null,
                        REASON_EXPLICIT_HEALTH_CHECK, null));
                assertTrue(watchdogEventOccurred(watchdogEvents, ROLLBACK_BOOT_TRIGGERED, null,
                        null, null));
            } finally {
                logcatStream.close();
            }
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
        InputStreamSource logcatStream = mReceiver.getLogcatData();
        try {
            List<String> watchdogEvents = getWatchdogLoggingEvents(logcatStream);
            assertEquals(watchdogEventOccurred(watchdogEvents, null, null,
                    REASON_EXPLICIT_HEALTH_CHECK, null), false);
        } finally {
            logcatStream.close();
        }
    }

    /**
     * Returns a list of all Watchdog logging events which have occurred.
     */
    private List<String> getWatchdogLoggingEvents(InputStreamSource inputStreamSource)
            throws Exception {
        List<String> watchdogEvents = new ArrayList<>();
        InputStream inputStream = inputStreamSource.createInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("Watchdog event occurred")) {
                watchdogEvents.add(line);
            }
        }
        return watchdogEvents;
    }

    /**
     * Returns whether a Watchdog event has occurred that matches the given criteria.
     *
     * Check the value of all non-null parameters against the list of Watchdog events that have
     * occurred, and return {@code true} if an event exists which matches all criteria.
     */
    private boolean watchdogEventOccurred(List<String> loggingEvents,
            String type, String logPackage,
            String rollbackReason, String failedPackageName) throws Exception {
        List<String> eventCriteria = new ArrayList<>();
        if (type != null) {
            eventCriteria.add("type: " + type);
        }
        if (logPackage != null) {
            eventCriteria.add("logPackage: " + logPackage);
        }
        if (rollbackReason != null) {
            eventCriteria.add("rollbackReason: " + rollbackReason);
        }
        if (failedPackageName != null) {
            eventCriteria.add("failedPackageName: " + failedPackageName);
        }
        for (String loggingEvent: loggingEvents) {
            boolean matchesCriteria = true;
            for (String criterion: eventCriteria) {
                if (!loggingEvent.contains(criterion)) {
                    matchesCriteria = false;
                }
            }
            if (matchesCriteria) {
                return true;
            }
        }
        return false;
    }
}
