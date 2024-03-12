/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_EMPTY;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_UNKNOWN;
import static android.util.DebugUtils.valueToString;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.ActivityManagerInternalTest.CustomThread;
import static com.android.server.am.ActivityManagerService.Injector;
import static com.android.server.am.ProcessList.NETWORK_STATE_BLOCK;
import static com.android.server.am.ProcessList.NETWORK_STATE_NO_CHANGE;
import static com.android.server.am.ProcessList.NETWORK_STATE_UNBLOCK;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.IUidObserver;
import android.app.SyncNotedAppOp;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DeviceConfig;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.sdksandbox.flags.Flags;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.StickyBroadcast;
import com.android.server.am.ProcessList.IsolatedUidRange;
import com.android.server.am.ProcessList.IsolatedUidRangeAllocator;
import com.android.server.am.UidObserverController.ChangeRecord;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Test class for {@link ActivityManagerService}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityManagerServiceTest
 */
@Presubmit
@SmallTest
public class ActivityManagerServiceTest {
    private static final String TAG = ActivityManagerServiceTest.class.getSimpleName();

    private static final int TEST_USER = 11;

    private static final String TEST_ACTION1 = "com.android.server.am.TEST_ACTION1";
    private static final String TEST_ACTION2 = "com.android.server.am.TEST_ACTION2";
    private static final String TEST_ACTION3 = "com.android.server.am.TEST_ACTION3";

    private static final String TEST_EXTRA_KEY1 = "com.android.server.am.TEST_EXTRA_KEY1";
    private static final String TEST_EXTRA_VALUE1 = "com.android.server.am.TEST_EXTRA_VALUE1";
    private static final String PROPERTY_APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS =
            "apply_sdk_sandbox_audit_restrictions";
    private static final String PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS =
            "apply_sdk_sandbox_next_restrictions";
    private static final String APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS = ":isSdkSandboxAudit";
    private static final String APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS = ":isSdkSandboxNext";
    private static final int TEST_UID = 11111;
    private static final int USER_ID = 666;

    private static final long TEST_PROC_STATE_SEQ1 = 555;
    private static final long TEST_PROC_STATE_SEQ2 = 556;

    private static final int[] UID_RECORD_CHANGES = {
        UidRecord.CHANGE_PROCSTATE,
        UidRecord.CHANGE_GONE,
        UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE,
        UidRecord.CHANGE_IDLE,
        UidRecord.CHANGE_ACTIVE,
        UidRecord.CHANGE_CAPABILITY,
    };

    private static PackageManagerInternal sPackageManagerInternal;
    private static ProcessList.ProcessListSettingsListener sProcessListSettingsListener;

    @BeforeClass
    public static void setUpOnce() {
        sPackageManagerInternal = mock(PackageManagerInternal.class);
        doReturn(new ComponentName("", "")).when(sPackageManagerInternal)
                .getSystemUiServiceComponent();
        LocalServices.addService(PackageManagerInternal.class, sPackageManagerInternal);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
    }

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private Context mContext = getInstrumentation().getTargetContext();

    @Mock private AppOpsService mAppOpsService;
    @Mock private UserController mUserController;

