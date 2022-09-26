/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.RemoteServiceException.CannotDeliverBroadcastException;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerExemptionManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Common tests for {@link BroadcastQueue} implementations.
 */
@MediumTest
@RunWith(Parameterized.class)
@SuppressWarnings("GuardedBy")
public class BroadcastQueueTest {
    private static final String TAG = "BroadcastQueueTest";

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    private final Impl mImpl;

    private enum Impl {
        DEFAULT,
        MODERN,
    }

    private Context mContext;
    private HandlerThread mHandlerThread;
    private AtomicInteger mNextPid;

    @Mock
    private AppOpsService mAppOpsService;
    @Mock
    private ProcessList mProcessList;
    @Mock
    private PackageManagerInternal mPackageManagerInt;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInt;

    private ActivityManagerService mAms;
    private BroadcastQueue mQueue;

    /**
     * When enabled {@link ActivityManagerService#startProcessLocked} will fail
     * by returning {@code null}; otherwise it will spawn a new mock process.
     */
    private boolean mFailStartProcess;

    /**
     * Map from PID to registered registered runtime receivers.
     */
    private SparseArray<ReceiverList> mRegisteredReceivers = new SparseArray<>();

    /**
     * Collection of all active processes during current test run.
     */
    private List<ProcessRecord> mActiveProcesses = new ArrayList<>();

    @Parameters(name = "impl={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {Impl.DEFAULT}, {Impl.MODERN} });
    }

