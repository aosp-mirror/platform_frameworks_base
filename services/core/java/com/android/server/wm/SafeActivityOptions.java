/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.Manifest.permission.CONTROL_KEYGUARD;
import static android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;
import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.Manifest.permission.STATUS_BAR_SERVICE;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.view.RemoteAnimationAdapter;
import android.window.RemoteTransition;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Wraps {@link ActivityOptions}, records binder identity, and checks permission when retrieving
 * the inner options. Also supports having two set of options: Once from the original caller, and
 * once from the caller that is overriding it, which happens when sending a {@link PendingIntent}.
 */
public class SafeActivityOptions {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "SafeActivityOptions" : TAG_ATM;

    private final int mOriginalCallingPid;
    private final int mOriginalCallingUid;
    private int mRealCallingPid;
    private int mRealCallingUid;
    private final @Nullable ActivityOptions mOriginalOptions;
    private @Nullable ActivityOptions mCallerOptions;

    /**
     * Constructs a new instance from a bundle and records {@link Binder#getCallingPid}/
     * {@link Binder#getCallingUid}. Thus, calling identity MUST NOT be cleared when constructing
     * this object.
     *
     * @param bOptions The {@link ActivityOptions} as {@link Bundle}.
     */
    public static SafeActivityOptions fromBundle(Bundle bOptions) {
        return bOptions != null
                ? new SafeActivityOptions(ActivityOptions.fromBundle(bOptions))
                : null;
    }

    /**
     * Constructs a new instance from a bundle and provided pid/uid.
     *
     * @param bOptions The {@link ActivityOptions} as {@link Bundle}.
     */
    static SafeActivityOptions fromBundle(Bundle bOptions, int callingPid, int callingUid) {
        return bOptions != null
                ? new SafeActivityOptions(ActivityOptions.fromBundle(bOptions),
                        callingPid, callingUid)
                : null;
    }

    /**
     * Constructs a new instance and records {@link Binder#getCallingPid}/
     * {@link Binder#getCallingUid}. Thus, calling identity MUST NOT be cleared when constructing
     * this object.
     *
     * @param options The options to wrap.
     */
    public SafeActivityOptions(@Nullable ActivityOptions options) {
        mOriginalCallingPid = Binder.getCallingPid();
        mOriginalCallingUid = Binder.getCallingUid();
        mOriginalOptions = options;
    }

    /**
     * Constructs a new instance.
     *
     * @param options The options to wrap.
     */
    private SafeActivityOptions(@Nullable ActivityOptions options, int callingPid, int callingUid) {
        mOriginalCallingPid = callingPid;
        mOriginalCallingUid = callingUid;
        mOriginalOptions = options;
    }

    /**
     * To ensure that two activities, one using this object, and the other using the
     * SafeActivityOptions returned from this function, are launched into the same display/root task
     * through ActivityStartController#startActivities, all display-related information, i.e.
     * displayAreaToken, launchDisplayId, callerDisplayId and the launch root task are cloned.
     */
    @Nullable SafeActivityOptions selectiveCloneLaunchOptions() {
        final ActivityOptions options = cloneLaunchingOptions(mOriginalOptions);
        final ActivityOptions callerOptions = cloneLaunchingOptions(mCallerOptions);
        if (options == null && callerOptions == null) {
            return null;
        }

        final SafeActivityOptions safeOptions = new SafeActivityOptions(options,
                mOriginalCallingPid, mOriginalCallingUid);
        safeOptions.mCallerOptions = callerOptions;
        safeOptions.mRealCallingPid = mRealCallingPid;
        safeOptions.mRealCallingUid = mRealCallingUid;
        return safeOptions;
    }