    private TestInjector mInjector;
    private ActivityManagerService mAms;
    private HandlerThread mHandlerThread;
    private TestHandler mHandler;
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new TestHandler(mHandlerThread.getLooper());
        mInjector = new TestInjector(mContext);
        doAnswer(invocation -> {
            final int userId = invocation.getArgument(2);
            return userId;
        }).when(mUserController).handleIncomingUser(anyInt(), anyInt(), anyInt(), anyBoolean(),
                anyInt(), any(), any());
        doReturn(true).when(mUserController).isUserOrItsParentRunning(anyInt());
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread(),
                mUserController);
        mAms.mConstants.mNetworkAccessTimeoutMs = 2000;
        mAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mAms.mActivityTaskManager.initialize(null, null, mHandler.getLooper());
        mHandler.setRunnablesToIgnore(
                List.of(mAms.mUidObserverController.getDispatchRunnableForTest()));

        // Required for updating DeviceConfig.
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                Manifest.permission.READ_DEVICE_CONFIG,
                Manifest.permission.WRITE_DEVICE_CONFIG);
        sProcessListSettingsListener = mAms.mProcessList.getProcessListSettingsListener();
        assertThat(sProcessListSettingsListener).isNotNull();
    }

    private void mockNoteOperation() {
        SyncNotedAppOp allowed = new SyncNotedAppOp(AppOpsManager.MODE_ALLOWED,
                AppOpsManager.OP_GET_USAGE_STATS, null, mContext.getPackageName());
        when(mAppOpsService.noteOperation(eq(AppOpsManager.OP_GET_USAGE_STATS), eq(Process.myUid()),
                nullable(String.class), nullable(String.class), any(Boolean.class),
                nullable(String.class), any(Boolean.class))).thenReturn(allowed);
    }

    @After
    public void tearDown() {
        mHandlerThread.quit();
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation()
            .dropShellPermissionIdentity();
        if (sProcessListSettingsListener != null) {
            sProcessListSettingsListener.unregisterObserver();
        }
    }

    @SuppressWarnings("GuardedBy")
    @MediumTest
    @Test
    public void incrementProcStateSeqAndNotifyAppsLocked() throws Exception {

        final UidRecord uidRec = addUidRecord(TEST_UID);
        addUidRecord(TEST_UID + 1);

        // Uid state is not moving from background to foreground or vice versa.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_TOP, // prevState
                PROCESS_STATE_TOP, // curState
                NETWORK_STATE_NO_CHANGE, // expectedBlockState
                false); // expectNotify

        // Uid state is moving from foreground to background.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_FOREGROUND_SERVICE, // prevState
                PROCESS_STATE_SERVICE, // curState
                NETWORK_STATE_UNBLOCK, // expectedBlockState
                true); // expectNotify

        // Explicitly setting the seq counter for more verification.
        // @SuppressWarnings("GuardedBy")
        mAms.mProcessList.mProcStateSeqCounter = 42;

        // Uid state is not moving from background to foreground or vice versa.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_TRANSIENT_BACKGROUND, // prevState
                PROCESS_STATE_IMPORTANT_BACKGROUND, // curState
                NETWORK_STATE_NO_CHANGE, // expectedBlockState
                false); // expectNotify

        // Uid state is moving from background to foreground.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_LAST_ACTIVITY, // prevState
                PROCESS_STATE_TOP, // curState
                NETWORK_STATE_BLOCK, // expectedBlockState
                false); // expectNotify

        // verify waiting threads are not notified.
        uidRec.procStateSeqWaitingForNetwork = 0;
        // Uid state is moving from foreground to background.
        verifySeqCounterAndInteractions(uidRec,
                PROCESS_STATE_FOREGROUND_SERVICE, // prevState
                PROCESS_STATE_SERVICE, // curState
                NETWORK_STATE_UNBLOCK, // expectedBlockState
                false); // expectNotify
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void defaultSdkSandboxNextRestrictions() throws Exception {
        sProcessListSettingsListener.onPropertiesChanged(
                new DeviceConfig.Properties(
                DeviceConfig.NAMESPACE_ADSERVICES,
                Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "")));
        assertThat(
            sProcessListSettingsListener.applySdkSandboxRestrictionsNext())
            .isFalse();
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void doNotApplySdkSandboxNextRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                    DeviceConfig.NAMESPACE_ADSERVICES,
                    Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "false")));
            assertThat(
                sProcessListSettingsListener.applySdkSandboxRestrictionsNext())
                .isFalse();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec = new ProcessRecord(
                    mAms, info, TAG, Process.FIRST_SDK_SANDBOX_UID,
                    /* sdkSandboxClientPackageName= */ "com.example.client",
                    /* definingUid= */ 0, /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec)).doesNotContain(
                    APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void applySdkSandboxNextRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                    DeviceConfig.NAMESPACE_ADSERVICES,
                    Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "true")));
            assertThat(
                sProcessListSettingsListener.applySdkSandboxRestrictionsNext())
                .isTrue();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec = new ProcessRecord(
                    mAms, info, TAG, Process.FIRST_SDK_SANDBOX_UID,
                    /* sdkSandboxClientPackageName= */ "com.example.client",
                    /* definingUid= */ 0, /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec)).contains(
                    APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SELINUX_SDK_SANDBOX_AUDIT)
    public void applySdkSandboxAuditRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            Map.of(PROPERTY_APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS, "true")));
            assertThat(sProcessListSettingsListener.applySdkSandboxRestrictionsAudit()).isTrue();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec =
                    new ProcessRecord(
                            mAms,
                            info,
                            TAG,
                            Process.FIRST_SDK_SANDBOX_UID,
                            /* sdkSandboxClientPackageName= */ "com.example.client",
                            /* definingUid= */ 0,
                            /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec))
                    .contains(APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @SmallTest
    @Test
    public void applySdkSandboxNextAndAuditRestrictions() throws Exception {
        MockitoSession mockitoSession =
                ExtendedMockito.mockitoSession().spyStatic(Process.class).startMocking();
        try {
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            Map.of(PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS, "true")));
            sProcessListSettingsListener.onPropertiesChanged(
                    new DeviceConfig.Properties(
                            DeviceConfig.NAMESPACE_ADSERVICES,
                            Map.of(PROPERTY_APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS, "true")));
            assertThat(sProcessListSettingsListener.applySdkSandboxRestrictionsNext()).isTrue();
            assertThat(sProcessListSettingsListener.applySdkSandboxRestrictionsAudit()).isTrue();
            ExtendedMockito.doReturn(true).when(() -> Process.isSdkSandboxUid(anyInt()));
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = "com.android.sdksandbox";
            info.seInfo = "default:targetSdkVersion=34:complete";
            final ProcessRecord appRec =
                    new ProcessRecord(
                            mAms,
                            info,
                            TAG,
                            Process.FIRST_SDK_SANDBOX_UID,
                            /* sdkSandboxClientPackageName= */ "com.example.client",
                            /* definingUid= */ 0,
                            /* definingProcessName= */ "");
            assertThat(mAms.mProcessList.updateSeInfo(appRec))
                    .contains(APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
            assertThat(mAms.mProcessList.updateSeInfo(appRec))
                    .doesNotContain(APPLY_SDK_SANDBOX_AUDIT_RESTRICTIONS);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    private UidRecord addUidRecord(int uid) {
        final UidRecord uidRec = new UidRecord(uid, mAms);
        uidRec.procStateSeqWaitingForNetwork = 1;
        uidRec.hasInternetPermission = true;
        mAms.mProcessList.mActiveUids.put(uid, uidRec);

        ApplicationInfo info = new ApplicationInfo();
        info.packageName = "";

        final ProcessRecord appRec = new ProcessRecord(mAms, info, TAG, uid);
        final ProcessStatsService tracker = new ProcessStatsService(mAms, mContext.getCacheDir());
        appRec.makeActive(mock(IApplicationThread.class), tracker);
        mAms.mProcessList.getLruProcessesLSP().add(appRec);

        return uidRec;
    }

    @SuppressWarnings("GuardedBy")
    private void verifySeqCounterAndInteractions(UidRecord uidRec, int prevState, int curState,
            int expectedBlockState, boolean expectNotify) throws Exception {
        CustomThread thread = new CustomThread(uidRec.networkStateLock);
        thread.startAndWait("Unexpected state for " + uidRec);

        uidRec.setSetProcState(prevState);
        uidRec.setCurProcState(curState);
        final long beforeProcStateSeq = mAms.mProcessList.mProcStateSeqCounter;

        mAms.mProcessList.incrementProcStateSeqAndNotifyAppsLOSP(mAms.mProcessList.mActiveUids);

        final long afterProcStateSeq = beforeProcStateSeq
                + mAms.mProcessList.mActiveUids.size();
        assertEquals("beforeProcStateSeq=" + beforeProcStateSeq
                        + ",activeUids.size=" + mAms.mProcessList.mActiveUids.size(),
                afterProcStateSeq, mAms.mProcessList.mProcStateSeqCounter);
        assertTrue("beforeProcStateSeq=" + beforeProcStateSeq
                        + ",afterProcStateSeq=" + afterProcStateSeq
                        + ",uidCurProcStateSeq=" + uidRec.curProcStateSeq,
                uidRec.curProcStateSeq > beforeProcStateSeq
                        && uidRec.curProcStateSeq <= afterProcStateSeq);

        for (int i = mAms.mProcessList.getLruSizeLOSP() - 1; i >= 0; --i) {
            final ProcessRecord app = mAms.mProcessList.getLruProcessesLOSP().get(i);
            // AMS should notify apps only for block states other than NETWORK_STATE_NO_CHANGE.
            if (app.uid == uidRec.getUid() && expectedBlockState == NETWORK_STATE_BLOCK) {
                verify(app.getThread()).setNetworkBlockSeq(uidRec.curProcStateSeq);
            } else {
                verifyZeroInteractions(app.getThread());
            }
            Mockito.reset(app.getThread());
        }

        if (expectNotify) {
            thread.assertTerminated("Unexpected state for " + uidRec);
        } else {
            thread.assertWaiting("Unexpected state for " + uidRec);
            thread.interrupt();
        }
    }

    private void validateAppZygoteIsolatedUidRange(IsolatedUidRange uidRange) {
        assertNotNull(uidRange);
        assertTrue(uidRange.mFirstUid >= Process.FIRST_APP_ZYGOTE_ISOLATED_UID
                && uidRange.mFirstUid <= Process.LAST_APP_ZYGOTE_ISOLATED_UID);
        assertTrue(uidRange.mLastUid >= Process.FIRST_APP_ZYGOTE_ISOLATED_UID
                && uidRange.mLastUid <= Process.LAST_APP_ZYGOTE_ISOLATED_UID);
        assertTrue(uidRange.mLastUid > uidRange.mFirstUid
                && ((uidRange.mLastUid - uidRange.mFirstUid + 1)
                     == Process.NUM_UIDS_PER_APP_ZYGOTE));
    }

    private void verifyUidRangesNoOverlap(IsolatedUidRange uidRange1, IsolatedUidRange uidRange2) {
        IsolatedUidRange lowRange = uidRange1.mFirstUid <= uidRange2.mFirstUid
                ? uidRange1 : uidRange2;
        IsolatedUidRange highRange = lowRange == uidRange1  ? uidRange2 : uidRange1;

        assertTrue(highRange.mFirstUid > lowRange.mLastUid);
    }

    @Test
    public void testIsolatedUidRangeAllocator() {
        final IsolatedUidRangeAllocator allocator = mAms.mProcessList.mAppIsolatedUidRangeAllocator;

        // Create initial range
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.processName = "com.android.test.app";
        appInfo.uid = 10000;
        final IsolatedUidRange range = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo.processName, appInfo.uid);
        validateAppZygoteIsolatedUidRange(range);
        verifyIsolatedUidAllocator(range);

        // Create a second range
        ApplicationInfo appInfo2 = new ApplicationInfo();
        appInfo2.processName = "com.android.test.app2";
        appInfo2.uid = 10001;
        IsolatedUidRange range2 = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo2.processName, appInfo2.uid);
        validateAppZygoteIsolatedUidRange(range2);
        verifyIsolatedUidAllocator(range2);

        // Verify ranges don't overlap
        verifyUidRangesNoOverlap(range, range2);

        // Free range, reallocate and verify
        allocator.freeUidRangeLocked(appInfo2);
        range2 = allocator.getOrCreateIsolatedUidRangeLocked(appInfo2.processName, appInfo2.uid);
        validateAppZygoteIsolatedUidRange(range2);
        verifyUidRangesNoOverlap(range, range2);
        verifyIsolatedUidAllocator(range2);

        // Free both
        allocator.freeUidRangeLocked(appInfo);
        allocator.freeUidRangeLocked(appInfo2);

        // Verify for a secondary user
        ApplicationInfo appInfo3 = new ApplicationInfo();
        appInfo3.processName = "com.android.test.app";
        appInfo3.uid = 1010000;
        final IsolatedUidRange range3 = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo3.processName, appInfo3.uid);
        validateAppZygoteIsolatedUidRange(range3);
        verifyIsolatedUidAllocator(range3);

        allocator.freeUidRangeLocked(appInfo3);
        // Try to allocate the maximum number of UID ranges
        int maxNumUidRanges = (Process.LAST_APP_ZYGOTE_ISOLATED_UID
                - Process.FIRST_APP_ZYGOTE_ISOLATED_UID + 1) / Process.NUM_UIDS_PER_APP_ZYGOTE;
        for (int i = 0; i < maxNumUidRanges; i++) {
            appInfo = new ApplicationInfo();
            appInfo.uid = 10000 + i;
            appInfo.processName = "com.android.test.app" + Integer.toString(i);
            IsolatedUidRange uidRange = allocator.getOrCreateIsolatedUidRangeLocked(
                    appInfo.processName, appInfo.uid);
            validateAppZygoteIsolatedUidRange(uidRange);
            verifyIsolatedUidAllocator(uidRange);
        }

        // Try to allocate another one and make sure it fails
        appInfo = new ApplicationInfo();
        appInfo.uid = 9000;
        appInfo.processName = "com.android.test.app.failed";
        IsolatedUidRange failedRange = allocator.getOrCreateIsolatedUidRangeLocked(
                appInfo.processName, appInfo.uid);

        assertNull(failedRange);
    }

    public void verifyIsolatedUid(ProcessList.IsolatedUidRange range, int uid) {
        assertTrue(uid >= range.mFirstUid && uid <= range.mLastUid);
    }

    public void verifyIsolatedUidAllocator(ProcessList.IsolatedUidRange range) {
        int uid = range.allocateIsolatedUidLocked(0);
        verifyIsolatedUid(range, uid);

        int uid2 = range.allocateIsolatedUidLocked(0);
        verifyIsolatedUid(range, uid2);
        assertTrue(uid2 != uid);

        // Free both
        range.freeIsolatedUidLocked(uid);
        range.freeIsolatedUidLocked(uid2);

        // Allocate the entire range
        for (int i = 0; i < (range.mLastUid - range.mFirstUid + 1); ++i) {
            uid = range.allocateIsolatedUidLocked(0);
            verifyIsolatedUid(range, uid);
        }

        // Ensure the next one fails
        uid = range.allocateIsolatedUidLocked(0);
        assertEquals(uid, -1);
    }

    @Test
    public void testGlobalIsolatedUidAllocator() {
        final IsolatedUidRange globalUidRange = mAms.mProcessList.mGlobalIsolatedUids;
        assertEquals(globalUidRange.mFirstUid, Process.FIRST_ISOLATED_UID);
        assertEquals(globalUidRange.mLastUid, Process.LAST_ISOLATED_UID);
        verifyIsolatedUidAllocator(globalUidRange);
    }

    @Test
    public void testBlockStateForUid() {
        final UidRecord uidRec = new UidRecord(TEST_UID, mAms);
        int expectedBlockState;

        final String errorTemplate = "Block state should be %s, prevState: %s, curState: %s";
        Function<Integer, String> errorMsg = (blockState) -> {
            return String.format(errorTemplate,
                    valueToString(ActivityManagerService.class, "NETWORK_STATE_", blockState),
                    valueToString(ActivityManager.class, "PROCESS_STATE_",
                        uidRec.getSetProcState()),
                    valueToString(ActivityManager.class, "PROCESS_STATE_", uidRec.getCurProcState())
            );
        };

        // No change in uid state
        uidRec.setSetProcState(PROCESS_STATE_RECEIVER);
        uidRec.setCurProcState(PROCESS_STATE_RECEIVER);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Foreground to foreground
        uidRec.setSetProcState(PROCESS_STATE_FOREGROUND_SERVICE);
        uidRec.setCurProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Background to background
        uidRec.setSetProcState(PROCESS_STATE_CACHED_ACTIVITY);
        uidRec.setCurProcState(PROCESS_STATE_CACHED_EMPTY);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Background to background
        uidRec.setSetProcState(PROCESS_STATE_NONEXISTENT);
        uidRec.setCurProcState(PROCESS_STATE_CACHED_ACTIVITY);
        expectedBlockState = NETWORK_STATE_NO_CHANGE;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Background to foreground
        uidRec.setSetProcState(PROCESS_STATE_SERVICE);
        uidRec.setCurProcState(PROCESS_STATE_FOREGROUND_SERVICE);
        expectedBlockState = NETWORK_STATE_BLOCK;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));

        // Foreground to background
        uidRec.setSetProcState(PROCESS_STATE_TOP);
        uidRec.setCurProcState(PROCESS_STATE_LAST_ACTIVITY);
        expectedBlockState = NETWORK_STATE_UNBLOCK;
        assertEquals(errorMsg.apply(expectedBlockState),
                expectedBlockState, mAms.mProcessList.getBlockStateForUid(uidRec));
    }

    /**
     * This test verifies that process state changes are dispatched to observers based on the
     * changes they wanted to listen (this is specified when registering the observer).
     */
    @Test
    public void testDispatchUids_dispatchNeededChanges() throws RemoteException {
        mockNoteOperation();

        final int[] changesToObserve = {
            ActivityManager.UID_OBSERVER_PROCSTATE,
            ActivityManager.UID_OBSERVER_GONE,
            ActivityManager.UID_OBSERVER_IDLE,
            ActivityManager.UID_OBSERVER_ACTIVE,
            ActivityManager.UID_OBSERVER_CAPABILITY,
            ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                    | ActivityManager.UID_OBSERVER_ACTIVE | ActivityManager.UID_OBSERVER_IDLE
                    | ActivityManager.UID_OBSERVER_CAPABILITY
        };
        final IUidObserver[] observers = new IUidObserver.Stub[changesToObserve.length];
        for (int i = 0; i < observers.length; ++i) {
            observers[i] = mock(IUidObserver.Stub.class);
            when(observers[i].asBinder()).thenReturn((IBinder) observers[i]);
            mAms.registerUidObserver(observers[i], changesToObserve[i] /* which */,
                    ActivityManager.PROCESS_STATE_UNKNOWN /* cutpoint */, null /* caller */);

            // When we invoke AMS.registerUidObserver, there are some interactions with observers[i]
            // mock in RemoteCallbackList class. We don't want to test those interactions and
            // at the same time, we don't want those to interfere with verifyNoMoreInteractions.
            // So, resetting the mock here.
            Mockito.reset(observers[i]);
        }

        // Add pending uid records each corresponding to a different change type UidRecord.CHANGE_*
        final int[] changesForPendingUidRecords = UID_RECORD_CHANGES;

        final int[] procStatesForPendingUidRecords = {
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
            ActivityManager.PROCESS_STATE_NONEXISTENT,
            ActivityManager.PROCESS_STATE_CACHED_EMPTY,
            ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
            ActivityManager.PROCESS_STATE_TOP,
            ActivityManager.PROCESS_STATE_TOP,
        };
        final int[] capabilitiesForPendingUidRecords = {
            ActivityManager.PROCESS_CAPABILITY_ALL,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_NONE,
            ActivityManager.PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK,
        };
        final Map<Integer, ChangeRecord> changeItems = new HashMap<>();
        for (int i = 0; i < changesForPendingUidRecords.length; ++i) {
            final ChangeRecord pendingChange = new ChangeRecord();
            pendingChange.change = changesForPendingUidRecords[i];
            pendingChange.uid = i;
            pendingChange.procState = procStatesForPendingUidRecords[i];
            pendingChange.procStateSeq = i;
            pendingChange.capability = capabilitiesForPendingUidRecords[i];
            changeItems.put(changesForPendingUidRecords[i], pendingChange);
            addPendingUidChange(pendingChange);
        }

        mAms.mUidObserverController.dispatchUidsChanged();
        // Verify the required changes have been dispatched to observers.
        for (int i = 0; i < observers.length; ++i) {
            final int changeToObserve = changesToObserve[i];
            final IUidObserver observerToTest = observers[i];
            if ((changeToObserve & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                // Observer listens to uid idle changes, so change items corresponding to
                // UidRecord.CHANGE_IDLE or UidRecord.CHANGE_IDLE_GONE needs to be
                // delivered to this observer.
                final int[] changesToVerify = {
                    UidRecord.CHANGE_IDLE,
                    UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidIdle(changeItem.uid, changeItem.ephemeral);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                // Observer listens to uid active changes, so change items corresponding to
                // UidRecord.CHANGE_ACTIVE needs to be delivered to this observer.
                final int[] changesToVerify = { UidRecord.CHANGE_ACTIVE };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidActive(changeItem.uid);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_GONE) != 0) {
                // Observer listens to uid gone changes, so change items corresponding to
                // UidRecord.CHANGE_GONE or UidRecord.CHANGE_IDLE_GONE needs to be
                // delivered to this observer.
                final int[] changesToVerify = {
                        UidRecord.CHANGE_GONE,
                        UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE
                };
                verifyObserverReceivedChanges(observerToTest, changesToVerify, changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidGone(changeItem.uid, changeItem.ephemeral);
                        });
            }
            if ((changeToObserve & ActivityManager.UID_OBSERVER_PROCSTATE) != 0
                    || (changeToObserve & ActivityManager.UID_OBSERVER_CAPABILITY) != 0) {
                // Observer listens to uid procState changes, so change items corresponding to
                // UidRecord.CHANGE_PROCSTATE or UidRecord.CHANGE_IDLE or UidRecord.CHANGE_ACTIVE
                // needs to be delivered to this observer.
                final IntArray changesToVerify = new IntArray();
                if ((changeToObserve & ActivityManager.UID_OBSERVER_PROCSTATE) == 0) {
                    changesToVerify.add(UidRecord.CHANGE_CAPABILITY);
                } else {
                    changesToVerify.add(UidRecord.CHANGE_PROCSTATE);
                    changesToVerify.add(UidRecord.CHANGE_ACTIVE);
                    changesToVerify.add(UidRecord.CHANGE_IDLE);
                    changesToVerify.add(UidRecord.CHANGE_CAPABILITY);
                }
                verifyObserverReceivedChanges(observerToTest, changesToVerify.toArray(),
                        changeItems,
                        (observer, changeItem) -> {
                            verify(observer).onUidStateChanged(changeItem.uid,
                                    changeItem.procState, changeItem.procStateSeq,
                                    changeItem.capability);
                        });
            }
            // Verify there are no other callbacks for this observer.
            verifyNoMoreInteractions(observerToTest);
        }
    }

    @Test
    public void testBroadcastStickyIntent() {
        final Intent intent1 = new Intent(TEST_ACTION1);
        final Intent intent2 = new Intent(TEST_ACTION2)
                .putExtra(TEST_EXTRA_KEY1, TEST_EXTRA_VALUE1);
        final Intent intent3 = new Intent(TEST_ACTION3);
        final BroadcastOptions options = BroadcastOptions.makeWithDeferUntilActive(true);

        broadcastIntent(intent1, null, true);
        assertStickyBroadcasts(mAms.getStickyBroadcasts(TEST_ACTION1, TEST_USER),
                StickyBroadcast.create(intent1, false, Process.myUid(), PROCESS_STATE_UNKNOWN));
        assertNull(mAms.getStickyBroadcasts(TEST_ACTION2, TEST_USER));
        assertNull(mAms.getStickyBroadcasts(TEST_ACTION3, TEST_USER));

        broadcastIntent(intent2, options.toBundle(), true);
        assertStickyBroadcasts(mAms.getStickyBroadcasts(TEST_ACTION1, TEST_USER),
                StickyBroadcast.create(intent1, false, Process.myUid(), PROCESS_STATE_UNKNOWN));
        assertStickyBroadcasts(mAms.getStickyBroadcasts(TEST_ACTION2, TEST_USER),
                StickyBroadcast.create(intent2, true, Process.myUid(), PROCESS_STATE_UNKNOWN));
        assertNull(mAms.getStickyBroadcasts(TEST_ACTION3, TEST_USER));

        broadcastIntent(intent3, null, true);
        assertStickyBroadcasts(mAms.getStickyBroadcasts(TEST_ACTION1, TEST_USER),
                StickyBroadcast.create(intent1, false, Process.myUid(), PROCESS_STATE_UNKNOWN));
        assertStickyBroadcasts(mAms.getStickyBroadcasts(TEST_ACTION2, TEST_USER),
                StickyBroadcast.create(intent2, true, Process.myUid(), PROCESS_STATE_UNKNOWN));
        assertStickyBroadcasts(mAms.getStickyBroadcasts(TEST_ACTION3, TEST_USER),
                StickyBroadcast.create(intent3, false, Process.myUid(), PROCESS_STATE_UNKNOWN));
    }

    @SuppressWarnings("GuardedBy")
    private void broadcastIntent(Intent intent, Bundle options, boolean sticky) {
        final int res = mAms.broadcastIntentLocked(null, null, null, intent, null, null, 0,
                null, null, null, null, null, 0, options, false, sticky,
                Process.myPid(), Process.myUid(), Process.myUid(), Process.myPid(), TEST_USER);
        assertEquals(ActivityManager.BROADCAST_SUCCESS, res);
    }

    private void assertStickyBroadcasts(ArrayList<StickyBroadcast> actualBroadcasts,
            StickyBroadcast... expectedBroadcasts) {
        final String errMsg = "Expected: " + Arrays.toString(expectedBroadcasts)
                + "; Actual: " + Arrays.toString(actualBroadcasts.toArray());
        assertEquals(errMsg, expectedBroadcasts.length, actualBroadcasts.size());
        for (int i = 0; i < expectedBroadcasts.length; ++i) {
            final StickyBroadcast expected = expectedBroadcasts[i];
            final StickyBroadcast actual = actualBroadcasts.get(i);
            assertTrue(errMsg, areEquals(expected, actual));
        }
    }

    private boolean areEquals(StickyBroadcast a, StickyBroadcast b) {
        if (!Objects.equals(a.intent.getAction(), b.intent.getAction())) {
            return false;
        }
        if (!Bundle.kindofEquals(a.intent.getExtras(), b.intent.getExtras())) {
            return false;
        }
        if (a.deferUntilActive != b.deferUntilActive) {
            return false;
        }
        if (a.originalCallingUid != b.originalCallingUid) {
            return false;
        }
        return true;
    }

    private interface ObserverChangesVerifier {
        void verify(IUidObserver observer, ChangeRecord changeItem) throws RemoteException;
    }

    private void verifyObserverReceivedChanges(IUidObserver observer, int[] changesToVerify,
            Map<Integer, ChangeRecord> changeItems, ObserverChangesVerifier verifier)
            throws RemoteException {
        for (int change : changesToVerify) {
            final ChangeRecord changeItem = changeItems.get(change);
            verifier.verify(observer, changeItem);
        }
    }

    /**
     * This test verifies that process state changes are dispatched to observers only when they
     * change across the cutpoint (this is specified when registering the observer).
     */
    @Test
    public void testDispatchUidChanges_procStateCutpoint() throws RemoteException {
        mockNoteOperation();

        final IUidObserver observer = mock(IUidObserver.Stub.class);

        when(observer.asBinder()).thenReturn((IBinder) observer);
        mAms.registerUidObserver(observer, ActivityManager.UID_OBSERVER_PROCSTATE /* which */,
                ActivityManager.PROCESS_STATE_SERVICE /* cutpoint */, null /* callingPackage */);
        // When we invoke AMS.registerUidObserver, there are some interactions with observer
        // mock in RemoteCallbackList class. We don't want to test those interactions and
        // at the same time, we don't want those to interfere with verifyNoMoreInteractions.
        // So, resetting the mock here.
        Mockito.reset(observer);

        final ChangeRecord changeItem = new ChangeRecord();
        changeItem.uid = TEST_UID;
        changeItem.change = UidRecord.CHANGE_PROCSTATE;
        changeItem.procState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
        changeItem.procStateSeq = 111;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // First process state message is always delivered regardless of whether the process state
        // change is above or below the cutpoint (PROCESS_STATE_SERVICE).
        verify(observer).onUidStateChanged(TEST_UID,
                changeItem.procState, changeItem.procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_RECEIVER;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is below cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is also below cutpoint, so no callback will be invoked.
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is below cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is above cutpoint, so callback will be invoked with the
        // current process state change.
        verify(observer).onUidStateChanged(TEST_UID,
                changeItem.procState, changeItem.procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_TOP;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is above cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is also above cutpoint, so no callback will be invoked.
        verifyNoMoreInteractions(observer);

        changeItem.procState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        addPendingUidChange(changeItem);
        mAms.mUidObserverController.dispatchUidsChanged();
        // Previous process state change is above cutpoint (PROCESS_STATE_SERVICE) and
        // the current process state change is below cutpoint, so callback will be invoked with the
        // current process state change.
        verify(observer).onUidStateChanged(TEST_UID,
                changeItem.procState, changeItem.procStateSeq,
                ActivityManager.PROCESS_CAPABILITY_NONE);
        verifyNoMoreInteractions(observer);
    }

    /**
     * This test verifies that {@link UidObserverController#getValidateUidsForTest()} which is a
     * part of dumpsys is correctly updated.
     */
    @Test
    public void testDispatchUidChanges_validateUidsUpdated() {
        mockNoteOperation();

        final int[] changesForPendingItems = UID_RECORD_CHANGES;

        final int[] procStatesForPendingItems = {
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
            ActivityManager.PROCESS_STATE_CACHED_EMPTY,
            ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
            ActivityManager.PROCESS_STATE_SERVICE,
            ActivityManager.PROCESS_STATE_RECEIVER,
        };
        final ArrayList<ChangeRecord> pendingItemsForUids =
                new ArrayList<>(procStatesForPendingItems.length);
        for (int i = 0; i < procStatesForPendingItems.length; ++i) {
            final ChangeRecord item = new ChangeRecord();
            item.uid = i;
            item.change = changesForPendingItems[i];
            item.procState = procStatesForPendingItems[i];
            pendingItemsForUids.add(i, item);
        }

        // Verify that when there no observers listening to uid state changes, then there will
        // be no changes to validateUids.
        addPendingUidChanges(pendingItemsForUids);
        mAms.mUidObserverController.dispatchUidsChanged();
        assertEquals("No observers registered, so validateUids should be empty",
                0, mAms.mUidObserverController.getValidateUidsForTest().size());

        final IUidObserver observer = mock(IUidObserver.Stub.class);
        when(observer.asBinder()).thenReturn((IBinder) observer);
        mAms.registerUidObserver(observer, 0, 0, null);
        // Verify that when observers are registered, then validateUids is correctly updated.
        addPendingUidChanges(pendingItemsForUids);
        mAms.mUidObserverController.dispatchUidsChanged();
        for (int i = 0; i < pendingItemsForUids.size(); ++i) {
            final ChangeRecord item = pendingItemsForUids.get(i);
            final UidRecord validateUidRecord =
                    mAms.mUidObserverController.getValidateUidsForTest().get(item.uid);
            if ((item.change & UidRecord.CHANGE_GONE) != 0) {
                assertNull("validateUidRecord should be null since the change is either "
                        + "CHANGE_GONE or CHANGE_GONE_IDLE", validateUidRecord);
            } else {
                assertNotNull("validateUidRecord should not be null since the change is neither "
                        + "CHANGE_GONE nor CHANGE_GONE_IDLE", validateUidRecord);
                assertEquals("processState: " + item.procState + " curProcState: "
                        + validateUidRecord.getCurProcState() + " should have been equal",
                        item.procState, validateUidRecord.getCurProcState());
                assertEquals("processState: " + item.procState + " setProcState: "
                        + validateUidRecord.getCurProcState() + " should have been equal",
                        item.procState, validateUidRecord.getSetProcState());
                if (item.change == UidRecord.CHANGE_IDLE) {
                    assertTrue("UidRecord.idle should be updated to true for CHANGE_IDLE",
                            validateUidRecord.isIdle());
                } else if (item.change == UidRecord.CHANGE_ACTIVE) {
                    assertFalse("UidRecord.idle should be updated to false for CHANGE_ACTIVE",
                            validateUidRecord.isIdle());
                }
            }
        }

        // Verify that when uid state changes to CHANGE_GONE or CHANGE_GONE_IDLE, then it
        // will be removed from validateUids.
        assertNotEquals("validateUids should not be empty", 0,
                mAms.mUidObserverController.getValidateUidsForTest().size());
        for (int i = 0; i < pendingItemsForUids.size(); ++i) {
            final ChangeRecord item = pendingItemsForUids.get(i);
            // Assign CHANGE_GONE_IDLE to some items and CHANGE_GONE to the others, using even/odd
            // distribution for this assignment.
            item.change = (i % 2) == 0 ? (UidRecord.CHANGE_GONE | UidRecord.CHANGE_IDLE)
                    : UidRecord.CHANGE_GONE;
        }
        addPendingUidChanges(pendingItemsForUids);
        mAms.mUidObserverController.dispatchUidsChanged();
        assertEquals("validateUids should be empty, size="
                + mAms.mUidObserverController.getValidateUidsForTest().size(),
                        0, mAms.mUidObserverController.getValidateUidsForTest().size());
    }

    @Test
    public void testEnqueueUidChangeLocked_nullUidRecord() {
        // Use "null" uidRecord to make sure there is no crash.
        mAms.enqueueUidChangeLocked(null, TEST_UID, UidRecord.CHANGE_ACTIVE);
    }

    @MediumTest
    @Test
    public void testEnqueueUidChangeLocked_dispatchUidsChanged() {
        final UidRecord uidRecord = new UidRecord(TEST_UID, mAms);
        final int expectedProcState = PROCESS_STATE_SERVICE;
        uidRecord.setSetProcState(expectedProcState);
        uidRecord.curProcStateSeq = TEST_PROC_STATE_SEQ1;

        // Test with no pending uid records.
        for (int i = 0; i < UID_RECORD_CHANGES.length; ++i) {
            final int changeToDispatch = UID_RECORD_CHANGES[i];

            // Reset the current state
            mHandler.reset();
            clearPendingUidChanges();
            uidRecord.pendingChange.isPending = false;

            mAms.enqueueUidChangeLocked(uidRecord, -1, changeToDispatch);

            // Verify that pendingChange is updated correctly.
            final ChangeRecord pendingChange = uidRecord.pendingChange;
            assertTrue(pendingChange.isPending);
            assertEquals(TEST_UID, pendingChange.uid);
            assertEquals(expectedProcState, pendingChange.procState);
            assertEquals(TEST_PROC_STATE_SEQ1, pendingChange.procStateSeq);

            // TODO: Verify that DISPATCH_UIDS_CHANGED_UI_MSG is posted to handler.
        }
    }

    @MediumTest
    @Test
    public void testWaitForNetworkStateUpdate() throws Exception {
        // Check there is no crash when there is no UidRecord for myUid
        mAms.waitForNetworkStateUpdate(TEST_PROC_STATE_SEQ1);

        // Verify there is not waiting when the procStateSeq in the request already has
        // an updated network state.
        verifyWaitingForNetworkStateUpdate(
                TEST_PROC_STATE_SEQ1, // curProcStateSeq
                TEST_PROC_STATE_SEQ1, // lastNetworkUpdatedProcStateSeq
                TEST_PROC_STATE_SEQ1, // procStateSeqToWait
                false); // expectWait

        // Verify waiting for network works
        verifyWaitingForNetworkStateUpdate(
                TEST_PROC_STATE_SEQ1, // curProcStateSeq
                TEST_PROC_STATE_SEQ1 - 1, // lastNetworkUpdatedProcStateSeq
                TEST_PROC_STATE_SEQ1, // procStateSeqToWait
                true); // expectWait
    }

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{4, 8, 15, 16, 23, 42};

        int [] displayIds = mAms.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingVisibleBackgroundUsers()")
                .that(displayIds).asList().containsExactly(4, 8, 15, 16, 23, 42);
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_invalidDisplay() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{4, 8, 15, 16, 23, 42};

        assertThrows(IllegalArgumentException.class,
                () -> mAms.startUserInBackgroundVisibleOnDisplay(USER_ID, 666,
                        /* unlockProgressListener= */ null));

        assertWithMessage("UserController.startUserOnSecondaryDisplay() calls")
                .that(mInjector.usersStartedOnSecondaryDisplays).isEmpty();
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_validDisplay_failed() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{ 4, 8, 15, 16, 23, 42 };
        mInjector.returnValueForstartUserOnSecondaryDisplay = false;

        boolean started = mAms.startUserInBackgroundVisibleOnDisplay(USER_ID, 42,
                /* unlockProgressListener= */ null);
        Log.v(TAG, "Started: " + started);

        assertWithMessage("mAms.startUserInBackgroundOnDisplay(%s, 42)", USER_ID)
                .that(started).isFalse();
        assertWithMessage("UserController.startUserOnSecondaryDisplay() calls")
                .that(mInjector.usersStartedOnSecondaryDisplays)
                .containsExactly(new Pair<>(USER_ID, 42));
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_validDisplay_success() {
        mInjector.secondaryDisplayIdsForStartingBackgroundUsers = new int[]{ 4, 8, 15, 16, 23, 42 };
        mInjector.returnValueForstartUserOnSecondaryDisplay = true;

        boolean started = mAms.startUserInBackgroundVisibleOnDisplay(USER_ID, 42,
                /* unlockProgressListener= */ null);
        Log.v(TAG, "Started: " + started);

        assertWithMessage("mAms.startUserInBackgroundOnDisplay(%s, 42)", USER_ID)
                .that(started).isTrue();
        assertWithMessage("UserController.startUserOnDisplay() calls")
                .that(mInjector.usersStartedOnSecondaryDisplays)
                .containsExactly(new Pair<>(USER_ID, 42));
    }

    private void verifyWaitingForNetworkStateUpdate(long curProcStateSeq,
            long lastNetworkUpdatedProcStateSeq,
            final long procStateSeqToWait, boolean expectWait) throws Exception {
        final UidRecord record = new UidRecord(Process.myUid(), mAms);
        record.curProcStateSeq = curProcStateSeq;
        record.lastNetworkUpdatedProcStateSeq = lastNetworkUpdatedProcStateSeq;
        mAms.mProcessList.mActiveUids.put(Process.myUid(), record);

        CustomThread thread = new CustomThread(record.networkStateLock, new Runnable() {
            @Override
            public void run() {
                mAms.waitForNetworkStateUpdate(procStateSeqToWait);
            }
        });
        final String errMsg = "Unexpected state for " + record;
        if (expectWait) {
            thread.startAndWait(errMsg, true);
            thread.assertTimedWaiting(errMsg);
            synchronized (record.networkStateLock) {
                record.networkStateLock.notifyAll();
            }
            thread.assertTerminated(errMsg);
            assertTrue(thread.mNotified);
            assertEquals(0, record.procStateSeqWaitingForNetwork);
        } else {
            thread.start();
            thread.assertTerminated(errMsg);
        }

        mAms.mProcessList.mActiveUids.clear();
    }

    private void addPendingUidChange(ChangeRecord record) {
        mAms.mUidObserverController.getPendingUidChangesForTest().add(record);
    }

    private void addPendingUidChanges(ArrayList<ChangeRecord> changes) {
        final ArrayList<ChangeRecord> pendingChanges =
                mAms.mUidObserverController.getPendingUidChangesForTest();
        for (int i = 0; i < changes.size(); ++i) {
            final ChangeRecord record = changes.get(i);
            pendingChanges.add(record);
        }
    }

    private void clearPendingUidChanges() {
        mAms.mUidObserverController.getPendingUidChangesForTest().clear();
    }

    private static class TestHandler extends Handler {
        private static final long WAIT_FOR_MSG_TIMEOUT_MS = 4000; // 4 sec
        private static final long WAIT_FOR_MSG_INTERVAL_MS = 400; // 0.4 sec

        private final Set<Integer> mMsgsHandled = new HashSet<>();
        private final List<Runnable> mRunnablesToIgnore = new ArrayList<>();

        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void dispatchMessage(Message msg) {
            if (msg.getCallback() != null && mRunnablesToIgnore.contains(msg.getCallback())) {
                return;
            }
            super.dispatchMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            mMsgsHandled.add(msg.what);
        }

        public void waitForMessage(int msg) {
            final long endTime = System.currentTimeMillis() + WAIT_FOR_MSG_TIMEOUT_MS;
            while (!mMsgsHandled.contains(msg) && System.currentTimeMillis() < endTime) {
                SystemClock.sleep(WAIT_FOR_MSG_INTERVAL_MS);
            }
            if (!mMsgsHandled.contains(msg)) {
                fail("Timed out waiting for the message to be handled, msg: " + msg);
            }
        }

        public void setRunnablesToIgnore(List<Runnable> runnables) {
            mRunnablesToIgnore.clear();
            mRunnablesToIgnore.addAll(runnables);
        }

        public void reset() {
            mMsgsHandled.clear();
        }
    }

    private class TestInjector extends Injector {
        public boolean restricted = true;
        public int[] secondaryDisplayIdsForStartingBackgroundUsers;

        public boolean returnValueForstartUserOnSecondaryDisplay;
        public List<Pair<Integer, Integer>> usersStartedOnSecondaryDisplays = new ArrayList<>();

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
        public boolean isNetworkRestrictedForUid(int uid) {
            return restricted;
        }

        @Override
        public int[] getDisplayIdsForStartingVisibleBackgroundUsers() {
            return secondaryDisplayIdsForStartingBackgroundUsers;
        }

        @Override
        public boolean startUserInBackgroundVisibleOnDisplay(int userId, int displayId,
                IProgressListener unlockProgressListener) {
            usersStartedOnSecondaryDisplays.add(new Pair<>(userId, displayId));
            return returnValueForstartUserOnSecondaryDisplay;
        }
    }

    // TODO: [b/302724778] Remove manual JNI load
    static {
        System.loadLibrary("mockingservicestestjni");
    }
}
