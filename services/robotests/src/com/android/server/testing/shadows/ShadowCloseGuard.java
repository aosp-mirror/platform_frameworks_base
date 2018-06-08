/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import dalvik.system.CloseGuard;

import org.robolectric.annotation.Implements;

@Implements(CloseGuard.class)
public class ShadowCloseGuard {
    private static final Reporter REPORTER = new Reporter();

    public static boolean hasReported() {
        return REPORTER.mReports > 0;
    }

    public static void setUp() {
        // Can't do this in static {} block because shadow initialization is part of real class
        // initialization and it happens right in the beginning. When the shadow is being
        // initialized the class hasn't been initialized yet and it will be after the shadow. So,
        // REPORTER field (inside CloseGuard) will be assigned *after* setReporter() is called.
        CloseGuard.setReporter(REPORTER);
        REPORTER.mReports = 0;
    }

    private static class Reporter implements CloseGuard.Reporter {
        private int mReports = 0;

        @Override
        public void report(String message, Throwable allocationSite) {
            mReports += 1;
        }
    }
}
