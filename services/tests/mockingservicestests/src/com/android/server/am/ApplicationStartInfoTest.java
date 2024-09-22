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

package com.android.server.am;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.ActivityManagerService.Injector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.app.ApplicationStartInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.text.TextUtils;

import com.android.internal.os.Clock;
import com.android.internal.os.MonotonicClock;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * Test class for {@link android.app.ApplicationStartInfo}.
 *
 * Build/Install/Run:
 * atest ApplicationStartInfoTest
 */
@Presubmit
public class ApplicationStartInfoTest {

    private static final String TAG = ApplicationStartInfoTest.class.getSimpleName();
    private static final ComponentName COMPONENT = new ComponentName("com.android.test", ".Foo");

    private static final int APP_1_UID = 10123;
    private static final int APP_1_PID_1 = 12345;
    private static final int APP_1_PID_2 = 12346;
    private static final int APP_1_DEFINING_UID = 23456;
    private static final int APP_1_UID_USER_2 = 1010123;
    private static final int APP_1_PID_USER_2 = 12347;
    private static final String APP_1_PROCESS_NAME = "com.android.test.stub1:process";
    private static final String APP_1_PACKAGE_NAME = "com.android.test.stub1";

    @Rule public ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();
    @Mock private AppOpsService mAppOpsService;
    @Mock private PackageManagerInternal mPackageManagerInt;

