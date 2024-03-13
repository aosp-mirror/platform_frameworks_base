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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.annotation.NonNull;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.TestLooperManager;
import android.os.UserHandle;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.util.SparseArray;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.util.FrameworkStatsLog;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.AlarmManagerInternal;
import com.android.server.DropBoxManagerInternal;
import com.android.server.LocalServices;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseBroadcastQueueTest {

    static final int USER_GUEST = 11;

    static final String PACKAGE_ANDROID = "android";
    static final String PACKAGE_PHONE = "com.android.phone";
    static final String PACKAGE_RED = "com.example.red";
    static final String PACKAGE_GREEN = "com.example.green";
    static final String PACKAGE_BLUE = "com.example.blue";
    static final String PACKAGE_YELLOW = "com.example.yellow";
    static final String PACKAGE_ORANGE = "com.example.orange";

    static final String PROCESS_SYSTEM = "system";

    static final String CLASS_RED = "com.example.red.Red";
    static final String CLASS_GREEN = "com.example.green.Green";
    static final String CLASS_BLUE = "com.example.blue.Blue";
    static final String CLASS_YELLOW = "com.example.yellow.Yellow";
    static final String CLASS_ORANGE = "com.example.orange.Orange";

    static final BroadcastProcessQueue.BroadcastPredicate BROADCAST_PREDICATE_ANY =
            (r, i) -> true;

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .spyStatic(FrameworkStatsLog.class)
            .spyStatic(ProcessList.class)
            .build();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    AppOpsService mAppOpsService;
    @Mock
    PackageManagerInternal mPackageManagerInt;
    @Mock
    UsageStatsManagerInternal mUsageStatsManagerInt;
    @Mock
    DropBoxManagerInternal mDropBoxManagerInt;
    @Mock
    AlarmManagerInternal mAlarmManagerInt;
    @Mock
    ProcessList mProcessList;

    Context mContext;
    ActivityManagerService mAms;
    BroadcastConstants mConstants;
    BroadcastSkipPolicy mSkipPolicy;
    HandlerThread mHandlerThread;
    TestLooperManager mLooper;
    AtomicInteger mNextPid;
    BroadcastHistory mEmptyHistory;

    /**
     * Map from PID to registered registered runtime receivers.
     */
    SparseArray<ReceiverList> mRegisteredReceivers = new SparseArray<>();

    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mHandlerThread = new HandlerThread(getTag());
        mHandlerThread.start();
        // Pause all event processing until a test chooses to resume
        mLooper = Objects.requireNonNull(InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mHandlerThread.getLooper()));
        mNextPid = new AtomicInteger(100);

        mConstants = new BroadcastConstants(Settings.Global.BROADCAST_FG_CONSTANTS);
        mEmptyHistory = new BroadcastHistory(mConstants) {
            public void addBroadcastToHistoryLocked(BroadcastRecord original) {
                // Ignored
            }
        };

        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.addService(DropBoxManagerInternal.class, mDropBoxManagerInt);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
        LocalServices.removeServiceForTest(AlarmManagerInternal.class);
        LocalServices.addService(AlarmManagerInternal.class, mAlarmManagerInt);
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doNothing().when(mPackageManagerInt).notifyComponentUsed(any(), anyInt(), any(), any());
        doAnswer((invocation) -> {
            return getUidForPackage(invocation.getArgument(0));
        }).when(mPackageManagerInt).getPackageUid(any(), anyLong(), eq(UserHandle.USER_SYSTEM));

        final ActivityManagerService realAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        realAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        realAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        realAms.mAtmInternal = spy(realAms.mActivityTaskManager.getAtmInternal());
        realAms.mOomAdjuster.mCachedAppOptimizer = Mockito.mock(CachedAppOptimizer.class);
        realAms.mOomAdjuster = spy(realAms.mOomAdjuster);
        ExtendedMockito.doNothing().when(() -> ProcessList.setOomAdj(anyInt(), anyInt(), anyInt()));
        realAms.mPackageManagerInt = mPackageManagerInt;
        realAms.mUsageStatsService = mUsageStatsManagerInt;
        realAms.mProcessesReady = true;
        mAms = spy(realAms);

        mSkipPolicy = spy(new BroadcastSkipPolicy(mAms));
        doReturn(null).when(mSkipPolicy).shouldSkipMessage(any(), any());
        doReturn(false).when(mSkipPolicy).disallowBackgroundStart(any());
    }

    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
    }

    static int getUidForPackage(@NonNull String packageName) {
        switch (packageName) {
            case PACKAGE_ANDROID: return android.os.Process.SYSTEM_UID;
            case PACKAGE_PHONE: return android.os.Process.PHONE_UID;
            case PACKAGE_RED: return android.os.Process.FIRST_APPLICATION_UID + 1;
            case PACKAGE_GREEN: return android.os.Process.FIRST_APPLICATION_UID + 2;
            case PACKAGE_BLUE: return android.os.Process.FIRST_APPLICATION_UID + 3;
            case PACKAGE_YELLOW: return android.os.Process.FIRST_APPLICATION_UID + 4;
            case PACKAGE_ORANGE: return android.os.Process.FIRST_APPLICATION_UID + 5;
            default: throw new IllegalArgumentException();
        }
    }

    static int getUidForPackage(@NonNull String packageName, int userId) {
        return UserHandle.getUid(userId, getUidForPackage(packageName));
    }

    private class TestInjector extends ActivityManagerService.Injector {
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
        public BroadcastQueue getBroadcastQueue(ActivityManagerService service) {
            return null;
        }
    }

    abstract String getTag();

    static ApplicationInfo makeApplicationInfo(String packageName) {
        return makeApplicationInfo(packageName, packageName, UserHandle.USER_SYSTEM);
    }

    static ApplicationInfo makeApplicationInfo(String packageName, String processName, int userId) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.processName = processName;
        ai.uid = getUidForPackage(packageName, userId);
        return ai;
    }

    static ResolveInfo withPriority(ResolveInfo info, int priority) {
        info.priority = priority;
        return info;
    }

    static BroadcastFilter withPriority(BroadcastFilter filter, int priority) {
        filter.setPriority(priority);
        return filter;
    }

    static ResolveInfo makeManifestReceiver(String packageName, String name) {
        return makeManifestReceiver(packageName, name, UserHandle.USER_SYSTEM);
    }

    static ResolveInfo makeManifestReceiver(String packageName, String name, int userId) {
        return makeManifestReceiver(packageName, packageName, name, userId);
    }

    static ResolveInfo makeManifestReceiver(String packageName, String processName,
            String name, int userId) {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = packageName;
        ri.activityInfo.processName = processName;
        ri.activityInfo.name = name;
        ri.activityInfo.applicationInfo = makeApplicationInfo(packageName, processName, userId);
        return ri;
    }

    BroadcastFilter makeRegisteredReceiver(ProcessRecord app) {
        return makeRegisteredReceiver(app, 0);
    }

    BroadcastFilter makeRegisteredReceiver(ProcessRecord app, int priority) {
        final ReceiverList receiverList = mRegisteredReceivers.get(app.getPid());
        return makeRegisteredReceiver(receiverList, priority);
    }

    static BroadcastFilter makeRegisteredReceiver(ReceiverList receiverList, int priority) {
        final IntentFilter filter = new IntentFilter();
        filter.setPriority(priority);
        final BroadcastFilter res = new BroadcastFilter(filter, receiverList,
                receiverList.app.info.packageName, null, null, null, receiverList.uid,
                receiverList.userId, false, false, true);
        receiverList.add(res);
        return res;
    }

    void setProcessFreezable(ProcessRecord app, boolean pendingFreeze, boolean frozen) {
        app.mOptRecord.setPendingFreeze(pendingFreeze);
        app.mOptRecord.setFrozen(frozen);
    }
}
