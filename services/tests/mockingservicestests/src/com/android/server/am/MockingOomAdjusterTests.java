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

import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL;
import static android.app.ActivityManager.PROCESS_CAPABILITY_BFSL;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_RECENT;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
import static android.app.ActivityManager.PROCESS_STATE_HOME;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TOP_SLEEPING;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_ACTIVITY;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_NONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.ProcessList.BACKUP_APP_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MAX_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;
import static com.android.server.am.ProcessList.FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.HEAVY_WEIGHT_APP_ADJ;
import static com.android.server.am.ProcessList.HOME_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.PERSISTENT_PROC_ADJ;
import static com.android.server.am.ProcessList.PERSISTENT_SERVICE_ADJ;
import static com.android.server.am.ProcessList.PREVIOUS_APP_ADJ;
import static com.android.server.am.ProcessList.SCHED_GROUP_BACKGROUND;
import static com.android.server.am.ProcessList.SCHED_GROUP_DEFAULT;
import static com.android.server.am.ProcessList.SCHED_GROUP_RESTRICTED;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP;
import static com.android.server.am.ProcessList.SCHED_GROUP_TOP_APP_BOUND;
import static com.android.server.am.ProcessList.SERVICE_ADJ;
import static com.android.server.am.ProcessList.SERVICE_B_ADJ;
import static com.android.server.am.ProcessList.UNKNOWN_ADJ;
import static com.android.server.am.ProcessList.VISIBLE_APP_ADJ;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.answer;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowProcessController;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for {@link OomAdjuster}.
 *
 * Build/Install/Run:
 * atest MockingOomAdjusterTests
 */
@Presubmit
public class MockingOomAdjusterTests {
    private static final int MOCKAPP_PID = 12345;
    private static final int MOCKAPP_UID = 12345;
    private static final String MOCKAPP_PROCESSNAME = "test #1";
    private static final String MOCKAPP_PACKAGENAME = "com.android.test.test1";
    private static final int MOCKAPP2_PID = MOCKAPP_PID + 1;
    private static final int MOCKAPP2_UID = MOCKAPP_UID + 1;
    private static final String MOCKAPP2_PROCESSNAME = "test #2";
    private static final String MOCKAPP2_PACKAGENAME = "com.android.test.test2";
    private static final int MOCKAPP3_PID = MOCKAPP_PID + 2;
    private static final int MOCKAPP3_UID = MOCKAPP_UID + 2;
    private static final String MOCKAPP3_PROCESSNAME = "test #3";
    private static final String MOCKAPP3_PACKAGENAME = "com.android.test.test3";
    private static final int MOCKAPP4_PID = MOCKAPP_PID + 3;
    private static final int MOCKAPP4_UID = MOCKAPP_UID + 3;
    private static final String MOCKAPP4_PROCESSNAME = "test #4";
    private static final String MOCKAPP4_PACKAGENAME = "com.android.test.test4";
    private static final int MOCKAPP5_PID = MOCKAPP_PID + 4;
    private static final int MOCKAPP5_UID = MOCKAPP_UID + 4;
    private static final String MOCKAPP5_PROCESSNAME = "test #5";
    private static final String MOCKAPP5_PACKAGENAME = "com.android.test.test5";
    private static final int MOCKAPP2_UID_OTHER = MOCKAPP2_UID + UserHandle.PER_USER_RANGE;
    private static int sFirstCachedAdj = ProcessList.CACHED_APP_MIN_ADJ
                                          + ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
    private static Context sContext;
    private static PackageManagerInternal sPackageManagerInternal;
    private static ActivityManagerService sService;

    @SuppressWarnings("GuardedBy")
    @BeforeClass
    public static void setUpOnce() {
        sContext = getInstrumentation().getTargetContext();
        System.setProperty("dexmaker.share_classloader", "true");

        sPackageManagerInternal = mock(PackageManagerInternal.class);
        doReturn(new ComponentName("", "")).when(sPackageManagerInternal)
                .getSystemUiServiceComponent();
        // Remove stale instance of PackageManagerInternal if there is any
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, sPackageManagerInternal);

        sService = mock(ActivityManagerService.class);
        sService.mActivityTaskManager = new ActivityTaskManagerService(sContext);
        sService.mActivityTaskManager.initialize(null, null, sContext.getMainLooper());
        sService.mPackageManagerInt = sPackageManagerInternal;
        sService.mAtmInternal = spy(sService.mActivityTaskManager.getAtmInternal());

