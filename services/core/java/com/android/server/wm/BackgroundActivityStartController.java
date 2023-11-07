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
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_FG_ONLY;
import static com.android.server.wm.ActivityTaskSupervisor.getApplicationLabel;
import static com.android.window.flags.Flags.balShowToasts;
import static com.android.window.flags.Flags.balShowToastsBlocked;
import static com.android.server.wm.PendingRemoteAnimationRegistry.TIMEOUT_MS;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Slog;
import android.widget.Toast;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.server.UiThread;
import com.android.server.am.PendingIntentRecord;

import java.lang.annotation.Retention;
import java.util.HashMap;
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
    public static final ActivityOptions ACTIVITY_OPTIONS_SYSTEM_DEFINED =
            ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED)
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);

    private final ActivityTaskManagerService mService;

    private final ActivityTaskSupervisor mSupervisor;

    // TODO(b/263368846) Rename when ASM logic is moved in
    @Retention(SOURCE)
    @IntDef({BAL_BLOCK,
            BAL_ALLOW_DEFAULT,
            BAL_ALLOW_ALLOWLISTED_UID,
            BAL_ALLOW_ALLOWLISTED_COMPONENT,
            BAL_ALLOW_VISIBLE_WINDOW,
            BAL_ALLOW_PENDING_INTENT,
            BAL_ALLOW_PERMISSION,
            BAL_ALLOW_SAW_PERMISSION,
            BAL_ALLOW_GRACE_PERIOD,
            BAL_ALLOW_FOREGROUND,
            BAL_ALLOW_SDK_SANDBOX
    })
    public @interface BalCode {}

    static final int BAL_BLOCK = 0;

    static final int BAL_ALLOW_DEFAULT = 1;

    // Following codes are in order of precedence

    /** Important UIDs which should be always allowed to launch activities */
    static final int BAL_ALLOW_ALLOWLISTED_UID = 2;

    /** Apps that fulfill a certain role that can can always launch new tasks */
    static final int BAL_ALLOW_ALLOWLISTED_COMPONENT = 3;

    /** Apps which currently have a visible window or are bound by a service with a visible
     * window */
    static final int BAL_ALLOW_VISIBLE_WINDOW = 4;

    /** Allowed due to the PendingIntent sender */
    static final int BAL_ALLOW_PENDING_INTENT = 5;

    /** App has START_ACTIVITIES_FROM_BACKGROUND permission or BAL instrumentation privileges
     * granted to it */
    static final int BAL_ALLOW_PERMISSION = 6;

    /** Process has SYSTEM_ALERT_WINDOW permission granted to it */
    static final int BAL_ALLOW_SAW_PERMISSION = 7;

    /** App is in grace period after an activity was started or finished */
    static final int BAL_ALLOW_GRACE_PERIOD = 8;

    /** App is in a foreground task or bound to a foreground service (but not itself visible) */
    static final int BAL_ALLOW_FOREGROUND = 9;

    /** Process belongs to a SDK sandbox */
    static final int BAL_ALLOW_SDK_SANDBOX = 10;

    static String balCodeToString(@BalCode int balCode) {
        switch (balCode) {
            case BAL_ALLOW_ALLOWLISTED_COMPONENT:
                return "BAL_ALLOW_ALLOWLISTED_COMPONENT";
            case BAL_ALLOW_ALLOWLISTED_UID:
                return "BAL_ALLOW_ALLOWLISTED_UID";
            case BAL_ALLOW_DEFAULT:
                return "BAL_ALLOW_DEFAULT";
            case BAL_ALLOW_FOREGROUND:
                return "BAL_ALLOW_FOREGROUND";
            case BAL_ALLOW_GRACE_PERIOD:
                return "BAL_ALLOW_GRACE_PERIOD";
            case BAL_ALLOW_PENDING_INTENT:
                return "BAL_ALLOW_PENDING_INTENT";
            case BAL_ALLOW_PERMISSION:
                return "BAL_ALLOW_PERMISSION";
            case BAL_ALLOW_SAW_PERMISSION:
                return "BAL_ALLOW_SAW_PERMISSION";
            case BAL_ALLOW_SDK_SANDBOX:
                return "BAL_ALLOW_SDK_SANDBOX";
            case BAL_ALLOW_VISIBLE_WINDOW:
                return "BAL_ALLOW_VISIBLE_WINDOW";
            case BAL_BLOCK:
                return "BAL_BLOCK";
            default:
                throw new IllegalArgumentException("Unexpected value: " + balCode);
        }
    }

    @GuardedBy("mService.mGlobalLock")
    private HashMap<Integer, FinishedActivityEntry> mTaskIdToFinishedActivity = new HashMap<>();
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

    boolean shouldAbortBackgroundActivityStart(
            int callingUid,
            int callingPid,
            final String callingPackage,
            int realCallingUid,
            int realCallingPid,
            WindowProcessController callerApp,
            PendingIntentRecord originatingPendingIntent,
            BackgroundStartPrivileges backgroundStartPrivileges,
            Intent intent,
            ActivityOptions checkedOptions) {
        return checkBackgroundActivityStart(callingUid, callingPid, callingPackage,
                realCallingUid, realCallingPid,
                callerApp, originatingPendingIntent,
                backgroundStartPrivileges, intent, checkedOptions).blocks();
    }

    private class BalState {

        private final String mCallingPackage;
        private final int mCallingUid;
        private final int mCallingPid;
        private final @ActivityTaskManagerService.AppSwitchState int mAppSwitchState;
        private final boolean mCallingUidHasAnyVisibleWindow;
        private final @ActivityManager.ProcessState int mCallingUidProcState;
        private final boolean mIsCallingUidPersistentSystemProcess;
        private final BackgroundStartPrivileges mBalAllowedByPiSender;
        private final BackgroundStartPrivileges mBalAllowedByPiCreator;
        private final String mRealCallingPackage;
        private final int mRealCallingUid;
        private final int mRealCallingPid;
        private final boolean mRealCallingUidHasAnyVisibleWindow;
        private final @ActivityManager.ProcessState int mRealCallingUidProcState;
        private final boolean mIsRealCallingUidPersistentSystemProcess;
        private final PendingIntentRecord mOriginatingPendingIntent;
        private final BackgroundStartPrivileges mBackgroundStartPrivileges;
        private final Intent mIntent;
        private final WindowProcessController mCallerApp;
        private final WindowProcessController mRealCallerApp;

        private BalState(int callingUid, int callingPid, final String callingPackage,
                 int realCallingUid, int realCallingPid,
                 WindowProcessController callerApp,
                 PendingIntentRecord originatingPendingIntent,
                 BackgroundStartPrivileges backgroundStartPrivileges,
                 Intent intent,
                 ActivityOptions checkedOptions) {
            this.mCallingPackage = callingPackage;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            mRealCallingUid = realCallingUid;
            mRealCallingPid = realCallingPid;
            mCallerApp = callerApp;
            mBackgroundStartPrivileges = backgroundStartPrivileges;
            mOriginatingPendingIntent = originatingPendingIntent;
            mIntent = intent;
            mRealCallingPackage = mService.getPackageNameIfUnique(realCallingUid, realCallingPid);
            mBalAllowedByPiSender =
                    PendingIntentRecord.getBackgroundStartPrivilegesAllowedByCaller(checkedOptions,
                            realCallingUid, mRealCallingPackage);
            mBalAllowedByPiCreator =
                    checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                            == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED
                            ? BackgroundStartPrivileges.NONE : BackgroundStartPrivileges.ALLOW_BAL;
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

        private String getDebugPackageName(String packageName, int uid) {
            if (packageName != null) {
                return packageName; // use actual package
            }
            if (uid == 0) {
                return "root[debugOnly]";
            }
            String name = mService.mContext.getPackageManager().getNameForUid(uid);
            if (name == null) {
                name = "uid=" + uid;
            }
            return name + "[debugOnly]";
        }

        private boolean isPendingIntent() {
            return mRealCallingUid != NO_PROCESS_UID;
        }

        private String dump(BalVerdict resultIfPiCreatorAllowsBal) {
            Preconditions.checkState(!isPendingIntent());
            return dump(resultIfPiCreatorAllowsBal, null);
        }

        private boolean callerIsRealCaller() {
            return mCallingUid == mRealCallingUid;
        }

        private String dump(BalVerdict resultIfPiCreatorAllowsBal,
                            BalVerdict resultIfPiSenderAllowsBal) {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("[callingPackage: ")
                    .append(getDebugPackageName(mCallingPackage, mCallingUid));
            sb.append("; callingUid: ").append(mCallingUid);
            sb.append("; callingPid: ").append(mCallingPid);
            sb.append("; isPendingIntent: ").append(isPendingIntent());
            sb.append("; appSwitchState: ").append(mAppSwitchState);
            sb.append("; callingUidHasAnyVisibleWindow: ").append(mCallingUidHasAnyVisibleWindow);
            sb.append("; callingUidProcState: ").append(DebugUtils.valueToString(
                    ActivityManager.class, "PROCESS_STATE_", mCallingUidProcState));
            sb.append("; isCallingUidPersistentSystemProcess: ")
                    .append(mIsCallingUidPersistentSystemProcess);
            sb.append("; balAllowedByPiCreator: ").append(mBalAllowedByPiCreator);
            if (isPendingIntent()) {
                sb.append("; balAllowedByPiSender: ").append(mBalAllowedByPiSender);
                sb.append("; realCallingPackage: ")
                        .append(getDebugPackageName(mRealCallingPackage, mRealCallingUid));
                sb.append("; realCallingUid: ").append(mRealCallingUid);
                sb.append("; realCallingPid: ").append(mRealCallingPid);
                sb.append("; realCallingUidHasAnyVisibleWindow: ")
                        .append(mRealCallingUidHasAnyVisibleWindow);
                sb.append("; realCallingUidProcState: ").append(DebugUtils.valueToString(
                        ActivityManager.class, "PROCESS_STATE_", mRealCallingUidProcState));
                sb.append("; isRealCallingUidPersistentSystemProcess: ")
                        .append(mIsRealCallingUidPersistentSystemProcess);
                sb.append("; originatingPendingIntent: ").append(mOriginatingPendingIntent);
            }
            sb.append("; backgroundStartPrivileges: ").append(mBackgroundStartPrivileges);
            sb.append("; intent: ").append(mIntent);
            sb.append("; callerApp: ").append(mCallerApp);
            if (isPendingIntent()) {
                sb.append("; realCallerApp: ").append(mRealCallerApp);
            }
            if (mCallerApp != null) {
                sb.append("; inVisibleTask: ").append(mCallerApp.hasActivityInVisibleTask());
            }
            if (isPendingIntent()) {
                if (mRealCallerApp != null) {
                    sb.append("; realInVisibleTask: ")
                            .append(mRealCallerApp.hasActivityInVisibleTask());
                }
                sb.append("; resultIfPiSenderAllowsBal: ").append(resultIfPiSenderAllowsBal);
            }
            sb.append("; resultIfPiCreatorAllowsBal: ").append(resultIfPiCreatorAllowsBal);
            sb.append("]");
            return sb.toString();
        }
    }

    static class BalVerdict {

        static final BalVerdict BLOCK = new BalVerdict(BAL_BLOCK, false, "Blocked");
        private final @BalCode int mCode;
        private final boolean mBackground;
        private final String mMessage;
        private String mProcessInfo;

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

        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(". BAL Code: ");
            builder.append(balCodeToString(mCode));
            if (DEBUG_ACTIVITY_STARTS) {
                builder.append(" ");
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
            }
            return builder.toString();
        }

        public @BalCode int getCode() {
            return mCode;
        }
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStart(
            int callingUid,
            int callingPid,
            final String callingPackage,
            int realCallingUid,
            int realCallingPid,
            WindowProcessController callerApp,
            PendingIntentRecord originatingPendingIntent,
            BackgroundStartPrivileges backgroundStartPrivileges,
            Intent intent,
            ActivityOptions checkedOptions) {

        if (checkedOptions == null) {
            // replace null with a constant to simplify evaluation
            checkedOptions = ACTIVITY_OPTIONS_SYSTEM_DEFINED;
        }

        BalState state = new BalState(callingUid, callingPid, callingPackage,
                realCallingUid, realCallingPid, callerApp, originatingPendingIntent,
                backgroundStartPrivileges, intent, checkedOptions);

        // In the case of an SDK sandbox calling uid, check if the corresponding app uid has a
        // visible window.
        if (Process.isSdkSandboxUid(state.mRealCallingUid)) {
            int realCallingSdkSandboxUidToAppUid =
                    Process.getAppUidForSdkSandboxUid(state.mRealCallingUid);
            // realCallingSdkSandboxUidToAppUid should probably just be used instead (or in addition
            // to realCallingUid when calculating resultForRealCaller below.
            if (mService.hasActiveVisibleWindow(realCallingSdkSandboxUidToAppUid)) {
                BalVerdict balVerdict = new BalVerdict(BAL_ALLOW_SDK_SANDBOX, /*background*/ false,
                        "uid in SDK sandbox has visible (non-toast) window");
                return statsLog(balVerdict, state);
            }
        }

        BalVerdict resultForCaller = checkBackgroundActivityStartAllowedByCaller(state);

        if (!state.isPendingIntent()) {
            if (resultForCaller.allows()) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "Background activity start allowed. "
                            + state.dump(resultForCaller));
                }
                return statsLog(resultForCaller, state);
            }
            // anything that has fallen through would currently be aborted
            Slog.w(TAG, "Background activity launch blocked! "
                    + state.dump(resultForCaller));
            showBalBlockedToast("BAL blocked", state);
            return statsLog(BalVerdict.BLOCK, state);
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
                : checkBackgroundActivityStartAllowedBySender(state, checkedOptions);

        if (resultForCaller.allows()
                && checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Activity start explicitly allowed by PI creator. "
                        + state.dump(resultForCaller, resultForRealCaller));
            }
            return statsLog(resultForCaller, state);
        }
        if (resultForRealCaller.allows()
                && checkedOptions.getPendingIntentBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Activity start explicitly allowed by PI sender. "
                        + state.dump(resultForCaller, resultForRealCaller));
            }
            return statsLog(resultForRealCaller, state);
        }
        if (resultForCaller.allows() && resultForRealCaller.allows()
                && checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED
                && checkedOptions.getPendingIntentBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
            // Both caller and real caller allow with system defined behavior
            Slog.wtf(TAG,
                    "With Android 15 BAL hardening this activity start would be blocked"
                            + " (missing opt in by PI creator)! "
                            + state.dump(resultForCaller, resultForRealCaller));
            showBalRiskToast("BAL would be blocked", state);
            // return the realCaller result for backwards compatibility
            return statsLog(resultForRealCaller, state);
        }
        if (resultForCaller.allows()
                && checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
            // Allowed before V by creator
            Slog.wtf(TAG,
                    "With Android 15 BAL hardening this activity start would be blocked"
                            + " (missing opt in by PI creator)! "
                            + state.dump(resultForCaller, resultForRealCaller));
            showBalRiskToast("BAL would be blocked", state);
            return statsLog(resultForCaller, state);
        }
        if (resultForRealCaller.allows()
                && checkedOptions.getPendingIntentBackgroundActivityStartMode()
                == ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
            // Allowed before U by sender
            if (state.mBalAllowedByPiSender.allowsBackgroundActivityStarts()) {
                Slog.wtf(TAG,
                        "With Android 14 BAL hardening this activity start would be blocked"
                                + " (missing opt in by PI sender)! "
                                + state.dump(resultForCaller, resultForRealCaller));
                showBalBlockedToast("BAL would be blocked", state);
                return statsLog(resultForRealCaller, state);
            }
            Slog.wtf(TAG, "Without Android 14 BAL hardening this activity start would be allowed"
                    + " (missing opt in by PI sender)! "
                    + state.dump(resultForCaller, resultForRealCaller));
            // fall through
        }
        // anything that has fallen through would currently be aborted
        Slog.w(TAG, "Background activity launch blocked! "
                + state.dump(resultForCaller, resultForRealCaller));
        showBalBlockedToast("BAL blocked", state);
        return statsLog(BalVerdict.BLOCK, state);
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedByCaller(BalState state) {
        int callingUid = state.mCallingUid;
        int callingPid = state.mCallingPid;
        final String callingPackage = state.mCallingPackage;
        WindowProcessController callerApp = state.mCallerApp;

        // don't abort for the most important UIDs
        final int callingAppId = UserHandle.getAppId(callingUid);
        if (callingUid == Process.ROOT_UID
                || callingAppId == Process.SYSTEM_UID
                || callingAppId == Process.NFC_UID) {
            return new BalVerdict(
                    BAL_ALLOW_ALLOWLISTED_UID, /*background*/ false,
                     "Important callingUid");
        }

        // Always allow home application to start activities.
        if (isHomeApp(callingUid, callingPackage)) {
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

        // This is used to block background activity launch even if the app is still
        // visible to user after user clicking home button.
        final int appSwitchState = mService.getBalAppSwitchesState();

        // don't abort if the callingUid has a visible window or is a persistent system process
        final int callingUidProcState = mService.mActiveUids.getUidState(callingUid);
        final boolean callingUidHasAnyVisibleWindow = mService.hasActiveVisibleWindow(callingUid);
        final boolean isCallingUidPersistentSystemProcess =
                callingUidProcState <= ActivityManager.PROCESS_STATE_PERSISTENT_UI;

        // Normal apps with visible app window will be allowed to start activity if app switching
        // is allowed, or apps like live wallpaper with non app visible window will be allowed.
        final boolean appSwitchAllowedOrFg =
                appSwitchState == APP_SWITCH_ALLOW || appSwitchState == APP_SWITCH_FG_ONLY;
        final boolean allowCallingUidStartActivity =
                ((appSwitchAllowedOrFg || mService.mActiveUids.hasNonAppVisibleWindow(callingUid))
                        && callingUidHasAnyVisibleWindow)
                        || isCallingUidPersistentSystemProcess;
        if (allowCallingUidStartActivity) {
            return new BalVerdict(BAL_ALLOW_VISIBLE_WINDOW,
                    /*background*/ false,
                    "callingUidHasAnyVisibleWindow = "
                            + callingUid
                            + ", isCallingUidPersistentSystemProcess = "
                            + isCallingUidPersistentSystemProcess);
        }

        // don't abort if the callingUid has START_ACTIVITIES_FROM_BACKGROUND permission
        if (ActivityTaskManagerService.checkPermission(START_ACTIVITIES_FROM_BACKGROUND,
                callingPid, callingUid) == PERMISSION_GRANTED) {
            return new BalVerdict(BAL_ALLOW_PERMISSION,
                    /*background*/ true,
                    "START_ACTIVITIES_FROM_BACKGROUND permission granted");
        }
        // don't abort if the caller has the same uid as the recents component
        if (mSupervisor.mRecentTasks.isCallerRecents(callingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Recents Component");
        }
        // don't abort if the callingUid is the device owner
        if (mService.isDeviceOwner(callingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Device Owner");
        }
        // don't abort if the callingUid is a affiliated profile owner
        if (mService.isAffiliatedProfileOwner(callingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Affiliated Profile Owner");
        }
        // don't abort if the callingUid has companion device
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (mService.isAssociatedCompanionApp(callingUserId, callingUid)) {
            return new BalVerdict(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    /*background*/ true, "Companion App");
        }
        // don't abort if the callingUid has SYSTEM_ALERT_WINDOW permission
        if (mService.hasSystemAlertWindowPermission(callingUid, callingPid, callingPackage)) {
            Slog.w(
                    TAG,
                    "Background activity start for "
                            + callingPackage
                            + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
            return new BalVerdict(BAL_ALLOW_SAW_PERMISSION,
                    /*background*/ true, "SYSTEM_ALERT_WINDOW permission is granted");
        }
        // don't abort if the callingUid and callingPackage have the
        // OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop
        if (isSystemExemptFlagEnabled() && mService.getAppOpsManager().checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION,
                callingUid, callingPackage) == AppOpsManager.MODE_ALLOWED) {
            return new BalVerdict(BAL_ALLOW_PERMISSION, /*background*/ true,
                    "OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop is granted");
        }

        // If we don't have callerApp at this point, no caller was provided to startActivity().
        // That's the case for PendingIntent-based starts, since the creator's process might not be
        // up and alive.
        // Don't abort if the callerApp or other processes of that uid are allowed in any way.
        BalVerdict callerAppAllowsBal = checkProcessAllowsBal(callerApp, state);
        if (callerAppAllowsBal.allows()) {
            return callerAppAllowsBal;
        }

        // If we are here, it means all exemptions based on the creator failed
        return BalVerdict.BLOCK;
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link #BAL_BLOCK} if the launch should be blocked
     */
    BalVerdict checkBackgroundActivityStartAllowedBySender(
            BalState state,
            ActivityOptions checkedOptions) {

        if (PendingIntentRecord.isPendingIntentBalAllowedByPermission(checkedOptions)
                && ActivityManager.checkComponentPermission(
                android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                state.mRealCallingUid, NO_PROCESS_UID, true) == PackageManager.PERMISSION_GRANTED) {
            return new BalVerdict(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false,
                    "realCallingUid has BAL permission.");
        }

        // don't abort if the realCallingUid has a visible window
        // TODO(b/171459802): We should check appSwitchAllowed also
        if (state.mRealCallingUidHasAnyVisibleWindow) {
            return new BalVerdict(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false,
                    "realCallingUid has visible (non-toast) window.");
        }
        // if the realCallingUid is a persistent system process, abort if the IntentSender
        // wasn't allowed to start an activity
        if (state.mIsRealCallingUidPersistentSystemProcess
                && state.mBackgroundStartPrivileges.allowsBackgroundActivityStarts()) {
            return new BalVerdict(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false,
                    "realCallingUid is persistent system process AND intent "
                            + "sender allowed (allowBackgroundActivityStart = true).");
        }
        // don't abort if the realCallingUid is an associated companion app
        if (mService.isAssociatedCompanionApp(
                UserHandle.getUserId(state.mRealCallingUid), state.mRealCallingUid)) {
            return new BalVerdict(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false,
                    "realCallingUid is a companion app.");
        }

        // don't abort if the callerApp or other processes of that uid are allowed in any way
        BalVerdict realCallerAppAllowsBal =
                checkProcessAllowsBal(state.mRealCallerApp, state);
        if (realCallerAppAllowsBal.allows()) {
            return realCallerAppAllowsBal;
        }

        // If we are here, it means all exemptions based on PI sender failed
        return BalVerdict.BLOCK;
    }

    /**
     * Check if the app allows BAL.
     *
     * See {@link BackgroundLaunchProcessController#areBackgroundActivityStartsAllowed(int, int,
     * String, int, boolean, boolean, boolean, long, long, long)} for details on the
     * exceptions.
     */
    private BalVerdict checkProcessAllowsBal(WindowProcessController app,
            BalState state) {
        if (app == null) {
            return BalVerdict.BLOCK;
        }
        // first check the original calling process
        final BalVerdict balAllowedForCaller = app
                .areBackgroundActivityStartsAllowed(state.mAppSwitchState);
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
                                state.mAppSwitchState);
                        if (balAllowedForUid.allows()) {
                            return balAllowedForCaller.withProcessInfo("process", proc);
                        }
                    }
                }
            }
        }
        return BalVerdict.BLOCK;
    }

    /**
     * Log activity starts which violate one of the following rules of the
     * activity security model (ASM):
     * See go/activity-security for rationale behind the rules.
     * 1. Within a task, only an activity matching a top UID of the task can start activities
     * 2. Only activities within a foreground task, which match a top UID of the task, can
     * create a new task or bring an existing one into the foreground
     */
    boolean checkActivityAllowedToStart(@Nullable ActivityRecord sourceRecord,
            @NonNull ActivityRecord targetRecord, boolean newTask, @NonNull Task targetTask,
            int launchFlags, int balCode, int callingUid, int realCallingUid) {
        // BAL Exception allowed in all cases
        if (balCode == BAL_ALLOW_ALLOWLISTED_UID) {
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
                    || balCode == BAL_ALLOW_VISIBLE_WINDOW) {
                return true;
            }
        }

        if (balCode == BAL_ALLOW_GRACE_PERIOD) {
            if (taskToFront && mTopFinishedActivity != null
                    && mTopFinishedActivity.mUid == callingUid) {
                return true;
            } else if (!taskToFront) {
                FinishedActivityEntry finishedEntry =
                        mTaskIdToFinishedActivity.get(targetTask.mTaskId);
                if (finishedEntry != null && finishedEntry.mUid == callingUid) {
                    return true;
                }
            }
        }

        BlockActivityStart bas = null;
        if (sourceRecord != null) {
            boolean passesAsmChecks = true;
            Task sourceTask = sourceRecord.getTask();

            // Allow launching into a new task (or a task matching the launched activity's
            // affinity) only if the current task is foreground or mutating its own task.
            // The latter can happen eg. if caller uses NEW_TASK flag and the activity being
            // launched matches affinity of source task.
            if (taskToFront) {
                passesAsmChecks = sourceTask != null
                        && (sourceTask.isVisible() || sourceTask == targetTask);
            }

            if (passesAsmChecks) {
                Task taskToCheck = taskToFront ? sourceTask : targetTask;
                bas = isTopActivityMatchingUidAbsentForAsm(taskToCheck, sourceRecord.getUid(),
                        sourceRecord);
            }
        } else if (!taskToFront) {
            // We don't have a sourceRecord, and we're launching into an existing task.
            // Allow if callingUid is top of stack.
            bas = isTopActivityMatchingUidAbsentForAsm(targetTask, callingUid,
                    /*sourceRecord*/null);
        }

        if (bas != null && !bas.mWouldBlockActivityStartIgnoringFlag) {
            return true;
        }

        // ASM rules have failed. Log why
        return logAsmFailureAndCheckFeatureEnabled(sourceRecord, callingUid, realCallingUid,
                newTask, targetTask, targetRecord, balCode, launchFlags, bas, taskToFront);
    }

    private boolean logAsmFailureAndCheckFeatureEnabled(ActivityRecord sourceRecord, int callingUid,
            int realCallingUid, boolean newTask, Task targetTask, ActivityRecord targetRecord,
            @BalCode int balCode, int launchFlags, BlockActivityStart bas, boolean taskToFront) {

        ActivityRecord targetTopActivity = targetTask == null ? null
                : targetTask.getActivity(ar -> !ar.finishing && !ar.isAlwaysOnTop());

        int action = newTask || sourceRecord == null
                ? FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__ACTIVITY_START_NEW_TASK
                : (sourceRecord.getTask().equals(targetTask)
                ? FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__ACTIVITY_START_SAME_TASK
                : FrameworkStatsLog.ACTIVITY_ACTION_BLOCKED__ACTION__ACTIVITY_START_DIFFERENT_TASK);

        boolean blockActivityStartAndFeatureEnabled = ActivitySecurityModelFeatureFlags
                .shouldRestrictActivitySwitch(callingUid)
                && (bas == null || bas.mBlockActivityStartIfFlagEnabled);

        String asmDebugInfo = getDebugInfoForActivitySecurity("Launch", sourceRecord,
                targetRecord, targetTask, targetTopActivity, realCallingUid, balCode,
                blockActivityStartAndFeatureEnabled, taskToFront);

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
                    + (blockActivityStartAndFeatureEnabled ? " blocked " : " would block ")
                    + getApplicationLabel(mService.mContext.getPackageManager(),
                    launchedFromPackageName);
            showToast(toastText);

            Slog.i(TAG, asmDebugInfo);
        }

        if (blockActivityStartAndFeatureEnabled) {
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

    private void showBalBlockedToast(String toastText, BalState state) {
        if (balShowToastsBlocked()) {
            showToast(toastText
                    + " caller:" + state.mCallingPackage
                    + " realCaller:" + state.mRealCallingPackage);
        }
    }

    private void showBalRiskToast(String toastText, BalState state) {
        if (balShowToasts()) {
            showToast(toastText
                    + " caller:" + state.mCallingPackage
                    + " realCaller:" + state.mRealCallingPackage);
        }
    }

    private void showToast(String toastText) {
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
        boolean shouldBlockActivityStart = ActivitySecurityModelFeatureFlags
                .shouldRestrictActivitySwitch(callingUid);
        int[] finishCount = new int[0];
        if (shouldBlockActivityStart) {
            ActivityRecord activity = targetTask.getActivity(isLaunchingOrLaunched);
            if (activity == null) {
                // mStartActivity is not in task, so clear everything
                activity = targetRecord;
            }

            finishCount = new int[1];
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
                    /* taskToFront */ true));
        }
    }

    /**
     * Returns home if the passed in callingUid is not top of the stack, rather than returning to
     * previous task.
     */
    void checkActivityAllowedToClearTask(@NonNull Task task, int callingUid,
            @NonNull String callerActivityClassName) {
        // We may have already checked that the callingUid has additional clearTask privileges, and
        // cleared the calling identify. If so, we infer we do not need further restrictions here.
        if (callingUid == SYSTEM_UID || !task.isVisible() || task.inMultiWindowMode()) {
            return;
        }

        TaskDisplayArea displayArea = task.getTaskDisplayArea();
        if (displayArea == null) {
            // If there is no associated display area, we can not return home.
            return;
        }

        BlockActivityStart bas = isTopActivityMatchingUidAbsentForAsm(task, callingUid, null);
        if (!bas.mWouldBlockActivityStartIgnoringFlag) {
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
                .shouldRestrictActivitySwitch(callingUid)
                && bas.mBlockActivityStartIfFlagEnabled;

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
     *
     * Returns a class where the elements are:
     * <pre>
     * shouldBlockActivityStart: {@code true} if we should actually block the transition (takes into
     * consideration feature flag and targetSdk).
     * wouldBlockActivityStartIgnoringFlags: {@code true} if we should warn about the transition via
     * toasts. This happens if the transition would be blocked in case both the app was targeting V+
     * and the feature was enabled.
     * </pre>
     */
    private BlockActivityStart isTopActivityMatchingUidAbsentForAsm(@NonNull Task task,
            int uid, @Nullable ActivityRecord sourceRecord) {
        // If the source is visible, consider it 'top'.
        if (sourceRecord != null && sourceRecord.isVisible()) {
            return new BlockActivityStart(false, false);
        }

        // Always allow actual top activity to clear task
        ActivityRecord topActivity = task.getTopMostActivity();
        if (topActivity != null && topActivity.isUid(uid)) {
            return new BlockActivityStart(false, false);
        }

        // Consider the source activity, whether or not it is finishing. Do not consider any other
        // finishing activity.
        Predicate<ActivityRecord> topOfStackPredicate = (ar) -> ar.equals(sourceRecord)
                || (!ar.finishing && !ar.isAlwaysOnTop());

        // Check top of stack (or the first task fragment for embedding).
        topActivity = task.getActivity(topOfStackPredicate);
        if (topActivity == null) {
            return new BlockActivityStart(true, true);
        }

        BlockActivityStart pair = blockCrossUidActivitySwitchFromBelow(topActivity, uid);
        if (!pair.mBlockActivityStartIfFlagEnabled) {
            return pair;
        }

        // Even if the top activity is not a match, we may be in an embedded activity scenario with
        // an adjacent task fragment. Get the second fragment.
        TaskFragment taskFragment = topActivity.getTaskFragment();
        if (taskFragment == null) {
            return pair;
        }

        TaskFragment adjacentTaskFragment = taskFragment.getAdjacentTaskFragment();
        if (adjacentTaskFragment == null) {
            return pair;
        }

        // Check the second fragment.
        topActivity = adjacentTaskFragment.getActivity(topOfStackPredicate);
        if (topActivity == null) {
            return new BlockActivityStart(true, true);
        }

        return blockCrossUidActivitySwitchFromBelow(topActivity, uid);
    }

    /**
     * Determines if a source is allowed to add or remove activities from the task,
     * if the current ActivityRecord is above it in the stack
     *
     * A transition is blocked ({@code false} returned) if all of the following are met:
     * <pre>
     * 1. The source activity and the current activity record belong to different apps
     * (i.e, have different UIDs).
     * 2. Both the source activity and the current activity target U+
     * 3. The current activity has not set
     * {@link ActivityRecord#setAllowCrossUidActivitySwitchFromBelow(boolean)} to {@code true}
     * </pre>
     *
     * Returns a class where the elements are:
     * <pre>
     * shouldBlockActivityStart: {@code true} if we should actually block the transition (takes into
     * consideration feature flag and targetSdk).
     * wouldBlockActivityStartIgnoringFlags: {@code true} if we should warn about the transition via
     * toasts. This happens if the transition would be blocked in case both the app was targeting V+
     * and the feature was enabled.
     * </pre>
     *
     * @param sourceUid The source (s) activity performing the state change
     */
    private BlockActivityStart blockCrossUidActivitySwitchFromBelow(ActivityRecord ar,
            int sourceUid) {
        if (ar.isUid(sourceUid)) {
            return new BlockActivityStart(false, false);
        }

        // If mAllowCrossUidActivitySwitchFromBelow is set, honor it.
        if (ar.mAllowCrossUidActivitySwitchFromBelow) {
            return new BlockActivityStart(false, false);
        }

        // At this point, we would block if the feature is launched and both apps were V+
        // Since we have a feature flag, we need to check that too
        // TODO(b/258792202) Replace with CompatChanges and replace Pair with boolean once feature
        // flag is removed
        boolean restrictActivitySwitch =
                ActivitySecurityModelFeatureFlags.shouldRestrictActivitySwitch(ar.getUid())
                        && ActivitySecurityModelFeatureFlags
                        .shouldRestrictActivitySwitch(sourceUid);
        return new BackgroundActivityStartController
                .BlockActivityStart(restrictActivitySwitch, true);
    }

    /**
     * Only called when an activity launch may be blocked, which should happen very rarely
     */
    private String getDebugInfoForActivitySecurity(@NonNull String action,
            @Nullable ActivityRecord sourceRecord, @NonNull ActivityRecord targetRecord,
            @Nullable Task targetTask, @Nullable ActivityRecord targetTopActivity,
            int realCallingUid, @BalCode int balCode,
            boolean blockActivityStartAndFeatureEnabled, boolean taskToFront) {
        final String prefix = "[ASM] ";
        Function<ActivityRecord, String> recordToString = (ar) -> {
            if (ar == null) {
                return null;
            }
            return (ar == sourceRecord ? " [source]=> "
                    : ar == targetTopActivity ? " [ top  ]=> "
                    : ar == targetRecord ? " [target]=> "
                    : "         => ")
                    + ar
                    + " :: visible=" + ar.isVisible()
                    + ", finishing=" + ar.isFinishing()
                    + ", alwaysOnTop=" + ar.isAlwaysOnTop()
                    + ", taskFragment=" + ar.getTaskFragment();
        };

        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(prefix + "------ Activity Security " + action + " Debug Logging Start ------");
        joiner.add(prefix + "Block Enabled: " + blockActivityStartAndFeatureEnabled);
        joiner.add(prefix + "ASM Version: " + ActivitySecurityModelFeatureFlags.ASM_VERSION);

        boolean targetTaskMatchesSourceTask = targetTask != null
                && sourceRecord != null && sourceRecord.getTask() == targetTask;

        if (sourceRecord == null) {
            joiner.add(prefix + "Source Package: " + targetRecord.launchedFromPackage);
            String realCallingPackage = mService.mContext.getPackageManager().getNameForUid(
                    realCallingUid);
            joiner.add(prefix + "Real Calling Uid Package: " + realCallingPackage);
        } else {
            joiner.add(prefix + "Source Record: " + recordToString.apply(sourceRecord));
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
        joiner.add(prefix + "BalCode: " + balCodeToString(balCode));

        joiner.add(prefix + "------ Activity Security " + action + " Debug Logging End ------");
        return joiner.toString();
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

        @BalCode int code = finalVerdict.getCode();
        int callingUid = state.mCallingUid;
        int realCallingUid = state.mRealCallingUid;
        Intent intent = state.mIntent;

        if (code == BAL_ALLOW_PENDING_INTENT
                && (callingUid == Process.SYSTEM_UID || realCallingUid == Process.SYSTEM_UID)) {
            String activityName =
                    intent != null ? intent.getComponent().flattenToShortString() : "";
            FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                    activityName,
                    code,
                    callingUid,
                    realCallingUid);
        }
        if (code == BAL_ALLOW_PERMISSION || code == BAL_ALLOW_FOREGROUND
                    || code == BAL_ALLOW_SAW_PERMISSION) {
            // We don't need to know which activity in this case.
            FrameworkStatsLog.write(FrameworkStatsLog.BAL_ALLOWED,
                    /*activityName*/ "",
                    code,
                    callingUid,
                    realCallingUid);
        }
        return finalVerdict;
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

        if (!finishActivity.mVisibleRequested
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

    static class BlockActivityStart {
        // We should block if feature flag is enabled
        private final boolean mBlockActivityStartIfFlagEnabled;
        // Used for logging/toasts. Would we block if target sdk was V and feature was
        // enabled?
        private final boolean mWouldBlockActivityStartIgnoringFlag;

        BlockActivityStart(boolean shouldBlockActivityStart,
                boolean wouldBlockActivityStartIgnoringFlags) {
            this.mBlockActivityStartIfFlagEnabled = shouldBlockActivityStart;
            this.mWouldBlockActivityStartIgnoringFlag = wouldBlockActivityStartIgnoringFlags;
        }
    }

    private class FinishedActivityEntry {
        int mUid;
        int mTaskId;
        int mLaunchCount;

        FinishedActivityEntry(ActivityRecord ar) {
            FinishedActivityEntry entry = mTaskIdToFinishedActivity.get(ar.getTask().mTaskId);
            int taskId = ar.getTask().mTaskId;
            this.mUid = ar.getUid();
            this.mTaskId = taskId;
            this.mLaunchCount = entry == null || !ar.isUid(entry.mUid) ? 1 : entry.mLaunchCount + 1;

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
