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
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Truth;

import static com.google.common.truth.Truth.assertThat;

public class WatchdogEventLogger {
    private static final String[] ROLLBACK_EVENT_TYPES = {
            "ROLLBACK_INITIATE", "ROLLBACK_BOOT_TRIGGERED", "ROLLBACK_SUCCESS"};
    private static final String[] ROLLBACK_EVENT_ATTRS = {
            "logPackage", "rollbackReason", "failedPackageName"};
    private static final String PROP_PREFIX = "persist.sys.rollbacktest.";

    private ITestDevice mDevice;

    private void resetProperties(boolean enabled) throws Exception {
        try {
            mDevice.enableAdbRoot();
            assertThat(mDevice.setProperty(
                    PROP_PREFIX + "enabled", String.valueOf(enabled))).isTrue();
            for (String type : ROLLBACK_EVENT_TYPES) {
                String key = PROP_PREFIX + type;
                assertThat(mDevice.setProperty(key, "")).isTrue();
                for (String attr : ROLLBACK_EVENT_ATTRS) {
                    assertThat(mDevice.setProperty(key + "." + attr, "")).isTrue();
                }
            }
        } finally {
            mDevice.disableAdbRoot();
        }
    }

    public void start(ITestDevice device) throws Exception {
        mDevice = device;
        resetProperties(true);
    }

    public void stop() throws Exception {
        if (mDevice != null) {
            resetProperties(false);
        }
    }

    private boolean matchProperty(String type, String attr, String expectedVal) throws Exception {
        String key = PROP_PREFIX + type + "." + attr;
        String val = mDevice.getProperty(key);
        return expectedVal == null || expectedVal.equals(val);
    }

    /**
     * Returns whether a Watchdog event has occurred that matches the given criteria.
     *
     * Check the value of all non-null parameters against the list of Watchdog events that have
     * occurred, and return {@code true} if an event exists which matches all criteria.
     */
    public boolean watchdogEventOccurred(String type, String logPackage,
            String rollbackReason, String failedPackageName) throws Exception {
        return mDevice.getBooleanProperty(PROP_PREFIX + type, false)
                && matchProperty(type, "logPackage", logPackage)
                && matchProperty(type, "rollbackReason", rollbackReason)
                && matchProperty(type, "failedPackageName", failedPackageName);
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
                String failedPackageName) throws Exception {
            check("watchdogEventOccurred(type=%s, logPackage=%s, rollbackReason=%s, "
                    + "failedPackageName=%s)", type, logPackage, rollbackReason, failedPackageName)
                    .that(mActual.watchdogEventOccurred(type, logPackage, rollbackReason,
                            failedPackageName)).isTrue();
        }
    }
}
