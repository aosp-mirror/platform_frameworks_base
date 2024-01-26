/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_HOME;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;
import static android.content.Context.BIND_WAIVE_PRIORITY;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
import static android.os.UserHandle.USER_SYSTEM;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;

import static com.android.server.am.ProcessList.HOME_APP_ADJ;
import static com.android.server.am.ProcessList.PERCEPTIBLE_APP_ADJ;
import static com.android.server.am.ProcessList.SERVICE_ADJ;
import static com.android.server.am.ProcessList.CACHED_APP_MIN_ADJ;

import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.DropBoxManagerInternal;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.am.ApplicationExitInfoTest.ServiceThreadRule;
import com.android.server.appop.AppOpsService;
import com.android.server.firewall.IntentFirewall;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowProcessController;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/**
 * Test class for the service timeout.
 *
 * Build/Install/Run:
 *  atest ServiceBindingOomAdjPolicyTest
 */
@Presubmit
public final class ServiceBindingOomAdjPolicyTest {
    private static final String TAG = ServiceBindingOomAdjPolicyTest.class.getSimpleName();

    private static final String TEST_APP1_NAME = "com.example.foo";
    private static final String TEST_SERVICE1_NAME = "com.example.foo.Foobar";
    private static final int TEST_APP1_UID = 10123;
    private static final int TEST_APP1_PID = 12345;

    private static final String TEST_APP2_NAME = "com.example.bar";
    private static final String TEST_SERVICE2_NAME = "com.example.bar.Buz";
    private static final int TEST_APP2_UID = 10124;
    private static final int TEST_APP2_PID = 12346;

    @Rule
    public final ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

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
    private AppErrors mAppErrors;
    @Mock
    private IntentFirewall mIntentFirewall;

    private ActivityManagerService mAms;
    private ProcessList mProcessList;
    private ActiveServices mActiveServices;

    private int mCurrentCallingUid;
    private int mCurrentCallingPid;

    /** Run at the test class initialization */
    @BeforeClass
    public static void setUpOnce() {
        System.setProperty("dexmaker.share_classloader", "true");
    }

    @SuppressWarnings("GuardedBy")
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        final ProcessList realProcessList = new ProcessList();
        mProcessList = spy(realProcessList);

        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.addService(DropBoxManagerInternal.class, mDropBoxManagerInt);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();

        final ActivityManagerService realAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        final ActivityTaskManagerService realAtm = new ActivityTaskManagerService(mContext);
        realAtm.initialize(null, null, mContext.getMainLooper());
        realAms.mActivityTaskManager = spy(realAtm);
        realAms.mAtmInternal = spy(realAms.mActivityTaskManager.getAtmInternal());
        realAms.mOomAdjuster = spy(realAms.mOomAdjuster);
        realAms.mOomAdjuster.mCachedAppOptimizer = spy(realAms.mOomAdjuster.mCachedAppOptimizer);
        realAms.mPackageManagerInt = mPackageManagerInt;
        realAms.mUsageStatsService = mUsageStatsManagerInt;
        realAms.mAppProfiler = spy(realAms.mAppProfiler);
        realAms.mProcessesReady = true;
        mAms = spy(realAms);
        realProcessList.mService = mAms;

        doReturn(false).when(mPackageManagerInt).filterAppAccess(anyString(), anyInt(), anyInt());
        doReturn(true).when(mIntentFirewall).checkService(any(), any(), anyInt(), anyInt(), any(),
                any());
        doReturn(false).when(mAms.mAtmInternal).hasSystemAlertWindowPermission(anyInt(), anyInt(),
                any());
        doReturn(true).when(mAms.mOomAdjuster.mCachedAppOptimizer).useFreezer();
        doNothing().when(mAms.mOomAdjuster.mCachedAppOptimizer).freezeAppAsyncInternalLSP(
                any(), anyLong(), anyBoolean());
        doReturn(false).when(mAms.mAppProfiler).updateLowMemStateLSP(anyInt(), anyInt(),
                anyInt(), anyLong());

