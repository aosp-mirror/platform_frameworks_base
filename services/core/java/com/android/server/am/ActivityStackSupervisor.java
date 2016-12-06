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

import android.Manifest;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackId;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityContainer;
import android.app.IActivityContainerCallback;
import android.app.IActivityManager;
import android.app.IActivityManager.WaitResult;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.app.StatusBarManager;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerInternal;
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
import android.os.Trace;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static android.Manifest.permission.START_ANY_ACTIVITY;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.ActivityManager.RESIZE_MODE_FORCED;
import static android.app.ActivityManager.RESIZE_MODE_SYSTEM;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.ActivityManager.StackId.FIRST_STATIC_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.LAST_STATIC_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CONTAINERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_IDLE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LOCKSCREEN;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_VISIBLE_BEHIND;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CONTAINERS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_IDLE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_VISIBLE_BEHIND;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ANIMATE;
import static com.android.server.am.ActivityManagerService.FIRST_SUPERVISOR_STACK_MSG;
import static com.android.server.am.ActivityManagerService.NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG;
import static com.android.server.am.ActivityManagerService.NOTIFY_FORCED_RESIZABLE_MSG;
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.RECENTS_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStack.ActivityState.DESTROYED;
import static com.android.server.am.ActivityStack.ActivityState.DESTROYING;
import static com.android.server.am.ActivityStack.ActivityState.INITIALIZING;
import static com.android.server.am.ActivityStack.ActivityState.PAUSED;
import static com.android.server.am.ActivityStack.ActivityState.PAUSING;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStack.ActivityState.STOPPED;
import static com.android.server.am.ActivityStack.ActivityState.STOPPING;
import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_MOVING;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;
import static com.android.server.am.ActivityStack.STACK_VISIBLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_PINNABLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_WHITELISTED;
import static com.android.server.wm.AppTransition.TRANSIT_DOCK_TASK_FROM_RECENTS;

