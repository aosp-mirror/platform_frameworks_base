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
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_FG_ONLY;
import static com.android.server.wm.ActivityTaskSupervisor.getApplicationLabel;
import static com.android.server.wm.PendingRemoteAnimationRegistry.TIMEOUT_MS;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.ComponentOptions;
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
    public static final String VERDICT_ALLOWED = "Activity start allowed";
    public static final String VERDICT_WOULD_BE_ALLOWED_IF_SENDER_GRANTS_BAL =
            "Activity start would be allowed if the sender granted BAL privileges";

    private static final long ASM_GRACEPERIOD_TIMEOUT_MS = TIMEOUT_MS;
    private static final int ASM_GRACEPERIOD_MAX_REPEATS = 5;

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
                backgroundStartPrivileges, intent, checkedOptions) == BAL_BLOCK;
    }

    /**
     * @return A code denoting which BAL rule allows an activity to be started,
     * or {@link BAL_BLOCK} if the launch should be blocked
     */
    @BalCode
    int checkBackgroundActivityStart(
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
        // don't abort for the most important UIDs
        final int callingAppId = UserHandle.getAppId(callingUid);
        final boolean useCallingUidState =
                originatingPendingIntent == null
                        || checkedOptions == null
                        || checkedOptions.getPendingIntentCreatorBackgroundActivityStartMode()
                                != ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
        if (useCallingUidState) {
            if (callingUid == Process.ROOT_UID
                    || callingAppId == Process.SYSTEM_UID
                    || callingAppId == Process.NFC_UID) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_ALLOWLISTED_UID, /*background*/ false,
                        callingUid, realCallingUid, intent, "Important callingUid");
            }

            // Always allow home application to start activities.
            if (isHomeApp(callingUid, callingPackage)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "Home app");
            }

            // IME should always be allowed to start activity, like IME settings.
            final WindowState imeWindow =
                    mService.mRootWindowContainer.getCurrentInputMethodWindow();
            if (imeWindow != null && callingAppId == imeWindow.mOwnerUid) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "Active ime");
            }
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
        if (useCallingUidState && allowCallingUidStartActivity) {
            return logStartAllowedAndReturnCode(BAL_ALLOW_VISIBLE_WINDOW,
                    /*background*/ false, callingUid, realCallingUid, intent,
                    "callingUidHasAnyVisibleWindow = "
                            + callingUid
                            + ", isCallingUidPersistentSystemProcess = "
                            + isCallingUidPersistentSystemProcess);
        }
        // take realCallingUid into consideration
        final int realCallingUidProcState =
                (callingUid == realCallingUid)
                        ? callingUidProcState
                        : mService.mActiveUids.getUidState(realCallingUid);
        final boolean realCallingUidHasAnyVisibleWindow =
                (callingUid == realCallingUid)
                        ? callingUidHasAnyVisibleWindow
                        : mService.hasActiveVisibleWindow(realCallingUid);
        final int realCallingAppId = UserHandle.getAppId(realCallingUid);
        final boolean isRealCallingUidPersistentSystemProcess =
                (callingUid == realCallingUid)
                        ? isCallingUidPersistentSystemProcess
                        : (realCallingAppId == Process.SYSTEM_UID)
                                || realCallingUidProcState
                                        <= ActivityManager.PROCESS_STATE_PERSISTENT_UI;

        // In the case of an SDK sandbox calling uid, check if the corresponding app uid has a
        // visible window.
        if (Process.isSdkSandboxUid(realCallingUid)) {
            int realCallingSdkSandboxUidToAppUid =
                    Process.getAppUidForSdkSandboxUid(realCallingUid);

            if (mService.hasActiveVisibleWindow(realCallingSdkSandboxUidToAppUid)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_SDK_SANDBOX,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "uid in SDK sandbox has visible (non-toast) window");
            }
        }

        String realCallingPackage = mService.getPackageNameIfUnique(realCallingUid, realCallingPid);

        // Legacy behavior allows to use caller foreground state to bypass BAL restriction.
        // The options here are the options passed by the sender and not those on the intent.
        final BackgroundStartPrivileges balAllowedByPiSender =
                PendingIntentRecord.getBackgroundStartPrivilegesAllowedByCaller(
                        checkedOptions, realCallingUid, realCallingPackage);

        final boolean logVerdictChangeByPiDefaultChange = checkedOptions == null
                || checkedOptions.getPendingIntentBackgroundActivityStartMode()
                        == ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
        final boolean considerPiRules = logVerdictChangeByPiDefaultChange
                || balAllowedByPiSender.allowsBackgroundActivityStarts();
        final String verdictLogForPiSender =
                balAllowedByPiSender.allowsBackgroundActivityStarts() ? VERDICT_ALLOWED
                        : VERDICT_WOULD_BE_ALLOWED_IF_SENDER_GRANTS_BAL;

        @BalCode int resultIfPiSenderAllowsBal = BAL_BLOCK;
        if (realCallingUid != callingUid && considerPiRules) {
            resultIfPiSenderAllowsBal = checkPiBackgroundActivityStart(callingUid, realCallingUid,
                backgroundStartPrivileges, intent, checkedOptions,
                realCallingUidHasAnyVisibleWindow, isRealCallingUidPersistentSystemProcess,
                verdictLogForPiSender);
        }
        if (resultIfPiSenderAllowsBal != BAL_BLOCK
                && balAllowedByPiSender.allowsBackgroundActivityStarts()
                && !logVerdictChangeByPiDefaultChange) {
            // The result is to allow (because the sender allows BAL) and we are not interested in
            // logging differences, so just return.
            return resultIfPiSenderAllowsBal;
        }
        if (useCallingUidState) {
            // don't abort if the callingUid has START_ACTIVITIES_FROM_BACKGROUND permission
            if (ActivityTaskManagerService.checkPermission(START_ACTIVITIES_FROM_BACKGROUND,
                    callingPid, callingUid) == PERMISSION_GRANTED) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_PERMISSION,
                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                    /*background*/ true, callingUid, realCallingUid, intent,
                    "START_ACTIVITIES_FROM_BACKGROUND permission granted");
            }
            // don't abort if the caller has the same uid as the recents component
            if (mSupervisor.mRecentTasks.isCallerRecents(callingUid)) {
                return logStartAllowedAndReturnCode(
                    BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                    /*background*/ true, callingUid, realCallingUid,
                    intent, "Recents Component");
            }
            // don't abort if the callingUid is the device owner
            if (mService.isDeviceOwner(callingUid)) {
                return logStartAllowedAndReturnCode(
                    BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                    /*background*/ true, callingUid, realCallingUid,
                    intent, "Device Owner");
            }
            // don't abort if the callingUid is a affiliated profile owner
            if (mService.isAffiliatedProfileOwner(callingUid)) {
                return logStartAllowedAndReturnCode(
                    BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                    /*background*/ true, callingUid, realCallingUid,
                    intent, "Affiliated Profile Owner");
            }
            // don't abort if the callingUid has companion device
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (mService.isAssociatedCompanionApp(callingUserId, callingUid)) {
                return logStartAllowedAndReturnCode(
                    BAL_ALLOW_ALLOWLISTED_COMPONENT,
                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                    /*background*/ true, callingUid, realCallingUid,
                    intent, "Companion App");
            }
            // don't abort if the callingUid has SYSTEM_ALERT_WINDOW permission
            if (mService.hasSystemAlertWindowPermission(callingUid, callingPid, callingPackage)) {
                Slog.w(
                        TAG,
                        "Background activity start for "
                                + callingPackage
                                + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
                return logStartAllowedAndReturnCode(
                    BAL_ALLOW_SAW_PERMISSION,
                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                    /*background*/ true, callingUid, realCallingUid,
                    intent, "SYSTEM_ALERT_WINDOW permission is granted");
            }
            // don't abort if the callingUid and callingPackage have the
            // OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop
            if (isSystemExemptFlagEnabled() && mService.getAppOpsManager().checkOpNoThrow(
                        AppOpsManager.OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION,
                        callingUid, callingPackage) == AppOpsManager.MODE_ALLOWED) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_PERMISSION,
                        resultIfPiSenderAllowsBal, balAllowedByPiSender,
                        /*background*/ true, callingUid, realCallingUid, intent,
                        "OP_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION appop is granted");
            }
        }
        // If we don't have callerApp at this point, no caller was provided to startActivity().
        // That's the case for PendingIntent-based starts, since the creator's process might not be
        // up and alive. If that's the case, we retrieve the WindowProcessController for the send()
        // caller if caller allows, so that we can make the decision based on its state.
        int callerAppUid = callingUid;
        boolean callerAppBasedOnPiSender = callerApp == null && considerPiRules
                && resultIfPiSenderAllowsBal == BAL_BLOCK;
        if (callerAppBasedOnPiSender) {
            callerApp = mService.getProcessController(realCallingPid, realCallingUid);
            callerAppUid = realCallingUid;
        }
        // don't abort if the callerApp or other processes of that uid are allowed in any way
        if (callerApp != null && useCallingUidState) {
            // first check the original calling process
            final @BalCode int balAllowedForCaller = callerApp
                    .areBackgroundActivityStartsAllowed(appSwitchState);
            if (balAllowedForCaller != BAL_BLOCK) {
                if (callerAppBasedOnPiSender) {
                    resultIfPiSenderAllowsBal = logStartAllowedAndReturnCode(balAllowedForCaller,
                        /*background*/ true, callingUid, realCallingUid, intent,
                        "callerApp process (pid = " + callerApp.getPid()
                            + ", uid = " + callerAppUid + ") is allowed", verdictLogForPiSender);
                } else {
                    return logStartAllowedAndReturnCode(balAllowedForCaller,
                        resultIfPiSenderAllowsBal, balAllowedByPiSender,
                        /*background*/ true, callingUid, realCallingUid, intent,
                        "callerApp process (pid = " + callerApp.getPid()
                            + ", uid = " + callerAppUid + ") is allowed");
                }
            } else {
                // only if that one wasn't allowed, check the other ones
                final ArraySet<WindowProcessController> uidProcesses =
                    mService.mProcessMap.getProcesses(callerAppUid);
                if (uidProcesses != null) {
                    for (int i = uidProcesses.size() - 1; i >= 0; i--) {
                        final WindowProcessController proc = uidProcesses.valueAt(i);
                        int balAllowedForUid = proc.areBackgroundActivityStartsAllowed(
                                appSwitchState);
                        if (proc != callerApp && balAllowedForUid != BAL_BLOCK) {
                            if (callerAppBasedOnPiSender) {
                                resultIfPiSenderAllowsBal = logStartAllowedAndReturnCode(
                                    balAllowedForUid,
                                    /*background*/ true, callingUid, realCallingUid, intent,
                                    "process" + proc.getPid() + " from uid " + callerAppUid
                                        + " is allowed", verdictLogForPiSender);
                                break;
                            } else {
                                return logStartAllowedAndReturnCode(balAllowedForUid,
                                    resultIfPiSenderAllowsBal, balAllowedByPiSender,
                                    /*background*/ true, callingUid, realCallingUid, intent,
                                    "process" + proc.getPid() + " from uid " + callerAppUid
                                        + " is allowed");
                            }
                        }
                    }
                }
            }
            if (callerAppBasedOnPiSender) {
                // If caller app was based on PI sender, this result is part of
                // resultIfPiSenderAllowsBal
                if (resultIfPiSenderAllowsBal != BAL_BLOCK
                        && balAllowedByPiSender.allowsBackgroundActivityStarts()
                        && !logVerdictChangeByPiDefaultChange) {
                    // The result is to allow (because the sender allows BAL) and we are not
                    // interested in logging differences, so just return.
                    return resultIfPiSenderAllowsBal;
                }
            } else {
                // If caller app was NOT based on PI sender and we found a allow reason we should
                // have returned already
                checkState(balAllowedForCaller == BAL_BLOCK,
                        "balAllowedForCaller = " + balAllowedForCaller + " (should have returned)");
            }
        }
        // If we are here, it means all exemptions not based on PI sender failed, so we'll block
        // unless resultIfPiSenderAllowsBal is an allow and the PI sender allows BAL

        if (realCallingPackage == null) {
            realCallingPackage = (callingUid == realCallingUid ? callingPackage :
                    mService.mContext.getPackageManager().getNameForUid(realCallingUid))
                    + "[debugOnly]";
        }

        String stateDumpLog = " [callingPackage: " + callingPackage
                + "; callingUid: " + callingUid
                + "; appSwitchState: " + appSwitchState
                + "; callingUidHasAnyVisibleWindow: " + callingUidHasAnyVisibleWindow
                + "; callingUidProcState: " + DebugUtils.valueToString(
                        ActivityManager.class, "PROCESS_STATE_", callingUidProcState)
                + "; isCallingUidPersistentSystemProcess: " + isCallingUidPersistentSystemProcess
                + "; balAllowedByPiSender: " + balAllowedByPiSender
                + "; realCallingPackage: " + realCallingPackage
                + "; realCallingUid: " + realCallingUid
                + "; realCallingUidHasAnyVisibleWindow: " + realCallingUidHasAnyVisibleWindow
                + "; realCallingUidProcState: " + DebugUtils.valueToString(
                        ActivityManager.class, "PROCESS_STATE_", realCallingUidProcState)
                + "; isRealCallingUidPersistentSystemProcess: "
                        + isRealCallingUidPersistentSystemProcess
                + "; originatingPendingIntent: " + originatingPendingIntent
                + "; backgroundStartPrivileges: " + backgroundStartPrivileges
                + "; intent: " + intent
                + "; callerApp: " + callerApp
                + "; inVisibleTask: " + (callerApp != null && callerApp.hasActivityInVisibleTask())
                + "; resultIfPiSenderAllowsBal: " + balCodeToString(resultIfPiSenderAllowsBal)
                + "]";
        if (resultIfPiSenderAllowsBal != BAL_BLOCK) {
            // We should have returned before if !logVerdictChangeByPiDefaultChange
            checkState(logVerdictChangeByPiDefaultChange,
                    "resultIfPiSenderAllowsBal = " + balCodeToString(resultIfPiSenderAllowsBal)
                        + " at the end but logVerdictChangeByPiDefaultChange = false");
            if (balAllowedByPiSender.allowsBackgroundActivityStarts()) {
                // The verdict changed from block to allow, PI sender default change is off and
                // we'd block if it were on
                Slog.wtf(TAG, "With BAL hardening this activity start would be blocked!"
                        + stateDumpLog);
                return resultIfPiSenderAllowsBal;
            } else {
                // The verdict changed from allow (resultIfPiSenderAllowsBal) to block, PI sender
                // default change is on (otherwise we would have fallen into if above) and we'd
                // allow if it were off
                Slog.wtf(TAG, "Without BAL hardening this activity start would be allowed!"
                        + stateDumpLog);
            }
        }
        // anything that has fallen through would currently be aborted
        Slog.w(TAG, "Background activity launch blocked" + stateDumpLog);
        // log aborted activity start to TRON
        if (mService.isActivityStartsLoggingEnabled()) {
            mSupervisor
                    .getActivityMetricsLogger()
                    .logAbortedBgActivityStart(
                            intent,
                            callerApp,
                            callingUid,
                            callingPackage,
                            callingUidProcState,
                            callingUidHasAnyVisibleWindow,
                            realCallingUid,
                            realCallingUidProcState,
                            realCallingUidHasAnyVisibleWindow,
                            (originatingPendingIntent != null));
        }
        return BAL_BLOCK;
    }

    private @BalCode int checkPiBackgroundActivityStart(int callingUid, int realCallingUid,
            BackgroundStartPrivileges backgroundStartPrivileges, Intent intent,
            ActivityOptions checkedOptions, boolean realCallingUidHasAnyVisibleWindow,
            boolean isRealCallingUidPersistentSystemProcess, String verdictLog) {
        final boolean useCallerPermission =
                PendingIntentRecord.isPendingIntentBalAllowedByPermission(checkedOptions);
        if (useCallerPermission
                && ActivityManager.checkComponentPermission(
                                android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                realCallingUid, -1, true) == PackageManager.PERMISSION_GRANTED) {
            return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                /*background*/ false, callingUid, realCallingUid, intent,
                "realCallingUid has BAL permission. realCallingUid: " + realCallingUid,
                verdictLog);
        }

        // don't abort if the realCallingUid has a visible window
        // TODO(b/171459802): We should check appSwitchAllowed also
        if (realCallingUidHasAnyVisibleWindow) {
            return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false, callingUid, realCallingUid, intent,
                    "realCallingUid has visible (non-toast) window. realCallingUid: "
                            + realCallingUid, verdictLog);
        }
        // if the realCallingUid is a persistent system process, abort if the IntentSender
        // wasn't allowed to start an activity
        if (isRealCallingUidPersistentSystemProcess
                && backgroundStartPrivileges.allowsBackgroundActivityStarts()) {
            return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false, callingUid, realCallingUid, intent,
                    "realCallingUid is persistent system process AND intent "
                            + "sender allowed (allowBackgroundActivityStart = true). "
                            + "realCallingUid: " + realCallingUid, verdictLog);
        }
        // don't abort if the realCallingUid is an associated companion app
        if (mService.isAssociatedCompanionApp(
                UserHandle.getUserId(realCallingUid), realCallingUid)) {
            return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                    /*background*/ false, callingUid, realCallingUid, intent,
                    "realCallingUid is a companion app. "
                            + "realCallingUid: " + realCallingUid, verdictLog);
        }
        return BAL_BLOCK;
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
                targetTopActivity != null ? targetTopActivity.getUid() : -1,
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
            UiThread.getHandler().post(() -> Toast.makeText(mService.mContext,
                    toastText, Toast.LENGTH_LONG).show());

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
            UiThread.getHandler().post(() -> Toast.makeText(mService.mContext,
                    (shouldBlockActivityStart
                            ? "Top activities cleared by "
                            : "Top activities would be cleared by ")
                            + ActivitySecurityModelFeatureFlags.DOC_LINK,
                    Toast.LENGTH_LONG).show());

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
                topActivity == null ? -1 : topActivity.getUid(),
                /* target_task_top_activity_class_name */
                topActivity == null ? null : topActivity.info.name,
                /* target_task_is_different */
                false,
                /* target_activity_uid */
                -1,
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
            UiThread.getHandler().post(() -> Toast.makeText(mService.mContext,
                    (ActivitySecurityModelFeatureFlags.DOC_LINK
                            + (restrictActivitySwitch ? " returned home due to "
                            : " would return home due to ")
                            + callingLabel), Toast.LENGTH_LONG).show());
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

    static @BalCode int logStartAllowedAndReturnCode(@BalCode int code, boolean background,
            int callingUid, int realCallingUid, Intent intent, int pid, String msg) {
        return logStartAllowedAndReturnCode(code, background, callingUid, realCallingUid, intent,
                DEBUG_ACTIVITY_STARTS ?  ("[Process(" + pid + ")]" + msg) : "");
    }

    static @BalCode int logStartAllowedAndReturnCode(@BalCode int code, boolean background,
            int callingUid, int realCallingUid, Intent intent, String msg) {
        return logStartAllowedAndReturnCode(code, background, callingUid, realCallingUid, intent,
            msg, VERDICT_ALLOWED);
    }

    /**
     * Logs the start and returns one of the provided codes depending on if the PI sender allows
     * using its BAL privileges.
     */
    static @BalCode int logStartAllowedAndReturnCode(@BalCode int result,
            @BalCode int resultIfPiSenderAllowsBal, BackgroundStartPrivileges balAllowedByPiSender,
            boolean background, int callingUid, int realCallingUid, Intent intent, String msg) {
        if (resultIfPiSenderAllowsBal != BAL_BLOCK
                && balAllowedByPiSender.allowsBackgroundActivityStarts()) {
            // resultIfPiSenderAllowsBal was already logged, so just return
            return resultIfPiSenderAllowsBal;
        }
        return logStartAllowedAndReturnCode(result, background, callingUid, realCallingUid,
            intent, msg, VERDICT_ALLOWED);
    }


    static @BalCode int logStartAllowedAndReturnCode(@BalCode int code, boolean background,
            int callingUid, int realCallingUid, Intent intent, String msg, String verdict) {
        statsLogBalAllowed(code, callingUid, realCallingUid, intent);
        if (DEBUG_ACTIVITY_STARTS) {
            StringBuilder builder = new StringBuilder();
            if (background) {
                builder.append("Background ");
            }
            builder.append(verdict + ": " + msg + ". callingUid: " + callingUid + ". ");
            builder.append("BAL Code: ");
            builder.append(balCodeToString(code));
            if (verdict.equals(VERDICT_ALLOWED)) {
                Slog.i(TAG, builder.toString());
            } else {
                Slog.d(TAG, builder.toString());
            }
        }
        return code;
    }

    private static boolean isSystemExemptFlagEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_WINDOW_MANAGER,
                /* name= */ "system_exempt_from_activity_bg_start_restriction_enabled",
                /* defaultValue= */ true);
    }

    private static void statsLogBalAllowed(
            @BalCode int code, int callingUid, int realCallingUid, Intent intent) {
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
