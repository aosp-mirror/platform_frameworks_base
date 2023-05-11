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
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_FG_ONLY;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
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


import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.PendingIntentRecord;

import java.lang.annotation.Retention;

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
                realCallingUid, realCallingPid, callerApp, originatingPendingIntent,
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
                    Process.getAppUidForSdkSandboxUid(UserHandle.getAppId(realCallingUid));

            if (mService.hasActiveVisibleWindow(realCallingSdkSandboxUidToAppUid)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_SDK_SANDBOX,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "uid in SDK sandbox has visible (non-toast) window");
            }
        }

        // Legacy behavior allows to use caller foreground state to bypass BAL restriction.
        // The options here are the options passed by the sender and not those on the intent.
        final BackgroundStartPrivileges balAllowedByPiSender =
                PendingIntentRecord.getBackgroundStartPrivilegesAllowedByCaller(
                        checkedOptions, realCallingUid);

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

        String realCallingPackage = callingUid == realCallingUid ? callingPackage :
                mService.mContext.getPackageManager().getNameForUid(realCallingUid);

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
}
