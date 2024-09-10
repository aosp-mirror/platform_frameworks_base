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

import static android.app.ActivityManager.PROCESS_STATE_SERVICE;

import static com.android.server.am.ApplicationExitInfoTest.makeProcessRecord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IApplicationThread;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.DropBoxManagerInternal;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.am.ApplicationExitInfoTest.ServiceThreadRule;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

/**
 * Test class for the service timeout.
 *
 * Build/Install/Run:
 *  atest ServiceTimeoutTest
 */
@Presubmit
public final class ServiceTimeoutTest {
    private static final String TAG = ServiceTimeoutTest.class.getSimpleName();
    private static final long DEFAULT_SERVICE_TIMEOUT = 2000;

    @Rule
    public final ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();
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

    private ActivityManagerService mAms;
    private ProcessList mProcessList;
    private ActiveServices mActiveServices;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mProcessList = spy(new ProcessList());

        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.addService(DropBoxManagerInternal.class, mDropBoxManagerInt);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();

        final ActivityManagerService realAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        realAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        realAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        realAms.mAtmInternal = spy(realAms.mActivityTaskManager.getAtmInternal());
        realAms.mOomAdjuster.mCachedAppOptimizer = spy(realAms.mOomAdjuster.mCachedAppOptimizer);
        realAms.mPackageManagerInt = mPackageManagerInt;
        realAms.mUsageStatsService = mUsageStatsManagerInt;
        realAms.mProcessesReady = true;
        realAms.mConstants.SERVICE_TIMEOUT = DEFAULT_SERVICE_TIMEOUT;
        realAms.mConstants.SERVICE_BACKGROUND_TIMEOUT = DEFAULT_SERVICE_TIMEOUT;
        mAms = spy(realAms);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        mHandlerThread.quit();
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testServiceTimeoutAndProcessKill() throws Exception {
        final int pid = 12345;
        final int uid = 10123;
        final String name = "com.example.foo";
        final ProcessRecord app = makeProcessRecord(
                pid,                   // pid
                uid,                   // uid
                uid,                   // packageUid
                null,                  // definingUid
                0,                     // connectionGroup
                PROCESS_STATE_SERVICE, // procstate
                0,                     // pss
                0,                     // rss
                name,                  // processName
                name,                  // packageName
                mAms);
        app.makeActive(mock(ApplicationThreadDeferred.class), mAms.mProcessStats);
        mProcessList.updateLruProcessLocked(app, false, null);

        final long now = SystemClock.uptimeMillis();
        final ServiceRecord sr = spy(ServiceRecord.newEmptyInstanceForTest(mAms));
        doNothing().when(sr).dump(any(), anyString());
        sr.startRequested = true;
        sr.executingStart = now;

        app.mServices.startExecutingService(sr);
        mActiveServices.scheduleServiceTimeoutLocked(app);

        verify(mActiveServices, timeout(DEFAULT_SERVICE_TIMEOUT * 2).times(1))
                .serviceTimeout(eq(app));

        clearInvocations(mActiveServices);

        app.mServices.startExecutingService(sr);
        mActiveServices.scheduleServiceTimeoutLocked(app);

        app.killLocked(TAG, 42, false);
        mAms.removeLruProcessLocked(app);

        verify(mActiveServices, after(DEFAULT_SERVICE_TIMEOUT * 4)
                .times(1)).serviceTimeout(eq(app));
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
            return mProcessList;
        }

        @Override
        public ActiveServices getActiveServices(ActivityManagerService service) {
            if (mActiveServices == null) {
                mActiveServices = spy(new ActiveServices(service));
            }
            return mActiveServices;
        }
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
