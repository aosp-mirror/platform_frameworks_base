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

import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityOptions.BackgroundActivityStartMode;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_COMPAT;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
import static android.os.Process.INVALID_PID;
import static android.os.Process.INVALID_UID;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SYSTEM_UID;
import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;
import static android.security.Flags.asmOptSystemIntoEnforcement;

import static com.android.server.wm.ActivityStarter.ASM_RESTRICTIONS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ACTIVITY_BG_START_GRACE_PERIOD_MS;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_FG_ONLY;
import static com.android.server.wm.ActivityTaskSupervisor.getApplicationLabel;
import static com.android.server.wm.PendingRemoteAnimationRegistry.TIMEOUT_MS;
import static com.android.window.flags.Flags.balAdditionalStartModes;
import static com.android.window.flags.Flags.balDontBringExistingBackgroundTaskStackToFg;
import static com.android.window.flags.Flags.balImproveRealCallerVisibilityCheck;
import static com.android.window.flags.Flags.balImprovedMetrics;
import static com.android.window.flags.Flags.balRequireOptInByPendingIntentCreator;
import static com.android.window.flags.Flags.balRespectAppSwitchStateWhenCheckBoundByForegroundUid;
import static com.android.window.flags.Flags.balShowToastsBlocked;
import static com.android.window.flags.Flags.balStrictModeRo;

import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.IBackgroundActivityLaunchCallback;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.server.UiThread;
import com.android.server.am.PendingIntentRecord;
import com.android.server.wm.BackgroundLaunchProcessController.BalCheckConfiguration;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Helper class to check permissions for starting Activities.
 *
 * <p>This class collects all the logic to prevent malicious attempts to start activities.
 */
public class BackgroundActivityStartController {

    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "BackgroundActivityStartController" : TAG_ATM;

    private static final long ASM_GRACEPERIOD_TIMEOUT_MS = TIMEOUT_MS;
    private static final int ASM_GRACEPERIOD_MAX_REPEATS = 5;
    private static final int NO_PROCESS_UID = -1;

    private static final BalCheckConfiguration BAL_CHECK_FOREGROUND = new BalCheckConfiguration(
            /* isCheckingForFgsStarts */ false,
            /* checkVisibility */ true,
            /* checkOtherExemptions */ false,
            ACTIVITY_BG_START_GRACE_PERIOD_MS);
    private static final BalCheckConfiguration BAL_CHECK_BACKGROUND = new BalCheckConfiguration(
            /* isCheckingForFgsStarts */ false,
            /* checkVisibility */ false,
            /* checkOtherExemptions */ true,
            ACTIVITY_BG_START_GRACE_PERIOD_MS);

    static final String AUTO_OPT_IN_NOT_PENDING_INTENT = "notPendingIntent";
    static final String AUTO_OPT_IN_CALL_FOR_RESULT = "callForResult";
    static final String AUTO_OPT_IN_SAME_UID = "sameUid";
    static final String AUTO_OPT_IN_COMPAT = "compatibility";

