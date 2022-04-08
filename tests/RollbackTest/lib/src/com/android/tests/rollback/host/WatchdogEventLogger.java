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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.LogcatReceiver;
import com.android.tradefed.result.InputStreamSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class WatchdogEventLogger {
    private LogcatReceiver mReceiver;

    public void start(ITestDevice device) {
        mReceiver =  new LogcatReceiver(device, "logcat -s WatchdogRollbackLogger",
                device.getOptions().getMaxLogcatDataSize(), 0);
        mReceiver.start();
    }

    public void stop() {
        if (mReceiver != null) {
            mReceiver.stop();
            mReceiver.clear();
        }
    }

    /**
     * Returns a list of all Watchdog logging events which have occurred.
     */
    public List<String> getWatchdogLoggingEvents() throws Exception {
        try (InputStreamSource logcatStream = mReceiver.getLogcatData()) {
            return getWatchdogLoggingEvents(logcatStream);
        }
    }

    private static List<String> getWatchdogLoggingEvents(InputStreamSource inputStreamSource)
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
    public static boolean watchdogEventOccurred(List<String> loggingEvents,
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
