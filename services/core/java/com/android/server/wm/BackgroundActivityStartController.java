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
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
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
            BAL_ALLOW_BAL_PERMISSION,
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

    /** Apps which currently have a visible window */
    static final int BAL_ALLOW_VISIBLE_WINDOW = 4;

    /** Allowed due to the PendingIntent sender */
    static final int BAL_ALLOW_PENDING_INTENT = 5;

    /** App has START_ACTIVITIES_FROM_BACKGROUND permission or BAL instrumentation privileges
     * granted to it */
    static final int BAL_ALLOW_BAL_PERMISSION = 6;

    /** Process has SYSTEM_ALERT_WINDOW permission granted to it */
    static final int BAL_ALLOW_SAW_PERMISSION = 7;

    /** App is in grace period after an activity was started or finished */
    static final int BAL_ALLOW_GRACE_PERIOD = 8;

    /** App is in a foreground task or bound to a foreground service (but not itself visible) */
    static final int BAL_ALLOW_FOREGROUND = 9;

    /** Process belongs to a SDK sandbox */
    static final int BAL_ALLOW_SDK_SANDBOX = 10;

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
            boolean allowBackgroundActivityStart,
            Intent intent,
            ActivityOptions checkedOptions) {
        return checkBackgroundActivityStart(callingUid, callingPid, callingPackage,
                realCallingUid, realCallingPid, callerApp, originatingPendingIntent,
                allowBackgroundActivityStart, intent, checkedOptions) == BAL_BLOCK;
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
            boolean allowBackgroundActivityStart,
            Intent intent,
            ActivityOptions checkedOptions) {
        // don't abort for the most important UIDs
        final int callingAppId = UserHandle.getAppId(callingUid);
        final boolean useCallingUidState =
                originatingPendingIntent == null
                        || checkedOptions == null
                        || !checkedOptions.getIgnorePendingIntentCreatorForegroundState();
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
        final boolean isCallingUidForeground =
                callingUidHasAnyVisibleWindow
                        || callingUidProcState == ActivityManager.PROCESS_STATE_TOP
                        || callingUidProcState == ActivityManager.PROCESS_STATE_BOUND_TOP;
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
        final boolean isRealCallingUidForeground =
                (callingUid == realCallingUid)
                        ? isCallingUidForeground
                        : realCallingUidHasAnyVisibleWindow
                                || realCallingUidProcState == ActivityManager.PROCESS_STATE_TOP;
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
        final boolean balAllowedByPiSender =
                PendingIntentRecord.isPendingIntentBalAllowedByCaller(checkedOptions);

        if (balAllowedByPiSender && realCallingUid != callingUid) {
            final boolean useCallerPermission =
                    PendingIntentRecord.isPendingIntentBalAllowedByPermission(checkedOptions);
            if (useCallerPermission
                    && ActivityManager.checkComponentPermission(
                                    android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                                    realCallingUid,
                                    -1,
                                    true)
                            == PackageManager.PERMISSION_GRANTED) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "realCallingUid has BAL permission. realCallingUid: " + realCallingUid);
            }

            // don't abort if the realCallingUid has a visible window
            // TODO(b/171459802): We should check appSwitchAllowed also
            if (realCallingUidHasAnyVisibleWindow) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "realCallingUid has visible (non-toast) window. realCallingUid: "
                                + realCallingUid);
            }
            // if the realCallingUid is a persistent system process, abort if the IntentSender
            // wasn't allowed to start an activity
            if (isRealCallingUidPersistentSystemProcess && allowBackgroundActivityStart) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "realCallingUid is persistent system process AND intent "
                                + "sender allowed (allowBackgroundActivityStart = true). "
                                + "realCallingUid: " + realCallingUid);
            }
            // don't abort if the realCallingUid is an associated companion app
            if (mService.isAssociatedCompanionApp(
                    UserHandle.getUserId(realCallingUid), realCallingUid)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_PENDING_INTENT,
                        /*background*/ false, callingUid, realCallingUid, intent,
                        "realCallingUid is a companion app. "
                                + "realCallingUid: " + realCallingUid);
            }
        }
        if (useCallingUidState) {
            // don't abort if the callingUid has START_ACTIVITIES_FROM_BACKGROUND permission
            if (ActivityTaskManagerService.checkPermission(START_ACTIVITIES_FROM_BACKGROUND,
                    callingPid, callingUid) == PERMISSION_GRANTED) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_BAL_PERMISSION,
                        /*background*/ true, callingUid, realCallingUid, intent,
                        "START_ACTIVITIES_FROM_BACKGROUND permission granted");
            }
            // don't abort if the caller has the same uid as the recents component
            if (mSupervisor.mRecentTasks.isCallerRecents(callingUid)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                        /*background*/ true, callingUid, realCallingUid,
                        intent, "Recents Component");
            }
            // don't abort if the callingUid is the device owner
            if (mService.isDeviceOwner(callingUid)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_ALLOWLISTED_COMPONENT,
                        /*background*/ true, callingUid, realCallingUid,
                        intent, "Device Owner");
            }
            // don't abort if the callingUid has companion device
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (mService.isAssociatedCompanionApp(callingUserId, callingUid)) {
                return logStartAllowedAndReturnCode(BAL_ALLOW_ALLOWLISTED_COMPONENT,
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
                return logStartAllowedAndReturnCode(BAL_ALLOW_SAW_PERMISSION,
                        /*background*/ true, callingUid, realCallingUid,
                        intent, "SYSTEM_ALERT_WINDOW permission is granted");
            }
        }
        // If we don't have callerApp at this point, no caller was provided to startActivity().
        // That's the case for PendingIntent-based starts, since the creator's process might not be
        // up and alive. If that's the case, we retrieve the WindowProcessController for the send()
        // caller if caller allows, so that we can make the decision based on its state.
        int callerAppUid = callingUid;
        if (callerApp == null && balAllowedByPiSender) {
            callerApp = mService.getProcessController(realCallingPid, realCallingUid);
            callerAppUid = realCallingUid;
        }
        // don't abort if the callerApp or other processes of that uid are allowed in any way
        if (callerApp != null && useCallingUidState) {
            // first check the original calling process
            @BalCode int balAllowedForCaller = callerApp
                    .areBackgroundActivityStartsAllowed(appSwitchState);
            if (balAllowedForCaller != BAL_BLOCK) {
                return logStartAllowedAndReturnCode(balAllowedForCaller,
                        /*background*/ true, callingUid, realCallingUid, intent,
                        "callerApp process (pid = " + callerApp.getPid()
                                + ", uid = " + callerAppUid + ") is allowed");
            }
            // only if that one wasn't allowed, check the other ones
            final ArraySet<WindowProcessController> uidProcesses =
                    mService.mProcessMap.getProcesses(callerAppUid);
            if (uidProcesses != null) {
                for (int i = uidProcesses.size() - 1; i >= 0; i--) {
                    final WindowProcessController proc = uidProcesses.valueAt(i);
                    int balAllowedForUid = proc.areBackgroundActivityStartsAllowed(appSwitchState);
                    if (proc != callerApp
                            && balAllowedForUid != BAL_BLOCK) {
                        return logStartAllowedAndReturnCode(balAllowedForUid,
                                /*background*/ true, callingUid, realCallingUid, intent,
                                "process" + proc.getPid()
                                        + " from uid " + callerAppUid + " is allowed");
                    }
                }
            }
        }
        // anything that has fallen through would currently be aborted
        Slog.w(
                TAG,
                "Background activity start [callingPackage: "
                        + callingPackage
                        + "; callingUid: "
                        + callingUid
                        + "; appSwitchState: "
                        + appSwitchState
                        + "; isCallingUidForeground: "
                        + isCallingUidForeground
                        + "; callingUidHasAnyVisibleWindow: "
                        + callingUidHasAnyVisibleWindow
                        + "; callingUidProcState: "
                        + DebugUtils.valueToString(
                                ActivityManager.class, "PROCESS_STATE_", callingUidProcState)
                        + "; isCallingUidPersistentSystemProcess: "
                        + isCallingUidPersistentSystemProcess
                        + "; realCallingUid: "
                        + realCallingUid
                        + "; isRealCallingUidForeground: "
                        + isRealCallingUidForeground
                        + "; realCallingUidHasAnyVisibleWindow: "
                        + realCallingUidHasAnyVisibleWindow
                        + "; realCallingUidProcState: "
                        + DebugUtils.valueToString(
                                ActivityManager.class, "PROCESS_STATE_", realCallingUidProcState)
                        + "; isRealCallingUidPersistentSystemProcess: "
                        + isRealCallingUidPersistentSystemProcess
                        + "; originatingPendingIntent: "
                        + originatingPendingIntent
                        + "; allowBackgroundActivityStart: "
                        + allowBackgroundActivityStart
                        + "; intent: "
                        + intent
                        + "; callerApp: "
                        + callerApp
                        + "; inVisibleTask: "
                        + (callerApp != null && callerApp.hasActivityInVisibleTask())
                        + "]");
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

    static @BalCode int logStartAllowedAndReturnCode(@BalCode int code, boolean background,
            int callingUid, int realCallingUid, Intent intent, int pid, String msg) {
        return logStartAllowedAndReturnCode(code, background, callingUid, realCallingUid, intent,
                DEBUG_ACTIVITY_STARTS ?  ("[Process(" + pid + ")]" + msg) : "");
    }

    static @BalCode int logStartAllowedAndReturnCode(@BalCode int code, boolean background,
            int callingUid, int realCallingUid, Intent intent, String msg) {
        statsLogBalAllowed(code, callingUid, realCallingUid, intent);
        if (DEBUG_ACTIVITY_STARTS) {
            StringBuilder builder = new StringBuilder();
            if (background) {
                builder.append("Background ");
            }
            builder.append("Activity start allowed: " + msg + ". callingUid: " + callingUid + ". ");
            builder.append("BAL Code: ");
            builder.append(code);
            Slog.d(TAG,  builder.toString());
        }
        return code;
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
        if (code == BAL_ALLOW_BAL_PERMISSION || code == BAL_ALLOW_FOREGROUND
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