    /** If enabled the creator will not allow BAL on its behalf by default. */
    @ChangeId
    @EnabledAfter(targetSdkVersion = UPSIDE_DOWN_CAKE)
    private static final long DEFAULT_RESCIND_BAL_PRIVILEGES_FROM_PENDING_INTENT_CREATOR =
            296478951;
    public static final ActivityOptions ACTIVITY_OPTIONS_SYSTEM_DEFINED =
            ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED)
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                            MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);

    private final ActivityTaskManagerService mService;

    private final ActivityTaskSupervisor mSupervisor;
    @GuardedBy("mStrictModeBalCallbacks")
    private final SparseArray<ArrayMap<IBinder, IBackgroundActivityLaunchCallback>>
            mStrictModeBalCallbacks = new SparseArray<>();


    // TODO(b/263368846) Rename when ASM logic is moved in
    @Retention(SOURCE)
    @IntDef({
            BAL_ALLOW_ALLOWLISTED_COMPONENT,
            BAL_ALLOW_ALLOWLISTED_UID,
            BAL_ALLOW_BOUND_BY_FOREGROUND,
            BAL_ALLOW_DEFAULT,
            BAL_ALLOW_FOREGROUND,
            BAL_ALLOW_GRACE_PERIOD,
            BAL_ALLOW_PENDING_INTENT,
            BAL_ALLOW_PERMISSION,
            BAL_ALLOW_SAW_PERMISSION,
            BAL_ALLOW_SDK_SANDBOX,
            BAL_ALLOW_TOKEN,
            BAL_ALLOW_VISIBLE_WINDOW,
            BAL_BLOCK
    })
    public @interface BalCode {}

    static final int BAL_BLOCK = FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_BLOCKED;

    static final int BAL_ALLOW_DEFAULT =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_DEFAULT;

    // Following codes are in order of precedence

    /** Important UIDs which should be always allowed to launch activities */
    static final int BAL_ALLOW_ALLOWLISTED_UID =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_ALLOWLISTED_UID;

    /** Apps that fulfill a certain role that can can always launch new tasks */
    static final int BAL_ALLOW_ALLOWLISTED_COMPONENT =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_ALLOWLISTED_COMPONENT;

    /**
     * Apps which currently have a visible window or are bound by a service with a visible
     * window
     */
    static final int BAL_ALLOW_VISIBLE_WINDOW =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_VISIBLE_WINDOW;

    /** Allowed due to the PendingIntent sender */
    static final int BAL_ALLOW_PENDING_INTENT =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_PENDING_INTENT;

    /**
     * App has START_ACTIVITIES_FROM_BACKGROUND permission or BAL instrumentation privileges
     * granted to it
     */
    static final int BAL_ALLOW_PERMISSION =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_BAL_PERMISSION;

    /** Process has SYSTEM_ALERT_WINDOW permission granted to it */
    static final int BAL_ALLOW_SAW_PERMISSION =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_SAW_PERMISSION;

    /** App is in grace period after an activity was started or finished */
    static final int BAL_ALLOW_GRACE_PERIOD =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_GRACE_PERIOD;

    /** App is in a foreground task or bound to a foreground service (but not itself visible) */
    static final int BAL_ALLOW_FOREGROUND =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_FOREGROUND;

    /** Process belongs to a SDK sandbox */
    static final int BAL_ALLOW_SDK_SANDBOX =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_SDK_SANDBOX;

    /** Process belongs to a SDK sandbox */
    static final int BAL_ALLOW_NON_APP_VISIBLE_WINDOW =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_NON_APP_VISIBLE_WINDOW;

    /** Process belongs to a SDK sandbox */
    static final int BAL_ALLOW_TOKEN =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_TOKEN;

    /** Process belongs to a SDK sandbox */
    static final int BAL_ALLOW_BOUND_BY_FOREGROUND =
            FrameworkStatsLog.BAL_ALLOWED__ALLOWED_REASON__BAL_ALLOW_BOUND_BY_FOREGROUND;

    static String balCodeToString(@BalCode int balCode) {
        return switch (balCode) {
            case BAL_ALLOW_ALLOWLISTED_COMPONENT -> "BAL_ALLOW_ALLOWLISTED_COMPONENT";
            case BAL_ALLOW_ALLOWLISTED_UID -> "BAL_ALLOW_ALLOWLISTED_UID";
            case BAL_ALLOW_BOUND_BY_FOREGROUND -> "BAL_ALLOW_BOUND_BY_FOREGROUND";
            case BAL_ALLOW_DEFAULT -> "BAL_ALLOW_DEFAULT";
            case BAL_ALLOW_FOREGROUND -> "BAL_ALLOW_FOREGROUND";
            case BAL_ALLOW_GRACE_PERIOD -> "BAL_ALLOW_GRACE_PERIOD";
            case BAL_ALLOW_NON_APP_VISIBLE_WINDOW -> "BAL_ALLOW_NON_APP_VISIBLE_WINDOW";
            case BAL_ALLOW_PENDING_INTENT -> "BAL_ALLOW_PENDING_INTENT";
            case BAL_ALLOW_PERMISSION -> "BAL_ALLOW_PERMISSION";
            case BAL_ALLOW_SAW_PERMISSION -> "BAL_ALLOW_SAW_PERMISSION";
            case BAL_ALLOW_SDK_SANDBOX -> "BAL_ALLOW_SDK_SANDBOX";
            case BAL_ALLOW_TOKEN -> "BAL_ALLOW_TOKEN";
            case BAL_ALLOW_VISIBLE_WINDOW -> "BAL_ALLOW_VISIBLE_WINDOW";
            case BAL_BLOCK -> "BAL_BLOCK";
            default -> throw new IllegalArgumentException("Unexpected value: " + balCode);
        };
    }

    static String balStartModeToString(@BackgroundActivityStartMode int startMode) {
        return switch (startMode) {
            case MODE_BACKGROUND_ACTIVITY_START_ALLOWED -> "MODE_BACKGROUND_ACTIVITY_START_ALLOWED";
            case MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED ->
                    "MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED";
            case MODE_BACKGROUND_ACTIVITY_START_COMPAT -> "MODE_BACKGROUND_ACTIVITY_START_COMPAT";
            case MODE_BACKGROUND_ACTIVITY_START_DENIED -> "MODE_BACKGROUND_ACTIVITY_START_DENIED";
            case MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS ->
                    "MODE_BACKGROUND_ACTIVITY_START_ALWAYS";
            case MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE ->
                    "MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE";
            default -> "MODE_BACKGROUND_ACTIVITY_START_ALLOWED(" + startMode + ")";
        };
    }

    @GuardedBy("mService.mGlobalLock")
    private final HashMap<Integer, FinishedActivityEntry> mTaskIdToFinishedActivity =
            new HashMap<>();
    @GuardedBy("mService.mGlobalLock")
    private FinishedActivityEntry mTopFinishedActivity = null;

    BackgroundActivityStartController(
            final ActivityTaskManagerService service, final ActivityTaskSupervisor supervisor) {
        mService = service;
        mSupervisor = supervisor;
    }

    private boolean isHomeApp(int uid, @Nullable String packageName) {
        if (mService.mHomeProcess != null) {
            // Fast check
            return uid == mService.mHomeProcess.mUid;
        }
        if (packageName == null) {
            return false;
        }
        ComponentName activity =
                mService.getPackageManagerInternalLocked()
                        .getDefaultHomeActivity(UserHandle.getUserId(uid));
        return activity != null && packageName.equals(activity.getPackageName());
    }

    @VisibleForTesting class BalState {

        private final String mCallingPackage;
        private final int mCallingUid;
        private final int mCallingPid;
        private final @ActivityTaskManagerService.AppSwitchState int mAppSwitchState;
        private final boolean mCallingUidHasAnyVisibleWindow;
        private final @ActivityManager.ProcessState int mCallingUidProcState;
        private final boolean mIsCallingUidPersistentSystemProcess;
        final BackgroundStartPrivileges mBalAllowedByPiSender;
        final BackgroundStartPrivileges mBalAllowedByPiCreatorWithHardening;
        final BackgroundStartPrivileges mBalAllowedByPiCreator;
        private final String mRealCallingPackage;
        private final int mRealCallingUid;
        private final int mRealCallingPid;
        private final boolean mRealCallingUidHasAnyVisibleWindow;
        private final @ActivityManager.ProcessState int mRealCallingUidProcState;
        private final boolean mIsRealCallingUidPersistentSystemProcess;
        private final PendingIntentRecord mOriginatingPendingIntent;
        private final boolean mAllowBalExemptionForSystemProcess;
        private final Intent mIntent;
        private final WindowProcessController mCallerApp;
        private final WindowProcessController mRealCallerApp;
        private final boolean mIsCallForResult;
        private final ActivityOptions mCheckedOptions;
        final String mAutoOptInReason;
        private final boolean mAutoOptInCaller;
        private BalVerdict mResultForCaller;
        private BalVerdict mResultForRealCaller;

        @VisibleForTesting BalState(int callingUid, int callingPid, final String callingPackage,
                 int realCallingUid, int realCallingPid,
                 WindowProcessController callerApp,
                 PendingIntentRecord originatingPendingIntent,
                 boolean allowBalExemptionForSystemProcess,
                 ActivityRecord resultRecord,
                 Intent intent,
                 ActivityOptions checkedOptions) {
            this.mCallingPackage = callingPackage;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            mRealCallingUid = realCallingUid;
            mRealCallingPid = realCallingPid;
            mCallerApp = callerApp;
            mAllowBalExemptionForSystemProcess = allowBalExemptionForSystemProcess;
            mOriginatingPendingIntent = originatingPendingIntent;
            mIntent = intent;
            mRealCallingPackage = mService.getPackageNameIfUnique(realCallingUid, realCallingPid);
            mIsCallForResult = resultRecord != null;
            mCheckedOptions = checkedOptions;
            @BackgroundActivityStartMode int callerBackgroundActivityStartMode =
                    checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode();
            @BackgroundActivityStartMode int realCallerBackgroundActivityStartMode =
                    checkedOptions.getPendingIntentBackgroundActivityStartMode();

            if (!balImproveRealCallerVisibilityCheck()) {
                // without this fix the auto-opt ins below would violate CTS tests
                mAutoOptInReason = null;
                mAutoOptInCaller = false;
            } else if (originatingPendingIntent == null) {
                mAutoOptInReason = AUTO_OPT_IN_NOT_PENDING_INTENT;
                mAutoOptInCaller = true;
            } else if (mIsCallForResult) {
                mAutoOptInReason = AUTO_OPT_IN_CALL_FOR_RESULT;
                mAutoOptInCaller = false;
            } else if (callingUid == realCallingUid) {
                mAutoOptInReason = AUTO_OPT_IN_SAME_UID;
                mAutoOptInCaller = false;
            } else if (realCallerBackgroundActivityStartMode
                    == MODE_BACKGROUND_ACTIVITY_START_COMPAT) {
                mAutoOptInReason = AUTO_OPT_IN_COMPAT;
                mAutoOptInCaller = false;
            } else {
                mAutoOptInReason = null;
                mAutoOptInCaller = false;
            }

            if (mAutoOptInCaller) {
                // grant BAL privileges unless explicitly opted out
                mBalAllowedByPiCreatorWithHardening = mBalAllowedByPiCreator =
                        callerBackgroundActivityStartMode == MODE_BACKGROUND_ACTIVITY_START_DENIED
                                ? BackgroundStartPrivileges.NONE
                                : BackgroundStartPrivileges.ALLOW_BAL;
            } else {
                // for PendingIntents we restrict BAL based on target_sdk
                mBalAllowedByPiCreatorWithHardening = getBackgroundStartPrivilegesAllowedByCreator(
                        callingUid, callingPackage, checkedOptions);
                final BackgroundStartPrivileges mBalAllowedByPiCreatorWithoutHardening =
                        callerBackgroundActivityStartMode
                                == MODE_BACKGROUND_ACTIVITY_START_DENIED
                                ? BackgroundStartPrivileges.NONE
                                : BackgroundStartPrivileges.ALLOW_BAL;
                mBalAllowedByPiCreator = balRequireOptInByPendingIntentCreator()
                        ? mBalAllowedByPiCreatorWithHardening
                        : mBalAllowedByPiCreatorWithoutHardening;
            }

            if (mAutoOptInReason != null) {
                // grant BAL privileges unless explicitly opted out
                mBalAllowedByPiSender = realCallerBackgroundActivityStartMode
                        == MODE_BACKGROUND_ACTIVITY_START_DENIED
                        ? BackgroundStartPrivileges.NONE
                        : BackgroundStartPrivileges.ALLOW_BAL;
            } else {
                // for PendingIntents we restrict BAL based on target_sdk
                mBalAllowedByPiSender =
                        PendingIntentRecord.getBackgroundStartPrivilegesAllowedByCaller(
                                checkedOptions, realCallingUid, mRealCallingPackage);
            }

            mAppSwitchState = mService.getBalAppSwitchesState();
            mCallingUidProcState = mService.mActiveUids.getUidState(callingUid);
            mIsCallingUidPersistentSystemProcess =
                    mCallingUidProcState <= ActivityManager.PROCESS_STATE_PERSISTENT_UI;
            mCallingUidHasAnyVisibleWindow = mService.hasActiveVisibleWindow(callingUid);
            if (realCallingUid == NO_PROCESS_UID) {
                // no process provided
                mRealCallingUidProcState = PROCESS_STATE_NONEXISTENT;
                mRealCallingUidHasAnyVisibleWindow = false;
                mRealCallerApp = null;
                mIsRealCallingUidPersistentSystemProcess = false;
            } else if (callingUid == realCallingUid) {
                mRealCallingUidProcState = mCallingUidProcState;
                mRealCallingUidHasAnyVisibleWindow = mCallingUidHasAnyVisibleWindow;
                // In the PendingIntent case callerApp is not passed in, so resolve it ourselves.
                mRealCallerApp = callerApp == null
                        ? mService.getProcessController(realCallingPid, realCallingUid)
                        : callerApp;
                mIsRealCallingUidPersistentSystemProcess = mIsCallingUidPersistentSystemProcess;
            } else {
                mRealCallingUidProcState = mService.mActiveUids.getUidState(realCallingUid);
                mRealCallingUidHasAnyVisibleWindow =
                        mService.hasActiveVisibleWindow(realCallingUid);
                mRealCallerApp = mService.getProcessController(realCallingPid, realCallingUid);
                mIsRealCallingUidPersistentSystemProcess =
                        mRealCallingUidProcState <= ActivityManager.PROCESS_STATE_PERSISTENT_UI;
            }
        }

        private BackgroundStartPrivileges getBackgroundStartPrivilegesAllowedByCreator(
                int callingUid, String callingPackage, ActivityOptions checkedOptions) {
            switch (checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode()) {
                case MODE_BACKGROUND_ACTIVITY_START_ALLOWED:
                case MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE:
                case MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS:
                    return BackgroundStartPrivileges.ALLOW_BAL;
                case MODE_BACKGROUND_ACTIVITY_START_DENIED:
                    return BackgroundStartPrivileges.NONE;
                case MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED:
                    // no explicit choice by the app - let us decide what to do
                    if (callingUid == ROOT_UID || callingUid == SYSTEM_UID) {
                        // root and system must always opt in explicitly
                        return BackgroundStartPrivileges.NONE;
                    }
                    if (callingPackage != null) {
                        // determine based on the calling/creating package
                        boolean changeEnabled = CompatChanges.isChangeEnabled(
                                DEFAULT_RESCIND_BAL_PRIVILEGES_FROM_PENDING_INTENT_CREATOR,
                                callingPackage,
                                UserHandle.getUserHandleForUid(callingUid));
                        return changeEnabled ? BackgroundStartPrivileges.NONE
                                : BackgroundStartPrivileges.ALLOW_BAL;
                    }
                    // determine based on the calling/creating uid if we cannot determine the
                    // actual package name (e.g. shared uid)
                    boolean changeEnabled = CompatChanges.isChangeEnabled(
                            DEFAULT_RESCIND_BAL_PRIVILEGES_FROM_PENDING_INTENT_CREATOR,
                            callingUid);
                    return changeEnabled ? BackgroundStartPrivileges.NONE
                            : BackgroundStartPrivileges.ALLOW_BAL;
                default:
                    throw new IllegalStateException("unsupported BackgroundActivityStartMode: "
                            + checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode());
            }
        }

        private String getDebugPackageName(String packageName, int uid) {
            if (packageName != null) {
                return packageName; // use actual package
            }
            if (uid == 0) {
                return "root[debugOnly]";
            }
            String name = mService.getPackageManagerInternalLocked().getNameForUid(uid);
            if (name == null) {
                name = "uid=" + uid;
            }
            return name + "[debugOnly]";
        }

        private boolean hasRealCaller() {
            return mRealCallingUid != NO_PROCESS_UID;
        }

        boolean isPendingIntent() {
            return mOriginatingPendingIntent != null && hasRealCaller();
        }

        private boolean callerIsRealCaller() {
            return mCallingUid == mRealCallingUid;
        }

        public void setResultForCaller(BalVerdict resultForCaller) {
            Preconditions.checkState(mResultForCaller == null,
                    "mResultForCaller can only be set once");
            this.mResultForCaller = resultForCaller;
        }

        public void setResultForRealCaller(BalVerdict resultForRealCaller) {
            Preconditions.checkState(mResultForRealCaller == null,
                    "mResultForRealCaller can only be set once");
            this.mResultForRealCaller = resultForRealCaller;
        }

        public boolean callerExplicitOptInOrAutoOptIn() {
            if (mAutoOptInCaller) {
                return !callerExplicitOptOut();
            }
            return mCheckedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
        }

        public boolean realCallerExplicitOptInOrAutoOptIn() {
            if (mAutoOptInReason != null) {
                return !realCallerExplicitOptOut();
            }
            return mCheckedOptions.getPendingIntentBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
        }

        public boolean callerExplicitOptOut() {
            return mCheckedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_DENIED;
        }

        public boolean realCallerExplicitOptOut() {
            return mCheckedOptions.getPendingIntentBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_DENIED;
        }

        public boolean callerExplicitOptInOrOut() {
            return mCheckedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                    != MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
        }

        public boolean realCallerExplicitOptInOrOut() {
            return mCheckedOptions.getPendingIntentBackgroundActivityStartMode()
                    != MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("[callingPackage: ")
                    .append(getDebugPackageName(mCallingPackage, mCallingUid));
            sb.append("; callingPackageTargetSdk: ").append(getTargetSdk(mCallingPackage));
            sb.append("; callingUid: ").append(mCallingUid);
            sb.append("; callingPid: ").append(mCallingPid);
            sb.append("; appSwitchState: ").append(mAppSwitchState);
            sb.append("; callingUidHasAnyVisibleWindow: ").append(mCallingUidHasAnyVisibleWindow);
            sb.append("; callingUidProcState: ").append(DebugUtils.valueToString(
                    ActivityManager.class, "PROCESS_STATE_", mCallingUidProcState));
            sb.append("; isCallingUidPersistentSystemProcess: ")
                    .append(mIsCallingUidPersistentSystemProcess);
            sb.append("; allowBalExemptionForSystemProcess: ")
                    .append(mAllowBalExemptionForSystemProcess);
            sb.append("; intent: ").append(mIntent);
            sb.append("; callerApp: ").append(mCallerApp);
            if (mCallerApp != null) {
                sb.append("; inVisibleTask: ").append(mCallerApp.hasActivityInVisibleTask());
            }
            sb.append("; balAllowedByPiCreator: ").append(mBalAllowedByPiCreator);
            sb.append("; balAllowedByPiCreatorWithHardening: ")
                    .append(mBalAllowedByPiCreatorWithHardening);
            sb.append("; resultIfPiCreatorAllowsBal: ").append(mResultForCaller);
            sb.append("; callerStartMode: ").append(balStartModeToString(
                    mCheckedOptions.getPendingIntentCreatorBackgroundActivityStartMode()));
            sb.append("; hasRealCaller: ").append(hasRealCaller());
            sb.append("; isCallForResult: ").append(mIsCallForResult);
            sb.append("; isPendingIntent: ").append(isPendingIntent());
            sb.append("; autoOptInReason: ").append(mAutoOptInReason);
            if (hasRealCaller()) {
                sb.append("; realCallingPackage: ")
                        .append(getDebugPackageName(mRealCallingPackage, mRealCallingUid));
                sb.append("; realCallingPackageTargetSdk: ")
                        .append(getTargetSdk(mRealCallingPackage));
                sb.append("; realCallingUid: ").append(mRealCallingUid);
                sb.append("; realCallingPid: ").append(mRealCallingPid);
                sb.append("; realCallingUidHasAnyVisibleWindow: ")
                        .append(mRealCallingUidHasAnyVisibleWindow);
                sb.append("; realCallingUidProcState: ").append(DebugUtils.valueToString(
                        ActivityManager.class, "PROCESS_STATE_", mRealCallingUidProcState));
                sb.append("; isRealCallingUidPersistentSystemProcess: ")
                        .append(mIsRealCallingUidPersistentSystemProcess);
                sb.append("; originatingPendingIntent: ").append(mOriginatingPendingIntent);
                sb.append("; realCallerApp: ").append(mRealCallerApp);
                if (mRealCallerApp != null) {
                    sb.append("; realInVisibleTask: ")
                            .append(mRealCallerApp.hasActivityInVisibleTask());
                }
                sb.append("; balAllowedByPiSender: ").append(mBalAllowedByPiSender);
                sb.append("; resultIfPiSenderAllowsBal: ").append(mResultForRealCaller);
                sb.append("; realCallerStartMode: ").append(balStartModeToString(
                        mCheckedOptions.getPendingIntentBackgroundActivityStartMode()));
            }
            // features
            sb.append("; balImproveRealCallerVisibilityCheck: ")
                    .append(balImproveRealCallerVisibilityCheck());
            sb.append("; balRequireOptInByPendingIntentCreator: ")
                    .append(balRequireOptInByPendingIntentCreator());
            sb.append("; balRespectAppSwitchStateWhenCheckBoundByForegroundUid: ")
                    .append(balRespectAppSwitchStateWhenCheckBoundByForegroundUid());
            sb.append("; balDontBringExistingBackgroundTaskStackToFg: ")
                    .append(balDontBringExistingBackgroundTaskStackToFg());
            sb.append("]");
            return sb.toString();
        }
    }

    static class BalVerdict {

        static final BalVerdict BLOCK = new BalVerdict(BAL_BLOCK, false, "Blocked");
        static final BalVerdict ALLOW_BY_DEFAULT =
                new BalVerdict(BAL_ALLOW_DEFAULT, false, "Default");
        // Careful using this - it will bypass all ASM checks.
        static final BalVerdict ALLOW_PRIVILEGED =
                new BalVerdict(BAL_ALLOW_ALLOWLISTED_UID, false, "PRIVILEGED");
        private final @BalCode int mCode;
        private final boolean mBackground;
        private final String mMessage;
        private String mProcessInfo;
        // indicates BAL would be blocked because only creator of the PI has the privilege to allow
        // BAL, the sender does not have the privilege to allow BAL.
        private boolean mOnlyCreatorAllows;
        /** indicates that this verdict is based on the real calling UID and not the calling UID */
        private boolean mBasedOnRealCaller;

        BalVerdict(@BalCode int balCode, boolean background, String message) {
            this.mBackground = background;
            this.mCode = balCode;
            this.mMessage = message;
        }

        public BalVerdict withProcessInfo(String msg, WindowProcessController process) {
            mProcessInfo = msg + " (uid=" + process.mUid + ",pid=" + process.getPid() + ")";
            return this;
        }

        boolean blocks() {
            return mCode == BAL_BLOCK;
        }

        boolean allows() {
            return !blocks();
        }

        void setOnlyCreatorAllows(boolean onlyCreatorAllows) {
            mOnlyCreatorAllows = onlyCreatorAllows;
        }

        boolean onlyCreatorAllows() {
            return mOnlyCreatorAllows;
        }

        @VisibleForTesting
        BalVerdict setBasedOnRealCaller() {
            mBasedOnRealCaller = true;
            return this;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(balCodeToString(mCode));
            if (DEBUG_ACTIVITY_STARTS) {
                builder.append(" (");
                if (mBackground) {
                    builder.append("Background ");
                }
                builder.append("Activity start ");
                if (mCode == BAL_BLOCK) {
                    builder.append("denied");
                } else {
                    builder.append("allowed: ").append(mMessage);
                }
                if (mProcessInfo != null) {
                    builder.append(" ");
                    builder.append(mProcessInfo);
                }
                builder.append(")");
            }
            return builder.toString();
        }

        public @BalCode int getRawCode() {
            return mCode;
        }

        public @BalCode int getCode() {
            if (mBasedOnRealCaller && mCode != BAL_BLOCK) {
                // for compatibility always return BAL_ALLOW_PENDING_INTENT if based on real caller
                return BAL_ALLOW_PENDING_INTENT;
            }
            return mCode;
        }
    }

    /**
     * Check if a (background) activity start is allowed.
     *
     * @param callingUid The UID that wants to start the activity.
     * @param callingPid The PID that wants to start the activity.
     * @param callingPackage The package name that wants to start the activity.
     * @param realCallingUid The UID that actually calls this method (only if this handles a
     *      PendingIntent, otherwise -1)
     * @param realCallingPid The PID that actually calls this method (only if this handles a
     *      *      PendingIntent, otherwise -1)
     * @param callerApp The process that calls this method (only if not a PendingIntent)
     * @param originatingPendingIntent PendingIntentRecord that originated this activity start or
     *        null if not originated by PendingIntent
     * @param allowBalExemptionForSystemProcess If set to true, the
     *        PendingIntent's sender will allow additional exemptions.
     *        This is only possible if the sender of the PendingIntent is a system process.
     * @param resultRecord If not null, this indicates that the caller expects a result.
     * @param intent Intent that should be started.
     * @param checkedOptions ActivityOptions to allow specific opt-ins/opt outs.
     *
     * @return A verdict denoting which BAL rule allows an activity to be started,
     *        or if the launch should be blocked.
     */
    BalVerdict checkBackgroundActivityStart(
            int callingUid,
            int callingPid,
            final String callingPackage,
            int realCallingUid,
            int realCallingPid,
            WindowProcessController callerApp,
            PendingIntentRecord originatingPendingIntent,
            boolean allowBalExemptionForSystemProcess,
            ActivityRecord resultRecord,
            Intent intent,
            ActivityOptions checkedOptions) {

        if (checkedOptions == null) {
            // replace null with a constant to simplify evaluation
            checkedOptions = ACTIVITY_OPTIONS_SYSTEM_DEFINED;
        }

        BalState state = new BalState(callingUid, callingPid, callingPackage,
                realCallingUid, realCallingPid, callerApp, originatingPendingIntent,
                allowBalExemptionForSystemProcess, resultRecord, intent, checkedOptions);

        // In the case of an SDK sandbox calling uid, check if the corresponding app uid has a
        // visible window.
        if (Process.isSdkSandboxUid(state.mRealCallingUid)) {
            int realCallingSdkSandboxUidToAppUid =
                    Process.getAppUidForSdkSandboxUid(state.mRealCallingUid);
            // realCallingSdkSandboxUidToAppUid should probably just be used instead (or in addition
            // to realCallingUid when calculating resultForRealCaller below.
            if (mService.hasActiveVisibleWindow(realCallingSdkSandboxUidToAppUid)) {
                state.setResultForRealCaller(new BalVerdict(BAL_ALLOW_SDK_SANDBOX,
                        /*background*/ false,
                        "uid in SDK sandbox has visible (non-toast) window"));
                return allowBasedOnRealCaller(state);
            }
        }

        BalVerdict resultForCaller = checkBackgroundActivityStartAllowedByCaller(state);
        state.setResultForCaller(resultForCaller);

        if (!state.hasRealCaller()) {
            if (resultForCaller.allows()) {
                return allowBasedOnCaller(state);
            }
            return abortLaunch(state);
        }

        // The realCaller result is only calculated for PendingIntents (indicated by a valid
        // realCallingUid). If caller and realCaller are same UID and we are already allowed based
        // on the caller (i.e. creator of the PendingIntent) there is no need to calculate this
        // again, but if the result is block it is possible that there are additional exceptions
        // that allow based on the realCaller (i.e. sender of the PendingIntent), e.g. if the
        // realCallerApp process is allowed to start (in the creator path the callerApp for
        // PendingIntents is null).
        BalVerdict resultForRealCaller = state.callerIsRealCaller() && resultForCaller.allows()
                ? resultForCaller
                : checkBackgroundActivityStartAllowedByRealCaller(state)
                        .setBasedOnRealCaller();
        state.setResultForRealCaller(resultForRealCaller);

        if (state.isPendingIntent()) {
            resultForCaller.setOnlyCreatorAllows(
                    resultForCaller.allows() && resultForRealCaller.blocks());
        }

        // Handle cases with explicit opt-in
        if (resultForCaller.allows() && state.callerExplicitOptInOrAutoOptIn()) {
            return allowBasedOnCaller(state);
        }
        if (resultForRealCaller.allows() && state.realCallerExplicitOptInOrAutoOptIn()) {
            return allowBasedOnRealCaller(state);
        }
        // Handle PendingIntent cases with default behavior next
        boolean callerCanAllow = resultForCaller.allows() && !state.callerExplicitOptOut();
        boolean realCallerCanAllow = resultForRealCaller.allows()
                && !state.realCallerExplicitOptOut();
        if (callerCanAllow) {
            // Allowed before V by creator
            if (state.mBalAllowedByPiCreator.allowsBackgroundActivityStarts()) {
                Slog.wtf(TAG, "With Android 15 BAL hardening this activity start may be blocked"
                        + " if the PI creator upgrades target_sdk to 35+!"
                        + " goo.gle/android-bal"
                        + " (missing opt in by PI creator)!" + state);
                return allowBasedOnCaller(state);
            }
        }
        if (realCallerCanAllow) {
            // Allowed before U by sender
            if (state.mBalAllowedByPiSender.allowsBackgroundActivityStarts()) {
                Slog.wtf(TAG, "With Android 14 BAL hardening this activity start will be blocked"
                        + " if the PI sender upgrades target_sdk to 34+! "
                        + " goo.gle/android-bal"
                        + " (missing opt in by PI sender)!" + state);
                return allowBasedOnRealCaller(state);
            }
        }
        // caller or real caller could start the activity, but would need to explicitly opt in
        if (callerCanAllow || realCallerCanAllow) {
            Slog.w(TAG, "Without BAL hardening this activity start would be allowed");
        }
        // neither the caller not the realCaller can allow or have explicitly opted out
        return abortLaunch(state);
    }

    private BalVerdict allowBasedOnCaller(BalState state) {
        if (DEBUG_ACTIVITY_STARTS) {
            Slog.d(TAG, "Background activity launch allowed based on caller. " + state);
        }
        return statsLog(state.mResultForCaller, state);
    }

    private BalVerdict allowBasedOnRealCaller(BalState state) {
        if (DEBUG_ACTIVITY_STARTS) {
            Slog.d(TAG, "Background activity launch allowed based on real caller. " + state);
        }
        return statsLog(state.mResultForRealCaller, state);
    }

    private BalVerdict abortLaunch(BalState state) {
        Slog.wtf(TAG, "Background activity launch blocked! goo.gle/android-bal "
                + state);
        if (balShowToastsBlocked()
                && (state.mResultForCaller.allows() || state.mResultForRealCaller.allows())) {
            // only show a toast if either caller or real caller could launch if they opted in
            showToast("BAL blocked. goo.gle/android-bal");
        }
        BalVerdict verdict = statsLog(BalVerdict.BLOCK, state);
        if (balStrictModeRo()) {
            String abortDebugMessage;
            if (state.isPendingIntent()) {
                abortDebugMessage =
                        "PendingIntent Activity start blocked in " + state.mRealCallingPackage
                                + ". "
                                + "PendingIntent was created in " + state.mCallingPackage
                                + ". "
                                + (state.mResultForRealCaller.allows()
                                ? state.mRealCallingPackage
                                + " could opt in to grant BAL privileges when sending. "
                                : "")
                                + (state.mResultForCaller.allows()
                                ? state.mCallingPackage
                                + " could opt in to grant BAL privileges when creating."
                                : "")
                                + "The intent would have started " + state.mIntent.getComponent();
            } else {
                abortDebugMessage = "Activity start blocked. "
                        + "The intent would have started " + state.mIntent.getComponent();
            }
            strictModeLaunchAborted(state.mCallingUid, abortDebugMessage);
            if (!state.callerIsRealCaller()) {
                strictModeLaunchAborted(state.mRealCallingUid, abortDebugMessage);
            }
        }
        return verdict;
    }

    /**
     * Retrieve a registered strict mode callback for BAL.
     * @param uid the uid of the app.
     * @return the callback if it exists, returns <code>null</code> otherwise.
     */
    @Nullable
    Map<IBinder, IBackgroundActivityLaunchCallback> getStrictModeBalCallbacks(int uid) {
        ArrayMap<IBinder, IBackgroundActivityLaunchCallback> callbackMap;
        synchronized (mStrictModeBalCallbacks) {
            callbackMap =
                    mStrictModeBalCallbacks.get(uid);
            if (callbackMap == null) {
                return null;
            }
            return new ArrayMap<>(callbackMap);
        }
    }

    /**
     * Add strict mode callback for BAL.
     *
     * @param uid      the UID for which the binder is registered.
     * @param callback the {@link IBackgroundActivityLaunchCallback} binder to call when BAL is
     *                 blocked.
     * @return {@code true} if the callback has been successfully added.
     */
    boolean addStrictModeCallback(int uid, IBinder callback) {
        IBackgroundActivityLaunchCallback balCallback =
                IBackgroundActivityLaunchCallback.Stub.asInterface(callback);
        synchronized (mStrictModeBalCallbacks) {
            ArrayMap<IBinder, IBackgroundActivityLaunchCallback> callbackMap =
                    mStrictModeBalCallbacks.get(uid);
            if (callbackMap == null) {
                callbackMap = new ArrayMap<>();
                mStrictModeBalCallbacks.put(uid, callbackMap);
            }
            if (callbackMap.containsKey(callback)) {
                return false;
            }
            callbackMap.put(callback, balCallback);
        }
        try {
            callback.linkToDeath(() -> removeStrictModeCallback(uid, callback), 0);
        } catch (RemoteException e) {
            removeStrictModeCallback(uid, callback);
        }
        return true;
    }

    /**
     * Remove strict mode callback for BAL.
     *
     * @param uid      the UID for which the binder is registered.
     * @param callback the {@link IBackgroundActivityLaunchCallback} binder to call when BAL is
     *                 blocked.
     */
    void removeStrictModeCallback(int uid, IBinder callback) {
        synchronized (mStrictModeBalCallbacks) {
            Map<IBinder, IBackgroundActivityLaunchCallback> callbackMap =
                    mStrictModeBalCallbacks.get(uid);
            if (callback == null || !callbackMap.containsKey(callback)) {
                return;
            }
            callbackMap.remove(callback);
            if (callbackMap.isEmpty()) {
                mStrictModeBalCallbacks.remove(uid);
            }
        }
    }

    private void strictModeLaunchAborted(int callingUid, String message) {
        Map<IBinder, IBackgroundActivityLaunchCallback> strictModeBalCallbacks =
                getStrictModeBalCallbacks(callingUid);
        if (strictModeBalCallbacks == null) {
            return;
        }
        for (Map.Entry<IBinder, IBackgroundActivityLaunchCallback> callbackEntry :
                strictModeBalCallbacks.entrySet()) {
            try {
                callbackEntry.getValue().onBackgroundActivityLaunchAborted(message);
            } catch (RemoteException e) {
                removeStrictModeCallback(callingUid, callbackEntry.getKey());
            }
        }
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByCaller(BalState state) {
        if (state.isPendingIntent()) {
            // PendingIntents should mostly be allowed by the sender (real caller) or a permission
            // the creator of the PendingIntent has. Visibility should be the exceptional case, so
            // test it last (this does not change the result, just the bal code).
            BalVerdict result = BalVerdict.BLOCK;
            if (!(balAdditionalStartModes()
                    && state.mCheckedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)) {
                result = checkBackgroundActivityStartAllowedByCallerInBackground(state);
            }
            if (result == BalVerdict.BLOCK) {
                result = checkBackgroundActivityStartAllowedByCallerInForeground(state);

            }
            return result;
        } else {
            BalVerdict result = checkBackgroundActivityStartAllowedByCallerInForeground(state);
            if (result == BalVerdict.BLOCK && !(balAdditionalStartModes()
                    && state.mCheckedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                    == MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)) {
                result = checkBackgroundActivityStartAllowedByCallerInBackground(state);
            }
            return result;
        }
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByCallerInForeground(BalState state) {
        // This is used to block background activity launch even if the app is still
        // visible to user after user clicking home button.

        // Normal apps with visible app window will be allowed to start activity if app switching
        // is allowed, or apps like live wallpaper with non app visible window will be allowed.
        final boolean appSwitchAllowedOrFg = state.mAppSwitchState == APP_SWITCH_ALLOW
                || state.mAppSwitchState == APP_SWITCH_FG_ONLY;
        if (appSwitchAllowedOrFg && state.mCallingUidHasAnyVisibleWindow) {
            return new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW,
                    /*background*/ false, "callingUid has visible window");
        }
        if (mService.mActiveUids.hasNonAppVisibleWindow(state.mCallingUid)) {
            return new BalVerdict(BAL_ALLOW_NON_APP_VISIBLE_WINDOW,
                    /*background*/ false, "callingUid has non-app visible window");
        }
        // Don't abort if the callerApp or other processes of that uid are considered to be in the
        // foreground.
        return checkProcessAllowsBal(state.mCallerApp, state, BAL_CHECK_FOREGROUND);
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByCallerInBackground(BalState state) {
        // don't abort for the most important UIDs
        final int callingAppId = UserHandle.getAppId(state.mCallingUid);
        if (state.mCallingUid == Process.ROOT_UID
                || callingAppId == Process.SYSTEM_UID
                || callingAppId == Process.NFC_UID) {
            return new BalVerdict(
                    BAL_ALLOW_ALLOWLISTED_UID, /*background*/ false,
                     "Important callingUid");
        }

        // Always allow home application to start activities.
        if (isHomeApp(state.mCallingUid, state.mCallingPackage)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ false,
                    "Home app");
        }

        // IME should always be allowed to start activity, like IME settings.
        final WindowState imeWindow =
                mService.mRootWindowContainer.getCurrentInputMethodWindow();
        if (imeWindow != null && callingAppId == imeWindow.mOwnerUid) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ false,
                    "Active ime");
        }

        // don't abort if the callingUid is a persistent system process
        if (state.mIsCallingUidPersistentSystemProcess) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ false, "callingUid is persistent system process");
        }

        // don't abort if the callingUid has START_ACTIVITIES_FROM_BACKGROUND permission
        if (hasBalPermission(state.mCallingUid, state.mCallingPid)) {
            return new BalVerdict(BAL_ALLOW_PERMISSION,
                    /*background*/ true,
                    "START_ACTIVITIES_FROM_BACKGROUND permission granted");
        }
        // don't abort if the caller has the same uid as the recents component
        if (mSupervisor.mRecentTasks.isCallerRecents(state.mCallingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Recents Component");
        }
        // don't abort if the callingUid is the device owner
        if (mService.isDeviceOwner(state.mCallingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Device Owner");
        }
        // don't abort if the callingUid is a affiliated profile owner
        if (mService.isAffiliatedProfileOwner(state.mCallingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Affiliated Profile Owner");
        }
        // don't abort if the callingUid has companion device
        final int callingUserId = UserHandle.getUserId(state.mCallingUid);
        if (mService.isAssociatedCompanionApp(callingUserId, state.mCallingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Companion App");
        }
        // don't abort if the callingUid has SYSTEM_ALERT_WINDOW permission
        if (mService.hasSystemAlertWindowPermission(state.mCallingUid, state.mCallingPid,
                state.mCallingPackage)) {
            Slog.w(
                    TAG,
                    "Background activity start for "
                            + state.mCallingPackage
                            + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
            return new BalVerdict(BAL_ALLOW_SAW_PERMISSION,
                    /*background*/ true, "SYSTEM_ALERT_WINDOW permission is granted");
        }
        // don't abort if the callingUid and callingPackage have the
        // OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop
        if (isSystemExemptFlagEnabled() && mService.getAppOpsManager().checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION,
                state.mCallingUid, state.mCallingPackage) == AppOpsManager.MODE_ALLOWED) {
            return new BalVerdict(BAL_ALLOW_PERMISSION, /*background*/ true,
                    "OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop is granted");
        }

        // Don't abort if the callerApp or other processes of that uid are allowed in any way.
        return checkProcessAllowsBal(state.mCallerApp, state, BAL_CHECK_BACKGROUND);
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByRealCaller(BalState state) {
        BalVerdict result = checkBackgroundActivityStartAllowedByRealCallerInForeground(state);
        if (result == BalVerdict.BLOCK && !(balAdditionalStartModes()
                && state.mCheckedOptions.getPendingIntentBackgroundActivityStartMode()
                == MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)) {
            result = checkBackgroundActivityStartAllowedByRealCallerInBackground(state);
        }
        return result;
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByRealCallerInForeground(BalState state) {
        // Normal apps with visible app window will be allowed to start activity if app switching
        // is allowed, or apps like live wallpaper with non app visible window will be allowed.
        // The home app can start apps even if app switches are usually disallowed.
        final boolean appSwitchAllowedOrFg = state.mAppSwitchState == APP_SWITCH_ALLOW
                || state.mAppSwitchState == APP_SWITCH_FG_ONLY
                || isHomeApp(state.mRealCallingUid, state.mRealCallingPackage);
        if (balImproveRealCallerVisibilityCheck()) {
            if (appSwitchAllowedOrFg && state.mRealCallingUidHasAnyVisibleWindow) {
                return new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW,
                        /*background*/ false, "realCallingUid has visible window");
            }
            if (mService.mActiveUids.hasNonAppVisibleWindow(state.mRealCallingUid)) {
                return new BalVerdict(BAL_ALLOW_NON_APP_VISIBLE_WINDOW,
                        /*background*/ false, "realCallingUid has non-app visible window");
            }
        } else {
            // don't abort if the realCallingUid has a visible window
            // TODO(b/171459802): We should check appSwitchAllowed also
            if (state.mRealCallingUidHasAnyVisibleWindow) {
                return new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW,
                        /*background*/ false,
                        "realCallingUid has visible (non-toast) window.");
            }
        }

        // Don't abort if the realCallerApp or other processes of that uid are considered to be in
        // the foreground.
        return checkProcessAllowsBal(state.mRealCallerApp, state, BAL_CHECK_FOREGROUND);
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByRealCallerInBackground(BalState state) {
        boolean allowAlways = state.mCheckedOptions.getPendingIntentBackgroundActivityStartMode()
                == MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS;
        if (allowAlways
                && hasBalPermission(state.mRealCallingUid, state.mRealCallingPid)) {
            return new BalVerdict(BAL_ALLOW_PERMISSION,
                    /*background*/ false,
                    "realCallingUid has BAL permission.");
        }

        // don't abort if the realCallingUid has SYSTEM_ALERT_WINDOW permission
        Slog.i(TAG, "hasSystemAlertWindowPermission(" + state.mRealCallingUid + ", "
                + state.mRealCallingPid + ", " + state.mRealCallingPackage + ") "
                + balStartModeToString(
                state.mCheckedOptions.getPendingIntentBackgroundActivityStartMode()));
        if (allowAlways
                && mService.hasSystemAlertWindowPermission(state.mRealCallingUid,
                state.mRealCallingPid, state.mRealCallingPackage)) {
            Slog.w(
                    TAG,
                    "Background activity start for "
                            + state.mRealCallingPackage
                            + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
            return new BalVerdict(BAL_ALLOW_SAW_PERMISSION,
                    /*background*/ true, "SYSTEM_ALERT_WINDOW permission is granted");
        }

        // if the realCallingUid is a persistent system process, abort if the IntentSender
        // wasn't allowed to start an activity
        if ((allowAlways || state.mAllowBalExemptionForSystemProcess)
                && state.mIsRealCallingUidPersistentSystemProcess) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_UID,
                    /*background*/ false,
                    "realCallingUid is persistent system process AND intent "
                            + "sender forced to allow.");
        }
        // don't abort if the realCallingUid is an associated companion app
        if (mService.isAssociatedCompanionApp(
                UserHandle.getUserId(state.mRealCallingUid), state.mRealCallingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ false,
                    "realCallingUid is a companion app.");
        }

        // don't abort if the callerApp or other processes of that uid are allowed in any way
        return checkProcessAllowsBal(state.mRealCallerApp, state, BAL_CHECK_BACKGROUND);
    }

    @VisibleForTesting boolean hasBalPermission(int uid, int pid) {
        return ActivityTaskManagerService.checkPermission(START_ACTIVITIES_FROM_BACKGROUND,
                pid, uid) == PERMISSION_GRANTED;
    }

    /**
     * Check if the app allows BAL.
     * <p>
     * See {@link BackgroundLaunchProcessController#areBackgroundActivityStartsAllowed(int, int,
     * String, int, boolean, boolean, boolean, long, long, long)} for details on the
     * exceptions.
     */
    @VisibleForTesting BalVerdict checkProcessAllowsBal(WindowProcessController app,
            BalState state, BalCheckConfiguration balCheckConfiguration) {
        if (app == null) {
            return BalVerdict.BLOCK;
        }
        // first check the original calling process
        final BalVerdict balAllowedForCaller = app
                .areBackgroundActivityStartsAllowed(state.mAppSwitchState, balCheckConfiguration);
        if (balAllowedForCaller.allows()) {
            return balAllowedForCaller.withProcessInfo("callerApp process", app);
        } else {
            // only if that one wasn't allowed, check the other ones
            final ArraySet<WindowProcessController> uidProcesses =
                    mService.mProcessMap.getProcesses(app.mUid);
            if (uidProcesses != null) {
                for (int i = uidProcesses.size() - 1; i >= 0; i--) {
                    final WindowProcessController proc = uidProcesses.valueAt(i);
                    if (proc != app) {
                        BalVerdict balAllowedForUid = proc.areBackgroundActivityStartsAllowed(
                                state.mAppSwitchState, balCheckConfiguration);
                        if (balAllowedForUid.allows()) {
                            return balAllowedForUid.withProcessInfo("process", proc);
                        }
                    }
                }
            }
        }
        return BalVerdict.BLOCK;
    }

    /**
     * Check activity starts which violate one of the following rules of the
     * activity security model (ASM):
     * See go/activity-security for rationale behind the rules.
     * 1. Within a task, only an activity matching a top UID of the task can start activities
     * 2. Only activities within a foreground task, which match a top UID of the task, can
     * create a new task or bring an existing one into the foreground
     */
    boolean checkActivityAllowedToStart(@Nullable ActivityRecord sourceRecord,
            @NonNull ActivityRecord targetRecord, boolean newTask, boolean avoidMoveTaskToFront,
            @Nullable Task targetTask, int launchFlags, int balCode, int callingUid,
            int realCallingUid, TaskDisplayArea preferredTaskDisplayArea) {
        // BAL Exception allowed in all cases
        if (balCode == BAL_ALLOW_ALLOWLISTED_UID
                || (android.security.Flags.asmReintroduceGracePeriod()
                    && balCode == BAL_ALLOW_GRACE_PERIOD)) {
            return true;
        }

        // Intents with FLAG_ACTIVITY_NEW_TASK will always be considered as creating a new task
        // even if the intent is delivered to an existing task.
        boolean taskToFront = newTask
                || (launchFlags & FLAG_ACTIVITY_NEW_TASK) == FLAG_ACTIVITY_NEW_TASK;

        // BAL exception only allowed for new tasks
        if (taskToFront) {
            if (balCode == BAL_ALLOW_ALLOWLISTED_COMPONENT
                    || balCode == BAL_ALLOW_PERMISSION
                    || balCode == BAL_ALLOW_PENDING_INTENT
                    || balCode == BAL_ALLOW_SAW_PERMISSION
                    || balCode == BAL_ALLOW_VISIBLE_WINDOW
                    || balCode == BAL_ALLOW_NON_APP_VISIBLE_WINDOW
                    || balCode == BAL_ALLOW_TOKEN
                    || balCode == BAL_ALLOW_BOUND_BY_FOREGROUND) {
                return true;
            }
        }

        BlockActivityStart bas = new BlockActivityStart();
        if (sourceRecord != null) {
            Task sourceTask = sourceRecord.getTask();

            Task taskToCheck = taskToFront ? sourceTask : targetTask;
            bas = checkTopActivityForAsm(taskToCheck, sourceRecord.getUid(),
                    sourceRecord, bas);

            // Allow launching into a new task (or a task matching the launched activity's
            // affinity) only if the current task is foreground or mutating its own task.
            // The latter can happen eg. if caller uses NEW_TASK flag and the activity being
            // launched matches affinity of source task.
            if (taskToFront && bas.mTopActivityMatchesSource) {
                bas.mTopActivityMatchesSource = (sourceTask != null
                        && (sourceTask.isVisible() || sourceTask == targetTask));
            }
        } else if (targetTask != null && (!taskToFront || avoidMoveTaskToFront)) {
            // We don't have a sourceRecord, and we're launching into an existing task.
            // Allow if callingUid is top of stack.
            bas = checkTopActivityForAsm(targetTask, callingUid,
                    /*sourceRecord*/null, bas);
        } else {
            // We're launching from a non-visible activity. Has any visible app opted in?
            TaskDisplayArea displayArea = targetTask != null && targetTask.getDisplayArea() != null
                    ? targetTask.getDisplayArea()
                    : preferredTaskDisplayArea;
            if (displayArea != null) {
                ArrayList<Task> visibleTasks = displayArea.getVisibleTasks();
                for (int i = 0; i < visibleTasks.size(); i++) {
                    Task task = visibleTasks.get(i);
                    if (android.security.Flags.asmReintroduceGracePeriod()) {
                        bas = checkTopActivityForAsm(task, callingUid, /*sourceRecord*/null, bas);
                    } else {
                        if (visibleTasks.size() == 1 && task.isActivityTypeHomeOrRecents()) {
                            bas.optedIn(task.getTopMostActivity());
                        } else {
                            bas = checkTopActivityForAsm(
                                task, callingUid, /*sourceRecord*/null, bas);
                        }
                    }
                }
            }
        }

        if (bas.mTopActivityMatchesSource) {
            return true;
        }

        // ASM rules have failed. Log why
        return logAsmFailureAndCheckFeatureEnabled(sourceRecord, callingUid, realCallingUid,
                newTask, avoidMoveTaskToFront, targetTask, targetRecord, balCode, launchFlags,
                bas, taskToFront);
    }

    private boolean logAsmFailureAndCheckFeatureEnabled(ActivityRecord sourceRecord, int callingUid,
            int realCallingUid, boolean newTask, boolean avoidMoveTaskToFront, Task targetTask,
            ActivityRecord targetRecord, @BalCode int balCode, int launchFlags,
            BlockActivityStart bas, boolean taskToFront) {

        ActivityRecord targetTopActivity = targetTask == null ? null
                : targetTask.getActivity(ar -> !ar.finishing && !ar.isAlwaysOnTop());

        int action = newTask || sourceRecord == null
                ? FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__ACTIVITY_START_NEW_TASK
                : (sourceRecord.getTask().equals(targetTask)
                ? FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__ACTIVITY_START_SAME_TASK
                : FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__ACTIVITY_START_DIFFERENT_TASK);

        boolean enforceBlock = bas.mTopActivityOptedIn
                && ActivitySecurityModelFeatureFlags.shouldRestrictActivitySwitch(callingUid);

        boolean allowedByGracePeriod = allowedByAsmGracePeriod(callingUid, sourceRecord, targetTask,
                balCode, taskToFront, avoidMoveTaskToFront);

        String asmDebugInfo = getDebugInfoForActivitySecurity("Launch", sourceRecord,
                targetRecord, targetTask, targetTopActivity, realCallingUid, balCode,
                enforceBlock, taskToFront, avoidMoveTaskToFront, allowedByGracePeriod,
                bas.mActivityOptedIn);

        FrameworkStatsLog.write(FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED,
                /* caller_uid */
                sourceRecord != null ? sourceRecord.getUid() : callingUid,
                /* caller_activity_class_name */
                sourceRecord != null ? sourceRecord.info.name : null,
                /* target_task_top_activity_uid */
                targetTopActivity != null ? targetTopActivity.getUid() : NO_PROCESS_UID,
                /* target_task_top_activity_class_name */
                targetTopActivity != null ? targetTopActivity.info.name : null,
                /* target_task_is_different */
                newTask || sourceRecord == null || targetTask == null
                        || !targetTask.equals(sourceRecord.getTask()),
                /* target_activity_uid */
                targetRecord.getUid(),
                /* target_activity_class_name */
                targetRecord.info.name,
                /* target_intent_action */
                targetRecord.intent.getAction(),
                /* target_intent_flags */
                launchFlags,
                /* action */
                action,
                /* version */
                ActivitySecurityModelFeatureFlags.ASM_VERSION,
                /* multi_window - we have our source not in the target task, but both are visible */
                targetTask != null && sourceRecord != null
                        && !targetTask.equals(sourceRecord.getTask()) && targetTask.isVisible(),
                /* bal_code */
                balCode,
                /* debug_info */
                asmDebugInfo
        );

        String launchedFromPackageName = targetRecord.launchedFromPackage;
        if (ActivitySecurityModelFeatureFlags.shouldShowToast(callingUid)) {
            String toastText = ActivitySecurityModelFeatureFlags.DOC_LINK
                    + (enforceBlock ? " blocked " : " would block ")
                    + getApplicationLabel(mService.mContext.getPackageManager(),
                    launchedFromPackageName);
            showToast(toastText);

            Slog.i(TAG, asmDebugInfo);
        }

        if (enforceBlock) {
            Slog.e(TAG, "[ASM] Abort Launching r: " + targetRecord
                    + " as source: "
                    + (sourceRecord != null ? sourceRecord : launchedFromPackageName)
                    + " is in background. New task: " + newTask
                    + ". Top activity: " + targetTopActivity
                    + ". BAL Code: " + balCodeToString(balCode));

            return false;
        }

        return true;
    }

    @VisibleForTesting void showToast(String toastText) {
        UiThread.getHandler().post(() -> Toast.makeText(mService.mContext,
                toastText, Toast.LENGTH_LONG).show());
    }

    /**
     * If the top activity uid does not match the launching or launched activity, and the launch was
     * not requested from the top uid, we want to clear out all non matching activities to prevent
     * the top activity being sandwiched.
     * Both creator and sender UID are considered for the launching activity.
     */
    void clearTopIfNeeded(@NonNull Task targetTask, @Nullable ActivityRecord sourceRecord,
            @NonNull ActivityRecord targetRecord, int callingUid, int realCallingUid,
            int launchFlags, @BalCode int balCode) {
        if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) != FLAG_ACTIVITY_NEW_TASK
                || balCode == BAL_ALLOW_ALLOWLISTED_UID) {
            // Launch is from the same task, (a top or privileged UID), or is directly privileged.
            return;
        }

        int startingUid = targetRecord.getUid();
        Predicate<ActivityRecord> isLaunchingOrLaunched = ar ->
                ar.isUid(startingUid) || ar.isUid(callingUid) || ar.isUid(realCallingUid);

        // Return early if we know for sure we won't need to clear any activities by just checking
        // the top activity.
        ActivityRecord targetTaskTop = targetTask.getTopMostActivity();
        if (targetTaskTop == null || isLaunchingOrLaunched.test(targetTaskTop)) {
            return;
        }

        // Find the first activity which matches a safe UID and is not finishing. Clear everything
        // above it
        int[] finishCount = new int[1];
        boolean shouldBlockActivityStart = ActivitySecurityModelFeatureFlags
                .shouldRestrictActivitySwitch(callingUid);
        BlockActivityStart bas = checkCrossUidActivitySwitchFromBelow(
                targetTaskTop, callingUid, new BlockActivityStart());
        if (shouldBlockActivityStart && bas.mTopActivityOptedIn) {
            ActivityRecord activity = targetTask.getActivity(isLaunchingOrLaunched);
            if (activity == null) {
                // mStartActivity is not in task, so clear everything
                activity = targetRecord;
            }

            targetTask.performClearTop(activity, launchFlags, finishCount);
            if (finishCount[0] > 0) {
                Slog.w(TAG, "Cleared top n: " + finishCount[0] + " activities from task t: "
                        + targetTask + " not matching top uid: " + callingUid);
            }
        }

        if (ActivitySecurityModelFeatureFlags.shouldShowToast(callingUid)
                && (!shouldBlockActivityStart || finishCount[0] > 0)) {
            showToast((shouldBlockActivityStart
                    ? "Top activities cleared by "
                    : "Top activities would be cleared by ")
                    + ActivitySecurityModelFeatureFlags.DOC_LINK);

            Slog.i(TAG, getDebugInfoForActivitySecurity("Clear Top", sourceRecord, targetRecord,
                    targetTask, targetTaskTop, realCallingUid, balCode, shouldBlockActivityStart,
                    /* taskToFront */ true, /* avoidMoveTaskToFront */ false,
                    /* allowedByAsmGracePeriod */ false, bas.mActivityOptedIn));
        }
    }

    /**
     * Returns home if the passed in callingUid is not top of the stack, rather than returning to
     * previous task.
     */
    void checkActivityAllowedToClearTask(@NonNull Task task, int callingUid, int callingPid,
            @NonNull String callerActivityClassName) {
        // We may have already checked that the callingUid has additional clearTask privileges, and
        // cleared the calling identify. If so, we infer we do not need further restrictions here.
        if (callingUid == SYSTEM_UID || !task.isVisible() || task.inMultiWindowMode()) {
            return;
        }

        String packageName =  mService.mContext.getPackageManager().getNameForUid(callingUid);
        BalState state = new BalState(callingUid, callingPid, packageName, INVALID_UID,
                INVALID_PID, null, null, false, null, null, ActivityOptions.makeBasic());
        @BalCode int balCode = checkBackgroundActivityStartAllowedByCaller(state).mCode;
        if (balCode == BAL_ALLOW_ALLOWLISTED_UID
                || balCode == BAL_ALLOW_ALLOWLISTED_COMPONENT
                || balCode == BAL_ALLOW_PERMISSION
                || balCode == BAL_ALLOW_SAW_PERMISSION
                || balCode == BAL_ALLOW_VISIBLE_WINDOW
                || balCode == BAL_ALLOW_NON_APP_VISIBLE_WINDOW
                || balCode == BAL_ALLOW_BOUND_BY_FOREGROUND) {
            return;
        }

        TaskDisplayArea displayArea = task.getTaskDisplayArea();
        if (displayArea == null) {
            // If there is no associated display area, we can not return home.
            return;
        }

        BlockActivityStart bas = checkTopActivityForAsm(task, callingUid, null,
                new BlockActivityStart());
        if (bas.mTopActivityMatchesSource) {
            return;
        }

        ActivityRecord topActivity = task.getActivity(ar -> !ar.finishing && !ar.isAlwaysOnTop());
        FrameworkStatsLog.write(FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED,
                /* caller_uid */
                callingUid,
                /* caller_activity_class_name */
                callerActivityClassName,
                /* target_task_top_activity_uid */
                topActivity == null ? NO_PROCESS_UID : topActivity.getUid(),
                /* target_task_top_activity_class_name */
                topActivity == null ? null : topActivity.info.name,
                /* target_task_is_different */
                false,
                /* target_activity_uid */
                NO_PROCESS_UID,
                /* target_activity_class_name */
                null,
                /* target_intent_action */
                null,
                /* target_intent_flags */
                0,
                /* action */
                FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__FINISH_TASK,
                /* version */
                ActivitySecurityModelFeatureFlags.ASM_VERSION,
                /* multi_window */
                false,
                /* bal_code */
                -1,
                /* debug_info */
                null
        );

        boolean restrictActivitySwitch = ActivitySecurityModelFeatureFlags
                .shouldRestrictActivitySwitch(callingUid) && bas.mTopActivityOptedIn;

        PackageManager pm = mService.mContext.getPackageManager();
        String callingPackage = pm.getNameForUid(callingUid);
        final CharSequence callingLabel;
        if (callingPackage == null) {
            callingPackage = String.valueOf(callingUid);
            callingLabel = callingPackage;
        } else {
            callingLabel = getApplicationLabel(pm, callingPackage);
        }

        if (ActivitySecurityModelFeatureFlags.shouldShowToast(callingUid)) {
            showToast((ActivitySecurityModelFeatureFlags.DOC_LINK
                    + (restrictActivitySwitch ? " returned home due to "
                    : " would return home due to ")
                    + callingLabel));
        }

        // If the activity switch should be restricted, return home rather than the
        // previously top task, to prevent users from being confused which app they're
        // viewing
        if (restrictActivitySwitch) {
            Slog.w(TAG, "[ASM] Return to home as source: " + callingPackage
                    + " is not on top of task t: " + task);
            displayArea.moveHomeActivityToTop("taskRemoved");
        } else {
            Slog.i(TAG, "[ASM] Would return to home as source: " + callingPackage
                    + " is not on top of task t: " + task);
        }
    }

    /**
     * For the purpose of ASM, ‘Top UID” for a task is defined as an activity UID
     * 1. Which is top of the stack in z-order
     * a. Excluding any activities with the flag ‘isAlwaysOnTop’ and
     * b. Excluding any activities which are `finishing`
     * 2. Or top of an adjacent task fragment to (1)
     * <p>
     * The 'sourceRecord' can be considered top even if it is 'finishing'
     * <p>
     */
    private BlockActivityStart checkTopActivityForAsm(@NonNull Task task,
            int uid, @Nullable ActivityRecord sourceRecord, BlockActivityStart bas) {
        // If the source is visible, consider it 'top'.
        if (sourceRecord != null && sourceRecord.isVisibleRequested()) {
            return bas.matchesSource();
        }

        // Always allow actual top activity
        ActivityRecord topActivity = task.getTopMostActivity();
        if (topActivity == null) {
            Slog.wtf(TAG, "Activities for task: " + task + " not found.");
            return bas.optedIn(topActivity);
        }

        bas = checkCrossUidActivitySwitchFromBelow(topActivity, uid, bas);
        if (bas.mTopActivityMatchesSource) {
            return bas;
        }

        // If UID is visible in target task, allow launch
        if (task.forAllActivities((Predicate<ActivityRecord>)
                ar -> ar.isUid(uid) && ar.isVisibleRequested())) {
            return bas.matchesSource();
        }

        // Consider the source activity, whether or not it is finishing. Do not consider any other
        // finishing activity.
        Predicate<ActivityRecord> topOfStackPredicate = (ar) -> ar.equals(sourceRecord)
                || (!ar.finishing && !ar.isAlwaysOnTop());

        // Check top of stack (or the first task fragment for embedding).
        topActivity = task.getActivity(topOfStackPredicate);
        if (topActivity == null) {
            return bas;
        }

        bas = checkCrossUidActivitySwitchFromBelow(topActivity, uid, bas);
        if (bas.mTopActivityMatchesSource) {
            return bas;
        }

        // Even if the top activity is not a match, we may be in an embedded activity scenario with
        // an adjacent task fragment. Get the second fragment.
        TaskFragment taskFragment = topActivity.getTaskFragment();
        if (taskFragment == null) {
            return bas;
        }

        TaskFragment adjacentTaskFragment = taskFragment.getAdjacentTaskFragment();
        if (adjacentTaskFragment == null) {
            return bas;
        }

        // Check the second fragment.
        topActivity = adjacentTaskFragment.getActivity(topOfStackPredicate);
        if (topActivity == null) {
            return bas;
        }

        return checkCrossUidActivitySwitchFromBelow(topActivity, uid, bas);
    }

    /**
     * Determines if a source is allowed to add or remove activities from the task,
     * if the current ActivityRecord is above it in the stack
     * <p>
     * A transition is blocked if all of the following are met:
     * <pre>
     * 1. The source activity and the current activity record belong to different apps
     * (i.e, have different UIDs).
     * 2. The current activity target V+
     * 3. The current app has set
     * {@link R.styleable#AndroidManifestApplication_allowCrossUidActivitySwitchFromBelow}
     * to {@code false}
     * 4. The current activity has not set
     * {@link ActivityRecord#setAllowCrossUidActivitySwitchFromBelow(boolean)} to {@code true}
     * </pre>
     *
     *
     * @param sourceUid The source (s) activity performing the state change
     */
    private BlockActivityStart checkCrossUidActivitySwitchFromBelow(ActivityRecord ar,
            int sourceUid, BlockActivityStart bas) {
        if (ar.isUid(sourceUid)) {
            return bas.matchesSource();
        }

        // We don't need to check package level if activity has opted out.
        if (ar.mAllowCrossUidActivitySwitchFromBelow) {
            bas.mTopActivityOptedIn = false;
            return bas.matchesSource();
        }

        if (ar.isUid(SYSTEM_UID)) {
            if (asmOptSystemIntoEnforcement()) {
                return bas.optedIn(ar);
            } else {
                return bas;
            }
        }

        if (!CompatChanges.isChangeEnabled(ASM_RESTRICTIONS, ar.getUid())) {
            return bas;
        }


        String packageName = ar.packageName;
        if (packageName == null) {
            Slog.wtf(TAG, "Package name: " + ar + " not found.");
            return bas.optedIn(ar);
        }

        PackageManager pm = mService.mContext.getPackageManager();
        ApplicationInfo applicationInfo;

        final int sourceUserId = UserHandle.getUserId(sourceUid);
        try {
            applicationInfo = pm.getApplicationInfoAsUser(packageName, /* flags= */ 0,
                    sourceUserId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.wtf(TAG, "Package name: " + packageName + " not found for user "
                    + sourceUserId);
            return bas.optedIn(ar);
        }

        return applicationInfo.allowCrossUidActivitySwitchFromBelow ? bas : bas.optedIn(ar);
    }

    /**
     * Only called when an activity launch may be blocked, which should happen very rarely
     */
    private String getDebugInfoForActivitySecurity(@NonNull String action,
            @Nullable ActivityRecord sourceRecord, @NonNull ActivityRecord targetRecord,
            @Nullable Task targetTask, @Nullable ActivityRecord targetTopActivity,
            int realCallingUid, @BalCode int balCode,
            boolean enforceBlock, boolean taskToFront,
            boolean avoidMoveTaskToFront, boolean allowedByGracePeriod,
            ActivityRecord activityOptedIn) {
        final String prefix = "[ASM] ";
        Function<ActivityRecord, String> recordToString = (ar) -> {
            if (ar == null) {
                return null;
            }

            return (ar == sourceRecord ?        " [source]=> "
                    : ar == targetTopActivity ? " [ top  ]=> "
                    : ar == targetRecord ?      " [target]=> "
                    :                           "         => ")
                    + getDebugStringForActivityRecord(ar);
        };

        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(prefix + "------ Activity Security " + action + " Debug Logging Start ------");
        joiner.add(prefix + "Block Enabled: " + enforceBlock);
        if (!enforceBlock) {
            joiner.add(prefix + "Feature Flag Enabled: " + android.security
                    .Flags.asmRestrictionsEnabled());
            joiner.add(prefix + "Mendel Override: " + ActivitySecurityModelFeatureFlags
                    .asmRestrictionsEnabledForAll());
        }
        joiner.add(prefix + "ASM Version: " + ActivitySecurityModelFeatureFlags.ASM_VERSION);
        joiner.add(prefix + "System Time: " + SystemClock.uptimeMillis());
        joiner.add(prefix + "Activity Opted In: " + recordToString.apply(activityOptedIn));

        boolean targetTaskMatchesSourceTask = targetTask != null
                && sourceRecord != null && sourceRecord.getTask() == targetTask;

        if (sourceRecord == null) {
            joiner.add(prefix + "Source Package: " + targetRecord.launchedFromPackage);
            String realCallingPackage = mService.mContext.getPackageManager().getNameForUid(
                    realCallingUid);
            joiner.add(prefix + "Real Calling Uid Package: " + realCallingPackage);
        } else {
            joiner.add(prefix + "Source Record: " + recordToString.apply(sourceRecord));
            joiner.add(prefix + "Source Launch Package: " + sourceRecord.launchedFromPackage);
            joiner.add(prefix + "Source Launch Intent: " + sourceRecord.intent);
            if (targetTaskMatchesSourceTask) {
                joiner.add(prefix + "Source/Target Task: " + sourceRecord.getTask());
                joiner.add(prefix + "Source/Target Task Stack: ");
            } else {
                joiner.add(prefix + "Source Task: " + sourceRecord.getTask());
                joiner.add(prefix + "Source Task Stack: ");
            }
            sourceRecord.getTask().forAllActivities((Consumer<ActivityRecord>)
                    ar -> joiner.add(prefix + recordToString.apply(ar)));
        }

        joiner.add(prefix + "Target Task Top: " + recordToString.apply(targetTopActivity));
        if (!targetTaskMatchesSourceTask) {
            joiner.add(prefix + "Target Task: " + targetTask);
            if (targetTask != null) {
                joiner.add(prefix + "Target Task Stack: ");
                targetTask.forAllActivities((Consumer<ActivityRecord>)
                        ar -> joiner.add(prefix + recordToString.apply(ar)));
            }
        }

        joiner.add(prefix + "Target Record: " + recordToString.apply(targetRecord));
        joiner.add(prefix + "Intent: " + targetRecord.intent);
        joiner.add(prefix + "TaskToFront: " + taskToFront);
        joiner.add(prefix + "AvoidMoveToFront: " + avoidMoveTaskToFront);
        joiner.add(prefix + "BalCode: " + balCodeToString(balCode));
        joiner.add(prefix + "Allowed By Grace Period: " + allowedByGracePeriod);
        joiner.add(prefix + "LastResumedActivity: "
                       + recordToString.apply(mService.mLastResumedActivity));
        joiner.add(prefix + "System opted into enforcement: " + asmOptSystemIntoEnforcement());

        if (mTopFinishedActivity != null) {
            joiner.add(prefix + "TopFinishedActivity: " + mTopFinishedActivity.mDebugInfo);
        }

        if (!mTaskIdToFinishedActivity.isEmpty()) {
            joiner.add(prefix + "TaskIdToFinishedActivity: ");
            mTaskIdToFinishedActivity.values().forEach(
                    (fae) -> joiner.add(prefix + "  " + fae.mDebugInfo));
        }

        if (balCode == BAL_ALLOW_VISIBLE_WINDOW || balCode == BAL_ALLOW_NON_APP_VISIBLE_WINDOW
                || balCode == BAL_ALLOW_FOREGROUND || balCode == BAL_ALLOW_BOUND_BY_FOREGROUND) {
            Task task = sourceRecord != null ? sourceRecord.getTask() : targetTask;
            if (task != null && task.getDisplayArea() != null) {
                joiner.add(prefix + "Tasks: ");
                task.getDisplayArea().forAllTasks((Consumer<Task>)
                        t -> joiner.add(prefix + "   T: " + t.toFullString()));
            }
        }

        joiner.add(prefix + "------ Activity Security " + action + " Debug Logging End ------");
        return joiner.toString();
    }

    private boolean allowedByAsmGracePeriod(int callingUid, @Nullable ActivityRecord sourceRecord,
            @Nullable Task targetTask, @BalCode int balCode, boolean taskToFront,
            boolean avoidMoveTaskToFront) {
        if (balCode == BAL_ALLOW_GRACE_PERIOD) {
            // Allow if launching into new task, and caller matches most recently finished activity
            if (taskToFront && mTopFinishedActivity != null
                    && mTopFinishedActivity.mUid == callingUid) {
                return true;
            }

            // Launching into existing task - allow if matches most recently finished activity
            // within the task.
            // We can reach here multiple ways:
            // 1. activity in fg fires intent (taskToFront = false, sourceRecord is available)
            // 2. activity in bg fires intent (taskToFront = false, sourceRecord is available)
            // 3. activity in bg fires intent with NEW_FLAG (taskToFront = true,
            //         avoidMoveTaskToFront = true, sourceRecord is available)
            // 4. activity in bg fires PI (taskToFront = true, avoidMoveTaskToFront = true,
            //         sourceRecord is not available, targetTask may be available)
            if (!taskToFront || avoidMoveTaskToFront) {
                if (targetTask != null) {
                    FinishedActivityEntry finishedEntry =
                            mTaskIdToFinishedActivity.get(targetTask.mTaskId);
                    if (finishedEntry != null && finishedEntry.mUid == callingUid) {
                        return true;
                    }
                }

                if (sourceRecord != null) {
                    FinishedActivityEntry finishedEntry =
                            mTaskIdToFinishedActivity.get(sourceRecord.getTask().mTaskId);
                    return finishedEntry != null && finishedEntry.mUid == callingUid;
                }
            }
        }
        return false;
    }

    private static boolean isSystemExemptFlagEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_WINDOW_MANAGER,
                /* name= */ "system_exempt_from_activity_bg_start_restriction_enabled",
                /* defaultValue= */ true);
    }

    private BalVerdict statsLog(BalVerdict finalVerdict, BalState state) {
        if (finalVerdict.blocks() && mService.isActivityStartsLoggingEnabled()) {
            // log aborted activity start to TRON
            mSupervisor
                    .getActivityMetricsLogger()
                    .logAbortedBgActivityStart(
                            state.mIntent,
                            state.mCallerApp,
                            state.mCallingUid,
                            state.mCallingPackage,
                            state.mCallingUidProcState,
                            state.mCallingUidHasAnyVisibleWindow,
                            state.mRealCallingUid,
                            state.mRealCallingUidProcState,
                            state.mRealCallingUidHasAnyVisibleWindow,
                            (state.mOriginatingPendingIntent != null));
        }

        if (finalVerdict.getRawCode() == BAL_ALLOW_GRACE_PERIOD) {
            if (state.realCallerExplicitOptInOrAutoOptIn()
                    && state.mResultForRealCaller.allows()
                    && state.mResultForRealCaller.getRawCode() != BAL_ALLOW_GRACE_PERIOD) {
                // real caller could allow with a different exemption
            } else if (state.callerExplicitOptInOrAutoOptIn() && state.mResultForCaller.allows()
                    && state.mResultForCaller.getRawCode() != BAL_ALLOW_GRACE_PERIOD) {
                // caller could allow with a different exemption
            } else {
                // log to determine grace period length distribution
                Slog.wtf(TAG, "Activity start ONLY allowed by BAL_ALLOW_GRACE_PERIOD "
                        + finalVerdict.mMessage + ": " + state);
            }
        }

        if (balImprovedMetrics()) {
            if (shouldLogStats(finalVerdict, state)) {
                String activityName;
                if (shouldLogIntentActivity(finalVerdict, state)) {
                    Intent intent = state.mIntent;
                    activityName = intent == null ? "noIntent" // should never happen
                            : requireNonNull(intent.getComponent()).flattenToShortString();
                } else {
                    activityName = "";
                }
                writeBalAllowedLog(activityName, finalVerdict.getCode(), state);
            } else {
                writeBalAllowedLogMinimal(state);
            }
        } else {
            @BalCode int code = finalVerdict.getCode();
            int callingUid = state.mCallingUid;
            int realCallingUid = state.mRealCallingUid;
            Intent intent = state.mIntent;

            if (code == BAL_ALLOW_PENDING_INTENT
                    && (callingUid < Process.FIRST_APPLICATION_UID
                    || realCallingUid < Process.FIRST_APPLICATION_UID)) {
                String activityName = intent != null
                        ? requireNonNull(intent.getComponent()).flattenToShortString() : "";
                writeBalAllowedLog(activityName, BAL_ALLOW_PENDING_INTENT,
                        state);
            }
            if (code == BAL_ALLOW_PERMISSION || code == BAL_ALLOW_FOREGROUND
                    || code == BAL_ALLOW_SAW_PERMISSION) {
                // We don't need to know which activity in this case.
                writeBalAllowedLog("", code, state);
            }
        }
        return finalVerdict;
    }

    @VisibleForTesting
    boolean shouldLogStats(BalVerdict finalVerdict, BalState state) {
        if (finalVerdict.getRawCode() == BAL_ALLOW_VISIBLE_WINDOW) {
            if (!state.isPendingIntent()) {
                // regular activity start by visible app
                return false;
            }
            if (finalVerdict.mBasedOnRealCaller) {
                // PendingIntent started by visible app
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    boolean shouldLogIntentActivity(BalVerdict finalVerdict, BalState state) {
        return finalVerdict.mBasedOnRealCaller
                ? state.mRealCallingUid < Process.FIRST_APPLICATION_UID
                : state.mCallingUid < Process.FIRST_APPLICATION_UID;
    }

    @VisibleForTesting void writeBalAllowedLog(String activityName, int code, BalState state) {
        FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                activityName,
                code,
                state.mCallingUid,
                state.mRealCallingUid,
                state.mResultForCaller == null ? BAL_BLOCK : state.mResultForCaller.getRawCode(),
                state.mBalAllowedByPiCreator.allowsBackgroundActivityStarts(),
                state.callerExplicitOptInOrOut(),
                state.mResultForRealCaller == null ? BAL_BLOCK
                        : state.mResultForRealCaller.getRawCode(),
                state.mBalAllowedByPiSender.allowsBackgroundActivityStarts(),
                state.realCallerExplicitOptInOrOut(),
                getTargetSdk(state.mCallingPackage),
                getTargetSdk(state.mRealCallingPackage)
        );
    }

    @VisibleForTesting void writeBalAllowedLogMinimal(BalState state) {
        FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                "",
                BAL_ALLOW_DEFAULT,
                NO_PROCESS_UID,
                NO_PROCESS_UID,
                state.mResultForCaller == null ? BAL_BLOCK : state.mResultForCaller.getRawCode(),
                state.mBalAllowedByPiCreator.allowsBackgroundActivityStarts(),
                state.callerExplicitOptInOrOut(),
                state.mResultForRealCaller == null ? BAL_BLOCK
                        : state.mResultForRealCaller.getRawCode(),
                state.mBalAllowedByPiSender.allowsBackgroundActivityStarts(),
                state.realCallerExplicitOptInOrOut(),
                getTargetSdk(state.mCallingPackage),
                getTargetSdk(state.mRealCallingPackage)
        );
    }

    /**
     * Called whenever an activity finishes. Stores the record, so it can be used by ASM grace
     * period checks.
     */
    void onActivityRequestedFinishing(@NonNull ActivityRecord finishActivity) {
        // We only update the entry if the passed in activity
        // 1. Has been chained less than a set max AND
        // 2. Is visible or top
        FinishedActivityEntry entry =
                mTaskIdToFinishedActivity.get(finishActivity.getTask().mTaskId);
        if (entry != null && finishActivity.isUid(entry.mUid)
                && entry.mLaunchCount > ASM_GRACEPERIOD_MAX_REPEATS) {
            return;
        }

        if (!finishActivity.isVisibleRequested()
                && finishActivity != finishActivity.getTask().getTopMostActivity()) {
            return;
        }

        FinishedActivityEntry newEntry = new FinishedActivityEntry(finishActivity);
        mTaskIdToFinishedActivity.put(finishActivity.getTask().mTaskId, newEntry);
        if (finishActivity.getTask().mVisibleRequested) {
            mTopFinishedActivity = newEntry;
        }
    }

    /**
     * Called whenever an activity starts. Updates the record so the activity is no longer
     * considered for ASM grace period checks
     */
    void onNewActivityLaunched(ActivityRecord activityStarted) {
        if (activityStarted.getTask() == null) {
            return;
        }

        if (activityStarted.getTask().mVisibleRequested) {
            mTopFinishedActivity = null;
        }

        FinishedActivityEntry entry =
                mTaskIdToFinishedActivity.get(activityStarted.getTask().mTaskId);
        if (entry != null && activityStarted.getTask().isTaskId(entry.mTaskId)) {
            mTaskIdToFinishedActivity.remove(entry.mTaskId);
        }
    }

    private static class BlockActivityStart {
        private boolean mTopActivityOptedIn;
        private boolean mTopActivityMatchesSource;
        private ActivityRecord mActivityOptedIn;

        BlockActivityStart optedIn(ActivityRecord activity) {
            mTopActivityOptedIn = true;
            if (mActivityOptedIn == null) {
                mActivityOptedIn = activity;
            }
            return this;
        }

        BlockActivityStart matchesSource() {
            mTopActivityMatchesSource = true;
            return this;
        }
    }

    private static String getDebugStringForActivityRecord(ActivityRecord ar) {
        return ar
                + " :: visible=" + ar.isVisible()
                + ", visibleRequested=" + ar.isVisibleRequested()
                + ", finishing=" + ar.finishing
                + ", alwaysOnTop=" + ar.isAlwaysOnTop()
                + ", lastLaunchTime=" + ar.lastLaunchTime
                + ", lastVisibleTime=" + ar.lastVisibleTime
                + ", taskFragment=" + ar.getTaskFragment();
    }

    /** @return valid targetSdk or <code>-1</code> */
    private int getTargetSdk(String packageName) {
        if (packageName == null) {
            return -1;
        }
        try {
            PackageManager pm = mService.mContext.getPackageManager();
            return pm.getTargetSdkVersion(packageName);
        } catch (Exception e) {
            return -1;
        }
    }

    private class FinishedActivityEntry {
        int mUid;
        int mTaskId;
        int mLaunchCount;
        String mDebugInfo;

        FinishedActivityEntry(ActivityRecord ar) {
            FinishedActivityEntry entry = mTaskIdToFinishedActivity.get(ar.getTask().mTaskId);
            int taskId = ar.getTask().mTaskId;
            this.mUid = ar.getUid();
            this.mTaskId = taskId;
            this.mLaunchCount = entry == null || !ar.isUid(entry.mUid) ? 1 : entry.mLaunchCount + 1;
            this.mDebugInfo = getDebugStringForActivityRecord(ar);

            mService.mH.postDelayed(() -> {
                synchronized (mService.mGlobalLock) {
                    if (mTaskIdToFinishedActivity.get(taskId) == this) {
                        mTaskIdToFinishedActivity.remove(taskId);
                    }

                    if (mTopFinishedActivity == this) {
                        mTopFinishedActivity = null;
                    }
                }
            }, ASM_GRACEPERIOD_TIMEOUT_MS);
        }
    }
}
