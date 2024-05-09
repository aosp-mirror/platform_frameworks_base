/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;
import static android.app.Activity.FULLSCREEN_MODE_REQUEST_ENTER;
import static android.app.Activity.FULLSCREEN_MODE_REQUEST_EXIT;
import static android.app.ActivityOptions.ANIM_SCENE_TRANSITION;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.ActivityTaskManager.INVALID_WINDOWING_MODE;
import static android.app.FullscreenRequestHandler.REMOTE_CALLBACK_RESULT_KEY;
import static android.app.FullscreenRequestHandler.RESULT_APPROVED;
import static android.app.FullscreenRequestHandler.RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY;
import static android.app.FullscreenRequestHandler.RESULT_FAILED_NOT_TOP_FOCUSED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.service.voice.VoiceInteractionSession.SHOW_SOURCE_APPLICATION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IMMERSIVE;
import static com.android.server.wm.ActivityRecord.State.DESTROYED;
import static com.android.server.wm.ActivityRecord.State.DESTROYING;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESTARTING_PROCESS;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.ActivityTaskManagerService.TAG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerService.enforceNotIsolatedCaller;
import static com.android.window.flags.Flags.allowDisableActivityRecordInputSink;

import android.Manifest;
import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.FullscreenRequestHandler;
import android.app.IActivityClientController;
import android.app.ICompatCameraControlCallback;
import android.app.IRequestFinishCallback;
import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.app.compat.CompatChanges;
import android.app.servertransaction.EnterPipRequestedItem;
import android.app.servertransaction.PipStateTransactionItem;
import android.compat.annotation.ChangeId;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManagerInternal;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.voice.VoiceInteractionManagerInternal;
import android.util.Slog;
import android.view.RemoteAnimationDefinition;
import android.window.SizeConfigurationBuckets;
import android.window.TransitionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.pm.KnownPackages;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.uri.GrantUri;
import com.android.server.uri.NeededUriGrants;
import com.android.server.utils.quota.Categorizer;
import com.android.server.utils.quota.Category;
import com.android.server.utils.quota.CountQuotaTracker;
import com.android.server.vr.VrManagerInternal;

/**
 * Server side implementation for the client activity to interact with system.
 *
 * @see android.app.ActivityClient
 */
class ActivityClientController extends IActivityClientController.Stub {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityClientController" : TAG_ATM;

    private final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;
    private final ActivityTaskSupervisor mTaskSupervisor;
    private final Context mContext;

    // Prevent malicious app abusing the Activity#setPictureInPictureParams API
    @VisibleForTesting CountQuotaTracker mSetPipAspectRatioQuotaTracker;
    // Limit to 60 times / minute
    private static final int SET_PIP_ASPECT_RATIO_LIMIT = 60;
    // The timeWindowMs here can not be smaller than QuotaTracker#MIN_WINDOW_SIZE_MS
    private static final long SET_PIP_ASPECT_RATIO_TIME_WINDOW_MS = 60_000;

    /** Wrapper around VoiceInteractionServiceManager. */
    private AssistUtils mAssistUtils;

    /**
     * Grants access to the launching app's identity if the app opted-in to sharing its identity
     * by launching this activity with an instance of {@link android.app.ActivityOptions} on which
     * {@link android.app.ActivityOptions#setShareIdentityEnabled(boolean)} was invoked with a
     * value of {@code true}, or if the launched activity's uid is the same as the launching
     * app's. When this change is enabled and one of these requirements is met, the activity
     * can access the launching app's uid and package name with {@link
     * android.app.Activity#getLaunchedFromUid()} and {@link
     * android.app.Activity#getLaunchedFromPackage()}, respectively.
     */
    @ChangeId
    public static final long ACCESS_SHARED_IDENTITY = 259743961L;

    ActivityClientController(ActivityTaskManagerService service) {
        mService = service;
        mGlobalLock = service.mGlobalLock;
        mTaskSupervisor = service.mTaskSupervisor;
        mContext = service.mContext;
    }

