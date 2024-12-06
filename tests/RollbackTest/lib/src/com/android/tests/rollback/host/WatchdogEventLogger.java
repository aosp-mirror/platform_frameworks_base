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

import static com.google.common.truth.Truth.assertThat;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Truth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WatchdogEventLogger {

    private ITestDevice mDevice;

    private void updateTestSysProp(boolean enabled) throws Exception {
        try {
            mDevice.enableAdbRoot();
            assertThat(mDevice.setProperty(
                    "persist.sys.rollbacktest.enabled", String.valueOf(enabled))).isTrue();
        } finally {
            mDevice.disableAdbRoot();
        }
    }

    public void start(ITestDevice device) throws Exception {
        mDevice = device;
        updateTestSysProp(true);
    }

    public void stop() throws Exception {
        if (mDevice != null) {
            updateTestSysProp(false);
        }
    }

    private boolean verifyEventContainsVal(String watchdogEvent, String expectedVal) {
        return expectedVal == null || watchdogEvent.contains(expectedVal);
    }

    /**
     * Returns whether a Watchdog event has occurred that matches the given criteria.
     *
     * Check the value of all non-null parameters against the list of Watchdog events that have
     * occurred, and return {@code true} if an event exists which matches all criteria.
     */
    public boolean watchdogEventOccurred(String type, String logPackage,
            String rollbackReason, String failedPackageName) {
        String watchdogEvent = getEventForRollbackType(type);
        return (watchdogEvent != null)
                && verifyEventContainsVal(watchdogEvent, logPackage)
                && verifyEventContainsVal(watchdogEvent, rollbackReason)
                && verifyEventContainsVal(watchdogEvent, failedPackageName);
    }

    /** Returns last matched event for rollbackType **/
    private String getEventForRollbackType(String rollbackType) {
        String lastMatchedEvent = null;
        try {
            String rollbackDump = mDevice.executeShellCommand("dumpsys rollback");
            String eventRegex = ".*%s%s(.*)\\n";
            String eventPrefix = "Watchdog event occurred with type: ";

            final Pattern pattern = Pattern.compile(
                    String.format(eventRegex, eventPrefix, rollbackType));
            final Matcher matcher = pattern.matcher(rollbackDump);
            while (matcher.find()) {
                lastMatchedEvent = matcher.group(1);
            }
            CLog.d("Found watchdogEvent: " + lastMatchedEvent + " for type: " + rollbackType);
        } catch (Exception e) {
            CLog.e("Unable to find event for type: " + rollbackType, e);
        }
        return lastMatchedEvent;
    }

    static class Subject extends com.google.common.truth.Subject {
        private final WatchdogEventLogger mActual;

        private Subject(FailureMetadata failureMetadata, WatchdogEventLogger subject) {
            super(failureMetadata, subject);
            mActual = subject;
        }

        private static com.google.common.truth.Subject.Factory<Subject,
                WatchdogEventLogger> loggers() {
            return Subject::new;
        }

        static Subject assertThat(WatchdogEventLogger actual) {
            return Truth.assertAbout(loggers()).that(actual);
        }

        void eventOccurred(String type, String logPackage, String rollbackReason,
                String failedPackageName) {
            check("watchdogEventOccurred(type=%s, logPackage=%s, rollbackReason=%s, "
                    + "failedPackageName=%s)", type, logPackage, rollbackReason, failedPackageName)
                    .that(mActual.watchdogEventOccurred(type, logPackage, rollbackReason,
                            failedPackageName)).isTrue();
        }
    }
}
