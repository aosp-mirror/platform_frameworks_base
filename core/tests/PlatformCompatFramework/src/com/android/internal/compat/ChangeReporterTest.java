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

package com.android.internal.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChangeReporterTest {
    @Test
    public void testStatsLogOnce() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022, otherUid = 1023;
        long myChangeId = 500L, otherChangeId = 600L;
        int myState = ChangeReporter.STATE_ENABLED, otherState = ChangeReporter.STATE_DISABLED;

        assertTrue(reporter.shouldWriteToStatsLog(myUid, myChangeId, myState));
        reporter.reportChange(myUid, myChangeId, myState);

        // Same report will not be logged again.
        assertFalse(reporter.shouldWriteToStatsLog(myUid, myChangeId, myState));
        // Other reports will be logged.
        assertTrue(reporter.shouldWriteToStatsLog(otherUid, myChangeId, myState));
        assertTrue(reporter.shouldWriteToStatsLog(myUid, otherChangeId, myState));
        assertTrue(reporter.shouldWriteToStatsLog(myUid, myChangeId, otherState));
    }

    @Test
    public void testStatsLogAfterReset() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myState = ChangeReporter.STATE_ENABLED;

        assertTrue(reporter.shouldWriteToStatsLog(myUid, myChangeId, myState));
        reporter.reportChange(myUid, myChangeId, myState);

        // Same report will not be logged again.
        assertFalse(reporter.shouldWriteToStatsLog(myUid, myChangeId, myState));
        reporter.resetReportedChanges(myUid);

        // Same report will be logged again after reset.
        assertTrue(reporter.shouldWriteToStatsLog(myUid, myChangeId, myState));
    }

    @Test
    public void testDebugLogOnce() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022, otherUid = 1023;
        long myChangeId = 500L, otherChangeId = 600L;
        int myState = ChangeReporter.STATE_ENABLED, otherState = ChangeReporter.STATE_DISABLED;

        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
        reporter.reportChange(myUid, myChangeId, myState);

        // Same report will not be logged again.
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
        // Other reports will be logged.
        assertTrue(reporter.shouldWriteToDebug(otherUid, myChangeId, myState));
        assertTrue(reporter.shouldWriteToDebug(myUid, otherChangeId, myState));
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, otherState));
    }

    @Test
    public void testDebugLogAfterReset() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myState = ChangeReporter.STATE_ENABLED;

        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
        reporter.reportChange(myUid, myChangeId, myState);

        // Same report will not be logged again.
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
        reporter.resetReportedChanges(myUid);

        // Same report will be logged again after reset.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
    }

    @Test
    public void testDebugLogWithLogAll() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myState = ChangeReporter.STATE_ENABLED;

        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
        reporter.reportChange(myUid, myChangeId, myState);

        reporter.startDebugLogAll();
        // Same report will be logged again.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myState));

        reporter.stopDebugLogAll();
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myState));
    }
}