    private ActivityOptions cloneLaunchingOptions(ActivityOptions options) {
        return options == null ? null : ActivityOptions.makeBasic()
                .setLaunchTaskDisplayArea(options.getLaunchTaskDisplayArea())
                .setLaunchDisplayId(options.getLaunchDisplayId())
                .setCallerDisplayId(options.getCallerDisplayId())
                .setLaunchRootTask(options.getLaunchRootTask())
                .setPendingIntentBackgroundActivityStartMode(
                        options.getPendingIntentBackgroundActivityStartMode())
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        options.getPendingIntentCreatorBackgroundActivityStartMode())
                .setRemoteTransition(options.getRemoteTransition());
    }

    /**
     * Overrides options with options from a caller and records {@link Binder#getCallingPid}/
     * {@link Binder#getCallingUid}. Thus, calling identity MUST NOT be cleared when calling this
     * method.
     */
    public void setCallerOptions(@Nullable ActivityOptions options) {
        mRealCallingPid = Binder.getCallingPid();
        mRealCallingUid = Binder.getCallingUid();
        mCallerOptions = options;
    }

    /**
     * Performs permission check and retrieves the options.
     *
     * @param r The record of the being started activity.
     */
    ActivityOptions getOptions(ActivityRecord r) throws SecurityException {
        return getOptions(r.intent, r.info, r.app, r.mTaskSupervisor);
    }

    /**
     * Performs permission check and retrieves the options when options are not being used to launch
     * a specific activity (i.e. a task is moved to front).
     */
    ActivityOptions getOptions(ActivityTaskSupervisor supervisor) throws SecurityException {
        return getOptions(null, null, null, supervisor);
    }

    /**
     * Performs permission check and retrieves the options.
     *
     * @param intent The intent that is being launched.
     * @param aInfo The info of the activity being launched.
     * @param callerApp The record of the caller.
     */
    ActivityOptions getOptions(@Nullable Intent intent, @Nullable ActivityInfo aInfo,
            @Nullable WindowProcessController callerApp,
            ActivityTaskSupervisor supervisor) throws SecurityException {
        if (mOriginalOptions != null) {
            checkPermissions(intent, aInfo, callerApp, supervisor, mOriginalOptions,
                    mOriginalCallingPid, mOriginalCallingUid);
            setCallingPidUidForRemoteAnimationAdapter(mOriginalOptions, mOriginalCallingPid,
                    mOriginalCallingUid);
        }
        if (mCallerOptions != null) {
            checkPermissions(intent, aInfo, callerApp, supervisor, mCallerOptions,
                    mRealCallingPid, mRealCallingUid);
            setCallingPidUidForRemoteAnimationAdapter(mCallerOptions, mRealCallingPid,
                    mRealCallingUid);
        }
        return mergeActivityOptions(mOriginalOptions, mCallerOptions);
    }

    private void setCallingPidUidForRemoteAnimationAdapter(ActivityOptions options,
            int callingPid, int callingUid) {
        final RemoteAnimationAdapter adapter = options.getRemoteAnimationAdapter();
        if (adapter == null) {
            return;
        }
        if (callingPid == WindowManagerService.MY_PID) {
            Slog.wtf(TAG, "Safe activity options constructed after clearing calling id");
            return;
        }
        adapter.setCallingPidUid(callingPid, callingUid);
    }

    /**
     * Gets the original options passed in. It should only be used for logging. DO NOT use it as a
     * condition in the logic of activity launch.
     */
    ActivityOptions getOriginalOptions() {
        return mOriginalOptions;
    }

    /**
     * @see ActivityOptions#popAppVerificationBundle
     */
    Bundle popAppVerificationBundle() {
        return mOriginalOptions != null ? mOriginalOptions.popAppVerificationBundle() : null;
    }

    private void abort() {
        if (mOriginalOptions != null) {
            ActivityOptions.abort(mOriginalOptions);
        }
        if (mCallerOptions != null) {
            ActivityOptions.abort(mCallerOptions);
        }
    }

    static void abort(@Nullable SafeActivityOptions options) {
        if (options != null) {
            options.abort();
        }
    }

    /**
     * Merges two activity options into one, with {@code options2} taking precedence in case of a
     * conflict.
     */
    @VisibleForTesting
    @Nullable ActivityOptions mergeActivityOptions(@Nullable ActivityOptions options1,
            @Nullable ActivityOptions options2) {
        if (options1 == null) {
            return options2;
        }
        if (options2 == null) {
            return options1;
        }
        final Bundle b1 = options1.toBundle();
        final Bundle b2 = options2.toBundle();
        b1.putAll(b2);
        return ActivityOptions.fromBundle(b1);
    }

    private void checkPermissions(@Nullable Intent intent, @Nullable ActivityInfo aInfo,
            @Nullable WindowProcessController callerApp, ActivityTaskSupervisor supervisor,
            ActivityOptions options, int callingPid, int callingUid) {
        // If a launch task id is specified, then ensure that the caller is the recents
        // component or has the START_TASKS_FROM_RECENTS permission
        if ((options.getLaunchTaskId() != INVALID_TASK_ID || options.getDisableStartingWindow())
                && !supervisor.mRecentTasks.isCallerRecents(callingUid)) {
            final int startInTaskPerm = ActivityTaskManagerService.checkPermission(
                    START_TASKS_FROM_RECENTS, callingPid, callingUid);
            if (startInTaskPerm == PERMISSION_DENIED) {
                final String msg = "Permission Denial: starting " + getIntentString(intent)
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ") with launchTaskId="
                        + options.getLaunchTaskId();
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }
        if (options.getTransientLaunch() && !supervisor.mRecentTasks.isCallerRecents(callingUid)
                && ActivityTaskManagerService.checkPermission(
                        MANAGE_ACTIVITY_TASKS, callingPid, callingUid) == PERMISSION_DENIED) {
            final String msg = "Permission Denial: starting transient launch from " + callerApp
                    + ", pid=" + callingPid + ", uid=" + callingUid;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        // Check if the caller is allowed to launch on the specified display area.
        final TaskDisplayArea taskDisplayArea = getLaunchTaskDisplayArea(options, supervisor);
        if (aInfo != null && taskDisplayArea != null
                && !supervisor.isCallerAllowedToLaunchOnTaskDisplayArea(callingPid, callingUid,
                taskDisplayArea, aInfo)) {
            final String msg = "Permission Denial: starting " + getIntentString(intent)
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") with launchTaskDisplayArea=" + taskDisplayArea;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        // Check if the caller is allowed to launch on the specified display.
        final int launchDisplayId = options.getLaunchDisplayId();
        if (aInfo != null && launchDisplayId != INVALID_DISPLAY
                && !supervisor.isCallerAllowedToLaunchOnDisplay(callingPid, callingUid,
                        launchDisplayId, aInfo)) {
            final String msg = "Permission Denial: starting " + getIntentString(intent)
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") with launchDisplayId="
                    + launchDisplayId;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        // Check if someone tries to launch an unallowlisted activity into LockTask mode.
        final boolean lockTaskMode = options.getLockTaskMode();
        if (aInfo != null && lockTaskMode
                && !supervisor.mService.getLockTaskController().isPackageAllowlisted(
                        UserHandle.getUserId(callingUid), aInfo.packageName)) {
            final String msg = "Permission Denial: starting " + getIntentString(intent)
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") with lockTaskMode=true";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        // Check if the caller is allowed to override any app transition animation.
        final boolean overrideTaskTransition = options.getOverrideTaskTransition();
        if (aInfo != null && overrideTaskTransition) {
            final int startTasksFromRecentsPerm = ActivityTaskManagerService.checkPermission(
                    START_TASKS_FROM_RECENTS, callingPid, callingUid);
            // Allow if calling uid is from assistant, or start task from recents
            if (startTasksFromRecentsPerm != PERMISSION_GRANTED
                    && !isAssistant(supervisor.mService, callingUid)) {
                final String msg = "Permission Denial: starting " + getIntentString(intent)
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ") with overrideTaskTransition=true";
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }

        // Check if the caller is allowed to dismiss keyguard.
        final boolean dismissKeyguardIfInsecure = options.getDismissKeyguardIfInsecure();
        if (aInfo != null && dismissKeyguardIfInsecure) {
            final int controlKeyguardPerm = ActivityTaskManagerService.checkPermission(
                    CONTROL_KEYGUARD, callingPid, callingUid);
            if (controlKeyguardPerm != PERMISSION_GRANTED) {
                final String msg = "Permission Denial: starting " + getIntentString(intent)
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ") with dismissKeyguardIfInsecure=true";
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }

        // Check permission for remote animations
        final RemoteAnimationAdapter adapter = options.getRemoteAnimationAdapter();
        if (adapter != null && supervisor.mService.checkPermission(
                CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS, callingPid, callingUid)
                        != PERMISSION_GRANTED) {
            final String msg = "Permission Denial: starting " + getIntentString(intent)
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") with remoteAnimationAdapter";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        // Check permission for remote transitions
        final RemoteTransition transition = options.getRemoteTransition();
        if (transition != null && supervisor.mService.checkPermission(
                CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS, callingPid, callingUid)
                != PERMISSION_GRANTED) {
            final String msg = "Permission Denial: starting " + getIntentString(intent)
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") with remoteTransition";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        // If launched from bubble is specified, then ensure that the caller is system or sysui.
        if (options.getLaunchedFromBubble() && !isSystemOrSystemUI(callingPid, callingUid)) {
            final String msg = "Permission Denial: starting " + getIntentString(intent)
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") with launchedFromBubble=true";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final int activityType = options.getLaunchActivityType();
        if (activityType != ACTIVITY_TYPE_UNDEFINED
                && !isSystemOrSystemUI(callingPid, callingUid)) {
            // Granted if it is assistant type and the calling uid is assistant.
            boolean activityTypeGranted = false;
            if (activityType == ACTIVITY_TYPE_ASSISTANT
                    && isAssistant(supervisor.mService, callingUid)) {
                activityTypeGranted = true;
            }

            if (!activityTypeGranted) {
                final String msg = "Permission Denial: starting " + getIntentString(intent)
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ") with launchActivityType="
                        + activityTypeToString(options.getLaunchActivityType());
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }
    }

    @VisibleForTesting
    TaskDisplayArea getLaunchTaskDisplayArea(ActivityOptions options,
            ActivityTaskSupervisor supervisor) {
        final WindowContainerToken daToken = options.getLaunchTaskDisplayArea();
        TaskDisplayArea taskDisplayArea = daToken != null
                ? (TaskDisplayArea) WindowContainer.fromBinder(daToken.asBinder()) : null;
        if (taskDisplayArea != null) {
            return taskDisplayArea;
        }

        // If we do not have a task display area token, check if the launch task display area
        // feature id is specified.
        final int launchTaskDisplayAreaFeatureId = options.getLaunchTaskDisplayAreaFeatureId();
        if (launchTaskDisplayAreaFeatureId != FEATURE_UNDEFINED) {
            final int launchDisplayId = options.getLaunchDisplayId() == INVALID_DISPLAY
                    ? DEFAULT_DISPLAY : options.getLaunchDisplayId();
            final DisplayContent dc = supervisor.mRootWindowContainer
                    .getDisplayContent(launchDisplayId);
            if (dc != null) {
                taskDisplayArea = dc.getItemFromTaskDisplayAreas(tda ->
                        tda.mFeatureId == launchTaskDisplayAreaFeatureId ? tda : null);
            }
        }
        return taskDisplayArea;
    }

    private boolean isAssistant(ActivityTaskManagerService atmService, int callingUid) {
        if (atmService.mActiveVoiceInteractionServiceComponent == null) {
            return false;
        }

        final String assistantPackage =
                atmService.mActiveVoiceInteractionServiceComponent.getPackageName();
        try {
            final int uid = AppGlobals.getPackageManager().getPackageUid(assistantPackage,
                    PackageManager.MATCH_DIRECT_BOOT_AUTO,
                    UserHandle.getUserId(callingUid));
            if (uid == callingUid) {
                return true;
            }
        } catch (RemoteException e) {
            // Should not happen
        }
        return false;
    }

    private boolean isSystemOrSystemUI(int callingPid, int callingUid) {
        if (callingUid == Process.SYSTEM_UID) {
            return true;
        }

        final int statusBarPerm = ActivityTaskManagerService.checkPermission(
                STATUS_BAR_SERVICE, callingPid, callingUid);
        return statusBarPerm == PERMISSION_GRANTED;
    }

    private String getIntentString(Intent intent) {
        return intent != null ? intent.toString() : "(no intent)";
    }
}