        mCurrentCallingUid = TEST_APP1_UID;
        mCurrentCallingPid = TEST_APP1_PID;
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        mHandlerThread.quit();
    }

    @Test
    public void testServiceSelfBindingOomAdj() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be 0 oom adj updates.
        performTestServiceSelfBindingOomAdj(never(), never());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update.
        performTestServiceSelfBindingOomAdj(atLeastOnce(), atLeastOnce());
    }

    @SuppressWarnings("GuardedBy")
    private void performTestServiceSelfBindingOomAdj(VerificationMode bindMode,
            VerificationMode unbindMode) throws Exception {
        final ProcessRecord app = addProcessRecord(
                TEST_APP1_PID,           // pid
                TEST_APP1_UID,           // uid
                PROCESS_STATE_SERVICE,   // procstate
                SERVICE_ADJ,             // adj
                PROCESS_CAPABILITY_NONE, // capabilities
                TEST_APP1_NAME           // packageName
        );
        final Intent serviceIntent = createServiceIntent(TEST_APP1_NAME, TEST_SERVICE1_NAME,
                TEST_APP1_UID);
        final IServiceConnection serviceConnection = mock(IServiceConnection.class);

        // Make a self binding.
        assertNotEquals(0, mAms.bindService(
                app.getThread(),         // caller
                null,                    // token
                serviceIntent,           // service
                null,                    // resolveType
                serviceConnection,       // connection
                BIND_AUTO_CREATE,        // flags
                TEST_APP1_NAME,          // callingPackage
                USER_SYSTEM              // userId
        ));

        verify(mAms.mOomAdjuster, bindMode).updateOomAdjPendingTargetsLocked(anyInt());
        clearInvocations(mAms.mOomAdjuster);

        // Unbind the service.
        mAms.unbindService(serviceConnection);

        verify(mAms.mOomAdjuster, unbindMode).updateOomAdjPendingTargetsLocked(anyInt());
        clearInvocations(mAms.mOomAdjuster);

        removeProcessRecord(app);
    }

    @Test
    public void testServiceDistinctBindingOomAdjMoreImportant() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        // because the client is more important.
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHasForegroundServices,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_HOME,
                HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHomeProcess,
                BIND_AUTO_CREATE,
                atLeastOnce(), atLeastOnce());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        // because the client is more important.
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHasForegroundServices,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_HOME,
                HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHomeProcess,
                BIND_AUTO_CREATE,
                atLeastOnce(), atLeastOnce());
    }

    @Test
    public void testServiceDistinctBindingOomAdjLessImportant() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be 0 oom adj update
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE,
                never(), never());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE,
                atLeastOnce(), atLeastOnce());
    }

    @Test
    public void testServiceDistinctBindingOomAdjWaivePriority() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be 0 oom adj update for binding
        // because we're using the BIND_WAIVE_PRIORITY;
        // but for the unbinding, because client is better than service, we can't skip it safely.
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHasForegroundServices,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_HOME,
                HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHomeProcess,
                BIND_AUTO_CREATE | BIND_WAIVE_PRIORITY,
                never(), atLeastOnce());

        // Verify that there should be 0 oom adj update
        // because we're using the BIND_WAIVE_PRIORITY;
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE | BIND_WAIVE_PRIORITY,
                never(), never());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        // because the client is more important.
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_FOREGROUND_SERVICE, PERCEPTIBLE_APP_ADJ,
                PROCESS_CAPABILITY_NONE, TEST_APP1_NAME,
                this::setHasForegroundServices,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_HOME,
                HOME_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHomeProcess,
                BIND_AUTO_CREATE,
                atLeastOnce(), atLeastOnce());
    }

    @Test
    public void testServiceDistinctBindingOomAdjNoIncludeCapabilities() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be 0 oom adj update
        // because we didn't specify the "BIND_INCLUDE_CAPABILITIES"
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ,
                PROCESS_CAPABILITY_FOREGROUND_MICROPHONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE,
                never(), never());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ,
                PROCESS_CAPABILITY_FOREGROUND_MICROPHONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE,
                atLeastOnce(), atLeastOnce());
    }

    @Test
    public void testServiceDistinctBindingOomAdjWithIncludeCapabilities() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        // because we use the "BIND_INCLUDE_CAPABILITIES"
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ,
                PROCESS_CAPABILITY_FOREGROUND_MICROPHONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES,
                atLeastOnce(), atLeastOnce());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_HOME, HOME_APP_ADJ,
                PROCESS_CAPABILITY_FOREGROUND_MICROPHONE, TEST_APP1_NAME,
                this::setHomeProcess,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE | BIND_INCLUDE_CAPABILITIES,
                atLeastOnce(), atLeastOnce());
    }

    @Test
    public void testServiceDistinctBindingOomAdjFreezeCaller() throws Exception {
        // Enable the flags.
        mSetFlagsRule.enableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be 0 oom adj update
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_CACHED_EMPTY, CACHED_APP_MIN_ADJ, PROCESS_CAPABILITY_NONE,
                TEST_APP1_NAME, null,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE,
                never(), never());

        // Disable the flags.
        mSetFlagsRule.disableFlags(Flags.FLAG_SERVICE_BINDING_OOM_ADJ_POLICY);

        // Verify that there should be at least 1 oom adj update
        performTestServiceDistinctBindingOomAdj(TEST_APP1_PID, TEST_APP1_UID,
                PROCESS_STATE_CACHED_EMPTY, CACHED_APP_MIN_ADJ, PROCESS_CAPABILITY_NONE,
                TEST_APP1_NAME, null,
                TEST_APP2_PID, TEST_APP2_UID, PROCESS_STATE_FOREGROUND_SERVICE,
                PERCEPTIBLE_APP_ADJ, PROCESS_CAPABILITY_NONE, TEST_APP2_NAME, TEST_SERVICE2_NAME,
                this::setHasForegroundServices,
                BIND_AUTO_CREATE,
                atLeastOnce(), atLeastOnce());
    }

    @SuppressWarnings("GuardedBy")
    private void performTestServiceDistinctBindingOomAdj(int clientPid, int clientUid,
            int clientProcState, int clientAdj, int clientCap, String clientPackageName,
            Consumer<ProcessRecord> clientAppFixer,
            int servicePid, int serviceUid, int serviceProcState, int serviceAdj,
            int serviceCap, String servicePackageName, String serviceName,
            Consumer<ProcessRecord> serviceAppFixer, int bindingFlags,
            VerificationMode bindMode, VerificationMode unbindMode) throws Exception {
        final ProcessRecord clientApp = addProcessRecord(
                clientPid,
                clientUid,
                clientProcState,
                clientAdj,
                clientCap,
                clientPackageName
        );
        final ProcessRecord serviceApp = addProcessRecord(
                servicePid,
                serviceUid,
                serviceProcState,
                serviceAdj,
                serviceCap,
                servicePackageName
        );
        final Intent serviceIntent = createServiceIntent(servicePackageName, serviceName,
                serviceUid);
        final IServiceConnection serviceConnection = mock(IServiceConnection.class);
        if (clientAppFixer != null) clientAppFixer.accept(clientApp);
        if (serviceAppFixer != null) serviceAppFixer.accept(serviceApp);

        // Make a self binding.
        assertNotEquals(0, mAms.bindService(
                clientApp.getThread(), // caller
                null,                  // token
                serviceIntent,         // service
                null,                  // resolveType
                serviceConnection,     // connection
                bindingFlags,          // flags
                clientPackageName,     // callingPackage
                USER_SYSTEM            // userId
        ));

        verify(mAms.mOomAdjuster, bindMode).updateOomAdjPendingTargetsLocked(anyInt());
        clearInvocations(mAms.mOomAdjuster);

        if (clientApp.isFreezable()) {
            verify(mAms.mOomAdjuster.mCachedAppOptimizer,
                    times(Flags.serviceBindingOomAdjPolicy() ? 1 : 0))
                    .freezeAppAsyncInternalLSP(eq(clientApp), eq(0L), anyBoolean());
            clearInvocations(mAms.mOomAdjuster.mCachedAppOptimizer);
        }

        // Unbind the service.
        mAms.unbindService(serviceConnection);

        verify(mAms.mOomAdjuster, unbindMode).updateOomAdjPendingTargetsLocked(anyInt());
        clearInvocations(mAms.mOomAdjuster);

        removeProcessRecord(clientApp);
        removeProcessRecord(serviceApp);
    }

    private void setHasForegroundServices(ProcessRecord app) {
        app.mServices.setHasForegroundServices(true,
                FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, false);
    }

    private void setHomeProcess(ProcessRecord app) {
        final WindowProcessController wpc = app.getWindowProcessController();
        doReturn(true).when(wpc).isHomeProcess();
    }

    @SuppressWarnings("GuardedBy")
    private ProcessRecord addProcessRecord(int pid, int uid, int procState, int adj, int cap,
                String packageName) {
        final IApplicationThread appThread = mock(IApplicationThread.class);
        final IBinder threadBinder = mock(IBinder.class);
        final ProcessRecord app = makeProcessRecord(pid, uid, uid, null, 0,
                procState, adj, cap, 0L, 0L, packageName, packageName, mAms);

        app.makeActive(appThread, mAms.mProcessStats);
        doReturn(threadBinder).when(appThread).asBinder();
        mProcessList.addProcessNameLocked(app);
        mProcessList.updateLruProcessLocked(app, false, null);

        setFieldValue(ProcessRecord.class, app, "mWindowProcessController",
                mock(WindowProcessController.class));

        doReturn(app.getSetCapability()).when(mAms.mOomAdjuster).getDefaultCapability(
                eq(app), anyInt());

        return app;
    }

    @SuppressWarnings("GuardedBy")
    private Intent createServiceIntent(String packageName, String serviceName, int serviceUid) {
        final ComponentName compName = new ComponentName(packageName, serviceName);
        final Intent serviceIntent = new Intent().setComponent(compName);
        final ResolveInfo rInfo = new ResolveInfo();
        rInfo.serviceInfo = makeServiceInfo(compName.getClassName(), compName.getPackageName(),
                serviceUid);
        doReturn(rInfo).when(mPackageManagerInt).resolveService(any(Intent.class), any(),
                anyLong(), anyInt(), anyInt());

        return serviceIntent;
    }

    @SuppressWarnings("GuardedBy")
    private void removeProcessRecord(ProcessRecord app) {
        app.setKilled(true);
        mProcessList.removeProcessNameLocked(app.processName, app.uid);
        mProcessList.removeLruProcessLocked(app);
    }

    @SuppressWarnings("GuardedBy")
    private ProcessRecord makeProcessRecord(int pid, int uid, int packageUid, Integer definingUid,
            int connectionGroup, int procState, int adj, int cap, long pss, long rss,
            String processName, String packageName, ActivityManagerService ams) {
        final ProcessRecord app = ApplicationExitInfoTest.makeProcessRecord(pid, uid, packageUid,
                definingUid, connectionGroup, procState, pss, rss, processName, packageName, ams);
        app.mState.setCurProcState(procState);
        app.mState.setSetProcState(procState);
        app.mState.setCurAdj(adj);
        app.mState.setSetAdj(adj);
        app.mState.setCurCapability(cap);
        app.mState.setSetCapability(cap);
        app.mState.setCached(procState >= PROCESS_STATE_LAST_ACTIVITY || adj >= CACHED_APP_MIN_ADJ);
        return app;
    }

    @SuppressWarnings("GuardedBy")
    private ServiceInfo makeServiceInfo(String serviceName, String packageName, int packageUid) {
        final ServiceInfo sInfo = new ServiceInfo();
        sInfo.name = serviceName;
        sInfo.processName = packageName;
        sInfo.packageName = packageName;
        sInfo.applicationInfo = new ApplicationInfo();
        sInfo.applicationInfo.uid = packageUid;
        sInfo.applicationInfo.packageName = packageName;
        sInfo.exported = true;
        return sInfo;
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

        @Override
        public int getCallingUid() {
            return mCurrentCallingUid;
        }

        @Override
        public int getCallingPid() {
            return mCurrentCallingPid;
        }

        @Override
        public long clearCallingIdentity() {
            return (((long) mCurrentCallingUid) << 32) | mCurrentCallingPid;
        }

        @Override
        public void restoreCallingIdentity(long ident) {
        }

        @Override
        public AppErrors getAppErrors() {
            return mAppErrors;
        }

        @Override
        public IntentFirewall getIntentFirewall() {
            return mIntentFirewall;
        }
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