    private Context mContext = getInstrumentation().getTargetContext();
    private TestInjector mInjector;
    private ActivityManagerService mAms;
    private ProcessList mProcessList;
    private AppStartInfoTracker mAppStartInfoTracker;
    private Handler mHandler;
    private HandlerThread mHandlerThread;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mProcessList = spy(new ProcessList());
        mAppStartInfoTracker = spy(new AppStartInfoTracker());
        mAppStartInfoTracker.mEnabled = true;
        setFieldValue(ProcessList.class, mProcessList, "mAppStartInfoTracker",
                mAppStartInfoTracker);
        mInjector = new TestInjector(mContext);
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread());
        mAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        mAms.mAtmInternal = spy(mAms.mActivityTaskManager.getAtmInternal());
        mAms.mPackageManagerInt = mPackageManagerInt;
        mAppStartInfoTracker.mService = mAms;
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doReturn("com.android.test").when(mPackageManagerInt).getNameForUid(anyInt());
        // Remove stale instance of PackageManagerInternal if there is any
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);

        mAppStartInfoTracker.mMonotonicClock = new MonotonicClock(
                Clock.SYSTEM_CLOCK.elapsedRealtime(), Clock.SYSTEM_CLOCK);
        mAppStartInfoTracker.clearProcessStartInfo(true);
        mAppStartInfoTracker.mAppStartInfoLoaded.set(true);
        mAppStartInfoTracker.mAppStartInfoHistoryListSize =
                mAppStartInfoTracker.APP_START_INFO_HISTORY_LIST_SIZE;
        doNothing().when(mAppStartInfoTracker).schedulePersistProcessStartInfo(anyBoolean());

        mAppStartInfoTracker.mProcStartStoreDir = new File(mContext.getFilesDir(),
                AppStartInfoTracker.APP_START_STORE_DIR);
        mAppStartInfoTracker.mProcStartInfoFile = new File(mAppStartInfoTracker.mProcStartStoreDir,
                AppStartInfoTracker.APP_START_INFO_FILE);
    }

    @After
    public void tearDown() {
        mHandlerThread.quit();
    }

    @Test
    public void testApplicationStartInfo() throws Exception {
        // Make sure we can write to the file.
        assertTrue(FileUtils.createDir(mAppStartInfoTracker.mProcStartStoreDir));

        final long appStartTimestampIntentStarted = 1000000;
        final long appStartTimestampActivityLaunchFinished = 2000000;
        final long appStartTimestampFirstFrameDrawn = 2500000;
        final long appStartTimestampReportFullyDrawn = 3000000;
        final long appStartTimestampService = 4000000;
        final long appStartTimestampBroadcast = 5000000;
        final long appStartTimestampRContentProvider = 6000000;

        ProcessRecord app = makeProcessRecord(
                APP_1_PID_1,                 // pid
                APP_1_UID,                   // uid
                APP_1_UID,                   // packageUid
                null,                        // definingUid
                APP_1_PROCESS_NAME,          // processName
                APP_1_PACKAGE_NAME);         // packageName

        ArrayList<ApplicationStartInfo> list = new ArrayList<ApplicationStartInfo>();

        // Case 1: Activity start intent failed
        mAppStartInfoTracker.onIntentStarted(buildIntent(COMPONENT),
                appStartTimestampIntentStarted);
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(1);
        assertEquals(list.size(), 0);

        verifyInProgressApplicationStartInfo(
                0,                                                    // index
                0,                                                    // pid
                0,                                                    // uid
                0,                                                    // packageUid
                null,                                                 // definingUid
                null,                                                 // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_UNSET,                // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        mAppStartInfoTracker.onIntentFailed(appStartTimestampIntentStarted);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(0);
        assertEquals(list.size(), 0);

        mAppStartInfoTracker.clearProcessStartInfo(true);

        // Case 2: Activity start launch cancelled
        mAppStartInfoTracker.onIntentStarted(buildIntent(COMPONENT),
                appStartTimestampIntentStarted);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(1);
        assertEquals(list.size(), 0);

        mAppStartInfoTracker.onActivityLaunched(appStartTimestampIntentStarted, COMPONENT,
                ApplicationStartInfo.START_TYPE_COLD, app);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(1);
        assertEquals(list.size(), 1);

        verifyInProgressApplicationStartInfo(
                0,                                                    // index
                APP_1_PID_1,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        mAppStartInfoTracker.onActivityLaunchCancelled(appStartTimestampIntentStarted);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(0);
        assertEquals(list.size(), 1);

        verifyApplicationStartInfo(
                list.get(0),                                          // info
                APP_1_PID_1,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_ERROR,             // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        mAppStartInfoTracker.clearProcessStartInfo(true);

        // Case 3: Activity start success
        mAppStartInfoTracker.onIntentStarted(buildIntent(COMPONENT),
                appStartTimestampIntentStarted);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(1);
        assertEquals(list.size(), 0);

        mAppStartInfoTracker.onActivityLaunched(appStartTimestampIntentStarted, COMPONENT,
                ApplicationStartInfo.START_TYPE_COLD, app);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(1);
        assertEquals(list.size(), 1);

        verifyInProgressApplicationStartInfo(
                0,                                                    // index
                APP_1_PID_1,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        verifyApplicationStartInfo(
                list.get(0),                                          // info
                APP_1_PID_1,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        mAppStartInfoTracker.onActivityLaunchFinished(appStartTimestampIntentStarted, COMPONENT,
                appStartTimestampActivityLaunchFinished, ApplicationStartInfo.LAUNCH_MODE_STANDARD);
        mAppStartInfoTracker.addTimestampToStart(APP_1_PACKAGE_NAME, APP_1_UID,
                appStartTimestampFirstFrameDrawn, ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(1);
        assertEquals(list.size(), 1);

        verifyInProgressApplicationStartInfo(
                0,                                                    // index
                APP_1_PID_1,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN, // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        mAppStartInfoTracker.onReportFullyDrawn(appStartTimestampIntentStarted,
                appStartTimestampReportFullyDrawn);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_1, 0, list);
        verifyInProgressRecordsSize(0);
        assertEquals(list.size(), 1);

        verifyApplicationStartInfo(
                list.get(0),                                          // info
                APP_1_PID_1,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_START_ACTIVITY,     // reason
                ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN, // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        // Don't clear records for use in subsequent cases.

        // Case 4: Create an other app1 record with different pid started for a service
        sleep(1);
        app = makeProcessRecord(
                APP_1_PID_2,                 // pid
                APP_1_UID,                   // uid
                APP_1_UID,                   // packageUid
                APP_1_DEFINING_UID,          // definingUid
                APP_1_PROCESS_NAME,          // processName
                APP_1_PACKAGE_NAME);         // packageName
        ServiceRecord service = ServiceRecord.newEmptyInstanceForTest(mAms);

        mAppStartInfoTracker.handleProcessServiceStart(appStartTimestampService, app, service);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, 0, 0, list);
        assertEquals(list.size(), 2);

        verifyApplicationStartInfo(
                list.get(0),                                          // info
                APP_1_PID_2,                                          // pid
                APP_1_UID,                                            // uid
                APP_1_UID,                                            // packageUid
                APP_1_DEFINING_UID,                                   // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_SERVICE,            // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        // Case 5: Create an instance of app1 with a different user started for a broadcast
        sleep(1);
        app = makeProcessRecord(
                APP_1_PID_USER_2,                // pid
                APP_1_UID_USER_2,                // uid
                APP_1_UID_USER_2,                // packageUid
                null,                            // definingUid
                APP_1_PROCESS_NAME,              // processName
                APP_1_PACKAGE_NAME);             // packageName

        mAppStartInfoTracker.handleProcessBroadcastStart(appStartTimestampBroadcast, app,
                buildIntent(COMPONENT), false /* isAlarm */);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID_USER_2, APP_1_PID_USER_2, 0,
                list);
        assertEquals(list.size(), 1);

        verifyApplicationStartInfo(
                list.get(0),                                          // info
                APP_1_PID_USER_2,                                     // pid
                APP_1_UID_USER_2,                                     // uid
                APP_1_UID_USER_2,                                     // packageUid
                null,                                                 // definingUid
                APP_1_PROCESS_NAME,                                   // processName
                ApplicationStartInfo.START_REASON_BROADCAST,          // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        // Case 6: User 2 gets removed
        mAppStartInfoTracker.onPackageRemoved(APP_1_PACKAGE_NAME, APP_1_UID_USER_2, false);
        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID_USER_2, APP_1_PID_USER_2, 0,
                list);
        assertEquals(list.size(), 0);

        list.clear();
        mAppStartInfoTracker.getStartInfo(APP_1_PACKAGE_NAME, APP_1_UID, APP_1_PID_USER_2, 0, list);
        assertEquals(list.size(), 2);


        // Case 7: Create a process from another package started for a content provider
        final int app2UidUser2 = 1010234;
        final int app2PidUser2 = 12348;
        final String app2ProcessName = "com.android.test.stub2:process";
        final String app2PackageName = "com.android.test.stub2";

        sleep(1);

        app = makeProcessRecord(
                app2PidUser2,                    // pid
                app2UidUser2,                    // uid
                app2UidUser2,                    // packageUid
                null,                            // definingUid
                app2ProcessName,                 // processName
                app2PackageName);                // packageName

        mAppStartInfoTracker.handleProcessContentProviderStart(appStartTimestampRContentProvider,
                app);
        list.clear();
        mAppStartInfoTracker.getStartInfo(app2PackageName, app2UidUser2, app2PidUser2, 0, list);
        assertEquals(list.size(), 1);

        verifyApplicationStartInfo(
                list.get(0),                                          // info
                app2PidUser2,                                         // pid
                app2UidUser2,                                         // uid
                app2UidUser2,                                         // packageUid
                null,                                                 // definingUid
                app2ProcessName,                                      // processName
                ApplicationStartInfo.START_REASON_CONTENT_PROVIDER,   // reason
                ApplicationStartInfo.STARTUP_STATE_STARTED,           // startup state
                ApplicationStartInfo.START_TYPE_COLD,                 // state type
                ApplicationStartInfo.LAUNCH_MODE_STANDARD);           // launch mode

        // Case 8: Save and load again
        ArrayList<ApplicationStartInfo> original = new ArrayList<ApplicationStartInfo>();
        mAppStartInfoTracker.getStartInfo(null, APP_1_UID, 0, 0, original);
        assertTrue(original.size() > 0);

        mAppStartInfoTracker.persistProcessStartInfo();
        assertTrue(mAppStartInfoTracker.mProcStartInfoFile.exists());

        mAppStartInfoTracker.clearProcessStartInfo(false);
        list.clear();
        mAppStartInfoTracker.getStartInfo(null, APP_1_UID, 0, 0, list);
        assertEquals(0, list.size());

        mAppStartInfoTracker.loadExistingProcessStartInfo();
        list.clear();
        mAppStartInfoTracker.getStartInfo(null, APP_1_UID, 0, 0, list);
        assertEquals(original.size(), list.size());

        for (int i = list.size() - 1; i >= 0; i--) {
            assertTrue(list.get(i).equals(original.get(i)));
        }
    }

    /**
     * Test to make sure that in progress records stay within their size limits and discard the
     * correct records.
     */
    @SuppressWarnings("GuardedBy")
    @Test
    public void testInProgressRecordsLimit() throws Exception {
        ProcessRecord app = makeProcessRecord(
                APP_1_PID_1,                 // pid
                APP_1_UID,                   // uid
                APP_1_UID,                   // packageUid
                null,                        // definingUid
                APP_1_PROCESS_NAME,          // processName
                APP_1_PACKAGE_NAME);         // packageName

        // Mock performing 2 x MAX_IN_PROGRESS_RECORDS successful starts and ensure that the list
        // never exceeds the expected size of MAX_IN_PROGRESS_RECORDS.
        for (int i = 0; i < AppStartInfoTracker.MAX_IN_PROGRESS_RECORDS * 2; i++) {
            Long startTime = Long.valueOf(i);
            mAppStartInfoTracker.onIntentStarted(buildIntent(COMPONENT), startTime);
            verifyInProgressRecordsSize(
                    Math.min(i + 1, AppStartInfoTracker.MAX_IN_PROGRESS_RECORDS));

            mAppStartInfoTracker.onActivityLaunched(startTime, COMPONENT,
                    ApplicationStartInfo.START_TYPE_COLD, app);
            verifyInProgressRecordsSize(
                    Math.min(i + 1, AppStartInfoTracker.MAX_IN_PROGRESS_RECORDS));

            mAppStartInfoTracker.onActivityLaunchFinished(startTime, COMPONENT,
                    startTime + 100, ApplicationStartInfo.LAUNCH_MODE_STANDARD);
            verifyInProgressRecordsSize(
                    Math.min(i + 1, AppStartInfoTracker.MAX_IN_PROGRESS_RECORDS));

            // Make sure that the record added in this iteration is still present.
            assertTrue(mAppStartInfoTracker.mInProgressRecords.containsKey(startTime));
        }

        // Confirm that after 2 x MAX_IN_PROGRESS_RECORDS starts only MAX_IN_PROGRESS_RECORDS are
        // present.
        verifyInProgressRecordsSize(AppStartInfoTracker.MAX_IN_PROGRESS_RECORDS);
    }

    /**
     * Test to make sure that records are returned in correct order, from most recently added at
     * index 0 to least recently added at index size - 1.
     */
    @Test
    public void testHistoricalRecordsOrdering() throws Exception {
        // Clear old records
        mAppStartInfoTracker.clearProcessStartInfo(false);

        // Add some records with timestamps 0 decreasing as clock increases.
        ProcessRecord app = makeProcessRecord(
                APP_1_PID_1,                     // pid
                APP_1_UID,                       // uid
                APP_1_UID,                       // packageUid
                null,                            // definingUid
                APP_1_PROCESS_NAME,              // processName
                APP_1_PACKAGE_NAME);             // packageName

        mAppStartInfoTracker.handleProcessBroadcastStart(3, app, buildIntent(COMPONENT),
                false /* isAlarm */);
        // Add a brief delay between timestamps to make sure the clock, which is in milliseconds has
        // actually incremented.
        sleep(1);
        mAppStartInfoTracker.handleProcessBroadcastStart(2, app, buildIntent(COMPONENT),
                false /* isAlarm */);
        sleep(1);
        mAppStartInfoTracker.handleProcessBroadcastStart(1, app, buildIntent(COMPONENT),
                false /* isAlarm */);

        // Get records
        ArrayList<ApplicationStartInfo> list = new ArrayList<ApplicationStartInfo>();
        mAppStartInfoTracker.getStartInfo(null, APP_1_UID, 0, 0, list);

        // Confirm that records are in correct order, with index 0 representing the most recently
        // added record and index size - 1 representing the least recently added one.
        assertEquals(3, list.size());
        assertEquals(1L, list.get(0).getStartupTimestamps().get(0).longValue());
        assertEquals(2L, list.get(1).getStartupTimestamps().get(0).longValue());
        assertEquals(3L, list.get(2).getStartupTimestamps().get(0).longValue());
    }

    /**
     * Test to make sure that persist and restore correctly maintains the state of the monotonic
     * clock.
     */
    @Test
    public void testPersistAndRestoreMonotonicClock() {
        // Make sure we can write to the file.
        assertTrue(FileUtils.createDir(mAppStartInfoTracker.mProcStartStoreDir));

        // No need to persist records for this test, clear any that may be there.
        mAppStartInfoTracker.clearProcessStartInfo(false);

        // Set clock with an arbitrary 5 minute offset, just needs to be longer than it would take
        // for code to run.
        mAppStartInfoTracker.mMonotonicClock = new MonotonicClock(5 * 60 * 1000,
                Clock.SYSTEM_CLOCK);

        // Record the current time.
        long originalMonotonicTime = mAppStartInfoTracker.mMonotonicClock.monotonicTime();

        // Now persist the process start info. Records were cleared above so this should just
        // persist the monotonic time.
        mAppStartInfoTracker.persistProcessStartInfo();

        // Null out the clock to make sure its set on load.
        mAppStartInfoTracker.mMonotonicClock = null;
        assertNull(mAppStartInfoTracker.mMonotonicClock);

        // Now load from disk.
        mAppStartInfoTracker.loadExistingProcessStartInfo();

        // Confirm clock has been set and that its current time is greater than or equal to the
        // previous one, thereby ensuring it was loaded from disk.
        assertNotNull(mAppStartInfoTracker.mMonotonicClock);
        assertTrue(mAppStartInfoTracker.mMonotonicClock.monotonicTime() >= originalMonotonicTime);
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

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private ProcessRecord makeProcessRecord(int pid, int uid, int packageUid, Integer definingUid,
            String processName, String packageName) {
        return makeProcessRecord(pid, uid, packageUid, definingUid, processName, packageName, mAms);
    }

    @SuppressWarnings("GuardedBy")
    static ProcessRecord makeProcessRecord(int pid, int uid, int packageUid, Integer definingUid,
            String processName, String packageName, ActivityManagerService ams) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ProcessRecord app = new ProcessRecord(ams, ai, processName, uid);
        app.setPid(pid);
        app.info.uid = packageUid;
        if (definingUid != null) {
            app.setHostingRecord(HostingRecord.byAppZygote(COMPONENT, "", definingUid, ""));
        }
        return app;
    }

    private static Intent buildIntent(ComponentName componentName) throws Exception {
        Intent intent = new Intent();
        intent.setComponent(componentName);
        intent.setPackage(componentName.getPackageName());
        return intent;
    }

    private void verifyInProgressRecordsSize(int expectedSize) {
        synchronized (mAppStartInfoTracker.mLock) {
            assertEquals(mAppStartInfoTracker.mInProgressRecords.size(), expectedSize);
        }
    }

    private void verifyInProgressApplicationStartInfo(int index,
            Integer pid, Integer uid, Integer packageUid,
            Integer definingUid, String processName,
            Integer reason, Integer startupState, Integer startType, Integer launchMode) {
        synchronized (mAppStartInfoTracker.mLock) {
            verifyApplicationStartInfo(mAppStartInfoTracker.mInProgressRecords.valueAt(index),
                    pid, uid, packageUid, definingUid, processName, reason, startupState,
                    startType, launchMode);
        }
    }

    private void verifyApplicationStartInfo(ApplicationStartInfo info,
            Integer pid, Integer uid, Integer packageUid,
            Integer definingUid, String processName,
            Integer reason, Integer startupState, Integer startType, Integer launchMode) {
        assertNotNull(info);

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
        if (reason != null) {
            assertEquals(reason.intValue(), info.getReason());
        }
        if (startupState != null) {
            assertEquals(startupState.intValue(), info.getStartupState());
        }
        if (startType != null) {
            assertEquals(startType.intValue(), info.getStartType());
        }
        if (launchMode != null) {
            assertEquals(launchMode.intValue(), info.getLaunchMode());
        }
    }

    private class TestInjector extends Injector {
        TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File recentAccessesFile, File storageFile,
                Handler handler) {
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

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
