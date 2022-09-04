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

import com.android.server.am.PendingIntentRecord;

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
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed for important callingUid (" + callingUid + ")");
                }
                return false;
            }

            // Always allow home application to start activities.
            if (isHomeApp(callingUid, callingPackage)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed for home app callingUid (" + callingUid + ")");
                }
                return false;
            }

            // IME should always be allowed to start activity, like IME settings.
            final WindowState imeWindow =
                    mService.mRootWindowContainer.getCurrentInputMethodWindow();
            if (imeWindow != null && callingAppId == imeWindow.mOwnerUid) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "Activity start allowed for active ime (" + callingUid + ")");
                }
                return false;
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
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(
                        TAG,
                        "Activity start allowed: callingUidHasAnyVisibleWindow = "
                                + callingUid
                                + ", isCallingUidPersistentSystemProcess = "
                                + isCallingUidPersistentSystemProcess);
            }
            return false;
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
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed: uid in SDK sandbox ("
                                    + realCallingUid
                                    + ") has visible (non-toast) window.");
                }
                return false;
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
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed: realCallingUid ("
                                    + realCallingUid
                                    + ") has BAL permission.");
                }
                return false;
            }

            // don't abort if the realCallingUid has a visible window
            // TODO(b/171459802): We should check appSwitchAllowed also
            if (realCallingUidHasAnyVisibleWindow) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed: realCallingUid ("
                                    + realCallingUid
                                    + ") has visible (non-toast) window");
                }
                return false;
            }
            // if the realCallingUid is a persistent system process, abort if the IntentSender
            // wasn't allowed to start an activity
            if (isRealCallingUidPersistentSystemProcess && allowBackgroundActivityStart) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed: realCallingUid ("
                                    + realCallingUid
                                    + ") is persistent system process AND intent sender allowed "
                                    + "(allowBackgroundActivityStart = true)");
                }
                return false;
            }
            // don't abort if the realCallingUid is an associated companion app
            if (mService.isAssociatedCompanionApp(
                    UserHandle.getUserId(realCallingUid), realCallingUid)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Activity start allowed: realCallingUid ("
                                    + realCallingUid
                                    + ") is companion app");
                }
                return false;
            }
        }
        if (useCallingUidState) {
            // don't abort if the callingUid has START_ACTIVITIES_FROM_BACKGROUND permission
            if (mService.checkPermission(START_ACTIVITIES_FROM_BACKGROUND, callingPid, callingUid)
                    == PERMISSION_GRANTED) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Background activity start allowed: START_ACTIVITIES_FROM_BACKGROUND "
                                    + "permission granted for uid "
                                    + callingUid);
                }
                return false;
            }
            // don't abort if the caller has the same uid as the recents component
            if (mSupervisor.mRecentTasks.isCallerRecents(callingUid)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Background activity start allowed: callingUid ("
                                    + callingUid
                                    + ") is recents");
                }
                return false;
            }
            // don't abort if the callingUid is the device owner
            if (mService.isDeviceOwner(callingUid)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Background activity start allowed: callingUid ("
                                    + callingUid
                                    + ") is device owner");
                }
                return false;
            }
            // don't abort if the callingUid has companion device
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (mService.isAssociatedCompanionApp(callingUserId, callingUid)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Background activity start allowed: callingUid ("
                                    + callingUid
                                    + ") is companion app");
                }
                return false;
            }
            // don't abort if the callingUid has SYSTEM_ALERT_WINDOW permission
            if (mService.hasSystemAlertWindowPermission(callingUid, callingPid, callingPackage)) {
                Slog.w(
                        TAG,
                        "Background activity start for "
                                + callingPackage
                                + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
                return false;
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
            if (callerApp.areBackgroundActivityStartsAllowed(appSwitchState)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(
                            TAG,
                            "Background activity start allowed: callerApp process (pid = "
                                    + callerApp.getPid()
                                    + ", uid = "
                                    + callerAppUid
                                    + ") is allowed");
                }
                return false;
            }
            // only if that one wasn't allowed, check the other ones
            final ArraySet<WindowProcessController> uidProcesses =
                    mService.mProcessMap.getProcesses(callerAppUid);
            if (uidProcesses != null) {
                for (int i = uidProcesses.size() - 1; i >= 0; i--) {
                    final WindowProcessController proc = uidProcesses.valueAt(i);
                    if (proc != callerApp
                            && proc.areBackgroundActivityStartsAllowed(appSwitchState)) {
                        if (DEBUG_ACTIVITY_STARTS) {
                            Slog.d(
                                    TAG,
                                    "Background activity start allowed: process "
                                            + proc.getPid()
                                            + " from uid "
                                            + callerAppUid
                                            + " is allowed");
                        }
                        return false;
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
        return true;
    }
}
