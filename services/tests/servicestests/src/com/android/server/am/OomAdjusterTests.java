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

package com.android.server.am;

import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.ActivityManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Test class for {@link OomAdjuster}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:OomAdjusterTests
 */
public class OomAdjusterTests {
    private static Context sContext;
    private static ActivityManagerService sService;
    private static PackageManagerInternal sPackageManagerInternal;

    private ProcessRecord mProcessRecord;

    private static final long ZERO = 0L;
    private static final long USAGE_STATS_INTERACTION = 10 * 60 * 1000L;
    private static final long SERVICE_USAGE_INTERACTION = 60 * 1000;

    @SuppressWarnings("GuardedBy")
    @BeforeClass
    public static void setUpOnce() {
        sContext = getInstrumentation().getTargetContext();

        sPackageManagerInternal = mock(PackageManagerInternal.class);
        doReturn(new ComponentName("", "")).when(sPackageManagerInternal)
                .getSystemUiServiceComponent();
        LocalServices.addService(PackageManagerInternal.class, sPackageManagerInternal);

        // We need to run with dexmaker share class loader to make use of
        // ActivityTaskManagerService from wm package.
        runWithDexmakerShareClassLoader(() -> {
            sService = mock(ActivityManagerService.class);
            sService.mActivityTaskManager = new ActivityTaskManagerService(sContext);
            sService.mActivityTaskManager.initialize(null, null, sContext.getMainLooper());
            sService.mAtmInternal = sService.mActivityTaskManager.getAtmInternal();

            setFieldValue(ActivityManagerService.class, sService, "mProcLock",
                    new ActivityManagerProcLock());
            sService.mConstants = new ActivityManagerConstants(sContext, sService,
                    sContext.getMainThreadHandler());
            final AppProfiler profiler = mock(AppProfiler.class);
            setFieldValue(AppProfiler.class, profiler, "mProfilerLock", new Object());
            setFieldValue(ActivityManagerService.class, sService, "mAppProfiler", profiler);
            final OomAdjuster.Injector injector = new OomAdjuster.Injector(){
                @Override
                boolean isChangeEnabled(int changeId, ApplicationInfo app,
                        boolean defaultValue) {
                    return true;
                }
            };
            sService.mProcessStateController = new ProcessStateController.Builder(sService,
                    sService.mProcessList, null)
                    .setOomAdjusterInjector(injector)
                    .build();
            sService.mOomAdjuster = sService.mProcessStateController.getOomAdjuster();
            LocalServices.addService(UsageStatsManagerInternal.class,
                    mock(UsageStatsManagerInternal.class));
            sService.mUsageStatsService = LocalServices.getService(UsageStatsManagerInternal.class);
        });
    }

    private static <T> void setFieldValue(Class clazz, Object obj, String fieldName, T val) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Field mfield = Field.class.getDeclaredField("accessFlags");
            mfield.setAccessible(true);
            mfield.setInt(field, mfield.getInt(field) & ~(Modifier.FINAL | Modifier.PRIVATE));
            field.set(obj, val);
        } catch (NoSuchFieldException | IllegalAccessException e) {
        }
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
    }

    @Before
    public void setUpProcess() {
        // Need to run with dexmaker share class loader to mock package private class.
        runWithDexmakerShareClassLoader(() -> {
            mProcessRecord = spy(new ProcessRecord(sService, sContext.getApplicationInfo(),
                    "name", 12345));
        });

        // Ensure certain services and constants are defined properly
        assertNotNull(sService.mUsageStatsService);
        assertEquals(USAGE_STATS_INTERACTION,
                sService.mConstants.USAGE_STATS_INTERACTION_INTERVAL_POST_S);
        assertEquals(SERVICE_USAGE_INTERACTION,
                sService.mConstants.SERVICE_USAGE_INTERACTION_TIME_POST_S);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStatePersistentUI() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_PERSISTENT_UI);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateTop() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_TOP);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateTop_PreviousInteraction() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_TOP);
        mProcessRecord.mState.setReportedInteraction(true);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateTop_PastUsageInterval() {
        final long elapsedTime = 3 * USAGE_STATS_INTERACTION;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_TOP);
        mProcessRecord.mState.setReportedInteraction(true);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateBoundTop() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_BOUND_TOP);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(elapsedTime, false, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS_ShortInteraction() {
        final long elapsedTime = ZERO;
        final long fgInteractionTime = 1000L;
        mProcessRecord.mState.setFgInteractionTime(fgInteractionTime);
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(fgInteractionTime, false, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS_LongInteraction() {
        final long elapsedTime = 2 * SERVICE_USAGE_INTERACTION;
        final long fgInteractionTime = 1000L;
        mProcessRecord.mState.setFgInteractionTime(fgInteractionTime);
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(fgInteractionTime, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGS_PreviousLongInteraction() {
        final long elapsedTime = 2 * SERVICE_USAGE_INTERACTION;
        final long fgInteractionTime = 1000L;
        mProcessRecord.mState.setFgInteractionTime(fgInteractionTime);
        mProcessRecord.mState.setReportedInteraction(true);
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(fgInteractionTime, true, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateFGSLocation() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(elapsedTime, false, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateBFGS() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(
                ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantFG() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantFG_PreviousInteraction() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        mProcessRecord.mState.setReportedInteraction(true);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantFG_PastUsageInterval() {
        final long elapsedTime = 3 * USAGE_STATS_INTERACTION;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND);
        mProcessRecord.mState.setReportedInteraction(true);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, true, elapsedTime);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateImportantBG() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, false, ZERO);
    }

    @Test
    public void testMaybeUpdateUsageStats_ProcStateService() {
        final long elapsedTime = ZERO;
        mProcessRecord.mState.setCurProcState(ActivityManager.PROCESS_STATE_SERVICE);
        sService.mOomAdjuster.maybeUpdateUsageStats(mProcessRecord, elapsedTime);

        assertProcessRecordState(ZERO, false, ZERO);
    }

    private void assertProcessRecordState(long fgInteractionTime, boolean reportedInteraction,
            long interactionEventTime) {
        assertEquals("Foreground interaction time was not updated correctly.",
                fgInteractionTime, mProcessRecord.mState.getFgInteractionTime());
        assertEquals("Interaction was not updated correctly.",
                reportedInteraction, mProcessRecord.mState.hasReportedInteraction());
        assertEquals("Interaction event time was not updated correctly.",
                interactionEventTime, mProcessRecord.mState.getInteractionEventTime());
    }
}
