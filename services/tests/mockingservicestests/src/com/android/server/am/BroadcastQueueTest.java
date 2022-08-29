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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Common tests for {@link BroadcastQueue} implementations.
 */
@MediumTest
@RunWith(Parameterized.class)
public class BroadcastQueueTest {
    private static final String TAG = "BroadcastQueueTest";

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    private final Impl mImpl;

    private enum Impl {
        DEFAULT
    }

    private Context mContext;
    private HandlerThread mHandlerThread;

    @Mock
    private AppOpsService mAppOpsService;
    @Mock
    private PackageManagerInternal mPackageManagerInt;

    private ActivityManagerService mAms;
    private BroadcastQueue mQueue;

    @Parameters(name = "impl={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {Impl.DEFAULT} });
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

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doNothing().when(mPackageManagerInt).setPackageStoppedState(any(), anyBoolean(), anyInt());

        final ActivityManagerService realAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        realAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        realAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        realAms.mAtmInternal = spy(realAms.mActivityTaskManager.getAtmInternal());
        realAms.mPackageManagerInt = mPackageManagerInt;
        mAms = spy(realAms);

        final BroadcastConstants constants = new BroadcastConstants(
                Settings.Global.BROADCAST_FG_CONSTANTS);
        final BroadcastSkipPolicy emptySkipPolicy = new BroadcastSkipPolicy(mAms) {
            public boolean shouldSkip(BroadcastRecord r, ResolveInfo info) {
                return false;
            }
            public boolean shouldSkip(BroadcastRecord r, BroadcastFilter filter) {
                return false;
            }
        };

        if (mImpl == Impl.DEFAULT) {
            mQueue = new BroadcastQueueImpl(mAms, mHandlerThread.getThreadHandler(), TAG,
                    constants, emptySkipPolicy, false);
        } else {
            throw new UnsupportedOperationException();
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
    }

    private ProcessRecord makeActiveProcessRecord(String packageName) throws Exception {
        final ProcessRecord r = new ProcessRecord(mAms, makeApplicationInfo(packageName), null,
                getUidForPackage(packageName));
        final IApplicationThread thread = mock(IApplicationThread.class);
        final IBinder threadBinder = new Binder();
        doReturn(threadBinder).when(thread).asBinder();
        r.makeActive(thread, mAms.mProcessStats);

        doReturn(r).when(mAms).getProcessRecordLocked(eq(r.info.processName), eq(r.info.uid));

        doAnswer((invocation) -> {
            Log.v(TAG, "Delivering finishReceiverLocked() for "
                    + Arrays.toString(invocation.getArguments()));
            mQueue.finishReceiverLocked(threadBinder, Activity.RESULT_OK,
                    null, null, false, false);
            return null;
        }).when(thread).scheduleReceiver(any(), any(), any(), anyInt(), any(), any(), anyBoolean(),
                anyInt(), anyInt());

        return r;
    }

    private ApplicationInfo makeApplicationInfo(String packageName) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.uid = getUidForPackage(packageName);
        return ai;
    }

    private ResolveInfo makeManifestReceiver(String packageName, String name) {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = packageName;
        ri.activityInfo.name = name;
        ri.activityInfo.applicationInfo = makeApplicationInfo(packageName);
        return ri;
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            List receivers) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(), receivers);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            BroadcastOptions options, List receivers) {
        return new BroadcastRecord(mQueue, intent, callerApp, callerApp.info.packageName, null,
                callerApp.getPid(), callerApp.info.uid, false, null, null, null, null,
                AppOpsManager.OP_NONE, options, receivers, null, Activity.RESULT_OK, null, null,
                false, false, false, UserHandle.USER_SYSTEM, false, null, false, null);
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

    private void waitForIdle() throws Exception {
        for (int i = 0; i < 100; i++) {
            if (mQueue.isIdle()) break;
            SystemClock.sleep(100);
        }
        assertTrue(mQueue.isIdle());
    }

    private static final String PACKAGE_RED = "com.example.red";
    private static final String PACKAGE_GREEN = "com.example.green";
    private static final String PACKAGE_BLUE = "com.example.blue";

    private static final String CLASS_RED = "com.example.red.Red";
    private static final String CLASS_GREEN = "com.example.green.Green";
    private static final String CLASS_BLUE = "com.example.blue.Blue";

    private static int getUidForPackage(String packageName) {
        switch (packageName) {
            case PACKAGE_RED: return android.os.Process.FIRST_APPLICATION_UID + 1;
            case PACKAGE_GREEN: return android.os.Process.FIRST_APPLICATION_UID + 2;
            case PACKAGE_BLUE: return android.os.Process.FIRST_APPLICATION_UID + 3;
            default: throw new IllegalArgumentException();
        }
    }

    @Test
    public void testSimple_Manifest_Warm() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        mQueue.enqueueBroadcastLocked(makeBroadcastRecord(intent, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verify(receiverApp.getThread()).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(intent)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    @Test
    public void testSimple_Manifest_Warm_Multiple() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        mQueue.enqueueBroadcastLocked(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mQueue.enqueueBroadcastLocked(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        waitForIdle();
        verify(receiverGreenApp.getThread()).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(timezone)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
        verify(receiverBlueApp.getThread()).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(timezone)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
        verify(receiverBlueApp.getThread()).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(), anyInt(), any(),
                any(), eq(false), eq(UserHandle.USER_SYSTEM), anyInt());
    }

    // TODO: verify registered receiver in warm app
    // TODO: verify manifest receiver in cold app

    // TODO: verify mixing multiple manifest and registered receivers of same broadcast
    // TODO: verify delivery of 3 distinct broadcasts
}
