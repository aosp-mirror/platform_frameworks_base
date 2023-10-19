/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.app.procstats;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.ActivityManager;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FrameworkStatsLog;

import junit.framework.TestCase;

import org.junit.Before;
import org.mockito.Mock;

import java.util.concurrent.TimeUnit;

/** Provides test cases for ProcessStats. */
public class ProcessStatsTest extends TestCase {

    private static final String APP_1_PACKAGE_NAME = "com.android.testapp";
    private static final int APP_1_UID = 5001;
    private static final long APP_1_VERSION = 10;
    private static final String APP_1_PROCESS_NAME = "com.android.testapp.p";
    private static final String APP_1_SERVICE_NAME = "com.android.testapp.service";

    private static final String APP_2_PACKAGE_NAME = "com.android.testapp2";
    private static final int APP_2_UID = 5002;
    private static final long APP_2_VERSION = 30;
    private static final String APP_2_PROCESS_NAME = "com.android.testapp2.p";

    private static final long NOW_MS = 123000;
    private static final int DURATION_SECS = 6;

    @Mock StatsEventOutput mStatsEventOutput;

    @Before
    public void setUp() {
        initMocks(this);
    }

    @SmallTest
    public void testDumpProcessState() throws Exception {
        ProcessStats processStats = new ProcessStats();
        processStats.getProcessStateLocked(
                APP_1_PACKAGE_NAME, APP_1_UID, APP_1_VERSION, APP_1_PROCESS_NAME);
        processStats.getProcessStateLocked(
                APP_2_PACKAGE_NAME, APP_2_UID, APP_2_VERSION, APP_2_PROCESS_NAME);
        processStats.dumpProcessState(FrameworkStatsLog.PROCESS_STATE, mStatsEventOutput);
        verify(mStatsEventOutput)
                .write(
                        eq(FrameworkStatsLog.PROCESS_STATE),
                        eq(APP_1_UID),
                        eq(APP_1_PROCESS_NAME),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0));
        verify(mStatsEventOutput)
                .write(
                        eq(FrameworkStatsLog.PROCESS_STATE),
                        eq(APP_2_UID),
                        eq(APP_2_PROCESS_NAME),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0));
    }

    @SmallTest
    public void testNonZeroProcessStateDuration() throws Exception {
        ProcessStats processStats = new ProcessStats();
        ProcessState processState =
                processStats.getProcessStateLocked(
                        APP_1_PACKAGE_NAME, APP_1_UID, APP_1_VERSION, APP_1_PROCESS_NAME);
        processState.setState(ActivityManager.PROCESS_STATE_TOP, ProcessStats.ADJ_MEM_FACTOR_NORMAL,
                NOW_MS, /* pkgList */ null);
        processState.commitStateTime(NOW_MS + TimeUnit.SECONDS.toMillis(DURATION_SECS));
        processStats.dumpProcessState(FrameworkStatsLog.PROCESS_STATE, mStatsEventOutput);
        verify(mStatsEventOutput)
                .write(
                        eq(FrameworkStatsLog.PROCESS_STATE),
                        eq(APP_1_UID),
                        eq(APP_1_PROCESS_NAME),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(DURATION_SECS),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0));
    }

    @SmallTest
    public void testDumpBoundFgsDuration() throws Exception {
        ProcessStats processStats = new ProcessStats();
        ProcessState processState =
                processStats.getProcessStateLocked(
                        APP_1_PACKAGE_NAME, APP_1_UID, APP_1_VERSION, APP_1_PROCESS_NAME);
        processState.setState(ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
                ProcessStats.ADJ_MEM_FACTOR_NORMAL, NOW_MS, /* pkgList */ null);
        processState.commitStateTime(NOW_MS + TimeUnit.SECONDS.toMillis(DURATION_SECS));
        processStats.dumpProcessState(FrameworkStatsLog.PROCESS_STATE, mStatsEventOutput);
        verify(mStatsEventOutput)
                .write(
                        eq(FrameworkStatsLog.PROCESS_STATE),
                        eq(APP_1_UID),
                        eq(APP_1_PROCESS_NAME),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(DURATION_SECS),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0));
    }

    @SmallTest
    public void testDumpFrozenDuration() throws Exception {
        ProcessStats processStats = new ProcessStats();
        ProcessState processState =
                processStats.getProcessStateLocked(
                        APP_1_PACKAGE_NAME, APP_1_UID, APP_1_VERSION, APP_1_PROCESS_NAME);
        processState.setState(ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
                ProcessStats.ADJ_MEM_FACTOR_NORMAL, NOW_MS, /* pkgList */ null);
        processState.onProcessFrozen(NOW_MS   + 1 * TimeUnit.SECONDS.toMillis(DURATION_SECS),
            /* pkgList */ null);
        processState.onProcessUnfrozen(NOW_MS + 2 * TimeUnit.SECONDS.toMillis(DURATION_SECS),
            /* pkgList */ null);
        processState.commitStateTime(NOW_MS   + 3 * TimeUnit.SECONDS.toMillis(DURATION_SECS));
        processStats.dumpProcessState(FrameworkStatsLog.PROCESS_STATE, mStatsEventOutput);
        verify(mStatsEventOutput)
                .write(
                        eq(FrameworkStatsLog.PROCESS_STATE),
                        eq(APP_1_UID),
                        eq(APP_1_PROCESS_NAME),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(2 * DURATION_SECS),  // bound_fgs
                        eq(0),
                        eq(0),
                        eq(DURATION_SECS),  // frozen
                        eq(0));
    }

    @SmallTest
    public void testDumpProcessAssociation() throws Exception {
        ProcessStats processStats = new ProcessStats();
        AssociationState associationState =
                processStats.getAssociationStateLocked(
                        APP_1_PACKAGE_NAME,
                        APP_1_UID,
                        APP_1_VERSION,
                        APP_1_PROCESS_NAME,
                        APP_1_SERVICE_NAME);
        AssociationState.SourceState sourceState =
                associationState.startSource(APP_2_UID, APP_2_PROCESS_NAME, APP_2_PACKAGE_NAME);
        sourceState.stop();
        processStats.dumpProcessAssociation(
                FrameworkStatsLog.PROCESS_ASSOCIATION, mStatsEventOutput);
        verify(mStatsEventOutput)
                .write(
                        eq(FrameworkStatsLog.PROCESS_ASSOCIATION),
                        eq(APP_2_UID),
                        eq(APP_2_PROCESS_NAME),
                        eq(APP_1_UID),
                        eq(APP_1_SERVICE_NAME),
                        anyInt(),
                        anyInt(),
                        eq(0),
                        eq(0),
                        eq(0),
                        eq(APP_1_PROCESS_NAME));
    }

    @SmallTest
    public void testSafelyResetClearsProcessInUidState() throws Exception {
        ProcessStats processStats = new ProcessStats();
        ProcessState processState =
                processStats.getProcessStateLocked(
                        APP_1_PACKAGE_NAME, APP_1_UID, APP_1_VERSION, APP_1_PROCESS_NAME);
        processState.makeActive();
        UidState uidState = processStats.mUidStates.get(APP_1_UID);
        assertTrue(uidState.isInUse());
        processState.makeInactive();
        uidState.resetSafely(NOW_MS);
        processState.makeActive();
        assertFalse(uidState.isInUse());
    }
}
