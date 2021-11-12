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
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.ActivityManagerService.Injector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.annotation.CurrentTimeMillisLong;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Debug;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * Test class for {@link android.app.ApplicationExitInfo}.
 *
 * Build/Install/Run:
 *  atest ApplicationExitInfoTest
 */
@Presubmit
public class ApplicationExitInfoTest {
    private static final String TAG = ApplicationExitInfoTest.class.getSimpleName();

    @Rule public ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();
    @Mock private AppOpsService mAppOpsService;
    @Mock private PackageManagerInternal mPackageManagerInt;

    private Context mContext = getInstrumentation().getTargetContext();
    private TestInjector mInjector;
    private ActivityManagerService mAms;
    private ProcessList mProcessList;
    private AppExitInfoTracker mAppExitInfoTracker;
    private Handler mHandler;
    private HandlerThread mHandlerThread;

    @BeforeClass
    public static void setUpOnce() {
        System.setProperty("dexmaker.share_classloader", "true");
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mProcessList = spy(new ProcessList());
        ProcessList.sKillHandler = null;
        mAppExitInfoTracker = spy(new AppExitInfoTracker());
        setFieldValue(AppExitInfoTracker.class, mAppExitInfoTracker, "mIsolatedUidRecords",
                spy(mAppExitInfoTracker.new IsolatedUidRecords()));
        setFieldValue(AppExitInfoTracker.class, mAppExitInfoTracker, "mAppExitInfoSourceZygote",
                spy(mAppExitInfoTracker.new AppExitInfoExternalSource("zygote", null)));
        setFieldValue(AppExitInfoTracker.class, mAppExitInfoTracker, "mAppExitInfoSourceLmkd",
                spy(mAppExitInfoTracker.new AppExitInfoExternalSource("lmkd",
                ApplicationExitInfo.REASON_LOW_MEMORY)));
        setFieldValue(AppExitInfoTracker.class, mAppExitInfoTracker, "mAppTraceRetriever",
                spy(mAppExitInfoTracker.new AppTraceRetriever()));
        setFieldValue(ProcessList.class, mProcessList, "mAppExitInfoTracker", mAppExitInfoTracker);
        mInjector = new TestInjector(mContext);
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread());
        mAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        mAms.mAtmInternal = spy(mAms.mActivityTaskManager.getAtmInternal());
        mAms.mPackageManagerInt = mPackageManagerInt;
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        // Remove stale instance of PackageManagerInternal if there is any
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        mHandlerThread.quit();
        ProcessList.sKillHandler = null;
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

    private void updateExitInfo(ProcessRecord app, @CurrentTimeMillisLong long timestamp) {
        ApplicationExitInfo raw = mAppExitInfoTracker.obtainRawRecord(app, timestamp);
        mAppExitInfoTracker.handleNoteProcessDiedLocked(raw);
        mAppExitInfoTracker.recycleRawRecord(raw);
    }

    private void noteAppKill(ProcessRecord app, int reason, int subReason, String msg,
            @CurrentTimeMillisLong long timestamp) {
        ApplicationExitInfo raw = mAppExitInfoTracker.obtainRawRecord(app, timestamp);
        raw.setReason(reason);
        raw.setSubReason(subReason);
        raw.setDescription(msg);
        mAppExitInfoTracker.handleNoteAppKillLocked(raw);
        mAppExitInfoTracker.recycleRawRecord(raw);
    }

