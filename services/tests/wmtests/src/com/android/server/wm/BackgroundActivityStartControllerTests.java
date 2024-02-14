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

package com.android.server.wm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.server.am.PendingIntentRecord;
import com.android.server.wm.BackgroundActivityStartController.BalVerdict;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the {@link ActivityStarter} class.
 *
 * Build/Install/Run:
 * atest WmTests:BackgroundActivityStartControllerTests
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class BackgroundActivityStartControllerTests {

    private static final int REGULAR_UID_1 = 10001;
    private static final int REGULAR_UID_2 = 10002;
    private static final int NO_UID = 01;
    private static final int REGULAR_PID_1 = 11001;
    private static final int REGULAR_PID_2 = 11002;
    private static final int NO_PID = 01;
    private static final String REGULAR_PACKAGE_1 = "package.app1";
    private static final String REGULAR_PACKAGE_2 = "package.app2";

    public @Rule MockitoRule mMockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS);

    BackgroundActivityStartController mController;
    @Mock
    ActivityMetricsLogger mActivityMetricsLogger;
    @Mock
    WindowProcessController mCallerApp;
    DeviceConfigStateHelper mDeviceConfig = new DeviceConfigStateHelper(
            DeviceConfig.NAMESPACE_WINDOW_MANAGER);
    @Mock
    ActivityRecord mResultRecord;

    @Mock
    ActivityTaskManagerService mService;
    @Mock
    Context /* mService. */ mContext;
    @Mock
    PackageManagerInternal /* mService. */ mPackageManagerInternal;
    @Mock
    RootWindowContainer /* mService. */ mRootWindowContainer;
    @Mock
    AppOpsManager mAppOpsManager;
    MirrorActiveUids mActiveUids = new MirrorActiveUids();
    WindowProcessControllerMap mProcessMap = new WindowProcessControllerMap();

    @Mock
    ActivityTaskSupervisor mSupervisor;
    @Mock
    RecentTasks /* mSupervisor. */ mRecentTasks;

    @Mock
    PendingIntentRecord mPendingIntentRecord; // just so we can pass a non-null instance

    record BalAllowedLog(String packageName, int code) {
    }

    List<String> mShownToasts = new ArrayList<>();
    List<BalAllowedLog> mBalAllowedLogs = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        // wire objects
        mService.mTaskSupervisor = mSupervisor;
        mService.mContext = mContext;
        setViaReflection(mService, "mActiveUids", mActiveUids);
        Mockito.when(mService.getPackageManagerInternalLocked()).thenReturn(
                mPackageManagerInternal);
        mService.mRootWindowContainer = mRootWindowContainer;
        Mockito.when(mService.getAppOpsManager()).thenReturn(mAppOpsManager);
        setViaReflection(mService, "mProcessMap", mProcessMap);

        //Mockito.when(mSupervisor.getBackgroundActivityLaunchController()).thenReturn(mController);
        setViaReflection(mSupervisor, "mRecentTasks", mRecentTasks);

        mController = new BackgroundActivityStartController(mService, mSupervisor) {
            @Override
            protected void showToast(String toastText) {
                mShownToasts.add(toastText);
            }

            @Override
            protected void writeBalAllowedLog(String activityName, int code,
                    BackgroundActivityStartController.BalState state) {
                mBalAllowedLogs.add(new BalAllowedLog(activityName, code));
            }
        };

        // safe defaults
        Mockito.when(mAppOpsManager.checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                anyInt(), anyString())).thenReturn(AppOpsManager.MODE_DEFAULT);
        Mockito.when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                BalVerdict.BLOCK);

    }

    private void setViaReflection(Object o, String property, Object value) {
        try {
            Field field = o.getClass().getDeclaredField(property);
            field.setAccessible(true);
            field.set(o, value);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalArgumentException("Cannot set " + property + " of " + o.getClass(), e);
        }
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testRegularActivityStart_noExemption_isBlocked() {
        // setup state

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = new Intent();
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict.getCode()).isEqualTo(BackgroundActivityStartController.BAL_BLOCK);

        assertThat(mBalAllowedLogs).isEmpty();
    }

    @Test
    public void testRegularActivityStart_allowedBLPC_isAllowed() {
        // setup state
        BalVerdict blpcVerdict = new BalVerdict(
                BackgroundActivityStartController.BAL_ALLOW_PERMISSION, true, "Allowed by BLPC");
        Mockito.when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                blpcVerdict);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = new Intent();
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(blpcVerdict);
        assertThat(mBalAllowedLogs).containsExactly(
                new BalAllowedLog("", BackgroundActivityStartController.BAL_ALLOW_PERMISSION));
    }

    @Test
    public void testRegularActivityStart_allowedByCallerBLPC_isAllowed() {
        // setup state
        BalVerdict blpcVerdict = new BalVerdict(
                BackgroundActivityStartController.BAL_ALLOW_PERMISSION, true, "Allowed by BLPC");
        Mockito.when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                blpcVerdict);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = new Intent();
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(blpcVerdict);
        assertThat(mBalAllowedLogs).containsExactly(
                new BalAllowedLog("", BackgroundActivityStartController.BAL_ALLOW_PERMISSION));
    }
}
