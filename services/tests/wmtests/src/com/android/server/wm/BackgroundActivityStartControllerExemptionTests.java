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

import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_ALLOWLISTED_COMPONENT;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_FOREGROUND;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_PERMISSION;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_SAW_PERMISSION;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_VISIBLE_WINDOW;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.modules.utils.testing.ExtendedMockitoRule;
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
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for the {@link BackgroundActivityStartController} class.
 *
 * Build/Install/Run:
 * atest WmTests:BackgroundActivityStartControllerExemptionTests
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class BackgroundActivityStartControllerExemptionTests {

    private static final int REGULAR_UID_1 = 10100;
    private static final int REGULAR_UID_2 = 10200;
    private static final int NO_UID = -1;
    private static final int REGULAR_PID_1 = 11100;
    private static final int REGULAR_PID_1_1 = 11101;
    private static final int REGULAR_PID_2 = 11200;
    private static final int NO_PID = -1;
    private static final String REGULAR_PACKAGE_1 = "package.app1";
    private static final String REGULAR_PACKAGE_2 = "package.app2";

    private static final Intent TEST_INTENT = new Intent()
            .setComponent(new ComponentName("package.app3", "someClass"));

    @Rule
    public final ExtendedMockitoRule extendedMockitoRule =
            new ExtendedMockitoRule.Builder(this).setStrictness(Strictness.LENIENT).build();

    TestableBackgroundActivityStartController mController;
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

    ActivityOptions mCheckedOptions = ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);

    class TestableBackgroundActivityStartController extends BackgroundActivityStartController {
        private Set<Pair<Integer, Integer>> mBalPermissionUidPidPairs = new HashSet<>();

        TestableBackgroundActivityStartController(ActivityTaskManagerService service,
                ActivityTaskSupervisor supervisor) {
            super(service, supervisor);
        }

        @Override
        protected void writeBalAllowedLog(String activityName, int code,
                BalState state) {
            mBalAllowedLogs.add(new BalAllowedLog(activityName, code));
        }

        @Override
        boolean hasBalPermission(int uid, int pid) {
            return mBalPermissionUidPidPairs.contains(Pair.create(uid, pid));
        }

        void addBalPermission(int uid, int pid) {
            mBalPermissionUidPidPairs.add(Pair.create(uid, pid));
        }

    }

    @Before
    public void setUp() throws Exception {
        // wire objects
        mService.mTaskSupervisor = mSupervisor;
        mService.mContext = mContext;
        setViaReflection(mService, "mActiveUids", mActiveUids);
        when(mService.getPackageManagerInternalLocked()).thenReturn(
                mPackageManagerInternal);
        mService.mRootWindowContainer = mRootWindowContainer;
        when(mService.getAppOpsManager()).thenReturn(mAppOpsManager);
        setViaReflection(mService, "mProcessMap", mProcessMap);

        //Mockito.when(mSupervisor.getBackgroundActivityLaunchController()).thenReturn(mController);
        setViaReflection(mSupervisor, "mRecentTasks", mRecentTasks);

        mController = new TestableBackgroundActivityStartController(mService, mSupervisor);

        // nicer toString
        when(mPendingIntentRecord.toString()).thenReturn("PendingIntentRecord");

        // safe defaults
        when(mAppOpsManager.checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                anyInt(), anyString())).thenReturn(AppOpsManager.MODE_DEFAULT);
        when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
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
    public void testNoExemption() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        assertWithMessage(balState.toString()).that(balState.isPendingIntent()).isTrue();

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        BalVerdict realCallerVerdict = mController.checkBackgroundActivityStartAllowedBySender(
                balState);

        balState.setResultForCaller(callerVerdict);
        balState.setResultForRealCaller(realCallerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BackgroundActivityStartController.BAL_BLOCK);
        assertWithMessage(balState.toString()).that(realCallerVerdict.getCode()).isEqualTo(
                BackgroundActivityStartController.BAL_BLOCK);
    }

    @Test
    public void testCaller_appHasVisibleWindow() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(mService.hasActiveVisibleWindow(eq(callingUid))).thenReturn(true);
        when(mService.getBalAppSwitchesState()).thenReturn(APP_SWITCH_ALLOW);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_VISIBLE_WINDOW);
    }

    @Test
    public void testRealCaller_appHasVisibleWindow() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(mService.hasActiveVisibleWindow(eq(realCallingUid))).thenReturn(true);
        when(mService.getBalAppSwitchesState()).thenReturn(APP_SWITCH_ALLOW);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict realCallerVerdict = mController.checkBackgroundActivityStartAllowedBySender(
                balState);
        balState.setResultForRealCaller(realCallerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(realCallerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_VISIBLE_WINDOW);
    }

    @Test
    public void testCaller_appAllowedByBLPC() {
        // This covers the cases
        // - The app has an activity in the back stack of the foreground task.
        // - The app has an activity in the back stack of an existing task on the Recents screen.
        // - The app has an activity that started very recently.
        // - The app called finish() on an activity very recently.
        // - The app has a service that is bound by a different, visible app.

        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                new BalVerdict(BAL_ALLOW_FOREGROUND, false, "allowed"));
        when(mService.getBalAppSwitchesState()).thenReturn(APP_SWITCH_ALLOW);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_FOREGROUND);
    }

    @Test
    public void testRealCaller_appAllowedByBLPC() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(
                mService.getProcessController(eq(realCallingPid), eq(realCallingUid))).thenReturn(
                mCallerApp);
        when(mService.getBalAppSwitchesState()).thenReturn(APP_SWITCH_ALLOW);
        when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                new BalVerdict(BAL_ALLOW_FOREGROUND, false, "allowed"));

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict realCallerVerdict = mController.checkBackgroundActivityStartAllowedBySender(
                balState);
        balState.setResultForRealCaller(realCallerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(realCallerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_FOREGROUND);
    }

    // TODO? The app has one of the following services that is bound by the system. These
    //  services might need to launch a UI.

    @Test
    public void testRealCaller_appAllowedByBLPCforOtherProcess() {
        // The app has a service that is bound by a different, visible app. The app bound to the
        // service must remain visible for the app in the background to start activities
        // successfully.
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        WindowProcessControllerMap mProcessMap = new WindowProcessControllerMap();
        WindowProcessController otherProcess = Mockito.mock(WindowProcessController.class);
        mProcessMap.put(callingPid, mCallerApp);
        mProcessMap.put(REGULAR_PID_1_1, otherProcess);
        setViaReflection(mService, "mProcessMap", mProcessMap);
        when(
                mService.getProcessController(eq(realCallingPid), eq(realCallingUid))).thenReturn(
                mCallerApp);
        when(mService.getBalAppSwitchesState()).thenReturn(APP_SWITCH_ALLOW);
        when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                BalVerdict.BLOCK);
        when(otherProcess.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                new BalVerdict(BAL_ALLOW_FOREGROUND, false, "allowed"));

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict realCallerVerdict = mController.checkBackgroundActivityStartAllowedBySender(
                balState);
        balState.setResultForRealCaller(realCallerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(realCallerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_FOREGROUND);
    }

    @Test
    public void testRealCaller_isCompanionApp() {
        // The app has a service that is bound by a different, visible app. The app bound to the
        // service must remain visible for the app in the background to start activities
        // successfully.
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        final int realCallingUserId = UserHandle.getUserId(realCallingUid);
        when(mService.isAssociatedCompanionApp(eq(realCallingUserId),
                eq(realCallingUid))).thenReturn(true);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict realCallerVerdict = mController.checkBackgroundActivityStartAllowedBySender(
                balState);
        balState.setResultForRealCaller(realCallerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(realCallerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_ALLOWLISTED_COMPONENT);
    }

    @Test
    public void testCaller_balPermission() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        mController.addBalPermission(callingUid, callingPid);
        mController.addBalPermission(callingUid, NO_PID);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_PERMISSION);
    }

    @Test
    public void testRealCaller_balPermission() {
        // BAL allowed by permission. Requires explicit opt-in in options (hidden/not documented!).
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        mController.addBalPermission(realCallingUid, realCallingPid);
        mController.addBalPermission(realCallingUid, NO_PID);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        checkedOptions.setPendingIntentBackgroundActivityLaunchAllowedByPermission(true);
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        assertThat(balState.isPendingIntentBalAllowedByPermission()).isTrue();

        // call
        BalVerdict realCallerVerdict = mController.checkBackgroundActivityStartAllowedBySender(
                balState);
        balState.setResultForRealCaller(realCallerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(realCallerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_PERMISSION);
    }

    @Test
    public void testCaller_sawPermission() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(mService.hasSystemAlertWindowPermission(eq(callingUid), eq(callingPid),
                eq(callingPackage))).thenReturn(true);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_SAW_PERMISSION);
    }

    @Test
    public void testCaller_isRecents() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        //if (mSupervisor.mRecentTasks.isCallerRecents(state.mCallingUid))
        RecentTasks recentTasks = mock(RecentTasks.class);
        when(recentTasks.isCallerRecents(eq(callingUid))).thenReturn(true);
        mSupervisor.mRecentTasks = recentTasks;

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_ALLOWLISTED_COMPONENT);
    }

    @Test
    public void testCaller_isDeviceOwner() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(mService.isDeviceOwner(eq(callingUid))).thenReturn(true);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_ALLOWLISTED_COMPONENT);
    }

    @Test
    public void testCaller_isAffiliatedProfileOwner() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        // setup state
        when(mService.isAffiliatedProfileOwner(eq(callingUid))).thenReturn(true);

        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_ALLOWLISTED_COMPONENT);
    }

    @Test
    public void testCaller_isExemptFromBgStartRestriction() {
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = REGULAR_UID_2;
        int realCallingPid = REGULAR_PID_2;

        mDeviceConfig.set("system_exempt_from_activity_bg_start_restriction_enabled", "true");
        AppOpsManager appOpsManager = mock(AppOpsManager.class);
        when(mService.getAppOpsManager()).thenReturn(appOpsManager);
        when(appOpsManager.checkOpNoThrow(eq(
                        AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                eq(callingUid), eq(callingPackage))).thenReturn(AppOpsManager.MODE_ALLOWED);


        // prepare call
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = mCheckedOptions;
        BackgroundActivityStartController.BalState balState = mController.new BalState(callingUid,
                callingPid, callingPackage, realCallingUid, realCallingPid, null,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // call
        BalVerdict callerVerdict = mController.checkBackgroundActivityStartAllowedByCaller(
                balState);
        balState.setResultForCaller(callerVerdict);

        // assertions
        assertWithMessage(balState.toString()).that(callerVerdict.getCode()).isEqualTo(
                BAL_ALLOW_PERMISSION);
    }
}