        sService.mConstants = new ActivityManagerConstants(sContext, sService,
                sContext.getMainThreadHandler());
        setFieldValue(ActivityManagerService.class, sService, "mContext",
                sContext);
        ProcessList pr = spy(new ProcessList());
        pr.mService = sService;
        AppProfiler profiler = mock(AppProfiler.class);
        setFieldValue(ActivityManagerService.class, sService, "mProcessList",
                pr);
        setFieldValue(ActivityManagerService.class, sService, "mHandler",
                mock(ActivityManagerService.MainHandler.class));
        setFieldValue(ActivityManagerService.class, sService, "mProcessStats",
                new ProcessStatsService(sService, new File(sContext.getFilesDir(), "procstats")));
        setFieldValue(ActivityManagerService.class, sService, "mBackupTargets",
                mock(SparseArray.class));
        setFieldValue(ActivityManagerService.class, sService, "mOomAdjProfiler",
                mock(OomAdjProfiler.class));
        setFieldValue(ActivityManagerService.class, sService, "mUserController",
                mock(UserController.class));
        setFieldValue(ActivityManagerService.class, sService, "mAppProfiler", profiler);
        setFieldValue(ActivityManagerService.class, sService, "mProcLock",
                new ActivityManagerProcLock());
        setFieldValue(ActivityManagerService.class, sService, "mServices",
                spy(new ActiveServices(sService)));
        setFieldValue(ActivityManagerService.class, sService, "mInternal",
                mock(ActivityManagerService.LocalService.class));
        setFieldValue(ActivityManagerService.class, sService, "mBatteryStatsService",
                mock(BatteryStatsService.class));
        setFieldValue(ActivityManagerService.class, sService, "mInjector",
                new ActivityManagerService.Injector(sContext));
        doReturn(mock(AppOpsManager.class)).when(sService).getAppOpsManager();
        doCallRealMethod().when(sService).enqueueOomAdjTargetLocked(any(ProcessRecord.class));
        doCallRealMethod().when(sService).updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_ACTIVITY);
        setFieldValue(AppProfiler.class, profiler, "mProfilerLock", new Object());
        doReturn(new ActivityManagerService.ProcessChangeItem()).when(pr)
                .enqueueProcessChangeItemLocked(anyInt(), anyInt());
        sService.mOomAdjuster = new OomAdjuster(sService, sService.mProcessList,
                new ActiveUids(sService, false));
        sService.mOomAdjuster.mAdjSeq = 10000;
        sService.mWakefulness = new AtomicInteger(PowerManagerInternal.WAKEFULNESS_AWAKE);
        if (sService.mConstants.USE_TIERED_CACHED_ADJ) {
            sFirstCachedAdj = ProcessList.CACHED_APP_MIN_ADJ + 10;
        }
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
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

    private static void assertBfsl(ProcessRecord app) {
        assertEquals(PROCESS_CAPABILITY_BFSL,
                app.mState.getSetCapability() & PROCESS_CAPABILITY_BFSL);
    }

    private static void assertNoBfsl(ProcessRecord app) {
        assertEquals(0, app.mState.getSetCapability() & PROCESS_CAPABILITY_BFSL);
    }

    /**
     * Replace the process LRU with the given processes.
     * @param apps
     */
    private void setProcessesToLru(ProcessRecord... apps) {
        ArrayList<ProcessRecord> lru = sService.mProcessList.getLruProcessesLOSP();
        lru.clear();
        Collections.addAll(lru, apps);
    }

    /**
     * Run updateOomAdjLocked().
     * - If there's only one process, then it calls updateOomAdjLocked(ProcessRecord, int).
     * - Otherwise, sets the processes to the LRU and run updateOomAdjLocked(int).
     */
    @SuppressWarnings("GuardedBy")
    private void updateOomAdj(ProcessRecord... apps) {
        if (apps.length == 1) {
            sService.mOomAdjuster.updateOomAdjLocked(apps[0], OOM_ADJ_REASON_NONE);
        } else {
            setProcessesToLru(apps);
            sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);
            sService.mProcessList.getLruProcessesLOSP().clear();
        }
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Persistent_TopUi_Sleeping() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        app.mState.setHasTopUi(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_ASLEEP);
        updateOomAdj(app);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERSISTENT_PROC_ADJ,
                SCHED_GROUP_RESTRICTED);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Persistent_TopUi_Awake() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        app.mState.setHasTopUi(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_PERSISTENT_UI, PERSISTENT_PROC_ADJ,
                SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Persistent_TopApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        doReturn(app).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doReturn(null).when(sService).getTopApp();

        assertProcStates(app, PROCESS_STATE_PERSISTENT_UI, PERSISTENT_PROC_ADJ,
                SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TopApp_Awake() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(app).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doReturn(null).when(sService).getTopApp();

        assertProcStates(app, PROCESS_STATE_TOP, FOREGROUND_APP_ADJ, SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_RunningAnimations() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(PROCESS_STATE_TOP_SLEEPING).when(sService.mAtmInternal).getTopProcessState();
        app.mState.setRunningRemoteAnimation(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();

        assertProcStates(app, PROCESS_STATE_TOP_SLEEPING, VISIBLE_APP_ADJ, SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_RunningInstrumentation() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(ActiveInstrumentation.class)).when(app).getActiveInstrumentation();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doCallRealMethod().when(app).getActiveInstrumentation();

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, FOREGROUND_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ReceivingBroadcast() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(true).when(sService).isReceivingBroadcastLocked(any(ProcessRecord.class),
                any(int[].class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doReturn(false).when(sService).isReceivingBroadcastLocked(any(ProcessRecord.class),
                any(int[].class));

        assertProcStates(app, PROCESS_STATE_RECEIVER, FOREGROUND_APP_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ExecutingService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mServices.startExecutingService(mock(ServiceRecord.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_SERVICE, FOREGROUND_APP_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TopApp_Sleeping() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(PROCESS_STATE_TOP_SLEEPING).when(sService.mAtmInternal).getTopProcessState();
        doReturn(app).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_ASLEEP);
        updateOomAdj(app);
        doReturn(null).when(sService).getTopApp();
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

        assertProcStates(app, PROCESS_STATE_TOP_SLEEPING, FOREGROUND_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_CachedEmpty() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mState.setCurRawAdj(CACHED_APP_MIN_ADJ);
        doReturn(null).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, CACHED_APP_MIN_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_VisibleActivities() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).hasActivities();
        doAnswer(answer(callback -> {
            Field field = callback.getClass().getDeclaredField("adj");
            field.set(callback, VISIBLE_APP_ADJ);
            field = callback.getClass().getDeclaredField("foregroundActivities");
            field.set(callback, true);
            field = callback.getClass().getDeclaredField("procState");
            field.set(callback, PROCESS_STATE_TOP);
            field = callback.getClass().getDeclaredField("schedGroup");
            field.set(callback, SCHED_GROUP_TOP_APP);
            return 0;
        })).when(wpc).computeOomAdjFromActivities(
                any(WindowProcessController.ComputeOomAdjCallback.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_TOP, VISIBLE_APP_ADJ, SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_RecentTasks() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).hasRecentTasks();
        app.mState.setLastTopTime(SystemClock.uptimeMillis());
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doCallRealMethod().when(wpc).hasRecentTasks();

        assertEquals(PROCESS_STATE_CACHED_RECENT, app.mState.getSetProcState());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_FgServiceLocation() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mServices.setHasForegroundServices(true, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                /* hasNoneType=*/false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_FgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_FgService_ShortFgs() {
        sService.mConstants.TOP_TO_FGS_GRACE_DURATION = 100_000;
        sService.mConstants.mShortFgsProcStateExtraWaitDuration = 200_000;

        ServiceRecord s = ServiceRecord.newEmptyInstanceForTest(sService);
        s.startRequested = true;
        s.isForeground = true;
        s.foregroundServiceType = FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
        s.setShortFgsInfo(SystemClock.uptimeMillis());

        // SHORT_SERVICE FGS will get IMP_FG and a slightly different recent-adjustment.
        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
            app.mServices.startService(s);
            app.mServices.setHasForegroundServices(true,
                    FOREGROUND_SERVICE_TYPE_SHORT_SERVICE, /* hasNoneType=*/false);
            app.mState.setLastTopTime(SystemClock.uptimeMillis());
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

            updateOomAdj(app);

            assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE,
                    PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 1, SCHED_GROUP_DEFAULT);
            assertNoBfsl(app);
        }

        // SHORT_SERVICE, but no longer recent.
        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
            app.mServices.setHasForegroundServices(true,
                    FOREGROUND_SERVICE_TYPE_SHORT_SERVICE, /* hasNoneType=*/false);
            app.mServices.startService(s);
            app.mState.setLastTopTime(SystemClock.uptimeMillis()
                    - sService.mConstants.TOP_TO_FGS_GRACE_DURATION);
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

            updateOomAdj(app);

            assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE,
                    PERCEPTIBLE_MEDIUM_APP_ADJ + 1, SCHED_GROUP_DEFAULT);
            assertNoBfsl(app);
        }

        // SHORT_SERVICE, timed out already.
        s = ServiceRecord.newEmptyInstanceForTest(sService);
        s.startRequested = true;
        s.isForeground = true;
        s.foregroundServiceType = FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
        s.setShortFgsInfo(SystemClock.uptimeMillis()
                - sService.mConstants.mShortFgsTimeoutDuration
                - sService.mConstants.mShortFgsProcStateExtraWaitDuration);
        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
            app.mServices.setHasForegroundServices(true,
                    FOREGROUND_SERVICE_TYPE_SHORT_SERVICE, /* hasNoneType=*/false);
            app.mServices.startService(s);
            app.mState.setLastTopTime(SystemClock.uptimeMillis()
                    - sService.mConstants.TOP_TO_FGS_GRACE_DURATION);
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

            updateOomAdj(app);

            // Procstate should be lower than FGS. (It should be SERVICE)
            assertEquals(app.mState.getSetProcState(), PROCESS_STATE_SERVICE);
            assertNoBfsl(app);
        }
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_OverlayUi() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mState.setHasOverlayUi(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_PerceptibleRecent_FgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        app.mState.setLastTopTime(SystemClock.uptimeMillis());
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ, SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_PerceptibleRecent_AlmostPerceptibleService() {
        // Grace period allows the adjustment.
        {
            ProcessRecord system = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, true));
            long nowUptime = SystemClock.uptimeMillis();
            app.mState.setLastTopTime(nowUptime);
            // Simulate the system starting and binding to a service in the app.
            ServiceRecord s = bindService(app, system,
                    null, Context.BIND_ALMOST_PERCEPTIBLE, mock(IBinder.class));
            s.lastTopAlmostPerceptibleBindRequestUptimeMs = nowUptime;
            s.getConnections().clear();
            app.mServices.updateHasTopStartedAlmostPerceptibleServices();
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            updateOomAdj(app);

            assertEquals(PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 2, app.mState.getSetAdj());
        }

        // Out of grace period but valid binding allows the adjustment.
        {
            ProcessRecord system = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, true));
            long nowUptime = SystemClock.uptimeMillis();
            app.mState.setLastTopTime(nowUptime);
            // Simulate the system starting and binding to a service in the app.
            ServiceRecord s = bindService(app, system,
                    null, Context.BIND_ALMOST_PERCEPTIBLE + 2, mock(IBinder.class));
            s.lastTopAlmostPerceptibleBindRequestUptimeMs =
                    nowUptime - 2 * sService.mConstants.mServiceBindAlmostPerceptibleTimeoutMs;
            app.mServices.updateHasTopStartedAlmostPerceptibleServices();
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            updateOomAdj(app);

            assertEquals(PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 2, app.mState.getSetAdj());
        }

        // Out of grace period and no valid binding so no adjustment.
        {
            ProcessRecord system = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, true));
            long nowUptime = SystemClock.uptimeMillis();
            app.mState.setLastTopTime(nowUptime);
            // Simulate the system starting and binding to a service in the app.
            ServiceRecord s = bindService(app, system,
                    null, Context.BIND_ALMOST_PERCEPTIBLE, mock(IBinder.class));
            s.lastTopAlmostPerceptibleBindRequestUptimeMs =
                    nowUptime - 2 * sService.mConstants.mServiceBindAlmostPerceptibleTimeoutMs;
            s.getConnections().clear();
            app.mServices.updateHasTopStartedAlmostPerceptibleServices();
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            updateOomAdj(app);

            assertNotEquals(PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 2, app.mState.getSetAdj());
        }
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ImpFg_AlmostPerceptibleService() {
        ProcessRecord system = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, true));
        system.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        system.mState.setHasTopUi(true);
        // Simulate the system starting and binding to a service in the app.
        ServiceRecord s = bindService(app, system,
                null, Context.BIND_ALMOST_PERCEPTIBLE, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        sService.mOomAdjuster.updateOomAdjLocked(app, OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_IMPORTANT_FOREGROUND,
                PERCEPTIBLE_APP_ADJ + 1, SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Toast() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mState.setForcingToImportant(new Object());
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_HeavyWeight() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).isHeavyWeightProcess();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doReturn(false).when(wpc).isHeavyWeightProcess();

        assertProcStates(app, PROCESS_STATE_HEAVY_WEIGHT, HEAVY_WEIGHT_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_HomeApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_HOME, HOME_APP_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_PreviousApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).isPreviousProcess();
        doReturn(true).when(wpc).hasActivities();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_LAST_ACTIVITY, PREVIOUS_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Backup() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        BackupRecord backupTarget = new BackupRecord(null, 0, 0, 0);
        backupTarget.app = app;
        doReturn(backupTarget).when(sService.mBackupTargets).get(anyInt());
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);
        doReturn(null).when(sService.mBackupTargets).get(anyInt());

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, BACKUP_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ClientActivities() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mServices.setHasClientActivities(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertEquals(PROCESS_STATE_CACHED_ACTIVITY_CLIENT, app.mState.getSetProcState());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TreatLikeActivity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.mServices.setTreatLikeActivity(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertEquals(PROCESS_STATE_CACHED_ACTIVITY, app.mState.getSetProcState());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ServiceB() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.mState.setServiceB(true);
        ServiceRecord s = mock(ServiceRecord.class);
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = SystemClock.uptimeMillis();
        app.mServices.startService(s);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_SERVICE, SERVICE_B_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_MaxAdj() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.mState.setMaxAdj(PERCEPTIBLE_LOW_APP_ADJ);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, PERCEPTIBLE_LOW_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_NonCachedToCached() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.mState.setCached(false);
        app.mState.setCurRawAdj(SERVICE_ADJ);
        doReturn(null).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertTrue(ProcessList.CACHED_APP_MIN_ADJ <= app.mState.getSetAdj());
        assertTrue(ProcessList.CACHED_APP_MAX_ADJ >= app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Started() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ServiceRecord s = mock(ServiceRecord.class);
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = SystemClock.uptimeMillis();
        app.mServices.startService(s);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_SERVICE, SERVICE_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Started_WaivePriority() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        ServiceRecord s = bindService(app, client, null, Context.BIND_WAIVE_PRIORITY,
                mock(IBinder.class));
        s.startRequested = true;
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client).when(sService).getTopApp();
        updateOomAdj(client, app);
        doReturn(null).when(sService).getTopApp();

        assertProcStates(app, PROCESS_STATE_SERVICE, sFirstCachedAdj, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Started_WaivePriority_TreatLikeActivity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_WAIVE_PRIORITY
                | Context.BIND_TREAT_LIKE_ACTIVITY, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PROCESS_STATE_CACHED_ACTIVITY, app.mState.getSetProcState());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Started_WaivePriority_AdjustWithActivity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        IBinder binder = mock(IBinder.class);
        ServiceRecord s = bindService(app, client, null, Context.BIND_WAIVE_PRIORITY
                | Context.BIND_ADJUST_WITH_ACTIVITY | Context.BIND_IMPORTANT, binder);
        ConnectionRecord cr = s.getConnections().get(binder).get(0);
        setFieldValue(ConnectionRecord.class, cr, "activity",
                mock(ActivityServiceConnectionsHolder.class));
        doReturn(true).when(cr.activity).isActivityVisible();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(FOREGROUND_APP_ADJ, app.mState.getSetAdj());
        assertEquals(SCHED_GROUP_TOP_APP_BOUND, app.mState.getSetSchedGroup());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Self() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        bindService(app, app, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, sFirstCachedAdj, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_CachedActivity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        client.mServices.setTreatLikeActivity(true);
        bindService(app, client, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PROCESS_STATE_CACHED_EMPTY, app.mState.getSetProcState());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_AllowOomManagement() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(false).when(wpc).isHomeProcess();
        doReturn(true).when(wpc).isPreviousProcess();
        doReturn(true).when(wpc).hasActivities();
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_ALLOW_OOM_MANAGEMENT, mock(IBinder.class));
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);
        doReturn(null).when(sService).getTopApp();

        assertEquals(PREVIOUS_APP_ADJ, app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundByPersistentService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_FOREGROUND_SERVICE, mock(IBinder.class));
        client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        client.mState.setHasTopUi(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Bound_ImportantFg() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_IMPORTANT, mock(IBinder.class));
        client.mServices.startExecutingService(mock(ServiceRecord.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(FOREGROUND_APP_ADJ, app.mState.getSetAdj());
        assertNoBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundByTop() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);
        doReturn(null).when(sService).getTopApp();

        assertProcStates(app, PROCESS_STATE_BOUND_TOP, VISIBLE_APP_ADJ, SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_FOREGROUND_SERVICE, mock(IBinder.class));
        client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PROCESS_STATE_BOUND_FOREGROUND_SERVICE, app.mState.getSetProcState());
        assertEquals(PROCESS_STATE_PERSISTENT, client.mState.getSetProcState());
        assertBfsl(client);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundNotForeground() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_NOT_FOREGROUND, mock(IBinder.class));
        client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertEquals(PROCESS_STATE_TRANSIENT_BACKGROUND, app.mState.getSetProcState());
        assertNoBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_ImportantFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        client.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, client.mState.getSetProcState());
        assertBfsl(client);
        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, app.mState.getSetProcState());
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_ImportantFgService_ShortFgs() {
        // Client has a SHORT_SERVICE FGS, which isn't allowed BFSL.
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));

        // In order to trick OomAdjuster to think it has a short-service, we need this logic.
        ServiceRecord s = ServiceRecord.newEmptyInstanceForTest(sService);
        s.startRequested = true;
        s.isForeground = true;
        s.foregroundServiceType = FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
        s.setShortFgsInfo(SystemClock.uptimeMillis());
        client.mServices.startService(s);
        client.mState.setLastTopTime(SystemClock.uptimeMillis());

        client.mServices.setHasForegroundServices(true, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                /* hasNoneType=*/false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        // Client only has a SHORT_FGS, so it doesn't have BFSL, and that's propagated.
        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, client.mState.getSetProcState());
        assertNoBfsl(client);
        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, app.mState.getSetProcState());
        assertNoBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundForegroundService_with_ShortFgs() {

        // app2, which is bound by app1 (which makes it BFGS)
        // but it also has a short-fgs.
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));

        // In order to trick OomAdjuster to think it has a short-service, we need this logic.
        ServiceRecord s = ServiceRecord.newEmptyInstanceForTest(sService);
        s.startRequested = true;
        s.isForeground = true;
        s.foregroundServiceType = FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
        s.setShortFgsInfo(SystemClock.uptimeMillis());
        app2.mServices.startService(s);
        app2.mState.setLastTopTime(SystemClock.uptimeMillis());

        app2.mServices.setHasForegroundServices(true, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                /* hasNoneType=*/false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app2);

        // Client only has a SHORT_FGS, so it doesn't have BFSL, and that's propagated.
        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, app2.mState.getSetProcState());
        assertNoBfsl(app2);

        // Now, create a BFGS process (app1), and make it bind to app 2

        // Persistent process
        ProcessRecord pers = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        pers.mState.setMaxAdj(PERSISTENT_PROC_ADJ);

        // app1, which is bound by pers (which makes it BFGS)
        ProcessRecord app1 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));

        bindService(app1, pers, null, Context.BIND_FOREGROUND_SERVICE, mock(IBinder.class));
        bindService(app2, app1, null, 0, mock(IBinder.class));

        updateOomAdj(pers, app1, app2);

        assertEquals(PROCESS_STATE_BOUND_FOREGROUND_SERVICE, app1.mState.getSetProcState());
        assertBfsl(app1);

        // Now, app2 gets BFSL from app1.
        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, app2.mState.getSetProcState());
        assertBfsl(app2);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundByBackup_AboveClient() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_ABOVE_CLIENT, mock(IBinder.class));
        BackupRecord backupTarget = new BackupRecord(null, 0, 0, 0);
        backupTarget.app = client;
        doReturn(backupTarget).when(sService.mBackupTargets).get(anyInt());
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        doReturn(null).when(sService.mBackupTargets).get(anyInt());

        assertEquals(BACKUP_APP_ADJ, app.mState.getSetAdj());
        assertNoBfsl(app);

        client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        updateOomAdj(app);

        assertEquals(PERSISTENT_SERVICE_ADJ, app.mState.getSetAdj());
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_NotPerceptible() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_NOT_PERCEPTIBLE, mock(IBinder.class));
        client.mState.setRunningRemoteAnimation(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertEquals(PERCEPTIBLE_LOW_APP_ADJ, app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_NotVisible() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_NOT_VISIBLE, mock(IBinder.class));
        client.mState.setRunningRemoteAnimation(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PERCEPTIBLE_APP_ADJ, app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Perceptible() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        client.mState.setHasOverlayUi(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PERCEPTIBLE_APP_ADJ, app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_AlmostPerceptible() {
        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
            ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
            bindService(app, client, null,
                    Context.BIND_ALMOST_PERCEPTIBLE | Context.BIND_NOT_FOREGROUND,
                    mock(IBinder.class));
            client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            updateOomAdj(client, app);

            assertEquals(PERCEPTIBLE_MEDIUM_APP_ADJ + 2, app.mState.getSetAdj());
        }

        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
            ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
            WindowProcessController wpc = client.getWindowProcessController();
            doReturn(true).when(wpc).isHeavyWeightProcess();
            bindService(app, client, null,
                    Context.BIND_ALMOST_PERCEPTIBLE | Context.BIND_NOT_FOREGROUND,
                    mock(IBinder.class));
            client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            updateOomAdj(client, app);
            doReturn(false).when(wpc).isHeavyWeightProcess();

            assertEquals(PERCEPTIBLE_MEDIUM_APP_ADJ + 2, app.mState.getSetAdj());
        }

        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
            ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
            bindService(app, client, null,
                    Context.BIND_ALMOST_PERCEPTIBLE,
                    mock(IBinder.class));
            client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            sService.mOomAdjuster.updateOomAdjLocked(app, OOM_ADJ_REASON_NONE);

            assertEquals(PERCEPTIBLE_APP_ADJ + 1, app.mState.getSetAdj());
        }

        {
            ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                    MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
            ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                    MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
            WindowProcessController wpc = client.getWindowProcessController();
            doReturn(true).when(wpc).isHeavyWeightProcess();
            bindService(app, client, null,
                    Context.BIND_ALMOST_PERCEPTIBLE,
                    mock(IBinder.class));
            client.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
            sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
            sService.mOomAdjuster.updateOomAdjLocked(app, OOM_ADJ_REASON_NONE);
            doReturn(false).when(wpc).isHeavyWeightProcess();

            assertEquals(PERCEPTIBLE_APP_ADJ + 1, app.mState.getSetAdj());
        }
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Other() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        client.mState.setRunningRemoteAnimation(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(VISIBLE_APP_ADJ, app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Bind_ImportantBg() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_IMPORTANT_BACKGROUND, mock(IBinder.class));
        client.mState.setHasOverlayUi(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertEquals(PROCESS_STATE_IMPORTANT_BACKGROUND, app.mState.getSetProcState());
        assertNoBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Self() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        bindProvider(app, app, null, null, false);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, sFirstCachedAdj, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Cached_Activity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, false);
        client.mServices.setTreatLikeActivity(true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, sFirstCachedAdj, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_TopApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, false);
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);
        doReturn(null).when(sService).getTopApp();

        assertProcStates(app, PROCESS_STATE_BOUND_TOP, FOREGROUND_APP_ADJ, SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_FgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        client.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindProvider(app, client, null, null, false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_FgService_ShortFgs() {
        // Client has a SHORT_SERVICE FGS, which isn't allowed BFSL.
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));

        // In order to trick OomAdjuster to think it has a short-service, we need this logic.
        ServiceRecord s = ServiceRecord.newEmptyInstanceForTest(sService);
        s.startRequested = true;
        s.isForeground = true;
        s.foregroundServiceType = FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
        s.setShortFgsInfo(SystemClock.uptimeMillis());
        client.mServices.startService(s);
        client.mState.setLastTopTime(SystemClock.uptimeMillis());

        client.mServices.setHasForegroundServices(true, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                /* hasNoneType=*/false);
        bindProvider(app, client, null, null, false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        // Client only has a SHORT_FGS, so it doesn't have BFSL, and that's propagated.
        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
                PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 1,
                SCHED_GROUP_DEFAULT);
        assertNoBfsl(client);
        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
                PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ + 1,
                SCHED_GROUP_DEFAULT);
        assertNoBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_ExternalProcessHandles() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, app);

        assertProcStates(app, PROCESS_STATE_IMPORTANT_FOREGROUND, FOREGROUND_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Retention() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.mProviders.setLastProviderTime(SystemClock.uptimeMillis());
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_LAST_ACTIVITY, PREVIOUS_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByTop() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client2).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, client2, app);
        doReturn(null).when(sService).getTopApp();

        assertProcStates(app, PROCESS_STATE_BOUND_TOP, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundByFgService_Branch() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app, client2, null, 0, mock(IBinder.class));
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, client2, app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, client2, app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Cycle() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(client2, app, null, 0, mock(IBinder.class));

        // Note: We add processes to LRU but still call updateOomAdjLocked() with a specific
        // processes.
        setProcessesToLru(app, client, client2);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(client);
        assertBfsl(client2);

        client2.mServices.setHasForegroundServices(false, 0, /* hasNoneType=*/false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        sService.mOomAdjuster.updateOomAdjLocked(client2, OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_CACHED_EMPTY, client2.mState.getSetProcState());
        assertEquals(PROCESS_STATE_CACHED_EMPTY, client.mState.getSetProcState());
        assertEquals(PROCESS_STATE_CACHED_EMPTY, app.mState.getSetProcState());
        assertNoBfsl(app);
        assertNoBfsl(client);
        assertNoBfsl(client2);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Cycle_2() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        bindService(client, app, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client2, client, null, 0, mock(IBinder.class));
        client.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(client);
        assertBfsl(client2);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Cycle_3() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        bindService(client2, client, null, 0, mock(IBinder.class));
        client.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(client);
        assertBfsl(client2);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Cycle_4() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        bindService(client2, client, null, 0, mock(IBinder.class));
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        bindService(client3, client, null, 0, mock(IBinder.class));
        ProcessRecord client4 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        bindService(client3, client4, null, 0, mock(IBinder.class));
        bindService(client4, client3, null, 0, mock(IBinder.class));
        client.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2, client3, client4);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client4, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(client);
        assertBfsl(client2);
        assertBfsl(client3);
        assertBfsl(client4);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Cycle_Branch() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(client2, app, null, 0, mock(IBinder.class));
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.mState.setForcingToImportant(new Object());
        bindService(app, client3, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2, client3);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_Perceptible_Cycle_Branch() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        bindService(client2, app, null, 0, mock(IBinder.class));
        WindowProcessController wpc = client2.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.mState.setForcingToImportant(new Object());
        bindService(app, client3, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2, client3);

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_Perceptible_Cycle_2() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        bindService(client2, app, null, 0, mock(IBinder.class));
        WindowProcessController wpc = client2.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        ProcessRecord client4 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        client4.mState.setForcingToImportant(new Object());
        bindService(app, client4, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2, client3, client4);

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Cycle_Branch_2() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, 0, mock(IBinder.class));
        bindService(client2, app, null, 0, mock(IBinder.class));
        WindowProcessController wpc = client2.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.mState.setForcingToImportant(new Object());
        bindService(app, client3, null, 0, mock(IBinder.class));
        ProcessRecord client4 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        client4.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(app, client4, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2, client3, client4);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Branch_3() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        WindowProcessController wpc = client.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app, client2, null, 0, mock(IBinder.class));
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.mState.setForcingToImportant(new Object());
        bindService(app, client3, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, client2, client3, app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Provider() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindProvider(client, client2, null, null, false);
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, client2, app);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Provider_Cycle() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindProvider(client, client2, null, null, false);
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(client2, app, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Chain_BoundByFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, false);
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindProvider(client, client2, null, null, false);
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client, client2, app);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Chain_BoundByFgService_Cycle() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, false);
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindProvider(client, client2, null, null, false);
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindProvider(client2, app, null, null, false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ScheduleLikeTop() {
        final ProcessRecord app1 = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        final ProcessRecord client1 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        final ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        bindService(app1, client1, null, Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                mock(IBinder.class));
        bindService(app2, client2, null, Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                mock(IBinder.class));
        client1.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        client2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);

        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(client1, client2, app1, app2);

        assertProcStates(app1, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app1);
        assertBfsl(app2);

        bindService(app1, client1, null, Context.BIND_SCHEDULE_LIKE_TOP_APP, mock(IBinder.class));
        bindService(app2, client2, null, Context.BIND_SCHEDULE_LIKE_TOP_APP, mock(IBinder.class));
        updateOomAdj(app1, app2);

        assertProcStates(app1, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, VISIBLE_APP_ADJ,
                SCHED_GROUP_TOP_APP);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);

        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_ASLEEP);
        updateOomAdj(client1, client2, app1, app2);
        assertProcStates(app1, PROCESS_STATE_IMPORTANT_FOREGROUND, VISIBLE_APP_ADJ,
                SCHED_GROUP_TOP_APP);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app2);

        bindService(client2, app1, null, 0, mock(IBinder.class));
        bindService(app1, client2, null, 0, mock(IBinder.class));
        client2.mServices.setHasForegroundServices(false, 0, /* hasNoneType=*/false);
        updateOomAdj(app1, client1, client2);
        assertProcStates(app1, PROCESS_STATE_IMPORTANT_FOREGROUND, VISIBLE_APP_ADJ,
                SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TreatLikeVisFGS() {
        final ProcessRecord app1 = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        final ProcessRecord client1 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        final ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client1.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        client2.mState.setMaxAdj(PERSISTENT_PROC_ADJ);

        final ServiceRecord s1 = bindService(app1, client1, null,
                Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE, mock(IBinder.class));
        final ServiceRecord s2 = bindService(app2, client2, null,
                Context.BIND_IMPORTANT, mock(IBinder.class));

        updateOomAdj(client1, client2, app1, app2);

        assertProcStates(app1, PROCESS_STATE_FOREGROUND_SERVICE, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_PERSISTENT, PERSISTENT_SERVICE_ADJ,
                SCHED_GROUP_DEFAULT);

        bindService(app2, client1, s2, Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE,
                mock(IBinder.class));
        updateOomAdj(app2);
        assertProcStates(app2, PROCESS_STATE_PERSISTENT, PERSISTENT_SERVICE_ADJ,
                SCHED_GROUP_DEFAULT);

        s1.getConnections().clear();
        s2.getConnections().clear();
        client1.mState.setMaxAdj(UNKNOWN_ADJ);
        client2.mState.setMaxAdj(UNKNOWN_ADJ);
        client1.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        client2.mState.setHasOverlayUi(true);

        bindService(app1, client1, s1, Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE,
                mock(IBinder.class));
        bindService(app2, client2, s2, Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE,
                mock(IBinder.class));

        updateOomAdj(app1, app2);

        // VISIBLE_APP_ADJ is the max oom-adj for BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE.
        assertProcStates(app1, PROCESS_STATE_FOREGROUND_SERVICE, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);

        client2.mState.setHasOverlayUi(false);
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client2).when(sService).getTopApp();
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

        sService.mOomAdjuster.updateOomAdjLocked(app2, OOM_ADJ_REASON_NONE);
        assertProcStates(app2, PROCESS_STATE_BOUND_TOP, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_BindNotPerceptibleFGS() {
        final ProcessRecord app1 = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord client1 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        client1.mState.setMaxAdj(PERSISTENT_PROC_ADJ);

        app1.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

        bindService(app1, client1, null, Context.BIND_NOT_PERCEPTIBLE, mock(IBinder.class));

        updateOomAdj(client1, app1);

        assertProcStates(app1, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app1);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_BindAlmostPerceptibleFGS() {
        final ProcessRecord app1 = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord client1 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        client1.mState.setMaxAdj(PERSISTENT_PROC_ADJ);

        app1.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

        bindService(app1, client1, null, Context.BIND_ALMOST_PERCEPTIBLE, mock(IBinder.class));

        updateOomAdj(client1, app1);

        assertProcStates(app1, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app1);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_PendingFinishAttach() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.setPendingFinishAttach(true);
        app.mState.setHasForegroundActivities(false);

        sService.mOomAdjuster.setAttachingProcessStatesLSP(app);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, FOREGROUND_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TopApp_PendingFinishAttach() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.setPendingFinishAttach(true);
        app.mState.setHasForegroundActivities(true);

        sService.mOomAdjuster.setAttachingProcessStatesLSP(app);
        updateOomAdj(app);

        assertProcStates(app, PROCESS_STATE_TOP, FOREGROUND_APP_ADJ,
                SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_UidIdle_StopService() {
        final ProcessRecord app1 = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        final ProcessRecord client1 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        final ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP3_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        final ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        final UidRecord app1UidRecord = new UidRecord(MOCKAPP_UID, sService);
        final UidRecord app2UidRecord = new UidRecord(MOCKAPP2_UID, sService);
        final UidRecord app3UidRecord = new UidRecord(MOCKAPP5_UID, sService);
        final UidRecord clientUidRecord = new UidRecord(MOCKAPP3_UID, sService);
        app1.setUidRecord(app1UidRecord);
        app2.setUidRecord(app2UidRecord);
        app3.setUidRecord(app3UidRecord);
        client1.setUidRecord(clientUidRecord);
        client2.setUidRecord(clientUidRecord);

        client1.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        client2.mState.setForcingToImportant(new Object());
        setProcessesToLru(app1, app2, app3, client1, client2);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);

        final ComponentName cn1 = ComponentName.unflattenFromString(
                MOCKAPP_PACKAGENAME + "/.TestService");
        final ServiceRecord s1 = bindService(app1, client1, null, 0, mock(IBinder.class));
        setFieldValue(ServiceRecord.class, s1, "name", cn1);
        s1.startRequested = true;

        final ComponentName cn2 = ComponentName.unflattenFromString(
                MOCKAPP2_PACKAGENAME + "/.TestService");
        final ServiceRecord s2 = bindService(app2, client2, null, 0, mock(IBinder.class));
        setFieldValue(ServiceRecord.class, s2, "name", cn2);
        s2.startRequested = true;

        final ComponentName cn3 = ComponentName.unflattenFromString(
                MOCKAPP5_PACKAGENAME + "/.TestService");
        final ServiceRecord s3 = bindService(app3, client1, null, 0, mock(IBinder.class));
        setFieldValue(ServiceRecord.class, s3, "name", cn3);
        s3.startRequested = true;

        final ComponentName cn4 = ComponentName.unflattenFromString(
                MOCKAPP3_PACKAGENAME + "/.TestService");
        final ServiceRecord c2s = makeServiceRecord(client2);
        setFieldValue(ServiceRecord.class, c2s, "name", cn4);
        c2s.startRequested = true;

        try {
            sService.mOomAdjuster.mActiveUids.put(MOCKAPP_UID, app1UidRecord);
            sService.mOomAdjuster.mActiveUids.put(MOCKAPP2_UID, app2UidRecord);
            sService.mOomAdjuster.mActiveUids.put(MOCKAPP5_UID, app3UidRecord);
            sService.mOomAdjuster.mActiveUids.put(MOCKAPP3_UID, clientUidRecord);

            setServiceMap(s1, MOCKAPP_UID, cn1);
            setServiceMap(s2, MOCKAPP2_UID, cn2);
            setServiceMap(s3, MOCKAPP5_UID, cn3);
            setServiceMap(c2s, MOCKAPP3_UID, cn4);
            app2UidRecord.setIdle(false);
            sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);

            assertProcStates(app1, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                    SCHED_GROUP_DEFAULT);
            assertProcStates(app3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                    SCHED_GROUP_DEFAULT);
            assertProcStates(client1, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                    SCHED_GROUP_DEFAULT);
            assertEquals(PROCESS_STATE_TRANSIENT_BACKGROUND, app2.mState.getSetProcState());
            assertEquals(PROCESS_STATE_TRANSIENT_BACKGROUND, client2.mState.getSetProcState());

            client1.mServices.setHasForegroundServices(false, 0, /* hasNoneType=*/false);
            client2.mState.setForcingToImportant(null);
            app1UidRecord.reset();
            app2UidRecord.reset();
            app3UidRecord.reset();
            clientUidRecord.reset();
            app1UidRecord.setIdle(true);
            app2UidRecord.setIdle(true);
            app3UidRecord.setIdle(true);
            clientUidRecord.setIdle(true);
            doReturn(ActivityManager.APP_START_MODE_DELAYED).when(sService)
                    .getAppStartModeLOSP(anyInt(), any(String.class), anyInt(),
                    anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
            doNothing().when(sService.mServices)
                    .scheduleServiceTimeoutLocked(any(ProcessRecord.class));
            sService.mOomAdjuster.updateOomAdjLocked(client1, OOM_ADJ_REASON_NONE);

            assertEquals(PROCESS_STATE_CACHED_EMPTY, client1.mState.getSetProcState());
            assertEquals(PROCESS_STATE_SERVICE, app1.mState.getSetProcState());
            assertEquals(PROCESS_STATE_SERVICE, client2.mState.getSetProcState());
        } finally {
            doCallRealMethod().when(sService)
                    .getAppStartModeLOSP(anyInt(), any(String.class), anyInt(),
                    anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
            sService.mServices.mServiceMap.clear();
            sService.mOomAdjuster.mActiveUids.clear();
        }
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_Unbound() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.mState.setForcingToImportant(new Object());
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        app2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);

        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, app2);

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app2);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.mState.setForcingToImportant(new Object());
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        app2.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(app, app2, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, app2);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(app2);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundFgService_Cycle() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, app2, null, 0, mock(IBinder.class));
        ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app2, app3, null, 0, mock(IBinder.class));
        app3.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(app3, app, null, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, app2, app3);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertEquals("service", app.mState.getAdjType());
        assertEquals("service", app2.mState.getAdjType());
        assertEquals("fg-service", app3.mState.getAdjType());
        assertEquals(false, app.isCached());
        assertEquals(false, app2.isCached());
        assertEquals(false, app3.isCached());
        assertEquals(false, app.mState.isEmpty());
        assertEquals(false, app2.mState.isEmpty());
        assertEquals(false, app3.mState.isEmpty());
        assertBfsl(app);
        assertBfsl(app2);
        assertBfsl(app3);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundFgService_Cycle_Branch_2() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        ServiceRecord s = bindService(app, app2, null, 0, mock(IBinder.class));
        ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app2, app3, null, 0, mock(IBinder.class));
        bindService(app3, app, null, 0, mock(IBinder.class));
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.mState.setHasOverlayUi(true);
        bindService(app, app4, s, 0, mock(IBinder.class));
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(app, app5, s, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, app2, app3, app4, app5);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app4, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app5, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(app2);
        assertBfsl(app3);
        // 4 is IMP_FG
        assertBfsl(app5);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundFgService_Cycle_Branch_3() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        ServiceRecord s = bindService(app, app2, null, 0, mock(IBinder.class));
        ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app2, app3, null, 0, mock(IBinder.class));
        bindService(app3, app, null, 0, mock(IBinder.class));
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.mState.setHasOverlayUi(true);
        bindService(app, app4, s, 0, mock(IBinder.class));
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(app, app5, s, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app5, app4, app3, app2, app);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app4, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app5, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(app2);
        assertBfsl(app3);
        // 4 is IMP_FG
        assertBfsl(app5);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundFgService_Cycle_Branch_4() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        ServiceRecord s = bindService(app, app2, null, 0, mock(IBinder.class));
        ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app2, app3, null, 0, mock(IBinder.class));
        bindService(app3, app, null, 0, mock(IBinder.class));
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.mState.setHasOverlayUi(true);
        bindService(app, app4, s, 0, mock(IBinder.class));
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindService(app, app5, s, 0, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app3, app4, app2, app, app5);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app4, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app5, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(app2);
        assertBfsl(app3);
        // 4 is IMP_FG
        assertBfsl(app5);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundByPersService_Cycle_Branch_Capability() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_INCLUDE_CAPABILITIES, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(client, client2, null, Context.BIND_INCLUDE_CAPABILITIES, mock(IBinder.class));
        bindService(client2, app, null, Context.BIND_INCLUDE_CAPABILITIES, mock(IBinder.class));
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.mState.setMaxAdj(PERSISTENT_PROC_ADJ);
        bindService(app, client3, null, Context.BIND_INCLUDE_CAPABILITIES, mock(IBinder.class));
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, client, client2, client3);

        final int expected = PROCESS_CAPABILITY_ALL & ~PROCESS_CAPABILITY_BFSL;
        assertEquals(expected, client.mState.getSetCapability());
        assertEquals(expected, client2.mState.getSetCapability());
        assertEquals(expected, app.mState.getSetCapability());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_Provider_Cycle_Branch_2() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        ContentProviderRecord cr = bindProvider(app, app2, null, null, false);
        ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindProvider(app2, app3, null, null, false);
        bindProvider(app3, app, null, null, false);
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.mState.setHasOverlayUi(true);
        bindProvider(app, app4, cr, null, false);
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.mServices.setHasForegroundServices(true, 0, /* hasNoneType=*/true);
        bindProvider(app, app5, cr, null, false);
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        updateOomAdj(app, app2, app3, app4, app5);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app3, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app4, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app5, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertBfsl(app);
        assertBfsl(app2);
        assertBfsl(app3);
        // 4 is IMP_FG
        assertBfsl(app5);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_ServiceB() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        long now = SystemClock.uptimeMillis();
        ServiceRecord s = bindService(app, app2, null, 0, mock(IBinder.class));
        s.startRequested = true;
        s.lastActivity = now;
        s = bindService(app2, app, null, 0, mock(IBinder.class));
        s.startRequested = true;
        s.lastActivity = now;
        ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        s = mock(ServiceRecord.class);
        s.app = app3;
        setFieldValue(ServiceRecord.class, s, "connections",
                new ArrayMap<IBinder, ArrayList<ConnectionRecord>>());
        app3.mServices.startService(s);
        doCallRealMethod().when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = now;
        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        sService.mOomAdjuster.mNumServiceProcs = 3;
        updateOomAdj(app3, app2, app);

        assertEquals(SERVICE_B_ADJ, app3.mState.getSetAdj());
        assertEquals(SERVICE_ADJ, app2.mState.getSetAdj());
        assertEquals(SERVICE_ADJ, app.mState.getSetAdj());
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_Service_KeepWarmingList() {
        final ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID_OTHER,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        final int userOwner = 0;
        final int userOther = 1;
        final int cachedAdj1 = sService.mConstants.USE_TIERED_CACHED_ADJ
                               ? CACHED_APP_MIN_ADJ + 10
                               : CACHED_APP_MIN_ADJ + ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
        final int cachedAdj2 = sService.mConstants.USE_TIERED_CACHED_ADJ
                               ? CACHED_APP_MIN_ADJ + 10
                               : cachedAdj1 + ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2;
        doReturn(userOwner).when(sService.mUserController).getCurrentUserId();

        final ArrayList<ProcessRecord> lru = sService.mProcessList.getLruProcessesLOSP();
        lru.clear();
        lru.add(app2);
        lru.add(app);

        final ComponentName cn = ComponentName.unflattenFromString(
                MOCKAPP_PACKAGENAME + "/.TestService");
        final ComponentName cn2 = ComponentName.unflattenFromString(
                MOCKAPP2_PACKAGENAME + "/.TestService");
        final long now = SystemClock.uptimeMillis();

        sService.mConstants.KEEP_WARMING_SERVICES.clear();
        final ServiceInfo si = mock(ServiceInfo.class);
        si.applicationInfo = mock(ApplicationInfo.class);
        ServiceRecord s = spy(new ServiceRecord(sService, cn, cn, null, 0, null,
                si, false, null));
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = now;

        app.mState.setCached(false);
        app.mServices.startService(s);
        app.mState.setHasShownUi(true);

        final ServiceInfo si2 = mock(ServiceInfo.class);
        si2.applicationInfo = mock(ApplicationInfo.class);
        si2.applicationInfo.uid = MOCKAPP2_UID_OTHER;
        ServiceRecord s2 = spy(new ServiceRecord(sService, cn2, cn2, null, 0, null,
                si2, false, null));
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s2).getConnections();
        s2.startRequested = true;
        s2.lastActivity = now - sService.mConstants.MAX_SERVICE_INACTIVITY - 1;

        app2.mState.setCached(false);
        app2.mServices.startService(s2);
        app2.mState.setHasShownUi(false);

        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);

        assertProcStates(app, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-ui-services");
        assertProcStates(app2, true, PROCESS_STATE_SERVICE, cachedAdj2, "cch-started-services");

        app.mState.setSetProcState(PROCESS_STATE_NONEXISTENT);
        app.mState.setAdjType(null);
        app.mState.setSetAdj(UNKNOWN_ADJ);
        app.mState.setHasShownUi(false);
        sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);

        assertProcStates(app, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");

        app.mState.setCached(false);
        app.mState.setSetProcState(PROCESS_STATE_NONEXISTENT);
        app.mState.setAdjType(null);
        app.mState.setSetAdj(UNKNOWN_ADJ);
        s.lastActivity = now - sService.mConstants.MAX_SERVICE_INACTIVITY - 1;
        sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);

        assertProcStates(app, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");

        app.mServices.stopService(s);
        app.mState.setSetProcState(PROCESS_STATE_NONEXISTENT);
        app.mState.setAdjType(null);
        app.mState.setSetAdj(UNKNOWN_ADJ);
        app.mState.setHasShownUi(true);
        sService.mConstants.KEEP_WARMING_SERVICES.add(cn);
        sService.mConstants.KEEP_WARMING_SERVICES.add(cn2);
        s = spy(new ServiceRecord(sService, cn, cn, null, 0, null,
                si, false, null));
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = now;

        app.mServices.startService(s);
        sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);

        assertProcStates(app, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");
        assertProcStates(app2, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");

        app.mState.setCached(true);
        app.mState.setSetProcState(PROCESS_STATE_NONEXISTENT);
        app.mState.setAdjType(null);
        app.mState.setSetAdj(UNKNOWN_ADJ);
        app.mState.setHasShownUi(false);
        s.lastActivity = now - sService.mConstants.MAX_SERVICE_INACTIVITY - 1;
        sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);

        assertProcStates(app, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");
        assertProcStates(app2, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");

        doReturn(userOther).when(sService.mUserController).getCurrentUserId();
        sService.mOomAdjuster.handleUserSwitchedLocked();

        sService.mOomAdjuster.updateOomAdjLocked(OOM_ADJ_REASON_NONE);
        assertProcStates(app, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");
        assertProcStates(app2, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_Side_Cycle() {
        final ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        final ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        final ProcessRecord app3 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        long now = SystemClock.uptimeMillis();
        ServiceRecord s = bindService(app, app2, null, 0, mock(IBinder.class));
        s.startRequested = true;
        s.lastActivity = now;
        s = bindService(app2, app3, null, 0, mock(IBinder.class));
        s.lastActivity = now;
        s = bindService(app3, app2, null, 0, mock(IBinder.class));
        s.lastActivity = now;

        sService.mWakefulness.set(PowerManagerInternal.WAKEFULNESS_AWAKE);
        sService.mOomAdjuster.mNumServiceProcs = 3;
        updateOomAdj(app, app2, app3);

        assertEquals(SERVICE_ADJ, app.mState.getSetAdj());
        assertTrue(sFirstCachedAdj <= app2.mState.getSetAdj());
        assertTrue(sFirstCachedAdj <= app3.mState.getSetAdj());
        assertTrue(CACHED_APP_MAX_ADJ >= app2.mState.getSetAdj());
        assertTrue(CACHED_APP_MAX_ADJ >= app3.mState.getSetAdj());
    }

    private ProcessRecord makeDefaultProcessRecord(int pid, int uid, String processName,
            String packageName, boolean hasShownUi) {
        long now = SystemClock.uptimeMillis();
        return makeProcessRecord(sService, pid, uid, processName,
                packageName, 12345, Build.VERSION_CODES.CUR_DEVELOPMENT,
                now, now, now, 12345, UNKNOWN_ADJ, UNKNOWN_ADJ,
                UNKNOWN_ADJ, CACHED_APP_MAX_ADJ,
                SCHED_GROUP_DEFAULT, SCHED_GROUP_DEFAULT,
                PROCESS_STATE_NONEXISTENT, PROCESS_STATE_NONEXISTENT,
                PROCESS_STATE_NONEXISTENT, PROCESS_STATE_NONEXISTENT,
                0, 0, false, false, false, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                false, false, false, hasShownUi, false, false, false, false, false, false, null,
                0, Long.MIN_VALUE, Long.MIN_VALUE, true, 0, null, false);
    }

    private ProcessRecord makeProcessRecord(ActivityManagerService service, int pid, int uid,
            String processName, String packageName, long versionCode, int targetSdkVersion,
            long lastActivityTime, long lastPssTime, long nextPssTime, long lastPss, int maxAdj,
            int setRawAdj, int curAdj, int setAdj, int curSchedGroup, int setSchedGroup,
            int curProcState, int repProcState, int curRawProcState, int setProcState,
            int connectionGroup, int connectionImportance, boolean serviceb,
            boolean hasClientActivities, boolean hasForegroundServices, int fgServiceTypes,
            boolean hasForegroundActivities, boolean repForegroundActivities, boolean systemNoUi,
            boolean hasShownUi, boolean hasTopUi, boolean hasOverlayUi,
            boolean runningRemoteAnimation, boolean hasAboveClient, boolean treatLikeActivity,
            boolean killedByAm, Object forcingToImportant, int numOfCurReceivers,
            long lastProviderTime, long lastTopTime, boolean cached, int numOfExecutingServices,
            String isolatedEntryPoint, boolean execServicesFg) {
        ApplicationInfo ai = spy(new ApplicationInfo());
        ai.uid = uid;
        ai.packageName = packageName;
        ai.longVersionCode = versionCode;
        ai.targetSdkVersion = targetSdkVersion;
        ProcessRecord app = new ProcessRecord(service, ai, processName, uid);
        final ProcessStateRecord state = app.mState;
        final ProcessServiceRecord services = app.mServices;
        final ProcessReceiverRecord receivers = app.mReceivers;
        final ProcessProfileRecord profile = app.mProfile;
        final ProcessProviderRecord providers = app.mProviders;
        app.makeActive(mock(IApplicationThread.class), sService.mProcessStats);
        app.setLastActivityTime(lastActivityTime);
        app.setKilledByAm(killedByAm);
        app.setIsolatedEntryPoint(isolatedEntryPoint);
        setFieldValue(ProcessRecord.class, app, "mWindowProcessController",
                mock(WindowProcessController.class));
        profile.setLastPssTime(lastPssTime);
        profile.setNextPssTime(nextPssTime);
        profile.setLastPss(lastPss);
        state.setMaxAdj(maxAdj);
        state.setSetRawAdj(setRawAdj);
        state.setCurAdj(curAdj);
        state.setSetAdj(setAdj);
        state.setCurrentSchedulingGroup(curSchedGroup);
        state.setSetSchedGroup(setSchedGroup);
        state.setCurProcState(curProcState);
        state.setReportedProcState(repProcState);
        state.setCurRawProcState(curRawProcState);
        state.setSetProcState(setProcState);
        state.setServiceB(serviceb);
        state.setRepForegroundActivities(repForegroundActivities);
        state.setHasForegroundActivities(hasForegroundActivities);
        state.setSystemNoUi(systemNoUi);
        state.setHasShownUi(hasShownUi);
        state.setHasTopUi(hasTopUi);
        state.setRunningRemoteAnimation(runningRemoteAnimation);
        state.setHasOverlayUi(hasOverlayUi);
        state.setCached(cached);
        state.setLastTopTime(lastTopTime);
        state.setForcingToImportant(forcingToImportant);
        services.setConnectionGroup(connectionGroup);
        services.setConnectionImportance(connectionImportance);
        services.setHasClientActivities(hasClientActivities);
        services.setHasForegroundServices(hasForegroundServices, fgServiceTypes,
                /* hasNoneType=*/false);
        services.setHasAboveClient(hasAboveClient);
        services.setTreatLikeActivity(treatLikeActivity);
        services.setExecServicesFg(execServicesFg);
        for (int i = 0; i < numOfExecutingServices; i++) {
            services.startExecutingService(mock(ServiceRecord.class));
        }
        for (int i = 0; i < numOfCurReceivers; i++) {
            receivers.addCurReceiver(mock(BroadcastRecord.class));
        }
        providers.setLastProviderTime(lastProviderTime);
        return app;
    }

    private ServiceRecord makeServiceRecord(ProcessRecord app) {
        final ServiceRecord record = mock(ServiceRecord.class);
        record.app = app;
        setFieldValue(ServiceRecord.class, record, "connections",
                new ArrayMap<IBinder, ArrayList<ConnectionRecord>>());
        doCallRealMethod().when(record).getConnections();
        setFieldValue(ServiceRecord.class, record, "packageName", app.info.packageName);
        app.mServices.startService(record);
        record.appInfo = app.info;
        setFieldValue(ServiceRecord.class, record, "bindings", new ArrayMap<>());
        setFieldValue(ServiceRecord.class, record, "pendingStarts", new ArrayList<>());
        return record;
    }

    private void setServiceMap(ServiceRecord s, int uid, ComponentName cn) {
        ActiveServices.ServiceMap serviceMap = sService.mServices.mServiceMap.get(
                UserHandle.getUserId(uid));
        if (serviceMap == null) {
            serviceMap = mock(ActiveServices.ServiceMap.class);
            setFieldValue(ActiveServices.ServiceMap.class, serviceMap, "mServicesByInstanceName",
                    new ArrayMap<>());
            setFieldValue(ActiveServices.ServiceMap.class, serviceMap, "mActiveForegroundApps",
                    new ArrayMap<>());
            setFieldValue(ActiveServices.ServiceMap.class, serviceMap, "mServicesByIntent",
                    new ArrayMap<>());
            setFieldValue(ActiveServices.ServiceMap.class, serviceMap, "mDelayedStartList",
                    new ArrayList<>());
            sService.mServices.mServiceMap.put(UserHandle.getUserId(uid), serviceMap);
        }
        serviceMap.mServicesByInstanceName.put(cn, s);
    }

    private ServiceRecord bindService(ProcessRecord service, ProcessRecord client,
            ServiceRecord record, long bindFlags, IBinder binder) {
        if (record == null) {
            record = makeServiceRecord(service);
        }
        AppBindRecord binding = new AppBindRecord(record, null, client, null);
        ConnectionRecord cr = spy(new ConnectionRecord(binding,
                mock(ActivityServiceConnectionsHolder.class),
                mock(IServiceConnection.class), bindFlags,
                0, null, client.uid, client.processName, client.info.packageName, null));
        doCallRealMethod().when(record).addConnection(any(IBinder.class),
                any(ConnectionRecord.class));
        record.addConnection(binder, cr);
        client.mServices.addConnection(cr);
        binding.connections.add(cr);
        doNothing().when(cr).trackProcState(anyInt(), anyInt());
        return record;
    }

    private ContentProviderRecord bindProvider(ProcessRecord publisher, ProcessRecord client,
            ContentProviderRecord record, String name, boolean hasExternalProviders) {
        if (record == null) {
            record = mock(ContentProviderRecord.class);
            publisher.mProviders.installProvider(name, record);
            record.proc = publisher;
            setFieldValue(ContentProviderRecord.class, record, "connections",
                    new ArrayList<ContentProviderConnection>());
            doReturn(hasExternalProviders).when(record).hasExternalProcessHandles();
        }
        ContentProviderConnection conn = spy(new ContentProviderConnection(record, client,
                client.info.packageName, UserHandle.getUserId(client.uid)));
        record.connections.add(conn);
        client.mProviders.addProviderConnection(conn);
        return record;
    }

    private void assertProcStates(ProcessRecord app, int expectedProcState, int expectedAdj,
            int expectedSchedGroup) {
        final ProcessStateRecord state = app.mState;
        assertEquals(expectedProcState, state.getSetProcState());
        assertEquals(expectedAdj, state.getSetAdj());
        assertEquals(expectedSchedGroup, state.getSetSchedGroup());

        // Below BFGS should never have BFSL.
        if (expectedProcState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            assertNoBfsl(app);
        }
        // Above FGS should always have BFSL.
        if (expectedProcState < PROCESS_STATE_FOREGROUND_SERVICE) {
            assertBfsl(app);
        }
    }

    private void assertProcStates(ProcessRecord app, boolean expectedCached,
            int expectedProcState, int expectedAdj, String expectedAdjType) {
        final ProcessStateRecord state = app.mState;
        assertEquals(expectedCached, state.isCached());
        assertEquals(expectedProcState, state.getSetProcState());
        assertEquals(expectedAdj, state.getSetAdj());
        assertEquals(expectedAdjType, state.getAdjType());

        // Below BFGS should never have BFSL.
        if (expectedProcState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            assertNoBfsl(app);
        }
        // Above FGS should always have BFSL.
        if (expectedProcState < PROCESS_STATE_FOREGROUND_SERVICE) {
            assertBfsl(app);
        }
    }
}
