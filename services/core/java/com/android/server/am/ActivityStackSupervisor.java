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
import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.android.server.am.ActivityManagerDebugConfig.*;
import static com.android.server.am.ActivityManagerService.FIRST_SUPERVISOR_STACK_MSG;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.RECENTS_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStack.ActivityState.*;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_PINNABLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_WHITELISTED;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityContainer;
import android.app.IActivityContainerCallback;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.app.ProfilerInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityManager.WaitResult;
import android.app.ResultInfo;
import android.app.StatusBarManager;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerInternal;
import android.net.Uri;
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
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;

import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputEvent;
import android.view.Surface;
import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.os.TransferPipe;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.wm.WindowManagerService;


import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class ActivityStackSupervisor implements DisplayListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStackSupervisor" : TAG_AM;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_CONTAINERS = TAG + POSTFIX_CONTAINERS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_IDLE = TAG + POSTFIX_IDLE;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    private static final String TAG_VISIBLE_BEHIND = TAG + POSTFIX_VISIBLE_BEHIND;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;

    public static final int HOME_STACK_ID = 0;

    /** How long we wait until giving up on the last activity telling us it is idle. */
    static final int IDLE_TIMEOUT = 10 * 1000;

    /** How long we can hold the sleep wake lock before giving up. */
    static final int SLEEP_TIMEOUT = 5 * 1000;

    // How long we can hold the launch wake lock before giving up.
    static final int LAUNCH_TIMEOUT = 10 * 1000;

    static final int IDLE_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG;
    static final int IDLE_NOW_MSG = FIRST_SUPERVISOR_STACK_MSG + 1;
    static final int RESUME_TOP_ACTIVITY_MSG = FIRST_SUPERVISOR_STACK_MSG + 2;
    static final int SLEEP_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 3;
    static final int LAUNCH_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 4;
    static final int HANDLE_DISPLAY_ADDED = FIRST_SUPERVISOR_STACK_MSG + 5;
    static final int HANDLE_DISPLAY_CHANGED = FIRST_SUPERVISOR_STACK_MSG + 6;
    static final int HANDLE_DISPLAY_REMOVED = FIRST_SUPERVISOR_STACK_MSG + 7;
    static final int CONTAINER_CALLBACK_VISIBILITY = FIRST_SUPERVISOR_STACK_MSG + 8;
    static final int LOCK_TASK_START_MSG = FIRST_SUPERVISOR_STACK_MSG + 9;
    static final int LOCK_TASK_END_MSG = FIRST_SUPERVISOR_STACK_MSG + 10;
    static final int CONTAINER_CALLBACK_TASK_LIST_EMPTY = FIRST_SUPERVISOR_STACK_MSG + 11;
    static final int LAUNCH_TASK_BEHIND_COMPLETE = FIRST_SUPERVISOR_STACK_MSG + 12;
    static final int SHOW_LOCK_TASK_ESCAPE_MESSAGE_MSG = FIRST_SUPERVISOR_STACK_MSG + 13;

    private final static String VIRTUAL_DISPLAY_BASE_NAME = "ActivityViewVirtualDisplay";

    private static final String LOCK_TASK_TAG = "Lock-to-App";

    // Activity actions an app cannot start if it uses a permission which is not granted.
    private static final ArrayMap<String, String> ACTION_TO_RUNTIME_PERMISSION =
            new ArrayMap<>();
    static {
        ACTION_TO_RUNTIME_PERMISSION.put(MediaStore.ACTION_IMAGE_CAPTURE,
                Manifest.permission.CAMERA);
        ACTION_TO_RUNTIME_PERMISSION.put(MediaStore.ACTION_VIDEO_CAPTURE,
                Manifest.permission.CAMERA);
        ACTION_TO_RUNTIME_PERMISSION.put(Intent.ACTION_CALL,
                Manifest.permission.CALL_PHONE);
    }

    /** Action restriction: launching the activity is not restricted. */
    private static final int ACTIVITY_RESTRICTION_NONE = 0;
    /** Action restriction: launching the activity is restricted by a permission. */
    private static final int ACTIVITY_RESTRICTION_PERMISSION = 1;
    /** Action restriction: launching the activity is restricted by an app op. */
    private static final int ACTIVITY_RESTRICTION_APPOP = 2;

    /** Status Bar Service **/
    private IBinder mToken = new Binder();
    private IStatusBarService mStatusBarService;
    private IDevicePolicyManager mDevicePolicyManager;

    // For debugging to make sure the caller when acquiring/releasing our
    // wake lock is the system process.
    static final boolean VALIDATE_WAKE_LOCK_CALLER = false;

    final ActivityManagerService mService;

    private final RecentTasks mRecentTasks;

    final ActivityStackSupervisorHandler mHandler;

    /** Short cut */
    WindowManagerService mWindowManager;
    DisplayManager mDisplayManager;

    /** Identifier counter for all ActivityStacks */
    private int mLastStackId = HOME_STACK_ID;

    /** Task identifier that activities are currently being started in.  Incremented each time a
     * new task is created. */
    private int mCurTaskId = 0;

    /** The current user */
    private int mCurrentUser;

    /** The stack containing the launcher app. Assumed to always be attached to
     * Display.DEFAULT_DISPLAY. */
    private ActivityStack mHomeStack;

    /** The stack currently receiving input or launching the next activity. */
    private ActivityStack mFocusedStack;

    /** If this is the same as mFocusedStack then the activity on the top of the focused stack has
     * been resumed. If stacks are changing position this will hold the old stack until the new
     * stack becomes resumed after which it will be set to mFocusedStack. */
    private ActivityStack mLastFocusedStack;

    /** List of activities that are waiting for a new activity to become visible before completing
     * whatever operation they are supposed to do. */
    final ArrayList<ActivityRecord> mWaitingVisibleActivities = new ArrayList<>();

    /** List of processes waiting to find out about the next visible activity. */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityVisible = new ArrayList<>();

    /** List of processes waiting to find out about the next launched activity. */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityLaunched = new ArrayList<>();

    /** List of activities that are ready to be stopped, but waiting for the next activity to
     * settle down before doing so. */
    final ArrayList<ActivityRecord> mStoppingActivities = new ArrayList<>();

    /** List of activities that are ready to be finished, but waiting for the previous activity to
     * settle down before doing so.  It contains ActivityRecord objects. */
    final ArrayList<ActivityRecord> mFinishingActivities = new ArrayList<>();

    /** List of activities that are in the process of going to sleep. */
    final ArrayList<ActivityRecord> mGoingToSleepActivities = new ArrayList<>();

    /** Used on user changes */
    final ArrayList<UserState> mStartingUsers = new ArrayList<>();

    /** Used to queue up any background users being started */
    final ArrayList<UserState> mStartingBackgroundUsers = new ArrayList<>();

    /** Set to indicate whether to issue an onUserLeaving callback when a newly launched activity
     * is being brought in front of us. */
    boolean mUserLeaving = false;

    /** Set when we have taken too long waiting to go to sleep. */
    boolean mSleepTimeout = false;

    /** Indicates if we are running on a Leanback-only (TV) device. Only initialized after
     * setWindowManager is called. **/
    private boolean mLeanbackOnlyDevice;

    /**
     * We don't want to allow the device to go to sleep while in the process
     * of launching an activity.  This is primarily to allow alarm intent
     * receivers to launch an activity and get that to run before the device
     * goes back to sleep.
     */
    PowerManager.WakeLock mLaunchingActivity;

    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    PowerManager.WakeLock mGoingToSleep;

    /** Stack id of the front stack when user switched, indexed by userId. */
    SparseIntArray mUserStackInFront = new SparseIntArray(2);

    // TODO: Add listener for removal of references.
    /** Mapping from (ActivityStack/TaskStack).mStackId to their current state */
    private SparseArray<ActivityContainer> mActivityContainers = new SparseArray<>();

    /** Mapping from displayId to display current state */
    private final SparseArray<ActivityDisplay> mActivityDisplays = new SparseArray<>();

    InputManagerInternal mInputManagerInternal;

    /** The chain of tasks in lockTask mode. The current frontmost task is at the top, and tasks
     * may be finished until there is only one entry left. If this is empty the system is not
     * in lockTask mode. */
    ArrayList<TaskRecord> mLockTaskModeTasks = new ArrayList<>();
    /** Store the current lock task mode. Possible values:
     * {@link ActivityManager#LOCK_TASK_MODE_NONE}, {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     * {@link ActivityManager#LOCK_TASK_MODE_PINNED}
     */
    private int mLockTaskModeState;
    /**
     * Notifies the user when entering/exiting lock-task.
     */
    private LockTaskNotify mLockTaskNotify;

    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches = new ArrayList<>();

    /** Used to keep resumeTopActivityLocked() from being entered recursively */
    boolean inResumeTopActivity;

    /**
     * Description of a request to start a new activity, which has been held
     * due to app switches being disabled.
     */
    static class PendingActivityLaunch {
        final ActivityRecord r;
        final ActivityRecord sourceRecord;
        final int startFlags;
        final ActivityStack stack;

        PendingActivityLaunch(ActivityRecord _r, ActivityRecord _sourceRecord,
                int _startFlags, ActivityStack _stack) {
            r = _r;
            sourceRecord = _sourceRecord;
            startFlags = _startFlags;
            stack = _stack;
        }
    }

    public ActivityStackSupervisor(ActivityManagerService service, RecentTasks recentTasks) {
        mService = service;
        mRecentTasks = recentTasks;
        mHandler = new ActivityStackSupervisorHandler(mService.mHandler.getLooper());
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize our wakelocks afterwards.
     */
    void initPowerManagement() {
        PowerManager pm = (PowerManager)mService.mContext.getSystemService(Context.POWER_SERVICE);
        mGoingToSleep = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        mLaunchingActivity = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*launch*");
        mLaunchingActivity.setReferenceCounted(false);
    }

    // This function returns a IStatusBarService. The value is from ServiceManager.
    // getService and is cached.
    private IStatusBarService getStatusBarService() {
        synchronized (mService) {
            if (mStatusBarService == null) {
                mStatusBarService = IStatusBarService.Stub.asInterface(
                    ServiceManager.checkService(Context.STATUS_BAR_SERVICE));
                if (mStatusBarService == null) {
                    Slog.w("StatusBarManager", "warning: no STATUS_BAR_SERVICE");
                }
            }
            return mStatusBarService;
        }
    }

    private IDevicePolicyManager getDevicePolicyManager() {
        synchronized (mService) {
            if (mDevicePolicyManager == null) {
                mDevicePolicyManager = IDevicePolicyManager.Stub.asInterface(
                    ServiceManager.checkService(Context.DEVICE_POLICY_SERVICE));
                if (mDevicePolicyManager == null) {
                    Slog.w(TAG, "warning: no DEVICE_POLICY_SERVICE");
                }
            }
            return mDevicePolicyManager;
        }
    }

    void setWindowManager(WindowManagerService wm) {
        synchronized (mService) {
            mWindowManager = wm;

            mDisplayManager =
                    (DisplayManager)mService.mContext.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayManager.registerDisplayListener(this, null);

            Display[] displays = mDisplayManager.getDisplays();
            for (int displayNdx = displays.length - 1; displayNdx >= 0; --displayNdx) {
                final int displayId = displays[displayNdx].getDisplayId();
                ActivityDisplay activityDisplay = new ActivityDisplay(displayId);
                if (activityDisplay.mDisplay == null) {
                    throw new IllegalStateException("Default Display does not exist");
                }
                mActivityDisplays.put(displayId, activityDisplay);
            }

            createStackOnDisplay(HOME_STACK_ID, Display.DEFAULT_DISPLAY);
            mHomeStack = mFocusedStack = mLastFocusedStack = getStack(HOME_STACK_ID);

            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);

            // Initialize this here, now that we can get a valid reference to PackageManager.
            mLeanbackOnlyDevice = isLeanbackOnlyDevice();
        }
    }

    void notifyActivityDrawnForKeyguard() {
        if (DEBUG_LOCKSCREEN) mService.logLockScreen("");
        mWindowManager.notifyActivityDrawnForKeyguard();
    }

    ActivityStack getFocusedStack() {
        return mFocusedStack;
    }

    ActivityStack getLastStack() {
        return mLastFocusedStack;
    }

    /** Top of all visible stacks is/should always be equal to the focused stack.
     * Use {@link ActivityStack#isStackVisibleLocked} to determine if a specific
     * stack is visible or not. */
    boolean isFrontStack(ActivityStack stack) {
        if (stack == null) {
            return false;
        }

        final ActivityRecord parent = stack.mActivityContainer.mParentActivity;
        if (parent != null) {
            stack = parent.task.stack;
        }
        return stack == mFocusedStack;
    }

    void moveHomeStack(boolean toFront, String reason) {
        moveHomeStack(toFront, reason, null);
    }

    void moveHomeStack(boolean toFront, String reason, ActivityStack lastFocusedStack) {
        ArrayList<ActivityStack> stacks = mHomeStack.mStacks;
        final int topNdx = stacks.size() - 1;
        if (topNdx <= 0) {
            return;
        }

        // The home stack should either be at the top or bottom of the stack list.
        if ((toFront && (stacks.get(topNdx) != mHomeStack))
                || (!toFront && (stacks.get(0) != mHomeStack))) {
            if (DEBUG_STACK) Slog.d(TAG_STACK, "moveHomeTask: topStack old="
                    + ((lastFocusedStack != null) ? lastFocusedStack : stacks.get(topNdx))
                    + " new=" + mFocusedStack);
            stacks.remove(mHomeStack);
            stacks.add(toFront ? topNdx : 0, mHomeStack);
        }

        if (lastFocusedStack != null) {
            mLastFocusedStack = lastFocusedStack;
        }
        mFocusedStack = stacks.get(topNdx);

        EventLog.writeEvent(EventLogTags.AM_HOME_STACK_MOVED,
                mCurrentUser, toFront ? 1 : 0, stacks.get(topNdx).getStackId(),
                mFocusedStack == null ? -1 : mFocusedStack.getStackId(), reason);

        if (mService.mBooting || !mService.mBooted) {
            final ActivityRecord r = topRunningActivityLocked();
            if (r != null && r.idle) {
                checkFinishBootingLocked();
            }
        }
    }

    /** Returns true if the focus activity was adjusted to the home stack top activity. */
    boolean moveHomeStackTaskToTop(int homeStackTaskType, String reason) {
        if (homeStackTaskType == RECENTS_ACTIVITY_TYPE) {
            mWindowManager.showRecentApps();
            return false;
        }

        mHomeStack.moveHomeStackTaskToTop(homeStackTaskType);

        final ActivityRecord top = getHomeActivity();
        if (top == null) {
            return false;
        }
        mService.setFocusedActivityLocked(top, reason);
        return true;
    }

    boolean resumeHomeStackTask(int homeStackTaskType, ActivityRecord prev, String reason) {
        if (!mService.mBooting && !mService.mBooted) {
            // Not ready yet!
            return false;
        }

        if (homeStackTaskType == RECENTS_ACTIVITY_TYPE) {
            mWindowManager.showRecentApps();
            return false;
        }

        if (prev != null) {
            prev.task.setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
        }

        mHomeStack.moveHomeStackTaskToTop(homeStackTaskType);
        ActivityRecord r = getHomeActivity();
        if (r != null) {
            mService.setFocusedActivityLocked(r, reason);
            return resumeTopActivitiesLocked(mHomeStack, prev, null);
        }
        return mService.startHomeActivityLocked(mCurrentUser, reason);
    }

    TaskRecord anyTaskForIdLocked(int id) {
        return anyTaskForIdLocked(id, true);
    }

    /**
     * Returns a {@link TaskRecord} for the input id if available. Null otherwise.
     * @param id Id of the task we would like returned.
     * @param restoreFromRecents If the id was not in the active list, but was found in recents,
     *                           restore the task from recents to the active list.
     */
    TaskRecord anyTaskForIdLocked(int id, boolean restoreFromRecents) {
        int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                ActivityStack stack = stacks.get(stackNdx);
                TaskRecord task = stack.taskForIdLocked(id);
                if (task != null) {
                    return task;
                }
            }
        }

        // Don't give up! Look in recents.
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS, "Looking for task id=" + id + " in recents");
        TaskRecord task = mRecentTasks.taskForIdLocked(id);
        if (task == null) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "\tDidn't find task id=" + id + " in recents");
            return null;
        }

        if (!restoreFromRecents) {
            return task;
        }

        if (!restoreRecentTaskLocked(task)) {
            if (DEBUG_RECENTS) Slog.w(TAG_RECENTS,
                    "Couldn't restore task id=" + id + " found in recents");
            return null;
        }
        if (DEBUG_RECENTS) Slog.w(TAG_RECENTS, "Restored task id=" + id + " from in recents");
        return task;
    }

    ActivityRecord isInAnyStackLocked(IBinder token) {
        int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityRecord r = stacks.get(stackNdx).isInStackLocked(token);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    void setNextTaskId(int taskId) {
        if (taskId > mCurTaskId) {
            mCurTaskId = taskId;
        }
    }

    int getNextTaskId() {
        do {
            mCurTaskId++;
            if (mCurTaskId <= 0) {
                mCurTaskId = 1;
            }
        } while (anyTaskForIdLocked(mCurTaskId, false) != null);
        return mCurTaskId;
    }

    ActivityRecord resumedAppLocked() {
        ActivityStack stack = mFocusedStack;
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

    boolean attachApplicationLocked(ProcessRecord app) throws RemoteException {
        final String processName = app.processName;
        boolean didSomething = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!isFrontStack(stack)) {
                    continue;
                }
                ActivityRecord hr = stack.topRunningActivityLocked(null);
                if (hr != null) {
                    if (hr.app == null && app.uid == hr.info.applicationInfo.uid
                            && processName.equals(hr.processName)) {
                        try {
                            if (realStartActivityLocked(hr, app, true, true)) {
                                didSomething = true;
                            }
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Exception in new application when starting activity "
                                  + hr.intent.getComponent().flattenToShortString(), e);
                            throw e;
                        }
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!isFrontStack(stack) || stack.numActivities() == 0) {
                    continue;
                }
                final ActivityRecord resumedActivity = stack.mResumedActivity;
                if (resumedActivity == null || !resumedActivity.idle) {
                    if (DEBUG_STATES) Slog.d(TAG_STATES, "allResumedActivitiesIdle: stack="
                             + stack.mStackId + " " + resumedActivity + " not idle");
                    return false;
                }
            }
        }
        return true;
    }

    boolean allResumedActivitiesComplete() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (isFrontStack(stack)) {
                    final ActivityRecord r = stack.mResumedActivity;
                    if (r != null && r.state != RESUMED) {
                        return false;
                    }
                }
            }
        }
        // TODO: Not sure if this should check if all Paused are complete too.
        if (DEBUG_STACK) Slog.d(TAG_STACK,
                "allResumedActivitiesComplete: mLastFocusedStack changing from=" +
                mLastFocusedStack + " to=" + mFocusedStack);
        mLastFocusedStack = mFocusedStack;
        return true;
    }

    boolean allResumedActivitiesVisible() {
        boolean foundResumed = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                final ActivityRecord r = stack.mResumedActivity;
                if (r != null) {
                    if (!r.nowVisible || mWaitingVisibleActivities.contains(r)) {
                        return false;
                    }
                    foundResumed = true;
                }
            }
        }
        return foundResumed;
    }

    /**
     * Pause all activities in either all of the stacks or just the back stacks.
     * @param userLeaving Passed to pauseActivity() to indicate whether to call onUserLeaving().
     * @return true if any activity was paused as a result of this call.
     */
    boolean pauseBackStacks(boolean userLeaving, boolean resuming, boolean dontWait) {
        boolean someActivityPaused = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!isFrontStack(stack) && stack.mResumedActivity != null) {
                    if (DEBUG_STATES) Slog.d(TAG_STATES, "pauseBackStacks: stack=" + stack +
                            " mResumedActivity=" + stack.mResumedActivity);
                    someActivityPaused |= stack.startPausingLocked(userLeaving, false, resuming,
                            dontWait);
                }
            }
        }
        return someActivityPaused;
    }

    boolean allPausedActivitiesComplete() {
        boolean pausing = true;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                final ActivityRecord r = stack.mPausingActivity;
                if (r != null && r.state != PAUSED && r.state != STOPPED && r.state != STOPPING) {
                    if (DEBUG_STATES) {
                        Slog.d(TAG_STATES,
                                "allPausedActivitiesComplete: r=" + r + " state=" + r.state);
                        pausing = false;
                    } else {
                        return false;
                    }
                }
            }
        }
        return pausing;
    }

    void pauseChildStacks(ActivityRecord parent, boolean userLeaving, boolean uiSleeping,
            boolean resuming, boolean dontWait) {
        // TODO: Put all stacks in supervisor and iterate through them instead.
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (stack.mResumedActivity != null &&
                        stack.mActivityContainer.mParentActivity == parent) {
                    stack.startPausingLocked(userLeaving, uiSleeping, resuming, dontWait);
                }
            }
        }
    }

    void reportActivityVisibleLocked(ActivityRecord r) {
        sendWaitingVisibleReportLocked(r);
    }

    void sendWaitingVisibleReportLocked(ActivityRecord r) {
        boolean changed = false;
        for (int i = mWaitingActivityVisible.size()-1; i >= 0; i--) {
            WaitResult w = mWaitingActivityVisible.get(i);
            if (w.who == null) {
                changed = true;
                w.timeout = false;
                if (r != null) {
                    w.who = new ComponentName(r.info.packageName, r.info.name);
                }
                w.totalTime = SystemClock.uptimeMillis() - w.thisTime;
                w.thisTime = w.totalTime;
            }
        }
        if (changed) {
            mService.notifyAll();
        }
    }

    void reportActivityLaunchedLocked(boolean timeout, ActivityRecord r,
            long thisTime, long totalTime) {
        boolean changed = false;
        for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = mWaitingActivityLaunched.remove(i);
            if (w.who == null) {
                changed = true;
                w.timeout = timeout;
                if (r != null) {
                    w.who = new ComponentName(r.info.packageName, r.info.name);
                }
                w.thisTime = thisTime;
                w.totalTime = totalTime;
            }
        }
        if (changed) {
            mService.notifyAll();
        }
    }

    ActivityRecord topRunningActivityLocked() {
        final ActivityStack focusedStack = mFocusedStack;
        ActivityRecord r = focusedStack.topRunningActivityLocked(null);
        if (r != null) {
            return r;
        }

        // Return to the home stack.
        final ArrayList<ActivityStack> stacks = mHomeStack.mStacks;
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = stacks.get(stackNdx);
            if (stack != focusedStack && isFrontStack(stack)) {
                r = stack.topRunningActivityLocked(null);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    void getTasksLocked(int maxNum, List<RunningTaskInfo> list, int callingUid, boolean allowed) {
        // Gather all of the running tasks for each stack into runningTaskLists.
        ArrayList<ArrayList<RunningTaskInfo>> runningTaskLists =
                new ArrayList<ArrayList<RunningTaskInfo>>();
        final int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                ArrayList<RunningTaskInfo> stackTaskList = new ArrayList<>();
                runningTaskLists.add(stackTaskList);
                stack.getTasksLocked(stackTaskList, callingUid, allowed);
            }
        }

        // The lists are already sorted from most recent to oldest. Just pull the most recent off
        // each list and add it to list. Stop when all lists are empty or maxNum reached.
        while (maxNum > 0) {
            long mostRecentActiveTime = Long.MIN_VALUE;
            ArrayList<RunningTaskInfo> selectedStackList = null;
            final int numTaskLists = runningTaskLists.size();
            for (int stackNdx = 0; stackNdx < numTaskLists; ++stackNdx) {
                ArrayList<RunningTaskInfo> stackTaskList = runningTaskLists.get(stackNdx);
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
    }

    ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
            ProfilerInfo profilerInfo, int userId) {
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

            if (profilerInfo != null) {
                if (!aInfo.processName.equals("system")) {
                    mService.setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
                }
            }
        }
        return aInfo;
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason) {
        moveHomeStackTaskToTop(HOME_ACTIVITY_TYPE, reason);
        startActivityLocked(null /* caller */, intent, null /* resolvedType */, aInfo,
                null /* voiceSession */, null /* voiceInteractor */, null /* resultTo */,
                null /* resultWho */, 0 /* requestCode */, 0 /* callingPid */, 0 /* callingUid */,
                null /* callingPackage */, 0 /* realCallingPid */, 0 /* realCallingUid */,
                0 /* startFlags */, null /* options */, false /* ignoreTargetSecurity */,
                false /* componentSpecified */,
                null /* outActivity */, null /* container */,  null /* inTask */);
        if (inResumeTopActivity) {
            // If we are in resume section already, home activity will be initialized, but not
            // resumed (to avoid recursive resume) and will stay that way until something pokes it
            // again. We need to schedule another resume.
            scheduleResumeTopActivities();
        }
    }

    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, WaitResult outResult, Configuration config,
            Bundle options, boolean ignoreTargetSecurity, int userId,
            IActivityContainer iContainer, TaskRecord inTask) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        boolean componentSpecified = intent.getComponent() != null;

        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        ActivityInfo aInfo =
                resolveActivity(intent, resolvedType, startFlags, profilerInfo, userId);

        ActivityContainer container = (ActivityContainer)iContainer;
        synchronized (mService) {
            if (container != null && container.mParentActivity != null &&
                    container.mParentActivity.state != RESUMED) {
                // Cannot start a child activity if the parent is not resumed.
                return ActivityManager.START_CANCELED;
            }
            final int realCallingPid = Binder.getCallingPid();
            final int realCallingUid = Binder.getCallingUid();
            int callingPid;
            if (callingUid >= 0) {
                callingPid = -1;
            } else if (caller == null) {
                callingPid = realCallingPid;
                callingUid = realCallingUid;
            } else {
                callingPid = callingUid = -1;
            }

            final ActivityStack stack;
            if (container == null || container.mStack.isOnHomeDisplay()) {
                stack = mFocusedStack;
            } else {
                stack = container.mStack;
            }
            stack.mConfigWillChange = config != null && mService.mConfiguration.diff(config) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Starting activity when config will change = " + stack.mConfigWillChange);

            final long origId = Binder.clearCallingIdentity();

            if (aInfo != null &&
                    (aInfo.applicationInfo.privateFlags
                            &ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
                // This may be a heavy-weight process!  Check to see if we already
                // have another, different heavy-weight process running.
                if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                    if (mService.mHeavyWeightProcess != null &&
                            (mService.mHeavyWeightProcess.info.uid != aInfo.applicationInfo.uid ||
                            !mService.mHeavyWeightProcess.processName.equals(aInfo.processName))) {
                        int appCallingUid = callingUid;
                        if (caller != null) {
                            ProcessRecord callerApp = mService.getRecordForAppLocked(caller);
                            if (callerApp != null) {
                                appCallingUid = callerApp.info.uid;
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
                                appCallingUid, userId, null, null, 0, new Intent[] { intent },
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

            int res = startActivityLocked(caller, intent, resolvedType, aInfo,
                    voiceSession, voiceInteractor, resultTo, resultWho,
                    requestCode, callingPid, callingUid, callingPackage,
                    realCallingPid, realCallingUid, startFlags, options, ignoreTargetSecurity,
                    componentSpecified, null, container, inTask);

            Binder.restoreCallingIdentity(origId);

            if (stack.mConfigWillChange) {
                // If the caller also wants to switch to a new configuration,
                // do so now.  This allows a clean switch, as we are waiting
                // for the current activity to pause (so we will not destroy
                // it), and have not yet started the next activity.
                mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                        "updateConfiguration()");
                stack.mConfigWillChange = false;
                if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Updating to new configuration after starting activity.");
                mService.updateConfigurationLocked(config, null, false, false);
            }

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
                    if (r.nowVisible && r.state == RESUMED) {
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
                    ActivityInfo aInfo = resolveActivity(intent, resolvedTypes[i], 0, null, userId);
                    // TODO: New, check if this is correct
                    aInfo = mService.getActivityInfoForUser(aInfo, userId);

                    if (aInfo != null &&
                            (aInfo.applicationInfo.privateFlags
                                    & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)  != 0) {
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
                            aInfo, null, null, resultTo, null, -1, callingPid, callingUid,
                            callingPackage, callingPid, callingUid,
                            0, theseOptions, false, componentSpecified, outActivity, null, null);
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

        if (andResume) {
            r.startFreezingScreenLocked(app, 0);
            mWindowManager.setAppVisibility(r.appToken, true);

            // schedule launch ticks to collect information about slow apps.
            r.startLaunchTickingLocked();
        }

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

        if (DEBUG_ALL) Slog.v(TAG, "Launching: " + r);

        int idx = app.activities.indexOf(r);
        if (idx < 0) {
            app.activities.add(r);
        }
        mService.updateLruProcessLocked(app, true, null);
        mService.updateOomAdjLocked();

        final TaskRecord task = r.task;
        if (task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE ||
                task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE_PRIV) {
            setLockTaskModeLocked(task, LOCK_TASK_MODE_LOCKED, "mLockTaskAuth==LAUNCHABLE", false);
        }

        final ActivityStack stack = task.stack;
        try {
            if (app.thread == null) {
                throw new RemoteException();
            }
            List<ResultInfo> results = null;
            List<ReferrerIntent> newIntents = null;
            if (andResume) {
                results = r.results;
                newIntents = r.newIntents;
            }
            if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                    "Launching: " + r + " icicle=" + r.icicle + " with results=" + results
                    + " newIntents=" + newIntents + " andResume=" + andResume);
            if (andResume) {
                EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY,
                        r.userId, System.identityHashCode(r),
                        task.taskId, r.shortComponentName);
            }
            if (r.isHomeActivity() && r.isNotResolverActivity()) {
                // Home process is the root process of the task.
                mService.mHomeProcess = task.mActivities.get(0).app;
            }
            mService.ensurePackageDexOpt(r.intent.getComponent().getPackageName());
            r.sleeping = false;
            r.forceNewConfig = false;
            mService.showAskCompatModeDialogLocked(r);
            r.compat = mService.compatibilityInfoForPackageLocked(r.info.applicationInfo);
            ProfilerInfo profilerInfo = null;
            if (mService.mProfileApp != null && mService.mProfileApp.equals(app.processName)) {
                if (mService.mProfileProc == null || mService.mProfileProc == app) {
                    mService.mProfileProc = app;
                    final String profileFile = mService.mProfileFile;
                    if (profileFile != null) {
                        ParcelFileDescriptor profileFd = mService.mProfileFd;
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

                        profilerInfo = new ProfilerInfo(profileFile, profileFd,
                                mService.mSamplingInterval, mService.mAutoStopProfiler);
                    }
                }
            }

            if (andResume) {
                app.hasShownUi = true;
                app.pendingUiClean = true;
            }
            app.forceProcessStateUpTo(mService.mTopProcessState);
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                    System.identityHashCode(r), r.info, new Configuration(mService.mConfiguration),
                    new Configuration(stack.mOverrideConfig), r.compat, r.launchedFromPackage,
                    task.voiceInteractor, app.repProcState, r.icicle, r.persistentState, results,
                    newIntents, !andResume, mService.isNextTransitionForward(), profilerInfo);

            if ((app.info.privateFlags&ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
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
                mService.appDiedLocked(app);
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
            if (DEBUG_STATES) Slog.v(TAG_STATES,
                    "Moving to STOPPED: " + r + " (starting in stopped state)");
            r.state = STOPPED;
            r.stopped = true;
        }

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        if (isFrontStack(stack)) {
            mService.startSetupActivityLocked();
        }

        // Update any services we are bound to that might care about whether
        // their client may have activities.
        mService.mServices.updateServiceConnectionActivitiesLocked(r.app);

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
                    app.addPackage(r.info.packageName, r.info.applicationInfo.versionCode,
                            mService.mProcessStats);
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
            Intent intent, String resolvedType, ActivityInfo aInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode,
            int callingPid, int callingUid, String callingPackage,
            int realCallingPid, int realCallingUid, int startFlags, Bundle options,
            boolean ignoreTargetSecurity, boolean componentSpecified, ActivityRecord[] outActivity,
            ActivityContainer container, TaskRecord inTask) {
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

        final int userId = aInfo != null ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;

        if (err == ActivityManager.START_SUCCESS) {
            Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
                    + "} from uid " + callingUid
                    + " on display " + (container == null ? (mFocusedStack == null ?
                            Display.DEFAULT_DISPLAY : mFocusedStack.mDisplayId) :
                            (container.mActivityDisplay == null ? Display.DEFAULT_DISPLAY :
                                    container.mActivityDisplay.mDisplayId)));
        }

        ActivityRecord sourceRecord = null;
        ActivityRecord resultRecord = null;
        if (resultTo != null) {
            sourceRecord = isInAnyStackLocked(resultTo);
            if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                    "Will send result to " + resultTo + " " + sourceRecord);
            if (sourceRecord != null) {
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    resultRecord = sourceRecord;
                }
            }
        }

        final int launchFlags = intent.getFlags();

        if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
            // Transfer the result target from the source activity to the new
            // one being started, including any failures.
            if (requestCode >= 0) {
                ActivityOptions.abort(options);
                return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
            }
            resultRecord = sourceRecord.resultTo;
            if (resultRecord != null && !resultRecord.isInStackLocked()) {
                resultRecord = null;
            }
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;
            sourceRecord.resultTo = null;
            if (resultRecord != null) {
                resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
            }
            if (sourceRecord.launchedFromUid == callingUid) {
                // The new activity is being launched from the same uid as the previous
                // activity in the flow, and asking to forward its result back to the
                // previous.  In this case the activity is serving as a trampoline between
                // the two, so we also want to update its launchedFromPackage to be the
                // same as the previous activity.  Note that this is safe, since we know
                // these two packages come from the same uid; the caller could just as
                // well have supplied that same package name itself.  This specifially
                // deals with the case of an intent picker/chooser being launched in the app
                // flow to redirect to an activity picked by the user, where we want the final
                // activity to consider it to have been launched by the previous app activity.
                callingPackage = sourceRecord.launchedFromPackage;
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

        if (err == ActivityManager.START_SUCCESS
                && !isCurrentProfileLocked(userId)
                && (aInfo.flags & FLAG_SHOW_FOR_ALL_USERS) == 0) {
            // Trying to launch a background activity that doesn't show for all users.
            err = ActivityManager.START_NOT_CURRENT_USER_ACTIVITY;
        }

        if (err == ActivityManager.START_SUCCESS && sourceRecord != null
                && sourceRecord.task.voiceSession != null) {
            // If this activity is being launched as part of a voice session, we need
            // to ensure that it is safe to do so.  If the upcoming activity will also
            // be part of the voice session, we can only launch it if it has explicitly
            // said it supports the VOICE category, or it is a part of the calling app.
            if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0
                    && sourceRecord.info.applicationInfo.uid != aInfo.applicationInfo.uid) {
                try {
                    intent.addCategory(Intent.CATEGORY_VOICE);
                    if (!AppGlobals.getPackageManager().activitySupportsIntent(
                            intent.getComponent(), intent, resolvedType)) {
                        Slog.w(TAG,
                                "Activity being started in current voice task does not support voice: "
                                + intent);
                        err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failure checking voice capabilities", e);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            }
        }

        if (err == ActivityManager.START_SUCCESS && voiceSession != null) {
            // If the caller is starting a new voice session, just make sure the target
            // is actually allowing it to run this way.
            try {
                if (!AppGlobals.getPackageManager().activitySupportsIntent(intent.getComponent(),
                        intent, resolvedType)) {
                    Slog.w(TAG,
                            "Activity being started in new voice task does not support: "
                            + intent);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure checking voice capabilities", e);
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        }

        final ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

        if (err != ActivityManager.START_SUCCESS) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            return err;
        }

        boolean abort = false;

        final int startAnyPerm = mService.checkPermission(
                START_ANY_ACTIVITY, callingPid, callingUid);

        if (startAnyPerm != PERMISSION_GRANTED) {
            final int componentRestriction = getComponentRestrictionForCallingPackage(
                    aInfo, callingPackage, callingPid, callingUid, ignoreTargetSecurity);
            final int actionRestriction = getActionRestrictionForCallingPackage(
                    intent.getAction(), callingPackage, callingPid, callingUid);

            if (componentRestriction == ACTIVITY_RESTRICTION_PERMISSION
                    || actionRestriction == ACTIVITY_RESTRICTION_PERMISSION) {
                if (resultRecord != null) {
                    resultStack.sendActivityResultLocked(-1,
                            resultRecord, resultWho, requestCode,
                            Activity.RESULT_CANCELED, null);
                }
                String msg;
                if (actionRestriction == ACTIVITY_RESTRICTION_PERMISSION) {
                    msg = "Permission Denial: starting " + intent.toString()
                            + " from " + callerApp + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")" + " with revoked permission "
                            + ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction());
                } else if (!aInfo.exported) {
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

            if (actionRestriction == ACTIVITY_RESTRICTION_APPOP) {
                String message = "Appop Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires " + AppOpsManager.permissionToOp(
                                ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction()));
                Slog.w(TAG, message);
                abort = true;
            } else if (componentRestriction == ACTIVITY_RESTRICTION_APPOP) {
                String message = "Appop Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires appop " + AppOpsManager.permissionToOp(aInfo.permission);
                Slog.w(TAG, message);
                abort = true;
            }
        }

        abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
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
            ActivityOptions.abort(options);
            return ActivityManager.START_SUCCESS;
        }

        ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
                intent, resolvedType, aInfo, mService.mConfiguration, resultRecord, resultWho,
                requestCode, componentSpecified, voiceSession != null, this, container, options);
        if (outActivity != null) {
            outActivity[0] = r;
        }

        if (r.appTimeTracker == null && sourceRecord != null) {
            // If the caller didn't specify an explicit time tracker, we want to continue
            // tracking under any it has.
            r.appTimeTracker = sourceRecord.appTimeTracker;
        }

        final ActivityStack stack = mFocusedStack;
        if (voiceSession == null && (stack.mResumedActivity == null
                || stack.mResumedActivity.info.applicationInfo.uid != callingUid)) {
            if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                    realCallingPid, realCallingUid, "Activity start")) {
                PendingActivityLaunch pal =
                        new PendingActivityLaunch(r, sourceRecord, startFlags, stack);
                mPendingActivityLaunches.add(pal);
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

        doPendingActivityLaunchesLocked(false);

        err = startActivityUncheckedLocked(r, sourceRecord, voiceSession, voiceInteractor,
                startFlags, true, options, inTask);

        if (err < 0) {
            // If someone asked to have the keyguard dismissed on the next
            // activity start, but we are not actually doing an activity
            // switch...  just dismiss the keyguard now, because we
            // probably want to see whatever is behind it.
            notifyActivityDrawnForKeyguard();
        }
        return err;
    }

    private int getComponentRestrictionForCallingPackage(ActivityInfo activityInfo,
            String callingPackage, int callingPid, int callingUid, boolean ignoreTargetSecurity) {
        if (!ignoreTargetSecurity && mService.checkComponentPermission(activityInfo.permission,
                callingPid, callingUid, activityInfo.applicationInfo.uid, activityInfo.exported)
                == PackageManager.PERMISSION_DENIED) {
            return ACTIVITY_RESTRICTION_PERMISSION;
        }

        if (activityInfo.permission == null) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        final int opCode = AppOpsManager.permissionToOpCode(activityInfo.permission);
        if (opCode == AppOpsManager.OP_NONE) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        if (mService.mAppOpsService.noteOperation(opCode, callingUid,
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            if (!ignoreTargetSecurity) {
                return ACTIVITY_RESTRICTION_APPOP;
            }
        }

        return ACTIVITY_RESTRICTION_NONE;
    }

    private int getActionRestrictionForCallingPackage(String action,
            String callingPackage, int callingPid, int callingUid) {
        if (action == null) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        String permission = ACTION_TO_RUNTIME_PERMISSION.get(action);
        if (permission == null) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        final PackageInfo packageInfo;
        try {
            packageInfo = mService.mContext.getPackageManager()
                    .getPackageInfo(callingPackage, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i(TAG, "Cannot find package info for " + callingPackage);
            return ACTIVITY_RESTRICTION_NONE;
        }

        if (!ArrayUtils.contains(packageInfo.requestedPermissions, permission)) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        if (mService.checkPermission(permission, callingPid, callingUid) ==
                PackageManager.PERMISSION_DENIED) {
            return ACTIVITY_RESTRICTION_PERMISSION;
        }

        final int opCode = AppOpsManager.permissionToOpCode(permission);
        if (opCode == AppOpsManager.OP_NONE) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        if (mService.mAppOpsService.noteOperation(opCode, callingUid,
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return ACTIVITY_RESTRICTION_APPOP;
        }

        return ACTIVITY_RESTRICTION_NONE;
    }

    ActivityStack computeStackFocus(ActivityRecord r, boolean newTask) {
        final TaskRecord task = r.task;

        // On leanback only devices we should keep all activities in the same stack.
        if (!mLeanbackOnlyDevice &&
                (r.isApplicationActivity() || (task != null && task.isApplicationTask()))) {

            ActivityStack stack;

            if (task != null && task.stack != null) {
                stack = task.stack;
                if (stack.isOnHomeDisplay()) {
                    if (mFocusedStack != stack) {
                        if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                                "computeStackFocus: Setting " + "focused stack to r=" + r
                                + " task=" + task);
                    } else {
                        if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Focused stack already=" + mFocusedStack);
                    }
                }
                return stack;
            }

            final ActivityContainer container = r.mInitialActivityContainer;
            if (container != null) {
                // The first time put it on the desired stack, after this put on task stack.
                r.mInitialActivityContainer = null;
                return container.mStack;
            }

            if (mFocusedStack != mHomeStack && (!newTask ||
                    mFocusedStack.mActivityContainer.isEligibleForNewTasks())) {
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                        "computeStackFocus: Have a focused stack=" + mFocusedStack);
                return mFocusedStack;
            }

            final ArrayList<ActivityStack> homeDisplayStacks = mHomeStack.mStacks;
            for (int stackNdx = homeDisplayStacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stack = homeDisplayStacks.get(stackNdx);
                if (!stack.isHomeStack()) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Setting focused stack=" + stack);
                    return stack;
                }
            }

            // Need to create an app stack for this user.
            stack = createStackOnDisplay(getNextStackId(), Display.DEFAULT_DISPLAY);
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS, "computeStackFocus: New stack r="
                    + r + " stackId=" + stack.mStackId);
            return stack;
        }
        return mHomeStack;
    }

    boolean setFocusedStack(ActivityRecord r, String reason) {
        if (r == null) {
            // Not sure what you are trying to do, but it is not going to work...
            return false;
        }
        final TaskRecord task = r.task;
        if (task == null || task.stack == null) {
            Slog.w(TAG, "Can't set focus stack for r=" + r + " task=" + task);
            return false;
        }
        task.stack.moveToFront(reason);
        return true;
    }

    final int startActivityUncheckedLocked(final ActivityRecord r, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags,
            boolean doResume, Bundle options, TaskRecord inTask) {
        final Intent intent = r.intent;
        final int callingUid = r.launchedFromUid;

        // In some flows in to this function, we retrieve the task record and hold on to it
        // without a lock before calling back in to here...  so the task at this point may
        // not actually be in recents.  Check for that, and if it isn't in recents just
        // consider it invalid.
        if (inTask != null && !inTask.inRecents) {
            Slog.w(TAG, "Starting activity in task not in recents: " + inTask);
            inTask = null;
        }

        final boolean launchSingleTop = r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP;
        final boolean launchSingleInstance = r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        final boolean launchSingleTask = r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK;

        int launchFlags = intent.getFlags();
        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (launchSingleInstance || launchSingleTask)) {
            // We have a conflict between the Intent and the Activity manifest, manifest wins.
            Slog.i(TAG, "Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is " +
                    "\"singleInstance\" or \"singleTask\"");
            launchFlags &=
                    ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        } else {
            switch (r.info.documentLaunchMode) {
                case ActivityInfo.DOCUMENT_LAUNCH_NONE:
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING:
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_ALWAYS:
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_NEVER:
                    launchFlags &= ~Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
                    break;
            }
        }

        final boolean launchTaskBehind = r.mLaunchTaskBehind
                && !launchSingleTask && !launchSingleInstance
                && (launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0;

        if (r.resultTo != null && (launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0
                && r.resultTo.task.stack != null) {
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

        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        }

        // If we are actually going to launch in to a new task, there are some cases where
        // we further want to do multiple task.
        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            if (launchTaskBehind
                    || r.info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS) {
                launchFlags |= Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
            }
        }

        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        mUserLeaving = (launchFlags & Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                "startActivity() => mUserLeaving=" + mUserLeaving);

        // If the caller has asked not to resume at this point, we make note
        // of this in the record so that we can skip it when trying to find
        // the top running activity.
        if (!doResume) {
            r.delayedResume = true;
        }

        ActivityRecord notTop =
                (launchFlags & Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

        // If the onlyIfNeeded flag is set, then we can do this if the activity
        // being launched is the same as the one making the call...  or, as
        // a special case, if we do not know the caller then we count the
        // current top activity as the caller.
        if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = mFocusedStack.topRunningNonDelayedActivityLocked(notTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                startFlags &= ~ActivityManager.START_FLAG_ONLY_IF_NEEDED;
            }
        }

        boolean addingToTask = false;
        TaskRecord reuseTask = null;

        // If the caller is not coming from another activity, but has given us an
        // explicit task into which they would like us to launch the new activity,
        // then let's see about doing that.
        if (sourceRecord == null && inTask != null && inTask.stack != null) {
            final Intent baseIntent = inTask.getBaseIntent();
            final ActivityRecord root = inTask.getRootActivity();
            if (baseIntent == null) {
                ActivityOptions.abort(options);
                throw new IllegalArgumentException("Launching into task without base intent: "
                        + inTask);
            }

            // If this task is empty, then we are adding the first activity -- it
            // determines the root, and must be launching as a NEW_TASK.
            if (launchSingleInstance || launchSingleTask) {
                if (!baseIntent.getComponent().equals(r.intent.getComponent())) {
                    ActivityOptions.abort(options);
                    throw new IllegalArgumentException("Trying to launch singleInstance/Task "
                            + r + " into different task " + inTask);
                }
                if (root != null) {
                    ActivityOptions.abort(options);
                    throw new IllegalArgumentException("Caller with inTask " + inTask
                            + " has root " + root + " but target is singleInstance/Task");
                }
            }

            // If task is empty, then adopt the interesting intent launch flags in to the
            // activity being started.
            if (root == null) {
                final int flagsOfInterest = Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
                launchFlags = (launchFlags&~flagsOfInterest)
                        | (baseIntent.getFlags()&flagsOfInterest);
                intent.setFlags(launchFlags);
                inTask.setIntent(r);
                addingToTask = true;

            // If the task is not empty and the caller is asking to start it as the root
            // of a new task, then we don't actually want to start this on the task.  We
            // will bring the task to the front, and possibly give it a new intent.
            } else if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
                addingToTask = false;

            } else {
                addingToTask = true;
            }

            reuseTask = inTask;
        } else {
            inTask = null;
        }

        if (inTask == null) {
            if (sourceRecord == null) {
                // This activity is not being started from another...  in this
                // case we -always- start a new task.
                if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0 && inTask == null) {
                    Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                            "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
                }
            } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                // The original activity who is starting us is running as a single
                // instance...  this new activity it is starting must go on its
                // own task.
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            } else if (launchSingleInstance || launchSingleTask) {
                // The activity being started is a single instance...  it always
                // gets launched into its own task.
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        }

        ActivityInfo newTaskInfo = null;
        Intent newTaskIntent = null;
        ActivityStack sourceStack;
        if (sourceRecord != null) {
            if (sourceRecord.finishing) {
                // If the source is finishing, we can't further count it as our source.  This
                // is because the task it is associated with may now be empty and on its way out,
                // so we don't want to blindly throw it in to that task.  Instead we will take
                // the NEW_TASK flow and try to find a task for it. But save the task information
                // so it can be used when creating the new task.
                if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
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

        boolean movedHome = false;
        ActivityStack targetStack;

        intent.setFlags(launchFlags);
        final boolean noAnimation = (launchFlags & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0;

        // We may want to try to place the new activity in to an existing task.  We always
        // do this if the target activity is singleTask or singleInstance; we will also do
        // this if NEW_TASK has been requested, and there is not an additional qualifier telling
        // us to still place it in a new task: multi task, always doc mode, or being asked to
        // launch this as a new task behind the current one.
        if (((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (launchFlags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || launchSingleInstance || launchSingleTask) {
            // If bring to front is requested, and no result is requested and we have not
            // been given an explicit task to launch in to, and
            // we can find a task that was started with this same
            // component, then instead of launching bring that one to the front.
            if (inTask == null && r.resultTo == null) {
                // See if there is a task to bring to the front.  If this is
                // a SINGLE_INSTANCE activity, there can be one and only one
                // instance of it in the history, and it is always in its own
                // unique task, so we do a special search.
                ActivityRecord intentActivity = !launchSingleInstance ?
                        findTaskLocked(r) : findActivityLocked(intent, r.info);
                if (intentActivity != null) {
                    // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused
                    // but still needs to be a lock task mode violation since the task gets
                    // cleared out and the device would otherwise leave the locked task.
                    if (isLockTaskModeViolation(intentActivity.task,
                            (launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))) {
                        showLockTaskToast();
                        Slog.e(TAG, "startActivityUnchecked: Attempt to violate Lock Task Mode");
                        return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
                    }
                    if (r.task == null) {
                        r.task = intentActivity.task;
                    }
                    if (intentActivity.task.intent == null) {
                        // This task was started because of movement of
                        // the activity based on affinity...  now that we
                        // are actually launching it, we can assign the
                        // base intent.
                        intentActivity.task.setIntent(r);
                    }
                    targetStack = intentActivity.task.stack;
                    targetStack.mLastPausedActivity = null;
                    // If the target task is not in the front, then we need
                    // to bring it to the front...  except...  well, with
                    // SINGLE_TASK_LAUNCH it's not entirely clear.  We'd like
                    // to have the same behavior as if a new instance was
                    // being started, which means not bringing it to the front
                    // if the caller is not itself in the front.
                    final ActivityStack focusStack = getFocusedStack();
                    ActivityRecord curTop = (focusStack == null)
                            ? null : focusStack.topRunningNonDelayedActivityLocked(notTop);
                    boolean movedToFront = false;
                    if (curTop != null && (curTop.task != intentActivity.task ||
                            curTop.task != focusStack.topTask())) {
                        r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                        if (sourceRecord == null || (sourceStack.topActivity() != null &&
                                sourceStack.topActivity().task == sourceRecord.task)) {
                            // We really do want to push this one into the user's face, right now.
                            if (launchTaskBehind && sourceRecord != null) {
                                intentActivity.setTaskToAffiliateWith(sourceRecord.task);
                            }
                            movedHome = true;
                            targetStack.moveTaskToFrontLocked(intentActivity.task, noAnimation,
                                    options, r.appTimeTracker, "bringingFoundTaskToFront");
                            movedToFront = true;
                            if ((launchFlags &
                                    (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                                    == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                                // Caller wants to appear on home activity.
                                intentActivity.task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
                            }
                            options = null;
                        }
                    }
                    if (!movedToFront) {
                        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Bring to front target: " + targetStack
                                + " from " + intentActivity);
                        targetStack.moveToFront("intentActivityFound");
                    }

                    // If the caller has requested that the target task be
                    // reset, then do so.
                    if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                        intentActivity = targetStack.resetTaskIfNeededLocked(intentActivity, r);
                    }
                    if ((startFlags & ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                        // We don't need to start a new activity, and
                        // the client said not to do anything if that
                        // is the case, so this is it!  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        if (doResume) {
                            resumeTopActivitiesLocked(targetStack, null, options);

                            // Make sure to notify Keyguard as well if we are not running an app
                            // transition later.
                            if (!movedToFront) {
                                notifyActivityDrawnForKeyguard();
                            }
                        } else {
                            ActivityOptions.abort(options);
                        }
                        return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                    }
                    if ((launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
                        // The caller has requested to completely replace any
                        // existing task with its new activity.  Well that should
                        // not be too hard...
                        reuseTask = intentActivity.task;
                        reuseTask.performClearTaskLocked();
                        reuseTask.setIntent(r);
                    } else if ((launchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                            || launchSingleInstance || launchSingleTask) {
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
                                top.task.setIntent(r);
                            }
                            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT,
                                    r, top.task);
                            top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                        } else {
                            // A special case: we need to start the activity because it is not
                            // currently running, and the caller has asked to clear the current
                            // task to have this activity at the top.
                            addingToTask = true;
                            // Now pretend like this activity is being started by the top of its
                            // task, so it is put in the right place.
                            sourceRecord = intentActivity;
                            TaskRecord task = sourceRecord.task;
                            if (task != null && task.stack == null) {
                                // Target stack got cleared when we all activities were removed
                                // above. Go ahead and reset it.
                                targetStack = computeStackFocus(sourceRecord, false /* newTask */);
                                targetStack.addTask(
                                        task, !launchTaskBehind /* toTop */, false /* moving */);
                            }

                        }
                    } else if (r.realActivity.equals(intentActivity.task.realActivity)) {
                        // In this case the top activity on the task is the
                        // same as the one being launched, so we take that
                        // as a request to bring the task to the foreground.
                        // If the top activity in the task is the root
                        // activity, deliver this new intent to it if it
                        // desires.
                        if (((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0 || launchSingleTop)
                                && intentActivity.realActivity.equals(r.realActivity)) {
                            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r,
                                    intentActivity.task);
                            if (intentActivity.frontOfTask) {
                                intentActivity.task.setIntent(r);
                            }
                            intentActivity.deliverNewIntentLocked(callingUid, r.intent,
                                    r.launchedFromPackage);
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
                        intentActivity.task.setIntent(r);
                    }
                    if (!addingToTask && reuseTask == null) {
                        // We didn't do anything...  but it was needed (a.k.a., client
                        // don't use that intent!)  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        if (doResume) {
                            targetStack.resumeTopActivityLocked(null, options);
                            if (!movedToFront) {
                                // Make sure to notify Keyguard as well if we are not running an app
                                // transition later.
                                notifyActivityDrawnForKeyguard();
                            }
                        } else {
                            ActivityOptions.abort(options);
                        }
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
            ActivityStack topStack = mFocusedStack;
            ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(notTop);
            if (top != null && r.resultTo == null) {
                if (top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                    if (top.app != null && top.app.thread != null) {
                        if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                            || launchSingleTop || launchSingleTask) {
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
                                return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                            }
                            top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                            return ActivityManager.START_DELIVERED_TO_TOP;
                        }
                    }
                }
            }

        } else {
            if (r.resultTo != null && r.resultTo.task.stack != null) {
                r.resultTo.task.stack.sendActivityResultLocked(-1, r.resultTo, r.resultWho,
                        r.requestCode, Activity.RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            return ActivityManager.START_CLASS_NOT_FOUND;
        }

        boolean newTask = false;
        boolean keepCurTransition = false;

        TaskRecord taskToAffiliate = launchTaskBehind && sourceRecord != null ?
                sourceRecord.task : null;

        // Should this be considered a new task?
        if (r.resultTo == null && inTask == null && !addingToTask
                && (launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            newTask = true;
            targetStack = computeStackFocus(r, newTask);
            targetStack.moveToFront("startingNewTask");

            if (reuseTask == null) {
                r.setTask(targetStack.createTaskRecord(getNextTaskId(),
                        newTaskInfo != null ? newTaskInfo : r.info,
                        newTaskIntent != null ? newTaskIntent : intent,
                        voiceSession, voiceInteractor, !launchTaskBehind /* toTop */),
                        taskToAffiliate);
                if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                        "Starting new activity " + r + " in new task " + r.task);
            } else {
                r.setTask(reuseTask, taskToAffiliate);
            }
            if (isLockTaskModeViolation(r.task)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            if (!movedHome) {
                if ((launchFlags &
                        (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                        == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                    // Caller wants to appear on home activity, so before starting
                    // their own activity we will bring home to the front.
                    r.task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
                }
            }
        } else if (sourceRecord != null) {
            final TaskRecord sourceTask = sourceRecord.task;
            if (isLockTaskModeViolation(sourceTask)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            targetStack = sourceTask.stack;
            targetStack.moveToFront("sourceStackToFront");
            final TaskRecord topTask = targetStack.topTask();
            if (topTask != sourceTask) {
                targetStack.moveTaskToFrontLocked(sourceTask, noAnimation, options,
                        r.appTimeTracker, "sourceTaskToFront");
            }
            if (!addingToTask && (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                // In this case, we are adding the activity to an existing
                // task, but the caller has asked to clear that task if the
                // activity is already running.
                ActivityRecord top = sourceTask.performClearTaskLocked(r, launchFlags);
                keepCurTransition = true;
                if (top != null) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    // For paranoia, make sure we have correctly
                    // resumed the top activity.
                    targetStack.mLastPausedActivity = null;
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null);
                    }
                    ActivityOptions.abort(options);
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
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
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
            r.setTask(sourceTask, null);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                    + " in existing task " + r.task + " from source " + sourceRecord);

        } else if (inTask != null) {
            // The caller is asking that the new activity be started in an explicit
            // task it has provided to us.
            if (isLockTaskModeViolation(inTask)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            targetStack = inTask.stack;
            targetStack.moveTaskToFrontLocked(inTask, noAnimation, options, r.appTimeTracker,
                    "inTaskToFront");

            // Check whether we should actually launch the new activity in to the task,
            // or just reuse the current activity on top.
            ActivityRecord top = inTask.getTopActivity();
            if (top != null && top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                        || launchSingleTop || launchSingleTask) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top, top.task);
                    if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                        // We don't need to start a new activity, and
                        // the client said not to do anything if that
                        // is the case, so this is it!
                        return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                    }
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            }

            if (!addingToTask) {
                // We don't actually want to have this activity added to the task, so just
                // stop here but still tell the caller that we consumed the intent.
                ActivityOptions.abort(options);
                return ActivityManager.START_TASK_TO_FRONT;
            }

            r.setTask(inTask, null);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                    + " in explicit task " + r.task);

        } else {
            // This not being started from an existing activity, and not part
            // of a new task...  just put it in the top task, though these days
            // this case should never happen.
            targetStack = computeStackFocus(r, newTask);
            targetStack.moveToFront("addingToTopTask");
            ActivityRecord prev = targetStack.topActivity();
            r.setTask(prev != null ? prev.task : targetStack.createTaskRecord(getNextTaskId(),
                            r.info, intent, null, null, true), null);
            mWindowManager.moveTaskToTop(r.task.taskId);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                    + " in new guessed " + r.task);
        }

        mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                intent, r.getUriPermissionsLocked(), r.userId);

        if (sourceRecord != null && sourceRecord.isRecentsActivity()) {
            r.task.setTaskToReturnTo(RECENTS_ACTIVITY_TYPE);
        }
        if (newTask) {
            EventLog.writeEvent(EventLogTags.AM_CREATE_TASK, r.userId, r.task.taskId);
        }
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, r, r.task);
        targetStack.mLastPausedActivity = null;
        targetStack.startActivityLocked(r, newTask, doResume, keepCurTransition, options);
        if (!launchTaskBehind) {
            // Don't set focus on an activity that's going to the back.
            mService.setFocusedActivityLocked(r, "startedActivity");
        }
        return ActivityManager.START_SUCCESS;
    }

    final void doPendingActivityLaunchesLocked(boolean doResume) {
        while (!mPendingActivityLaunches.isEmpty()) {
            PendingActivityLaunch pal = mPendingActivityLaunches.remove(0);
            startActivityUncheckedLocked(pal.r, pal.sourceRecord, null, null, pal.startFlags,
                    doResume && mPendingActivityLaunches.isEmpty(), null, null);
        }
    }

    void removePendingActivityLaunchesLocked(ActivityStack stack) {
        for (int palNdx = mPendingActivityLaunches.size() - 1; palNdx >= 0; --palNdx) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(palNdx);
            if (pal.stack == stack) {
                mPendingActivityLaunches.remove(palNdx);
            }
        }
    }

    void setLaunchSource(int uid) {
        mLaunchingActivity.setWorkSource(new WorkSource(uid));
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

    /**
     * Called when the frontmost task is idle.
     * @return the state of mService.mBooting before this was called.
     */
    private boolean checkFinishBootingLocked() {
        final boolean booting = mService.mBooting;
        boolean enableScreen = false;
        mService.mBooting = false;
        if (!mService.mBooted) {
            mService.mBooted = true;
            enableScreen = true;
        }
        if (booting || enableScreen) {
            mService.postFinishBooting(booting, enableScreen);
        }
        return booting;
    }

    // Checked.
    final ActivityRecord activityIdleInternalLocked(final IBinder token, boolean fromTimeout,
            Configuration config) {
        if (DEBUG_ALL) Slog.v(TAG, "Activity idle: " + token);

        ArrayList<ActivityRecord> stops = null;
        ArrayList<ActivityRecord> finishes = null;
        ArrayList<UserState> startingUsers = null;
        int NS = 0;
        int NF = 0;
        boolean booting = false;
        boolean activityRemoved = false;

        ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r != null) {
            if (DEBUG_IDLE) Slog.d(TAG_IDLE, "activityIdleInternalLocked: Callers="
                    + Debug.getCallers(4));
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

            //Slog.i(TAG, "IDLE: mBooted=" + mBooted + ", fromTimeout=" + fromTimeout);
            if (isFrontStack(r.task.stack) || fromTimeout) {
                booting = checkFinishBootingLocked();
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
        if ((NF = mFinishingActivities.size()) > 0) {
            finishes = new ArrayList<>(mFinishingActivities);
            mFinishingActivities.clear();
        }

        if (mStartingUsers.size() > 0) {
            startingUsers = new ArrayList<>(mStartingUsers);
            mStartingUsers.clear();
        }

        // Stop any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NS; i++) {
            r = stops.get(i);
            final ActivityStack stack = r.task.stack;
            if (stack != null) {
                if (r.finishing) {
                    stack.finishCurrentActivityLocked(r, ActivityStack.FINISH_IMMEDIATELY, false);
                } else {
                    stack.stopActivityLocked(r);
                }
            }
        }

        // Finish any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NF; i++) {
            r = finishes.get(i);
            final ActivityStack stack = r.task.stack;
            if (stack != null) {
                activityRemoved |= stack.destroyActivityLocked(r, true, "finish-idle");
            }
        }

        if (!booting) {
            // Complete user switch
            if (startingUsers != null) {
                for (int i = 0; i < startingUsers.size(); i++) {
                    mService.finishUserSwitch(startingUsers.get(i));
                }
            }
            // Complete starting up of background users
            if (mStartingBackgroundUsers.size() > 0) {
                startingUsers = new ArrayList<UserState>(mStartingBackgroundUsers);
                mStartingBackgroundUsers.clear();
                for (int i = 0; i < startingUsers.size(); i++) {
                    mService.finishUserBoot(startingUsers.get(i));
                }
            }
        }

        mService.trimApplications();
        //dump();
        //mWindowManager.dump();

        if (activityRemoved) {
            resumeTopActivitiesLocked();
        }

        return r;
    }

    boolean handleAppDiedLocked(ProcessRecord app) {
        boolean hasVisibleActivities = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                hasVisibleActivities |= stacks.get(stackNdx).handleAppDiedLocked(app);
            }
        }
        return hasVisibleActivities;
    }

    void closeSystemDialogsLocked() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stacks.get(stackNdx).closeSystemDialogsLocked();
            }
        }
    }

    void removeUserLocked(int userId) {
        mUserStackInFront.delete(userId);
    }

    /**
     * @return true if some activity was finished (or would have finished if doit were true).
     */
    boolean finishDisabledPackageActivitiesLocked(String packageName, Set<String> filterByClasses,
            boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int numStacks = stacks.size();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (stack.finishDisabledPackageActivitiesLocked(
                        packageName, filterByClasses, doit, evenPersistent, userId)) {
                    didSomething = true;
                }
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (isFrontStack(stack)) {
                    if (stack.mResumedActivity != null) {
                        fgApp = stack.mResumedActivity.app;
                    } else if (stack.mPausingActivity != null) {
                        fgApp = stack.mPausingActivity.app;
                    }
                    break;
                }
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
            targetStack = mFocusedStack;
        }
        // Do targetStack first.
        boolean result = false;
        if (isFrontStack(targetStack)) {
            result = targetStack.resumeTopActivityLocked(target, targetOptions);
        }

        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (stack == targetStack) {
                    // Already started above.
                    continue;
                }
                if (isFrontStack(stack)) {
                    stack.resumeTopActivityLocked(null);
                }
            }
        }
        return result;
    }

    void finishTopRunningActivityLocked(ProcessRecord app, String reason) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int numStacks = stacks.size();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.finishTopRunningActivityLocked(app, reason);
            }
        }
    }

    void finishVoiceTask(IVoiceInteractionSession session) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int numStacks = stacks.size();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.finishVoiceTask(session);
            }
        }
    }

    void findTaskToMoveToFrontLocked(TaskRecord task, int flags, Bundle options, String reason) {
        if ((flags & ActivityManager.MOVE_TASK_NO_USER_ACTION) == 0) {
            mUserLeaving = true;
        }
        if ((flags & ActivityManager.MOVE_TASK_WITH_HOME) != 0) {
            // Caller wants the home activity moved with it.  To accomplish this,
            // we'll just indicate that this task returns to the home task.
            task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
        }
        if (task.stack == null) {
            Slog.e(TAG, "findTaskToMoveToFrontLocked: can't move task="
                    + task + " to front. Stack is null");
            return;
        }
        task.stack.moveTaskToFrontLocked(task, false /* noAnimation */, options,
                task.getTopActivity() == null ? null : task.getTopActivity().appTimeTracker,
                reason);
        if (DEBUG_STACK) Slog.d(TAG_STACK,
                "findTaskToMoveToFront: moved to front of stack=" + task.stack);
    }

    ActivityStack getStack(int stackId) {
        ActivityContainer activityContainer = mActivityContainers.get(stackId);
        if (activityContainer != null) {
            return activityContainer.mStack;
        }
        return null;
    }

    ArrayList<ActivityStack> getStacks() {
        ArrayList<ActivityStack> allStacks = new ArrayList<ActivityStack>();
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            allStacks.addAll(mActivityDisplays.valueAt(displayNdx).mStacks);
        }
        return allStacks;
    }

    IBinder getHomeActivityToken() {
        ActivityRecord homeActivity = getHomeActivity();
        if (homeActivity != null) {
            return homeActivity.appToken;
        }
        return null;
    }

    ActivityRecord getHomeActivity() {
        return getHomeActivityForUser(mCurrentUser);
    }

    ActivityRecord getHomeActivityForUser(int userId) {
        final ArrayList<TaskRecord> tasks = mHomeStack.getAllTasks();
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = tasks.get(taskNdx);
            if (task.isHomeTask()) {
                final ArrayList<ActivityRecord> activities = task.mActivities;
                for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                    final ActivityRecord r = activities.get(activityNdx);
                    if (r.isHomeActivity()
                            && ((userId == UserHandle.USER_ALL) || (r.userId == userId))) {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    ActivityContainer createVirtualActivityContainer(ActivityRecord parentActivity,
            IActivityContainerCallback callback) {
        ActivityContainer activityContainer =
                new VirtualActivityContainer(parentActivity, callback);
        mActivityContainers.put(activityContainer.mStackId, activityContainer);
        if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS,
                "createActivityContainer: " + activityContainer);
        parentActivity.mChildContainers.add(activityContainer);
        return activityContainer;
    }

    void removeChildActivityContainers(ActivityRecord parentActivity) {
        final ArrayList<ActivityContainer> childStacks = parentActivity.mChildContainers;
        for (int containerNdx = childStacks.size() - 1; containerNdx >= 0; --containerNdx) {
            ActivityContainer container = childStacks.remove(containerNdx);
            if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS, "removeChildActivityContainers: removing "
                    + container);
            container.release();
        }
    }

    void deleteActivityContainer(IActivityContainer container) {
        ActivityContainer activityContainer = (ActivityContainer)container;
        if (activityContainer != null) {
            if (DEBUG_CONTAINERS) Slog.d(TAG_CONTAINERS,
                    "deleteActivityContainer: callers=" + Debug.getCallers(4));
            final int stackId = activityContainer.mStackId;
            mActivityContainers.remove(stackId);
            mWindowManager.removeStack(stackId);
        }
    }

    void resizeStackLocked(int stackId, Rect bounds) {
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            Slog.w(TAG, "resizeStack: stackId " + stackId + " not found.");
            return;
        }

        final ActivityRecord r = stack.topRunningActivityLocked(null);
        if (r != null && !r.task.mResizeable) {
            Slog.w(TAG, "resizeStack: top task " + r.task + " not resizeable.");
            return;
        }

        final Configuration overrideConfig = mWindowManager.resizeStack(stackId, bounds);
        if (stack.updateOverrideConfiguration(overrideConfig)) {
            if (r != null) {
                final boolean updated = stack.ensureActivityConfigurationLocked(r, 0);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                ensureActivitiesVisibleLocked(r, 0);
                if (!updated) {
                    resumeTopActivitiesLocked(stack, null, null);
                }
            }
        }
    }

    /** Makes sure the input task is in a stack with the specified bounds by either resizing the
     * current task stack if it only has one entry, moving the task to a stack that matches the
     * bounds, or creating a new stack with the required bounds. Also, makes the task resizeable.*/
    void resizeTaskLocked(TaskRecord task, Rect bounds) {
        task.mResizeable = true;
        final ActivityStack currentStack = task.stack;
        if (currentStack.isHomeStack()) {
            // Can't move task off the home stack. Sorry!
            return;
        }

        final int matchingStackId = mWindowManager.getStackIdWithBounds(bounds);
        if (matchingStackId != -1) {
            // There is already a stack with the right bounds!
            if (currentStack != null && currentStack.mStackId == matchingStackId) {
                // Nothing to do here. Already in the right stack...
                return;
            }
            // Move task to stack with matching bounds.
            moveTaskToStackLocked(task.taskId, matchingStackId, true);
            return;
        }

        if (currentStack != null && currentStack.numTasks() == 1) {
            // Just resize the current stack since this is the task in it.
            resizeStackLocked(currentStack.mStackId, bounds);
            return;
        }

        // Create new stack and move the task to it.
        final int displayId = (currentStack != null && currentStack.mDisplayId != -1)
                ? currentStack.mDisplayId : Display.DEFAULT_DISPLAY;
        ActivityStack newStack = createStackOnDisplay(getNextStackId(), displayId);

        if (newStack == null) {
            Slog.e(TAG, "resizeTaskLocked: Can't create stack for task=" + task);
            return;
        }
        moveTaskToStackLocked(task.taskId, newStack.mStackId, true);
        resizeStackLocked(newStack.mStackId, bounds);
    }

    ActivityStack createStackOnDisplay(int stackId, int displayId) {
        ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
        if (activityDisplay == null) {
            return null;
        }

        ActivityContainer activityContainer = new ActivityContainer(stackId);
        mActivityContainers.put(stackId, activityContainer);
        activityContainer.attachToDisplayLocked(activityDisplay);
        return activityContainer.mStack;
    }

    int getNextStackId() {
        while (true) {
            if (++mLastStackId <= HOME_STACK_ID) {
                mLastStackId = HOME_STACK_ID + 1;
            }
            if (getStack(mLastStackId) == null) {
                break;
            }
        }
        return mLastStackId;
    }

    private boolean restoreRecentTaskLocked(TaskRecord task) {
        ActivityStack stack = null;
        // Determine stack to restore task to.
        if (mLeanbackOnlyDevice) {
            // There is only one stack for lean back devices.
            stack = mHomeStack;
        } else {
            // Look for the top stack on the home display that isn't the home stack.
            final ArrayList<ActivityStack> homeDisplayStacks = mHomeStack.mStacks;
            for (int stackNdx = homeDisplayStacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack tmpStack = homeDisplayStacks.get(stackNdx);
                if (!tmpStack.isHomeStack() && tmpStack.mFullscreen) {
                    stack = tmpStack;
                    break;
                }
            }
        }

        if (stack == null) {
            // We couldn't find a stack to restore the task to. Possible if are restoring recents
            // before an application stack is created...Go ahead and create one on the default
            // display.
            stack = createStackOnDisplay(getNextStackId(), Display.DEFAULT_DISPLAY);
            // Restore home stack to top.
            moveHomeStack(true, "restoreRecentTask");
            if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                    "Created stack=" + stack + " for recents restoration.");
        }

        if (stack == null) {
            // What does this mean??? Not sure how we would get here...
            if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                    "Unable to find/create stack to restore recent task=" + task);
            return false;
        }

        stack.addTask(task, false, false);
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                "Added restored task=" + task + " to stack=" + stack);
        final ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
            final ActivityRecord r = activities.get(activityNdx);
            mWindowManager.addAppToken(0, r.appToken, task.taskId, stack.mStackId,
                    r.info.screenOrientation, r.fullscreen,
                    (r.info.flags & ActivityInfo.FLAG_SHOW_FOR_ALL_USERS) != 0,
                    r.userId, r.info.configChanges, task.voiceSession != null,
                    r.mLaunchTaskBehind);
        }
        return true;
    }

    void moveTaskToStackLocked(int taskId, int stackId, boolean toTop) {
        final TaskRecord task = anyTaskForIdLocked(taskId);
        if (task == null) {
            Slog.w(TAG, "moveTaskToStack: no task for id=" + taskId);
            return;
        }
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            Slog.w(TAG, "moveTaskToStack: no stack for id=" + stackId);
            return;
        }
        mWindowManager.moveTaskToStack(taskId, stackId, toTop);
        if (task.stack != null) {
            task.stack.removeTask(task, "moveTaskToStack", false /* notMoving */);
        }
        stack.addTask(task, toTop, true);
        // The task might have already been running and its visibility needs to be synchronized with
        // the visibility of the stack / windows.
        stack.ensureActivitiesVisibleLocked(null, 0);
        resumeTopActivitiesLocked();
    }

    ActivityRecord findTaskLocked(ActivityRecord r) {
        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Looking for task of " + r);
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!r.isApplicationActivity() && !stack.isHomeStack()) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping stack: (home activity) " + stack);
                    continue;
                }
                if (!stack.mActivityContainer.isEligibleForNewTasks()) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS,
                            "Skipping stack: (new task not allowed) " + stack);
                    continue;
                }
                final ActivityRecord ar = stack.findTaskLocked(r);
                if (ar != null) {
                    return ar;
                }
            }
        }
        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "No task found");
        return null;
    }

    ActivityRecord findActivityLocked(Intent intent, ActivityInfo info) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityRecord ar = stacks.get(stackNdx).findActivityLocked(intent, info);
                if (ar != null) {
                    return ar;
                }
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
        goingToSleepLocked();

        boolean timedout = false;
        final long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            boolean cantShutdown = false;
            for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
                final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
                for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                    cantShutdown |= stacks.get(stackNdx).checkReadyForSleepLocked();
                }
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.awakeFromSleepingLocked();
                if (isFrontStack(stack)) {
                    resumeTopActivitiesLocked();
                }
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
            for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
                final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
                for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                    dontSleep |= stacks.get(stackNdx).checkReadyForSleepLocked();
                }
            }

            if (mStoppingActivities.size() > 0) {
                // Still need to tell some activities to stop; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to stop "
                        + mStoppingActivities.size() + " activities");
                scheduleIdleLocked();
                dontSleep = true;
            }

            if (mGoingToSleepActivities.size() > 0) {
                // Still need to tell some activities to sleep; can't sleep yet.
                if (DEBUG_PAUSE) Slog.v(TAG_PAUSE, "Sleep still need to sleep "
                        + mGoingToSleepActivities.size() + " activities");
                dontSleep = true;
            }

            if (dontSleep) {
                return;
            }
        }

        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stacks.get(stackNdx).goToSleep();
            }
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
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            int stackNdx = stacks.size() - 1;
            while (stackNdx >= 0) {
                stacks.get(stackNdx).handleAppCrashLocked(app);
                stackNdx--;
            }
        }
    }

    boolean requestVisibleBehindLocked(ActivityRecord r, boolean visible) {
        final ActivityStack stack = r.task.stack;
        if (stack == null) {
            if (DEBUG_VISIBLE_BEHIND) Slog.d(TAG_VISIBLE_BEHIND,
                    "requestVisibleBehind: r=" + r + " visible=" + visible + " stack is null");
            return false;
        }
        final boolean isVisible = stack.hasVisibleBehindActivity();
        if (DEBUG_VISIBLE_BEHIND) Slog.d(TAG_VISIBLE_BEHIND,
                "requestVisibleBehind r=" + r + " visible=" + visible + " isVisible=" + isVisible);

        final ActivityRecord top = topRunningActivityLocked();
        if (top == null || top == r || (visible == isVisible)) {
            if (DEBUG_VISIBLE_BEHIND) Slog.d(TAG_VISIBLE_BEHIND, "requestVisibleBehind: quick return");
            stack.setVisibleBehindActivity(visible ? r : null);
            return true;
        }

        // A non-top activity is reporting a visibility change.
        if (visible && top.fullscreen) {
            // Let the caller know that it can't be seen.
            if (DEBUG_VISIBLE_BEHIND) Slog.d(TAG_VISIBLE_BEHIND,
                    "requestVisibleBehind: returning top.fullscreen=" + top.fullscreen
                    + " top.state=" + top.state + " top.app=" + top.app + " top.app.thread="
                    + top.app.thread);
            return false;
        } else if (!visible && stack.getVisibleBehindActivity() != r) {
            // Only the activity set as currently visible behind should actively reset its
            // visible behind state.
            if (DEBUG_VISIBLE_BEHIND) Slog.d(TAG_VISIBLE_BEHIND,
                    "requestVisibleBehind: returning visible=" + visible
                    + " stack.getVisibleBehindActivity()=" + stack.getVisibleBehindActivity()
                    + " r=" + r);
            return false;
        }

        stack.setVisibleBehindActivity(visible ? r : null);
        if (!visible) {
            // Make the activity immediately above r opaque.
            final ActivityRecord next = stack.findNextTranslucentActivity(r);
            if (next != null) {
                mService.convertFromTranslucent(next.appToken);
            }
        }
        if (top.app != null && top.app.thread != null) {
            // Notify the top app of the change.
            try {
                top.app.thread.scheduleBackgroundVisibleBehindChanged(top.appToken, visible);
            } catch (RemoteException e) {
            }
        }
        return true;
    }

    // Called when WindowManager has finished animating the launchingBehind activity to the back.
    void handleLaunchTaskBehindCompleteLocked(ActivityRecord r) {
        r.mLaunchTaskBehind = false;
        final TaskRecord task = r.task;
        task.setLastThumbnail(task.stack.screenshotActivities(r));
        mRecentTasks.addLocked(task);
        mService.notifyTaskStackChangedLocked();
        mWindowManager.setAppVisibility(r.appToken, false);
    }

    void scheduleLaunchTaskBehindComplete(IBinder token) {
        mHandler.obtainMessage(LAUNCH_TASK_BEHIND_COMPLETE, token).sendToTarget();
    }

    void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges) {
        // First the front stacks. In case any are not fullscreen and are in front of home.
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int topStackNdx = stacks.size() - 1;
            for (int stackNdx = topStackNdx; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.ensureActivitiesVisibleLocked(starting, configChanges);
            }
        }
    }

    void clearOtherAppTimeTrackers(AppTimeTracker except) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int topStackNdx = stacks.size() - 1;
            for (int stackNdx = topStackNdx; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.clearOtherAppTimeTrackers(except);
            }
        }
    }

    void scheduleDestroyAllActivities(ProcessRecord app, String reason) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int numStacks = stacks.size();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.scheduleDestroyActivities(app, reason);
            }
        }
    }

    void releaseSomeActivitiesLocked(ProcessRecord app, String reason) {
        // Examine all activities currently running in the process.
        TaskRecord firstTask = null;
        // Tasks is non-null only if two or more tasks are found.
        ArraySet<TaskRecord> tasks = null;
        if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Trying to release some activities in " + app);
        for (int i = 0; i < app.activities.size(); i++) {
            ActivityRecord r = app.activities.get(i);
            // First, if we find an activity that is in the process of being destroyed,
            // then we just aren't going to do anything for now; we want things to settle
            // down before we try to prune more activities.
            if (r.finishing || r.state == DESTROYING || r.state == DESTROYED) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Abort release; already destroying: " + r);
                return;
            }
            // Don't consider any activies that are currently not in a state where they
            // can be destroyed.
            if (r.visible || !r.stopped || !r.haveState || r.state == RESUMED || r.state == PAUSING
                    || r.state == PAUSED || r.state == STOPPING) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Not releasing in-use activity: " + r);
                continue;
            }
            if (r.task != null) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Collecting release task " + r.task
                        + " from " + r);
                if (firstTask == null) {
                    firstTask = r.task;
                } else if (firstTask != r.task) {
                    if (tasks == null) {
                        tasks = new ArraySet<>();
                        tasks.add(firstTask);
                    }
                    tasks.add(r.task);
                }
            }
        }
        if (tasks == null) {
            if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Didn't find two or more tasks to release");
            return;
        }
        // If we have activities in multiple tasks that are in a position to be destroyed,
        // let's iterate through the tasks and release the oldest one.
        final int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            // Step through all stacks starting from behind, to hit the oldest things first.
            for (int stackNdx = 0; stackNdx < stacks.size(); stackNdx++) {
                final ActivityStack stack = stacks.get(stackNdx);
                // Try to release activities in this stack; if we manage to, we are done.
                if (stack.releaseSomeActivitiesLocked(app, tasks, reason) > 0) {
                    return;
                }
            }
        }
    }

    boolean switchUserLocked(int userId, UserState uss) {
        mUserStackInFront.put(mCurrentUser, mFocusedStack.getStackId());
        final int restoreStackId = mUserStackInFront.get(userId, HOME_STACK_ID);
        mCurrentUser = userId;

        mStartingUsers.add(uss);
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.switchUserLocked(userId);
                TaskRecord task = stack.topTask();
                if (task != null) {
                    mWindowManager.moveTaskToTop(task.taskId);
                }
            }
        }

        ActivityStack stack = getStack(restoreStackId);
        if (stack == null) {
            stack = mHomeStack;
        }
        final boolean homeInFront = stack.isHomeStack();
        if (stack.isOnHomeDisplay()) {
            moveHomeStack(homeInFront, "switchUserOnHomeDisplay");
            TaskRecord task = stack.topTask();
            if (task != null) {
                mWindowManager.moveTaskToTop(task.taskId);
            }
        } else {
            // Stack was moved to another display while user was swapped out.
            resumeHomeStackTask(HOME_ACTIVITY_TYPE, null, "switchUserOnOtherDisplay");
        }
        return homeInFront;
    }

    /**
     * Add background users to send boot completed events to.
     * @param userId The user being started in the background
     * @param uss The state object for the user.
     */
    public void startBackgroundUserLocked(int userId, UserState uss) {
        mStartingBackgroundUsers.add(uss);
    }

    /** Checks whether the userid is a profile of the current user. */
    boolean isCurrentProfileLocked(int userId) {
        if (userId == mCurrentUser) return true;
        for (int i = 0; i < mService.mCurrentProfileIds.length; i++) {
            if (mService.mCurrentProfileIds[i] == userId) return true;
        }
        return false;
    }

    final ArrayList<ActivityRecord> processStoppingActivitiesLocked(boolean remove) {
        ArrayList<ActivityRecord> stops = null;

        final boolean nowVisible = allResumedActivitiesVisible();
        for (int activityNdx = mStoppingActivities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord s = mStoppingActivities.get(activityNdx);
            final boolean waitingVisible = mWaitingVisibleActivities.contains(s);
            if (DEBUG_ALL) Slog.v(TAG, "Stopping " + s + ": nowVisible=" + nowVisible
                    + " waitingVisible=" + waitingVisible + " finishing=" + s.finishing);
            if (waitingVisible && nowVisible) {
                mWaitingVisibleActivities.remove(s);
                if (s.finishing) {
                    // If this activity is finishing, it is sitting on top of
                    // everyone else but we now know it is no longer needed...
                    // so get rid of it.  Otherwise, we need to go through the
                    // normal flow and hide it once we determine that it is
                    // hidden by the activities in front of it.
                    if (DEBUG_ALL) Slog.v(TAG, "Before stopping, can hide: " + s);
                    mWindowManager.setAppVisibility(s.appToken, false);
                }
            }
            if ((!waitingVisible || mService.isSleepingOrShuttingDown()) && remove) {
                if (DEBUG_ALL) Slog.v(TAG, "Ready to stop: " + s);
                if (stops == null) {
                    stops = new ArrayList<>();
                }
                stops.add(s);
                mStoppingActivities.remove(activityNdx);
            }
        }

        return stops;
    }

    void validateTopActivitiesLocked() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                final ActivityRecord r = stack.topRunningActivityLocked(null);
                final ActivityState state = r == null ? DESTROYED : r.state;
                if (isFrontStack(stack)) {
                    if (r == null) Slog.e(TAG,
                            "validateTop...: null top activity, stack=" + stack);
                    else {
                        final ActivityRecord pausing = stack.mPausingActivity;
                        if (pausing != null && pausing == r) Slog.e(TAG,
                                "validateTop...: top stack has pausing activity r=" + r
                                + " state=" + state);
                        if (state != INITIALIZING && state != RESUMED) Slog.e(TAG,
                                "validateTop...: activity in front not resumed r=" + r
                                + " state=" + state);
                    }
                } else {
                    final ActivityRecord resumed = stack.mResumedActivity;
                    if (resumed != null && resumed == r) Slog.e(TAG,
                            "validateTop...: back stack has resumed activity r=" + r
                            + " state=" + state);
                    if (r != null && (state == INITIALIZING || state == RESUMED)) Slog.e(TAG,
                            "validateTop...: activity in back resumed r=" + r + " state=" + state);
                }
            }
        }
    }

    private String lockTaskModeToString() {
        switch (mLockTaskModeState) {
            case LOCK_TASK_MODE_LOCKED:
                return "LOCKED";
            case LOCK_TASK_MODE_PINNED:
                return "PINNED";
            case LOCK_TASK_MODE_NONE:
                return "NONE";
            default: return "unknown=" + mLockTaskModeState;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mFocusedStack=" + mFocusedStack);
                pw.print(" mLastFocusedStack="); pw.println(mLastFocusedStack);
        pw.print(prefix); pw.println("mSleepTimeout=" + mSleepTimeout);
        pw.print(prefix); pw.println("mCurTaskId=" + mCurTaskId);
        pw.print(prefix); pw.println("mUserStackInFront=" + mUserStackInFront);
        pw.print(prefix); pw.println("mActivityContainers=" + mActivityContainers);
        pw.print(prefix); pw.print("mLockTaskModeState=" + lockTaskModeToString());
                final SparseArray<String[]> packages = mService.mLockTaskPackages;
                if (packages.size() > 0) {
                    pw.println(" mLockTaskPackages (userId:packages)=");
                    for (int i = 0; i < packages.size(); ++i) {
                        pw.print(prefix); pw.print(prefix); pw.print(packages.keyAt(i));
                        pw.print(":"); pw.println(Arrays.toString(packages.valueAt(i)));
                    }
                }
                pw.println(" mLockTaskModeTasks" + mLockTaskModeTasks);
    }

    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        return mFocusedStack.getDumpActivitiesLocked(name);
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
        for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); ++displayNdx) {
            ActivityDisplay activityDisplay = mActivityDisplays.valueAt(displayNdx);
            pw.print("Display #"); pw.print(activityDisplay.mDisplayId);
                    pw.println(" (activities from top to bottom):");
            ArrayList<ActivityStack> stacks = activityDisplay.mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                StringBuilder stackHeader = new StringBuilder(128);
                stackHeader.append("  Stack #");
                stackHeader.append(stack.mStackId);
                stackHeader.append(":");
                printed |= stack.dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage,
                        needSep, stackHeader.toString());
                printed |= dumpHistoryList(fd, pw, stack.mLRUActivities, "    ", "Run", false,
                        !dumpAll, false, dumpPackage, true,
                        "    Running activities (most recent first):", null);

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
        if (DEBUG_IDLE) Slog.d(TAG_IDLE,
                "scheduleIdleTimeoutLocked: Callers=" + Debug.getCallers(4));
        Message msg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG, next);
        mHandler.sendMessageDelayed(msg, IDLE_TIMEOUT);
    }

    final void scheduleIdleLocked() {
        mHandler.sendEmptyMessage(IDLE_NOW_MSG);
    }

    void removeTimeoutsForActivityLocked(ActivityRecord r) {
        if (DEBUG_IDLE) Slog.d(TAG_IDLE, "removeTimeoutsForActivity: Callers="
                + Debug.getCallers(4));
        mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
    }

    final void scheduleResumeTopActivities() {
        if (!mHandler.hasMessages(RESUME_TOP_ACTIVITY_MSG)) {
            mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
        }
    }

    void removeSleepTimeouts() {
        mSleepTimeout = false;
        mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
    }

    final void scheduleSleepTimeout() {
        removeSleepTimeouts();
        mHandler.sendEmptyMessageDelayed(SLEEP_TIMEOUT_MSG, SLEEP_TIMEOUT);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display added displayId=" + displayId);
        mHandler.sendMessage(mHandler.obtainMessage(HANDLE_DISPLAY_ADDED, displayId, 0));
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display removed displayId=" + displayId);
        mHandler.sendMessage(mHandler.obtainMessage(HANDLE_DISPLAY_REMOVED, displayId, 0));
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (DEBUG_STACK) Slog.v(TAG, "Display changed displayId=" + displayId);
        mHandler.sendMessage(mHandler.obtainMessage(HANDLE_DISPLAY_CHANGED, displayId, 0));
    }

    private void handleDisplayAdded(int displayId) {
        boolean newDisplay;
        synchronized (mService) {
            newDisplay = mActivityDisplays.get(displayId) == null;
            if (newDisplay) {
                ActivityDisplay activityDisplay = new ActivityDisplay(displayId);
                if (activityDisplay.mDisplay == null) {
                    Slog.w(TAG, "Display " + displayId + " gone before initialization complete");
                    return;
                }
                mActivityDisplays.put(displayId, activityDisplay);
            }
        }
        if (newDisplay) {
            mWindowManager.onDisplayAdded(displayId);
        }
    }

    private void handleDisplayRemoved(int displayId) {
        synchronized (mService) {
            ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
            if (activityDisplay != null) {
                ArrayList<ActivityStack> stacks = activityDisplay.mStacks;
                for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                    stacks.get(stackNdx).mActivityContainer.detachLocked();
                }
                mActivityDisplays.remove(displayId);
            }
        }
        mWindowManager.onDisplayRemoved(displayId);
    }

    private void handleDisplayChanged(int displayId) {
        synchronized (mService) {
            ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
            if (activityDisplay != null) {
                // TODO: Update the bounds.
            }
        }
        mWindowManager.onDisplayChanged(displayId);
    }

    private StackInfo getStackInfoLocked(ActivityStack stack) {
        StackInfo info = new StackInfo();
        mWindowManager.getStackBounds(stack.mStackId, info.bounds);
        info.displayId = Display.DEFAULT_DISPLAY;
        info.stackId = stack.mStackId;

        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        final int numTasks = tasks.size();
        int[] taskIds = new int[numTasks];
        String[] taskNames = new String[numTasks];
        for (int i = 0; i < numTasks; ++i) {
            final TaskRecord task = tasks.get(i);
            taskIds[i] = task.taskId;
            taskNames[i] = task.origActivity != null ? task.origActivity.flattenToString()
                    : task.realActivity != null ? task.realActivity.flattenToString()
                    : task.getTopActivity() != null ? task.getTopActivity().packageName
                    : "unknown";
        }
        info.taskIds = taskIds;
        info.taskNames = taskNames;
        return info;
    }

    StackInfo getStackInfoLocked(int stackId) {
        ActivityStack stack = getStack(stackId);
        if (stack != null) {
            return getStackInfoLocked(stack);
        }
        return null;
    }

    ArrayList<StackInfo> getAllStackInfosLocked() {
        ArrayList<StackInfo> list = new ArrayList<StackInfo>();
        for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); ++displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int ndx = stacks.size() - 1; ndx >= 0; --ndx) {
                list.add(getStackInfoLocked(stacks.get(ndx)));
            }
        }
        return list;
    }

    TaskRecord getLockedTaskLocked() {
        final int top = mLockTaskModeTasks.size() - 1;
        if (top >= 0) {
            return mLockTaskModeTasks.get(top);
        }
        return null;
    }

    boolean isLockedTask(TaskRecord task) {
        return mLockTaskModeTasks.contains(task);
    }

    boolean isLastLockedTask(TaskRecord task) {
        return mLockTaskModeTasks.size() == 1 && mLockTaskModeTasks.contains(task);
    }

    void removeLockedTaskLocked(final TaskRecord task) {
        if (!mLockTaskModeTasks.remove(task)) {
            return;
        }
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "removeLockedTaskLocked: removed " + task);
        if (mLockTaskModeTasks.isEmpty()) {
            // Last one.
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "removeLockedTask: task=" + task +
                    " last task, reverting locktask mode. Callers=" + Debug.getCallers(3));
            final Message lockTaskMsg = Message.obtain();
            lockTaskMsg.arg1 = task.userId;
            lockTaskMsg.what = LOCK_TASK_END_MSG;
            mHandler.sendMessage(lockTaskMsg);
        }
    }

    void showLockTaskToast() {
        mLockTaskNotify.showToast(mLockTaskModeState);
    }

    void showLockTaskEscapeMessageLocked(TaskRecord task) {
        if (mLockTaskModeTasks.contains(task)) {
            mHandler.sendEmptyMessage(SHOW_LOCK_TASK_ESCAPE_MESSAGE_MSG);
        }
    }

    void setLockTaskModeLocked(TaskRecord task, int lockTaskModeState, String reason,
            boolean andResume) {
        if (task == null) {
            // Take out of lock task mode if necessary
            final TaskRecord lockedTask = getLockedTaskLocked();
            if (lockedTask != null) {
                removeLockedTaskLocked(lockedTask);
                if (!mLockTaskModeTasks.isEmpty()) {
                    // There are locked tasks remaining, can only finish this task, not unlock it.
                    if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                            "setLockTaskModeLocked: Tasks remaining, can't unlock");
                    lockedTask.performClearTaskLocked();
                    resumeTopActivitiesLocked();
                    return;
                }
            }
            if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                    "setLockTaskModeLocked: No tasks to unlock. Callers=" + Debug.getCallers(4));
            return;
        }

        // Should have already been checked, but do it again.
        if (task.mLockTaskAuth == LOCK_TASK_AUTH_DONT_LOCK) {
            if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                    "setLockTaskModeLocked: Can't lock due to auth");
            return;
        }
        if (isLockTaskModeViolation(task)) {
            Slog.e(TAG_LOCKTASK, "setLockTaskMode: Attempt to start an unauthorized lock task.");
            return;
        }

        if (mLockTaskModeTasks.isEmpty()) {
            // First locktask.
            final Message lockTaskMsg = Message.obtain();
            lockTaskMsg.obj = task.intent.getComponent().getPackageName();
            lockTaskMsg.arg1 = task.userId;
            lockTaskMsg.what = LOCK_TASK_START_MSG;
            lockTaskMsg.arg2 = lockTaskModeState;
            mHandler.sendMessage(lockTaskMsg);
        }
        // Add it or move it to the top.
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "setLockTaskModeLocked: Locking to " + task +
                " Callers=" + Debug.getCallers(4));
        mLockTaskModeTasks.remove(task);
        mLockTaskModeTasks.add(task);

        if (task.mLockTaskUid == -1) {
            task.mLockTaskUid = task.mCallingUid;
        }

        if (andResume) {
            findTaskToMoveToFrontLocked(task, 0, null, reason);
            resumeTopActivitiesLocked();
        }
    }

    boolean isLockTaskModeViolation(TaskRecord task) {
        return isLockTaskModeViolation(task, false);
    }

    boolean isLockTaskModeViolation(TaskRecord task, boolean isNewClearTask) {
        if (getLockedTaskLocked() == task && !isNewClearTask) {
            return false;
        }
        final int lockTaskAuth = task.mLockTaskAuth;
        switch (lockTaskAuth) {
            case LOCK_TASK_AUTH_DONT_LOCK:
                return !mLockTaskModeTasks.isEmpty();
            case LOCK_TASK_AUTH_LAUNCHABLE_PRIV:
            case LOCK_TASK_AUTH_LAUNCHABLE:
            case LOCK_TASK_AUTH_WHITELISTED:
                return false;
            case LOCK_TASK_AUTH_PINNABLE:
                // Pinnable tasks can't be launched on top of locktask tasks.
                return !mLockTaskModeTasks.isEmpty();
            default:
                Slog.w(TAG, "isLockTaskModeViolation: invalid lockTaskAuth value=" + lockTaskAuth);
                return true;
        }
    }

    void onLockTaskPackagesUpdatedLocked() {
        boolean didSomething = false;
        for (int taskNdx = mLockTaskModeTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord lockedTask = mLockTaskModeTasks.get(taskNdx);
            final boolean wasWhitelisted =
                    (lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE) ||
                    (lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_WHITELISTED);
            lockedTask.setLockTaskAuth();
            final boolean isWhitelisted =
                    (lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE) ||
                    (lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_WHITELISTED);
            if (wasWhitelisted && !isWhitelisted) {
                // Lost whitelisting authorization. End it now.
                if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "onLockTaskPackagesUpdated: removing " +
                        lockedTask + " mLockTaskAuth=" + lockedTask.lockTaskAuthToString());
                removeLockedTaskLocked(lockedTask);
                lockedTask.performClearTaskLocked();
                didSomething = true;
            }
        }
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.onLockTaskPackagesUpdatedLocked();
            }
        }
        final ActivityRecord r = topRunningActivityLocked();
        final TaskRecord task = r != null ? r.task : null;
        if (mLockTaskModeTasks.isEmpty() && task != null
                && task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE) {
            // This task must have just been authorized.
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK,
                    "onLockTaskPackagesUpdated: starting new locktask task=" + task);
            setLockTaskModeLocked(task, ActivityManager.LOCK_TASK_MODE_LOCKED, "package updated",
                    false);
            didSomething = true;
        }
        if (didSomething) {
            resumeTopActivitiesLocked();
        }
    }

    int getLockTaskModeState() {
        return mLockTaskModeState;
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
                    if (DEBUG_IDLE) Slog.d(TAG_IDLE,
                            "handleMessage: IDLE_TIMEOUT_MSG: r=" + msg.obj);
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
                    if (DEBUG_IDLE) Slog.d(TAG_IDLE, "handleMessage: IDLE_NOW_MSG: r=" + msg.obj);
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
                case HANDLE_DISPLAY_ADDED: {
                    handleDisplayAdded(msg.arg1);
                } break;
                case HANDLE_DISPLAY_CHANGED: {
                    handleDisplayChanged(msg.arg1);
                } break;
                case HANDLE_DISPLAY_REMOVED: {
                    handleDisplayRemoved(msg.arg1);
                } break;
                case CONTAINER_CALLBACK_VISIBILITY: {
                    final ActivityContainer container = (ActivityContainer) msg.obj;
                    final IActivityContainerCallback callback = container.mCallback;
                    if (callback != null) {
                        try {
                            callback.setVisible(container.asBinder(), msg.arg1 == 1);
                        } catch (RemoteException e) {
                        }
                    }
                } break;
                case LOCK_TASK_START_MSG: {
                    // When lock task starts, we disable the status bars.
                    try {
                        if (mLockTaskNotify == null) {
                            mLockTaskNotify = new LockTaskNotify(mService.mContext);
                        }
                        mLockTaskNotify.show(true);
                        mLockTaskModeState = msg.arg2;
                        if (getStatusBarService() != null) {
                            int flags = 0;
                            if (mLockTaskModeState == LOCK_TASK_MODE_LOCKED) {
                                flags = StatusBarManager.DISABLE_MASK
                                        & (~StatusBarManager.DISABLE_BACK);
                            } else if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                                flags = StatusBarManager.DISABLE_MASK
                                        & (~StatusBarManager.DISABLE_BACK)
                                        & (~StatusBarManager.DISABLE_HOME)
                                        & (~StatusBarManager.DISABLE_RECENT);
                            }
                            getStatusBarService().disable(flags, mToken,
                                    mService.mContext.getPackageName());
                        }
                        mWindowManager.disableKeyguard(mToken, LOCK_TASK_TAG);
                        if (getDevicePolicyManager() != null) {
                            getDevicePolicyManager().notifyLockTaskModeChanged(true,
                                    (String)msg.obj, msg.arg1);
                        }
                    } catch (RemoteException ex) {
                        throw new RuntimeException(ex);
                    }
                } break;
                case LOCK_TASK_END_MSG: {
                    // When lock task ends, we enable the status bars.
                    try {
                        if (getStatusBarService() != null) {
                            getStatusBarService().disable(StatusBarManager.DISABLE_NONE, mToken,
                                    mService.mContext.getPackageName());
                        }
                        mWindowManager.reenableKeyguard(mToken);
                        if (getDevicePolicyManager() != null) {
                            getDevicePolicyManager().notifyLockTaskModeChanged(false, null,
                                    msg.arg1);
                        }
                        if (mLockTaskNotify == null) {
                            mLockTaskNotify = new LockTaskNotify(mService.mContext);
                        }
                        mLockTaskNotify.show(false);
                        try {
                            boolean shouldLockKeyguard = Settings.Secure.getInt(
                                    mService.mContext.getContentResolver(),
                                    Settings.Secure.LOCK_TO_APP_EXIT_LOCKED) != 0;
                            if (mLockTaskModeState == LOCK_TASK_MODE_PINNED && shouldLockKeyguard) {
                                mWindowManager.lockNow(null);
                                mWindowManager.dismissKeyguard();
                                new LockPatternUtils(mService.mContext)
                                        .requireCredentialEntry(UserHandle.USER_ALL);
                            }
                        } catch (SettingNotFoundException e) {
                            // No setting, don't lock.
                        }
                    } catch (RemoteException ex) {
                        throw new RuntimeException(ex);
                    } finally {
                        mLockTaskModeState = LOCK_TASK_MODE_NONE;
                    }
                } break;
                case SHOW_LOCK_TASK_ESCAPE_MESSAGE_MSG: {
                    if (mLockTaskNotify == null) {
                        mLockTaskNotify = new LockTaskNotify(mService.mContext);
                    }
                    mLockTaskNotify.showToast(LOCK_TASK_MODE_PINNED);
                } break;
                case CONTAINER_CALLBACK_TASK_LIST_EMPTY: {
                    final ActivityContainer container = (ActivityContainer) msg.obj;
                    final IActivityContainerCallback callback = container.mCallback;
                    if (callback != null) {
                        try {
                            callback.onAllActivitiesComplete(container.asBinder());
                        } catch (RemoteException e) {
                        }
                    }
                } break;
                case LAUNCH_TASK_BEHIND_COMPLETE: {
                    synchronized (mService) {
                        ActivityRecord r = ActivityRecord.forTokenLocked((IBinder) msg.obj);
                        if (r != null) {
                            handleLaunchTaskBehindCompleteLocked(r);
                        }
                    }
                } break;
            }
        }
    }

    class ActivityContainer extends android.app.IActivityContainer.Stub {
        final static int FORCE_NEW_TASK_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        final int mStackId;
        IActivityContainerCallback mCallback = null;
        final ActivityStack mStack;
        ActivityRecord mParentActivity = null;
        String mIdString;

        boolean mVisible = true;

        /** Display this ActivityStack is currently on. Null if not attached to a Display. */
        ActivityDisplay mActivityDisplay;

        final static int CONTAINER_STATE_HAS_SURFACE = 0;
        final static int CONTAINER_STATE_NO_SURFACE = 1;
        final static int CONTAINER_STATE_FINISHING = 2;
        int mContainerState = CONTAINER_STATE_HAS_SURFACE;

        ActivityContainer(int stackId) {
            synchronized (mService) {
                mStackId = stackId;
                mStack = new ActivityStack(this, mRecentTasks);
                mIdString = "ActivtyContainer{" + mStackId + "}";
                if (DEBUG_STACK) Slog.d(TAG_STACK, "Creating " + this);
            }
        }

        void attachToDisplayLocked(ActivityDisplay activityDisplay) {
            if (DEBUG_STACK) Slog.d(TAG_STACK, "attachToDisplayLocked: " + this
                    + " to display=" + activityDisplay);
            mActivityDisplay = activityDisplay;
            mStack.mDisplayId = activityDisplay.mDisplayId;
            mStack.mStacks = activityDisplay.mStacks;

            activityDisplay.attachActivities(mStack);
            mWindowManager.attachStack(mStackId, activityDisplay.mDisplayId);
        }

        @Override
        public void attachToDisplay(int displayId) {
            synchronized (mService) {
                ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
                if (activityDisplay == null) {
                    return;
                }
                attachToDisplayLocked(activityDisplay);
            }
        }

        @Override
        public int getDisplayId() {
            synchronized (mService) {
                if (mActivityDisplay != null) {
                    return mActivityDisplay.mDisplayId;
                }
            }
            return -1;
        }

        @Override
        public int getStackId() {
            synchronized (mService) {
                return mStackId;
            }
        }

        @Override
        public boolean injectEvent(InputEvent event) {
            final long origId = Binder.clearCallingIdentity();
            try {
                synchronized (mService) {
                    if (mActivityDisplay != null) {
                        return mInputManagerInternal.injectInputEvent(event,
                                mActivityDisplay.mDisplayId,
                                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    }
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        @Override
        public void release() {
            synchronized (mService) {
                if (mContainerState == CONTAINER_STATE_FINISHING) {
                    return;
                }
                mContainerState = CONTAINER_STATE_FINISHING;

                long origId = Binder.clearCallingIdentity();
                try {
                    mStack.finishAllActivitiesLocked(false);
                    removePendingActivityLaunchesLocked(mStack);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }

        protected void detachLocked() {
            if (DEBUG_STACK) Slog.d(TAG_STACK, "detachLocked: " + this + " from display="
                    + mActivityDisplay + " Callers=" + Debug.getCallers(2));
            if (mActivityDisplay != null) {
                mActivityDisplay.detachActivitiesLocked(mStack);
                mActivityDisplay = null;
                mStack.mDisplayId = -1;
                mStack.mStacks = null;
                mWindowManager.detachStack(mStackId);
            }
        }

        @Override
        public final int startActivity(Intent intent) {
            mService.enforceNotIsolatedCaller("ActivityContainer.startActivity");
            final int userId = mService.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), mCurrentUser, false,
                    ActivityManagerService.ALLOW_FULL_ONLY, "ActivityContainer", null);

            // TODO: Switch to user app stacks here.
            String mimeType = intent.getType();
            final Uri data = intent.getData();
            if (mimeType == null && data != null && "content".equals(data.getScheme())) {
                mimeType = mService.getProviderMimeType(data, userId);
            }
            checkEmbeddedAllowedInner(userId, intent, mimeType);

            intent.addFlags(FORCE_NEW_TASK_FLAGS);
            return startActivityMayWait(null, -1, null, intent, mimeType, null, null, null, null,
                    0, 0, null, null, null, null, false, userId, this, null);
        }

        @Override
        public final int startActivityIntentSender(IIntentSender intentSender)
                throws TransactionTooLargeException {
            mService.enforceNotIsolatedCaller("ActivityContainer.startActivityIntentSender");

            if (!(intentSender instanceof PendingIntentRecord)) {
                throw new IllegalArgumentException("Bad PendingIntent object");
            }

            final int userId = mService.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), mCurrentUser, false,
                    ActivityManagerService.ALLOW_FULL_ONLY, "ActivityContainer", null);

            final PendingIntentRecord pendingIntent = (PendingIntentRecord) intentSender;
            checkEmbeddedAllowedInner(userId, pendingIntent.key.requestIntent,
                    pendingIntent.key.requestResolvedType);

            return pendingIntent.sendInner(0, null, null, null, null, null, null, 0,
                    FORCE_NEW_TASK_FLAGS, FORCE_NEW_TASK_FLAGS, null, this);
        }

        private void checkEmbeddedAllowedInner(int userId, Intent intent, String resolvedType) {
            ActivityInfo aInfo = resolveActivity(intent, resolvedType, 0, null, userId);
            if (aInfo != null && (aInfo.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) == 0) {
                throw new SecurityException(
                        "Attempt to embed activity that has not set allowEmbedded=\"true\"");
            }
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void setSurface(Surface surface, int width, int height, int density) {
            mService.enforceNotIsolatedCaller("ActivityContainer.attachToSurface");
        }

        ActivityStackSupervisor getOuter() {
            return ActivityStackSupervisor.this;
        }

        boolean isAttachedLocked() {
            return mActivityDisplay != null;
        }

        void getBounds(Point outBounds) {
            synchronized (mService) {
                    if (mActivityDisplay != null) {
                    mActivityDisplay.getBounds(outBounds);
                } else {
                    outBounds.set(0, 0);
                }
            }
        }

        // TODO: Make sure every change to ActivityRecord.visible results in a call to this.
        void setVisible(boolean visible) {
            if (mVisible != visible) {
                mVisible = visible;
                if (mCallback != null) {
                    mHandler.obtainMessage(CONTAINER_CALLBACK_VISIBILITY, visible ? 1 : 0,
                            0 /* unused */, this).sendToTarget();
                }
            }
        }

        void setDrawn() {
        }

        // You can always start a new task on a regular ActivityStack.
        boolean isEligibleForNewTasks() {
            return true;
        }

        void onTaskListEmptyLocked() {
            detachLocked();
            deleteActivityContainer(this);
            mHandler.obtainMessage(CONTAINER_CALLBACK_TASK_LIST_EMPTY, this).sendToTarget();
        }

        @Override
        public String toString() {
            return mIdString + (mActivityDisplay == null ? "N" : "A");
        }
    }

    private class VirtualActivityContainer extends ActivityContainer {
        Surface mSurface;
        boolean mDrawn = false;

        VirtualActivityContainer(ActivityRecord parent, IActivityContainerCallback callback) {
            super(getNextStackId());
            mParentActivity = parent;
            mCallback = callback;
            mContainerState = CONTAINER_STATE_NO_SURFACE;
            mIdString = "VirtualActivityContainer{" + mStackId + ", parent=" + mParentActivity + "}";
        }

        @Override
        public void setSurface(Surface surface, int width, int height, int density) {
            super.setSurface(surface, width, height, density);

            synchronized (mService) {
                final long origId = Binder.clearCallingIdentity();
                try {
                    setSurfaceLocked(surface, width, height, density);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }

        private void setSurfaceLocked(Surface surface, int width, int height, int density) {
            if (mContainerState == CONTAINER_STATE_FINISHING) {
                return;
            }
            VirtualActivityDisplay virtualActivityDisplay =
                    (VirtualActivityDisplay) mActivityDisplay;
            if (virtualActivityDisplay == null) {
                virtualActivityDisplay =
                        new VirtualActivityDisplay(width, height, density);
                mActivityDisplay = virtualActivityDisplay;
                mActivityDisplays.put(virtualActivityDisplay.mDisplayId, virtualActivityDisplay);
                attachToDisplayLocked(virtualActivityDisplay);
            }

            if (mSurface != null) {
                mSurface.release();
            }

            mSurface = surface;
            if (surface != null) {
                mStack.resumeTopActivityLocked(null);
            } else {
                mContainerState = CONTAINER_STATE_NO_SURFACE;
                ((VirtualActivityDisplay) mActivityDisplay).setSurface(null);
                if (mStack.mPausingActivity == null && mStack.mResumedActivity != null) {
                    mStack.startPausingLocked(false, true, false, false);
                }
            }

            setSurfaceIfReadyLocked();

            if (DEBUG_STACK) Slog.d(TAG_STACK,
                    "setSurface: " + this + " to display=" + virtualActivityDisplay);
        }

        @Override
        boolean isAttachedLocked() {
            return mSurface != null && super.isAttachedLocked();
        }

        @Override
        void setDrawn() {
            synchronized (mService) {
                mDrawn = true;
                setSurfaceIfReadyLocked();
            }
        }

        // Never start a new task on an ActivityView if it isn't explicitly specified.
        @Override
        boolean isEligibleForNewTasks() {
            return false;
        }

        private void setSurfaceIfReadyLocked() {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "setSurfaceIfReadyLocked: mDrawn=" + mDrawn +
                    " mContainerState=" + mContainerState + " mSurface=" + mSurface);
            if (mDrawn && mSurface != null && mContainerState == CONTAINER_STATE_NO_SURFACE) {
                ((VirtualActivityDisplay) mActivityDisplay).setSurface(mSurface);
                mContainerState = CONTAINER_STATE_HAS_SURFACE;
            }
        }
    }

    /** Exactly one of these classes per Display in the system. Capable of holding zero or more
     * attached {@link ActivityStack}s */
    class ActivityDisplay {
        /** Actual Display this object tracks. */
        int mDisplayId;
        Display mDisplay;
        DisplayInfo mDisplayInfo = new DisplayInfo();

        /** All of the stacks on this display. Order matters, topmost stack is in front of all other
         * stacks, bottommost behind. Accessed directly by ActivityManager package classes */
        final ArrayList<ActivityStack> mStacks = new ArrayList<ActivityStack>();

        ActivityRecord mVisibleBehindActivity;

        ActivityDisplay() {
        }

        // After instantiation, check that mDisplay is not null before using this. The alternative
        // is for this to throw an exception if mDisplayManager.getDisplay() returns null.
        ActivityDisplay(int displayId) {
            final Display display = mDisplayManager.getDisplay(displayId);
            if (display == null) {
                return;
            }
            init(display);
        }

        void init(Display display) {
            mDisplay = display;
            mDisplayId = display.getDisplayId();
            mDisplay.getDisplayInfo(mDisplayInfo);
        }

        void attachActivities(ActivityStack stack) {
            if (DEBUG_STACK) Slog.v(TAG_STACK,
                    "attachActivities: attaching " + stack + " to displayId=" + mDisplayId);
            mStacks.add(stack);
        }

        void detachActivitiesLocked(ActivityStack stack) {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "detachActivitiesLocked: detaching " + stack
                    + " from displayId=" + mDisplayId);
            mStacks.remove(stack);
        }

        void getBounds(Point bounds) {
            mDisplay.getDisplayInfo(mDisplayInfo);
            bounds.x = mDisplayInfo.appWidth;
            bounds.y = mDisplayInfo.appHeight;
        }

        void setVisibleBehindActivity(ActivityRecord r) {
            mVisibleBehindActivity = r;
        }

        boolean hasVisibleBehindActivity() {
            return mVisibleBehindActivity != null;
        }

        @Override
        public String toString() {
            return "ActivityDisplay={" + mDisplayId + " numStacks=" + mStacks.size() + "}";
        }
    }

    class VirtualActivityDisplay extends ActivityDisplay {
        VirtualDisplay mVirtualDisplay;

        VirtualActivityDisplay(int width, int height, int density) {
            DisplayManagerGlobal dm = DisplayManagerGlobal.getInstance();
            mVirtualDisplay = dm.createVirtualDisplay(mService.mContext, null,
                    VIRTUAL_DISPLAY_BASE_NAME, width, height, density, null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, null, null);

            init(mVirtualDisplay.getDisplay());

            mWindowManager.handleDisplayAdded(mDisplayId);
        }

        void setSurface(Surface surface) {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.setSurface(surface);
            }
        }

        @Override
        void detachActivitiesLocked(ActivityStack stack) {
            super.detachActivitiesLocked(stack);
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
        }

        @Override
        public String toString() {
            return "VirtualActivityDisplay={" + mDisplayId + "}";
        }
    }

    private boolean isLeanbackOnlyDevice() {
        boolean onLeanbackOnly = false;
        try {
            onLeanbackOnly = AppGlobals.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_LEANBACK_ONLY);
        } catch (RemoteException e) {
            // noop
        }

        return onLeanbackOnly;
    }
}