    @Test
    public void testApplicationExitInfo() throws Exception {
        mAppExitInfoTracker.clearProcessExitInfo(true);
        mAppExitInfoTracker.mAppExitInfoLoaded.set(true);
        mAppExitInfoTracker.mProcExitStoreDir = new File(mContext.getFilesDir(),
                AppExitInfoTracker.APP_EXIT_STORE_DIR);
        assertTrue(FileUtils.createDir(mAppExitInfoTracker.mProcExitStoreDir));
        mAppExitInfoTracker.mProcExitInfoFile = new File(mAppExitInfoTracker.mProcExitStoreDir,
                AppExitInfoTracker.APP_EXIT_INFO_FILE);

        // Test application calls System.exit()
        doNothing().when(mAppExitInfoTracker).schedulePersistProcessExitInfo(anyBoolean());
        doReturn(true).when(mAppExitInfoTracker).isFresh(anyLong());

        final int app1Uid = 10123;
        final int app1Pid1 = 12345;
        final int app1Pid2 = 12346;
        final int app1sPid1 = 13456;
        final int app1DefiningUid = 23456;
        final int app1ConnectiongGroup = 10;
        final int app1UidUser2 = 1010123;
        final int app1PidUser2 = 12347;
        final long app1Pss1 = 34567;
        final long app1Rss1 = 45678;
        final long app1Pss2 = 34568;
        final long app1Rss2 = 45679;
        final long app1Pss3 = 34569;
        final long app1Rss3 = 45680;
        final long app1sPss1 = 56789;
        final long app1sRss1 = 67890;
        final String app1ProcessName = "com.android.test.stub1:process";
        final String app1PackageName = "com.android.test.stub1";
        final String app1sProcessName = "com.android.test.stub_shared:process";
        final String app1sPackageName = "com.android.test.stub_shared";
        final byte[] app1Cookie1 = {(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,
                (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08};
        final byte[] app1Cookie2 = {(byte) 0x08, (byte) 0x07, (byte) 0x06, (byte) 0x05,
                (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x01};

        final long now1 = 1;
        ProcessRecord app = makeProcessRecord(
                app1Pid1,                    // pid
                app1Uid,                     // uid
                app1Uid,                     // packageUid
                null,                        // definingUid
                0,                           // connectionGroup
                PROCESS_STATE_LAST_ACTIVITY, // procstate
                app1Pss1,                    // pss
                app1Rss1,                    // rss
                app1ProcessName,             // processName
                app1PackageName);            // packageName

        // Case 1: basic System.exit() test
        int exitCode = 5;
        mAppExitInfoTracker.setProcessStateSummary(app1Uid, app1Pid1, app1Cookie1);
        assertTrue(ArrayUtils.equals(mAppExitInfoTracker.getProcessStateSummary(app1Uid,
                app1Pid1), app1Cookie1, app1Cookie1.length));
        doReturn(new Pair<Long, Object>(now1, Integer.valueOf(makeExitStatus(exitCode))))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());
        updateExitInfo(app, now1);

        ArrayList<ApplicationExitInfo> list = new ArrayList<ApplicationExitInfo>();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1Uid, app1Pid1, 0, list);
        assertEquals(1, list.size());

        ApplicationExitInfo info = list.get(0);

        verifyApplicationExitInfo(
                info,                                 // info
                now1,                                 // timestamp
                app1Pid1,                             // pid
                app1Uid,                              // uid
                app1Uid,                              // packageUid
                null,                                 // definingUid
                app1ProcessName,                      // processName
                0,                                    // connectionGroup
                ApplicationExitInfo.REASON_EXIT_SELF, // reason
                null,                                 // subReason
                exitCode,                             // status
                app1Pss1,                             // pss
                app1Rss1,                             // rss
                IMPORTANCE_CACHED,                    // importance
                null);                                // description

        assertTrue(ArrayUtils.equals(info.getProcessStateSummary(), app1Cookie1,
                app1Cookie1.length));
        assertEquals(info.getTraceInputStream(), null);

        // Now create a process record from a different package but shared UID.
        sleep(1);
        final long now1s = System.currentTimeMillis();
        app = makeProcessRecord(
                app1sPid1,                   // pid
                app1Uid,                     // uid
                app1Uid,                     // packageUid
                null,                        // definingUid
                0,                           // connectionGroup
                PROCESS_STATE_BOUND_TOP,     // procstate
                app1sPss1,                   // pss
                app1sRss1,                   // rss
                app1sProcessName,            // processName
                app1sPackageName);           // packageName
        doReturn(new Pair<Long, Object>(now1s, Integer.valueOf(0)))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());
        noteAppKill(app, ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.SUBREASON_UNKNOWN, null, now1s);

        // Case 2: create another app1 process record with a different pid
        sleep(1);
        final long now2 = 2;
        app = makeProcessRecord(
                app1Pid2,               // pid
                app1Uid,                // uid
                app1Uid,                // packageUid
                app1DefiningUid,        // definingUid
                app1ConnectiongGroup,   // connectionGroup
                PROCESS_STATE_RECEIVER, // procstate
                app1Pss2,               // pss
                app1Rss2,               // rss
                app1ProcessName,        // processName
                app1PackageName);       // packageName
        exitCode = 6;

        mAppExitInfoTracker.setProcessStateSummary(app1Uid, app1Pid2, app1Cookie1);
        // Override with a different cookie
        mAppExitInfoTracker.setProcessStateSummary(app1Uid, app1Pid2, app1Cookie2);
        assertTrue(ArrayUtils.equals(mAppExitInfoTracker.getProcessStateSummary(app1Uid,
                app1Pid2), app1Cookie2, app1Cookie2.length));
        doReturn(new Pair<Long, Object>(now2, Integer.valueOf(makeExitStatus(exitCode))))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        updateExitInfo(app, now2);
        list.clear();

        // Get all the records for app1Uid
        mAppExitInfoTracker.getExitInfo(null, app1Uid, 0, 0, list);
        assertEquals(3, list.size());

        info = list.get(1);

        verifyApplicationExitInfo(
                info,                                 // info
                now2,                                 // timestamp
                app1Pid2,                             // pid
                app1Uid,                              // uid
                app1Uid,                              // packageUid
                app1DefiningUid,                      // definingUid
                app1ProcessName,                      // processName
                app1ConnectiongGroup,                 // connectionGroup
                ApplicationExitInfo.REASON_EXIT_SELF, // reason
                null,                                 // subReason
                exitCode,                             // status
                app1Pss2,                             // pss
                app1Rss2,                             // rss
                IMPORTANCE_SERVICE,                   // importance
                null);                                // description

        assertTrue(ArrayUtils.equals(info.getProcessStateSummary(), app1Cookie2,
                app1Cookie2.length));

        info = list.get(0);
        verifyApplicationExitInfo(
                info,                                      // info
                now1s,                                     // timestamp
                app1sPid1,                                 // pid
                app1Uid,                                   // uid
                app1Uid,                                   // packageUid
                null,                                      // definingUid
                app1sProcessName,                          // processName
                0,                                         // connectionGroup
                ApplicationExitInfo.REASON_USER_REQUESTED, // reason
                null,                                      // subReason
                null,                                      // status
                app1sPss1,                                 // pss
                app1sRss1,                                 // rss
                IMPORTANCE_FOREGROUND,                     // importance
                null);                                     // description

        info = list.get(2);
        assertTrue(ArrayUtils.equals(info.getProcessStateSummary(), app1Cookie1,
                app1Cookie1.length));

        // Case 3: Create an instance of app1 with different user, and died because of SIGKILL
        sleep(1);
        final long now3 = System.currentTimeMillis();
        int sigNum = OsConstants.SIGKILL;
        app = makeProcessRecord(
                app1PidUser2,                           // pid
                app1UidUser2,                           // uid
                app1UidUser2,                           // packageUid
                null,                                   // definingUid
                0,                                      // connectionGroup
                PROCESS_STATE_BOUND_FOREGROUND_SERVICE, // procstate
                app1Pss3,                               // pss
                app1Rss3,                               // rss
                app1ProcessName,                        // processName
                app1PackageName);                       // packageName
        doReturn(new Pair<Long, Object>(now3, Integer.valueOf(makeSignalStatus(sigNum))))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        updateExitInfo(app, now3);
        list.clear();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1UidUser2, app1PidUser2, 0, list);

        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                // info
                now3,                                // timestamp
                app1PidUser2,                        // pid
                app1UidUser2,                        // uid
                app1UidUser2,                        // packageUid
                null,                                // definingUid
                app1ProcessName,                     // processName
                0,                                   // connectionGroup
                ApplicationExitInfo.REASON_SIGNALED, // reason
                null,                                 // subReason
                sigNum,                              // status
                app1Pss3,                            // pss
                app1Rss3,                            // rss
                IMPORTANCE_FOREGROUND_SERVICE,       // importance
                null);                               // description

        // try go get all from app1UidUser2
        list.clear();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1UidUser2, 0, 0, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                // info
                now3,                                // timestamp
                app1PidUser2,                        // pid
                app1UidUser2,                        // uid
                app1UidUser2,                        // packageUid
                null,                                // definingUid
                app1ProcessName,                     // processName
                0,                                   // connectionGroup
                ApplicationExitInfo.REASON_SIGNALED, // reason
                null,                                // subReason
                sigNum,                              // status
                app1Pss3,                            // pss
                app1Rss3,                            // rss
                IMPORTANCE_FOREGROUND_SERVICE,       // importance
                null);                               // description

        // Case 4: Create a process from another package with kill from lmkd
        final int app2UidUser2 = 1010234;
        final int app2PidUser2 = 12348;
        final long app2Pss1 = 54321;
        final long app2Rss1 = 65432;
        final String app2ProcessName = "com.android.test.stub2:process";
        final String app2PackageName = "com.android.test.stub2";

        sleep(1);
        final long now4 = System.currentTimeMillis();
        doReturn(new Pair<Long, Object>(now4, Integer.valueOf(0)))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(new Pair<Long, Object>(now4, null))
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());

        app = makeProcessRecord(
                app2PidUser2,                // pid
                app2UidUser2,                // uid
                app2UidUser2,                // packageUid
                null,                        // definingUid
                0,                           // connectionGroup
                PROCESS_STATE_CACHED_EMPTY,  // procstate
                app2Pss1,                    // pss
                app2Rss1,                    // rss
                app2ProcessName,             // processName
                app2PackageName);            // packageName
        updateExitInfo(app, now4);
        list.clear();
        mAppExitInfoTracker.getExitInfo(app2PackageName, app2UidUser2, app2PidUser2, 0, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                     // info
                now4,                                     // timestamp
                app2PidUser2,                             // pid
                app2UidUser2,                             // uid
                app2UidUser2,                             // packageUid
                null,                                     // definingUid
                app2ProcessName,                          // processName
                0,                                        // connectionGroup
                ApplicationExitInfo.REASON_LOW_MEMORY,    // reason
                null,                                     // subReason
                0,                                        // status
                app2Pss1,                                 // pss
                app2Rss1,                                 // rss
                IMPORTANCE_CACHED,                        // importance
                null);                                    // description

        // Verify to get all from User2 regarding app2
        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app2UidUser2, 0, 0, list);
        assertEquals(1, list.size());

        // Case 5: App native crash
        final int app3UidUser2 = 1010345;
        final int app3PidUser2 = 12349;
        final int app3ConnectiongGroup = 4;
        final long app3Pss1 = 54320;
        final long app3Rss1 = 65430;
        final String app3ProcessName = "com.android.test.stub3:process";
        final String app3PackageName = "com.android.test.stub3";
        final String app3Description = "native crash";

        sleep(1);
        final long now5 = System.currentTimeMillis();
        sigNum = OsConstants.SIGABRT;
        doReturn(new Pair<Long, Object>(now5, Integer.valueOf(makeSignalStatus(sigNum))))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());
        app = makeProcessRecord(
                app3PidUser2,            // pid
                app3UidUser2,            // uid
                app3UidUser2,            // packageUid
                null,                    // definingUid
                app3ConnectiongGroup,    // connectionGroup
                PROCESS_STATE_BOUND_TOP, // procstate
                app3Pss1,                // pss
                app3Rss1,                // rss
                app3ProcessName,         // processName
                app3PackageName);        // packageName
        noteAppKill(app, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.SUBREASON_UNKNOWN, app3Description, now5);

        updateExitInfo(app, now5);
        list.clear();
        mAppExitInfoTracker.getExitInfo(app3PackageName, app3UidUser2, app3PidUser2, 0, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                            // info
                now5,                                            // timestamp
                app3PidUser2,                                    // pid
                app3UidUser2,                                    // uid
                app3UidUser2,                                    // packageUid
                null,                                            // definingUid
                app3ProcessName,                                 // processName
                app3ConnectiongGroup,                            // connectionGroup
                ApplicationExitInfo.REASON_CRASH_NATIVE,         // reason
                null,                                            // subReason
                sigNum,                                          // status
                app3Pss1,                                        // pss
                app3Rss1,                                        // rss
                IMPORTANCE_FOREGROUND,                           // importance
                app3Description);                                // description

        // Verify the most recent kills, sorted by timestamp
        int maxNum = 3;
        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app3UidUser2, 0, maxNum, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                            // info
                now5,                                            // timestamp
                app3PidUser2,                                    // pid
                app3UidUser2,                                    // uid
                app3UidUser2,                                    // packageUid
                null,                                            // definingUid
                app3ProcessName,                                 // processName
                app3ConnectiongGroup,                            // connectionGroup
                ApplicationExitInfo.REASON_CRASH_NATIVE,         // reason
                null,                                            // subReason
                sigNum,                                          // status
                app3Pss1,                                        // pss
                app3Rss1,                                        // rss
                IMPORTANCE_FOREGROUND,                           // importance
                app3Description);                                // description

        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app2UidUser2, 0, maxNum, list);
        assertEquals(1, list.size());
        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                     // info
                now4,                                     // timestamp
                app2PidUser2,                             // pid
                app2UidUser2,                             // uid
                app2UidUser2,                             // packageUid
                null,                                     // definingUid
                app2ProcessName,                          // processName
                0,                                        // connectionGroup
                ApplicationExitInfo.REASON_LOW_MEMORY,    // reason
                null,                                     // subReason
                0,                                        // status
                app2Pss1,                                 // pss
                app2Rss1,                                 // rss
                IMPORTANCE_CACHED,                        // importance
                null);                                    // description

        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app1UidUser2, 0, maxNum, list);
        assertEquals(1, list.size());
        info = list.get(0);

        sigNum = OsConstants.SIGKILL;
        verifyApplicationExitInfo(
                info,                                // info
                now3,                                // timestamp
                app1PidUser2,                        // pid
                app1UidUser2,                        // uid
                app1UidUser2,                        // packageUid
                null,                                // definingUid
                app1ProcessName,                     // processName
                0,                                   // connectionGroup
                ApplicationExitInfo.REASON_SIGNALED, // reason
                null,                                // subReason
                sigNum,                              // status
                app1Pss3,                            // pss
                app1Rss3,                            // rss
                IMPORTANCE_FOREGROUND_SERVICE,       // importance
                null);                               // description

        // Case 6: App Java crash
        final int app3Uid = 10345;
        final int app3IsolatedUid = 99001; // it's an isolated process
        final int app3Pid = 12350;
        final long app3Pss2 = 23232;
        final long app3Rss2 = 32323;
        final String app3Description2 = "force close";

        sleep(1);
        final long now6 = System.currentTimeMillis();
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());
        app = makeProcessRecord(
                app3Pid,                     // pid
                app3IsolatedUid,             // uid
                app3Uid,                     // packageUid
                null,                        // definingUid
                0,                           // connectionGroup
                PROCESS_STATE_CACHED_EMPTY,  // procstate
                app3Pss2,                    // pss
                app3Rss2,                    // rss
                app3ProcessName,             // processName
                app3PackageName);            // packageName
        mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(app3IsolatedUid, app3Uid);
        noteAppKill(app, ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.SUBREASON_UNKNOWN, app3Description2, now6);

        assertEquals(app3Uid, mAppExitInfoTracker.mIsolatedUidRecords
                .getUidByIsolatedUid(app3IsolatedUid).longValue());
        updateExitInfo(app, now6);
        assertNull(mAppExitInfoTracker.mIsolatedUidRecords.getUidByIsolatedUid(app3IsolatedUid));

        list.clear();
        mAppExitInfoTracker.getExitInfo(app3PackageName, app3Uid, 0, 1, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                     // info
                now6,                                     // timestamp
                app3Pid,                                  // pid
                app3IsolatedUid,                          // uid
                app3Uid,                                  // packageUid
                null,                                     // definingUid
                app3ProcessName,                          // processName
                0,                                        // connectionGroup
                ApplicationExitInfo.REASON_CRASH,         // reason
                null,                                     // subReason
                0,                                        // status
                app3Pss2,                                 // pss
                app3Rss2,                                 // rss
                IMPORTANCE_CACHED,                        // importance
                app3Description2);                        // description

        // Case 7: App1 is "uninstalled" from User2
        mAppExitInfoTracker.onPackageRemoved(app1PackageName, app1UidUser2, false);
        list.clear();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1UidUser2, 0, 0, list);
        assertEquals(0, list.size());

        list.clear();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1Uid, 0, 0, list);
        assertEquals(2, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                 // info
                now2,                                 // timestamp
                app1Pid2,                             // pid
                app1Uid,                              // uid
                app1Uid,                              // packageUid
                app1DefiningUid,                      // definingUid
                app1ProcessName,                      // processName
                app1ConnectiongGroup,                 // connectionGroup
                ApplicationExitInfo.REASON_EXIT_SELF, // reason
                null,                                 // subReason
                exitCode,                             // status
                app1Pss2,                             // pss
                app1Rss2,                             // rss
                IMPORTANCE_SERVICE,                   // importance
                null);                                // description

        // Case 8: App1 gets "remove task"
        final String app1Description = "remove task";

        sleep(1);
        final int app1IsolatedUidUser2 = 1099002; // isolated uid
        final long app1Pss4 = 34343;
        final long app1Rss4 = 43434;
        final long now8 = System.currentTimeMillis();
        sigNum = OsConstants.SIGKILL;
        doReturn(new Pair<Long, Object>(now8, makeSignalStatus(sigNum)))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());
        app = makeProcessRecord(
                app1PidUser2,                 // pid
                app1IsolatedUidUser2,         // uid
                app1UidUser2,                 // packageUid
                null,                         // definingUid
                0,                            // connectionGroup
                PROCESS_STATE_CACHED_EMPTY,   // procstate
                app1Pss4,                     // pss
                app1Rss4,                     // rss
                app1ProcessName,              // processName
                app1PackageName);             // packageName

        mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(app1IsolatedUidUser2, app1UidUser2);
        noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                ApplicationExitInfo.SUBREASON_UNKNOWN, app1Description, now8);

        updateExitInfo(app, now8);
        list.clear();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1UidUser2, app1PidUser2, 1, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                     // info
                now8,                                     // timestamp
                app1PidUser2,                             // pid
                app1IsolatedUidUser2,                     // uid
                app1UidUser2,                             // packageUid
                null,                                     // definingUid
                app1ProcessName,                          // processName
                0,                                        // connectionGroup
                ApplicationExitInfo.REASON_OTHER,         // reason
                ApplicationExitInfo.SUBREASON_UNKNOWN,    // subReason
                0,                                        // status
                app1Pss4,                                 // pss
                app1Rss4,                                 // rss
                IMPORTANCE_CACHED,                        // importance
                app1Description);                         // description

        // App1 gets "too many empty"
        final String app1Description2 = "too many empty";
        sleep(1);
        final int app1Pid2User2 = 56565;
        final int app1IsolatedUid2User2 = 1099003; // isolated uid
        final long app1Pss5 = 34344;
        final long app1Rss5 = 43435;
        final long now9 = System.currentTimeMillis();
        sigNum = OsConstants.SIGKILL;
        doReturn(new Pair<Long, Object>(now9, makeSignalStatus(sigNum)))
                .when(mAppExitInfoTracker.mAppExitInfoSourceZygote)
                .remove(anyInt(), anyInt());
        doReturn(null)
                .when(mAppExitInfoTracker.mAppExitInfoSourceLmkd)
                .remove(anyInt(), anyInt());
        app = makeProcessRecord(
                app1Pid2User2,                // pid
                app1IsolatedUid2User2,        // uid
                app1UidUser2,                 // packageUid
                null,                         // definingUid
                0,                            // connectionGroup
                PROCESS_STATE_CACHED_EMPTY,   // procstate
                app1Pss5,                     // pss
                app1Rss5,                     // rss
                app1ProcessName,              // processName
                app1PackageName);             // packageName

        mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(app1IsolatedUid2User2, app1UidUser2);

        // Pretent it gets an ANR trace too (although the reason here should be REASON_ANR)
        final File traceFile = new File(mContext.getFilesDir(), "anr_original.txt");
        final int traceSize = 10240;
        final int traceStart = 1024;
        final int traceEnd = 8192;
        createRandomFile(traceFile, traceSize);
        assertEquals(traceSize, traceFile.length());
        mAppExitInfoTracker.handleLogAnrTrace(app.getPid(), app.uid, app.getPackageList(),
                traceFile, traceStart, traceEnd);

        noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                ApplicationExitInfo.SUBREASON_TOO_MANY_EMPTY, app1Description2, now9);
        updateExitInfo(app, now9);
        list.clear();
        mAppExitInfoTracker.getExitInfo(app1PackageName, app1UidUser2, app1Pid2User2, 1, list);
        assertEquals(1, list.size());

        info = list.get(0);

        verifyApplicationExitInfo(
                info,                                         // info
                now9,                                         // timestamp
                app1Pid2User2,                                // pid
                app1IsolatedUid2User2,                        // uid
                app1UidUser2,                                 // packageUid
                null,                                         // definingUid
                app1ProcessName,                              // processName
                0,                                            // connectionGroup
                ApplicationExitInfo.REASON_OTHER,             // reason
                ApplicationExitInfo.SUBREASON_TOO_MANY_EMPTY, // subReason
                0,                                            // status
                app1Pss5,                                     // pss
                app1Rss5,                                     // rss
                IMPORTANCE_CACHED,                            // importance
                app1Description2);                            // description

        // Verify if the traceFile get copied into the records correctly.
        verifyTraceFile(traceFile, traceStart, info.getTraceFile(), 0, traceEnd - traceStart);
        traceFile.delete();
        info.getTraceFile().delete();

        // Case 9: User2 gets removed
        sleep(1);
        mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(app1IsolatedUidUser2, app1UidUser2);
        mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(app3IsolatedUid, app3Uid);

        mAppExitInfoTracker.onUserRemoved(UserHandle.getUserId(app1UidUser2));

        assertNull(mAppExitInfoTracker.mIsolatedUidRecords.getUidByIsolatedUid(
                app1IsolatedUidUser2));
        assertNotNull(mAppExitInfoTracker.mIsolatedUidRecords.getUidByIsolatedUid(
                app3IsolatedUid));
        mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(
                app1IsolatedUidUser2, app1UidUser2);
        mAppExitInfoTracker.mIsolatedUidRecords.removeAppUid(app1UidUser2, false);
        assertNull(mAppExitInfoTracker.mIsolatedUidRecords.getUidByIsolatedUid(
                app1IsolatedUidUser2));
        mAppExitInfoTracker.mIsolatedUidRecords.removeAppUid(app3Uid, true);
        assertNull(mAppExitInfoTracker.mIsolatedUidRecords.getUidByIsolatedUid(app3IsolatedUid));

        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app1UidUser2, 0, 0, list);
        assertEquals(0, list.size());

        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app1Uid, 0, 0, list);
        assertEquals(3, list.size());

        info = list.get(1);

        exitCode = 6;
        verifyApplicationExitInfo(
                info,                                 // info
                now2,                                 // timestamp
                app1Pid2,                             // pid
                app1Uid,                              // uid
                app1Uid,                              // packageUid
                app1DefiningUid,                      // definingUid
                app1ProcessName,                      // processName
                app1ConnectiongGroup,                 // connectionGroup
                ApplicationExitInfo.REASON_EXIT_SELF, // reason
                null,                                 // subReason
                exitCode,                             // status
                app1Pss2,                             // pss
                app1Rss2,                             // rss
                IMPORTANCE_SERVICE,                   // importance
                null);                                // description

        info = list.get(0);
        verifyApplicationExitInfo(
                info,                                      // info
                now1s,                                     // timestamp
                app1sPid1,                                 // pid
                app1Uid,                                   // uid
                app1Uid,                                   // packageUid
                null,                                      // definingUid
                app1sProcessName,                          // processName
                0,                                         // connectionGroup
                ApplicationExitInfo.REASON_USER_REQUESTED, // reason
                null,                                      // subReason
                null,                                      // status
                app1sPss1,                                 // pss
                app1sRss1,                                 // rss
                IMPORTANCE_FOREGROUND,                     // importance
                null);                                     // description

        info = list.get(2);
        exitCode = 5;
        verifyApplicationExitInfo(
                info,                                 // info
                now1,                                 // timestamp
                app1Pid1,                             // pid
                app1Uid,                              // uid
                app1Uid,                              // packageUid
                null,                                 // definingUid
                app1ProcessName,                      // processName
                0,                                    // connectionGroup
                ApplicationExitInfo.REASON_EXIT_SELF, // reason
                null,                                 // subReason
                exitCode,                             // status
                app1Pss1,                             // pss
                app1Rss1,                             // rss
                IMPORTANCE_CACHED,                    // importance
                null);                                // description

        // Case 10: Save the info and load them again
        ArrayList<ApplicationExitInfo> original = new ArrayList<ApplicationExitInfo>();
        mAppExitInfoTracker.getExitInfo(null, app1Uid, 0, 0, original);
        assertTrue(original.size() > 0);

        mAppExitInfoTracker.persistProcessExitInfo();
        assertTrue(mAppExitInfoTracker.mProcExitInfoFile.exists());

        mAppExitInfoTracker.clearProcessExitInfo(false);
        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app1Uid, 0, 0, list);
        assertEquals(0, list.size());

        mAppExitInfoTracker.loadExistingProcessExitInfo();
        list.clear();
        mAppExitInfoTracker.getExitInfo(null, app1Uid, 0, 0, list);
        assertEquals(original.size(), list.size());

        for (int i = list.size() - 1; i >= 0; i--) {
            assertTrue(list.get(i).equals(original.get(i)));
        }
    }

    private static int makeExitStatus(int exitCode) {
        return (exitCode << 8) & 0xff00;
    }

    private static int makeSignalStatus(int sigNum) {
        return sigNum & 0x7f;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private static void createRandomFile(File file, int size) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            Random random = new Random();
            byte[] buf = random.ints('a', 'z').limit(size).collect(
                    StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString().getBytes();
            out.write(buf);
        }
    }

    private static void verifyTraceFile(File originFile, int originStart, File traceFile,
            int traceStart, int length) throws IOException {
        assertTrue(originFile.exists());
        assertTrue(traceFile.exists());
        assertTrue(originStart < originFile.length());
        try (GZIPInputStream traceIn = new GZIPInputStream(new FileInputStream(traceFile));
            BufferedInputStream originIn = new BufferedInputStream(
                    new FileInputStream(originFile))) {
            assertEquals(traceStart, traceIn.skip(traceStart));
            assertEquals(originStart, originIn.skip(originStart));
            byte[] buf1 = new byte[8192];
            byte[] buf2 = new byte[8192];
            while (length > 0) {
                int len = traceIn.read(buf1, 0, Math.min(buf1.length, length));
                assertEquals(len, originIn.read(buf2, 0, len));
                assertTrue(ArrayUtils.equals(buf1, buf2, len));
                length -= len;
            }
        }
    }

    private ProcessRecord makeProcessRecord(int pid, int uid, int packageUid, Integer definingUid,
            int connectionGroup, int procState, long pss, long rss,
            String processName, String packageName) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ProcessRecord app = new ProcessRecord(mAms, ai, processName, uid);
        app.setPid(pid);
        app.info.uid = packageUid;
        if (definingUid != null) {
            final String dummyPackageName = "com.android.test";
            final String dummyClassName = ".Foo";
            app.setHostingRecord(HostingRecord.byAppZygote(new ComponentName(
                    dummyPackageName, dummyClassName), "", definingUid));
        }
        app.mServices.setConnectionGroup(connectionGroup);
        app.mState.setReportedProcState(procState);
        app.mProfile.setLastMemInfo(spy(new Debug.MemoryInfo()));
        app.mProfile.setLastPss(pss);
        app.mProfile.setLastRss(rss);
        return app;
    }

    private void verifyApplicationExitInfo(ApplicationExitInfo info,
            Long timestamp, Integer pid, Integer uid, Integer packageUid,
            Integer definingUid, String processName, Integer connectionGroup,
            Integer reason, Integer subReason, Integer status,
            Long pss, Long rss, Integer importance, String description) {
        assertNotNull(info);

        if (timestamp != null) {
            final long tolerance = 10000; // ms
            assertTrue(timestamp - tolerance <= info.getTimestamp());
            assertTrue(timestamp + tolerance >= info.getTimestamp());
        }
        if (pid != null) {
            assertEquals(pid.intValue(), info.getPid());
        }
        if (uid != null) {
            assertEquals(uid.intValue(), info.getRealUid());
        }
        if (packageUid != null) {
            assertEquals(packageUid.intValue(), info.getPackageUid());
        }
        if (definingUid != null) {
            assertEquals(definingUid.intValue(), info.getDefiningUid());
        }
        if (processName != null) {
            assertTrue(TextUtils.equals(processName, info.getProcessName()));
        }
        if (connectionGroup != null) {
            assertEquals(connectionGroup.intValue(), info.getConnectionGroup());
        }
        if (reason != null) {
            assertEquals(reason.intValue(), info.getReason());
        }
        if (subReason != null) {
            assertEquals(subReason.intValue(), info.getSubReason());
        }
        if (status != null) {
            assertEquals(status.intValue(), info.getStatus());
        }
        if (pss != null) {
            assertEquals(pss.longValue(), info.getPss());
        }
        if (rss != null) {
            assertEquals(rss.longValue(), info.getRss());
        }
        if (importance != null) {
            assertEquals(importance.intValue(), info.getImportance());
        }
        if (description != null) {
            assertTrue(TextUtils.equals(description, info.getDescription()));
        }
    }

    private class TestInjector extends Injector {
        TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }

        @Override
        public ProcessList getProcessList(ActivityManagerService service) {
            return mProcessList;
        }
    }

    static class ServiceThreadRule implements TestRule {

        private ServiceThread mThread;

        ServiceThread getThread() {
            return mThread;
        }

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    mThread = new ServiceThread("TestServiceThread",
                            Process.THREAD_PRIORITY_DEFAULT, true /* allowIo */);
                    mThread.start();
                    try {
                        base.evaluate();
                    } finally {
                        mThread.getThreadHandler().runWithScissors(mThread::quit, 0 /* timeout */);
                    }
                }
            };
        }
    }
}