    void onSystemReady() {
        mAssistUtils = new AssistUtils(mContext);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw ActivityTaskManagerService.logAndRethrowRuntimeExceptionOnTransact(
                    "ActivityClientController", e);
        }
    }

    @Override
    public void activityIdle(IBinder token, Configuration config, boolean stopProfiling) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "activityIdle");
                final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    return;
                }
                mTaskSupervisor.activityIdleInternal(r, false /* fromTimeout */,
                        false /* processPausingActivities */, config);
                if (stopProfiling && r.hasProcess()) {
                    r.app.clearProfilerIfNeeded();
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void activityResumed(IBinder token, boolean handleSplashScreenExit) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            ActivityRecord.activityResumedLocked(token, handleSplashScreenExit);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void activityRefreshed(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            ActivityRecord.activityRefreshedLocked(token);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void activityTopResumedStateLost() {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            mTaskSupervisor.handleTopResumedStateReleased(false /* timeout */);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void activityPaused(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "activityPaused");
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                r.activityPaused(false);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void activityStopped(IBinder token, Bundle icicle, PersistableBundle persistentState,
            CharSequence description) {
        if (DEBUG_ALL) Slog.v(TAG, "Activity stopped: token=" + token);

        // Refuse possible leaked file descriptors.
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();

        String restartingName = null;
        int restartingUid = 0;
        final ActivityRecord r;
        synchronized (mGlobalLock) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "activityStopped");
            r = ActivityRecord.isInRootTaskLocked(token);
            if (r != null) {
                if (!r.isState(STOPPING, RESTARTING_PROCESS)
                        && mTaskSupervisor.hasScheduledRestartTimeouts(r)) {
                    // Recover the restarting state which was replaced by other lifecycle changes.
                    r.setState(RESTARTING_PROCESS, "continue-restart");
                }
                if (r.attachedToProcess() && r.isState(RESTARTING_PROCESS)) {
                    // The activity was requested to restart from
                    // {@link #restartActivityProcessIfVisible}.
                    restartingName = r.app.mName;
                    restartingUid = r.app.mUid;
                }
                r.activityStopped(icicle, persistentState, description);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        if (restartingName != null) {
            // In order to let the foreground activity can be restarted with its saved state from
            // {@link android.app.Activity#onSaveInstanceState}, the kill operation is postponed
            // until the activity reports stopped with the state. And the activity record will be
            // kept because the record state is restarting, then the activity will be restarted
            // immediately if it is still the top one.
            mTaskSupervisor.removeRestartTimeouts(r);
            mService.mAmInternal.killProcess(restartingName, restartingUid,
                    "restartActivityProcess");
        }
        mService.mAmInternal.trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void activityDestroyed(IBinder token) {
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "ACTIVITY DESTROYED: " + token);
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "activityDestroyed");
            try {
                final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r != null) {
                    r.destroyed("activityDestroyed");
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void activityLocalRelaunch(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                r.startRelaunching();
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void activityRelaunched(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                r.finishRelaunching();
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void reportSizeConfigurations(IBinder token,
            SizeConfigurationBuckets sizeConfigurations) {
        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Report configuration: %s %s",
                token, sizeConfigurations);
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r != null) {
                r.setSizeConfigurations(sizeConfigurations);
            }
        }
    }

    /**
     * Attempts to move a task backwards in z-order (the order of activities within the task is
     * unchanged).
     *
     * There are several possible results of this call:
     * - if the task is locked, then we will show the lock toast.
     * - if there is a task behind the provided task, then that task is made visible and resumed as
     * this task is moved to the back.
     * - otherwise, if there are no other tasks in the root task:
     * - if this task is in the pinned mode, then we remove the task completely, which will
     * have the effect of moving the task to the top or bottom of the fullscreen root task
     * (depending on whether it is visible).
     * - otherwise, we simply return home and hide this task.
     *
     * @param token   A reference to the activity we wish to move.
     * @param nonRoot If false then this only works if the activity is the root
     *                of a task; if true it will work for any activity in a task.
     * @return Returns true if the move completed, false if not.
     */
    @Override
    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        enforceNotIsolatedCaller("moveActivityTaskToBack");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final int taskId = ActivityRecord.getTaskForActivityLocked(token, !nonRoot);
                final Task task = mService.mRootWindowContainer.anyTaskForId(taskId);
                if (task != null) {
                    return ActivityRecord.getRootTask(token).moveTaskToBack(task);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
        return false;
    }

    @Override
    public boolean shouldUpRecreateTask(IBinder token, String destAffinity) {
        synchronized (mGlobalLock) {
            final ActivityRecord srec = ActivityRecord.forTokenLocked(token);
            if (srec != null) {
                return srec.getRootTask().shouldUpRecreateTaskLocked(srec, destAffinity);
            }
        }
        return false;
    }

    @Override
    public boolean navigateUpTo(IBinder token, Intent destIntent, String resolvedType,
            int resultCode, Intent resultData) {
        final ActivityRecord r;
        synchronized (mGlobalLock) {
            r = ActivityRecord.isInRootTaskLocked(token);
            if (r == null) {
                return false;
            }
        }

        // Carefully collect grants without holding lock.
        final NeededUriGrants destGrants = mService.collectGrants(destIntent, r);
        final NeededUriGrants resultGrants = mService.collectGrants(resultData, r.resultTo);

        synchronized (mGlobalLock) {
            return r.getRootTask().navigateUpTo(
                    r, destIntent, resolvedType, destGrants, resultCode, resultData, resultGrants);
        }
    }

    @Override
    public boolean releaseActivityInstance(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r == null || !r.isDestroyable()) {
                    return false;
                }
                r.destroyImmediately("app-req");
                return r.isState(DESTROYING, DESTROYED);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     *
     * @param token      The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * @param finishTask Whether to finish the task associated with this Activity.
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    @Override
    public boolean finishActivity(IBinder token, int resultCode, Intent resultData,
            int finishTask) {
        // Refuse possible leaked file descriptors.
        if (resultData != null && resultData.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        final ActivityRecord r;
        synchronized (mGlobalLock) {
            r = ActivityRecord.isInRootTaskLocked(token);
            if (r == null) {
                return true;
            }
        }

        // Carefully collect grants without holding lock.
        final NeededUriGrants resultGrants = mService.collectGrants(resultData, r.resultTo);

        synchronized (mGlobalLock) {
            // Check again in case activity was removed when collecting grants.
            if (!r.isInHistory()) {
                return true;
            }

            // Keep track of the root activity of the task before we finish it.
            final Task tr = r.getTask();
            final ActivityRecord rootR = tr.getRootActivity();
            if (rootR == null) {
                Slog.w(TAG, "Finishing task with all activities already finished");
            }
            // Do not allow task to finish if last task in lockTask mode. Launchable priv-apps can
            // finish.
            if (mService.getLockTaskController().activityBlockedFromFinish(r)) {
                return false;
            }

            // TODO: There is a dup. of this block of code in ActivityStack.navigateUpToLocked
            // We should consolidate.
            if (mService.mController != null) {
                // Find the first activity that is not finishing.
                final ActivityRecord next =
                        r.getRootTask().topRunningActivity(token, INVALID_TASK_ID);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mService.mController.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mService.mController = null;
                        Watchdog.getInstance().setActivityController(null);
                    }

                    if (!resumeOK) {
                        Slog.i(TAG, "Not finishing activity because controller resumed");
                        return false;
                    }
                }
            }

            // Note down that the process has finished an activity and is in background activity
            // starts grace period.
            if (r.app != null) {
                r.app.setLastActivityFinishTimeIfNeeded(SystemClock.uptimeMillis());
            }

            final long origId = Binder.clearCallingIdentity();
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "finishActivity");
            try {
                final boolean res;
                final boolean finishWithRootActivity =
                        finishTask == Activity.FINISH_TASK_WITH_ROOT_ACTIVITY;
                mTaskSupervisor.getBackgroundActivityLaunchController()
                        .onActivityRequestedFinishing(r);
                if (finishTask == Activity.FINISH_TASK_WITH_ACTIVITY
                        || (finishWithRootActivity && r == rootR)) {
                    // If requested, remove the task that is associated to this activity only if it
                    // was the root activity in the task. The result code and data is ignored
                    // because we don't support returning them across task boundaries. Also, to
                    // keep backwards compatibility we remove the task from recents when finishing
                    // task with root activity.
                    mTaskSupervisor.removeTask(tr, false /*killProcess*/,
                            finishWithRootActivity, "finish-activity", r.getUid(), r.getPid(),
                            r.info.name);
                    res = true;
                    // Explicitly dismissing the activity so reset its relaunch flag.
                    r.mRelaunchReason = RELAUNCH_REASON_NONE;
                } else {
                    r.finishIfPossible(resultCode, resultData, resultGrants, "app-request",
                            true /* oomAdj */);
                    res = r.finishing;
                    if (!res) {
                        Slog.i(TAG, "Failed to finish by app-request");
                    }
                }
                return res;
            } finally {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public boolean finishActivityAffinity(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r == null) {
                    return false;
                }

                // Do not allow task to finish if last task in lockTask mode. Launchable priv-apps
                // can finish.
                if (mService.getLockTaskController().activityBlockedFromFinish(r)) {
                    return false;
                }

                r.getTask().forAllActivities(activity -> r.finishIfSameAffinity(activity),
                        r /* boundary */, true /* includeBoundary */,
                        true /* traverseTopToBottom */);
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void finishSubActivity(IBinder token, String resultWho, int requestCode) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r == null) return;

                // TODO: This should probably only loop over the task since you need to be in the
                // same task to return results.
                r.getRootTask().forAllActivities(activity -> {
                    activity.finishIfSubActivity(r /* parent */, resultWho, requestCode);
                }, true /* traverseTopToBottom */);

                mService.updateOomAdj();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setForceSendResultForMediaProjection(IBinder token) {
        // Require that this is invoked only during MediaProjection setup.
        mService.mAmInternal.enforceCallingPermission(
                Manifest.permission.MANAGE_MEDIA_PROJECTION,
                "setForceSendResultForMediaProjection");

        final ActivityRecord r;
        synchronized (mGlobalLock) {
            r = ActivityRecord.isInRootTaskLocked(token);
            if (r == null || !r.isInHistory()) {
                return;
            }
            r.setForceSendResultForMediaProjection();
        }
    }

    @Override
    public boolean isTopOfTask(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            return r != null && r.getTask().getTopNonFinishingActivity() == r;
        }
    }

    @Override
    public boolean willActivityBeVisible(IBinder token) {
        synchronized (mGlobalLock) {
            final Task rootTask = ActivityRecord.getRootTask(token);
            return rootTask != null && rootTask.willActivityBeVisible(token);
        }
    }

    @Override
    public int getDisplayId(IBinder activityToken) {
        synchronized (mGlobalLock) {
            final Task rootTask = ActivityRecord.getRootTask(activityToken);
            if (rootTask != null) {
                final int displayId = rootTask.getDisplayId();
                return displayId != INVALID_DISPLAY ? displayId : DEFAULT_DISPLAY;
            }
            return DEFAULT_DISPLAY;
        }
    }

    @Override
    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r == null) {
                return INVALID_TASK_ID;
            }
            final Task task = r.getTask();
            if (onlyRoot) {
                return task.getRootActivity() == r ? task.mTaskId : INVALID_TASK_ID;
            }
            return task.mTaskId;
        }
    }

    /**
     * Returns the {@link Configuration} of the task which hosts the Activity, or {@code null} if
     * the task {@link Configuration} cannot be obtained.
     */
    @Override
    @Nullable
    public Configuration getTaskConfiguration(IBinder activityToken) {
        synchronized (mGlobalLock) {
            final ActivityRecord ar = ActivityRecord.isInAnyTask(activityToken);
            if (ar == null) {
                return null;
            }
            return ar.getTask().getConfiguration();
        }
    }

    @Override
    @Nullable
    public IBinder getActivityTokenBelow(IBinder activityToken) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord ar = ActivityRecord.isInAnyTask(activityToken);
                if (ar == null) {
                    return null;
                }
                // Exclude finishing activity.
                final ActivityRecord below = ar.getTask().getActivity((r) -> !r.finishing,
                        ar, false /*includeBoundary*/, true /*traverseTopToBottom*/);
                if (below != null && below.getUid() == ar.getUid()) {
                    return below.token;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return null;
    }

    @Override
    public ComponentName getCallingActivity(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = getCallingRecord(token);
            return r != null ? r.intent.getComponent() : null;
        }
    }

    @Override
    public String getCallingPackage(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = getCallingRecord(token);
            return r != null ? r.info.packageName : null;
        }
    }

    private static ActivityRecord getCallingRecord(IBinder token) {
        final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
        return r != null ? r.resultTo : null;
    }

    @Override
    public int getLaunchedFromUid(IBinder token) {
        return getUid(token, /* callerToken */ null, /* isActivityCallerCall */ false);
    }

    @Override
    public String getLaunchedFromPackage(IBinder token) {
        return getPackage(token, /* callerToken */ null, /* isActivityCallerCall */ false);
    }

    @Override
    public int getActivityCallerUid(IBinder activityToken, IBinder callerToken) {
        return getUid(activityToken, callerToken, /* isActivityCallerCall */ true);
    }

    @Override
    public String getActivityCallerPackage(IBinder activityToken, IBinder callerToken) {
        return getPackage(activityToken, callerToken, /* isActivityCallerCall */ true);
    }

    private int getUid(IBinder activityToken, IBinder callerToken, boolean isActivityCallerCall) {
        final int uid = Binder.getCallingUid();
        final boolean isInternalCaller = isInternalCallerGetLaunchedFrom(uid);
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(activityToken);
            if (r != null && (isInternalCaller || canGetLaunchedFromLocked(uid, r, callerToken,
                    isActivityCallerCall)) && isValidCaller(r, callerToken, isActivityCallerCall)) {
                return isActivityCallerCall ? r.getCallerUid(callerToken) : r.launchedFromUid;
            }
        }
        return INVALID_UID;
    }

    private String getPackage(IBinder activityToken, IBinder callerToken,
            boolean isActivityCallerCall) {
        final int uid = Binder.getCallingUid();
        final boolean isInternalCaller = isInternalCallerGetLaunchedFrom(uid);
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(activityToken);
            if (r != null && (isInternalCaller || canGetLaunchedFromLocked(uid, r, callerToken,
                    isActivityCallerCall)) && isValidCaller(r, callerToken, isActivityCallerCall)) {
                return isActivityCallerCall
                        ? r.getCallerPackage(callerToken) : r.launchedFromPackage;
            }
        }
        return null;
    }

    private boolean isValidCaller(ActivityRecord r, IBinder callerToken,
            boolean isActivityCallerCall) {
        return isActivityCallerCall ? r.hasCaller(callerToken) : callerToken == null;
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public int checkActivityCallerContentUriPermission(IBinder activityToken, IBinder callerToken,
            Uri uri, int modeFlags, int userId) {
        // 1. Check if we have access to the URI - > throw if we don't
        GrantUri grantUri = new GrantUri(userId, uri, modeFlags);
        if (!mService.mUgmInternal.checkUriPermission(grantUri, Binder.getCallingUid(), modeFlags,
                /* isFullAccessForContentUri */ true)) {
            throw new SecurityException("You don't have access to the content URI, hence can't"
                    + " check if the caller has access to it: " + uri);
        }

        // 2. Get the permission result for the caller
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(activityToken);
            if (r != null) {
                boolean granted = r.checkContentUriPermission(callerToken, grantUri, modeFlags);
                return granted ? PERMISSION_GRANTED : PERMISSION_DENIED;
            }
        }
        return PERMISSION_DENIED;
    }

    /** Whether the call to one of the getLaunchedFrom APIs is performed by an internal caller. */
    private boolean isInternalCallerGetLaunchedFrom(int uid) {
        if (UserHandle.getAppId(uid) == SYSTEM_UID) {
            return true;
        }
        final PackageManagerInternal pm = mService.mWindowManager.mPmInternal;
        final AndroidPackage callingPkg = pm.getPackage(uid);
        if (callingPkg == null) {
            return false;
        }
        if (callingPkg.isSignedWithPlatformKey()) {
            return true;
        }
        final String[] installerNames = pm.getKnownPackageNames(
                KnownPackages.PACKAGE_INSTALLER, UserHandle.getUserId(uid));
        return installerNames.length > 0 && callingPkg.getPackageName().equals(installerNames[0]);
    }

    /**
     * Returns whether the specified {@code uid} can access the launching app's identity by
     * verifying whether the provided {@code ActivityRecord r} has opted in to sharing its
     * identity or if the uid of the activity matches that of the launching app.
     */
    private static boolean canGetLaunchedFromLocked(int uid, ActivityRecord r,
            IBinder callerToken, boolean isActivityCallerCall) {
        if (CompatChanges.isChangeEnabled(ACCESS_SHARED_IDENTITY, uid)) {
            boolean isShareIdentityEnabled = isActivityCallerCall
                    ? r.isCallerShareIdentityEnabled(callerToken) : r.mShareIdentity;
            int callerUid = isActivityCallerCall ? r.getCallerUid(callerToken) : r.launchedFromUid;
            return isShareIdentityEnabled || callerUid == uid;
        }
        return false;
    }

    @Override
    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    EventLogTags.writeWmSetRequestedOrientation(requestedOrientation,
                            r.shortComponentName);
                    r.setRequestedOrientation(requestedOrientation);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int getRequestedOrientation(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            return r != null
                    ? r.getOverrideOrientation()
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    @Override
    public boolean convertFromTranslucent(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r == null) {
                    return false;
                }
                // Create a transition if the activity is playing in case the below activity didn't
                // commit invisible. That's because if any activity below this one has changed its
                // visibility while playing transition, there won't able to commit visibility until
                // the running transition finish.
                final Transition transition = r.mTransitionController.isShellTransitionsEnabled()
                        && !r.mTransitionController.isCollecting()
                        ? r.mTransitionController.createTransition(TRANSIT_TO_BACK) : null;
                final boolean changed = r.setOccludesParent(true);
                if (transition != null) {
                    if (changed) {
                        // Always set as scene transition because it expects to be a jump-cut.
                        transition.setOverrideAnimation(TransitionInfo.AnimationOptions
                                .makeSceneTransitionAnimOptions(), null, null);
                        r.mTransitionController.requestStartTransition(transition,
                                null /*startTask */, null /* remoteTransition */,
                                null /* displayChange */);
                        r.mTransitionController.setReady(r.getDisplayContent());
                    } else {
                        transition.abort();
                    }
                }
                return changed;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean convertToTranslucent(IBinder token, Bundle options) {
        final SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(options);
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r == null) {
                    return false;
                }
                final ActivityRecord under = r.getTask().getActivityBelow(r);
                if (under != null) {
                    under.returningOptions = safeOptions != null ? safeOptions.getOptions(r) : null;
                }
                // Create a transition to make sure the activity change is collected.
                final Transition transition = r.mTransitionController.isShellTransitionsEnabled()
                        && !r.mTransitionController.isCollecting()
                        ? r.mTransitionController.createTransition(TRANSIT_TO_FRONT) : null;
                final boolean changed = r.setOccludesParent(false);
                if (transition != null) {
                    if (changed) {
                        r.mTransitionController.requestStartTransition(transition,
                                null /*startTask */, null /* remoteTransition */,
                                null /* displayChange */);
                        r.mTransitionController.setReady(r.getDisplayContent());
                        if (under != null && under.returningOptions != null
                                && under.returningOptions.getAnimationType()
                                        == ANIM_SCENE_TRANSITION) {
                            // Pass along the scene-transition animation-type
                            transition.setOverrideAnimation(TransitionInfo.AnimationOptions
                                    .makeSceneTransitionAnimOptions(), null, null);
                        }
                    } else {
                        transition.abort();
                    }
                }
                return changed;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean isImmersive(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            return r.immersive;
        }
    }

    @Override
    public void setImmersive(IBinder token, boolean immersive) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            r.immersive = immersive;

            // Update associated state if we're frontmost.
            if (r.isFocusedActivityOnDisplay()) {
                ProtoLog.d(WM_DEBUG_IMMERSIVE, "Frontmost changed immersion: %s", r);
                mService.applyUpdateLockStateLocked(r);
            }
        }
    }

    @Override
    public boolean enterPictureInPictureMode(IBinder token, final PictureInPictureParams params) {
        final long origId = Binder.clearCallingIdentity();
        try {
            ensureSetPipAspectRatioQuotaTracker();
            synchronized (mGlobalLock) {
                final ActivityRecord r = ensureValidPictureInPictureActivityParams(
                        "enterPictureInPictureMode", token, params);
                return mService.enterPictureInPictureMode(r, params, true /* fromClient */);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setPictureInPictureParams(IBinder token, final PictureInPictureParams params) {
        final long origId = Binder.clearCallingIdentity();
        try {
            ensureSetPipAspectRatioQuotaTracker();
            synchronized (mGlobalLock) {
                final ActivityRecord r = ensureValidPictureInPictureActivityParams(
                        "setPictureInPictureParams", token, params);
                r.setPictureInPictureParams(params);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setShouldDockBigOverlays(IBinder token, boolean shouldDockBigOverlays) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                r.setShouldDockBigOverlays(shouldDockBigOverlays);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Splash screen view is attached to activity.
     */
    @Override
    public void splashScreenAttached(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            ActivityRecord.splashScreenAttachedLocked(token);
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void requestCompatCameraControl(IBinder token, boolean showControl,
            boolean transformationApplied, ICompatCameraControlCallback callback) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.updateCameraCompatState(showControl, transformationApplied, callback);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Initialize the {@link #mSetPipAspectRatioQuotaTracker} if applicable, which should happen
     * out of {@link #mGlobalLock} to avoid deadlock (AM lock is used in QuotaTrack ctor).
     */
    private void ensureSetPipAspectRatioQuotaTracker() {
        if (mSetPipAspectRatioQuotaTracker == null) {
            mSetPipAspectRatioQuotaTracker = new CountQuotaTracker(mContext,
                    Categorizer.SINGLE_CATEGORIZER);
            mSetPipAspectRatioQuotaTracker.setCountLimit(Category.SINGLE_CATEGORY,
                    SET_PIP_ASPECT_RATIO_LIMIT, SET_PIP_ASPECT_RATIO_TIME_WINDOW_MS);
        }
    }

    /**
     * Checks the state of the system and the activity associated with the given {@param token} to
     * verify that picture-in-picture is supported for that activity.
     *
     * @return the activity record for the given {@param token} if all the checks pass.
     */
    private ActivityRecord ensureValidPictureInPictureActivityParams(String caller,
            IBinder token, PictureInPictureParams params) {
        if (!mService.mSupportsPictureInPicture) {
            throw new IllegalStateException(caller
                    + ": Device doesn't support picture-in-picture mode.");
        }

        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            throw new IllegalStateException(caller
                    + ": Can't find activity for token=" + token);
        }

        if (!r.supportsPictureInPicture()) {
            throw new IllegalStateException(caller
                    + ": Current activity does not support picture-in-picture.");
        }

        // Rate limit how frequent an app can request aspect ratio change via
        // Activity#setPictureInPictureParams
        final int userId = UserHandle.getCallingUserId();
        if (r.pictureInPictureArgs.hasSetAspectRatio()
                && params.hasSetAspectRatio()
                && !r.pictureInPictureArgs.getAspectRatio().equals(
                params.getAspectRatio())
                && !mSetPipAspectRatioQuotaTracker.noteEvent(
                userId, r.packageName, "setPipAspectRatio")) {
            throw new IllegalStateException(caller
                    + ": Too many PiP aspect ratio change requests from " + r.packageName);
        }

        final float minAspectRatio = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        final float maxAspectRatio = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);

        if (params.hasSetAspectRatio()
                && !mService.mWindowManager.isValidPictureInPictureAspectRatio(
                r.mDisplayContent, params.getAspectRatioFloat())) {
            throw new IllegalArgumentException(String.format(caller
                            + ": Aspect ratio is too extreme (must be between %f and %f).",
                    minAspectRatio, maxAspectRatio));
        }

        if (mService.mSupportsExpandedPictureInPicture && params.hasSetExpandedAspectRatio()
                && !mService.mWindowManager.isValidExpandedPictureInPictureAspectRatio(
                r.mDisplayContent, params.getExpandedAspectRatioFloat())) {
            throw new IllegalArgumentException(String.format(caller
                            + ": Expanded aspect ratio is not extreme enough (must not be between"
                            + " %f and %f).",
                    minAspectRatio, maxAspectRatio));
        }

        // Truncate the number of actions if necessary.
        params.truncateActions(ActivityTaskManager.getMaxNumPictureInPictureActions(mContext));
        return r;
    }

    /**
     * Requests that an activity should enter picture-in-picture mode if possible. This method may
     * be used by the implementation of non-phone form factors.
     *
     * @return false if the activity cannot enter PIP mode.
     */
    boolean requestPictureInPictureMode(@NonNull ActivityRecord r) {
        if (r.inPinnedWindowingMode()) {
            return false;
        }

        final boolean canEnterPictureInPicture = r.checkEnterPictureInPictureState(
                "requestPictureInPictureMode", /* beforeStopping */ false);
        if (!canEnterPictureInPicture) {
            return false;
        }

        if (r.pictureInPictureArgs.isAutoEnterEnabled()) {
            return mService.enterPictureInPictureMode(r, r.pictureInPictureArgs,
                    false /* fromClient */);
        }

        try {
            mService.getLifecycleManager().scheduleTransactionItem(r.app.getThread(),
                    EnterPipRequestedItem.obtain(r.token));
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to send enter pip requested item: "
                    + r.intent.getComponent(), e);
            return false;
        }
    }

    /**
     * Alert the client that the Picture-in-Picture state has changed.
     */
    void onPictureInPictureUiStateChanged(@NonNull ActivityRecord r,
            PictureInPictureUiState pipState) {
        try {
            mService.getLifecycleManager().scheduleTransactionItem(r.app.getThread(),
                    PipStateTransactionItem.obtain(r.token, pipState));
        } catch (Exception e) {
            Slog.w(TAG, "Failed to send pip state transaction item: "
                    + r.intent.getComponent(), e);
        }
    }

    @Override
    public void toggleFreeformWindowingMode(IBinder token) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException(
                            "toggleFreeformWindowingMode: No activity record matching token="
                                    + token);
                }

                final Task rootTask = r.getRootTask();
                if (rootTask == null) {
                    throw new IllegalStateException("toggleFreeformWindowingMode: the activity "
                            + "doesn't have a root task");
                }

                if (!rootTask.inFreeformWindowingMode()
                        && rootTask.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
                    throw new IllegalStateException("toggleFreeformWindowingMode: You can only "
                            + "toggle between fullscreen and freeform.");
                }

                if (rootTask.inFreeformWindowingMode()) {
                    rootTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
                    rootTask.setBounds(null);
                } else if (!r.supportsFreeform()) {
                    throw new IllegalStateException(
                            "This activity is currently not freeform-enabled");
                } else if (rootTask.getParent().inFreeformWindowingMode()) {
                    // If the window is on a freeform display, set it to undefined. It will be
                    // resolved to freeform and it can adjust windowing mode when the display mode
                    // changes in runtime.
                    rootTask.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                } else {
                    rootTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private @FullscreenRequestHandler.RequestResult int validateMultiwindowFullscreenRequestLocked(
            Task topFocusedRootTask, int fullscreenRequest, ActivityRecord requesterActivity) {
        if (requesterActivity.getWindowingMode() == WINDOWING_MODE_PINNED) {
            return RESULT_APPROVED;
        }
        // If this is not coming from the currently top-most activity, reject the request.
        if (requesterActivity != topFocusedRootTask.getTopMostActivity()) {
            return RESULT_FAILED_NOT_TOP_FOCUSED;
        }
        if (fullscreenRequest == FULLSCREEN_MODE_REQUEST_EXIT) {
            if (topFocusedRootTask.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
                return RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY;
            }
            if (topFocusedRootTask.mMultiWindowRestoreWindowingMode == INVALID_WINDOWING_MODE) {
                return RESULT_FAILED_NOT_IN_FULLSCREEN_WITH_HISTORY;
            }
        }
        return RESULT_APPROVED;
    }

    @Override
    public void requestMultiwindowFullscreen(IBinder callingActivity, int fullscreenRequest,
            IRemoteCallback callback) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                requestMultiwindowFullscreenLocked(callingActivity, fullscreenRequest, callback);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void requestMultiwindowFullscreenLocked(IBinder callingActivity, int fullscreenRequest,
            IRemoteCallback callback) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(callingActivity);
        if (r == null) {
            return;
        }

        // If the shell transition is not enabled, just execute and done.
        final TransitionController controller = r.mTransitionController;
        if (!controller.isShellTransitionsEnabled()) {
            final @FullscreenRequestHandler.RequestResult int validateResult;
            final Task topFocusedRootTask;
            topFocusedRootTask = mService.getTopDisplayFocusedRootTask();
            validateResult = validateMultiwindowFullscreenRequestLocked(topFocusedRootTask,
                    fullscreenRequest, r);
            reportMultiwindowFullscreenRequestValidatingResult(callback, validateResult);
            if (validateResult == RESULT_APPROVED) {
                executeMultiWindowFullscreenRequest(fullscreenRequest, topFocusedRootTask);
            }
            return;
        }
        // Initiate the transition.
        final Transition transition = new Transition(TRANSIT_CHANGE, 0 /* flags */, controller,
                mService.mWindowManager.mSyncEngine);
        r.mTransitionController.startCollectOrQueue(transition,
                (deferred) -> {
                    executeFullscreenRequestTransition(fullscreenRequest, callback, r,
                            transition, deferred);
                });
    }

    private void executeFullscreenRequestTransition(int fullscreenRequest, IRemoteCallback callback,
            ActivityRecord r, Transition transition, boolean queued) {
        final @FullscreenRequestHandler.RequestResult int validateResult;
        final Task topFocusedRootTask;
        topFocusedRootTask = mService.getTopDisplayFocusedRootTask();
        validateResult = validateMultiwindowFullscreenRequestLocked(topFocusedRootTask,
                fullscreenRequest, r);
        reportMultiwindowFullscreenRequestValidatingResult(callback, validateResult);
        if (validateResult != RESULT_APPROVED) {
            transition.abort();
            return;
        }
        final Task requestingTask = r.getTask();
        transition.collect(requestingTask);
        executeMultiWindowFullscreenRequest(fullscreenRequest, requestingTask);
        r.mTransitionController.requestStartTransition(transition, requestingTask,
                null /* remoteTransition */, null /* displayChange */);
        transition.setReady(requestingTask, true);
    }

    private static void reportMultiwindowFullscreenRequestValidatingResult(IRemoteCallback callback,
            @FullscreenRequestHandler.RequestResult int result) {
        if (callback == null) {
            return;
        }
        Bundle res = new Bundle();
        res.putInt(REMOTE_CALLBACK_RESULT_KEY, result);
        try {
            callback.sendResult(res);
        } catch (RemoteException e) {
            Slog.w(TAG, "client throws an exception back to the server, ignore it");
        }
    }

    private static void executeMultiWindowFullscreenRequest(int fullscreenRequest, Task requester) {
        final int targetWindowingMode;
        if (fullscreenRequest == FULLSCREEN_MODE_REQUEST_ENTER) {
            final int restoreWindowingMode = requester.getRequestedOverrideWindowingMode();
            targetWindowingMode = WINDOWING_MODE_FULLSCREEN;
            requester.setWindowingMode(targetWindowingMode);
            // The restore windowing mode must be set after the windowing mode is set since
            // Task#setWindowingMode resets the restore windowing mode to WINDOWING_MODE_INVALID.
            requester.mMultiWindowRestoreWindowingMode = restoreWindowingMode;
        } else {
            targetWindowingMode = requester.mMultiWindowRestoreWindowingMode;
            requester.setWindowingMode(targetWindowingMode);
        }
        if (targetWindowingMode == WINDOWING_MODE_FULLSCREEN) {
            requester.setBounds(null);
        }
    }

    @Override
    public void startLockTaskModeByToken(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                mService.startLockTaskMode(r.getTask(), false /* isSystemCaller */);
            }
        }
    }

    @Override
    public void stopLockTaskModeByToken(IBinder token) {
        mService.stopLockTaskModeInternal(token, false /* isSystemCaller */);
    }

    @Override
    public void showLockTaskEscapeMessage(IBinder token) {
        synchronized (mGlobalLock) {
            if (ActivityRecord.forTokenLocked(token) != null) {
                mService.getLockTaskController().showLockTaskToast();
            }
        }
    }

    @Override
    public void setTaskDescription(IBinder token, ActivityManager.TaskDescription td) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r != null) {
                r.setTaskDescription(td);
            }
        }
    }

    @Override
    public boolean showAssistFromActivity(IBinder token, Bundle args) {
        final long ident = Binder.clearCallingIdentity();
        try {
            final String callingAttributionTag;
            synchronized (mGlobalLock) {
                final ActivityRecord caller = ActivityRecord.forTokenLocked(token);
                final Task topRootTask = mService.getTopDisplayFocusedRootTask();
                final ActivityRecord top = topRootTask != null
                        ? topRootTask.getTopNonFinishingActivity() : null;
                if (top != caller) {
                    Slog.w(TAG, "showAssistFromActivity failed: caller " + caller
                            + " is not current top " + top);
                    return false;
                }
                if (!top.nowVisible) {
                    Slog.w(TAG, "showAssistFromActivity failed: caller " + caller
                            + " is not visible");
                    return false;
                }
                callingAttributionTag = top.launchedFromFeatureId;
            }
            return mAssistUtils.showSessionForActiveService(args, SHOW_SOURCE_APPLICATION,
                    callingAttributionTag, null /* showCallback */, token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isRootVoiceInteraction(IBinder token) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            return r != null && r.rootVoiceInteraction;
        }
    }

    @Override
    public void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
        Slog.i(TAG, "Activity tried to startLocalVoiceInteraction");
        final String callingAttributionTag;
        synchronized (mGlobalLock) {
            final Task topRootTask = mService.getTopDisplayFocusedRootTask();
            final ActivityRecord activity = topRootTask != null
                    ? topRootTask.getTopNonFinishingActivity() : null;
            if (ActivityRecord.forTokenLocked(callingActivity) != activity) {
                throw new SecurityException("Only focused activity can call startVoiceInteraction");
            }
            if (mService.mRunningVoice != null || activity.getTask().voiceSession != null
                    || activity.voiceSession != null) {
                Slog.w(TAG, "Already in a voice interaction, cannot start new voice interaction");
                return;
            }
            if (activity.pendingVoiceInteractionStart) {
                Slog.w(TAG, "Pending start of voice interaction already.");
                return;
            }
            activity.pendingVoiceInteractionStart = true;
            callingAttributionTag = activity.launchedFromFeatureId;
        }
        LocalServices.getService(VoiceInteractionManagerInternal.class)
                .startLocalVoiceInteraction(callingActivity, callingAttributionTag, options);
    }

    @Override
    public void stopLocalVoiceInteraction(IBinder callingActivity) {
        LocalServices.getService(VoiceInteractionManagerInternal.class)
                .stopLocalVoiceInteraction(callingActivity);
    }

    @Override
    public void setShowWhenLocked(IBinder token, boolean showWhenLocked) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.setShowWhenLocked(showWhenLocked);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setInheritShowWhenLocked(IBinder token, boolean inheritShowWhenLocked) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.setInheritShowWhenLocked(inheritShowWhenLocked);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setTurnScreenOn(IBinder token, boolean turnScreenOn) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.setTurnScreenOn(turnScreenOn);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void setAllowCrossUidActivitySwitchFromBelow(IBinder token, boolean allowed) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.setAllowCrossUidActivitySwitchFromBelow(allowed);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void reportActivityFullyDrawn(IBinder token, boolean restoredFromBundle) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    mTaskSupervisor.getActivityMetricsLogger().notifyFullyDrawn(r,
                            restoredFromBundle);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void overrideActivityTransition(IBinder token, boolean open, int enterAnim, int exitAnim,
            int backgroundColor) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r != null) {
                r.overrideCustomTransition(open, enterAnim, exitAnim, backgroundColor);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void clearOverrideActivityTransition(IBinder token, boolean open) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r != null) {
                r.clearCustomTransition(open);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim, @ColorInt int backgroundColor) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
            if (r != null && r.isState(RESUMED, PAUSING)) {
                r.mDisplayContent.mAppTransition.overridePendingAppTransition(
                        packageName, enterAnim, exitAnim, backgroundColor, null, null,
                        r.mOverrideTaskTransition);
                r.mTransitionController.setOverrideAnimation(
                        TransitionInfo.AnimationOptions.makeCustomAnimOptions(packageName,
                                enterAnim, exitAnim, backgroundColor, r.mOverrideTaskTransition),
                        null /* startCallback */, null /* finishCallback */);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public int setVrMode(IBinder token, boolean enabled, ComponentName packageName) {
        mService.enforceSystemHasVrFeature();

        final VrManagerInternal vrService = LocalServices.getService(VrManagerInternal.class);
        final ActivityRecord r;
        synchronized (mGlobalLock) {
            r = ActivityRecord.isInRootTaskLocked(token);
        }
        if (r == null) {
            throw new IllegalArgumentException();
        }

        final int err;
        if ((err = vrService.hasVrPackage(packageName, r.mUserId)) != VrManagerInternal.NO_ERROR) {
            return err;
        }

        // Clear the binder calling uid since this path may call moveToTask().
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                r.requestedVrComponent = (enabled) ? packageName : null;

                // Update associated state if this activity is currently focused.
                if (r.isFocusedActivityOnDisplay()) {
                    mService.applyUpdateVrModeLocked(r);
                }
                return 0;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void setRecentsScreenshotEnabled(IBinder token, boolean enabled) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.setRecentsScreenshotEnabled(enabled);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void restartActivityProcessIfVisible(IBinder token) {
        ActivityTaskManagerService.enforceTaskPermission("restartActivityProcess");
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.restartProcessIfVisible();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Removes the outdated home task snapshot.
     *
     * @param token The token of the home task, or null if you have the
     *              {@link android.Manifest.permission#MANAGE_ACTIVITY_TASKS}
     *              permission and want us to find the home task token for you.
     */
    @Override
    public void invalidateHomeTaskSnapshot(IBinder token) {
        if (token == null) {
            ActivityTaskManagerService.enforceTaskPermission("invalidateHomeTaskSnapshot");
        }

        synchronized (mGlobalLock) {
            final ActivityRecord r;
            if (token == null) {
                final Task rootTask =
                        mService.mRootWindowContainer.getDefaultTaskDisplayArea().getRootHomeTask();
                r = rootTask != null ? rootTask.topRunningActivity() : null;
            } else {
                r = ActivityRecord.isInRootTaskLocked(token);
            }

            if (r != null && r.isActivityTypeHome()) {
                mService.mWindowManager.mTaskSnapshotController.removeSnapshotCache(
                        r.getTask().mTaskId);
            }
        }
    }

    @Override
    public void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback,
            CharSequence message) {
        if (message != null) {
            mService.mAmInternal.enforceCallingPermission(
                    android.Manifest.permission.SHOW_KEYGUARD_MESSAGE, "dismissKeyguard");
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mService.mKeyguardController.dismissKeyguard(token, callback, message);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void registerRemoteAnimations(IBinder token, RemoteAnimationDefinition definition) {
        mService.mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "registerRemoteAnimations");
        definition.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.registerRemoteAnimations(definition);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterRemoteAnimations(IBinder token) {
        mService.mAmInternal.enforceCallingPermission(CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS,
                "unregisterRemoteAnimations");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r != null) {
                    r.unregisterRemoteAnimations();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Return {@code true} when the given Activity is a relative Task root. That is, the rest of
     * the Activities in the Task should be finished when it finishes. Otherwise, return {@code
     * false}.
     */
    private static boolean isRelativeTaskRootActivity(ActivityRecord r, ActivityRecord taskRoot) {
        // Not a relative root if the given Activity is not the root Activity of its TaskFragment.
        final TaskFragment taskFragment = r.getTaskFragment();
        if (r != taskFragment.getActivity(ar -> !ar.finishing || ar == r,
                false /* traverseTopToBottom */)) {
            return false;
        }

        // The given Activity is the relative Task root if its TaskFragment is a companion
        // TaskFragment to the taskRoot (i.e. the taskRoot TF will be finished together).
        return taskRoot.getTaskFragment().getCompanionTaskFragment() == taskFragment;
    }

    private static boolean isTopActivityInTaskFragment(ActivityRecord activity) {
        return activity.getTaskFragment().topRunningActivity() == activity;
    }

    private void requestCallbackFinish(IRequestFinishCallback callback) {
        try {
            callback.requestFinish();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to invoke request finish callback", e);
        }
    }

    @Override
    public void onBackPressed(IBinder token, IRequestFinishCallback callback) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
                if (r == null) return;

                final Task task = r.getTask();
                final ActivityRecord root = task.getRootActivity(false /*ignoreRelinquishIdentity*/,
                        true /*setToBottomIfNone*/);
                if (r == root && mService.mWindowOrganizerController.mTaskOrganizerController
                        .handleInterceptBackPressedOnTaskRoot(r.getRootTask())) {
                    // This task is handled by a task organizer that has requested the back
                    // pressed callback.
                    return;
                }
                if (shouldMoveTaskToBack(r, root)) {
                    moveActivityTaskToBack(token, true /* nonRoot */);
                    return;
                }
            }

            // The default option for handling the back button is to finish the Activity.
            requestCallbackFinish(callback);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    static boolean shouldMoveTaskToBack(ActivityRecord r, ActivityRecord rootActivity) {
        if (r != rootActivity && !isRelativeTaskRootActivity(r, rootActivity)) {
            return false;
        }
        final boolean isBaseActivity = rootActivity.mActivityComponent.equals(
                r.getTask().realActivity);
        final Intent baseActivityIntent = isBaseActivity ? rootActivity.intent : null;

        // If the activity was launched directly from the home screen, then we should
        // refrain from finishing the activity and instead move it to the back to keep it in
        // memory. The requirements for this are:
        //   1. The activity is the last running activity in the task.
        //   2. The current activity is the base activity for the task.
        //   3. The activity was launched by the home process, and is one of the main entry
        //      points for the application.
        return baseActivityIntent != null
                && isTopActivityInTaskFragment(r)
                && rootActivity.isLaunchSourceType(ActivityRecord.LAUNCH_SOURCE_TYPE_HOME)
                && ActivityRecord.isMainIntent(baseActivityIntent);
    }

    @Override
    public void enableTaskLocaleOverride(IBinder token) {
        if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
            // Only allow system to align locale.
            return;
        }

        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(token);
            if (r != null) {
                r.getTask().mAlignActivityLocaleWithTask = true;
            }
        }
    }

    /**
     * Returns {@code true} if the activity was explicitly requested to be launched in its
     * current TaskFragment.
     *
     * @see ActivityRecord#mRequestedLaunchingTaskFragmentToken
     */
    public boolean isRequestedToLaunchInTaskFragment(IBinder activityToken,
            IBinder taskFragmentToken) {
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.isInRootTaskLocked(activityToken);
            if (r == null) return false;

            return r.mRequestedLaunchingTaskFragmentToken == taskFragmentToken;
        }
    }

    @Override
    public void setActivityRecordInputSinkEnabled(IBinder activityToken, boolean enabled) {
        if (!allowDisableActivityRecordInputSink()) {
            return;
        }

        mService.mAmInternal.enforceCallingPermission(
                Manifest.permission.INTERNAL_SYSTEM_WINDOW, "setActivityRecordInputSinkEnabled");
        synchronized (mGlobalLock) {
            final ActivityRecord r = ActivityRecord.forTokenLocked(activityToken);
            if (r != null) {
                r.mActivityRecordInputSinkEnabled = enabled;
            }
        }
    }
}
