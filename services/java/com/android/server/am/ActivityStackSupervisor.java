/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import static android.Manifest.permission.START_ANY_ACTIVITY;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.android.server.am.ActivityManagerService.localLOGV;
import static com.android.server.am.ActivityManagerService.DEBUG_CONFIGURATION;
import static com.android.server.am.ActivityManagerService.DEBUG_FOCUS;
import static com.android.server.am.ActivityManagerService.DEBUG_PAUSE;
import static com.android.server.am.ActivityManagerService.DEBUG_RESULTS;
import static com.android.server.am.ActivityManagerService.DEBUG_STACK;
import static com.android.server.am.ActivityManagerService.DEBUG_SWITCH;
import static com.android.server.am.ActivityManagerService.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerService.DEBUG_USER_LEAVING;
import static com.android.server.am.ActivityManagerService.FIRST_SUPERVISOR_STACK_MSG;
import static com.android.server.am.ActivityManagerService.TAG;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IThumbnailReceiver;
import android.app.PendingIntent;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityManager.WaitResult;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.os.TransferPipe;
import com.android.server.am.ActivityManagerService.PendingActivityLaunch;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.wm.StackBox;
import com.android.server.wm.WindowManagerService;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public final class ActivityStackSupervisor {
    static final boolean DEBUG = ActivityManagerService.DEBUG || false;
    static final boolean DEBUG_ADD_REMOVE = DEBUG || false;
    static final boolean DEBUG_APP = DEBUG || false;
    static final boolean DEBUG_SAVED_STATE = DEBUG || false;
    static final boolean DEBUG_STATES = DEBUG || false;
    static final boolean DEBUG_IDLE = DEBUG || false;

    public static final int HOME_STACK_ID = 0;

    /** How long we wait until giving up on the last activity telling us it is idle. */
    static final int IDLE_TIMEOUT = 10*1000;

    /** How long we can hold the sleep wake lock before giving up. */
    static final int SLEEP_TIMEOUT = 5*1000;

    // How long we can hold the launch wake lock before giving up.
    static final int LAUNCH_TIMEOUT = 10*1000;

    static final int IDLE_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG;
    static final int IDLE_NOW_MSG = FIRST_SUPERVISOR_STACK_MSG + 1;
    static final int RESUME_TOP_ACTIVITY_MSG = FIRST_SUPERVISOR_STACK_MSG + 2;
    static final int SLEEP_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 3;
    static final int LAUNCH_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 4;

    // For debugging to make sure the caller when acquiring/releasing our
    // wake lock is the system process.
    static final boolean VALIDATE_WAKE_LOCK_CALLER = false;

    final ActivityManagerService mService;
    final Context mContext;
    final Looper mLooper;

    final ActivityStackSupervisorHandler mHandler;

    /** Short cut */
    WindowManagerService mWindowManager;

    /** Dismiss the keyguard after the next activity is displayed? */
    boolean mDismissKeyguardOnNextActivity = false;

    /** Identifier counter for all ActivityStacks */
    private int mLastStackId = HOME_STACK_ID;

    /** Task identifier that activities are currently being started in.  Incremented each time a
     * new task is created. */
    private int mCurTaskId = 0;

    /** The current user */
    private int mCurrentUser;

    /** The stack containing the launcher app */
    private ActivityStack mHomeStack;

    /** The non-home stack currently receiving input or launching the next activity. If home is
     * in front then mHomeStack overrides mFocusedStack.
     * DO NOT ACCESS DIRECTLY - It may be null, use getFocusedStack() */
    private ActivityStack mFocusedStack;

    /** All the non-launcher stacks */
    private ArrayList<ActivityStack> mStacks = new ArrayList<ActivityStack>();

    private static final int STACK_STATE_HOME_IN_FRONT = 0;
    private static final int STACK_STATE_HOME_TO_BACK = 1;
    private static final int STACK_STATE_HOME_IN_BACK = 2;
    private static final int STACK_STATE_HOME_TO_FRONT = 3;
    private int mStackState = STACK_STATE_HOME_IN_FRONT;

    /** List of activities that are waiting for a new activity to become visible before completing
     * whatever operation they are supposed to do. */
    final ArrayList<ActivityRecord> mWaitingVisibleActivities = new ArrayList<ActivityRecord>();

    /** List of processes waiting to find out about the next visible activity. */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityVisible =
            new ArrayList<IActivityManager.WaitResult>();

    /** List of processes waiting to find out about the next launched activity. */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityLaunched =
            new ArrayList<IActivityManager.WaitResult>();

    /** List of activities that are ready to be stopped, but waiting for the next activity to
     * settle down before doing so. */
    final ArrayList<ActivityRecord> mStoppingActivities = new ArrayList<ActivityRecord>();

    /** List of activities that are ready to be finished, but waiting for the previous activity to
     * settle down before doing so.  It contains ActivityRecord objects. */
    final ArrayList<ActivityRecord> mFinishingActivities = new ArrayList<ActivityRecord>();

    /** List of activities that are in the process of going to sleep. */
    final ArrayList<ActivityRecord> mGoingToSleepActivities = new ArrayList<ActivityRecord>();

    /** List of ActivityRecord objects that have been finished and must still report back to a
     * pending thumbnail receiver. */
    final ArrayList<ActivityRecord> mCancelledThumbnails = new ArrayList<ActivityRecord>();

    /** Used on user changes */
    final ArrayList<UserStartedState> mStartingUsers = new ArrayList<UserStartedState>();

    /** Set to indicate whether to issue an onUserLeaving callback when a newly launched activity
     * is being brought in front of us. */
    boolean mUserLeaving = false;

    /** Set when we have taken too long waiting to go to sleep. */
    boolean mSleepTimeout = false;

    /**
     * We don't want to allow the device to go to sleep while in the process
     * of launching an activity.  This is primarily to allow alarm intent
     * receivers to launch an activity and get that to run before the device
     * goes back to sleep.
     */
    final PowerManager.WakeLock mLaunchingActivity;

    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    final PowerManager.WakeLock mGoingToSleep;

    /** Stack id of the front stack when user switched, indexed by userId. */
    SparseIntArray mUserStackInFront = new SparseIntArray(2);

    public ActivityStackSupervisor(ActivityManagerService service, Context context,
            Looper looper) {
        mService = service;
        mContext = context;
        mLooper = looper;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mGoingToSleep = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        mHandler = new ActivityStackSupervisorHandler(looper);
        if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != Process.myUid()) {
            throw new IllegalStateException("Calling must be system uid");
        }
        mLaunchingActivity =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Launch");
        mLaunchingActivity.setReferenceCounted(false);
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        mHomeStack = new ActivityStack(mService, mContext, mLooper, HOME_STACK_ID);
        mStacks.add(mHomeStack);
    }

    void dismissKeyguard() {
        if (ActivityManagerService.DEBUG_LOCKSCREEN) mService.logLockScreen("");
        if (mDismissKeyguardOnNextActivity) {
            mDismissKeyguardOnNextActivity = false;
            mWindowManager.dismissKeyguard();
        }
    }

    ActivityStack getFocusedStack() {
        if (mFocusedStack == null) {
            return mHomeStack;
        }
        switch (mStackState) {
            case STACK_STATE_HOME_IN_FRONT:
            case STACK_STATE_HOME_TO_FRONT:
                return mHomeStack;
            case STACK_STATE_HOME_IN_BACK:
            case STACK_STATE_HOME_TO_BACK:
            default:
                return mFocusedStack;
        }
    }

    ActivityStack getLastStack() {
        switch (mStackState) {
            case STACK_STATE_HOME_IN_FRONT:
            case STACK_STATE_HOME_TO_BACK:
                return mHomeStack;
            case STACK_STATE_HOME_TO_FRONT:
            case STACK_STATE_HOME_IN_BACK:
            default:
                return mFocusedStack;
        }
    }

    boolean isFrontStack(ActivityStack stack) {
        return !(stack.isHomeStack() ^ getFocusedStack().isHomeStack());
    }

    void moveHomeStack(boolean toFront) {
        final boolean homeInFront = isFrontStack(mHomeStack);
        if (homeInFront ^ toFront) {
            if (DEBUG_STACK) Slog.d(TAG, "moveHomeTask: mStackState old=" +
                    stackStateToString(mStackState) + " new=" + stackStateToString(homeInFront ?
                    STACK_STATE_HOME_TO_BACK : STACK_STATE_HOME_TO_FRONT));
            mStackState = homeInFront ? STACK_STATE_HOME_TO_BACK : STACK_STATE_HOME_TO_FRONT;
        }
    }

    void moveHomeToTop() {
        moveHomeStack(true);
        mHomeStack.moveHomeTaskToTop();
    }

    boolean resumeHomeActivity(ActivityRecord prev) {
        moveHomeToTop();
        if (prev != null) {
            prev.task.mOnTopOfHome = false;
        }
        ActivityRecord r = mHomeStack.topRunningActivityLocked(null);
        if (r != null && r.isHomeActivity()) {
            mService.setFocusedActivityLocked(r);
            return resumeTopActivitiesLocked(mHomeStack, prev, null);
        }
        return mService.startHomeActivityLocked(mCurrentUser);
    }

    void setDismissKeyguard(boolean dismiss) {
        if (ActivityManagerService.DEBUG_LOCKSCREEN) mService.logLockScreen(" dismiss=" + dismiss);
        mDismissKeyguardOnNextActivity = dismiss;
    }

    TaskRecord anyTaskForIdLocked(int id) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            ActivityStack stack = mStacks.get(stackNdx);
            TaskRecord task = stack.taskForIdLocked(id);
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    ActivityRecord isInAnyStackLocked(IBinder token) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityRecord r = mStacks.get(stackNdx).isInStackLocked(token);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    int getNextTaskId() {
        do {
            mCurTaskId++;
            if (mCurTaskId <= 0) {
                mCurTaskId = 1;
            }
        } while (anyTaskForIdLocked(mCurTaskId) != null);
        return mCurTaskId;
    }

    void removeTask(TaskRecord task) {
        mWindowManager.removeTask(task.taskId);
        final ActivityStack stack = task.stack;
        final ActivityRecord r = stack.mResumedActivity;
        if (r != null && r.task == task) {
            stack.mResumedActivity = null;
        }
        if (stack.removeTask(task) && !stack.isHomeStack()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing stack " + stack);
            mStacks.remove(stack);
            final int stackId = stack.mStackId;
            final int nextStackId = mWindowManager.removeStack(stackId);
            // TODO: Perhaps we need to let the ActivityManager determine the next focus...
            if (mFocusedStack == null || mFocusedStack.mStackId == stackId) {
                // If this is the last app stack, set mFocusedStack to null.
                mFocusedStack = nextStackId == HOME_STACK_ID ? null : getStack(nextStackId);
            }
        }
    }

    ActivityRecord resumedAppLocked() {
        ActivityStack stack = getFocusedStack();
        if (stack == null) {
            return null;
        }
        ActivityRecord resumedActivity = stack.mResumedActivity;
        if (resumedActivity == null || resumedActivity.app == null) {
            resumedActivity = stack.mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                resumedActivity = stack.topRunningActivityLocked(null);
            }
        }
        return resumedActivity;
    }

    boolean attachApplicationLocked(ProcessRecord app, boolean headless) throws Exception {
        boolean didSomething = false;
        final String processName = app.processName;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (!isFrontStack(stack)) {
                continue;
            }
            ActivityRecord hr = stack.topRunningActivityLocked(null);
            if (hr != null) {
                if (hr.app == null && app.uid == hr.info.applicationInfo.uid
                        && processName.equals(hr.processName)) {
                    try {
                        if (headless) {
                            Slog.e(TAG, "Starting activities not supported on headless device: "
                                    + hr);
                        } else if (realStartActivityLocked(hr, app, true, true)) {
                            didSomething = true;
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception in new application when starting activity "
                              + hr.intent.getComponent().flattenToShortString(), e);
                        throw e;
                    }
                }
            }
        }
        if (!didSomething) {
            ensureActivitiesVisibleLocked(null, 0);
        }
        return didSomething;
    }

    boolean allResumedActivitiesIdle() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (!isFrontStack(stack)) {
                continue;
            }
            final ActivityRecord resumedActivity = stack.mResumedActivity;
            if (resumedActivity == null || !resumedActivity.idle) {
                return false;
            }
        }
        return true;
    }

    boolean allResumedActivitiesComplete() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (isFrontStack(stack)) {
                final ActivityRecord r = stack.mResumedActivity;
                if (r != null && r.state != ActivityState.RESUMED) {
                    return false;
                }
            }
        }
        // TODO: Not sure if this should check if all Paused are complete too.
        switch (mStackState) {
            case STACK_STATE_HOME_TO_BACK:
                if (DEBUG_STACK) Slog.d(TAG, "allResumedActivitiesComplete: mStackState old=" +
                        stackStateToString(STACK_STATE_HOME_TO_BACK) + " new=" +
                        stackStateToString(STACK_STATE_HOME_IN_BACK));
                mStackState = STACK_STATE_HOME_IN_BACK;
                break;
            case STACK_STATE_HOME_TO_FRONT:
                if (DEBUG_STACK) Slog.d(TAG, "allResumedActivitiesComplete: mStackState old=" +
                        stackStateToString(STACK_STATE_HOME_TO_FRONT) + " new=" +
                        stackStateToString(STACK_STATE_HOME_IN_FRONT));
                mStackState = STACK_STATE_HOME_IN_FRONT;
                break;
        }
        return true;
    }

    boolean allResumedActivitiesVisible() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            final ActivityRecord r = stack.mResumedActivity;
            if (r != null && (!r.nowVisible || r.waitingVisible)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pause all activities in either all of the stacks or just the back stacks.
     * @param userLeaving Passed to pauseActivity() to indicate whether to call onUserLeaving().
     * @return true if any activity was paused as a result of this call.
     */
    boolean pauseBackStacks(boolean userLeaving) {
        boolean someActivityPaused = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (!isFrontStack(stack) && stack.mResumedActivity != null) {
                if (DEBUG_STATES) Slog.d(TAG, "pauseBackStacks: stack=" + stack +
                        " mResumedActivity=" + stack.mResumedActivity);
                stack.startPausingLocked(userLeaving, false);
                someActivityPaused = true;
            }
        }
        return someActivityPaused;
    }

    boolean allPausedActivitiesComplete() {
        boolean pausing = true;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            final ActivityRecord r = stack.mPausingActivity;
            if (r != null && r.state != ActivityState.PAUSED
                    && r.state != ActivityState.STOPPED
                    && r.state != ActivityState.STOPPING) {
                if (DEBUG_STATES) {
                    Slog.d(TAG, "allPausedActivitiesComplete: r=" + r + " state=" + r.state);
                    pausing = false;
                } else {
                    return false;
                }
            }
        }
        return pausing;
    }

    void reportActivityVisibleLocked(ActivityRecord r) {
        for (int i = mWaitingActivityVisible.size()-1; i >= 0; i--) {
            WaitResult w = mWaitingActivityVisible.get(i);
            w.timeout = false;
            if (r != null) {
                w.who = new ComponentName(r.info.packageName, r.info.name);
            }
            w.totalTime = SystemClock.uptimeMillis() - w.thisTime;
            w.thisTime = w.totalTime;
        }
        mService.notifyAll();
        dismissKeyguard();
    }

    void reportActivityLaunchedLocked(boolean timeout, ActivityRecord r,
            long thisTime, long totalTime) {
        for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = mWaitingActivityLaunched.remove(i);
            w.timeout = timeout;
            if (r != null) {
                w.who = new ComponentName(r.info.packageName, r.info.name);
            }
            w.thisTime = thisTime;
            w.totalTime = totalTime;
        }
        mService.notifyAll();
    }

    ActivityRecord topRunningActivityLocked() {
        final ActivityStack focusedStack = getFocusedStack();
        ActivityRecord r = focusedStack.topRunningActivityLocked(null);
        if (r != null) {
            return r;
        }

        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack != focusedStack && isFrontStack(stack)) {
                r = stack.topRunningActivityLocked(null);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    ActivityRecord getTasksLocked(int maxNum, IThumbnailReceiver receiver,
            PendingThumbnailsRecord pending, List<RunningTaskInfo> list) {
        ActivityRecord r = null;

        // Gather all of the running tasks for each stack into runningTaskLists.
        final int numStacks = mStacks.size();
        ArrayList<RunningTaskInfo>[] runningTaskLists = new ArrayList[numStacks];
        for (int stackNdx = numStacks - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            ArrayList<RunningTaskInfo> stackTaskList = new ArrayList<RunningTaskInfo>();
            runningTaskLists[stackNdx] = stackTaskList;
            final ActivityRecord ar = stack.getTasksLocked(receiver, pending, stackTaskList);
            if (isFrontStack(stack)) {
                r = ar;
            }
        }

        // The lists are already sorted from most recent to oldest. Just pull the most recent off
        // each list and add it to list. Stop when all lists are empty or maxNum reached.
        while (maxNum > 0) {
            long mostRecentActiveTime = Long.MIN_VALUE;
            ArrayList<RunningTaskInfo> selectedStackList = null;
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                ArrayList<RunningTaskInfo> stackTaskList = runningTaskLists[stackNdx];
                if (!stackTaskList.isEmpty()) {
                    final long lastActiveTime = stackTaskList.get(0).lastActiveTime;
                    if (lastActiveTime > mostRecentActiveTime) {
                        mostRecentActiveTime = lastActiveTime;
                        selectedStackList = stackTaskList;
                    }
                }
            }
            if (selectedStackList != null) {
                list.add(selectedStackList.remove(0));
                --maxNum;
            } else {
                break;
            }
        }

        return r;
    }

    ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, int userId) {
        // Collect information about the target of the Intent.
        ActivityInfo aInfo;
        try {
            ResolveInfo rInfo =
                AppGlobals.getPackageManager().resolveIntent(
                        intent, resolvedType,
                        PackageManager.MATCH_DEFAULT_ONLY
                                    | ActivityManagerService.STOCK_PM_FLAGS, userId);
            aInfo = rInfo != null ? rInfo.activityInfo : null;
        } catch (RemoteException e) {
            aInfo = null;
        }

        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));

            // Don't debug things in the system process
            if ((startFlags&ActivityManager.START_FLAG_DEBUG) != 0) {
                if (!aInfo.processName.equals("system")) {
                    mService.setDebugApp(aInfo.processName, true, false);
                }
            }

            if ((startFlags&ActivityManager.START_FLAG_OPENGL_TRACES) != 0) {
                if (!aInfo.processName.equals("system")) {
                    mService.setOpenGlTraceApp(aInfo.applicationInfo, aInfo.processName);
                }
            }

            if (profileFile != null) {
                if (!aInfo.processName.equals("system")) {
                    mService.setProfileApp(aInfo.applicationInfo, aInfo.processName,
                            profileFile, profileFd,
                            (startFlags&ActivityManager.START_FLAG_AUTO_STOP_PROFILER) != 0);
                }
            }
        }
        return aInfo;
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo) {
        moveHomeToTop();
        startActivityLocked(null, intent, null, aInfo, null, null, 0, 0, 0, null, 0,
                null, false, null);
    }

    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, String profileFile,
            ParcelFileDescriptor profileFd, WaitResult outResult, Configuration config,
            Bundle options, int userId) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        boolean componentSpecified = intent.getComponent() != null;

        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        ActivityInfo aInfo = resolveActivity(intent, resolvedType, startFlags,
                profileFile, profileFd, userId);

        synchronized (mService) {
            int callingPid;
            if (callingUid >= 0) {
                callingPid = -1;
            } else if (caller == null) {
                callingPid = Binder.getCallingPid();
                callingUid = Binder.getCallingUid();
            } else {
                callingPid = callingUid = -1;
            }

            final ActivityStack stack = getFocusedStack();
            stack.mConfigWillChange = config != null
                    && mService.mConfiguration.diff(config) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Starting activity when config will change = " + stack.mConfigWillChange);

            final long origId = Binder.clearCallingIdentity();

            if (aInfo != null &&
                    (aInfo.applicationInfo.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
                // This may be a heavy-weight process!  Check to see if we already
                // have another, different heavy-weight process running.
                if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                    if (mService.mHeavyWeightProcess != null &&
                            (mService.mHeavyWeightProcess.info.uid != aInfo.applicationInfo.uid ||
                            !mService.mHeavyWeightProcess.processName.equals(aInfo.processName))) {
                        int realCallingUid = callingUid;
                        if (caller != null) {
                            ProcessRecord callerApp = mService.getRecordForAppLocked(caller);
                            if (callerApp != null) {
                                realCallingUid = callerApp.info.uid;
                            } else {
                                Slog.w(TAG, "Unable to find app for caller " + caller
                                      + " (pid=" + callingPid + ") when starting: "
                                      + intent.toString());
                                ActivityOptions.abort(options);
                                return ActivityManager.START_PERMISSION_DENIED;
                            }
                        }

                        IIntentSender target = mService.getIntentSenderLocked(
                                ActivityManager.INTENT_SENDER_ACTIVITY, "android",
                                realCallingUid, userId, null, null, 0, new Intent[] { intent },
                                new String[] { resolvedType }, PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_ONE_SHOT, null);

                        Intent newIntent = new Intent();
                        if (requestCode >= 0) {
                            // Caller is requesting a result.
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_HAS_RESULT, true);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_INTENT,
                                new IntentSender(target));
                        if (mService.mHeavyWeightProcess.activities.size() > 0) {
                            ActivityRecord hist = mService.mHeavyWeightProcess.activities.get(0);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP,
                                    hist.packageName);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK,
                                    hist.task.taskId);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_NEW_APP,
                                aInfo.packageName);
                        newIntent.setFlags(intent.getFlags());
                        newIntent.setClassName("android",
                                HeavyWeightSwitcherActivity.class.getName());
                        intent = newIntent;
                        resolvedType = null;
                        caller = null;
                        callingUid = Binder.getCallingUid();
                        callingPid = Binder.getCallingPid();
                        componentSpecified = true;
                        try {
                            ResolveInfo rInfo =
                                AppGlobals.getPackageManager().resolveIntent(
                                        intent, null,
                                        PackageManager.MATCH_DEFAULT_ONLY
                                        | ActivityManagerService.STOCK_PM_FLAGS, userId);
                            aInfo = rInfo != null ? rInfo.activityInfo : null;
                            aInfo = mService.getActivityInfoForUser(aInfo, userId);
                        } catch (RemoteException e) {
                            aInfo = null;
                        }
                    }
                }
            }

            int res = startActivityLocked(caller, intent, resolvedType,
                    aInfo, resultTo, resultWho, requestCode, callingPid, callingUid,
                    callingPackage, startFlags, options, componentSpecified, null);

            if (stack.mConfigWillChange) {
                // If the caller also wants to switch to a new configuration,
                // do so now.  This allows a clean switch, as we are waiting
                // for the current activity to pause (so we will not destroy
                // it), and have not yet started the next activity.
                mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                        "updateConfiguration()");
                stack.mConfigWillChange = false;
                if (DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Updating to new configuration after starting activity.");
                mService.updateConfigurationLocked(config, null, false, false);
            }

            Binder.restoreCallingIdentity(origId);

            if (outResult != null) {
                outResult.result = res;
                if (res == ActivityManager.START_SUCCESS) {
                    mWaitingActivityLaunched.add(outResult);
                    do {
                        try {
                            mService.wait();
                        } catch (InterruptedException e) {
                        }
                    } while (!outResult.timeout && outResult.who == null);
                } else if (res == ActivityManager.START_TASK_TO_FRONT) {
                    ActivityRecord r = stack.topRunningActivityLocked(null);
                    if (r.nowVisible) {
                        outResult.timeout = false;
                        outResult.who = new ComponentName(r.info.packageName, r.info.name);
                        outResult.totalTime = 0;
                        outResult.thisTime = 0;
                    } else {
                        outResult.thisTime = SystemClock.uptimeMillis();
                        mWaitingActivityVisible.add(outResult);
                        do {
                            try {
                                mService.wait();
                            } catch (InterruptedException e) {
                            }
                        } while (!outResult.timeout && outResult.who == null);
                    }
                }
            }

            return res;
        }
    }

    final int startActivities(IApplicationThread caller, int callingUid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle options, int userId) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }


        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = Binder.getCallingPid();
            callingUid = Binder.getCallingUid();
        } else {
            callingPid = callingUid = -1;
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                ActivityRecord[] outActivity = new ActivityRecord[1];
                for (int i=0; i<intents.length; i++) {
                    Intent intent = intents[i];
                    if (intent == null) {
                        continue;
                    }

                    // Refuse possible leaked file descriptors
                    if (intent != null && intent.hasFileDescriptors()) {
                        throw new IllegalArgumentException("File descriptors passed in Intent");
                    }

                    boolean componentSpecified = intent.getComponent() != null;

                    // Don't modify the client's object!
                    intent = new Intent(intent);

                    // Collect information about the target of the Intent.
                    ActivityInfo aInfo = resolveActivity(intent, resolvedTypes[i],
                            0, null, null, userId);
                    // TODO: New, check if this is correct
                    aInfo = mService.getActivityInfoForUser(aInfo, userId);

                    if (aInfo != null &&
                            (aInfo.applicationInfo.flags & ApplicationInfo.FLAG_CANT_SAVE_STATE)
                                    != 0) {
                        throw new IllegalArgumentException(
                                "FLAG_CANT_SAVE_STATE not supported here");
                    }

                    Bundle theseOptions;
                    if (options != null && i == intents.length-1) {
                        theseOptions = options;
                    } else {
                        theseOptions = null;
                    }
                    int res = startActivityLocked(caller, intent, resolvedTypes[i],
                            aInfo, resultTo, null, -1, callingPid, callingUid, callingPackage,
                            0, theseOptions, componentSpecified, outActivity);
                    if (res < 0) {
                        return res;
                    }

                    resultTo = outActivity[0] != null ? outActivity[0].appToken : null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return ActivityManager.START_SUCCESS;
    }

    final boolean realStartActivityLocked(ActivityRecord r,
            ProcessRecord app, boolean andResume, boolean checkConfig)
            throws RemoteException {

        r.startFreezingScreenLocked(app, 0);
        if (false) Slog.d(TAG, "realStartActivity: setting app visibility true");
        mWindowManager.setAppVisibility(r.appToken, true);

        // schedule launch ticks to collect information about slow apps.
        r.startLaunchTickingLocked();

        // Have the window manager re-evaluate the orientation of
        // the screen based on the new activity order.  Note that
        // as a result of this, it can call back into the activity
        // manager with a new orientation.  We don't care about that,
        // because the activity is not currently running so we are
        // just restarting it anyway.
        if (checkConfig) {
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mService.mConfiguration,
                    r.mayFreezeScreenLocked(app) ? r.appToken : null);
            mService.updateConfigurationLocked(config, r, false, false);
        }

        r.app = app;
        app.waitingToKill = null;
        r.launchCount++;
        r.lastLaunchTime = SystemClock.uptimeMillis();

        if (localLOGV) Slog.v(TAG, "Launching: " + r);

        int idx = app.activities.indexOf(r);
        if (idx < 0) {
            app.activities.add(r);
        }
        mService.updateLruProcessLocked(app, true, null);
        mService.updateOomAdjLocked();

        final ActivityStack stack = r.task.stack;
        try {
            if (app.thread == null) {
                throw new RemoteException();
            }
            List<ResultInfo> results = null;
            List<Intent> newIntents = null;
            if (andResume) {
                results = r.results;
                newIntents = r.newIntents;
            }
            if (DEBUG_SWITCH) Slog.v(TAG, "Launching: " + r
                    + " icicle=" + r.icicle
                    + " with results=" + results + " newIntents=" + newIntents
                    + " andResume=" + andResume);
            if (andResume) {
                EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY,
                        r.userId, System.identityHashCode(r),
                        r.task.taskId, r.shortComponentName);
            }
            if (r.isHomeActivity() && r.isNotResolverActivity()) {
                // Home process is the root process of the task.
                mService.mHomeProcess = r.task.mActivities.get(0).app;
            }
            mService.ensurePackageDexOpt(r.intent.getComponent().getPackageName());
            r.sleeping = false;
            r.forceNewConfig = false;
            mService.showAskCompatModeDialogLocked(r);
            r.compat = mService.compatibilityInfoForPackageLocked(r.info.applicationInfo);
            String profileFile = null;
            ParcelFileDescriptor profileFd = null;
            boolean profileAutoStop = false;
            if (mService.mProfileApp != null && mService.mProfileApp.equals(app.processName)) {
                if (mService.mProfileProc == null || mService.mProfileProc == app) {
                    mService.mProfileProc = app;
                    profileFile = mService.mProfileFile;
                    profileFd = mService.mProfileFd;
                    profileAutoStop = mService.mAutoStopProfiler;
                }
            }
            app.hasShownUi = true;
            app.pendingUiClean = true;
            if (profileFd != null) {
                try {
                    profileFd = profileFd.dup();
                } catch (IOException e) {
                    if (profileFd != null) {
                        try {
                            profileFd.close();
                        } catch (IOException o) {
                        }
                        profileFd = null;
                    }
                }
            }
            app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_TOP);
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                    System.identityHashCode(r), r.info,
                    new Configuration(mService.mConfiguration), r.compat,
                    app.repProcState, r.icicle, results, newIntents, !andResume,
                    mService.isNextTransitionForward(), profileFile, profileFd,
                    profileAutoStop);

            if ((app.info.flags&ApplicationInfo.FLAG_CANT_SAVE_STATE) != 0) {
                // This may be a heavy-weight process!  Note that the package
                // manager will ensure that only activity can run in the main
                // process of the .apk, which is the only thing that will be
                // considered heavy-weight.
                if (app.processName.equals(app.info.packageName)) {
                    if (mService.mHeavyWeightProcess != null
                            && mService.mHeavyWeightProcess != app) {
                        Slog.w(TAG, "Starting new heavy weight process " + app
                                + " when already running "
                                + mService.mHeavyWeightProcess);
                    }
                    mService.mHeavyWeightProcess = app;
                    Message msg = mService.mHandler.obtainMessage(
                            ActivityManagerService.POST_HEAVY_NOTIFICATION_MSG);
                    msg.obj = r;
                    mService.mHandler.sendMessage(msg);
                }
            }

        } catch (RemoteException e) {
            if (r.launchFailed) {
                // This is the second time we failed -- finish activity
                // and give up.
                Slog.e(TAG, "Second failure launching "
                      + r.intent.getComponent().flattenToShortString()
                      + ", giving up", e);
                mService.appDiedLocked(app, app.pid, app.thread);
                stack.requestFinishActivityLocked(r.appToken, Activity.RESULT_CANCELED, null,
                        "2nd-crash", false);
                return false;
            }

            // This is the first time we failed -- restart process and
            // retry.
            app.activities.remove(r);
            throw e;
        }

        r.launchFailed = false;
        if (stack.updateLRUListLocked(r)) {
            Slog.w(TAG, "Activity " + r
                  + " being launched, but already in LRU list");
        }

        if (andResume) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            stack.minimalResumeActivityLocked(r);
        } else {
            // This activity is not starting in the resumed state... which
            // should look like we asked it to pause+stop (but remain visible),
            // and it has done so and reported back the current icicle and
            // other state.
            if (DEBUG_STATES) Slog.v(TAG, "Moving to STOPPED: " + r
                    + " (starting in stopped state)");
            r.state = ActivityState.STOPPED;
            r.stopped = true;
        }

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        if (isFrontStack(stack)) {
            mService.startSetupActivityLocked();
        }

        return true;
    }

    void startSpecificActivityLocked(ActivityRecord r,
            boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        ProcessRecord app = mService.getProcessRecordLocked(r.processName,
                r.info.applicationInfo.uid, true);

        r.task.stack.setLaunchTime(r);

        if (app != null && app.thread != null) {
            try {
                if ((r.info.flags&ActivityInfo.FLAG_MULTIPROCESS) == 0
                        || !"android".equals(r.info.packageName)) {
                    // Don't add this if it is a platform component that is marked
                    // to run in multiple processes, because this is actually
                    // part of the framework so doesn't make sense to track as a
                    // separate apk in the process.
                    app.addPackage(r.info.packageName, mService.mProcessStats);
                }
                realStartActivityLocked(r, app, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        mService.startProcessLocked(r.processName, r.info.applicationInfo, true, 0,
                "activity", r.intent.getComponent(), false, false, true);
    }

    final int startActivityLocked(IApplicationThread caller,
            Intent intent, String resolvedType, ActivityInfo aInfo, IBinder resultTo,
            String resultWho, int requestCode,
            int callingPid, int callingUid, String callingPackage, int startFlags, Bundle options,
            boolean componentSpecified, ActivityRecord[] outActivity) {
        int err = ActivityManager.START_SUCCESS;

        ProcessRecord callerApp = null;
        if (caller != null) {
            callerApp = mService.getRecordForAppLocked(caller);
            if (callerApp != null) {
                callingPid = callerApp.pid;
                callingUid = callerApp.info.uid;
            } else {
                Slog.w(TAG, "Unable to find app for caller " + caller
                      + " (pid=" + callingPid + ") when starting: "
                      + intent.toString());
                err = ActivityManager.START_PERMISSION_DENIED;
            }
        }

        if (err == ActivityManager.START_SUCCESS) {
            final int userId = aInfo != null ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;
            Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
                    + "} from pid " + (callerApp != null ? callerApp.pid : callingPid));
        }

        ActivityRecord sourceRecord = null;
        ActivityRecord resultRecord = null;
        if (resultTo != null) {
            sourceRecord = isInAnyStackLocked(resultTo);
            if (DEBUG_RESULTS) Slog.v(
                TAG, "Will send result to " + resultTo + " " + sourceRecord);
            if (sourceRecord != null) {
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    resultRecord = sourceRecord;
                }
            }
        }
        ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

        int launchFlags = intent.getFlags();

        if ((launchFlags&Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0
                && sourceRecord != null) {
            // Transfer the result target from the source activity to the new
            // one being started, including any failures.
            if (requestCode >= 0) {
                ActivityOptions.abort(options);
                return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
            }
            resultRecord = sourceRecord.resultTo;
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;
            sourceRecord.resultTo = null;
            if (resultRecord != null) {
                resultRecord.removeResultsLocked(
                    sourceRecord, resultWho, requestCode);
            }
        }

        if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
            // We couldn't find a class that can handle the given Intent.
            // That's the end of that!
            err = ActivityManager.START_INTENT_NOT_RESOLVED;
        }

        if (err == ActivityManager.START_SUCCESS && aInfo == null) {
            // We couldn't find the specific class specified in the Intent.
            // Also the end of the line.
            err = ActivityManager.START_CLASS_NOT_FOUND;
        }

        if (err != ActivityManager.START_SUCCESS) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            setDismissKeyguard(false);
            ActivityOptions.abort(options);
            return err;
        }

        final int startAnyPerm = mService.checkPermission(
                START_ANY_ACTIVITY, callingPid, callingUid);
        final int componentPerm = mService.checkComponentPermission(aInfo.permission, callingPid,
                callingUid, aInfo.applicationInfo.uid, aInfo.exported);
        if (startAnyPerm != PERMISSION_GRANTED && componentPerm != PERMISSION_GRANTED) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            setDismissKeyguard(false);
            String msg;
            if (!aInfo.exported) {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " not exported from uid " + aInfo.applicationInfo.uid;
            } else {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires " + aInfo.permission;
            }
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        boolean abort = !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
                callingPid, resolvedType, aInfo.applicationInfo);

        if (mService.mController != null) {
            try {
                // The Intent we give to the watcher has the extra data
                // stripped off, since it can contain private information.
                Intent watchIntent = intent.cloneFilter();
                abort |= !mService.mController.activityStarting(watchIntent,
                        aInfo.applicationInfo.packageName);
            } catch (RemoteException e) {
                mService.mController = null;
            }
        }

        if (abort) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
            }
            // We pretend to the caller that it was really started, but
            // they will just get a cancel result.
            setDismissKeyguard(false);
            ActivityOptions.abort(options);
            return ActivityManager.START_SUCCESS;
        }

        ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
                intent, resolvedType, aInfo, mService.mConfiguration,
                resultRecord, resultWho, requestCode, componentSpecified, this);
        if (outActivity != null) {
            outActivity[0] = r;
        }

        final ActivityStack stack = getFocusedStack();
        if (stack.mResumedActivity == null
                || stack.mResumedActivity.info.applicationInfo.uid != callingUid) {
            if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid, "Activity start")) {
                PendingActivityLaunch pal =
                        new PendingActivityLaunch(r, sourceRecord, startFlags, stack);
                mService.mPendingActivityLaunches.add(pal);
                setDismissKeyguard(false);
                ActivityOptions.abort(options);
                return ActivityManager.START_SWITCHES_CANCELED;
            }
        }

        if (mService.mDidAppSwitch) {
            // This is the second allowed switch since we stopped switches,
            // so now just generally allow switches.  Use case: user presses
            // home (switches disabled, switch to home, mDidAppSwitch now true);
            // user taps a home icon (coming from home so allowed, we hit here
            // and now allow anyone to switch again).
            mService.mAppSwitchesAllowedTime = 0;
        } else {
            mService.mDidAppSwitch = true;
        }

        mService.doPendingActivityLaunchesLocked(false);

        err = startActivityUncheckedLocked(r, sourceRecord, startFlags, true, options);

        if (allPausedActivitiesComplete()) {
            // If someone asked to have the keyguard dismissed on the next
            // activity start, but we are not actually doing an activity
            // switch...  just dismiss the keyguard now, because we
            // probably want to see whatever is behind it.
            dismissKeyguard();
        }
        return err;
    }

    ActivityStack adjustStackFocus(ActivityRecord r) {
        final TaskRecord task = r.task;
        if (r.isApplicationActivity() || (task != null && task.isApplicationTask())) {
            if (task != null) {
                final ActivityStack taskStack = task.stack;
                if (mFocusedStack != taskStack) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG,
                            "adjustStackFocus: Setting focused stack to r=" + r + " task=" + task);
                    mFocusedStack = taskStack.isHomeStack() ? null : taskStack;
                } else {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG,
                        "adjustStackFocus: Focused stack already=" + mFocusedStack);
                }
                return taskStack;
            }

            if (mFocusedStack != null) {
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG,
                        "adjustStackFocus: Have a focused stack=" + mFocusedStack);
                return mFocusedStack;
            }

            for (int stackNdx = mStacks.size() - 1; stackNdx > 0; --stackNdx) {
                ActivityStack stack = mStacks.get(stackNdx);
                if (!stack.isHomeStack()) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG,
                            "adjustStackFocus: Setting focused stack=" + stack);
                    mFocusedStack = stack;
                    return mFocusedStack;
                }
            }

            // Time to create the first app stack for this user.
            int stackId =
                    mService.createStack(-1, HOME_STACK_ID, StackBox.TASK_STACK_GOES_OVER, 1.0f);
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG, "adjustStackFocus: New stack r=" + r +
                    " stackId=" + stackId);
            mFocusedStack = getStack(stackId);
            return mFocusedStack;
        }
        return mHomeStack;
    }

    void setFocusedStack(ActivityRecord r) {
        if (r == null) {
            return;
        }
        if (!r.isApplicationActivity() || (r.task != null && !r.task.isApplicationTask())) {
            if (mStackState != STACK_STATE_HOME_IN_FRONT) {
                if (DEBUG_STACK || DEBUG_FOCUS) Slog.d(TAG, "setFocusedStack: mStackState old=" +
                        stackStateToString(mStackState) + " new=" +
                        stackStateToString(STACK_STATE_HOME_TO_FRONT) +
                        " Callers=" + Debug.getCallers(3));
                mStackState = STACK_STATE_HOME_TO_FRONT;
            }
        } else {
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG,
                    "setFocusedStack: Setting focused stack to r=" + r + " task=" + r.task +
                    " Callers=" + Debug.getCallers(3));
            final ActivityStack taskStack = r.task.stack;
            mFocusedStack = taskStack.isHomeStack() ? null : taskStack;
            if (mStackState != STACK_STATE_HOME_IN_BACK) {
                if (DEBUG_STACK) Slog.d(TAG, "setFocusedStack: mStackState old=" +
                        stackStateToString(mStackState) + " new=" +
                        stackStateToString(STACK_STATE_HOME_TO_BACK) +
                        " Callers=" + Debug.getCallers(3));
                mStackState = STACK_STATE_HOME_TO_BACK;
            }
        }
    }

    final int startActivityUncheckedLocked(ActivityRecord r,
            ActivityRecord sourceRecord, int startFlags, boolean doResume,
            Bundle options) {
        final Intent intent = r.intent;
        final int callingUid = r.launchedFromUid;

        int launchFlags = intent.getFlags();

        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        mUserLeaving = (launchFlags&Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Slog.v(TAG, "startActivity() => mUserLeaving=" + mUserLeaving);

        // If the caller has asked not to resume at this point, we make note
        // of this in the record so that we can skip it when trying to find
        // the top running activity.
        if (!doResume) {
            r.delayedResume = true;
        }

        ActivityRecord notTop = (launchFlags&Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

        // If the onlyIfNeeded flag is set, then we can do this if the activity
        // being launched is the same as the one making the call...  or, as
        // a special case, if we do not know the caller then we count the
        // current top activity as the caller.
        if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = getFocusedStack().topRunningNonDelayedActivityLocked(notTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                startFlags &= ~ActivityManager.START_FLAG_ONLY_IF_NEEDED;
            }
        }

        if (sourceRecord == null) {
            // This activity is not being started from another...  in this
            // case we -always- start a new task.
            if ((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                        "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // The original activity who is starting us is running as a single
            // instance...  this new activity it is starting must go on its
            // own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        } else if (r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
            // The activity being started is a single instance...  it always
            // gets launched into its own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        }

        ActivityInfo newTaskInfo = null;
        Intent newTaskIntent = null;
        final ActivityStack sourceStack;
        if (sourceRecord != null) {
            if (sourceRecord.finishing) {
                // If the source is finishing, we can't further count it as our source.  This
                // is because the task it is associated with may now be empty and on its way out,
                // so we don't want to blindly throw it in to that task.  Instead we will take
                // the NEW_TASK flow and try to find a task for it. But save the task information
                // so it can be used when creating the new task.
                if ((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                    Slog.w(TAG, "startActivity called from finishing " + sourceRecord
                            + "; forcing " + "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
                    newTaskInfo = sourceRecord.info;
                    newTaskIntent = sourceRecord.task.intent;
                }
                sourceRecord = null;
                sourceStack = null;
            } else {
                sourceStack = sourceRecord.task.stack;
            }
        } else {
            sourceStack = null;
        }

        if (r.resultTo != null && (launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            // For whatever reason this activity is being launched into a new
            // task...  yet the caller has requested a result back.  Well, that
            // is pretty messed up, so instead immediately send back a cancel
            // and let the new task continue launched as normal without a
            // dependency on its originator.
            Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            r.resultTo.task.stack.sendActivityResultLocked(-1,
                    r.resultTo, r.resultWho, r.requestCode,
                Activity.RESULT_CANCELED, null);
            r.resultTo = null;
        }

        boolean addingToTask = false;
        boolean movedHome = false;
        TaskRecord reuseTask = null;
        ActivityStack targetStack;
        if (((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (launchFlags&Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // If bring to front is requested, and no result is requested, and
            // we can find a task that was started with this same
            // component, then instead of launching bring that one to the front.
            if (r.resultTo == null) {
                // See if there is a task to bring to the front.  If this is
                // a SINGLE_INSTANCE activity, there can be one and only one
                // instance of it in the history, and it is always in its own
                // unique task, so we do a special search.
                ActivityRecord intentActivity = r.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
                        ? findTaskLocked(r)
                        : findActivityLocked(intent, r.info);
                if (intentActivity != null) {
                    if (r.task == null) {
                        r.task = intentActivity.task;
                    }
                    targetStack = intentActivity.task.stack;
                    targetStack.mLastPausedActivity = null;
                    if (DEBUG_TASKS) Slog.d(TAG, "Bring to front target: " + targetStack
                            + " from " + intentActivity);
                    moveHomeStack(targetStack.isHomeStack());
                    if (intentActivity.task.intent == null) {
                        // This task was started because of movement of
                        // the activity based on affinity...  now that we
                        // are actually launching it, we can assign the
                        // base intent.
                        intentActivity.task.setIntent(intent, r.info);
                    }
                    // If the target task is not in the front, then we need
                    // to bring it to the front...  except...  well, with
                    // SINGLE_TASK_LAUNCH it's not entirely clear.  We'd like
                    // to have the same behavior as if a new instance was
                    // being started, which means not bringing it to the front
                    // if the caller is not itself in the front.
                    final ActivityStack lastStack = getLastStack();
                    ActivityRecord curTop = lastStack == null?
                            null : lastStack.topRunningNonDelayedActivityLocked(notTop);
                    if (curTop != null && (curTop.task != intentActivity.task ||
                            curTop.task != lastStack.topTask())) {
                        r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                        if (sourceRecord == null || (sourceStack.topActivity() != null &&
                                sourceStack.topActivity().task == sourceRecord.task)) {
                            // We really do want to push this one into the
                            // user's face, right now.
                            movedHome = true;
                            targetStack.moveTaskToFrontLocked(intentActivity.task, r, options);
                            if ((launchFlags &
                                    (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                                    == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                                // Caller wants to appear on home activity.
                                intentActivity.task.mOnTopOfHome = true;
                            }
                            options = null;
                        }
                    }
                    // If the caller has requested that the target task be
                    // reset, then do so.
                    if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                        intentActivity = targetStack.resetTaskIfNeededLocked(intentActivity, r);
                    }
                    if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                        // We don't need to start a new activity, and
                        // the client said not to do anything if that
                        // is the case, so this is it!  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        if (doResume) {
                            resumeTopActivitiesLocked(targetStack, null, options);
                        } else {
                            ActivityOptions.abort(options);
                        }
                        if (r.task == null)  Slog.v(TAG,
                                "startActivityUncheckedLocked: task left null",
                                new RuntimeException("here").fillInStackTrace());
                        return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                    }
                    if ((launchFlags &
                            (Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            == (Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)) {
                        // The caller has requested to completely replace any
                        // existing task with its new activity.  Well that should
                        // not be too hard...
                        reuseTask = intentActivity.task;
                        reuseTask.performClearTaskLocked();
                        reuseTask.setIntent(r.intent, r.info);
                    } else if ((launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                        // In this situation we want to remove all activities
                        // from the task up to the one being started.  In most
                        // cases this means we are resetting the task to its
                        // initial state.
                        ActivityRecord top =
                                intentActivity.task.performClearTaskLocked(r, launchFlags);
                        if (top != null) {
                            if (top.frontOfTask) {
                                // Activity aliases may mean we use different
                                // intents for the top activity, so make sure
                                // the task now has the identity of the new
                                // intent.
                                top.task.setIntent(r.intent, r.info);
                            }
                            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT,
                                    r, top.task);
                            top.deliverNewIntentLocked(callingUid, r.intent);
                        } else {
                            // A special case: we need to
                            // start the activity because it is not currently
                            // running, and the caller has asked to clear the
                            // current task to have this activity at the top.
                            addingToTask = true;
                            // Now pretend like this activity is being started
                            // by the top of its task, so it is put in the
                            // right place.
                            sourceRecord = intentActivity;
                        }
                    } else if (r.realActivity.equals(intentActivity.task.realActivity)) {
                        // In this case the top activity on the task is the
                        // same as the one being launched, so we take that
                        // as a request to bring the task to the foreground.
                        // If the top activity in the task is the root
                        // activity, deliver this new intent to it if it
                        // desires.
                        if (((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP)
                                && intentActivity.realActivity.equals(r.realActivity)) {
                            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r,
                                    intentActivity.task);
                            if (intentActivity.frontOfTask) {
                                intentActivity.task.setIntent(r.intent, r.info);
                            }
                            intentActivity.deliverNewIntentLocked(callingUid, r.intent);
                        } else if (!r.intent.filterEquals(intentActivity.task.intent)) {
                            // In this case we are launching the root activity
                            // of the task, but with a different intent.  We
                            // should start a new instance on top.
                            addingToTask = true;
                            sourceRecord = intentActivity;
                        }
                    } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                        // In this case an activity is being launched in to an
                        // existing task, without resetting that task.  This
                        // is typically the situation of launching an activity
                        // from a notification or shortcut.  We want to place
                        // the new activity on top of the current task.
                        addingToTask = true;
                        sourceRecord = intentActivity;
                    } else if (!intentActivity.task.rootWasReset) {
                        // In this case we are launching in to an existing task
                        // that has not yet been started from its front door.
                        // The current task has been brought to the front.
                        // Ideally, we'd probably like to place this new task
                        // at the bottom of its stack, but that's a little hard
                        // to do with the current organization of the code so
                        // for now we'll just drop it.
                        intentActivity.task.setIntent(r.intent, r.info);
                    }
                    if (!addingToTask && reuseTask == null) {
                        // We didn't do anything...  but it was needed (a.k.a., client
                        // don't use that intent!)  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        if (doResume) {
                            targetStack.resumeTopActivityLocked(null, options);
                        } else {
                            ActivityOptions.abort(options);
                        }
                        if (r.task == null)  Slog.v(TAG,
                            "startActivityUncheckedLocked: task left null",
                            new RuntimeException("here").fillInStackTrace());
                        return ActivityManager.START_TASK_TO_FRONT;
                    }
                }
            }
        }

        //String uri = r.intent.toURI();
        //Intent intent2 = new Intent(uri);
        //Slog.i(TAG, "Given intent: " + r.intent);
        //Slog.i(TAG, "URI is: " + uri);
        //Slog.i(TAG, "To intent: " + intent2);

        if (r.packageName != null) {
            // If the activity being launched is the same as the one currently
            // at the top, then we need to check if it should only be launched
            // once.
            ActivityStack topStack = getFocusedStack();
            ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(notTop);
            if (top != null && r.resultTo == null) {
                if (top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                    if (top.app != null && top.app.thread != null) {
                        if ((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
                            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top,
                                    top.task);
                            // For paranoia, make sure we have correctly
                            // resumed the top activity.
                            topStack.mLastPausedActivity = null;
                            if (doResume) {
                                resumeTopActivitiesLocked();
                            }
                            ActivityOptions.abort(options);
                            if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                                // We don't need to start a new activity, and
                                // the client said not to do anything if that
                                // is the case, so this is it!
                                if (r.task == null)  Slog.v(TAG,
                                    "startActivityUncheckedLocked: task left null",
                                    new RuntimeException("here").fillInStackTrace());
                                return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                            }
                            top.deliverNewIntentLocked(callingUid, r.intent);
                            if (r.task == null)  Slog.v(TAG,
                                "startActivityUncheckedLocked: task left null",
                                new RuntimeException("here").fillInStackTrace());
                            return ActivityManager.START_DELIVERED_TO_TOP;
                        }
                    }
                }
            }

        } else {
            if (r.resultTo != null) {
                r.resultTo.task.stack.sendActivityResultLocked(-1, r.resultTo, r.resultWho,
                        r.requestCode, Activity.RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            if (r.task == null)  Slog.v(TAG,
                "startActivityUncheckedLocked: task left null",
                new RuntimeException("here").fillInStackTrace());
            return ActivityManager.START_CLASS_NOT_FOUND;
        }

        boolean newTask = false;
        boolean keepCurTransition = false;

        // Should this be considered a new task?
        if (r.resultTo == null && !addingToTask
                && (launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            targetStack = adjustStackFocus(r);
            moveHomeStack(targetStack.isHomeStack());
            if (reuseTask == null) {
                r.setTask(targetStack.createTaskRecord(getNextTaskId(),
                        newTaskInfo != null ? newTaskInfo : r.info,
                        newTaskIntent != null ? newTaskIntent : intent,
                        true), null, true);
                if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r + " in new task " +
                        r.task);
            } else {
                r.setTask(reuseTask, reuseTask, true);
            }
            newTask = true;
            if (!movedHome) {
                if ((launchFlags &
                        (Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_TASK_ON_HOME))
                        == (Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_TASK_ON_HOME)) {
                    // Caller wants to appear on home activity, so before starting
                    // their own activity we will bring home to the front.
                    r.task.mOnTopOfHome = true;
                }
            }
        } else if (sourceRecord != null) {
            TaskRecord sourceTask = sourceRecord.task;
            targetStack = sourceTask.stack;
            moveHomeStack(targetStack.isHomeStack());
            if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                // In this case, we are adding the activity to an existing
                // task, but the caller has asked to clear that task if the
                // activity is already running.
                ActivityRecord top = sourceTask.performClearTaskLocked(r, launchFlags);
                keepCurTransition = true;
                if (top != null) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    top.deliverNewIntentLocked(callingUid, r.intent);
                    // For paranoia, make sure we have correctly
                    // resumed the top activity.
                    targetStack.mLastPausedActivity = null;
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null);
                    }
                    ActivityOptions.abort(options);
                    if (r.task == null)  Slog.w(TAG,
                        "startActivityUncheckedLocked: task left null",
                        new RuntimeException("here").fillInStackTrace());
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            } else if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
                // In this case, we are launching an activity in our own task
                // that may already be running somewhere in the history, and
                // we want to shuffle it to the front of the stack if so.
                final ActivityRecord top = sourceTask.findActivityInHistoryLocked(r);
                if (top != null) {
                    final TaskRecord task = top.task;
                    task.moveActivityToFrontLocked(top);
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, task);
                    top.updateOptionsLocked(options);
                    top.deliverNewIntentLocked(callingUid, r.intent);
                    targetStack.mLastPausedActivity = null;
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null);
                    }
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            }
            // An existing activity is starting this new activity, so we want
            // to keep the new one in the same task as the one that is starting
            // it.
            r.setTask(sourceTask, sourceRecord.thumbHolder, false);
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r
                    + " in existing task " + r.task + " from source " + sourceRecord);

        } else {
            // This not being started from an existing activity, and not part
            // of a new task...  just put it in the top task, though these days
            // this case should never happen.
            targetStack = adjustStackFocus(r);
            moveHomeStack(targetStack.isHomeStack());
            ActivityRecord prev = targetStack.topActivity();
            r.setTask(prev != null ? prev.task
                    : targetStack.createTaskRecord(getNextTaskId(), r.info, intent, true),
                    null, true);
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r
                    + " in new guessed " + r.task);
        }

        mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                intent, r.getUriPermissionsLocked());

        if (newTask) {
            EventLog.writeEvent(EventLogTags.AM_CREATE_TASK, r.userId, r.task.taskId);
        }
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, r, r.task);
        targetStack.mLastPausedActivity = null;
        targetStack.startActivityLocked(r, newTask, doResume, keepCurTransition, options);
        mService.setFocusedActivityLocked(r);
        return ActivityManager.START_SUCCESS;
    }

    void acquireLaunchWakelock() {
        if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != Process.myUid()) {
            throw new IllegalStateException("Calling must be system uid");
        }
        mLaunchingActivity.acquire();
        if (!mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
            // To be safe, don't allow the wake lock to be held for too long.
            mHandler.sendEmptyMessageDelayed(LAUNCH_TIMEOUT_MSG, LAUNCH_TIMEOUT);
        }
    }

    // Checked.
    final ActivityRecord activityIdleInternalLocked(final IBinder token, boolean fromTimeout,
            Configuration config) {
        if (localLOGV) Slog.v(TAG, "Activity idle: " + token);

        ArrayList<ActivityRecord> stops = null;
        ArrayList<ActivityRecord> finishes = null;
        ArrayList<UserStartedState> startingUsers = null;
        int NS = 0;
        int NF = 0;
        IApplicationThread sendThumbnail = null;
        boolean booting = false;
        boolean enableScreen = false;
        boolean activityRemoved = false;

        ActivityRecord r = ActivityRecord.forToken(token);
        if (r != null) {
            if (DEBUG_IDLE) Slog.d(TAG, "activityIdleInternalLocked: Callers=" +
                    Debug.getCallers(4));
            mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
            r.finishLaunchTickingLocked();
            if (fromTimeout) {
                reportActivityLaunchedLocked(fromTimeout, r, -1, -1);
            }

            // This is a hack to semi-deal with a race condition
            // in the client where it can be constructed with a
            // newer configuration from when we asked it to launch.
            // We'll update with whatever configuration it now says
            // it used to launch.
            if (config != null) {
                r.configuration = config;
            }

            // We are now idle.  If someone is waiting for a thumbnail from
            // us, we can now deliver.
            r.idle = true;

            if (r.thumbnailNeeded && r.app != null && r.app.thread != null) {
                sendThumbnail = r.app.thread;
                r.thumbnailNeeded = false;
            }

            //Slog.i(TAG, "IDLE: mBooted=" + mBooted + ", fromTimeout=" + fromTimeout);
            if (!mService.mBooted && isFrontStack(r.task.stack)) {
                mService.mBooted = true;
                enableScreen = true;
            }
        }

        if (allResumedActivitiesIdle()) {
            if (r != null) {
                mService.scheduleAppGcsLocked();
            }

            if (mLaunchingActivity.isHeld()) {
                mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                if (VALIDATE_WAKE_LOCK_CALLER &&
                        Binder.getCallingUid() != Process.myUid()) {
                    throw new IllegalStateException("Calling must be system uid");
                }
                mLaunchingActivity.release();
            }
            ensureActivitiesVisibleLocked(null, 0);
        }

        // Atomically retrieve all of the other things to do.
        stops = processStoppingActivitiesLocked(true);
        NS = stops != null ? stops.size() : 0;
        if ((NF=mFinishingActivities.size()) > 0) {
            finishes = new ArrayList<ActivityRecord>(mFinishingActivities);
            mFinishingActivities.clear();
        }

        final ArrayList<ActivityRecord> thumbnails;
        final int NT = mCancelledThumbnails.size();
        if (NT > 0) {
            thumbnails = new ArrayList<ActivityRecord>(mCancelledThumbnails);
            mCancelledThumbnails.clear();
        } else {
            thumbnails = null;
        }

        if (isFrontStack(mHomeStack)) {
            booting = mService.mBooting;
            mService.mBooting = false;
        }

        if (mStartingUsers.size() > 0) {
            startingUsers = new ArrayList<UserStartedState>(mStartingUsers);
            mStartingUsers.clear();
        }

        // Perform the following actions from unsynchronized state.
        final IApplicationThread thumbnailThread = sendThumbnail;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (thumbnailThread != null) {
                    try {
                        thumbnailThread.requestThumbnail(token);
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception thrown when requesting thumbnail", e);
                        mService.sendPendingThumbnail(null, token, null, null, true);
                    }
                }

                // Report back to any thumbnail receivers.
                for (int i = 0; i < NT; i++) {
                    ActivityRecord r = thumbnails.get(i);
                    mService.sendPendingThumbnail(r, null, null, null, true);
                }
            }
        });

        // Stop any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NS; i++) {
            r = stops.get(i);
            final ActivityStack stack = r.task.stack;
            if (r.finishing) {
                stack.finishCurrentActivityLocked(r, ActivityStack.FINISH_IMMEDIATELY, false);
            } else {
                stack.stopActivityLocked(r);
            }
        }

        // Finish any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NF; i++) {
            r = finishes.get(i);
            activityRemoved |= r.task.stack.destroyActivityLocked(r, true, false, "finish-idle");
        }

        if (booting) {
            mService.finishBooting();
        } else if (startingUsers != null) {
            for (int i = 0; i < startingUsers.size(); i++) {
                mService.finishUserSwitch(startingUsers.get(i));
            }
        }

        mService.trimApplications();
        //dump();
        //mWindowManager.dump();

        if (enableScreen) {
            mService.enableScreenAfterBoot();
        }

        if (activityRemoved) {
            resumeTopActivitiesLocked();
        }

        return r;
    }

    boolean handleAppDiedLocked(ProcessRecord app) {
        boolean hasVisibleActivities = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            hasVisibleActivities |= mStacks.get(stackNdx).handleAppDiedLocked(app);
        }
        return hasVisibleActivities;
    }

    void closeSystemDialogsLocked() {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.closeSystemDialogsLocked();
        }
    }

    void removeUserLocked(int userId) {
        mUserStackInFront.delete(userId);
    }

    /**
     * @return true if some activity was finished (or would have finished if doit were true).
     */
    boolean forceStopPackageLocked(String name, boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.forceStopPackageLocked(name, doit, evenPersistent, userId)) {
                didSomething = true;
            }
        }
        return didSomething;
    }

    void updatePreviousProcessLocked(ActivityRecord r) {
        // Now that this process has stopped, we may want to consider
        // it to be the previous app to try to keep around in case
        // the user wants to return to it.

        // First, found out what is currently the foreground app, so that
        // we don't blow away the previous app if this activity is being
        // hosted by the process that is actually still the foreground.
        ProcessRecord fgApp = null;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (isFrontStack(stack)) {
                if (stack.mResumedActivity != null) {
                    fgApp = stack.mResumedActivity.app;
                } else if (stack.mPausingActivity != null) {
                    fgApp = stack.mPausingActivity.app;
                }
                break;
            }
        }

        // Now set this one as the previous process, only if that really
        // makes sense to.
        if (r.app != null && fgApp != null && r.app != fgApp
                && r.lastVisibleTime > mService.mPreviousProcessVisibleTime
                && r.app != mService.mHomeProcess) {
            mService.mPreviousProcess = r.app;
            mService.mPreviousProcessVisibleTime = r.lastVisibleTime;
        }
    }

    boolean resumeTopActivitiesLocked() {
        return resumeTopActivitiesLocked(null, null, null);
    }

    boolean resumeTopActivitiesLocked(ActivityStack targetStack, ActivityRecord target,
            Bundle targetOptions) {
        if (targetStack == null) {
            targetStack = getFocusedStack();
        }
        boolean result = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (isFrontStack(stack)) {
                if (stack == targetStack) {
                    result = stack.resumeTopActivityLocked(target, targetOptions);
                } else {
                    stack.resumeTopActivityLocked(null);
                }
            }
        }
        return result;
    }

    void finishTopRunningActivityLocked(ProcessRecord app) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.finishTopRunningActivityLocked(app);
        }
    }

    void findTaskToMoveToFrontLocked(int taskId, int flags, Bundle options) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            if (mStacks.get(stackNdx).findTaskToMoveToFrontLocked(taskId, flags, options)) {
                if (DEBUG_STACK) Slog.d(TAG, "findTaskToMoveToFront: moved to front of stack=" +
                        mStacks.get(stackNdx));
                return;
            }
        }
    }

    ActivityStack getStack(int stackId) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.getStackId() == stackId) {
                return stack;
            }
        }
        return null;
    }

    ArrayList<ActivityStack> getStacks() {
        return new ArrayList<ActivityStack>(mStacks);
    }

    int createStack() {
        while (true) {
            if (++mLastStackId <= HOME_STACK_ID) {
                mLastStackId = HOME_STACK_ID + 1;
            }
            if (getStack(mLastStackId) == null) {
                break;
            }
        }
        mStacks.add(new ActivityStack(mService, mContext, mLooper, mLastStackId));
        return mLastStackId;
    }

    void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        final TaskRecord task = anyTaskForIdLocked(taskId);
        if (task == null) {
            return;
        }
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            Slog.w(TAG, "moveTaskToStack: no stack for id=" + stackId);
            return;
        }
        removeTask(task);
        stack.addTask(task, toTop);
        mWindowManager.addTask(taskId, stackId, toTop);
        resumeTopActivitiesLocked();
    }

    ActivityRecord findTaskLocked(ActivityRecord r) {
        if (DEBUG_TASKS) Slog.d(TAG, "Looking for task of " + r);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (!r.isApplicationActivity() && !stack.isHomeStack()) {
                if (DEBUG_TASKS) Slog.d(TAG, "Skipping stack: " + stack);
                continue;
            }
            final ActivityRecord ar = stack.findTaskLocked(r);
            if (ar != null) {
                return ar;
            }
        }
        if (DEBUG_TASKS) Slog.d(TAG, "No task found");
        return null;
    }

    ActivityRecord findActivityLocked(Intent intent, ActivityInfo info) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityRecord ar = mStacks.get(stackNdx).findActivityLocked(intent, info);
            if (ar != null) {
                return ar;
            }
        }
        return null;
    }

    void goingToSleepLocked() {
        scheduleSleepTimeout();
        if (!mGoingToSleep.isHeld()) {
            mGoingToSleep.acquire();
            if (mLaunchingActivity.isHeld()) {
                if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != Process.myUid()) {
                    throw new IllegalStateException("Calling must be system uid");
                }
                mLaunchingActivity.release();
                mService.mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
            }
        }
        checkReadyForSleepLocked();
    }

    boolean shutdownLocked(int timeout) {
        boolean timedout = false;
        goingToSleepLocked();

        final long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            boolean cantShutdown = false;
            for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
                cantShutdown |= mStacks.get(stackNdx).checkReadyForSleepLocked();
            }
            if (cantShutdown) {
                long timeRemaining = endTime - System.currentTimeMillis();
                if (timeRemaining > 0) {
                    try {
                        mService.wait(timeRemaining);
                    } catch (InterruptedException e) {
                    }
                } else {
                    Slog.w(TAG, "Activity manager shutdown timed out");
                    timedout = true;
                    break;
                }
            } else {
                break;
            }
        }

        // Force checkReadyForSleep to complete.
        mSleepTimeout = true;
        checkReadyForSleepLocked();

        return timedout;
    }

    void comeOutOfSleepIfNeededLocked() {
        removeSleepTimeouts();
        if (mGoingToSleep.isHeld()) {
            mGoingToSleep.release();
        }
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.awakeFromSleepingLocked();
            if (isFrontStack(stack)) {
                resumeTopActivitiesLocked();
            }
        }
        mGoingToSleepActivities.clear();
    }

    void activitySleptLocked(ActivityRecord r) {
        mGoingToSleepActivities.remove(r);
        checkReadyForSleepLocked();
    }

    void checkReadyForSleepLocked() {
        if (!mService.isSleepingOrShuttingDown()) {
            // Do not care.
            return;
        }

        if (!mSleepTimeout) {
            boolean dontSleep = false;
            for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
                dontSleep |= mStacks.get(stackNdx).checkReadyForSleepLocked();
            }

            if (mStoppingActivities.size() > 0) {
                // Still need to tell some activities to stop; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep still need to stop "
                        + mStoppingActivities.size() + " activities");
                scheduleIdleLocked();
                dontSleep = true;
            }

            if (mGoingToSleepActivities.size() > 0) {
                // Still need to tell some activities to sleep; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep still need to sleep "
                        + mGoingToSleepActivities.size() + " activities");
                dontSleep = true;
            }

            if (dontSleep) {
                return;
            }
        }

        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).goToSleep();
        }

        removeSleepTimeouts();

        if (mGoingToSleep.isHeld()) {
            mGoingToSleep.release();
        }
        if (mService.mShuttingDown) {
            mService.notifyAll();
        }
    }

    boolean reportResumedActivityLocked(ActivityRecord r) {
        final ActivityStack stack = r.task.stack;
        if (isFrontStack(stack)) {
            mService.updateUsageStats(r, true);
        }
        if (allResumedActivitiesComplete()) {
            ensureActivitiesVisibleLocked(null, 0);
            mWindowManager.executeAppTransition();
            return true;
        }
        return false;
    }

    void handleAppCrashLocked(ProcessRecord app) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.handleAppCrashLocked(app);
        }
    }

    void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges) {
        // First the front stacks. In case any are not fullscreen and are in front of home.
        boolean showHomeBehindStack = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (isFrontStack(stack)) {
                showHomeBehindStack =
                        stack.ensureActivitiesVisibleLocked(starting, configChanges);
            }
        }
        // Now do back stacks.
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (!isFrontStack(stack)) {
                stack.ensureActivitiesVisibleLocked(starting, configChanges, showHomeBehindStack);
            }
        }
    }

    void scheduleDestroyAllActivities(ProcessRecord app, String reason) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.scheduleDestroyActivities(app, false, reason);
        }
    }

    boolean switchUserLocked(int userId, UserStartedState uss) {
        mUserStackInFront.put(mCurrentUser, getFocusedStack().getStackId());
        final int restoreStackId = mUserStackInFront.get(userId, HOME_STACK_ID);
        mCurrentUser = userId;

        mStartingUsers.add(uss);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).switchUserLocked(userId);
        }

        ActivityStack stack = getStack(restoreStackId);
        if (stack == null) {
            stack = mHomeStack;
        }
        final boolean homeInFront = stack.isHomeStack();
        moveHomeStack(homeInFront);
        mWindowManager.moveTaskToTop(stack.topTask().taskId);
        return homeInFront;
    }

    final ArrayList<ActivityRecord> processStoppingActivitiesLocked(boolean remove) {
        int N = mStoppingActivities.size();
        if (N <= 0) return null;

        ArrayList<ActivityRecord> stops = null;

        final boolean nowVisible = allResumedActivitiesVisible();
        for (int i=0; i<N; i++) {
            ActivityRecord s = mStoppingActivities.get(i);
            if (localLOGV) Slog.v(TAG, "Stopping " + s + ": nowVisible="
                    + nowVisible + " waitingVisible=" + s.waitingVisible
                    + " finishing=" + s.finishing);
            if (s.waitingVisible && nowVisible) {
                mWaitingVisibleActivities.remove(s);
                s.waitingVisible = false;
                if (s.finishing) {
                    // If this activity is finishing, it is sitting on top of
                    // everyone else but we now know it is no longer needed...
                    // so get rid of it.  Otherwise, we need to go through the
                    // normal flow and hide it once we determine that it is
                    // hidden by the activities in front of it.
                    if (localLOGV) Slog.v(TAG, "Before stopping, can hide: " + s);
                    mWindowManager.setAppVisibility(s.appToken, false);
                }
            }
            if ((!s.waitingVisible || mService.isSleepingOrShuttingDown()) && remove) {
                if (localLOGV) Slog.v(TAG, "Ready to stop: " + s);
                if (stops == null) {
                    stops = new ArrayList<ActivityRecord>();
                }
                stops.add(s);
                mStoppingActivities.remove(i);
                N--;
                i--;
            }
        }

        return stops;
    }

    void validateTopActivitiesLocked() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            final ActivityRecord r = stack.topRunningActivityLocked(null);
            final ActivityState state = r == null ? ActivityState.DESTROYED : r.state;
            if (isFrontStack(stack)) {
                if (r == null) {
                    Slog.e(TAG, "validateTop...: null top activity, stack=" + stack);
                } else {
                    final ActivityRecord pausing = stack.mPausingActivity;
                    if (pausing != null && pausing == r) {
                        Slog.e(TAG, "validateTop...: top stack has pausing activity r=" + r +
                            " state=" + state);
                    }
                    if (state != ActivityState.INITIALIZING && state != ActivityState.RESUMED) {
                        Slog.e(TAG, "validateTop...: activity in front not resumed r=" + r +
                                " state=" + state);
                    }
                }
            } else {
                final ActivityRecord resumed = stack.mResumedActivity;
                if (resumed != null && resumed == r) {
                    Slog.e(TAG, "validateTop...: back stack has resumed activity r=" + r +
                        " state=" + state);
                }
                if (r != null && (state == ActivityState.INITIALIZING
                        || state == ActivityState.RESUMED)) {
                    Slog.e(TAG, "validateTop...: activity in back resumed r=" + r +
                            " state=" + state);
                }
            }
        }
    }

    private static String stackStateToString(int stackState) {
        switch (stackState) {
            case STACK_STATE_HOME_IN_FRONT: return "STACK_STATE_HOME_IN_FRONT";
            case STACK_STATE_HOME_TO_BACK: return "STACK_STATE_HOME_TO_BACK";
            case STACK_STATE_HOME_IN_BACK: return "STACK_STATE_HOME_IN_BACK";
            case STACK_STATE_HOME_TO_FRONT: return "STACK_STATE_HOME_TO_FRONT";
            default: return "Unknown stackState=" + stackState;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mDismissKeyguardOnNextActivity=");
                pw.println(mDismissKeyguardOnNextActivity);
        pw.print(prefix); pw.print("mFocusedStack=" + mFocusedStack);
                pw.print(" mStackState="); pw.println(stackStateToString(mStackState));
        pw.print(prefix); pw.println("mSleepTimeout=" + mSleepTimeout);
        pw.print(prefix); pw.println("mCurTaskId=" + mCurTaskId);
        pw.print(prefix); pw.println("mUserStackInFront=" + mUserStackInFront);
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        return getFocusedStack().getDumpActivitiesLocked(name);
    }

    static boolean printThisActivity(PrintWriter pw, ActivityRecord activity, String dumpPackage,
            boolean needSep, String prefix) {
        if (activity != null) {
            if (dumpPackage == null || dumpPackage.equals(activity.packageName)) {
                if (needSep) {
                    pw.println();
                }
                pw.print(prefix);
                pw.println(activity);
                return true;
            }
        }
        return false;
    }

    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage) {
        boolean printed = false;
        boolean needSep = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            StringBuilder stackHeader = new StringBuilder(128);
            stackHeader.append("  Stack #");
            stackHeader.append(mStacks.indexOf(stack));
            stackHeader.append(":");
            printed |= stack.dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage, needSep,
                    stackHeader.toString());
            printed |= dumpHistoryList(fd, pw, stack.mLRUActivities, "    ", "Run", false, !dumpAll,
                    false, dumpPackage, true, "    Running activities (most recent first):", null);

            needSep = printed;
            boolean pr = printThisActivity(pw, stack.mPausingActivity, dumpPackage, needSep,
                    "    mPausingActivity: ");
            if (pr) {
                printed = true;
                needSep = false;
            }
            pr = printThisActivity(pw, stack.mResumedActivity, dumpPackage, needSep,
                    "    mResumedActivity: ");
            if (pr) {
                printed = true;
                needSep = false;
            }
            if (dumpAll) {
                pr = printThisActivity(pw, stack.mLastPausedActivity, dumpPackage, needSep,
                        "    mLastPausedActivity: ");
                if (pr) {
                    printed = true;
                    needSep = true;
                }
                printed |= printThisActivity(pw, stack.mLastNoHistoryActivity, dumpPackage,
                        needSep, "    mLastNoHistoryActivity: ");
            }
            needSep = printed;
        }

        printed |= dumpHistoryList(fd, pw, mFinishingActivities, "  ", "Fin", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to finish:", null);
        printed |= dumpHistoryList(fd, pw, mStoppingActivities, "  ", "Stop", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to stop:", null);
        printed |= dumpHistoryList(fd, pw, mWaitingVisibleActivities, "  ", "Wait", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting for another to become visible:",
                null);
        printed |= dumpHistoryList(fd, pw, mGoingToSleepActivities, "  ", "Sleep", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to sleep:", null);
        printed |= dumpHistoryList(fd, pw, mGoingToSleepActivities, "  ", "Sleep", false, !dumpAll,
                false, dumpPackage, true, "  Activities waiting to sleep:", null);

        return printed;
    }

    static boolean dumpHistoryList(FileDescriptor fd, PrintWriter pw, List<ActivityRecord> list,
            String prefix, String label, boolean complete, boolean brief, boolean client,
            String dumpPackage, boolean needNL, String header1, String header2) {
        TaskRecord lastTask = null;
        String innerPrefix = null;
        String[] args = null;
        boolean printed = false;
        for (int i=list.size()-1; i>=0; i--) {
            final ActivityRecord r = list.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.packageName)) {
                continue;
            }
            if (innerPrefix == null) {
                innerPrefix = prefix + "      ";
                args = new String[0];
            }
            printed = true;
            final boolean full = !brief && (complete || !r.isInHistory());
            if (needNL) {
                pw.println("");
                needNL = false;
            }
            if (header1 != null) {
                pw.println(header1);
                header1 = null;
            }
            if (header2 != null) {
                pw.println(header2);
                header2 = null;
            }
            if (lastTask != r.task) {
                lastTask = r.task;
                pw.print(prefix);
                pw.print(full ? "* " : "  ");
                pw.println(lastTask);
                if (full) {
                    lastTask.dump(pw, prefix + "  ");
                } else if (complete) {
                    // Complete + brief == give a summary.  Isn't that obvious?!?
                    if (lastTask.intent != null) {
                        pw.print(prefix); pw.print("  ");
                                pw.println(lastTask.intent.toInsecureStringWithClip());
                    }
                }
            }
            pw.print(prefix); pw.print(full ? "  * " : "    "); pw.print(label);
            pw.print(" #"); pw.print(i); pw.print(": ");
            pw.println(r);
            if (full) {
                r.dump(pw, innerPrefix);
            } else if (complete) {
                // Complete + brief == give a summary.  Isn't that obvious?!?
                pw.print(innerPrefix); pw.println(r.intent.toInsecureString());
                if (r.app != null) {
                    pw.print(innerPrefix); pw.println(r.app);
                }
            }
            if (client && r.app != null && r.app.thread != null) {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.app.thread.dumpActivity(tp.getWriteFd().getFileDescriptor(),
                                r.appToken, innerPrefix, args);
                        // Short timeout, since blocking here can
                        // deadlock with the application.
                        tp.go(fd, 2000);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println(innerPrefix + "Failure while dumping the activity: " + e);
                } catch (RemoteException e) {
                    pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
                }
                needNL = true;
            }
        }
        return printed;
    }

    void scheduleIdleTimeoutLocked(ActivityRecord next) {
        if (DEBUG_IDLE) Slog.d(TAG, "scheduleIdleTimeoutLocked: Callers=" + Debug.getCallers(4));
        Message msg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG, next);
        mHandler.sendMessageDelayed(msg, IDLE_TIMEOUT);
    }

    final void scheduleIdleLocked() {
        mHandler.sendEmptyMessage(IDLE_NOW_MSG);
    }

    void removeTimeoutsForActivityLocked(ActivityRecord r) {
        if (DEBUG_IDLE) Slog.d(TAG, "removeTimeoutsForActivity: Callers=" + Debug.getCallers(4));
        mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
    }

    final void scheduleResumeTopActivities() {
        mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
    }

    void removeSleepTimeouts() {
        mSleepTimeout = false;
        mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
    }

    final void scheduleSleepTimeout() {
        removeSleepTimeouts();
        mHandler.sendEmptyMessageDelayed(SLEEP_TIMEOUT_MSG, SLEEP_TIMEOUT);
    }

    private final class ActivityStackSupervisorHandler extends Handler {

        public ActivityStackSupervisorHandler(Looper looper) {
            super(looper);
        }

        void activityIdleInternal(ActivityRecord r) {
            synchronized (mService) {
                activityIdleInternalLocked(r != null ? r.appToken : null, true, null);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case IDLE_TIMEOUT_MSG: {
                    if (DEBUG_IDLE) Slog.d(TAG, "handleMessage: IDLE_TIMEOUT_MSG: r=" + msg.obj);
                    if (mService.mDidDexOpt) {
                        mService.mDidDexOpt = false;
                        Message nmsg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG);
                        nmsg.obj = msg.obj;
                        mHandler.sendMessageDelayed(nmsg, IDLE_TIMEOUT);
                        return;
                    }
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    activityIdleInternal((ActivityRecord)msg.obj);
                } break;
                case IDLE_NOW_MSG: {
                    if (DEBUG_IDLE) Slog.d(TAG, "handleMessage: IDLE_NOW_MSG: r=" + msg.obj);
                    activityIdleInternal((ActivityRecord)msg.obj);
                } break;
                case RESUME_TOP_ACTIVITY_MSG: {
                    synchronized (mService) {
                        resumeTopActivitiesLocked();
                    }
                } break;
                case SLEEP_TIMEOUT_MSG: {
                    synchronized (mService) {
                        if (mService.isSleepingOrShuttingDown()) {
                            Slog.w(TAG, "Sleep timeout!  Sleeping now.");
                            mSleepTimeout = true;
                            checkReadyForSleepLocked();
                        }
                    }
                } break;
                case LAUNCH_TIMEOUT_MSG: {
                    if (mService.mDidDexOpt) {
                        mService.mDidDexOpt = false;
                        mHandler.sendEmptyMessageDelayed(LAUNCH_TIMEOUT_MSG, LAUNCH_TIMEOUT);
                        return;
                    }
                    synchronized (mService) {
                        if (mLaunchingActivity.isHeld()) {
                            Slog.w(TAG, "Launch timeout has expired, giving up wake lock!");
                            if (VALIDATE_WAKE_LOCK_CALLER
                                    && Binder.getCallingUid() != Process.myUid()) {
                                throw new IllegalStateException("Calling must be system uid");
                            }
                            mLaunchingActivity.release();
                        }
                    }
                } break;
            }
        }
    }
}