    public BroadcastQueueTest(Impl impl) {
        mImpl = impl;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mNextPid = new AtomicInteger(100);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doNothing().when(mPackageManagerInt).setPackageStoppedState(any(), anyBoolean(), anyInt());
        doAnswer((invocation) -> {
            return getUidForPackage(invocation.getArgument(0));
        }).when(mPackageManagerInt).getPackageUid(any(), anyLong(), eq(UserHandle.USER_SYSTEM));

        final ActivityManagerService realAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        realAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        realAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        realAms.mAtmInternal = spy(realAms.mActivityTaskManager.getAtmInternal());
        realAms.mOomAdjuster.mCachedAppOptimizer = spy(realAms.mOomAdjuster.mCachedAppOptimizer);
        realAms.mPackageManagerInt = mPackageManagerInt;
        realAms.mUsageStatsService = mUsageStatsManagerInt;
        realAms.mProcessesReady = true;
        mAms = spy(realAms);
        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting startProcessLocked() for "
                    + Arrays.toString(invocation.getArguments()));
            if (mFailStartProcess) {
                return null;
            }
            final String processName = invocation.getArgument(0);
            final ApplicationInfo ai = invocation.getArgument(1);
            final ProcessRecord res = makeActiveProcessRecord(ai, processName,
                    ProcessBehavior.NORMAL, UnaryOperator.identity());
            mHandlerThread.getThreadHandler().post(() -> {
                synchronized (mAms) {
                    mQueue.onApplicationAttachedLocked(res);
                }
            });
            return res;
        }).when(mAms).startProcessLocked(any(), any(), anyBoolean(), anyInt(),
                any(), anyInt(), anyBoolean(), anyBoolean());
        doNothing().when(mAms).appNotResponding(any(), any());

        final BroadcastConstants constants = new BroadcastConstants(
                Settings.Global.BROADCAST_FG_CONSTANTS);
        constants.TIMEOUT = 100;
        constants.ALLOW_BG_ACTIVITY_START_TIMEOUT = 0;
        final BroadcastSkipPolicy emptySkipPolicy = new BroadcastSkipPolicy(mAms) {
            public boolean shouldSkip(BroadcastRecord r, ResolveInfo info) {
                return false;
            }
            public boolean shouldSkip(BroadcastRecord r, BroadcastFilter filter) {
                return false;
            }
        };
        final BroadcastHistory emptyHistory = new BroadcastHistory() {
            public void addBroadcastToHistoryLocked(BroadcastRecord original) {
            }
        };

        if (mImpl == Impl.DEFAULT) {
            mQueue = new BroadcastQueueImpl(mAms, mHandlerThread.getThreadHandler(), TAG,
                    constants, emptySkipPolicy, emptyHistory, false,
                    ProcessList.SCHED_GROUP_DEFAULT);
        } else if (mImpl == Impl.MODERN) {
            mQueue = new BroadcastQueueModernImpl(mAms, mHandlerThread.getThreadHandler(),
                    constants, constants, emptySkipPolicy, emptyHistory);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();

        // Verify that all processes have finished handling broadcasts
        for (ProcessRecord app : mActiveProcesses) {
            assertTrue(app.toShortString(), app.mReceivers.numberOfCurReceivers() == 0);
            assertTrue(app.toShortString(), mQueue.getPreferredSchedulingGroupLocked(app)
                    == ProcessList.SCHED_GROUP_UNDEFINED);
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
            return mHandlerThread.getThreadHandler();
        }

        @Override
        public ProcessList getProcessList(ActivityManagerService service) {
            return mProcessList;
        }
    }

    /**
     * Helper that leverages try-with-resources to pause dispatch of
     * {@link #mHandlerThread} until released.
     */
    private class SyncBarrier implements AutoCloseable {
        private final int mToken;

        public SyncBarrier() {
            mToken = mHandlerThread.getLooper().getQueue().postSyncBarrier();
        }

        @Override
        public void close() throws Exception {
            mHandlerThread.getLooper().getQueue().removeSyncBarrier(mToken);
        }
    }

    private enum ProcessBehavior {
        /** Process broadcasts normally */
        NORMAL,
        /** Wedge and never confirm broadcast receipt */
        WEDGE,
        /** Process broadcast by requesting abort */
        ABORT,
        /** Appear to behave completely dead */
        DEAD,
    }

    private ProcessRecord makeActiveProcessRecord(String packageName) throws Exception {
        final ApplicationInfo ai = makeApplicationInfo(packageName);
        return makeActiveProcessRecord(ai, ai.processName, ProcessBehavior.NORMAL,
                UnaryOperator.identity());
    }

    private ProcessRecord makeActiveProcessRecord(String packageName,
            ProcessBehavior behavior) throws Exception {
        final ApplicationInfo ai = makeApplicationInfo(packageName);
        return makeActiveProcessRecord(ai, ai.processName, behavior,
                UnaryOperator.identity());
    }

    private ProcessRecord makeActiveProcessRecord(ApplicationInfo ai, String processName,
            ProcessBehavior behavior, UnaryOperator<Bundle> extrasOperator) throws Exception {
        final boolean wedge = (behavior == ProcessBehavior.WEDGE);
        final boolean abort = (behavior == ProcessBehavior.ABORT);
        final boolean dead = (behavior == ProcessBehavior.DEAD);

        final ProcessRecord r = spy(new ProcessRecord(mAms, ai, processName, ai.uid));
        r.setPid(mNextPid.getAndIncrement());
        mActiveProcesses.add(r);

        final IApplicationThread thread;
        if (dead) {
            thread = mock(IApplicationThread.class, (invocation) -> {
                throw new DeadObjectException();
            });
        } else {
            thread = mock(IApplicationThread.class);
        }
        final IBinder threadBinder = new Binder();
        doReturn(threadBinder).when(thread).asBinder();
        r.makeActive(thread, mAms.mProcessStats);
        doReturn(r).when(mAms).getProcessRecordLocked(eq(r.info.processName), eq(r.info.uid));

        final IIntentReceiver receiver = mock(IIntentReceiver.class);
        final IBinder receiverBinder = new Binder();
        doReturn(receiverBinder).when(receiver).asBinder();
        final ReceiverList receiverList = new ReceiverList(mAms, r, r.getPid(), r.info.uid,
                UserHandle.getUserId(r.info.uid), receiver);
        mRegisteredReceivers.put(r.getPid(), receiverList);

        // If we're entirely dead, rely on default behaviors above
        if (dead) return r;

        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting scheduleReceiver() for "
                    + Arrays.toString(invocation.getArguments()));
            final Bundle extras = invocation.getArgument(5);
            if (!wedge) {
                assertTrue(r.mReceivers.numberOfCurReceivers() > 0);
                assertTrue(mQueue.getPreferredSchedulingGroupLocked(r)
                        != ProcessList.SCHED_GROUP_UNDEFINED);
                mHandlerThread.getThreadHandler().post(() -> {
                    synchronized (mAms) {
                        mQueue.finishReceiverLocked(r, Activity.RESULT_OK, null,
                                extrasOperator.apply(extras), abort, false);
                    }
                });
            }
            return null;
        }).when(thread).scheduleReceiver(any(), any(), any(), anyInt(), any(), any(), anyBoolean(),
                anyInt(), anyInt());

        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting scheduleRegisteredReceiver() for "
                    + Arrays.toString(invocation.getArguments()));
            final Bundle extras = invocation.getArgument(4);
            final boolean ordered = invocation.getArgument(5);
            if (!wedge && ordered) {
                assertTrue(r.mReceivers.numberOfCurReceivers() > 0);
                assertTrue(mQueue.getPreferredSchedulingGroupLocked(r)
                        != ProcessList.SCHED_GROUP_UNDEFINED);
                mHandlerThread.getThreadHandler().post(() -> {
                    synchronized (mAms) {
                        mQueue.finishReceiverLocked(r, Activity.RESULT_OK,
                                null, extrasOperator.apply(extras), abort, false);
                    }
                });
            }
            return null;
        }).when(thread).scheduleRegisteredReceiver(any(), any(), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt());

        return r;
    }

    static ApplicationInfo makeApplicationInfo(String packageName) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.processName = packageName;
        ai.uid = getUidForPackage(packageName);
        return ai;
    }

    static ResolveInfo makeManifestReceiver(String packageName, String name) {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = packageName;
        ri.activityInfo.processName = packageName;
        ri.activityInfo.name = name;
        ri.activityInfo.applicationInfo = makeApplicationInfo(packageName);
        return ri;
    }

    private BroadcastFilter makeRegisteredReceiver(ProcessRecord app) {
        final ReceiverList receiverList = mRegisteredReceivers.get(app.getPid());
        final IntentFilter filter = new IntentFilter();
        final BroadcastFilter res = new BroadcastFilter(filter, receiverList,
                receiverList.app.info.packageName, null, null, null, receiverList.uid,
                receiverList.userId, false, false, true);
        receiverList.add(res);
        return res;
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            List receivers) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(),
                receivers, false, null, null);
    }

    private BroadcastRecord makeOrderedBroadcastRecord(Intent intent, ProcessRecord callerApp,
            List receivers, IIntentReceiver orderedResultTo, Bundle orderedExtras) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(),
                receivers, true, orderedResultTo, orderedExtras);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            BroadcastOptions options, List receivers) {
        return makeBroadcastRecord(intent, callerApp, options, receivers, false, null, null);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            BroadcastOptions options, List receivers, boolean ordered,
            IIntentReceiver orderedResultTo, Bundle orderedExtras) {
        return new BroadcastRecord(mQueue, intent, callerApp, callerApp.info.packageName, null,
                callerApp.getPid(), callerApp.info.uid, false, null, null, null, null,
                AppOpsManager.OP_NONE, options, receivers, orderedResultTo, Activity.RESULT_OK,
                null, orderedExtras, ordered, false, false, UserHandle.USER_SYSTEM, false, null,
                false, null);
    }

    private ArgumentMatcher<Intent> filterEquals(Intent intent) {
        return (test) -> {
            return intent.filterEquals(test);
        };
    }

    private ArgumentMatcher<Intent> filterEqualsIgnoringComponent(Intent intent) {
        final Intent intentClean = new Intent(intent);
        intentClean.setComponent(null);
        return (test) -> {
            final Intent testClean = new Intent(test);
            testClean.setComponent(null);
            return intentClean.filterEquals(testClean);
        };
    }

    private ArgumentMatcher<Bundle> bundleEquals(Bundle bundle) {
        return (test) -> {
            // TODO: check values in addition to keys
            return Objects.equals(test.keySet(), bundle.keySet());
        };
    }

    private @NonNull Bundle clone(@Nullable Bundle b) {
        return (b != null) ? new Bundle(b) : new Bundle();
    }

    private void enqueueBroadcast(BroadcastRecord r) {
        synchronized (mAms) {
            mQueue.enqueueBroadcastLocked(r);
        }
    }

    private void waitForIdle() throws Exception {
        mQueue.waitForIdle(null);
    }

    private void verifyScheduleReceiver(ProcessRecord app, Intent intent) throws Exception {
        verify(app.getThread()).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(intent)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    private void verifyScheduleReceiver(VerificationMode mode, ProcessRecord app, Intent intent)
            throws Exception {
        verify(app.getThread(), mode).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(intent)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    private void verifyScheduleReceiver(VerificationMode mode, ProcessRecord app, Intent intent,
            ComponentName component) throws Exception {
        final Intent targetedIntent = new Intent(intent);
        targetedIntent.setComponent(component);
        verify(app.getThread(), mode).scheduleReceiver(
                argThat(filterEquals(targetedIntent)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    private void verifyScheduleRegisteredReceiver(ProcessRecord app, Intent intent)
            throws Exception {
        verify(app.getThread()).scheduleRegisteredReceiver(any(),
                argThat(filterEqualsIgnoringComponent(intent)), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    static final int USER_GUEST = 11;

    static final String PACKAGE_RED = "com.example.red";
    static final String PACKAGE_GREEN = "com.example.green";
    static final String PACKAGE_BLUE = "com.example.blue";
    static final String PACKAGE_YELLOW = "com.example.yellow";

    static final String CLASS_RED = "com.example.red.Red";
    static final String CLASS_GREEN = "com.example.green.Green";
    static final String CLASS_BLUE = "com.example.blue.Blue";
    static final String CLASS_YELLOW = "com.example.yellow.Yellow";

    static int getUidForPackage(@NonNull String packageName) {
        switch (packageName) {
            case PACKAGE_RED: return android.os.Process.FIRST_APPLICATION_UID + 1;
            case PACKAGE_GREEN: return android.os.Process.FIRST_APPLICATION_UID + 2;
            case PACKAGE_BLUE: return android.os.Process.FIRST_APPLICATION_UID + 3;
            case PACKAGE_YELLOW: return android.os.Process.FIRST_APPLICATION_UID + 4;
            default: throw new IllegalArgumentException();
        }
    }

    @Test
    public void testDump() throws Exception {
        // To maximize test coverage, dump current state; we're not worried
        // about the actual output, just that we don't crash
        mQueue.dumpLocked(FileDescriptor.err, new PrintWriter(new ByteArrayOutputStream()),
                null, 0, true, null, false);
    }

    /**
     * Verify dispatch of simple broadcast to single manifest receiver in
     * already-running warm app.
     */
    @Test
    public void testSimple_Manifest_Warm() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(intent, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verifyScheduleReceiver(receiverApp, intent);
    }

    /**
     * Verify dispatch of multiple broadcasts to multiple manifest receivers in
     * already-running warm apps.
     */
    @Test
    public void testSimple_Manifest_Warm_Multiple() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        waitForIdle();
        verifyScheduleReceiver(receiverGreenApp, timezone);
        verifyScheduleReceiver(receiverBlueApp, timezone);
        verifyScheduleReceiver(receiverBlueApp, airplane);
    }

    /**
     * Verify dispatch of multiple broadcast to multiple manifest receivers in
     * apps that require cold starts.
     */
    @Test
    public void testSimple_Manifest_ColdThenWarm() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // We purposefully dispatch into green twice; the first time cold and
        // the second time it should already be running

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        final ProcessRecord receiverGreenApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final ProcessRecord receiverBlueApp = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyScheduleReceiver(receiverGreenApp, timezone);
        verifyScheduleReceiver(receiverGreenApp, airplane);
        verifyScheduleReceiver(receiverBlueApp, timezone);
    }

    /**
     * Verify dispatch of simple broadcast to single registered receiver in
     * already-running warm app.
     */
    @Test
    public void testSimple_Registered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(intent, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));

        waitForIdle();
        verifyScheduleRegisteredReceiver(receiverApp, intent);
    }

    /**
     * Verify dispatch of multiple broadcasts to multiple registered receivers
     * in already-running warm apps.
     */
    @Test
    public void testSimple_Registered_Multiple() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeRegisteredReceiver(receiverGreenApp),
                        makeRegisteredReceiver(receiverBlueApp))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverBlueApp))));

        waitForIdle();
        verifyScheduleRegisteredReceiver(receiverGreenApp, timezone);
        verifyScheduleRegisteredReceiver(receiverBlueApp, timezone);
        verifyScheduleRegisteredReceiver(receiverBlueApp, airplane);
    }

    /**
     * Verify dispatch of multiple broadcasts mixed to both manifest and
     * registered receivers, to both warm and cold apps.
     */
    @Test
    public void testComplex() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverYellowApp = makeActiveProcessRecord(PACKAGE_YELLOW);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeRegisteredReceiver(receiverGreenApp),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeRegisteredReceiver(receiverYellowApp))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.setComponent(new ComponentName(PACKAGE_YELLOW, CLASS_YELLOW));
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.recordResponseEventWhileInBackground(42L);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, options,
                List.of(makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW))));

        waitForIdle();
        final ProcessRecord receiverBlueApp = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyScheduleReceiver(receiverGreenApp, timezone);
        verifyScheduleRegisteredReceiver(receiverGreenApp, timezone);
        verifyScheduleReceiver(receiverBlueApp, timezone);
        verifyScheduleRegisteredReceiver(receiverYellowApp, timezone);
        verifyScheduleReceiver(receiverYellowApp, airplane);

        for (ProcessRecord receiverApp : new ProcessRecord[] {
                receiverGreenApp, receiverBlueApp, receiverYellowApp
        }) {
            // Confirm expected OOM adjustments; we were invoked once to upgrade
            // and once to downgrade
            assertEquals(ActivityManager.PROCESS_STATE_RECEIVER,
                    receiverApp.mState.getReportedProcState());
            verify(mAms, times(2)).enqueueOomAdjTargetLocked(eq(receiverApp));

            if ((mImpl == Impl.DEFAULT) && (receiverApp == receiverBlueApp)) {
                // Nuance: the default implementation doesn't ask for manifest
                // cold-started apps to be thawed, but the modern stack does
            } else {
                // Confirm that app was thawed
                verify(mAms.mOomAdjuster.mCachedAppOptimizer).unfreezeTemporarily(eq(receiverApp),
                        eq(OomAdjuster.OOM_ADJ_REASON_START_RECEIVER));

                // Confirm that we added package to process
                verify(receiverApp, atLeastOnce()).addPackage(eq(receiverApp.info.packageName),
                        anyLong(), any());
            }

            // Confirm that we've reported package as being used
            verify(mAms, atLeastOnce()).notifyPackageUse(eq(receiverApp.info.packageName),
                    eq(PackageManager.NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER));

            // Confirm that we unstopped manifest receivers
            verify(mAms.mPackageManagerInt, atLeastOnce()).setPackageStoppedState(
                    eq(receiverApp.info.packageName), eq(false), eq(UserHandle.USER_SYSTEM));
        }

        // Confirm that we've reported expected usage events
        verify(mAms.mUsageStatsService).reportBroadcastDispatched(eq(callerApp.uid),
                eq(PACKAGE_YELLOW), eq(UserHandle.SYSTEM), eq(42L), anyLong(), anyInt());
        verify(mAms.mUsageStatsService).reportEvent(eq(PACKAGE_YELLOW), eq(UserHandle.USER_SYSTEM),
                eq(Event.APP_COMPONENT_USED));
    }

    /**
     * Verify that we detect and ANR a wedged process.
     */
    @Test
    public void testWedged() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.WEDGE);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verify(mAms).appNotResponding(eq(receiverApp), any());
    }

    /**
     * Verify that we handle registered receivers in a process that always
     * responds with {@link DeadObjectException}, recovering to restart the
     * process and deliver their next broadcast.
     */
    @Test
    public void testDead_Registered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.DEAD);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        waitForIdle();

        // First broadcast should have already been dead
        verifyScheduleRegisteredReceiver(receiverApp, airplane);
        verify(receiverApp).scheduleCrashLocked(any(),
                eq(CannotDeliverBroadcastException.TYPE_ID), any());

        // Second broadcast in new process should work fine
        final ProcessRecord restartedReceiverApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        assertNotEquals(receiverApp, restartedReceiverApp);
        verifyScheduleReceiver(restartedReceiverApp, timezone);
    }

    /**
     * Verify that we handle manifest receivers in a process that always
     * responds with {@link DeadObjectException}, recovering to restart the
     * process and deliver their next broadcast.
     */
    @Test
    public void testDead_Manifest() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.DEAD);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        waitForIdle();

        // First broadcast should have already been dead
        verifyScheduleReceiver(receiverApp, airplane);
        verify(receiverApp).scheduleCrashLocked(any(),
                eq(CannotDeliverBroadcastException.TYPE_ID), any());

        // Second broadcast in new process should work fine
        final ProcessRecord restartedReceiverApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        assertNotEquals(receiverApp, restartedReceiverApp);
        verifyScheduleReceiver(restartedReceiverApp, timezone);
    }

    /**
     * Verify that we handle the system failing to start a process.
     */
    @Test
    public void testFailStartProcess() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Send broadcast while process starts are failing
        mFailStartProcess = true;
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW))));

        // Confirm that queue goes idle, with no processes
        waitForIdle();
        assertEquals(1, mActiveProcesses.size());

        // Send more broadcasts with working process starts
        mFailStartProcess = false;
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW))));

        // Confirm that we only saw second broadcast
        waitForIdle();
        assertEquals(3, mActiveProcesses.size());
        final ProcessRecord receiverGreenApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final ProcessRecord receiverYellowApp = mAms.getProcessRecordLocked(PACKAGE_YELLOW,
                getUidForPackage(PACKAGE_YELLOW));
        verifyScheduleReceiver(never(), receiverGreenApp, airplane);
        verifyScheduleReceiver(never(), receiverYellowApp, airplane);
        verifyScheduleReceiver(times(1), receiverGreenApp, timezone);
        verifyScheduleReceiver(times(1), receiverYellowApp, timezone);
    }

    /**
     * Verify that we cleanup a disabled component, skipping a pending dispatch
     * of broadcast to that component.
     */
    @Test
    public void testCleanup() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        try (SyncBarrier b = new SyncBarrier()) {
            enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, new ArrayList<>(
                    List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_RED),
                            makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                            makeManifestReceiver(PACKAGE_GREEN, CLASS_BLUE)))));

            synchronized (mAms) {
                mQueue.cleanupDisabledPackageReceiversLocked(PACKAGE_GREEN, Set.of(CLASS_GREEN),
                        UserHandle.USER_SYSTEM);

                // Also try clearing out other unrelated things that should leave
                // the final receiver intact
                mQueue.cleanupDisabledPackageReceiversLocked(PACKAGE_RED, null,
                        UserHandle.USER_SYSTEM);
                mQueue.cleanupDisabledPackageReceiversLocked(null, null, USER_GUEST);
            }

            // To maximize test coverage, dump current state; we're not worried
            // about the actual output, just that we don't crash
            mQueue.dumpLocked(FileDescriptor.err, new PrintWriter(new ByteArrayOutputStream()),
                    null, 0, true, null, false);
        }

        waitForIdle();
        verifyScheduleReceiver(times(1), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_RED));
        verifyScheduleReceiver(never(), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_GREEN));
        verifyScheduleReceiver(times(1), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_BLUE));
    }

    /**
     * Verify that we skip broadcasts to an app being backed up.
     */
    @Test
    public void testBackup() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);
        receiverApp.setInFullBackup(true);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verifyScheduleReceiver(never(), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_GREEN));
    }

    /**
     * Verify that an ordered broadcast collects results from everyone along the
     * chain, and is delivered to final destination.
     */
    @Test
    public void testOrdered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Purposefully warm-start the middle apps to make sure we dispatch to
        // both cold and warm apps in expected order
        makeActiveProcessRecord(makeApplicationInfo(PACKAGE_BLUE), PACKAGE_BLUE,
                ProcessBehavior.NORMAL, (extras) -> {
                    extras = clone(extras);
                    extras.putBoolean(PACKAGE_BLUE, true);
                    return extras;
                });
        makeActiveProcessRecord(makeApplicationInfo(PACKAGE_YELLOW), PACKAGE_YELLOW,
                ProcessBehavior.NORMAL, (extras) -> {
                    extras = clone(extras);
                    extras.putBoolean(PACKAGE_YELLOW, true);
                    return extras;
                });

        final IIntentReceiver orderedResultTo = mock(IIntentReceiver.class);
        final Bundle orderedExtras = new Bundle();
        orderedExtras.putBoolean(PACKAGE_RED, true);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeOrderedBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW)),
                orderedResultTo, orderedExtras));

        waitForIdle();
        final IApplicationThread greenThread = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN)).getThread();
        final IApplicationThread blueThread = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE)).getThread();
        final IApplicationThread yellowThread = mAms.getProcessRecordLocked(PACKAGE_YELLOW,
                getUidForPackage(PACKAGE_YELLOW)).getThread();
        final IApplicationThread redThread = mAms.getProcessRecordLocked(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED)).getThread();

        // Verify that we called everyone in specific order, and that each of
        // them observed the expected extras at that stage
        final InOrder inOrder = inOrder(greenThread, blueThread, yellowThread, redThread);
        final Bundle expectedExtras = new Bundle();
        expectedExtras.putBoolean(PACKAGE_RED, true);
        inOrder.verify(greenThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)), eq(true),
                eq(UserHandle.USER_SYSTEM), anyInt());
        inOrder.verify(blueThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)), eq(true),
                eq(UserHandle.USER_SYSTEM), anyInt());
        expectedExtras.putBoolean(PACKAGE_BLUE, true);
        inOrder.verify(yellowThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)), eq(true),
                eq(UserHandle.USER_SYSTEM), anyInt());
        expectedExtras.putBoolean(PACKAGE_YELLOW, true);
        inOrder.verify(redThread).scheduleRegisteredReceiver(any(), argThat(filterEquals(airplane)),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)), eq(false),
                anyBoolean(), eq(UserHandle.USER_SYSTEM), anyInt());

        // Finally, verify that we thawed the final receiver
        verify(mAms.mOomAdjuster.mCachedAppOptimizer).unfreezeTemporarily(eq(callerApp),
                eq(OomAdjuster.OOM_ADJ_REASON_FINISH_RECEIVER));
    }

    /**
     * Verify that an ordered broadcast can be aborted partially through
     * dispatch, and is then delivered to final destination.
     */
    @Test
    public void testOrdered_Aborting() throws Exception {
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        doOrdered_Aborting(airplane);
    }

    /**
     * Verify that an ordered broadcast marked with
     * {@link Intent#FLAG_RECEIVER_NO_ABORT} cannot be aborted partially through
     * dispatch, and is delivered to everyone in order.
     */
    @Test
    public void testOrdered_Aborting_NoAbort() throws Exception {
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
        doOrdered_Aborting(airplane);
    }

    public void doOrdered_Aborting(@NonNull Intent intent) throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Create a process that aborts any ordered broadcasts
        makeActiveProcessRecord(makeApplicationInfo(PACKAGE_GREEN), PACKAGE_GREEN,
                ProcessBehavior.ABORT, (extras) -> {
                    extras = clone(extras);
                    extras.putBoolean(PACKAGE_GREEN, true);
                    return extras;
                });
        makeActiveProcessRecord(PACKAGE_BLUE);

        final IIntentReceiver orderedResultTo = mock(IIntentReceiver.class);

        enqueueBroadcast(makeOrderedBroadcastRecord(intent, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE)),
                orderedResultTo, null));

        waitForIdle();
        final IApplicationThread greenThread = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN)).getThread();
        final IApplicationThread blueThread = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE)).getThread();
        final IApplicationThread redThread = mAms.getProcessRecordLocked(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED)).getThread();

        final Bundle expectedExtras = new Bundle();
        expectedExtras.putBoolean(PACKAGE_GREEN, true);

        // Verify that we always invoke the first receiver, but then we might
        // have invoked or skipped the second receiver depending on the intent
        // flag policy; we always deliver to final receiver regardless of abort
        final InOrder inOrder = inOrder(greenThread, blueThread, redThread);
        inOrder.verify(greenThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(intent)), any(), any(),
                eq(Activity.RESULT_OK), any(), any(), eq(true), eq(UserHandle.USER_SYSTEM),
                anyInt());
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) != 0) {
            inOrder.verify(blueThread).scheduleReceiver(
                    argThat(filterEqualsIgnoringComponent(intent)), any(), any(),
                    eq(Activity.RESULT_OK), any(), any(), eq(true), eq(UserHandle.USER_SYSTEM),
                    anyInt());
        } else {
            inOrder.verify(blueThread, never()).scheduleReceiver(any(), any(), any(), anyInt(),
                    any(), any(), anyBoolean(), anyInt(), anyInt());
        }
        inOrder.verify(redThread).scheduleRegisteredReceiver(any(), argThat(filterEquals(intent)),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)),
                eq(false), anyBoolean(), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    @Test
    public void testBackgroundActivityStarts() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Binder backgroundActivityStartsToken = new Binder();
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord r = new BroadcastRecord(mQueue, intent, callerApp,
                callerApp.info.packageName, null, callerApp.getPid(), callerApp.info.uid, false,
                null, null, null, null, AppOpsManager.OP_NONE, BroadcastOptions.makeBasic(),
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), null, Activity.RESULT_OK,
                null, null, false, false, false, UserHandle.USER_SYSTEM, true,
                backgroundActivityStartsToken, false, null);
        enqueueBroadcast(r);

        waitForIdle();
        verify(receiverApp).addOrUpdateAllowBackgroundActivityStartsToken(eq(r),
                eq(backgroundActivityStartsToken));
        verify(receiverApp).removeAllowBackgroundActivityStartsToken(eq(r));
    }

    @Test
    public void testOptions_TemporaryAppAllowlist() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppAllowlist(1_000,
                PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_VPN, TAG);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, options,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verify(mAms).tempAllowlistUidLocked(eq(receiverApp.uid), eq(1_000L),
                eq(options.getTemporaryAppAllowlistReasonCode()), any(),
                eq(options.getTemporaryAppAllowlistType()), eq(callerApp.uid));
    }
}
