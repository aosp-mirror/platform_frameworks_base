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
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TOP_SLEEPING;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.ProcessList.BACKUP_APP_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MAX_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;
import static com.android.server.am.ProcessList.FOREGROUND_APP_ADJ;
import static com.android.server.am.ProcessList.HEAVY_WEIGHT_APP_ADJ;
import static com.android.server.am.ProcessList.HOME_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.answer;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

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
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowProcessController;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

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
    private static Context sContext;
    private static PackageManagerInternal sPackageManagerInternal;
    private static ActivityManagerService sService;

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
        ProcessList pr = new ProcessList();
        pr.init(sService, new ActiveUids(sService, false), null);
        setFieldValue(ActivityManagerService.class, sService, "mProcessList",
                pr);
        setFieldValue(ActivityManagerService.class, sService, "mHandler",
                mock(ActivityManagerService.MainHandler.class));
        setFieldValue(ActivityManagerService.class, sService, "mProcessStats",
                mock(ProcessStatsService.class));
        setFieldValue(ActivityManagerService.class, sService, "mBackupTargets",
                mock(SparseArray.class));
        setFieldValue(ActivityManagerService.class, sService, "mOomAdjProfiler",
                mock(OomAdjProfiler.class));
        setFieldValue(ActivityManagerService.class, sService, "mUserController",
                mock(UserController.class));
        doReturn(new ActivityManagerService.ProcessChangeItem()).when(sService)
                .enqueueProcessChangeItemLocked(anyInt(), anyInt());
        sService.mOomAdjuster = new OomAdjuster(sService, sService.mProcessList,
                mock(ActiveUids.class));
        sService.mOomAdjuster.mAdjSeq = 10000;
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

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Persistent_TopUi_Sleeping() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.maxAdj = PERSISTENT_PROC_ADJ;
        app.setHasTopUi(true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_ASLEEP;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERSISTENT_PROC_ADJ,
                SCHED_GROUP_RESTRICTED);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Persistent_TopUi_Awake() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.maxAdj = PERSISTENT_PROC_ADJ;
        app.setHasTopUi(true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_PERSISTENT_UI, PERSISTENT_PROC_ADJ,
                SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Persistent_TopApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.maxAdj = PERSISTENT_PROC_ADJ;
        doReturn(app).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

        assertProcStates(app, PROCESS_STATE_PERSISTENT_UI, PERSISTENT_PROC_ADJ,
                SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TopApp_Awake() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(app).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

        assertProcStates(app, PROCESS_STATE_TOP, FOREGROUND_APP_ADJ, SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_RunningAnimations() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(PROCESS_STATE_TOP_SLEEPING).when(sService.mAtmInternal).getTopProcessState();
        app.runningRemoteAnimation = true;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();

        assertProcStates(app, PROCESS_STATE_TOP_SLEEPING, VISIBLE_APP_ADJ, SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_RunningInstrumentation() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(ActiveInstrumentation.class)).when(app).getActiveInstrumentation();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doCallRealMethod().when(app).getActiveInstrumentation();

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, FOREGROUND_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ReceivingBroadcast() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(true).when(sService).isReceivingBroadcastLocked(any(ProcessRecord.class),
                any(ArraySet.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(false).when(sService).isReceivingBroadcastLocked(any(ProcessRecord.class),
                any(ArraySet.class));

        assertProcStates(app, PROCESS_STATE_RECEIVER, FOREGROUND_APP_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ExecutingService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.executingServices.add(mock(ServiceRecord.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_SERVICE, FOREGROUND_APP_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TopApp_Sleeping() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(PROCESS_STATE_TOP_SLEEPING).when(sService.mAtmInternal).getTopProcessState();
        doReturn(app).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_ASLEEP;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;

        assertProcStates(app, PROCESS_STATE_TOP_SLEEPING, FOREGROUND_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_CachedEmpty() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.setCurRawAdj(CACHED_APP_MIN_ADJ);
        doReturn(null).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, CACHED_APP_MIN_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_VisibleActivities() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(WindowProcessController.class)).when(app).getWindowProcessController();
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).hasActivities();
        doAnswer(answer((minTaskLayer, callback) -> {
            Field field = callback.getClass().getDeclaredField("adj");
            field.set(callback, VISIBLE_APP_ADJ);
            field = callback.getClass().getDeclaredField("foregroundActivities");
            field.set(callback, true);
            field = callback.getClass().getDeclaredField("procState");
            field.set(callback, PROCESS_STATE_TOP);
            field = callback.getClass().getDeclaredField("schedGroup");
            field.set(callback, SCHED_GROUP_TOP_APP);
            return 0;
        })).when(wpc).computeOomAdjFromActivities(anyInt(),
                any(WindowProcessController.ComputeOomAdjCallback.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doCallRealMethod().when(app).getWindowProcessController();

        assertProcStates(app, PROCESS_STATE_TOP, VISIBLE_APP_ADJ, SCHED_GROUP_TOP_APP);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_RecentTasks() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(WindowProcessController.class)).when(app).getWindowProcessController();
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).hasRecentTasks();
        app.lastTopTime = SystemClock.uptimeMillis();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doCallRealMethod().when(wpc).hasRecentTasks();

        assertEquals(PROCESS_STATE_CACHED_RECENT, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_FgServiceLocation() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.setHasForegroundServices(true, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_FgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.setHasForegroundServices(true, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_OverlayUi() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.setHasOverlayUi(true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_IMPORTANT_FOREGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_PerceptibleRecent() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.setHasForegroundServices(true, 0);
        app.lastTopTime = SystemClock.uptimeMillis();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ, SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Toast() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.forcingToImportant = new Object();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_HeavyWeight() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(true).when(sService.mAtmInternal).isHeavyWeightProcess(any(
                WindowProcessController.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(false).when(sService.mAtmInternal).isHeavyWeightProcess(any(
                WindowProcessController.class));

        assertProcStates(app, PROCESS_STATE_HEAVY_WEIGHT, HEAVY_WEIGHT_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_HomeApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(WindowProcessController.class)).when(app).getWindowProcessController();
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_HOME, HOME_APP_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_PreviousApp() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(WindowProcessController.class)).when(app).getWindowProcessController();
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).isPreviousProcess();
        doReturn(true).when(wpc).hasActivities();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_LAST_ACTIVITY, PREVIOUS_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Backup() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        BackupRecord backupTarget = new BackupRecord(null, 0, 0);
        backupTarget.app = app;
        doReturn(backupTarget).when(sService.mBackupTargets).get(anyInt());
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService.mBackupTargets).get(anyInt());

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, BACKUP_APP_ADJ,
                SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ClientActivities() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(true).when(app).hasClientActivities();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_CACHED_ACTIVITY_CLIENT, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_TreatLikeActivity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        app.treatLikeActivity = true;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_CACHED_ACTIVITY, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_ServiceB() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.serviceb = true;
        ServiceRecord s = mock(ServiceRecord.class);
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = SystemClock.uptimeMillis();
        app.startService(s);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_SERVICE, SERVICE_B_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_MaxAdj() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.maxAdj = PERCEPTIBLE_LOW_APP_ADJ;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, PERCEPTIBLE_LOW_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_NonCachedToCached() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.setCached(false);
        app.setCurRawAdj(SERVICE_ADJ);
        doReturn(null).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertTrue(ProcessList.CACHED_APP_MIN_ADJ <= app.setAdj);
        assertTrue(ProcessList.CACHED_APP_MAX_ADJ >= app.setAdj);
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
        app.startService(s);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

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
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client).when(sService).getTopAppLocked();
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

        assertProcStates(app, PROCESS_STATE_SERVICE, UNKNOWN_ADJ, SCHED_GROUP_BACKGROUND);
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
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_CACHED_ACTIVITY, app.setProcState);
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
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(FOREGROUND_APP_ADJ, app.setAdj);
        assertEquals(SCHED_GROUP_TOP_APP_BOUND, app.setSchedGroup);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Self() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        bindService(app, app, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, UNKNOWN_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_CachedActivity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        client.treatLikeActivity = true;
        bindService(app, client, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_CACHED_EMPTY, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_AllowOomManagement() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, true));
        doReturn(mock(WindowProcessController.class)).when(app).getWindowProcessController();
        WindowProcessController wpc = app.getWindowProcessController();
        doReturn(false).when(wpc).isHomeProcess();
        doReturn(true).when(wpc).isPreviousProcess();
        doReturn(true).when(wpc).hasActivities();
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_ALLOW_OOM_MANAGEMENT, mock(IBinder.class));
        doReturn(PROCESS_STATE_TOP).when(sService.mAtmInternal).getTopProcessState();
        doReturn(client).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

        assertEquals(PREVIOUS_APP_ADJ, app.setAdj);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundByPersistentService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_FOREGROUND_SERVICE, mock(IBinder.class));
        client.maxAdj = PERSISTENT_PROC_ADJ;
        client.setHasTopUi(true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, VISIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Bound_ImportantFg() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_IMPORTANT, mock(IBinder.class));
        client.executingServices.add(mock(ServiceRecord.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(FOREGROUND_APP_ADJ, app.setAdj);
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
        doReturn(client).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

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
        client.maxAdj = PERSISTENT_PROC_ADJ;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_BOUND_FOREGROUND_SERVICE, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundNotForeground() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_NOT_FOREGROUND, mock(IBinder.class));
        client.maxAdj = PERSISTENT_PROC_ADJ;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_TRANSIENT_BACKGROUND, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_ImportantFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        client.setHasForegroundServices(true, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_FOREGROUND_SERVICE, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_BoundByBackup_AboveClient() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_ABOVE_CLIENT, mock(IBinder.class));
        BackupRecord backupTarget = new BackupRecord(null, 0, 0);
        backupTarget.app = client;
        doReturn(backupTarget).when(sService.mBackupTargets).get(anyInt());
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService.mBackupTargets).get(anyInt());

        assertEquals(BACKUP_APP_ADJ, app.setAdj);

        client.maxAdj = PERSISTENT_PROC_ADJ;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PERSISTENT_SERVICE_ADJ, app.setAdj);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_NotPerceptible() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_NOT_PERCEPTIBLE, mock(IBinder.class));
        client.runningRemoteAnimation = true;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PERCEPTIBLE_LOW_APP_ADJ, app.setAdj);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_NotVisible() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_NOT_VISIBLE, mock(IBinder.class));
        client.runningRemoteAnimation = true;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PERCEPTIBLE_APP_ADJ, app.setAdj);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Perceptible() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        client.setHasOverlayUi(true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PERCEPTIBLE_APP_ADJ, app.setAdj);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Other() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, 0, mock(IBinder.class));
        client.runningRemoteAnimation = true;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(VISIBLE_APP_ADJ, app.setAdj);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Bind_ImportantBg() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindService(app, client, null, Context.BIND_IMPORTANT_BACKGROUND, mock(IBinder.class));
        client.setHasOverlayUi(true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_IMPORTANT_BACKGROUND, app.setProcState);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Self() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        bindProvider(app, app, null, null, false);
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, UNKNOWN_ADJ, SCHED_GROUP_BACKGROUND);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Cached_Activity() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, false);
        client.treatLikeActivity = true;
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_CACHED_EMPTY, UNKNOWN_ADJ, SCHED_GROUP_BACKGROUND);
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
        doReturn(client).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

        assertProcStates(app, PROCESS_STATE_BOUND_TOP, FOREGROUND_APP_ADJ, SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_FgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        client.setHasForegroundServices(true, 0);
        bindProvider(app, client, null, null, false);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_ExternalProcessHandles() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        bindProvider(app, client, null, null, true);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_IMPORTANT_FOREGROUND, FOREGROUND_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Provider_Retention() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.lastProviderTime = SystemClock.uptimeMillis();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

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
        doReturn(client2).when(sService).getTopAppLocked();
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);
        doReturn(null).when(sService).getTopAppLocked();

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
        client2.setHasForegroundServices(true, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        bindService(client2, app, null, 0, mock(IBinder.class));
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(client);
        lru.add(client2);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, true, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);

        client2.setHasForegroundServices(false, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(client2, true, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertEquals(PROCESS_STATE_CACHED_EMPTY, client2.setProcState);
        assertEquals(PROCESS_STATE_CACHED_EMPTY, client.setProcState);
        assertEquals(PROCESS_STATE_CACHED_EMPTY, app.setProcState);
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
        client.setHasForegroundServices(true, 0);
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(client);
        lru.add(client2);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, true, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(client2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        bindService(client2, app, null, 0, mock(IBinder.class));
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.forcingToImportant = new Object();
        bindService(app, client3, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        doReturn(mock(WindowProcessController.class)).when(client2).getWindowProcessController();
        WindowProcessController wpc = client2.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.forcingToImportant = new Object();
        bindService(app, client3, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

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
        doReturn(mock(WindowProcessController.class)).when(client2).getWindowProcessController();
        WindowProcessController wpc = client2.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        ProcessRecord client4 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        client4.forcingToImportant = new Object();
        bindService(app, client4, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

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
        doReturn(mock(WindowProcessController.class)).when(client2).getWindowProcessController();
        WindowProcessController wpc = client2.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.forcingToImportant = new Object();
        bindService(app, client3, null, 0, mock(IBinder.class));
        ProcessRecord client4 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        client4.setHasForegroundServices(true, 0);
        bindService(app, client4, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoOne_Service_Chain_BoundByFgService_Branch_3() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        ProcessRecord client = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        doReturn(mock(WindowProcessController.class)).when(client).getWindowProcessController();
        WindowProcessController wpc = client.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        bindService(app, client, null, 0, mock(IBinder.class));
        ProcessRecord client2 = spy(makeDefaultProcessRecord(MOCKAPP3_PID, MOCKAPP3_UID,
                MOCKAPP3_PROCESSNAME, MOCKAPP3_PACKAGENAME, false));
        bindService(app, client2, null, 0, mock(IBinder.class));
        client2.setHasForegroundServices(true, 0);
        ProcessRecord client3 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        client3.forcingToImportant = new Object();
        bindService(app, client3, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        bindService(client2, app, null, 0, mock(IBinder.class));
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        client2.setHasForegroundServices(true, 0);
        bindProvider(client2, app, null, null, false);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(app, false, OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, PROCESS_STATE_BOUND_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_Unbound() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.forcingToImportant = new Object();
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        app2.setHasForegroundServices(true, 0);
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(app2);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

        assertProcStates(app, PROCESS_STATE_TRANSIENT_BACKGROUND, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testUpdateOomAdj_DoAll_BoundFgService() {
        ProcessRecord app = spy(makeDefaultProcessRecord(MOCKAPP_PID, MOCKAPP_UID,
                MOCKAPP_PROCESSNAME, MOCKAPP_PACKAGENAME, false));
        app.forcingToImportant = new Object();
        ProcessRecord app2 = spy(makeDefaultProcessRecord(MOCKAPP2_PID, MOCKAPP2_UID,
                MOCKAPP2_PROCESSNAME, MOCKAPP2_PACKAGENAME, false));
        app2.setHasForegroundServices(true, 0);
        bindService(app, app2, null, 0, mock(IBinder.class));
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(app2);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
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
        app3.setHasForegroundServices(true, 0);
        bindService(app3, app, null, 0, mock(IBinder.class));
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(app2);
        lru.add(app3);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

        assertProcStates(app, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app2, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertProcStates(app3, PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                SCHED_GROUP_DEFAULT);
        assertEquals("service", app.adjType);
        assertEquals("service", app2.adjType);
        assertEquals("fg-service", app3.adjType);
        assertEquals(false, app.isCached());
        assertEquals(false, app2.isCached());
        assertEquals(false, app3.isCached());
        assertEquals(false, app.empty);
        assertEquals(false, app2.empty);
        assertEquals(false, app3.empty);
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
        doReturn(mock(WindowProcessController.class)).when(app3).getWindowProcessController();
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.setHasOverlayUi(true);
        bindService(app, app4, s, 0, mock(IBinder.class));
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.setHasForegroundServices(true, 0);
        bindService(app, app5, s, 0, mock(IBinder.class));
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(app2);
        lru.add(app3);
        lru.add(app4);
        lru.add(app5);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

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
        doReturn(mock(WindowProcessController.class)).when(app3).getWindowProcessController();
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.setHasOverlayUi(true);
        bindService(app, app4, s, 0, mock(IBinder.class));
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.setHasForegroundServices(true, 0);
        bindService(app, app5, s, 0, mock(IBinder.class));
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app5);
        lru.add(app4);
        lru.add(app3);
        lru.add(app2);
        lru.add(app);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

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
        doReturn(mock(WindowProcessController.class)).when(app3).getWindowProcessController();
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.setHasOverlayUi(true);
        bindService(app, app4, s, 0, mock(IBinder.class));
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.setHasForegroundServices(true, 0);
        bindService(app, app5, s, 0, mock(IBinder.class));
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app3);
        lru.add(app4);
        lru.add(app2);
        lru.add(app);
        lru.add(app5);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

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
        doReturn(mock(WindowProcessController.class)).when(app3).getWindowProcessController();
        WindowProcessController wpc = app3.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
        ProcessRecord app4 = spy(makeDefaultProcessRecord(MOCKAPP4_PID, MOCKAPP4_UID,
                MOCKAPP4_PROCESSNAME, MOCKAPP4_PACKAGENAME, false));
        app4.setHasOverlayUi(true);
        bindProvider(app, app4, cr, null, false);
        ProcessRecord app5 = spy(makeDefaultProcessRecord(MOCKAPP5_PID, MOCKAPP5_UID,
                MOCKAPP5_PROCESSNAME, MOCKAPP5_PACKAGENAME, false));
        app5.setHasForegroundServices(true, 0);
        bindProvider(app, app5, cr, null, false);
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app);
        lru.add(app2);
        lru.add(app3);
        lru.add(app4);
        lru.add(app5);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

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
        app3.startService(s);
        doCallRealMethod().when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = now;
        ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
        lru.clear();
        lru.add(app3);
        lru.add(app2);
        lru.add(app);
        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.mNumServiceProcs = 3;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        lru.clear();

        assertEquals(SERVICE_B_ADJ, app3.setAdj);
        assertEquals(SERVICE_ADJ, app2.setAdj);
        assertEquals(SERVICE_ADJ, app.setAdj);
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
        final int cachedAdj1 = CACHED_APP_MIN_ADJ + ProcessList.CACHED_APP_IMPORTANCE_LEVELS;
        final int cachedAdj2 = cachedAdj1 + ProcessList.CACHED_APP_IMPORTANCE_LEVELS * 2;
        doReturn(userOwner).when(sService.mUserController).getCurrentUserId();

        final ArrayList<ProcessRecord> lru = sService.mProcessList.mLruProcesses;
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
        ServiceRecord s = spy(new ServiceRecord(sService, null, cn, cn, null, 0, null,
                si, false, null));
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = now;

        app.setCached(false);
        app.startService(s);
        app.hasShownUi = true;

        final ServiceInfo si2 = mock(ServiceInfo.class);
        si2.applicationInfo = mock(ApplicationInfo.class);
        si2.applicationInfo.uid = MOCKAPP2_UID_OTHER;
        ServiceRecord s2 = spy(new ServiceRecord(sService, null, cn2, cn2, null, 0, null,
                si2, false, null));
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s2).getConnections();
        s2.startRequested = true;
        s2.lastActivity = now - sService.mConstants.MAX_SERVICE_INACTIVITY - 1;

        app2.setCached(false);
        app2.startService(s2);
        app2.hasShownUi = false;

        sService.mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-ui-services");
        assertProcStates(app2, true, PROCESS_STATE_SERVICE, cachedAdj2, "cch-started-services");

        app.setProcState = PROCESS_STATE_NONEXISTENT;
        app.adjType = null;
        app.setAdj = UNKNOWN_ADJ;
        app.hasShownUi = false;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");

        app.setCached(false);
        app.setProcState = PROCESS_STATE_NONEXISTENT;
        app.adjType = null;
        app.setAdj = UNKNOWN_ADJ;
        s.lastActivity = now - sService.mConstants.MAX_SERVICE_INACTIVITY - 1;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");

        app.stopService(s);
        app.setProcState = PROCESS_STATE_NONEXISTENT;
        app.adjType = null;
        app.setAdj = UNKNOWN_ADJ;
        app.hasShownUi = true;
        sService.mConstants.KEEP_WARMING_SERVICES.add(cn);
        sService.mConstants.KEEP_WARMING_SERVICES.add(cn2);
        s = spy(new ServiceRecord(sService, null, cn, cn, null, 0, null,
                si, false, null));
        doReturn(new ArrayMap<IBinder, ArrayList<ConnectionRecord>>()).when(s).getConnections();
        s.startRequested = true;
        s.lastActivity = now;

        app.startService(s);
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");
        assertProcStates(app2, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");

        app.setCached(true);
        app.setProcState = PROCESS_STATE_NONEXISTENT;
        app.adjType = null;
        app.setAdj = UNKNOWN_ADJ;
        app.hasShownUi = false;
        s.lastActivity = now - sService.mConstants.MAX_SERVICE_INACTIVITY - 1;
        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);

        assertProcStates(app, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");
        assertProcStates(app2, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");

        doReturn(userOther).when(sService.mUserController).getCurrentUserId();
        sService.mOomAdjuster.handleUserSwitchedLocked();

        sService.mOomAdjuster.updateOomAdjLocked(OomAdjuster.OOM_ADJ_REASON_NONE);
        assertProcStates(app, true, PROCESS_STATE_SERVICE, cachedAdj1, "cch-started-services");
        assertProcStates(app2, false, PROCESS_STATE_SERVICE, SERVICE_ADJ, "started-services");
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
                0, 0, 0, true, 0, null, false);
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
        app.thread = mock(IApplicationThread.class);
        app.lastActivityTime = lastActivityTime;
        app.lastPssTime = lastPssTime;
        app.nextPssTime = nextPssTime;
        app.lastPss = lastPss;
        app.maxAdj = maxAdj;
        app.setRawAdj = setRawAdj;
        app.curAdj = curAdj;
        app.setAdj = setAdj;
        app.setCurrentSchedulingGroup(curSchedGroup);
        app.setSchedGroup = setSchedGroup;
        app.setCurProcState(curProcState);
        app.setReportedProcState(repProcState);
        app.setCurRawProcState(curRawProcState);
        app.setProcState = setProcState;
        app.connectionGroup = connectionGroup;
        app.connectionImportance = connectionImportance;
        app.serviceb = serviceb;
        app.setHasClientActivities(hasClientActivities);
        app.setHasForegroundServices(hasForegroundServices, fgServiceTypes);
        app.setHasClientActivities(hasForegroundActivities);
        app.repForegroundActivities = repForegroundActivities;
        app.systemNoUi = systemNoUi;
        app.hasShownUi = hasShownUi;
        app.setHasTopUi(hasTopUi);
        app.setHasOverlayUi(hasOverlayUi);
        app.runningRemoteAnimation = runningRemoteAnimation;
        app.hasAboveClient = hasAboveClient;
        app.treatLikeActivity = treatLikeActivity;
        app.killedByAm = killedByAm;
        app.forcingToImportant = forcingToImportant;
        for (int i = 0; i < numOfCurReceivers; i++) {
            app.curReceivers.add(mock(BroadcastRecord.class));
        }
        app.lastProviderTime = lastProviderTime;
        app.lastTopTime = lastTopTime;
        app.setCached(cached);
        for (int i = 0; i < numOfExecutingServices; i++) {
            app.executingServices.add(mock(ServiceRecord.class));
        }
        app.isolatedEntryPoint = isolatedEntryPoint;
        app.execServicesFg = execServicesFg;
        return app;
    }

    private ServiceRecord bindService(ProcessRecord service, ProcessRecord client,
            ServiceRecord record, int bindFlags, IBinder binder) {
        if (record == null) {
            record = mock(ServiceRecord.class);
            record.app = service;
            setFieldValue(ServiceRecord.class, record, "connections",
                    new ArrayMap<IBinder, ArrayList<ConnectionRecord>>());
            service.startService(record);
            doCallRealMethod().when(record).getConnections();
        }
        AppBindRecord binding = new AppBindRecord(record, null, client);
        ConnectionRecord cr = spy(new ConnectionRecord(binding,
                mock(ActivityServiceConnectionsHolder.class),
                mock(IServiceConnection.class), bindFlags,
                0, null, client.uid, client.processName, client.info.packageName));
        doCallRealMethod().when(record).addConnection(any(IBinder.class),
                any(ConnectionRecord.class));
        record.addConnection(binder, cr);
        client.connections.add(cr);
        binding.connections.add(cr);
        doNothing().when(cr).trackProcState(anyInt(), anyInt(), anyLong());
        return record;
    }

    private ContentProviderRecord bindProvider(ProcessRecord publisher, ProcessRecord client,
            ContentProviderRecord record, String name, boolean hasExternalProviders) {
        if (record == null) {
            record = mock(ContentProviderRecord.class);
            publisher.pubProviders.put(name, record);
            record.proc = publisher;
            setFieldValue(ContentProviderRecord.class, record, "connections",
                    new ArrayList<ContentProviderConnection>());
            doReturn(hasExternalProviders).when(record).hasExternalProcessHandles();
        }
        ContentProviderConnection conn = spy(new ContentProviderConnection(record, client,
                client.info.packageName));
        record.connections.add(conn);
        client.conProviders.add(conn);
        return record;
    }

    private void assertProcStates(ProcessRecord app, int expectedProcState, int expectedAdj,
            int expectedSchedGroup) {
        assertEquals(expectedProcState, app.setProcState);
        assertEquals(expectedAdj, app.setAdj);
        assertEquals(expectedSchedGroup, app.setSchedGroup);
    }

    private void assertProcStates(ProcessRecord app, boolean expectedCached,
            int expectedProcState, int expectedAdj, String expectedAdjType) {
        assertEquals(expectedCached, app.isCached());
        assertEquals(expectedProcState, app.setProcState);
        assertEquals(expectedAdj, app.setAdj);
        assertEquals(expectedAdjType, app.adjType);
    }
}
