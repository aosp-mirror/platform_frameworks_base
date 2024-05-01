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

import static android.os.Process.myPid;
import static android.os.Process.myUid;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManagerInternal;
import android.app.IApplicationThread;
import android.app.IProcessObserver;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.DropBoxManagerInternal;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Arrays;


/**
 * Tests to verify that process events are dispatched to process observers.
 */
@MediumTest
@SuppressWarnings("GuardedBy")
public class ProcessObserverTest {
    private static final String TAG = "ProcessObserverTest";

    private static final String PACKAGE = "com.foo";

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    private Context mContext;
    private HandlerThread mHandlerThread;

    @Mock
    private AppOpsService mAppOpsService;
    @Mock
    private DropBoxManagerInternal mDropBoxManagerInt;
    @Mock
    private PackageManagerInternal mPackageManagerInt;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInt;
    @Mock
    private ActivityManagerInternal mActivityManagerInt;
    @Mock
    private ActivityTaskManagerInternal mActivityTaskManagerInt;
    @Mock
    private BatteryStatsService mBatteryStatsService;

    private ActivityManagerService mRealAms;
    private ActivityManagerService mAms;

    private ProcessList mRealProcessList = new ProcessList();
    private ProcessList mProcessList;

    final IProcessObserver mProcessObserver = mock(IProcessObserver.Stub.class);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.addService(DropBoxManagerInternal.class, mDropBoxManagerInt);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);

        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mActivityManagerInt);

        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(ActivityTaskManagerInternal.class, mActivityTaskManagerInt);

        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doReturn(true).when(mActivityTaskManagerInt).attachApplication(any());
        doNothing().when(mActivityTaskManagerInt).onProcessMapped(anyInt(), any());

        mRealAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        mRealAms.mConstants.loadDeviceConfigConstants();
        mRealAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mRealAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        mRealAms.mAtmInternal = mActivityTaskManagerInt;
        mRealAms.mPackageManagerInt = mPackageManagerInt;
        mRealAms.mUsageStatsService = mUsageStatsManagerInt;
        mRealAms.mProcessesReady = true;
        mAms = spy(mRealAms);
        mRealProcessList.mService = mAms;
        mProcessList = spy(mRealProcessList);

        doReturn(mProcessObserver).when(mProcessObserver).asBinder();
        mProcessList.registerProcessObserver(mProcessObserver);

        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting isProcStartValidLocked() for "
                    + Arrays.toString(invocation.getArguments()));
            return null;
        }).when(mProcessList).isProcStartValidLocked(any(), anyLong());
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
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
            return mHandlerThread.getThreadHandler();
        }

        @Override
        public ProcessList getProcessList(ActivityManagerService service) {
            return mRealProcessList;
        }

        @Override
        public BatteryStatsService getBatteryStatsService() {
            return mBatteryStatsService;
        }
    }

    private ProcessRecord makeActiveProcessRecord(String packageName)
            throws Exception {
        final ApplicationInfo ai = makeApplicationInfo(packageName);
        return makeActiveProcessRecord(ai);
    }

    private ProcessRecord makeActiveProcessRecord(ApplicationInfo ai)
            throws Exception {
        final IApplicationThread thread = mock(IApplicationThread.class);
        final IBinder threadBinder = new Binder();
        doReturn(threadBinder).when(thread).asBinder();
        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting bindApplication() for "
                    + Arrays.toString(invocation.getArguments()));
            if (mRealAms.mConstants.mEnableWaitForFinishAttachApplication) {
                mRealAms.finishAttachApplication(0, 0);
            }
            return null;
        }).when(thread).bindApplication(
                any(), any(),
                any(), any(), anyBoolean(),
                any(), any(),
                any(), any(),
                any(),
                any(), anyInt(),
                anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), any(),
                any(), any(), any(),
                any(), any(),
                any(), any(),
                any(), any(),
                anyLong(), anyLong());
        final ProcessRecord r = spy(new ProcessRecord(mAms, ai, ai.processName, ai.uid));
        r.setPid(myPid());
        r.setStartUid(myUid());
        r.setHostingRecord(new HostingRecord(HostingRecord.HOSTING_TYPE_BROADCAST));
        r.makeActive(thread, mAms.mProcessStats);
        doNothing().when(r).killLocked(any(), any(), anyInt(), anyInt(), anyBoolean(),
                anyBoolean());
        return r;
    }

    static ApplicationInfo makeApplicationInfo(String packageName) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.processName = packageName;
        ai.uid = myUid();
        return ai;
    }

    /**
     * Verify that a process start event is dispatched to process observers.
     */
    @Test
    public void testNormal() throws Exception {
        ProcessRecord app = startProcess();
        verify(mProcessObserver).onProcessStarted(
                app.getPid(), app.uid, app.info.uid, PACKAGE, PACKAGE);
    }

    private ProcessRecord startProcess() throws Exception {
        final ProcessRecord app = makeActiveProcessRecord(PACKAGE);
        final ApplicationInfo appInfo = makeApplicationInfo(PACKAGE);
        mProcessList.handleProcessStartedLocked(app, app.getPid(), /* usingWrapper */ false,
                /* expectedStartSeq */ 0, /* procAttached */ false);
        app.getThread().bindApplication(PACKAGE, appInfo,
                null, null, false,
                null,
                null,
                null, null,
                null,
                null, 0,
                false, false,
                true, false,
                null,
                null, null,
                null,
                null, null, null,
                null, null, null,
                0, 0);
        return app;
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
