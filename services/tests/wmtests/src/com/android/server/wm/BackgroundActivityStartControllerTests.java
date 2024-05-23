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

import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_PENDING_INTENT;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_PERMISSION;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_VISIBLE_WINDOW;
import static com.android.server.wm.BackgroundActivityStartController.BAL_BLOCK;
import static com.android.window.flags.Flags.balImprovedMetrics;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.content.ComponentName;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests for the {@link BackgroundActivityStartController} class.
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

    private static final Intent TEST_INTENT = new Intent()
            .setComponent(new ComponentName("package.app3", "someClass"));

    public @Rule MockitoRule mMockitoRule = MockitoJUnit.rule().strictness(Strictness.LENIENT);

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

    class TestableBackgroundActivityStartController extends BackgroundActivityStartController {
        Optional<BalVerdict> mCallerVerdict = Optional.empty();
        Optional<BalVerdict> mRealCallerVerdict = Optional.empty();
        Map<WindowProcessController, BalVerdict> mProcessVerdicts = new HashMap<>();

        TestableBackgroundActivityStartController(ActivityTaskManagerService service,
                ActivityTaskSupervisor supervisor) {
            super(service, supervisor);
        }

        @Override
        protected void showToast(String toastText) {
            mShownToasts.add(toastText);
        }

        @Override
        protected void writeBalAllowedLog(String activityName, int code,
                BackgroundActivityStartController.BalState state) {
            mBalAllowedLogs.add(new BalAllowedLog(activityName, code));
        }

        @Override
        boolean shouldLogStats(BalVerdict finalVerdict, BalState state) {
            return true;
        }

        @Override
        boolean shouldLogIntentActivity(BalVerdict finalVerdict, BalState state) {
            return true;
        }

        @Override
        BalVerdict checkBackgroundActivityStartAllowedByCaller(BalState state) {
            return mCallerVerdict.orElseGet(
                    () -> super.checkBackgroundActivityStartAllowedByCaller(state));
        }

        public void setCallerVerdict(BalVerdict verdict) {
            this.mCallerVerdict = Optional.of(verdict);
        }

        @Override
        BalVerdict checkBackgroundActivityStartAllowedBySender(BalState state) {
            return mRealCallerVerdict.orElseGet(
                    () -> super.checkBackgroundActivityStartAllowedBySender(state));
        }

        public void setRealCallerVerdict(BalVerdict verdict) {
            this.mRealCallerVerdict = Optional.of(verdict);
        }

        @Override
        BalVerdict checkProcessAllowsBal(WindowProcessController app, BalState state) {
            if (mProcessVerdicts.containsKey(app)) {
                return mProcessVerdicts.get(app);
            }
            return super.checkProcessAllowsBal(app, state);
        }
    }

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

        mController = new TestableBackgroundActivityStartController(mService, mSupervisor);

        // nicer toString
        Mockito.when(mPendingIntentRecord.toString()).thenReturn("PendingIntentRecord");

        // safe defaults
        Mockito.when(mAppOpsManager.checkOpNoThrow(
                eq(AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION),
                anyInt(), anyString())).thenReturn(AppOpsManager.MODE_DEFAULT);
        Mockito.when(mCallerApp.areBackgroundActivityStartsAllowed(anyInt())).thenReturn(
                BalVerdict.BLOCK);
    }

    static final void setViaReflection(Object o, String property, Object value) {
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
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict.getCode()).isEqualTo(BackgroundActivityStartController.BAL_BLOCK);
        if (balImprovedMetrics()) {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("package.app3/someClass", BAL_BLOCK));
        } else {
            assertThat(mBalAllowedLogs).isEmpty(); // not allowed
        }
    }

    // Tests for BackgroundActivityStartController.checkBackgroundActivityStart

    @Test
    public void testRegularActivityStart_notAllowed_isBlocked() {
        // setup state
        mController.setCallerVerdict(BalVerdict.BLOCK);
        mController.setRealCallerVerdict(BalVerdict.BLOCK);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(BalVerdict.BLOCK);
        if (balImprovedMetrics()) {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("package.app3/someClass", BAL_BLOCK));
        } else {
            assertThat(mBalAllowedLogs).isEmpty(); // not allowed
        }
    }

    @Test
    public void testRegularActivityStart_allowedByCaller_isAllowed() {
        // setup state
        BalVerdict callerVerdict = new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false,
                "CallerIsVisible");
        mController.setCallerVerdict(callerVerdict);
        mController.setRealCallerVerdict(BalVerdict.BLOCK);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(callerVerdict);
        if (balImprovedMetrics()) {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("package.app3/someClass", callerVerdict.getCode()));
        } else {
            assertThat(mBalAllowedLogs).isEmpty(); // non-critical exception
        }
    }

    @Test
    public void testRegularActivityStart_allowedByRealCaller_isAllowed() {
        // setup state
        BalVerdict realCallerVerdict = new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false,
                "RealCallerIsVisible");
        mController.setCallerVerdict(BalVerdict.BLOCK);
        mController.setRealCallerVerdict(realCallerVerdict);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(realCallerVerdict);
        assertThat(mBalAllowedLogs).containsExactly(
                new BalAllowedLog("package.app3/someClass", realCallerVerdict.getCode()));
        // TODO questionable log (should we only log PIs?)
    }

    @Test
    public void testRegularActivityStart_allowedByCallerAndRealCaller_returnsCallerVerdict() {
        // setup state
        BalVerdict callerVerdict =
                new BalVerdict(BAL_ALLOW_PERMISSION, false, "CallerHasPermission");
        BalVerdict realCallerVerdict =
                new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false, "RealCallerIsVisible");
        mController.setCallerVerdict(callerVerdict);
        mController.setRealCallerVerdict(realCallerVerdict);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(callerVerdict);
        if (balImprovedMetrics()) {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("package.app3/someClass", callerVerdict.getCode()));
        } else {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("", callerVerdict.getCode()));
        }
    }

    @Test
    public void testPendingIntent_allowedByCallerAndRealCallerButOptOut_isBlocked() {
        // setup state
        BalVerdict callerVerdict =
                new BalVerdict(BAL_ALLOW_PERMISSION, false, "CallerhasPermission");
        BalVerdict realCallerVerdict =
                new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false, "RealCallerIsVisible");
        mController.setCallerVerdict(callerVerdict);
        mController.setRealCallerVerdict(realCallerVerdict);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED)
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(BalVerdict.BLOCK);
        if (balImprovedMetrics()) {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("package.app3/someClass", BAL_BLOCK));
        } else {
            assertThat(mBalAllowedLogs).isEmpty();
        }
    }

    @Test
    public void testPendingIntent_allowedByCallerAndOptIn_isAllowed() {
        // setup state
        BalVerdict callerVerdict =
                new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false, "CallerIsVisible");
        mController.setCallerVerdict(callerVerdict);
        mController.setRealCallerVerdict(BalVerdict.BLOCK);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(callerVerdict);
        if (balImprovedMetrics()) {
            assertThat(mBalAllowedLogs).containsExactly(
                    new BalAllowedLog("package.app3/someClass", callerVerdict.getCode()));
        } else {
            assertThat(mBalAllowedLogs).isEmpty();
        }
    }

    @Test
    public void testPendingIntent_allowedByRealCallerAndOptIn_isAllowed() {
        // setup state
        BalVerdict realCallerVerdict =
                new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW, false, "RealCallerIsVisible");
        mController.setCallerVerdict(BalVerdict.BLOCK);
        mController.setRealCallerVerdict(realCallerVerdict);

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);

        // call
        BalVerdict verdict = mController.checkBackgroundActivityStart(callingUid, callingPid,
                callingPackage, realCallingUid, realCallingPid, mCallerApp,
                originatingPendingIntent, forcedBalByPiSender, mResultRecord, intent,
                checkedOptions);

        // assertions
        assertThat(verdict).isEqualTo(realCallerVerdict);
        assertThat(mBalAllowedLogs).containsExactly(
                new BalAllowedLog("package.app3/someClass", BAL_ALLOW_PENDING_INTENT));

    }

    // Tests for BackgroundActivityStartController.checkBackgroundActivityStartAllowedByCaller

    // Tests for BackgroundActivityStartController.checkBackgroundActivityStartAllowedBySender

    // Tests for BalState

    @Test
    public void testBalState_regularStart_isAutoOptIn() {
        // setup state

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = null;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();
        WindowProcessController callerApp = mCallerApp;
        ActivityRecord resultRecord = null;

        // call
        BackgroundActivityStartController.BalState balState = mController
                .new BalState(callingUid, callingPid, callingPackage, realCallingUid,
                realCallingPid, callerApp, originatingPendingIntent, forcedBalByPiSender,
                resultRecord, intent, checkedOptions);

        // assertions
        assertThat(balState.mAutoOptInReason).isEqualTo("notPendingIntent");
        assertThat(balState.mBalAllowedByPiCreator).isEqualTo(BackgroundStartPrivileges.ALLOW_BAL);
        assertThat(balState.mBalAllowedByPiSender).isEqualTo(BackgroundStartPrivileges.ALLOW_BAL);
        assertThat(balState.callerExplicitOptInOrAutoOptIn()).isTrue();
        assertThat(balState.callerExplicitOptInOrOut()).isFalse();
        assertThat(balState.realCallerExplicitOptInOrAutoOptIn()).isTrue();
        assertThat(balState.realCallerExplicitOptInOrOut()).isFalse();
        assertThat(balState.toString()).contains(
                "[callingPackage: package.app1; "
                        + "callingPackageTargetSdk: -1; "
                        + "callingUid: 10001; "
                        + "callingPid: 11001; "
                        + "appSwitchState: 0; "
                        + "callingUidHasAnyVisibleWindow: false; "
                        + "callingUidProcState: NONEXISTENT; "
                        + "isCallingUidPersistentSystemProcess: false; "
                        + "forcedBalByPiSender: BSP.NONE; "
                        + "intent: Intent { cmp=package.app3/someClass }; "
                        + "callerApp: mCallerApp; "
                        + "inVisibleTask: false; "
                        + "balAllowedByPiCreator: BSP.ALLOW_BAL; "
                        + "balAllowedByPiCreatorWithHardening: BSP.ALLOW_BAL; "
                        + "resultIfPiCreatorAllowsBal: null; "
                        + "hasRealCaller: true; "
                        + "isCallForResult: false; "
                        + "isPendingIntent: false; "
                        + "autoOptInReason: notPendingIntent; "
                        + "realCallingPackage: uid=1[debugOnly]; "
                        + "realCallingPackageTargetSdk: -1; "
                        + "realCallingUid: 1; "
                        + "realCallingPid: 1; "
                        + "realCallingUidHasAnyVisibleWindow: false; "
                        + "realCallingUidProcState: NONEXISTENT; "
                        + "isRealCallingUidPersistentSystemProcess: false; "
                        + "originatingPendingIntent: null; "
                        + "realCallerApp: null; "
                        + "balAllowedByPiSender: BSP.ALLOW_BAL; "
                        + "resultIfPiSenderAllowsBal: null");
    }

    @Test
    public void testBalState_pendingIntentForResult_isOptedInForSenderOnly() {
        // setup state
        Mockito.when(mPendingIntentRecord.toString()).thenReturn("PendingIntentRecord");

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();
        WindowProcessController callerApp = mCallerApp;
        ActivityRecord resultRecord = mResultRecord;

        // call
        BackgroundActivityStartController.BalState balState = mController
                .new BalState(callingUid, callingPid, callingPackage, realCallingUid,
                realCallingPid, callerApp, originatingPendingIntent, forcedBalByPiSender,
                resultRecord, intent, checkedOptions);

        // assertions
        assertThat(balState.mAutoOptInReason).isEqualTo("callForResult");
        assertThat(balState.mBalAllowedByPiCreator).isEqualTo(BackgroundStartPrivileges.NONE);
        assertThat(balState.mBalAllowedByPiSender).isEqualTo(BackgroundStartPrivileges.ALLOW_BAL);
        assertThat(balState.callerExplicitOptInOrAutoOptIn()).isFalse();
        assertThat(balState.callerExplicitOptInOrOut()).isFalse();
        assertThat(balState.realCallerExplicitOptInOrAutoOptIn()).isTrue();
        assertThat(balState.realCallerExplicitOptInOrOut()).isFalse();
    }

    @Test
    public void testBalState_pendingIntentWithDefaults_isOptedOut() {
        // setup state

        // prepare call
        int callingUid = REGULAR_UID_1;
        int callingPid = REGULAR_PID_1;
        final String callingPackage = REGULAR_PACKAGE_1;
        int realCallingUid = NO_UID;
        int realCallingPid = NO_PID;
        PendingIntentRecord originatingPendingIntent = mPendingIntentRecord;
        BackgroundStartPrivileges forcedBalByPiSender = BackgroundStartPrivileges.NONE;
        Intent intent = TEST_INTENT;
        ActivityOptions checkedOptions = ActivityOptions.makeBasic();
        WindowProcessController callerApp = mCallerApp;
        ActivityRecord resultRecord = null;

        // call
        BackgroundActivityStartController.BalState balState = mController
                .new BalState(callingUid, callingPid, callingPackage, realCallingUid,
                realCallingPid, callerApp, originatingPendingIntent, forcedBalByPiSender,
                resultRecord, intent, checkedOptions);

        // assertions
        assertThat(balState.mAutoOptInReason).isNull();
        assertThat(balState.mBalAllowedByPiCreator).isEqualTo(BackgroundStartPrivileges.NONE);
        assertThat(balState.mBalAllowedByPiSender).isEqualTo(BackgroundStartPrivileges.ALLOW_FGS);
        assertThat(balState.isPendingIntent()).isTrue();
        assertThat(balState.callerExplicitOptInOrAutoOptIn()).isFalse();
        assertThat(balState.callerExplicitOptInOrOut()).isFalse();
        assertThat(balState.realCallerExplicitOptInOrAutoOptIn()).isFalse();
        assertThat(balState.realCallerExplicitOptInOrOut()).isFalse();
        assertThat(balState.toString()).contains(
                "[callingPackage: package.app1; "
                        + "callingPackageTargetSdk: -1; "
                        + "callingUid: 10001; "
                        + "callingPid: 11001; "
                        + "appSwitchState: 0; "
                        + "callingUidHasAnyVisibleWindow: false; "
                        + "callingUidProcState: NONEXISTENT; "
                        + "isCallingUidPersistentSystemProcess: false; "
                        + "forcedBalByPiSender: BSP.NONE; "
                        + "intent: Intent { cmp=package.app3/someClass }; "
                        + "callerApp: mCallerApp; "
                        + "inVisibleTask: false; "
                        + "balAllowedByPiCreator: BSP.NONE; "
                        + "balAllowedByPiCreatorWithHardening: BSP.NONE; "
                        + "resultIfPiCreatorAllowsBal: null; "
                        + "hasRealCaller: true; "
                        + "isCallForResult: false; "
                        + "isPendingIntent: true; "
                        + "autoOptInReason: null; "
                        + "realCallingPackage: uid=1[debugOnly]; "
                        + "realCallingPackageTargetSdk: -1; "
                        + "realCallingUid: 1; "
                        + "realCallingPid: 1; "
                        + "realCallingUidHasAnyVisibleWindow: false; "
                        + "realCallingUidProcState: NONEXISTENT; "
                        + "isRealCallingUidPersistentSystemProcess: false; "
                        + "originatingPendingIntent: PendingIntentRecord; "
                        + "realCallerApp: null; "
                        + "balAllowedByPiSender: BSP.ALLOW_FGS; "
                        + "resultIfPiSenderAllowsBal: null");
    }
}