public final class ActivityStackSupervisor implements DisplayListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStackSupervisor" : TAG_AM;
    private static final String TAG_CONTAINERS = TAG + POSTFIX_CONTAINERS;
    private static final String TAG_IDLE = TAG + POSTFIX_IDLE;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;
    private static final String TAG_VISIBLE_BEHIND = TAG + POSTFIX_VISIBLE_BEHIND;

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
    static final int REPORT_MULTI_WINDOW_MODE_CHANGED_MSG = FIRST_SUPERVISOR_STACK_MSG + 14;
    static final int REPORT_PIP_MODE_CHANGED_MSG = FIRST_SUPERVISOR_STACK_MSG + 15;

    private static final String VIRTUAL_DISPLAY_BASE_NAME = "ActivityViewVirtualDisplay";

    private static final String LOCK_TASK_TAG = "Lock-to-App";

    // Used to indicate if an object (e.g. stack) that we are trying to get
    // should be created if it doesn't exist already.
    static final boolean CREATE_IF_NEEDED = true;

    // Used to indicate that windows of activities should be preserved during the resize.
    static final boolean PRESERVE_WINDOWS = true;

    // Used to indicate if an object (e.g. task) should be moved/created
    // at the top of its container (e.g. stack).
    static final boolean ON_TOP = true;

    // Used to indicate that an objects (e.g. task) removal from its container
    // (e.g. stack) is due to it moving to another container.
    static final boolean MOVING = true;

    // Force the focus to change to the stack we are moving a task to..
    static final boolean FORCE_FOCUS = true;

    // Restore task from the saved recents if it can't be found in any live stack.
    static final boolean RESTORE_FROM_RECENTS = true;

    // Don't execute any calls to resume.
    static final boolean DEFER_RESUME = true;

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

    // The height/width divide used when fitting a task within a bounds with method
    // {@link #fitWithinBounds}.
    // We always want the task to to be visible in the bounds without affecting its size when
    // fitting. To make sure this is the case, we don't adjust the task left or top side pass
    // the input bounds right or bottom side minus the width or height divided by this value.
    private static final int FIT_WITHIN_BOUNDS_DIVIDER = 3;

    /** Status Bar Service **/
    private IBinder mToken = new Binder();
    private IStatusBarService mStatusBarService;
    private IDevicePolicyManager mDevicePolicyManager;

    // For debugging to make sure the caller when acquiring/releasing our
    // wake lock is the system process.
    static final boolean VALIDATE_WAKE_LOCK_CALLER = false;
    /** The number of distinct task ids that can be assigned to the tasks of a single user */
    private static final int MAX_TASK_IDS_PER_USER = UserHandle.PER_USER_RANGE;

    final ActivityManagerService mService;

    private RecentTasks mRecentTasks;

    final ActivityStackSupervisorHandler mHandler;

    /** Short cut */
    WindowManagerService mWindowManager;
    DisplayManager mDisplayManager;

    /** Counter for next free stack ID to use for dynamic activity stacks. */
    private int mNextFreeStackId = FIRST_DYNAMIC_STACK_ID;

    /**
     * Maps the task identifier that activities are currently being started in to the userId of the
     * task. Each time a new task is created, the entry for the userId of the task is incremented
     */
    private final SparseIntArray mCurTaskIdForUser = new SparseIntArray(20);

    /** The current user */
    int mCurrentUser;

    /** The stack containing the launcher app. Assumed to always be attached to
     * Display.DEFAULT_DISPLAY. */
    ActivityStack mHomeStack;

    /** The stack currently receiving input or launching the next activity. */
    ActivityStack mFocusedStack;

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

    /** List of activities whose multi-window mode changed that we need to report to the
     * application */
    final ArrayList<ActivityRecord> mMultiWindowModeChangedActivities = new ArrayList<>();

    /** List of activities whose picture-in-picture mode changed that we need to report to the
     * application */
    final ArrayList<ActivityRecord> mPipModeChangedActivities = new ArrayList<>();

    /** Used on user changes */
    final ArrayList<UserState> mStartingUsers = new ArrayList<>();

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

    /** Used to keep resumeTopActivityUncheckedLocked() from being entered recursively */
    boolean inResumeTopActivity;

    // temp. rects used during resize calculation so we don't need to create a new object each time.
    private final Rect tempRect = new Rect();
    private final Rect tempRect2 = new Rect();

    private final SparseArray<Configuration> mTmpConfigs = new SparseArray<>();
    private final SparseArray<Rect> mTmpBounds = new SparseArray<>();
    private final SparseArray<Rect> mTmpInsetBounds = new SparseArray<>();

    // The default minimal size that will be used if the activity doesn't specify its minimal size.
    // It will be calculated when the default display gets added.
    int mDefaultMinSizeOfResizeableTask = -1;

    // Whether tasks have moved and we need to rank the tasks before next OOM scoring
    private boolean mTaskLayersChanged = true;

    final ActivityMetricsLogger mActivityMetricsLogger;

    private final ResizeDockedStackTimeout mResizeDockedStackTimeout;

    static class FindTaskResult {
        ActivityRecord r;
        boolean matchedByRootAffinity;
    }
    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();

    /**
     * Used to keep track whether app visibilities got changed since the last pause. Useful to
     * determine whether to invoke the task stack change listener after pausing.
     */
    boolean mAppVisibilitiesChangedSinceLastPause;

    /**
     * Set of tasks that are in resizing mode during an app transition to fill the "void".
     */
    private final ArraySet<Integer> mResizingTasksDuringAnimation = new ArraySet<>();


    /**
     * If set to {@code false} all calls to resize the docked stack {@link #resizeDockedStackLocked}
     * will be ignored. Useful for the case where the caller is handling resizing of other stack and
     * moving tasks around and doesn't want dock stack to be resized due to an automatic trigger
     * like the docked stack going empty.
     */
    private boolean mAllowDockedStackResize = true;

    /**
     * Is dock currently minimized.
     */
    boolean mIsDockMinimized;

    /**
     * Description of a request to start a new activity, which has been held
     * due to app switches being disabled.
     */
    static class PendingActivityLaunch {
        final ActivityRecord r;
        final ActivityRecord sourceRecord;
        final int startFlags;
        final ActivityStack stack;
        final ProcessRecord callerApp;

        PendingActivityLaunch(ActivityRecord _r, ActivityRecord _sourceRecord,
                int _startFlags, ActivityStack _stack, ProcessRecord _callerApp) {
            r = _r;
            sourceRecord = _sourceRecord;
            startFlags = _startFlags;
            stack = _stack;
            callerApp = _callerApp;
        }

        void sendErrorResult(String message) {
            try {
                if (callerApp.thread != null) {
                    callerApp.thread.scheduleCrash(message);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception scheduling crash of failed "
                        + "activity launcher sourceRecord=" + sourceRecord, e);
            }
        }
    }

    public ActivityStackSupervisor(ActivityManagerService service) {
        mService = service;
        mHandler = new ActivityStackSupervisorHandler(mService.mHandler.getLooper());
        mActivityMetricsLogger = new ActivityMetricsLogger(this, mService.mContext);
        mResizeDockedStackTimeout = new ResizeDockedStackTimeout(service, this, mHandler);
    }

    void setRecentTasks(RecentTasks recentTasks) {
        mRecentTasks = recentTasks;
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
                calculateDefaultMinimalSizeOfResizeableTasks(activityDisplay);
            }

            mHomeStack = mFocusedStack = mLastFocusedStack =
                    getStack(HOME_STACK_ID, CREATE_IF_NEEDED, ON_TOP);

            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
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

    boolean isFocusedStack(ActivityStack stack) {
        if (stack == null) {
            return false;
        }

        final ActivityRecord parent = stack.mActivityContainer.mParentActivity;
        if (parent != null) {
            stack = parent.task.stack;
        }
        return stack == mFocusedStack;
    }

    /** The top most stack. */
    boolean isFrontStack(ActivityStack stack) {
        if (stack == null) {
            return false;
        }

        final ActivityRecord parent = stack.mActivityContainer.mParentActivity;
        if (parent != null) {
            stack = parent.task.stack;
        }
        return stack == mHomeStack.mStacks.get((mHomeStack.mStacks.size() - 1));
    }

    /** NOTE: Should only be called from {@link ActivityStack#moveToFront} */
    void setFocusStackUnchecked(String reason, ActivityStack focusCandidate) {
        if (!focusCandidate.isFocusable()) {
            // The focus candidate isn't focusable. Move focus to the top stack that is focusable.
            focusCandidate = focusCandidate.getNextFocusableStackLocked();
        }

        if (focusCandidate != mFocusedStack) {
            mLastFocusedStack = mFocusedStack;
            mFocusedStack = focusCandidate;

            EventLogTags.writeAmFocusedStack(
                    mCurrentUser, mFocusedStack == null ? -1 : mFocusedStack.getStackId(),
                    mLastFocusedStack == null ? -1 : mLastFocusedStack.getStackId(), reason);
        }

        final ActivityRecord r = topRunningActivityLocked();
        if (!mService.mDoingSetFocusedActivity && mService.mFocusedActivity != r) {
            // The focus activity should always be the top activity in the focused stack.
            // There will be chaos and anarchy if it isn't...
            mService.setFocusedActivityLocked(r, reason + " setFocusStack");
        }

        if (mService.mBooting || !mService.mBooted) {
            if (r != null && r.idle) {
                checkFinishBootingLocked();
            }
        }
    }

    void moveHomeStackToFront(String reason) {
        mHomeStack.moveToFront(reason);
    }

    /** Returns true if the focus activity was adjusted to the home stack top activity. */
    boolean moveHomeStackTaskToTop(int homeStackTaskType, String reason) {
        if (homeStackTaskType == RECENTS_ACTIVITY_TYPE) {
            mWindowManager.showRecentApps(false /* fromHome */);
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
            mWindowManager.showRecentApps(false /* fromHome */);
            return false;
        }

        if (prev != null) {
            prev.task.setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
        }

        mHomeStack.moveHomeStackTaskToTop(homeStackTaskType);
        ActivityRecord r = getHomeActivity();
        final String myReason = reason + " resumeHomeStackTask";

        // Only resume home activity if isn't finishing.
        if (r != null && !r.finishing) {
            mService.setFocusedActivityLocked(r, myReason);
            return resumeFocusedStackTopActivityLocked(mHomeStack, prev, null);
        }
        return mService.startHomeActivityLocked(mCurrentUser, myReason);
    }

    TaskRecord anyTaskForIdLocked(int id) {
        return anyTaskForIdLocked(id, RESTORE_FROM_RECENTS, INVALID_STACK_ID);
    }

    /**
     * Returns a {@link TaskRecord} for the input id if available. Null otherwise.
     * @param id Id of the task we would like returned.
     * @param restoreFromRecents If the id was not in the active list, but was found in recents,
     *                           restore the task from recents to the active list.
     * @param stackId The stack to restore the task to (default launch stack will be used if
     *                stackId is {@link android.app.ActivityManager.StackId#INVALID_STACK_ID}).
     */
    TaskRecord anyTaskForIdLocked(int id, boolean restoreFromRecents, int stackId) {
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

        if (!restoreRecentTaskLocked(task, stackId)) {
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

    /**
     * TODO: Handle freefom mode.
     * @return true when credential confirmation is needed for the user and there is any
     *         activity started by the user in any visible stack.
     */
    boolean isUserLockedProfile(@UserIdInt int userId) {
        if (!mService.mUserController.shouldConfirmCredentials(userId)) {
            return false;
        }
        final ActivityStack[] activityStacks = {
            getStack(DOCKED_STACK_ID),
            getStack(FREEFORM_WORKSPACE_STACK_ID),
            getStack(FULLSCREEN_WORKSPACE_STACK_ID),
        };
        for (final ActivityStack activityStack : activityStacks) {
            if (activityStack == null) {
                continue;
            }
            if (activityStack.topRunningActivityLocked() == null) {
                continue;
            }
            if (activityStack.getStackVisibilityLocked(null) == STACK_INVISIBLE) {
                continue;
            }
            if (activityStack.isDockedStack() && mIsDockMinimized) {
                continue;
            }
            if (activityStack.mStackId == FREEFORM_WORKSPACE_STACK_ID) {
                // TODO: Only the topmost task should trigger credential confirmation. But first,
                //       there needs to be a way to block out locked task window surfaces.
                final List<TaskRecord> tasks = activityStack.getAllTasks();
                final int size = tasks.size();
                for (int i = 0; i < size; i++) {
                    if (taskContainsActivityFromUser(tasks.get(i), userId)) {
                        return true;
                    }
                }
            } else {
                final TaskRecord topTask = activityStack.topTask();
                if (topTask == null) {
                    continue;
                }
                if (taskContainsActivityFromUser(topTask, userId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean taskContainsActivityFromUser(TaskRecord task, @UserIdInt int userId) {
        // To handle the case that work app is in the task but just is not the top one.
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord activityRecord = task.mActivities.get(i);
            if (activityRecord.userId == userId) {
                return true;
            }
        }
        return false;
    }

    void setNextTaskIdForUserLocked(int taskId, int userId) {
        final int currentTaskId = mCurTaskIdForUser.get(userId, -1);
        if (taskId > currentTaskId) {
            mCurTaskIdForUser.put(userId, taskId);
        }
    }

    static int nextTaskIdForUser(int taskId, int userId) {
        int nextTaskId = taskId + 1;
        if (nextTaskId == (userId + 1) * MAX_TASK_IDS_PER_USER) {
            // Wrap around as there will be smaller task ids that are available now.
            nextTaskId -= MAX_TASK_IDS_PER_USER;
        }
        return nextTaskId;
    }

    int getNextTaskIdForUserLocked(int userId) {
        final int currentTaskId = mCurTaskIdForUser.get(userId, userId * MAX_TASK_IDS_PER_USER);
        // for a userId u, a taskId can only be in the range
        // [u*MAX_TASK_IDS_PER_USER, (u+1)*MAX_TASK_IDS_PER_USER-1], so if MAX_TASK_IDS_PER_USER
        // was 10, user 0 could only have taskIds 0 to 9, user 1: 10 to 19, user 2: 20 to 29, so on.
        int candidateTaskId = nextTaskIdForUser(currentTaskId, userId);
        while (mRecentTasks.taskIdTakenForUserLocked(candidateTaskId, userId)
                || anyTaskForIdLocked(candidateTaskId, !RESTORE_FROM_RECENTS,
                        INVALID_STACK_ID) != null) {
            candidateTaskId = nextTaskIdForUser(candidateTaskId, userId);
            if (candidateTaskId == currentTaskId) {
                // Something wrong!
                // All MAX_TASK_IDS_PER_USER task ids are taken up by running tasks for this user
                throw new IllegalStateException("Cannot get an available task id."
                        + " Reached limit of " + MAX_TASK_IDS_PER_USER
                        + " running tasks per user.");
            }
        }
        mCurTaskIdForUser.put(userId, candidateTaskId);
        return candidateTaskId;
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
                resumedActivity = stack.topRunningActivityLocked();
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
                if (!isFocusedStack(stack)) {
                    continue;
                }
                ActivityRecord hr = stack.topRunningActivityLocked();
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
            ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        }
        return didSomething;
    }

    boolean allResumedActivitiesIdle() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!isFocusedStack(stack) || stack.numActivities() == 0) {
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
        // Send launch end powerhint when idle
        mService.mActivityStarter.sendPowerHintForLaunchEndIfNeeded();
        return true;
    }

    boolean allResumedActivitiesComplete() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (isFocusedStack(stack)) {
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
     * @param resuming The resuming activity.
     * @param dontWait The resuming activity isn't going to wait for all activities to be paused
     *                 before resuming.
     * @return true if any activity was paused as a result of this call.
     */
    boolean pauseBackStacks(boolean userLeaving, ActivityRecord resuming, boolean dontWait) {
        boolean someActivityPaused = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!isFocusedStack(stack) && stack.mResumedActivity != null) {
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
            ActivityRecord resuming, boolean dontWait) {
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

    void cancelInitializingActivities() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stacks.get(stackNdx).cancelInitializingActivities();
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

    void reportTaskToFrontNoLaunch(ActivityRecord r) {
        boolean changed = false;
        for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = mWaitingActivityLaunched.remove(i);
            if (w.who == null) {
                changed = true;
                // Set result to START_TASK_TO_FRONT so that startActivityMayWait() knows that
                // the starting activity ends up moving another activity to front, and it should
                // wait for this new activity to become visible instead.
                // Do not modify other fields.
                w.result = START_TASK_TO_FRONT;
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
                // Do not modify w.result.
            }
        }
        if (changed) {
            mService.notifyAll();
        }
    }

    ActivityRecord topRunningActivityLocked() {
        final ActivityStack focusedStack = mFocusedStack;
        ActivityRecord r = focusedStack.topRunningActivityLocked();
        if (r != null) {
            return r;
        }

        // Look in other non-focused and non-home stacks.
        final ArrayList<ActivityStack> stacks = mHomeStack.mStacks;
        for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = stacks.get(stackNdx);
            if (stack != focusedStack && isFrontStack(stack) && stack.isFocusable()) {
                r = stack.topRunningActivityLocked();
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

    ActivityInfo resolveActivity(Intent intent, ResolveInfo rInfo, int startFlags,
            ProfilerInfo profilerInfo) {
        final ActivityInfo aInfo = rInfo != null ? rInfo.activityInfo : null;
        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));

            // Don't debug things in the system process
            if (!aInfo.processName.equals("system")) {
                if ((startFlags & ActivityManager.START_FLAG_DEBUG) != 0) {
                    mService.setDebugApp(aInfo.processName, true, false);
                }

                if ((startFlags & ActivityManager.START_FLAG_NATIVE_DEBUGGING) != 0) {
                    mService.setNativeDebuggingAppLocked(aInfo.applicationInfo, aInfo.processName);
                }

                if ((startFlags & ActivityManager.START_FLAG_TRACK_ALLOCATION) != 0) {
                    mService.setTrackAllocationApp(aInfo.applicationInfo, aInfo.processName);
                }

                if (profilerInfo != null) {
                    mService.setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
                }
            }
        }
        return aInfo;
    }

    ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId) {
        return resolveIntent(intent, resolvedType, userId, 0);
    }

    ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId, int flags) {
        try {
            return AppGlobals.getPackageManager().resolveIntent(intent, resolvedType,
                    PackageManager.MATCH_DEFAULT_ONLY | flags
                    | ActivityManagerService.STOCK_PM_FLAGS, userId);
        } catch (RemoteException e) {
        }
        return null;
    }

    ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
            ProfilerInfo profilerInfo, int userId) {
        final ResolveInfo rInfo = resolveIntent(intent, resolvedType, userId);
        return resolveActivity(intent, rInfo, startFlags, profilerInfo);
    }

    final boolean realStartActivityLocked(ActivityRecord r, ProcessRecord app,
            boolean andResume, boolean checkConfig) throws RemoteException {

        if (!allPausedActivitiesComplete()) {
            // While there are activities pausing we skipping starting any new activities until
            // pauses are complete. NOTE: that we also do this for activities that are starting in
            // the paused state because they will first be resumed then paused on the client side.
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "realStartActivityLocked: Skipping start of r=" + r
                    + " some activities pausing...");
            return false;
        }

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
            // Deferring resume here because we're going to launch new activity shortly.
            // We don't want to perform a redundant launch of the same record while ensuring
            // configurations and trying to resume top activity of focused stack.
            mService.updateConfigurationLocked(config, r, false, true /* deferResume */);
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
            if (r.isHomeActivity()) {
                // Home process is the root process of the task.
                mService.mHomeProcess = task.mActivities.get(0).app;
            }
            mService.notifyPackageUse(r.intent.getComponent().getPackageName(),
                                      PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY);
            r.sleeping = false;
            r.forceNewConfig = false;
            mService.showUnsupportedZoomDialogIfNeededLocked(r);
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
                    new Configuration(task.mOverrideConfig), r.compat, r.launchedFromPackage,
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
            Slog.w(TAG, "Activity " + r + " being launched, but already in LRU list");
        }

        if (andResume) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            stack.minimalResumeActivityLocked(r);
        } else {
            // This activity is not starting in the resumed state... which should look like we asked
            // it to pause+stop (but remain visible), and it has done so and reported back the
            // current icicle and other state.
            if (DEBUG_STATES) Slog.v(TAG_STATES,
                    "Moving to PAUSED: " + r + " (starting in paused state)");
            r.state = PAUSED;
        }

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        if (isFocusedStack(stack)) {
            mService.startSetupActivityLocked();
        }

        // Update any services we are bound to that might care about whether
        // their client may have activities.
        if (r.app != null) {
            mService.mServices.updateServiceConnectionActivitiesLocked(r.app);
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

    boolean checkStartAnyActivityPermission(Intent intent, ActivityInfo aInfo,
            String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, boolean ignoreTargetSecurity, ProcessRecord callerApp,
            ActivityRecord resultRecord, ActivityStack resultStack, ActivityOptions options) {
        final int startAnyPerm = mService.checkPermission(START_ANY_ACTIVITY, callingPid,
                callingUid);
        if (startAnyPerm ==  PERMISSION_GRANTED) {
            return true;
        }
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
            final String msg;
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
            final String message = "Appop Denial: starting " + intent.toString()
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ")"
                    + " requires " + AppOpsManager.permissionToOp(
                            ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction()));
            Slog.w(TAG, message);
            return false;
        } else if (componentRestriction == ACTIVITY_RESTRICTION_APPOP) {
            final String message = "Appop Denial: starting " + intent.toString()
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ")"
                    + " requires appop " + AppOpsManager.permissionToOp(aInfo.permission);
            Slog.w(TAG, message);
            return false;
        }
        if (options != null && options.getLaunchTaskId() != -1) {
            final int startInTaskPerm = mService.checkPermission(START_TASKS_FROM_RECENTS,
                    callingPid, callingUid);
            if (startInTaskPerm != PERMISSION_GRANTED) {
                final String msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ") with launchTaskId="
                        + options.getLaunchTaskId();
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }

        return true;
    }

    UserInfo getUserInfo(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return UserManager.get(mService.mContext).getUserInfo(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
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

    boolean moveActivityStackToFront(ActivityRecord r, String reason) {
        if (r == null) {
            // Not sure what you are trying to do, but it is not going to work...
            return false;
        }
        final TaskRecord task = r.task;
        if (task == null || task.stack == null) {
            Slog.w(TAG, "Can't move stack to front for r=" + r + " task=" + task);
            return false;
        }
        task.stack.moveToFront(reason, task);
        return true;
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
            if (isFocusedStack(r.task.stack) || fromTimeout) {
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
            ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        }

        // Atomically retrieve all of the other things to do.
        final ArrayList<ActivityRecord> stops = processStoppingActivitiesLocked(true);
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
                    mService.mUserController.finishUserSwitch(startingUsers.get(i));
                }
            }
        }

        mService.trimApplications();
        //dump();
        //mWindowManager.dump();

        if (activityRemoved) {
            resumeFocusedStackTopActivityLocked();
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
     * Update the last used stack id for non-current user (current user's last
     * used stack is the focused stack)
     */
    void updateUserStackLocked(int userId, ActivityStack stack) {
        if (userId != mCurrentUser) {
            mUserStackInFront.put(userId, stack != null ? stack.getStackId() : HOME_STACK_ID);
        }
    }

    /**
     * @return true if some activity was finished (or would have finished if doit were true).
     */
    boolean finishDisabledPackageActivitiesLocked(String packageName, Set<String> filterByClasses,
            boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
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
                if (isFocusedStack(stack)) {
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

    boolean resumeFocusedStackTopActivityLocked() {
        return resumeFocusedStackTopActivityLocked(null, null, null);
    }

    boolean resumeFocusedStackTopActivityLocked(
            ActivityStack targetStack, ActivityRecord target, ActivityOptions targetOptions) {
        if (targetStack != null && isFocusedStack(targetStack)) {
            return targetStack.resumeTopActivityUncheckedLocked(target, targetOptions);
        }
        final ActivityRecord r = mFocusedStack.topRunningActivityLocked();
        if (r == null || r.state != RESUMED) {
            mFocusedStack.resumeTopActivityUncheckedLocked(null, null);
        }
        return false;
    }

    void updateActivityApplicationInfoLocked(ApplicationInfo aInfo) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stacks.get(stackNdx).updateActivityApplicationInfoLocked(aInfo);
            }
        }
    }

    TaskRecord finishTopRunningActivityLocked(ProcessRecord app, String reason) {
        TaskRecord finishedTask = null;
        ActivityStack focusedStack = getFocusedStack();
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int numStacks = stacks.size();
            for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                TaskRecord t = stack.finishTopRunningActivityLocked(app, reason);
                if (stack == focusedStack || finishedTask == null) {
                    finishedTask = t;
                }
            }
        }
        return finishedTask;
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

    void findTaskToMoveToFrontLocked(TaskRecord task, int flags, ActivityOptions options,
            String reason, boolean forceNonResizeable) {
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

        if (task.isResizeable() && options != null) {
            int stackId = options.getLaunchStackId();
            if (canUseActivityOptionsLaunchBounds(options, stackId)) {
                final Rect bounds = TaskRecord.validateBounds(options.getLaunchBounds());
                task.updateOverrideConfiguration(bounds);
                if (stackId == INVALID_STACK_ID) {
                    stackId = task.getLaunchStackId();
                }
                if (stackId != task.stack.mStackId) {
                    final ActivityStack stack = moveTaskToStackUncheckedLocked(
                            task, stackId, ON_TOP, !FORCE_FOCUS, reason);
                    stackId = stack.mStackId;
                    // moveTaskToStackUncheckedLocked() should already placed the task on top,
                    // still need moveTaskToFrontLocked() below for any transition settings.
                }
                if (StackId.resizeStackWithLaunchBounds(stackId)) {
                    resizeStackLocked(stackId, bounds,
                            null /* tempTaskBounds */, null /* tempTaskInsetBounds */,
                            !PRESERVE_WINDOWS, true /* allowResizeInDockedMode */, !DEFER_RESUME);
                } else {
                    // WM resizeTask must be done after the task is moved to the correct stack,
                    // because Task's setBounds() also updates dim layer's bounds, but that has
                    // dependency on the stack.
                    mWindowManager.resizeTask(task.taskId, task.mBounds, task.mOverrideConfig,
                            false /* relayout */, false /* forced */);
                }
            }
        }

        final ActivityRecord r = task.getTopActivity();
        task.stack.moveTaskToFrontLocked(task, false /* noAnimation */, options,
                r == null ? null : r.appTimeTracker, reason);

        if (DEBUG_STACK) Slog.d(TAG_STACK,
                "findTaskToMoveToFront: moved to front of stack=" + task.stack);

        handleNonResizableTaskIfNeeded(task, INVALID_STACK_ID, task.stack.mStackId,
                forceNonResizeable);
    }

    boolean canUseActivityOptionsLaunchBounds(ActivityOptions options, int launchStackId) {
        // We use the launch bounds in the activity options is the device supports freeform
        // window management or is launching into the pinned stack.
        if (options.getLaunchBounds() == null) {
            return false;
        }
        return (mService.mSupportsPictureInPicture && launchStackId == PINNED_STACK_ID)
                || mService.mSupportsFreeformWindowManagement;
    }

    ActivityStack getStack(int stackId) {
        return getStack(stackId, !CREATE_IF_NEEDED, !ON_TOP);
    }

    ActivityStack getStack(int stackId, boolean createStaticStackIfNeeded, boolean createOnTop) {
        ActivityContainer activityContainer = mActivityContainers.get(stackId);
        if (activityContainer != null) {
            return activityContainer.mStack;
        }
        if (!createStaticStackIfNeeded || !StackId.isStaticStack(stackId)) {
            return null;
        }
        return createStackOnDisplay(stackId, Display.DEFAULT_DISPLAY, createOnTop);
    }

    ArrayList<ActivityStack> getStacks() {
        ArrayList<ActivityStack> allStacks = new ArrayList<>();
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

    /**
     * Returns if a stack should be treated as if it's docked. Returns true if the stack is
     * the docked stack itself, or if it's side-by-side to the docked stack.
     */
    boolean isStackDockedInEffect(int stackId) {
        return stackId == DOCKED_STACK_ID ||
                (StackId.isResizeableByDockedStack(stackId) && getStack(DOCKED_STACK_ID) != null);
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

    void resizeStackLocked(int stackId, Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds,
            boolean preserveWindows, boolean allowResizeInDockedMode, boolean deferResume) {
        if (stackId == DOCKED_STACK_ID) {
            resizeDockedStackLocked(bounds, tempTaskBounds, tempTaskInsetBounds, null, null,
                    preserveWindows, deferResume);
            return;
        }
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            Slog.w(TAG, "resizeStack: stackId " + stackId + " not found.");
            return;
        }

        if (!allowResizeInDockedMode && getStack(DOCKED_STACK_ID) != null) {
            // If the docked stack exist we don't allow resizes of stacks not caused by the docked
            // stack size changing so things don't get out of sync.
            return;
        }

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizeStack_" + stackId);
        mWindowManager.deferSurfaceLayout();
        try {
            resizeStackUncheckedLocked(stack, bounds, tempTaskBounds, tempTaskInsetBounds);
            if (!deferResume) {
                stack.ensureVisibleActivitiesConfigurationLocked(
                        stack.topRunningActivityLocked(), preserveWindows);
            }
        } finally {
            mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    void deferUpdateBounds(int stackId) {
        final ActivityStack stack = getStack(stackId);
        if (stack != null) {
            stack.deferUpdateBounds();
        }
    }

    void continueUpdateBounds(int stackId) {
        final ActivityStack stack = getStack(stackId);
        if (stack != null) {
            stack.continueUpdateBounds();
        }
    }

    void notifyAppTransitionDone() {
        continueUpdateBounds(HOME_STACK_ID);
        for (int i = mResizingTasksDuringAnimation.size() - 1; i >= 0; i--) {
            final int taskId = mResizingTasksDuringAnimation.valueAt(i);
            if (anyTaskForIdLocked(taskId, !RESTORE_FROM_RECENTS, INVALID_STACK_ID) != null) {
                mWindowManager.setTaskDockedResizing(taskId, false);
            }
        }
        mResizingTasksDuringAnimation.clear();
    }

    void resizeStackUncheckedLocked(ActivityStack stack, Rect bounds, Rect tempTaskBounds,
            Rect tempTaskInsetBounds) {
        bounds = TaskRecord.validateBounds(bounds);

        if (!stack.updateBoundsAllowed(bounds, tempTaskBounds, tempTaskInsetBounds)) {
            return;
        }

        mTmpBounds.clear();
        mTmpConfigs.clear();
        mTmpInsetBounds.clear();
        final ArrayList<TaskRecord> tasks = stack.getAllTasks();
        final Rect taskBounds = tempTaskBounds != null ? tempTaskBounds : bounds;
        final Rect insetBounds = tempTaskInsetBounds != null ? tempTaskInsetBounds : taskBounds;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final TaskRecord task = tasks.get(i);
            if (task.isResizeable()) {
                if (stack.mStackId == FREEFORM_WORKSPACE_STACK_ID) {
                    // For freeform stack we don't adjust the size of the tasks to match that
                    // of the stack, but we do try to make sure the tasks are still contained
                    // with the bounds of the stack.
                    tempRect2.set(task.mBounds);
                    fitWithinBounds(tempRect2, bounds);
                    task.updateOverrideConfiguration(tempRect2);
                } else {
                    task.updateOverrideConfiguration(taskBounds, insetBounds);
                }
            }

            mTmpConfigs.put(task.taskId, task.mOverrideConfig);
            mTmpBounds.put(task.taskId, task.mBounds);
            if (tempTaskInsetBounds != null) {
                mTmpInsetBounds.put(task.taskId, tempTaskInsetBounds);
            }
        }

        // We might trigger a configuration change. Save the current task bounds for freezing.
        mWindowManager.prepareFreezingTaskBounds(stack.mStackId);
        stack.mFullscreen = mWindowManager.resizeStack(stack.mStackId, bounds, mTmpConfigs,
                mTmpBounds, mTmpInsetBounds);
        stack.setBounds(bounds);
    }

    void moveTasksToFullscreenStackLocked(int fromStackId, boolean onTop) {
        final ActivityStack stack = getStack(fromStackId);
        if (stack == null) {
            return;
        }

        mWindowManager.deferSurfaceLayout();
        try {
            if (fromStackId == DOCKED_STACK_ID) {

                // We are moving all tasks from the docked stack to the fullscreen stack,
                // which is dismissing the docked stack, so resize all other stacks to
                // fullscreen here already so we don't end up with resize trashing.
                for (int i = FIRST_STATIC_STACK_ID; i <= LAST_STATIC_STACK_ID; i++) {
                    if (StackId.isResizeableByDockedStack(i)) {
                        ActivityStack otherStack = getStack(i);
                        if (otherStack != null) {
                            resizeStackLocked(i, null, null, null, PRESERVE_WINDOWS,
                                    true /* allowResizeInDockedMode */, DEFER_RESUME);
                        }
                    }
                }

                // Also disable docked stack resizing since we have manually adjusted the
                // size of other stacks above and we don't want to trigger a docked stack
                // resize when we remove task from it below and it is detached from the
                // display because it no longer contains any tasks.
                mAllowDockedStackResize = false;
            }
            final ArrayList<TaskRecord> tasks = stack.getAllTasks();
            final int size = tasks.size();
            if (onTop) {
                for (int i = 0; i < size; i++) {
                    moveTaskToStackLocked(tasks.get(i).taskId,
                            FULLSCREEN_WORKSPACE_STACK_ID, onTop, onTop /*forceFocus*/,
                            "moveTasksToFullscreenStack", ANIMATE, DEFER_RESUME);
                }

                ensureActivitiesVisibleLocked(null, 0, PRESERVE_WINDOWS);
                resumeFocusedStackTopActivityLocked();
            } else {
                for (int i = size - 1; i >= 0; i--) {
                    positionTaskInStackLocked(tasks.get(i).taskId,
                            FULLSCREEN_WORKSPACE_STACK_ID, 0);
                }
            }
        } finally {
            mAllowDockedStackResize = true;
            mWindowManager.continueSurfaceLayout();
        }
    }

    /**
     * TODO: remove the need for this method. (b/30693465)
     *
     * @param userId user handle for the locked managed profile. Freeform tasks for this user will
     *        be moved to another stack, so they are not shown in the background.
     */
    void moveProfileTasksFromFreeformToFullscreenStackLocked(@UserIdInt int userId) {
        final ActivityStack stack = getStack(FREEFORM_WORKSPACE_STACK_ID);
        if (stack == null) {
            return;
        }
        mWindowManager.deferSurfaceLayout();
        try {
            final ArrayList<TaskRecord> tasks = stack.getAllTasks();
            final int size = tasks.size();
            for (int i = size - 1; i >= 0; i--) {
                if (taskContainsActivityFromUser(tasks.get(i), userId)) {
                    positionTaskInStackLocked(tasks.get(i).taskId, FULLSCREEN_WORKSPACE_STACK_ID,
                            /* position */ 0);
                }
            }
        } finally {
            mWindowManager.continueSurfaceLayout();
        }
    }

    void resizeDockedStackLocked(Rect dockedBounds, Rect tempDockedTaskBounds,
            Rect tempDockedTaskInsetBounds, Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds,
            boolean preserveWindows) {
        resizeDockedStackLocked(dockedBounds, tempDockedTaskBounds, tempDockedTaskInsetBounds,
                tempOtherTaskBounds, tempOtherTaskInsetBounds, preserveWindows,
                false /* deferResume */);
    }

    void resizeDockedStackLocked(Rect dockedBounds, Rect tempDockedTaskBounds,
            Rect tempDockedTaskInsetBounds, Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds,
            boolean preserveWindows, boolean deferResume) {

        if (!mAllowDockedStackResize) {
            // Docked stack resize currently disabled.
            return;
        }

        final ActivityStack stack = getStack(DOCKED_STACK_ID);
        if (stack == null) {
            Slog.w(TAG, "resizeDockedStackLocked: docked stack not found");
            return;
        }

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizeDockedStack");
        mWindowManager.deferSurfaceLayout();
        try {
            // Don't allow re-entry while resizing. E.g. due to docked stack detaching.
            mAllowDockedStackResize = false;
            ActivityRecord r = stack.topRunningActivityLocked();
            resizeStackUncheckedLocked(stack, dockedBounds, tempDockedTaskBounds,
                    tempDockedTaskInsetBounds);

            // TODO: Checking for isAttached might not be needed as if the user passes in null
            // dockedBounds then they want the docked stack to be dismissed.
            if (stack.mFullscreen || (dockedBounds == null && !stack.isAttached())) {
                // The dock stack either was dismissed or went fullscreen, which is kinda the same.
                // In this case we make all other static stacks fullscreen and move all
                // docked stack tasks to the fullscreen stack.
                moveTasksToFullscreenStackLocked(DOCKED_STACK_ID, ON_TOP);

                // stack shouldn't contain anymore activities, so nothing to resume.
                r = null;
            } else {
                // Docked stacks occupy a dedicated region on screen so the size of all other
                // static stacks need to be adjusted so they don't overlap with the docked stack.
                // We get the bounds to use from window manager which has been adjusted for any
                // screen controls and is also the same for all stacks.
                mWindowManager.getStackDockedModeBounds(
                        HOME_STACK_ID, tempRect, true /* ignoreVisibility */);
                for (int i = FIRST_STATIC_STACK_ID; i <= LAST_STATIC_STACK_ID; i++) {
                    if (StackId.isResizeableByDockedStack(i) && getStack(i) != null) {
                        resizeStackLocked(i, tempRect, tempOtherTaskBounds,
                                tempOtherTaskInsetBounds, preserveWindows,
                                true /* allowResizeInDockedMode */, deferResume);
                    }
                }
            }
            if (!deferResume) {
                stack.ensureVisibleActivitiesConfigurationLocked(r, preserveWindows);
            }
        } finally {
            mAllowDockedStackResize = true;
            mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }

        mResizeDockedStackTimeout.notifyResizing(dockedBounds,
                tempDockedTaskBounds != null
                || tempDockedTaskInsetBounds != null
                || tempOtherTaskBounds != null
                || tempOtherTaskInsetBounds != null);
    }

    void resizePinnedStackLocked(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        final ActivityStack stack = getStack(PINNED_STACK_ID);
        if (stack == null) {
            Slog.w(TAG, "resizePinnedStackLocked: pinned stack not found");
            return;
        }
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizePinnedStack");
        mWindowManager.deferSurfaceLayout();
        try {
            ActivityRecord r = stack.topRunningActivityLocked();
            resizeStackUncheckedLocked(stack, pinnedBounds, tempPinnedTaskBounds,
                    null);
            stack.ensureVisibleActivitiesConfigurationLocked(r, false);
        } finally {
            mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    boolean resizeTaskLocked(TaskRecord task, Rect bounds, int resizeMode, boolean preserveWindow,
            boolean deferResume) {
        if (!task.isResizeable()) {
            Slog.w(TAG, "resizeTask: task " + task + " not resizeable.");
            return true;
        }

        // If this is a forced resize, let it go through even if the bounds is not changing,
        // as we might need a relayout due to surface size change (to/from fullscreen).
        final boolean forced = (resizeMode & RESIZE_MODE_FORCED) != 0;
        if (Objects.equals(task.mBounds, bounds) && !forced) {
            // Nothing to do here...
            return true;
        }
        bounds = TaskRecord.validateBounds(bounds);

        if (!mWindowManager.isValidTaskId(task.taskId)) {
            // Task doesn't exist in window manager yet (e.g. was restored from recents).
            // All we can do for now is update the bounds so it can be used when the task is
            // added to window manager.
            task.updateOverrideConfiguration(bounds);
            if (task.stack != null && task.stack.mStackId != FREEFORM_WORKSPACE_STACK_ID) {
                // re-restore the task so it can have the proper stack association.
                restoreRecentTaskLocked(task, FREEFORM_WORKSPACE_STACK_ID);
            }
            return true;
        }

        // Do not move the task to another stack here.
        // This method assumes that the task is already placed in the right stack.
        // we do not mess with that decision and we only do the resize!

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizeTask_" + task.taskId);

        final Configuration overrideConfig =  task.updateOverrideConfiguration(bounds);
        // This variable holds information whether the configuration didn't change in a significant
        // way and the activity was kept the way it was. If it's false, it means the activity had
        // to be relaunched due to configuration change.
        boolean kept = true;
        if (overrideConfig != null) {
            final ActivityRecord r = task.topRunningActivityLocked();
            if (r != null) {
                final ActivityStack stack = task.stack;
                kept = stack.ensureActivityConfigurationLocked(r, 0, preserveWindow);

                if (!deferResume) {

                    // All other activities must be made visible with their correct configuration.
                    ensureActivitiesVisibleLocked(r, 0, !PRESERVE_WINDOWS);
                    if (!kept) {
                        resumeFocusedStackTopActivityLocked();
                    }
                }
            }
        }
        mWindowManager.resizeTask(task.taskId, task.mBounds, task.mOverrideConfig, kept, forced);

        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        return kept;
    }

    ActivityStack createStackOnDisplay(int stackId, int displayId, boolean onTop) {
        ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
        if (activityDisplay == null) {
            return null;
        }

        ActivityContainer activityContainer = new ActivityContainer(stackId);
        mActivityContainers.put(stackId, activityContainer);
        activityContainer.attachToDisplayLocked(activityDisplay, onTop);
        return activityContainer.mStack;
    }

    int getNextStackId() {
        while (true) {
            if (mNextFreeStackId >= FIRST_DYNAMIC_STACK_ID
                    && getStack(mNextFreeStackId) == null) {
                break;
            }
            mNextFreeStackId++;
        }
        return mNextFreeStackId;
    }

    /**
     * Restores a recent task to a stack
     * @param task The recent task to be restored.
     * @param stackId The stack to restore the task to (default launch stack will be used
     *                if stackId is {@link android.app.ActivityManager.StackId#INVALID_STACK_ID}).
     * @return true if the task has been restored successfully.
     */
    private boolean restoreRecentTaskLocked(TaskRecord task, int stackId) {
        if (stackId == INVALID_STACK_ID) {
            stackId = task.getLaunchStackId();
        } else if (stackId == DOCKED_STACK_ID && !task.canGoInDockedStack()) {
            // Preferred stack is the docked stack, but the task can't go in the docked stack.
            // Put it in the fullscreen stack.
            stackId = FULLSCREEN_WORKSPACE_STACK_ID;
        } else if (stackId == FREEFORM_WORKSPACE_STACK_ID
                && mService.mUserController.shouldConfirmCredentials(task.userId)) {
            // Task is barred from the freeform stack. Put it in the fullscreen stack.
            stackId = FULLSCREEN_WORKSPACE_STACK_ID;
        }

        if (task.stack != null) {
            // Task has already been restored once. See if we need to do anything more
            if (task.stack.mStackId == stackId) {
                // Nothing else to do since it is already restored in the right stack.
                return true;
            }
            // Remove current stack association, so we can re-associate the task with the
            // right stack below.
            task.stack.removeTask(task, "restoreRecentTaskLocked", REMOVE_TASK_MODE_MOVING);
        }

        final ActivityStack stack =
                getStack(stackId, CREATE_IF_NEEDED, !ON_TOP);

        if (stack == null) {
            // What does this mean??? Not sure how we would get here...
            if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                    "Unable to find/create stack to restore recent task=" + task);
            return false;
        }

        stack.addTask(task, false, "restoreRecentTask");
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                "Added restored task=" + task + " to stack=" + stack);
        final ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
            stack.addConfigOverride(activities.get(activityNdx), task);
        }
        return true;
    }

    /**
     * Moves the specified task record to the input stack id.
     * WARNING: This method performs an unchecked/raw move of the task and
     * can leave the system in an unstable state if used incorrectly.
     * Use {@link #moveTaskToStackLocked} to perform safe task movement to a stack.
     * @param task Task to move.
     * @param stackId Id of stack to move task to.
     * @param toTop True if the task should be placed at the top of the stack.
     * @param forceFocus if focus should be moved to the new stack
     * @param reason Reason the task is been moved.
     * @return The stack the task was moved to.
     */
    ActivityStack moveTaskToStackUncheckedLocked(
            TaskRecord task, int stackId, boolean toTop, boolean forceFocus, String reason) {

        if (StackId.isMultiWindowStack(stackId) && !mService.mSupportsMultiWindow) {
            throw new IllegalStateException("moveTaskToStackUncheckedLocked: Device doesn't "
                    + "support multi-window task=" + task + " to stackId=" + stackId);
        }

        final ActivityRecord r = task.topRunningActivityLocked();
        final ActivityStack prevStack = task.stack;
        final boolean wasFocused = isFocusedStack(prevStack) && (topRunningActivityLocked() == r);
        final boolean wasResumed = prevStack.mResumedActivity == r;
        // In some cases the focused stack isn't the front stack. E.g. pinned stack.
        // Whenever we are moving the top activity from the front stack we want to make sure to move
        // the stack to the front.
        final boolean wasFront = isFrontStack(prevStack)
                && (prevStack.topRunningActivityLocked() == r);

        if (stackId == DOCKED_STACK_ID && !task.isResizeable()) {
            // We don't allow moving a unresizeable task to the docked stack since the docked
            // stack is used for split-screen mode and will cause things like the docked divider to
            // show up. We instead leave the task in its current stack or move it to the fullscreen
            // stack if it isn't currently in a stack.
            stackId = (prevStack != null) ? prevStack.mStackId : FULLSCREEN_WORKSPACE_STACK_ID;
            Slog.w(TAG, "Can not move unresizeable task=" + task
                    + " to docked stack. Moving to stackId=" + stackId + " instead.");
        }
        if (stackId == FREEFORM_WORKSPACE_STACK_ID
                && mService.mUserController.shouldConfirmCredentials(task.userId)) {
            stackId = (prevStack != null) ? prevStack.mStackId : FULLSCREEN_WORKSPACE_STACK_ID;
            Slog.w(TAG, "Can not move locked profile task=" + task
                    + " to freeform stack. Moving to stackId=" + stackId + " instead.");
        }

        // Temporarily disable resizeablility of task we are moving. We don't want it to be resized
        // if a docked stack is created below which will lead to the stack we are moving from and
        // its resizeable tasks being resized.
        task.mTemporarilyUnresizable = true;
        final ActivityStack stack = getStack(stackId, CREATE_IF_NEEDED, toTop);
        task.mTemporarilyUnresizable = false;
        mWindowManager.moveTaskToStack(task.taskId, stack.mStackId, toTop);
        stack.addTask(task, toTop, reason);

        // If the task had focus before (or we're requested to move focus),
        // move focus to the new stack by moving the stack to the front.
        stack.moveToFrontAndResumeStateIfNeeded(
                r, forceFocus || wasFocused || wasFront, wasResumed, reason);

        return stack;
    }

    boolean moveTaskToStackLocked(int taskId, int stackId, boolean toTop, boolean forceFocus,
            String reason, boolean animate) {
        return moveTaskToStackLocked(taskId, stackId, toTop, forceFocus, reason, animate,
                false /* deferResume */);
    }

    boolean moveTaskToStackLocked(int taskId, int stackId, boolean toTop, boolean forceFocus,
            String reason, boolean animate, boolean deferResume) {
        final TaskRecord task = anyTaskForIdLocked(taskId);
        if (task == null) {
            Slog.w(TAG, "moveTaskToStack: no task for id=" + taskId);
            return false;
        }

        if (task.stack != null && task.stack.mStackId == stackId) {
            // You are already in the right stack silly...
            Slog.i(TAG, "moveTaskToStack: taskId=" + taskId + " already in stackId=" + stackId);
            return true;
        }

        if (stackId == FREEFORM_WORKSPACE_STACK_ID && !mService.mSupportsFreeformWindowManagement) {
            throw new IllegalArgumentException("moveTaskToStack:"
                    + "Attempt to move task " + taskId + " to unsupported freeform stack");
        }

        final ActivityRecord topActivity = task.getTopActivity();
        final int sourceStackId = task.stack != null ? task.stack.mStackId : INVALID_STACK_ID;
        final boolean mightReplaceWindow =
                StackId.replaceWindowsOnTaskMove(sourceStackId, stackId) && topActivity != null;
        if (mightReplaceWindow) {
            // We are about to relaunch the activity because its configuration changed due to
            // being maximized, i.e. size change. The activity will first remove the old window
            // and then add a new one. This call will tell window manager about this, so it can
            // preserve the old window until the new one is drawn. This prevents having a gap
            // between the removal and addition, in which no window is visible. We also want the
            // entrance of the new window to be properly animated.
            // Note here we always set the replacing window first, as the flags might be needed
            // during the relaunch. If we end up not doing any relaunch, we clear the flags later.
            mWindowManager.setReplacingWindow(topActivity.appToken, animate);
        }

        mWindowManager.deferSurfaceLayout();
        final int preferredLaunchStackId = stackId;
        boolean kept = true;
        try {
            final ActivityStack stack = moveTaskToStackUncheckedLocked(
                    task, stackId, toTop, forceFocus, reason + " moveTaskToStack");
            stackId = stack.mStackId;

            if (!animate) {
                stack.mNoAnimActivities.add(topActivity);
            }

            // We might trigger a configuration change. Save the current task bounds for freezing.
            mWindowManager.prepareFreezingTaskBounds(stack.mStackId);

            // Make sure the task has the appropriate bounds/size for the stack it is in.
            if (stackId == FULLSCREEN_WORKSPACE_STACK_ID && task.mBounds != null) {
                kept = resizeTaskLocked(task, stack.mBounds, RESIZE_MODE_SYSTEM,
                        !mightReplaceWindow, deferResume);
            } else if (stackId == FREEFORM_WORKSPACE_STACK_ID) {
                Rect bounds = task.getLaunchBounds();
                if (bounds == null) {
                    stack.layoutTaskInStack(task, null);
                    bounds = task.mBounds;
                }
                kept = resizeTaskLocked(task, bounds, RESIZE_MODE_FORCED, !mightReplaceWindow,
                        deferResume);
            } else if (stackId == DOCKED_STACK_ID || stackId == PINNED_STACK_ID) {
                kept = resizeTaskLocked(task, stack.mBounds, RESIZE_MODE_SYSTEM,
                        !mightReplaceWindow, deferResume);
            }
        } finally {
            mWindowManager.continueSurfaceLayout();
        }

        if (mightReplaceWindow) {
            // If we didn't actual do a relaunch (indicated by kept==true meaning we kept the old
            // window), we need to clear the replace window settings. Otherwise, we schedule a
            // timeout to remove the old window if the replacing window is not coming in time.
            mWindowManager.scheduleClearReplacingWindowIfNeeded(topActivity.appToken, !kept);
        }

        if (!deferResume) {

            // The task might have already been running and its visibility needs to be synchronized with
            // the visibility of the stack / windows.
            ensureActivitiesVisibleLocked(null, 0, !mightReplaceWindow);
            resumeFocusedStackTopActivityLocked();
        }

        handleNonResizableTaskIfNeeded(task, preferredLaunchStackId, stackId);

        return (preferredLaunchStackId == stackId);
    }

    boolean moveTopStackActivityToPinnedStackLocked(int stackId, Rect bounds) {
        final ActivityStack stack = getStack(stackId, !CREATE_IF_NEEDED, !ON_TOP);
        if (stack == null) {
            throw new IllegalArgumentException(
                    "moveTopStackActivityToPinnedStackLocked: Unknown stackId=" + stackId);
        }

        final ActivityRecord r = stack.topRunningActivityLocked();
        if (r == null) {
            Slog.w(TAG, "moveTopStackActivityToPinnedStackLocked: No top running activity"
                    + " in stack=" + stack);
            return false;
        }

        if (!mService.mForceResizableActivities && !r.supportsPictureInPicture()) {
            Slog.w(TAG,
                    "moveTopStackActivityToPinnedStackLocked: Picture-In-Picture not supported for "
                            + " r=" + r);
            return false;
        }

        moveActivityToPinnedStackLocked(r, "moveTopActivityToPinnedStack", bounds);
        return true;
    }

    void moveActivityToPinnedStackLocked(ActivityRecord r, String reason, Rect bounds) {
        mWindowManager.deferSurfaceLayout();
        try {
            final TaskRecord task = r.task;

            if (r == task.stack.getVisibleBehindActivity()) {
                // An activity can't be pinned and visible behind at the same time. Go ahead and
                // release it from been visible behind before pinning.
                requestVisibleBehindLocked(r, false);
            }

            // Need to make sure the pinned stack exist so we can resize it below...
            final ActivityStack stack = getStack(PINNED_STACK_ID, CREATE_IF_NEEDED, ON_TOP);

            // Resize the pinned stack to match the current size of the task the activity we are
            // going to be moving is currently contained in. We do this to have the right starting
            // animation bounds for the pinned stack to the desired bounds the caller wants.
            resizeStackLocked(PINNED_STACK_ID, task.mBounds, null /* tempTaskBounds */,
                    null /* tempTaskInsetBounds */, !PRESERVE_WINDOWS,
                    true /* allowResizeInDockedMode */, !DEFER_RESUME);

            if (task.mActivities.size() == 1) {
                // There is only one activity in the task. So, we can just move the task over to
                // the stack without re-parenting the activity in a different task.
                if (task.getTaskToReturnTo() == HOME_ACTIVITY_TYPE) {
                    // Move the home stack forward if the task we just moved to the pinned stack
                    // was launched from home so home should be visible behind it.
                    moveHomeStackToFront(reason);
                }
                moveTaskToStackLocked(
                        task.taskId, PINNED_STACK_ID, ON_TOP, FORCE_FOCUS, reason, !ANIMATE);
            } else {
                stack.moveActivityToStack(r);
            }
        } finally {
            mWindowManager.continueSurfaceLayout();
        }

        // The task might have already been running and its visibility needs to be synchronized
        // with the visibility of the stack / windows.
        ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        resumeFocusedStackTopActivityLocked();

        mWindowManager.animateResizePinnedStack(bounds, -1);
        mService.notifyActivityPinnedLocked();
    }

    void positionTaskInStackLocked(int taskId, int stackId, int position) {
        final TaskRecord task = anyTaskForIdLocked(taskId);
        if (task == null) {
            Slog.w(TAG, "positionTaskInStackLocked: no task for id=" + taskId);
            return;
        }
        final ActivityStack stack = getStack(stackId, CREATE_IF_NEEDED, !ON_TOP);

        task.updateOverrideConfigurationForStack(stack);

        mWindowManager.positionTaskInStack(
                taskId, stackId, position, task.mBounds, task.mOverrideConfig);
        stack.positionTask(task, position);
        // The task might have already been running and its visibility needs to be synchronized with
        // the visibility of the stack / windows.
        stack.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        resumeFocusedStackTopActivityLocked();
    }

    ActivityRecord findTaskLocked(ActivityRecord r) {
        mTmpFindTaskResult.r = null;
        mTmpFindTaskResult.matchedByRootAffinity = false;
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
                stack.findTaskLocked(r, mTmpFindTaskResult);
                // It is possible to have task in multiple stacks with the same root affinity.
                // If the match we found was based on root affinity we keep on looking to see if
                // there is a better match in another stack. We eventually return the match based
                // on root affinity if we don't find a better match.
                if (mTmpFindTaskResult.r != null && !mTmpFindTaskResult.matchedByRootAffinity) {
                    return mTmpFindTaskResult.r;
                }
            }
        }
        if (DEBUG_TASKS && mTmpFindTaskResult.r == null) Slog.d(TAG_TASKS, "No task found");
        return mTmpFindTaskResult.r;
    }

    ActivityRecord findActivityLocked(Intent intent, ActivityInfo info,
                                      boolean compareIntentFilters) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityRecord ar = stacks.get(stackNdx)
                        .findActivityLocked(intent, info, compareIntentFilters);
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
                if (isFocusedStack(stack)) {
                    resumeFocusedStackTopActivityLocked();
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
        if (!mService.isSleepingOrShuttingDownLocked()) {
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

        // Send launch end powerhint before going sleep
        mService.mActivityStarter.sendPowerHintForLaunchEndIfNeeded();

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
        if (isFocusedStack(stack)) {
            mService.updateUsageStats(r, true);
        }
        if (allResumedActivitiesComplete()) {
            ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
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

        if (visible && !StackId.activitiesCanRequestVisibleBehind(stack.mStackId)) {
            if (DEBUG_VISIBLE_BEHIND) Slog.d(TAG_VISIBLE_BEHIND, "requestVisibleBehind: r=" + r
                    + " visible=" + visible + " stackId=" + stack.mStackId
                    + " can't contain visible behind activities");
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
            // If there is a translucent home activity, we need to force it stop being translucent,
            // because we can't depend on the application to necessarily perform that operation.
            // Check out b/14469711 for details.
            final ActivityRecord next = stack.findNextTranslucentActivity(r);
            if (next != null && next.isHomeActivity()) {
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
        final TaskRecord task = r.task;
        final ActivityStack stack = task.stack;

        r.mLaunchTaskBehind = false;
        task.setLastThumbnailLocked(stack.screenshotActivitiesLocked(r));
        mRecentTasks.addLocked(task);
        mService.notifyTaskStackChangedLocked();
        mWindowManager.setAppVisibility(r.appToken, false);

        // When launching tasks behind, update the last active time of the top task after the new
        // task has been shown briefly
        final ActivityRecord top = stack.topActivity();
        if (top != null) {
            top.task.touchActiveTime();
        }
    }

    void scheduleLaunchTaskBehindComplete(IBinder token) {
        mHandler.obtainMessage(LAUNCH_TASK_BEHIND_COMPLETE, token).sendToTarget();
    }

    void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        // First the front stacks. In case any are not fullscreen and are in front of home.
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int topStackNdx = stacks.size() - 1;
            for (int stackNdx = topStackNdx; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows);
            }
        }
    }

    void invalidateTaskLayers() {
        mTaskLayersChanged = true;
    }

    void rankTaskLayersIfNeeded() {
        if (!mTaskLayersChanged) {
            return;
        }
        mTaskLayersChanged = false;
        for (int displayNdx = 0; displayNdx < mActivityDisplays.size(); displayNdx++) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            int baseLayer = 0;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                baseLayer += stacks.get(stackNdx).rankTaskLayers(baseLayer);
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
        final int focusStackId = mFocusedStack.getStackId();
        // We dismiss the docked stack whenever we switch users.
        moveTasksToFullscreenStackLocked(DOCKED_STACK_ID, focusStackId == DOCKED_STACK_ID);

        mUserStackInFront.put(mCurrentUser, focusStackId);
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
            stack.moveToFront("switchUserOnHomeDisplay");
        } else {
            // Stack was moved to another display while user was swapped out.
            resumeHomeStackTask(HOME_ACTIVITY_TYPE, null, "switchUserOnOtherDisplay");
        }
        return homeInFront;
    }

    /** Checks whether the userid is a profile of the current user. */
    boolean isCurrentProfileLocked(int userId) {
        if (userId == mCurrentUser) return true;
        return mService.mUserController.isCurrentProfileLocked(userId);
    }

    /** Checks whether the activity should be shown for current user. */
    boolean okToShowLocked(ActivityRecord r) {
        return r != null && ((r.info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0
                || (isCurrentProfileLocked(r.userId)
                && !mService.mUserController.isUserStoppingOrShuttingDownLocked(r.userId)));
    }

    final ArrayList<ActivityRecord> processStoppingActivitiesLocked(boolean remove) {
        ArrayList<ActivityRecord> stops = null;

        final boolean nowVisible = allResumedActivitiesVisible();
        for (int activityNdx = mStoppingActivities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord s = mStoppingActivities.get(activityNdx);
            // TODO: Remove mWaitingVisibleActivities list and just remove activity from
            // mStoppingActivities when something else comes up.
            boolean waitingVisible = mWaitingVisibleActivities.contains(s);
            if (DEBUG_STATES) Slog.v(TAG, "Stopping " + s + ": nowVisible=" + nowVisible
                    + " waitingVisible=" + waitingVisible + " finishing=" + s.finishing);
            if (waitingVisible && nowVisible) {
                mWaitingVisibleActivities.remove(s);
                waitingVisible = false;
                if (s.finishing) {
                    // If this activity is finishing, it is sitting on top of
                    // everyone else but we now know it is no longer needed...
                    // so get rid of it.  Otherwise, we need to go through the
                    // normal flow and hide it once we determine that it is
                    // hidden by the activities in front of it.
                    if (DEBUG_STATES) Slog.v(TAG, "Before stopping, can hide: " + s);
                    mWindowManager.setAppVisibility(s.appToken, false);
                }
            }
            if ((!waitingVisible || mService.isSleepingOrShuttingDownLocked()) && remove) {
                if (DEBUG_STATES) Slog.v(TAG, "Ready to stop: " + s);
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
                final ActivityRecord r = stack.topRunningActivityLocked();
                final ActivityState state = r == null ? DESTROYED : r.state;
                if (isFocusedStack(stack)) {
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
        pw.print(prefix);
        pw.println("mCurTaskIdForUser=" + mCurTaskIdForUser);
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
                stackHeader.append("\n");
                stackHeader.append("  mFullscreen=" + stack.mFullscreen);
                stackHeader.append("\n");
                stackHeader.append("  mBounds=" + stack.mBounds);
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
                calculateDefaultMinimalSizeOfResizeableTasks(activityDisplay);
            }
        }
        if (newDisplay) {
            mWindowManager.onDisplayAdded(displayId);
        }
    }

    private void calculateDefaultMinimalSizeOfResizeableTasks(ActivityDisplay display) {
        mDefaultMinSizeOfResizeableTask =
                mService.mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.default_minimal_size_resizable_task);
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
        final ActivityDisplay display = mActivityDisplays.get(Display.DEFAULT_DISPLAY);
        StackInfo info = new StackInfo();
        mWindowManager.getStackBounds(stack.mStackId, info.bounds);
        info.displayId = Display.DEFAULT_DISPLAY;
        info.stackId = stack.mStackId;
        info.userId = stack.mCurrentUser;
        info.visible = stack.getStackVisibilityLocked(null) == STACK_VISIBLE;
        info.position = display != null
                ? display.mStacks.indexOf(stack)
                : 0;

        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        final int numTasks = tasks.size();
        int[] taskIds = new int[numTasks];
        String[] taskNames = new String[numTasks];
        Rect[] taskBounds = new Rect[numTasks];
        int[] taskUserIds = new int[numTasks];
        for (int i = 0; i < numTasks; ++i) {
            final TaskRecord task = tasks.get(i);
            taskIds[i] = task.taskId;
            taskNames[i] = task.origActivity != null ? task.origActivity.flattenToString()
                    : task.realActivity != null ? task.realActivity.flattenToString()
                    : task.getTopActivity() != null ? task.getTopActivity().packageName
                    : "unknown";
            taskBounds[i] = new Rect();
            mWindowManager.getTaskBounds(task.taskId, taskBounds[i]);
            taskUserIds[i] = task.userId;
        }
        info.taskIds = taskIds;
        info.taskNames = taskNames;
        info.taskBounds = taskBounds;
        info.taskUserIds = taskUserIds;

        final ActivityRecord top = stack.topRunningActivityLocked();
        info.topActivity = top != null ? top.intent.getComponent() : null;
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
        ArrayList<StackInfo> list = new ArrayList<>();
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

    void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredStackId, int actualStackId) {
        handleNonResizableTaskIfNeeded(task, preferredStackId, actualStackId,
                false /* forceNonResizable */);
    }

    void handleNonResizableTaskIfNeeded(
            TaskRecord task, int preferredStackId, int actualStackId, boolean forceNonResizable) {
        if ((!isStackDockedInEffect(actualStackId) && preferredStackId != DOCKED_STACK_ID)
                || task.isHomeTask()) {
            return;
        }

        final ActivityRecord topActivity = task.getTopActivity();
        if (!task.canGoInDockedStack() || forceNonResizable) {
            // Display a warning toast that we tried to put a non-dockable task in the docked stack.
            mService.mHandler.sendEmptyMessage(NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG);

            // Dismiss docked stack. If task appeared to be in docked stack but is not resizable -
            // we need to move it to top of fullscreen stack, otherwise it will be covered.
            moveTasksToFullscreenStackLocked(DOCKED_STACK_ID, actualStackId == DOCKED_STACK_ID);
        } else if (topActivity != null && topActivity.isNonResizableOrForced()
                && !topActivity.noDisplay) {
            String packageName = topActivity.appInfo.packageName;
            mService.mHandler.obtainMessage(NOTIFY_FORCED_RESIZABLE_MSG, task.taskId, 0,
                    packageName).sendToTarget();
        }
    }

    void showLockTaskToast() {
        if (mLockTaskNotify != null) {
            mLockTaskNotify.showToast(mLockTaskModeState);
        }
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
                    resumeFocusedStackTopActivityLocked();
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
            task.mLockTaskUid = task.effectiveUid;
        }

        if (andResume) {
            findTaskToMoveToFrontLocked(task, 0, null, reason,
                    lockTaskModeState != LOCK_TASK_MODE_NONE);
            resumeFocusedStackTopActivityLocked();
        } else if (lockTaskModeState != LOCK_TASK_MODE_NONE) {
            handleNonResizableTaskIfNeeded(task, INVALID_STACK_ID, task.stack.mStackId,
                    true /* forceNonResizable */);
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
            resumeFocusedStackTopActivityLocked();
        }
    }

    int getLockTaskModeState() {
        return mLockTaskModeState;
    }

    void activityRelaunchedLocked(IBinder token) {
        mWindowManager.notifyAppRelaunchingFinished(token);
        if (mService.isSleepingOrShuttingDownLocked()) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.setSleeping(true, true);
            }
        }
    }

    void activityRelaunchingLocked(ActivityRecord r) {
        mWindowManager.notifyAppRelaunching(r.appToken);
    }

    void logStackState() {
        mActivityMetricsLogger.logWindowState();
    }

    void scheduleReportMultiWindowModeChanged(TaskRecord task) {
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = task.mActivities.get(i);
            if (r.app != null && r.app.thread != null) {
                mMultiWindowModeChangedActivities.add(r);
            }
        }

        if (!mHandler.hasMessages(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG)) {
            mHandler.sendEmptyMessage(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG);
        }
    }

    void scheduleReportPictureInPictureModeChangedIfNeeded(TaskRecord task, ActivityStack prevStack) {
        final ActivityStack stack = task.stack;
        if (prevStack == null || prevStack == stack
                || (prevStack.mStackId != PINNED_STACK_ID && stack.mStackId != PINNED_STACK_ID)) {
            return;
        }

        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = task.mActivities.get(i);
            if (r.app != null && r.app.thread != null) {
                mPipModeChangedActivities.add(r);
            }
        }

        if (!mHandler.hasMessages(REPORT_PIP_MODE_CHANGED_MSG)) {
            mHandler.sendEmptyMessage(REPORT_PIP_MODE_CHANGED_MSG);
        }
    }

    void setDockedStackMinimized(boolean minimized) {
        mIsDockMinimized = minimized;
        if (minimized) {
            // Docked stack is not visible, no need to confirm credentials for its top activity.
            return;
        }
        final ActivityStack dockedStack = getStack(StackId.DOCKED_STACK_ID);
        if (dockedStack == null) {
            return;
        }
        final ActivityRecord top = dockedStack.topRunningActivityLocked();
        if (top != null && mService.mUserController.shouldConfirmCredentials(top.userId)) {
            mService.mActivityStarter.showConfirmDeviceCredential(top.userId);
        }
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
                case REPORT_MULTI_WINDOW_MODE_CHANGED_MSG: {
                    synchronized (mService) {
                        for (int i = mMultiWindowModeChangedActivities.size() - 1; i >= 0; i--) {
                            final ActivityRecord r = mMultiWindowModeChangedActivities.remove(i);
                            r.scheduleMultiWindowModeChanged();
                        }
                    }
                } break;
                case REPORT_PIP_MODE_CHANGED_MSG: {
                    synchronized (mService) {
                        for (int i = mPipModeChangedActivities.size() - 1; i >= 0; i--) {
                            final ActivityRecord r = mPipModeChangedActivities.remove(i);
                            r.schedulePictureInPictureModeChanged();
                        }
                    }
                } break;
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
                        resumeFocusedStackTopActivityLocked();
                    }
                } break;
                case SLEEP_TIMEOUT_MSG: {
                    synchronized (mService) {
                        if (mService.isSleepingOrShuttingDownLocked()) {
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
        final static int FORCE_NEW_TASK_FLAGS = FLAG_ACTIVITY_NEW_TASK |
                FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION;
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

        void attachToDisplayLocked(ActivityDisplay activityDisplay, boolean onTop) {
            if (DEBUG_STACK) Slog.d(TAG_STACK, "attachToDisplayLocked: " + this
                    + " to display=" + activityDisplay + " onTop=" + onTop);
            mActivityDisplay = activityDisplay;
            mStack.attachDisplay(activityDisplay, onTop);
            activityDisplay.attachActivities(mStack, onTop);
        }

        @Override
        public void attachToDisplay(int displayId) {
            synchronized (mService) {
                ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
                if (activityDisplay == null) {
                    return;
                }
                attachToDisplayLocked(activityDisplay, true);
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
                    mService.mActivityStarter.removePendingActivityLaunchesLocked(mStack);
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
                mStack.detachDisplay();
            }
        }

        @Override
        public final int startActivity(Intent intent) {
            return mService.startActivity(intent, this);
        }

        @Override
        public final int startActivityIntentSender(IIntentSender intentSender)
                throws TransactionTooLargeException {
            mService.enforceNotIsolatedCaller("ActivityContainer.startActivityIntentSender");

            if (!(intentSender instanceof PendingIntentRecord)) {
                throw new IllegalArgumentException("Bad PendingIntent object");
            }

            final int userId = mService.mUserController.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), mCurrentUser, false,
                    ActivityManagerService.ALLOW_FULL_ONLY, "ActivityContainer", null);

            final PendingIntentRecord pendingIntent = (PendingIntentRecord) intentSender;
            checkEmbeddedAllowedInner(userId, pendingIntent.key.requestIntent,
                    pendingIntent.key.requestResolvedType);

            return pendingIntent.sendInner(0, null, null, null, null, null, null, 0,
                    FORCE_NEW_TASK_FLAGS, FORCE_NEW_TASK_FLAGS, null, this);
        }

        void checkEmbeddedAllowedInner(int userId, Intent intent, String resolvedType) {
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
                attachToDisplayLocked(virtualActivityDisplay, true);
            }

            if (mSurface != null) {
                mSurface.release();
            }

            mSurface = surface;
            if (surface != null) {
                resumeFocusedStackTopActivityLocked();
            } else {
                mContainerState = CONTAINER_STATE_NO_SURFACE;
                ((VirtualActivityDisplay) mActivityDisplay).setSurface(null);
                if (mStack.mPausingActivity == null && mStack.mResumedActivity != null) {
                    mStack.startPausingLocked(false, true, null, false);
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
        final ArrayList<ActivityStack> mStacks = new ArrayList<>();

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

        void attachActivities(ActivityStack stack, boolean onTop) {
            if (DEBUG_STACK) Slog.v(TAG_STACK,
                    "attachActivities: attaching " + stack + " to displayId=" + mDisplayId
                    + " onTop=" + onTop);
            if (onTop) {
                mStacks.add(stack);
            } else {
                mStacks.add(0, stack);
            }
        }

        void detachActivitiesLocked(ActivityStack stack) {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "detachActivitiesLocked: detaching " + stack
                    + " from displayId=" + mDisplayId);
            mStacks.remove(stack);
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

    /**
     * Adjust bounds to stay within stack bounds.
     *
     * Since bounds might be outside of stack bounds, this method tries to move the bounds in a way
     * that keep them unchanged, but be contained within the stack bounds.
     *
     * @param bounds Bounds to be adjusted.
     * @param stackBounds Bounds within which the other bounds should remain.
     */
    private static void fitWithinBounds(Rect bounds, Rect stackBounds) {
        if (stackBounds == null || stackBounds.contains(bounds)) {
            return;
        }

        if (bounds.left < stackBounds.left || bounds.right > stackBounds.right) {
            final int maxRight = stackBounds.right
                    - (stackBounds.width() / FIT_WITHIN_BOUNDS_DIVIDER);
            int horizontalDiff = stackBounds.left - bounds.left;
            if ((horizontalDiff < 0 && bounds.left >= maxRight)
                    || (bounds.left + horizontalDiff >= maxRight)) {
                horizontalDiff = maxRight - bounds.left;
            }
            bounds.left += horizontalDiff;
            bounds.right += horizontalDiff;
        }

        if (bounds.top < stackBounds.top || bounds.bottom > stackBounds.bottom) {
            final int maxBottom = stackBounds.bottom
                    - (stackBounds.height() / FIT_WITHIN_BOUNDS_DIVIDER);
            int verticalDiff = stackBounds.top - bounds.top;
            if ((verticalDiff < 0 && bounds.top >= maxBottom)
                    || (bounds.top + verticalDiff >= maxBottom)) {
                verticalDiff = maxBottom - bounds.top;
            }
            bounds.top += verticalDiff;
            bounds.bottom += verticalDiff;
        }
    }

    ActivityStack findStackBehind(ActivityStack stack) {
        // TODO(multi-display): We are only looking for stacks on the default display.
        final ActivityDisplay display = mActivityDisplays.get(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return null;
        }
        final ArrayList<ActivityStack> stacks = display.mStacks;
        for (int i = stacks.size() - 1; i >= 0; i--) {
            if (stacks.get(i) == stack && i > 0) {
                return stacks.get(i - 1);
            }
        }
        throw new IllegalStateException("Failed to find a stack behind stack=" + stack
                + " in=" + stacks);
    }

    /**
     * Puts a task into resizing mode during the next app transition.
     *
     * @param taskId the id of the task to put into resizing mode
     */
    private void setResizingDuringAnimation(int taskId) {
        mResizingTasksDuringAnimation.add(taskId);
        mWindowManager.setTaskDockedResizing(taskId, true);
    }

    final int startActivityFromRecentsInner(int taskId, Bundle bOptions) {
        final TaskRecord task;
        final int callingUid;
        final String callingPackage;
        final Intent intent;
        final int userId;
        final ActivityOptions activityOptions = (bOptions != null)
                ? new ActivityOptions(bOptions) : null;
        final int launchStackId = (activityOptions != null)
                ? activityOptions.getLaunchStackId() : INVALID_STACK_ID;
        if (launchStackId == HOME_STACK_ID) {
            throw new IllegalArgumentException("startActivityFromRecentsInner: Task "
                    + taskId + " can't be launch in the home stack.");
        }

        if (launchStackId == DOCKED_STACK_ID) {
            mWindowManager.setDockedStackCreateState(
                    activityOptions.getDockCreateMode(), null /* initialBounds */);

            // Defer updating the stack in which recents is until the app transition is done, to
            // not run into issues where we still need to draw the task in recents but the
            // docked stack is already created.
            deferUpdateBounds(HOME_STACK_ID);
            mWindowManager.prepareAppTransition(TRANSIT_DOCK_TASK_FROM_RECENTS, false);
        }

        task = anyTaskForIdLocked(taskId, RESTORE_FROM_RECENTS, launchStackId);
        if (task == null) {
            continueUpdateBounds(HOME_STACK_ID);
            mWindowManager.executeAppTransition();
            throw new IllegalArgumentException(
                    "startActivityFromRecentsInner: Task " + taskId + " not found.");
        }

        // Since we don't have an actual source record here, we assume that the currently focused
        // activity was the source.
        final ActivityStack focusedStack = getFocusedStack();
        final ActivityRecord sourceRecord =
                focusedStack != null ? focusedStack.topActivity() : null;

        if (launchStackId != INVALID_STACK_ID) {
            if (task.stack.mStackId != launchStackId) {
                moveTaskToStackLocked(
                        taskId, launchStackId, ON_TOP, FORCE_FOCUS, "startActivityFromRecents",
                        ANIMATE);
            }
        }

        // If the user must confirm credentials (e.g. when first launching a work app and the
        // Work Challenge is present) let startActivityInPackage handle the intercepting.
        if (!mService.mUserController.shouldConfirmCredentials(task.userId)
                && task.getRootActivity() != null) {
            mService.mActivityStarter.sendPowerHintForLaunchStartIfNeeded(true /* forceSend */);
            mActivityMetricsLogger.notifyActivityLaunching();
            mService.moveTaskToFrontLocked(task.taskId, 0, bOptions);
            mActivityMetricsLogger.notifyActivityLaunched(ActivityManager.START_TASK_TO_FRONT,
                    task.getTopActivity());

            // If we are launching the task in the docked stack, put it into resizing mode so
            // the window renders full-screen with the background filling the void. Also only
            // call this at the end to make sure that tasks exists on the window manager side.
            if (launchStackId == DOCKED_STACK_ID) {
                setResizingDuringAnimation(taskId);
            }

            mService.mActivityStarter.postStartActivityUncheckedProcessing(task.getTopActivity(),
                    ActivityManager.START_TASK_TO_FRONT,
                    sourceRecord != null ? sourceRecord.task.stack.mStackId : INVALID_STACK_ID,
                    sourceRecord, task.stack);
            return ActivityManager.START_TASK_TO_FRONT;
        }
        callingUid = task.mCallingUid;
        callingPackage = task.mCallingPackage;
        intent = task.intent;
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        userId = task.userId;
        int result = mService.startActivityInPackage(callingUid, callingPackage, intent, null,
                null, null, 0, 0, bOptions, userId, null, task);
        if (launchStackId == DOCKED_STACK_ID) {
            setResizingDuringAnimation(task.taskId);
        }
        return result;
    }

    /**
     * @return a list of activities which are the top ones in each visible stack. The first
     * entry will be the focused activity.
     */
    public List<IBinder> getTopVisibleActivities() {
        final ActivityDisplay display = mActivityDisplays.get(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return Collections.EMPTY_LIST;
        }
        ArrayList<IBinder> topActivityTokens = new ArrayList<>();
        final ArrayList<ActivityStack> stacks = display.mStacks;
        for (int i = stacks.size() - 1; i >= 0; i--) {
            ActivityStack stack = stacks.get(i);
            if (stack.getStackVisibilityLocked(null) == ActivityStack.STACK_VISIBLE) {
                ActivityRecord top = stack.topActivity();
                if (top != null) {
                    if (stack == mFocusedStack) {
                        topActivityTokens.add(0, top.appToken);
                    } else {
                        topActivityTokens.add(top.appToken);
                    }
                }
            }
        }
        return topActivityTokens;
    }
}
