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

import android.platform.test.flag.junit.SetFlagsRule;

import com.android.internal.compat.flags.Flags;

import org.junit.Rule;
import org.junit.Test;

public class ChangeReporterTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testStatsLogOnce() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022, otherUid = 1023;
        long myChangeId = 500L, otherChangeId = 600L;
        int myState = ChangeReporter.STATE_ENABLED, otherState = ChangeReporter.STATE_DISABLED;

        assertTrue(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, myChangeId, myState)));
        reporter.reportChange(myUid, myChangeId, myState);

        // Same report will not be logged again.
        assertFalse(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, myChangeId, myState)));
        // Other reports will be logged.
        assertTrue(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(otherUid, myChangeId, myState)));
        assertTrue(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, otherChangeId, myState)));
        assertTrue(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, myChangeId, otherState)));
    }

    @Test
    public void testStatsLogAfterReset() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myState = ChangeReporter.STATE_ENABLED;

        assertTrue(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, myChangeId, myState)));
        reporter.reportChange(myUid, myChangeId, myState);

        // Same report will not be logged again.
        assertFalse(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, myChangeId, myState)));
        reporter.resetReportedChanges(myUid);

        // Same report will be logged again after reset.
        assertTrue(reporter.shouldWriteToStatsLog(false,
                reporter.isAlreadyReported(myUid, myChangeId, myState)));
    }

    @Test
    public void testDebugLogOnce() {
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022, otherUid = 1023;
        long myChangeId = 500L, otherChangeId = 600L;
        int myState = ChangeReporter.STATE_ENABLED, otherState = ChangeReporter.STATE_LOGGED;

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

    @Test
    public void testDebugLogWithFlagOnAndOldSdk() {
        mSetFlagsRule.enableFlags(Flags.FLAG_SKIP_OLD_AND_DISABLED_COMPAT_LOGGING);
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myEnabledState = ChangeReporter.STATE_ENABLED;
        int myDisabledState = ChangeReporter.STATE_DISABLED;

        // Report will not log if target sdk is before the previous version.
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myEnabledState, false));

        reporter.resetReportedChanges(myUid);

        // Report will be logged if target sdk is the latest version.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myEnabledState, true));

        reporter.resetReportedChanges(myUid);

        // If the report is disabled, the sdk version shouldn't matter.
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myDisabledState, true));
    }

    @Test
    public void testDebugLogWithFlagOnAndDisabledChange() {
        mSetFlagsRule.enableFlags(Flags.FLAG_SKIP_OLD_AND_DISABLED_COMPAT_LOGGING);
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myEnabledState = ChangeReporter.STATE_ENABLED;
        int myDisabledState = ChangeReporter.STATE_DISABLED;

        // Report will not log if the change is disabled.
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myDisabledState, true));

        reporter.resetReportedChanges(myUid);

        // Report will be logged if the change is enabled.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myEnabledState, true));

        reporter.resetReportedChanges(myUid);

        // If the report is not the latest version, the disabled state doesn't matter.
        assertFalse(reporter.shouldWriteToDebug(myUid, myChangeId, myEnabledState, false));
    }

    @Test
    public void testDebugLogWithFlagOff() {
        mSetFlagsRule.disableFlags(Flags.FLAG_SKIP_OLD_AND_DISABLED_COMPAT_LOGGING);
        ChangeReporter reporter = new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);
        int myUid = 1022;
        long myChangeId = 500L;
        int myEnabledState = ChangeReporter.STATE_ENABLED;
        int myDisabledState = ChangeReporter.STATE_DISABLED;

        // Report will be logged even if the change is not the latest sdk but the flag is off.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myEnabledState, false));

        reporter.resetReportedChanges(myUid);

        // Report will be logged if the change is enabled and the latest sdk but the flag is off.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myEnabledState, true));

        reporter.resetReportedChanges(myUid);

        // Report will be logged if the change is disabled and the latest sdk but the flag is
        // off.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myDisabledState, true));

        reporter.resetReportedChanges(myUid);

        // Report will be logged if the change is disabled and not the latest sdk but the flag is
        // off.
        assertTrue(reporter.shouldWriteToDebug(myUid, myChangeId, myDisabledState, false));
    }

    @Test
    public void testDontLogSystemApps() {
        // Verify we don't log an app if we know it's a system app when source is system server.
        ChangeReporter systemServerReporter =
                new ChangeReporter(ChangeReporter.SOURCE_SYSTEM_SERVER);

        assertFalse(systemServerReporter.shouldWriteToStatsLog(true, false));
        assertFalse(systemServerReporter.shouldWriteToStatsLog(true, true));

        // Verify we don't log an app if we know it's a system app when source is unknown.
        ChangeReporter unknownReporter =
                new ChangeReporter(ChangeReporter.SOURCE_UNKNOWN_SOURCE);

        assertFalse(unknownReporter.shouldWriteToStatsLog(true, false));
        assertFalse(unknownReporter.shouldWriteToStatsLog(true, true));
    }
}
