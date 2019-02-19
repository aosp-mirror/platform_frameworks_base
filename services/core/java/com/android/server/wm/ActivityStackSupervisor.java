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

package com.android.server.wm;

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.START_ANY_ACTIVITY;
import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FLAG_DEBUG;
import static android.app.ActivityManager.START_FLAG_NATIVE_DEBUGGING;
import static android.app.ActivityManager.START_FLAG_TRACK_ALLOCATION;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WaitResult.INVALID_DELAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.graphics.Rect.copyOrNull;
import static android.os.PowerManager.PARTIAL_WAKE_LOCK;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.TYPE_VIRTUAL;
import static android.view.WindowManager.TRANSIT_DOCK_TASK_FROM_RECENTS;

import static com.android.server.wm.ActivityStack.ActivityState.PAUSED;
import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_MOVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_IDLE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_IDLE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ANIMATE;
import static com.android.server.wm.ActivityTaskManagerService.H.FIRST_SUPERVISOR_STACK_MSG;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_ONLY;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;
import static com.android.server.wm.RootActivityContainer.TAG_STATES;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_WHITELISTED;
import static com.android.server.wm.TaskRecord.REPARENT_KEEP_STACK_AT_FRONT;
import static com.android.server.wm.TaskRecord.REPARENT_LEAVE_STACK_IN_PLACE;
import static com.android.server.wm.TaskRecord.REPARENT_MOVE_STACK_TO_FRONT;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.app.WaitResult;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.LaunchActivityItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.MediaStore;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.EventLogTags;
import com.android.server.am.UserState;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

// TODO: This class has become a dumping ground. Let's
// - Move things relating to the hierarchy to RootWindowContainer
// - Move things relating to activity life cycles to maybe a new class called ActivityLifeCycler
// - Move interface things to ActivityTaskManagerService.
// - All other little things to other files.
public class ActivityStackSupervisor implements RecentTasks.Callbacks {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStackSupervisor" : TAG_ATM;
    private static final String TAG_IDLE = TAG + POSTFIX_IDLE;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;

    /** How long we wait until giving up on the last activity telling us it is idle. */
    static final int IDLE_TIMEOUT = 10 * 1000;

    /** How long we can hold the sleep wake lock before giving up. */
    static final int SLEEP_TIMEOUT = 5 * 1000;

    // How long we can hold the launch wake lock before giving up.
    static final int LAUNCH_TIMEOUT = 10 * 1000;

    /** How long we wait until giving up on the activity telling us it released the top state. */
    static final int TOP_RESUMED_STATE_LOSS_TIMEOUT = 500;

    static final int IDLE_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG;
    static final int IDLE_NOW_MSG = FIRST_SUPERVISOR_STACK_MSG + 1;
    static final int RESUME_TOP_ACTIVITY_MSG = FIRST_SUPERVISOR_STACK_MSG + 2;
    static final int SLEEP_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 3;
    static final int LAUNCH_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 4;
    static final int LAUNCH_TASK_BEHIND_COMPLETE = FIRST_SUPERVISOR_STACK_MSG + 12;
    static final int RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 13;
    static final int REPORT_MULTI_WINDOW_MODE_CHANGED_MSG = FIRST_SUPERVISOR_STACK_MSG + 14;
    static final int REPORT_PIP_MODE_CHANGED_MSG = FIRST_SUPERVISOR_STACK_MSG + 15;
    static final int REPORT_HOME_CHANGED_MSG = FIRST_SUPERVISOR_STACK_MSG + 16;
    static final int TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG = FIRST_SUPERVISOR_STACK_MSG + 17;

    // Used to indicate that windows of activities should be preserved during the resize.
    static final boolean PRESERVE_WINDOWS = true;

    // Used to indicate if an object (e.g. task) should be moved/created
    // at the top of its container (e.g. stack).
    static final boolean ON_TOP = true;

    // Don't execute any calls to resume.
    static final boolean DEFER_RESUME = true;

    // Used to indicate that a task is removed it should also be removed from recents.
    static final boolean REMOVE_FROM_RECENTS = true;

    // Used to indicate that pausing an activity should occur immediately without waiting for
    // the activity callback indicating that it has completed pausing
    static final boolean PAUSE_IMMEDIATELY = true;

    /** True if the docked stack is currently being resized. */
    private boolean mDockedStackResizing;

    /**
     * True if there are pending docked bounds that need to be applied after
     * {@link #mDockedStackResizing} is reset to false.
     */
    private boolean mHasPendingDockedBounds;
    private Rect mPendingDockedBounds;
    private Rect mPendingTempDockedTaskBounds;
    private Rect mPendingTempDockedTaskInsetBounds;
    private Rect mPendingTempOtherTaskBounds;
    private Rect mPendingTempOtherTaskInsetBounds;

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

    // For debugging to make sure the caller when acquiring/releasing our
    // wake lock is the system process.
    static final boolean VALIDATE_WAKE_LOCK_CALLER = false;
    /** The number of distinct task ids that can be assigned to the tasks of a single user */
    private static final int MAX_TASK_IDS_PER_USER = UserHandle.PER_USER_RANGE;

    final ActivityTaskManagerService mService;
    RootActivityContainer mRootActivityContainer;

    /** The historial list of recent tasks including inactive tasks */
    RecentTasks mRecentTasks;

    /** Helper class to abstract out logic for fetching the set of currently running tasks */
    RunningTasks mRunningTasks;

    final ActivityStackSupervisorHandler mHandler;
    final Looper mLooper;

    /** Short cut */
    WindowManagerService mWindowManager;

     /** Common synchronization logic used to save things to disks. */
    PersisterQueue mPersisterQueue;
    LaunchParamsPersister mLaunchParamsPersister;
    private LaunchParamsController mLaunchParamsController;

    /**
     * Maps the task identifier that activities are currently being started in to the userId of the
     * task. Each time a new task is created, the entry for the userId of the task is incremented
     */
    private final SparseIntArray mCurTaskIdForUser = new SparseIntArray(20);

    /** List of processes waiting to find out when a specific activity becomes visible. */
    private final ArrayList<WaitInfo> mWaitingForActivityVisible = new ArrayList<>();

    /** List of processes waiting to find out about the next launched activity. */
    final ArrayList<WaitResult> mWaitingActivityLaunched = new ArrayList<>();

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

    /**
     * Animations that for the current transition have requested not to
     * be considered for the transition animation.
     */
    final ArrayList<ActivityRecord> mNoAnimActivities = new ArrayList<>();

    /**
     * Cached value of the topmost resumed activity in the system. Updated when new activity is
     * resumed.
     */
    private ActivityRecord mTopResumedActivity;

    /**
     * Flag indicating whether we're currently waiting for the previous top activity to handle the
     * loss of the state and report back before making new activity top resumed.
     */
    private boolean mTopResumedActivityWaitingForPrev;

    /** The target stack bounds for the picture-in-picture mode changed that we need to report to
     * the application */
    Rect mPipModeChangedTargetStackBounds;

    /** Used on user changes */
    final ArrayList<UserState> mStartingUsers = new ArrayList<>();

    /** Set to indicate whether to issue an onUserLeaving callback when a newly launched activity
     * is being brought in front of us. */
    boolean mUserLeaving = false;

    /**
     * We don't want to allow the device to go to sleep while in the process
     * of launching an activity.  This is primarily to allow alarm intent
     * receivers to launch an activity and get that to run before the device
     * goes back to sleep.
     */
    PowerManager.WakeLock mLaunchingActivityWakeLock;

    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    PowerManager.WakeLock mGoingToSleepWakeLock;

    /**
     * Temporary rect used during docked stack resize calculation so we don't need to create a new
     * object each time.
     */
    private final Rect tempRect = new Rect();
    private final ActivityOptions mTmpOptions = ActivityOptions.makeBasic();

    private ActivityMetricsLogger mActivityMetricsLogger;

    /** Check if placing task or activity on specified display is allowed. */
    boolean canPlaceEntityOnDisplay(int displayId, int callingPid, int callingUid,
            ActivityInfo activityInfo) {
        if (displayId == DEFAULT_DISPLAY) {
            // No restrictions for the default display.
            return true;
        }
        if (!mService.mSupportsMultiDisplay) {
            // Can't launch on secondary displays if feature is not supported.
            return false;
        }
        if (!isCallerAllowedToLaunchOnDisplay(callingPid, callingUid, displayId, activityInfo)) {
            // Can't place activities to a display that has restricted launch rules.
            // In this case the request should be made by explicitly adding target display id and
            // by caller with corresponding permissions. See #isCallerAllowedToLaunchOnDisplay().
            return false;
        }
        return true;
    }

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

    private KeyguardController mKeyguardController;

    private PowerManager mPowerManager;
    private int mDeferResumeCount;

    private boolean mInitialized;

    /**
     * Description of a request to start a new activity, which has been held
     * due to app switches being disabled.
     */
    static class PendingActivityLaunch {
        final ActivityRecord r;
        final ActivityRecord sourceRecord;
        final int startFlags;
        final ActivityStack stack;
        final WindowProcessController callerApp;

        PendingActivityLaunch(ActivityRecord _r, ActivityRecord _sourceRecord,
                int _startFlags, ActivityStack _stack, WindowProcessController app) {
            r = _r;
            sourceRecord = _sourceRecord;
            startFlags = _startFlags;
            stack = _stack;
            callerApp = app;
        }

        void sendErrorResult(String message) {
            try {
                if (callerApp.hasThread()) {
                    callerApp.getThread().scheduleCrash(message);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception scheduling crash of failed "
                        + "activity launcher sourceRecord=" + sourceRecord, e);
            }
        }
    }

    public ActivityStackSupervisor(ActivityTaskManagerService service, Looper looper) {
        mService = service;
        mLooper = looper;
        mHandler = new ActivityStackSupervisorHandler(looper);
    }

    public void initialize() {
        if (mInitialized) {
            return;
        }

        mInitialized = true;
        mRunningTasks = createRunningTasks();

        mActivityMetricsLogger = new ActivityMetricsLogger(this, mService.mContext,
                mHandler.getLooper());
        mKeyguardController = new KeyguardController(mService, this);

        mPersisterQueue = new PersisterQueue();
        mLaunchParamsPersister = new LaunchParamsPersister(mPersisterQueue, this);
        mLaunchParamsController = new LaunchParamsController(mService, mLaunchParamsPersister);
        mLaunchParamsController.registerDefaultModifiers(this);
    }

    void onSystemReady() {
        mLaunchParamsPersister.onSystemReady();
    }

    void onUserUnlocked(int userId) {
        // Only start persisting when the first user is unlocked. The method call is
        // idempotent so there is no side effect to call it again when the second user is
        // unlocked.
        mPersisterQueue.startPersisting();
        mLaunchParamsPersister.onUnlockUser(userId);
    }

    public ActivityMetricsLogger getActivityMetricsLogger() {
        return mActivityMetricsLogger;
    }

    public KeyguardController getKeyguardController() {
        return mKeyguardController;
    }

    void setRecentTasks(RecentTasks recentTasks) {
        mRecentTasks = recentTasks;
        mRecentTasks.registerCallback(this);
    }

    @VisibleForTesting
    RunningTasks createRunningTasks() {
        return new RunningTasks();
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize our wakelocks afterwards.
     */
    void initPowerManagement() {
        mPowerManager = mService.mContext.getSystemService(PowerManager.class);
        mGoingToSleepWakeLock = mPowerManager
                .newWakeLock(PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        mLaunchingActivityWakeLock = mPowerManager.newWakeLock(PARTIAL_WAKE_LOCK, "*launch*");
        mLaunchingActivityWakeLock.setReferenceCounted(false);
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        getKeyguardController().setWindowManager(wm);
    }

    void moveRecentsStackToFront(String reason) {
        final ActivityStack recentsStack = mRootActivityContainer.getDefaultDisplay().getStack(
                WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_RECENTS);
        if (recentsStack != null) {
            recentsStack.moveToFront(reason);
        }
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
        while (mRecentTasks.containsTaskId(candidateTaskId, userId)
                || mRootActivityContainer.anyTaskForId(
                        candidateTaskId, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS) != null) {
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

    void waitActivityVisible(ComponentName name, WaitResult result, long startTimeMs) {
        final WaitInfo waitInfo = new WaitInfo(name, result, startTimeMs);
        mWaitingForActivityVisible.add(waitInfo);
    }

    void cleanupActivity(ActivityRecord r) {
        // Make sure this record is no longer in the pending finishes list.
        // This could happen, for example, if we are trimming activities
        // down to the max limit while they are still waiting to finish.
        mFinishingActivities.remove(r);

        for (int i = mWaitingForActivityVisible.size() - 1; i >= 0; --i) {
            if (mWaitingForActivityVisible.get(i).matches(r.mActivityComponent)) {
                mWaitingForActivityVisible.remove(i);
            }
        }
    }

    void reportActivityVisibleLocked(ActivityRecord r) {
        sendWaitingVisibleReportLocked(r);
    }

    void sendWaitingVisibleReportLocked(ActivityRecord r) {
        boolean changed = false;
        for (int i = mWaitingForActivityVisible.size() - 1; i >= 0; --i) {
            final WaitInfo w = mWaitingForActivityVisible.get(i);
            if (w.matches(r.mActivityComponent)) {
                final WaitResult result = w.getResult();
                changed = true;
                result.timeout = false;
                result.who = w.getComponent();
                result.totalTime = SystemClock.uptimeMillis() - w.getStartTime();
                mWaitingForActivityVisible.remove(w);
            }
        }
        if (changed) {
            mService.mGlobalLock.notifyAll();
        }
    }

    void reportWaitingActivityLaunchedIfNeeded(ActivityRecord r, int result) {
        if (mWaitingActivityLaunched.isEmpty()) {
            return;
        }

        if (result != START_DELIVERED_TO_TOP && result != START_TASK_TO_FRONT) {
            return;
        }

        boolean changed = false;

        for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = mWaitingActivityLaunched.remove(i);
            if (w.who == null) {
                changed = true;
                w.result = result;

                // Unlike START_TASK_TO_FRONT, When an intent is delivered to top, there
                // will be no followup launch signals. Assign the result and launched component.
                if (result == START_DELIVERED_TO_TOP) {
                    w.who = r.mActivityComponent;
                }
            }
        }

        if (changed) {
            mService.mGlobalLock.notifyAll();
        }
    }

    void reportActivityLaunchedLocked(boolean timeout, ActivityRecord r, long totalTime,
            @WaitResult.LaunchState int launchState) {
        boolean changed = false;
        for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = mWaitingActivityLaunched.remove(i);
            if (w.who == null) {
                changed = true;
                w.timeout = timeout;
                if (r != null) {
                    w.who = new ComponentName(r.info.packageName, r.info.name);
                }
                w.totalTime = totalTime;
                w.launchState = launchState;
                // Do not modify w.result.
            }
        }
        if (changed) {
            mService.mGlobalLock.notifyAll();
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
                if ((startFlags & (START_FLAG_DEBUG | START_FLAG_NATIVE_DEBUGGING
                        | START_FLAG_TRACK_ALLOCATION)) != 0 || profilerInfo != null) {

                     // Mimic an AMS synchronous call by passing a message to AMS and wait for AMS
                     // to notify us that the task has completed.
                     // TODO(b/80414790) look into further untangling for the situation where the
                     // caller is on the same thread as the handler we are posting to.
                    synchronized (mService.mGlobalLock) {
                        // Post message to AMS.
                        final Message msg = PooledLambda.obtainMessage(
                                ActivityManagerInternal::setDebugFlagsForStartingActivity,
                                mService.mAmInternal, aInfo, startFlags, profilerInfo,
                                mService.mGlobalLock);
                        mService.mH.sendMessage(msg);
                        try {
                            mService.mGlobalLock.wait();
                        } catch (InterruptedException ignore) {

                        }
                    }
                }
            }
            final String intentLaunchToken = intent.getLaunchToken();
            if (aInfo.launchToken == null && intentLaunchToken != null) {
                aInfo.launchToken = intentLaunchToken;
            }
        }
        return aInfo;
    }

    ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId, int flags,
            int filterCallingUid) {
        try {
            Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "resolveIntent");
            int modifiedFlags = flags
                    | PackageManager.MATCH_DEFAULT_ONLY | ActivityManagerService.STOCK_PM_FLAGS;
            if (intent.isWebIntent()
                        || (intent.getFlags() & Intent.FLAG_ACTIVITY_MATCH_EXTERNAL) != 0) {
                modifiedFlags |= PackageManager.MATCH_INSTANT;
            }

            // In order to allow cross-profile lookup, we clear the calling identity here.
            // Note the binder identity won't affect the result, but filterCallingUid will.

            // Cross-user/profile call check are done at the entry points
            // (e.g. AMS.startActivityAsUser).
            final long token = Binder.clearCallingIdentity();
            try {
                return mService.getPackageManagerInternalLocked().resolveIntent(
                        intent, resolvedType, modifiedFlags, userId, true, filterCallingUid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
            ProfilerInfo profilerInfo, int userId, int filterCallingUid) {
        final ResolveInfo rInfo = resolveIntent(intent, resolvedType, userId, 0, filterCallingUid);
        return resolveActivity(intent, rInfo, startFlags, profilerInfo);
    }

    boolean realStartActivityLocked(ActivityRecord r, WindowProcessController proc,
            boolean andResume, boolean checkConfig) throws RemoteException {

        if (!mRootActivityContainer.allPausedActivitiesComplete()) {
            // While there are activities pausing we skipping starting any new activities until
            // pauses are complete. NOTE: that we also do this for activities that are starting in
            // the paused state because they will first be resumed then paused on the client side.
            if (DEBUG_SWITCH || DEBUG_PAUSE || DEBUG_STATES) Slog.v(TAG_PAUSE,
                    "realStartActivityLocked: Skipping start of r=" + r
                    + " some activities pausing...");
            return false;
        }

        final TaskRecord task = r.getTaskRecord();
        final ActivityStack stack = task.getStack();

        beginDeferResume();

        try {
            r.startFreezingScreenLocked(proc, 0);

            // schedule launch ticks to collect information about slow apps.
            r.startLaunchTickingLocked();

            r.setProcess(proc);

            // Ensure activity is allowed to be resumed after process has set.
            if (andResume && !r.canResumeByCompat()) {
                andResume = false;
            }

            if (getKeyguardController().isKeyguardLocked()) {
                r.notifyUnknownVisibilityLaunched();
            }

            // Have the window manager re-evaluate the orientation of the screen based on the new
            // activity order.  Note that as a result of this, it can call back into the activity
            // manager with a new orientation.  We don't care about that, because the activity is
            // not currently running so we are just restarting it anyway.
            if (checkConfig) {
                // Deferring resume here because we're going to launch new activity shortly.
                // We don't want to perform a redundant launch of the same record while ensuring
                // configurations and trying to resume top activity of focused stack.
                mRootActivityContainer.ensureVisibilityAndConfig(r, r.getDisplayId(),
                        false /* markFrozenIfConfigChanged */, true /* deferResume */);
            }

            if (r.getActivityStack().checkKeyguardVisibility(r, true /* shouldBeVisible */,
                    true /* isTop */)) {
                // We only set the visibility to true if the activity is allowed to be visible
                // based on
                // keyguard state. This avoids setting this into motion in window manager that is
                // later cancelled due to later calls to ensure visible activities that set
                // visibility back to false.
                r.setVisibility(true);
            }

            final int applicationInfoUid =
                    (r.info.applicationInfo != null) ? r.info.applicationInfo.uid : -1;
            if ((r.mUserId != proc.mUserId) || (r.appInfo.uid != applicationInfoUid)) {
                Slog.wtf(TAG,
                        "User ID for activity changing for " + r
                                + " appInfo.uid=" + r.appInfo.uid
                                + " info.ai.uid=" + applicationInfoUid
                                + " old=" + r.app + " new=" + proc);
            }

            proc.clearWaitingToKill();
            r.launchCount++;
            r.lastLaunchTime = SystemClock.uptimeMillis();

            if (DEBUG_ALL) Slog.v(TAG, "Launching: " + r);

            proc.addActivityIfNeeded(r);
            proc.updateProcessInfo(false, true, true, true);

            final LockTaskController lockTaskController = mService.getLockTaskController();
            if (task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE
                    || task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE_PRIV
                    || (task.mLockTaskAuth == LOCK_TASK_AUTH_WHITELISTED
                            && lockTaskController.getLockTaskModeState()
                                    == LOCK_TASK_MODE_LOCKED)) {
                lockTaskController.startLockTaskMode(task, false, 0 /* blank UID */);
            }

            try {
                if (!proc.hasThread()) {
                    throw new RemoteException();
                }
                List<ResultInfo> results = null;
                List<ReferrerIntent> newIntents = null;
                if (andResume) {
                    // We don't need to deliver new intents and/or set results if activity is going
                    // to pause immediately after launch.
                    results = r.results;
                    newIntents = r.newIntents;
                }
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                        "Launching: " + r + " icicle=" + r.icicle + " with results=" + results
                                + " newIntents=" + newIntents + " andResume=" + andResume);
                EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY, r.mUserId,
                        System.identityHashCode(r), task.taskId, r.shortComponentName);
                if (r.isActivityTypeHome()) {
                    // Home process is the root process of the task.
                    updateHomeProcess(task.mActivities.get(0).app);
                }
                mService.getPackageManagerInternalLocked().notifyPackageUse(
                        r.intent.getComponent().getPackageName(), NOTIFY_PACKAGE_USE_ACTIVITY);
                r.sleeping = false;
                r.forceNewConfig = false;
                mService.getAppWarningsLocked().onStartActivity(r);
                r.compat = mService.compatibilityInfoForPackageLocked(r.info.applicationInfo);
                ProfilerInfo profilerInfo = proc.onStartActivity(mService.mTopProcessState);

                // Because we could be starting an Activity in the system process this may not go
                // across a Binder interface which would create a new Configuration. Consequently
                // we have to always create a new Configuration here.

                final MergedConfiguration mergedConfiguration = new MergedConfiguration(
                        proc.getConfiguration(), r.getMergedOverrideConfiguration());
                r.setLastReportedConfiguration(mergedConfiguration);

                logIfTransactionTooLarge(r.intent, r.icicle);


                // Create activity launch transaction.
                final ClientTransaction clientTransaction = ClientTransaction.obtain(
                        proc.getThread(), r.appToken);

                final DisplayContent dc = r.getDisplay().mDisplayContent;
                clientTransaction.addCallback(LaunchActivityItem.obtain(new Intent(r.intent),
                        System.identityHashCode(r), r.info,
                        // TODO: Have this take the merged configuration instead of separate global
                        // and override configs.
                        mergedConfiguration.getGlobalConfiguration(),
                        mergedConfiguration.getOverrideConfiguration(), r.compat,
                        r.launchedFromPackage, task.voiceInteractor, proc.getReportedProcState(),
                        r.icicle, r.persistentState, results, newIntents,
                        dc.isNextTransitionForward(), profilerInfo));

                // Set desired final state.
                final ActivityLifecycleItem lifecycleItem;
                if (andResume) {
                    lifecycleItem = ResumeActivityItem.obtain(dc.isNextTransitionForward());
                } else {
                    lifecycleItem = PauseActivityItem.obtain();
                }
                clientTransaction.setLifecycleStateRequest(lifecycleItem);

                // Schedule transaction.
                mService.getLifecycleManager().scheduleTransaction(clientTransaction);
                updateTopResumedActivityIfNeeded();

                if ((proc.mInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0
                        && mService.mHasHeavyWeightFeature) {
                    // This may be a heavy-weight process! Note that the package manager will ensure
                    // that only activity can run in the main process of the .apk, which is the only
                    // thing that will be considered heavy-weight.
                    if (proc.mName.equals(proc.mInfo.packageName)) {
                        if (mService.mHeavyWeightProcess != null
                                && mService.mHeavyWeightProcess != proc) {
                            Slog.w(TAG, "Starting new heavy weight process " + proc
                                    + " when already running "
                                    + mService.mHeavyWeightProcess);
                        }
                        mService.setHeavyWeightProcess(r);
                    }
                }

            } catch (RemoteException e) {
                if (r.launchFailed) {
                    // This is the second time we failed -- finish activity and give up.
                    Slog.e(TAG, "Second failure launching "
                            + r.intent.getComponent().flattenToShortString() + ", giving up", e);
                    proc.appDied();
                    stack.requestFinishActivityLocked(r.appToken, Activity.RESULT_CANCELED, null,
                            "2nd-crash", false);
                    return false;
                }

                // This is the first time we failed -- restart process and
                // retry.
                r.launchFailed = true;
                proc.removeActivity(r);
                throw e;
            }
        } finally {
            endDeferResume();
        }

        r.launchFailed = false;
        if (stack.updateLRUListLocked(r)) {
            Slog.w(TAG, "Activity " + r + " being launched, but already in LRU list");
        }

        // TODO(lifecycler): Resume or pause requests are done as part of launch transaction,
        // so updating the state should be done accordingly.
        if (andResume && readyToResume()) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            stack.minimalResumeActivityLocked(r);
        } else {
            // This activity is not starting in the resumed state... which should look like we asked
            // it to pause+stop (but remain visible), and it has done so and reported back the
            // current icicle and other state.
            if (DEBUG_STATES) Slog.v(TAG_STATES,
                    "Moving to PAUSED: " + r + " (starting in paused state)");
            r.setState(PAUSED, "realStartActivityLocked");
        }

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        if (mRootActivityContainer.isTopDisplayFocusedStack(stack)) {
            mService.getActivityStartController().startSetupActivity();
        }

        // Update any services we are bound to that might care about whether
        // their client may have activities.
        if (r.app != null) {
            r.app.updateServiceConnectionActivities();
        }

        return true;
    }

    void updateHomeProcess(WindowProcessController app) {
        if (app != null && mService.mHomeProcess != app) {
            if (!mHandler.hasMessages(REPORT_HOME_CHANGED_MSG)) {
                mHandler.sendEmptyMessage(REPORT_HOME_CHANGED_MSG);
            }
            mService.mHomeProcess = app;
        }
    }

    private void logIfTransactionTooLarge(Intent intent, Bundle icicle) {
        int extrasSize = 0;
        if (intent != null) {
            final Bundle extras = intent.getExtras();
            if (extras != null) {
                extrasSize = extras.getSize();
            }
        }
        int icicleSize = (icicle == null ? 0 : icicle.getSize());
        if (extrasSize + icicleSize > 200000) {
            Slog.e(TAG, "Transaction too large, intent: " + intent + ", extras size: " + extrasSize
                    + ", icicle size: " + icicleSize);
        }
    }

    void startSpecificActivityLocked(ActivityRecord r, boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        final WindowProcessController wpc =
                mService.getProcessController(r.processName, r.info.applicationInfo.uid);

        boolean knownToBeDead = false;
        if (wpc != null && wpc.hasThread()) {
            try {
                if ((r.info.flags & ActivityInfo.FLAG_MULTIPROCESS) == 0
                        || !"android".equals(r.info.packageName)) {
                    // Don't add this if it is a platform component that is marked to run in
                    // multiple processes, because this is actually part of the framework so doesn't
                    // make sense to track as a separate apk in the process.
                    wpc.addPackage(r.info.packageName, r.info.applicationInfo.longVersionCode);
                }
                realStartActivityLocked(r, wpc, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
            knownToBeDead = true;
        }

        // Suppress transition until the new activity becomes ready, otherwise the keyguard can
        // appear for a short amount of time before the new process with the new activity had the
        // ability to set its showWhenLocked flags.
        if (getKeyguardController().isKeyguardLocked()) {
            r.notifyUnknownVisibilityLaunched();
        }

        try {
            if (Trace.isTagEnabled(TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "dispatchingStartProcess:"
                        + r.processName);
            }
            // Post message to start process to avoid possible deadlock of calling into AMS with the
            // ATMS lock held.
            final Message msg = PooledLambda.obtainMessage(
                    ActivityManagerInternal::startProcess, mService.mAmInternal, r.processName,
                    r.info.applicationInfo, knownToBeDead, "activity", r.intent.getComponent());
            mService.mH.sendMessage(msg);
        } finally {
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    boolean checkStartAnyActivityPermission(Intent intent, ActivityInfo aInfo, String resultWho,
            int requestCode, int callingPid, int callingUid, String callingPackage,
            boolean ignoreTargetSecurity, boolean launchingInTask,
            WindowProcessController callerApp, ActivityRecord resultRecord,
            ActivityStack resultStack) {
        final boolean isCallerRecents = mService.getRecentTasks() != null
                && mService.getRecentTasks().isCallerRecents(callingUid);
        final int startAnyPerm = mService.checkPermission(START_ANY_ACTIVITY, callingPid,
                callingUid);
        if (startAnyPerm == PERMISSION_GRANTED || (isCallerRecents && launchingInTask)) {
            // If the caller has START_ANY_ACTIVITY, ignore all checks below. In addition, if the
            // caller is the recents component and we are specifically starting an activity in an
            // existing task, then also allow the activity to be fully relaunched.
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

        return true;
    }

    /** Check if caller is allowed to launch activities on specified display. */
    boolean isCallerAllowedToLaunchOnDisplay(int callingPid, int callingUid, int launchDisplayId,
            ActivityInfo aInfo) {
        if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check: displayId=" + launchDisplayId
                + " callingPid=" + callingPid + " callingUid=" + callingUid);

        if (callingPid == -1 && callingUid == -1) {
            if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check: no caller info, skip check");
            return true;
        }

        final ActivityDisplay activityDisplay =
                mRootActivityContainer.getActivityDisplayOrCreate(launchDisplayId);
        if (activityDisplay == null || activityDisplay.isRemoved()) {
            Slog.w(TAG, "Launch on display check: display not found");
            return false;
        }

        // Check if the caller has enough privileges to embed activities and launch to private
        // displays.
        final int startAnyPerm = mService.checkPermission(INTERNAL_SYSTEM_WINDOW, callingPid,
                callingUid);
        if (startAnyPerm == PERMISSION_GRANTED) {
            if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check:"
                    + " allow launch any on display");
            return true;
        }

        // Check if caller is already present on display
        final boolean uidPresentOnDisplay = activityDisplay.isUidPresent(callingUid);

        final int displayOwnerUid = activityDisplay.mDisplay.getOwnerUid();
        if (activityDisplay.mDisplay.getType() == TYPE_VIRTUAL && displayOwnerUid != SYSTEM_UID
                && displayOwnerUid != aInfo.applicationInfo.uid) {
            // Limit launching on virtual displays, because their contents can be read from Surface
            // by apps that created them.
            if ((aInfo.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) == 0) {
                if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check:"
                        + " disallow launch on virtual display for not-embedded activity.");
                return false;
            }
            // Check if the caller is allowed to embed activities from other apps.
            if (mService.checkPermission(ACTIVITY_EMBEDDING, callingPid, callingUid)
                    == PERMISSION_DENIED && !uidPresentOnDisplay) {
                if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check:"
                        + " disallow activity embedding without permission.");
                return false;
            }
        }

        if (!activityDisplay.isPrivate()) {
            // Anyone can launch on a public display.
            if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check:"
                    + " allow launch on public display");
            return true;
        }

        // Check if the caller is the owner of the display.
        if (displayOwnerUid == callingUid) {
            if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check:"
                    + " allow launch for owner of the display");
            return true;
        }

        if (uidPresentOnDisplay) {
            if (DEBUG_TASKS) Slog.d(TAG, "Launch on display check:"
                    + " allow launch for caller present on the display");
            return true;
        }

        Slog.w(TAG, "Launch on display check: denied");
        return false;
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
                == PERMISSION_DENIED) {
            return ACTIVITY_RESTRICTION_PERMISSION;
        }

        if (activityInfo.permission == null) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        final int opCode = AppOpsManager.permissionToOpCode(activityInfo.permission);
        if (opCode == AppOpsManager.OP_NONE) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        if (mService.getAppOpsService().noteOperation(opCode, callingUid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
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

        if (mService.checkPermission(permission, callingPid, callingUid) == PERMISSION_DENIED) {
            return ACTIVITY_RESTRICTION_PERMISSION;
        }

        final int opCode = AppOpsManager.permissionToOpCode(permission);
        if (opCode == AppOpsManager.OP_NONE) {
            return ACTIVITY_RESTRICTION_NONE;
        }

        if (mService.getAppOpsService().noteOperation(opCode, callingUid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            return ACTIVITY_RESTRICTION_APPOP;
        }

        return ACTIVITY_RESTRICTION_NONE;
    }

    void setLaunchSource(int uid) {
        mLaunchingActivityWakeLock.setWorkSource(new WorkSource(uid));
    }

    void acquireLaunchWakelock() {
        if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != Process.myUid()) {
            throw new IllegalStateException("Calling must be system uid");
        }
        mLaunchingActivityWakeLock.acquire();
        if (!mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
            // To be safe, don't allow the wake lock to be held for too long.
            mHandler.sendEmptyMessageDelayed(LAUNCH_TIMEOUT_MSG, LAUNCH_TIMEOUT);
        }
    }

    /**
     * Called when all resumed tasks/stacks are idle.
     * @return the state of mService.mAm.mBooting before this was called.
     */
    @GuardedBy("mService")
    private boolean checkFinishBootingLocked() {
        final boolean booting = mService.isBooting();
        boolean enableScreen = false;
        mService.setBooting(false);
        if (!mService.isBooted()) {
            mService.setBooted(true);
            enableScreen = true;
        }
        if (booting || enableScreen) {
            mService.postFinishBooting(booting, enableScreen);
        }
        return booting;
    }

    // Checked.
    @GuardedBy("mService")
    final ActivityRecord activityIdleInternalLocked(final IBinder token, boolean fromTimeout,
            boolean processPausingActivities, Configuration config) {
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
                reportActivityLaunchedLocked(fromTimeout, r, INVALID_DELAY,
                        -1 /* launchState */);
            }

            // This is a hack to semi-deal with a race condition
            // in the client where it can be constructed with a
            // newer configuration from when we asked it to launch.
            // We'll update with whatever configuration it now says
            // it used to launch.
            if (config != null) {
                r.setLastReportedGlobalConfiguration(config);
            }

            // We are now idle.  If someone is waiting for a thumbnail from
            // us, we can now deliver.
            r.idle = true;

            //Slog.i(TAG, "IDLE: mBooted=" + mBooted + ", fromTimeout=" + fromTimeout);

            // Check if able to finish booting when device is booting and all resumed activities
            // are idle.
            if ((mService.isBooting() && mRootActivityContainer.allResumedActivitiesIdle())
                    || fromTimeout) {
                booting = checkFinishBootingLocked();
            }

            // When activity is idle, we consider the relaunch must be successful, so let's clear
            // the flag.
            r.mRelaunchReason = RELAUNCH_REASON_NONE;
        }

        if (mRootActivityContainer.allResumedActivitiesIdle()) {
            if (r != null) {
                mService.scheduleAppGcsLocked();
            }

            if (mLaunchingActivityWakeLock.isHeld()) {
                mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                if (VALIDATE_WAKE_LOCK_CALLER &&
                        Binder.getCallingUid() != Process.myUid()) {
                    throw new IllegalStateException("Calling must be system uid");
                }
                mLaunchingActivityWakeLock.release();
            }
            mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
        }

        // Atomically retrieve all of the other things to do.
        final ArrayList<ActivityRecord> stops = processStoppingActivitiesLocked(r,
                true /* remove */, processPausingActivities);
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
            final ActivityStack stack = r.getActivityStack();
            if (stack != null) {
                if (r.finishing) {
                    stack.finishCurrentActivityLocked(r, ActivityStack.FINISH_IMMEDIATELY, false,
                            "activityIdleInternalLocked");
                } else {
                    stack.stopActivityLocked(r);
                }
            }
        }

        // Finish any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (int i = 0; i < NF; i++) {
            r = finishes.get(i);
            final ActivityStack stack = r.getActivityStack();
            if (stack != null) {
                activityRemoved |= stack.destroyActivityLocked(r, true, "finish-idle");
            }
        }

        if (!booting) {
            // Complete user switch
            if (startingUsers != null) {
                for (int i = 0; i < startingUsers.size(); i++) {
                    mService.mAmInternal.finishUserSwitch(startingUsers.get(i));
                }
            }
        }

        mService.mH.post(() -> mService.mAmInternal.trimApplications());
        //dump();
        //mWindowManager.dump();

        if (activityRemoved) {
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        }

        return r;
    }

    /** This doesn't just find a task, it also moves the task to front. */
    void findTaskToMoveToFront(TaskRecord task, int flags, ActivityOptions options, String reason,
            boolean forceNonResizeable) {
        ActivityStack currentStack = task.getStack();
        if (currentStack == null) {
            Slog.e(TAG, "findTaskToMoveToFront: can't move task="
                    + task + " to front. Stack is null");
            return;
        }

        if ((flags & ActivityManager.MOVE_TASK_NO_USER_ACTION) == 0) {
            mUserLeaving = true;
        }

        reason = reason + " findTaskToMoveToFront";
        boolean reparented = false;
        if (task.isResizeable() && canUseActivityOptionsLaunchBounds(options)) {
            final Rect bounds = options.getLaunchBounds();
            task.updateOverrideConfiguration(bounds);

            ActivityStack stack =
                    mRootActivityContainer.getLaunchStack(null, options, task, ON_TOP);

            if (stack != currentStack) {
                moveHomeStackToFrontIfNeeded(flags, stack.getDisplay(), reason);
                task.reparent(stack, ON_TOP, REPARENT_KEEP_STACK_AT_FRONT, !ANIMATE, DEFER_RESUME,
                        reason);
                currentStack = stack;
                reparented = true;
                // task.reparent() should already placed the task on top,
                // still need moveTaskToFrontLocked() below for any transition settings.
            }
            if (stack.resizeStackWithLaunchBounds()) {
                mRootActivityContainer.resizeStack(stack, bounds, null /* tempTaskBounds */,
                        null /* tempTaskInsetBounds */, !PRESERVE_WINDOWS,
                        true /* allowResizeInDockedMode */, !DEFER_RESUME);
            } else {
                // WM resizeTask must be done after the task is moved to the correct stack,
                // because Task's setBounds() also updates dim layer's bounds, but that has
                // dependency on the stack.
                task.resizeWindowContainer();
            }
        }

        if (!reparented) {
            moveHomeStackToFrontIfNeeded(flags, currentStack.getDisplay(), reason);
        }

        final ActivityRecord r = task.getTopActivity();
        currentStack.moveTaskToFrontLocked(task, false /* noAnimation */, options,
                r == null ? null : r.appTimeTracker, reason);

        if (DEBUG_STACK) Slog.d(TAG_STACK,
                "findTaskToMoveToFront: moved to front of stack=" + currentStack);

        handleNonResizableTaskIfNeeded(task, WINDOWING_MODE_UNDEFINED, DEFAULT_DISPLAY,
                currentStack, forceNonResizeable);
    }

    private void moveHomeStackToFrontIfNeeded(int flags, ActivityDisplay display, String reason) {
        final ActivityStack focusedStack = display.getFocusedStack();

        if ((display.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && (flags & ActivityManager.MOVE_TASK_WITH_HOME) != 0)
                || (focusedStack != null && focusedStack.isActivityTypeRecents())) {
            // We move home stack to front when we are on a fullscreen display and caller has
            // requested the home activity to move with it. Or the previous stack is recents.
            display.moveHomeStackToFront(reason);
        }
    }

    boolean canUseActivityOptionsLaunchBounds(ActivityOptions options) {
        // We use the launch bounds in the activity options is the device supports freeform
        // window management or is launching into the pinned stack.
        if (options == null || options.getLaunchBounds() == null) {
            return false;
        }
        return (mService.mSupportsPictureInPicture
                && options.getLaunchWindowingMode() == WINDOWING_MODE_PINNED)
                || mService.mSupportsFreeformWindowManagement;
    }

    LaunchParamsController getLaunchParamsController() {
        return mLaunchParamsController;
    }

    private void deferUpdateRecentsHomeStackBounds() {
        mRootActivityContainer.deferUpdateBounds(ACTIVITY_TYPE_RECENTS);
        mRootActivityContainer.deferUpdateBounds(ACTIVITY_TYPE_HOME);
    }

    private void continueUpdateRecentsHomeStackBounds() {
        mRootActivityContainer.continueUpdateBounds(ACTIVITY_TYPE_RECENTS);
        mRootActivityContainer.continueUpdateBounds(ACTIVITY_TYPE_HOME);
    }

    void notifyAppTransitionDone() {
        continueUpdateRecentsHomeStackBounds();
        for (int i = mResizingTasksDuringAnimation.size() - 1; i >= 0; i--) {
            final int taskId = mResizingTasksDuringAnimation.valueAt(i);
            final TaskRecord task =
                    mRootActivityContainer.anyTaskForId(taskId, MATCH_TASK_IN_STACKS_ONLY);
            if (task != null) {
                task.setTaskDockedResizing(false);
            }
        }
        mResizingTasksDuringAnimation.clear();
    }

    /**
     * TODO: This should just change the windowing mode and resize vs. actually moving task around.
     * Can do that once we are no longer using static stack ids.
     */
    private void moveTasksToFullscreenStackInSurfaceTransaction(ActivityStack fromStack,
            int toDisplayId, boolean onTop) {

        mWindowManager.deferSurfaceLayout();
        try {
            final int windowingMode = fromStack.getWindowingMode();
            final boolean inPinnedWindowingMode = windowingMode == WINDOWING_MODE_PINNED;
            final ActivityDisplay toDisplay =
                    mRootActivityContainer.getActivityDisplay(toDisplayId);

            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                // Tell the display we are exiting split-screen mode.
                toDisplay.onExitingSplitScreenMode();
                // We are moving all tasks from the docked stack to the fullscreen stack,
                // which is dismissing the docked stack, so resize all other stacks to
                // fullscreen here already so we don't end up with resize trashing.
                for (int i = toDisplay.getChildCount() - 1; i >= 0; --i) {
                    final ActivityStack otherStack = toDisplay.getChildAt(i);
                    if (!otherStack.inSplitScreenSecondaryWindowingMode()) {
                        continue;
                    }
                    otherStack.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                }

                // Also disable docked stack resizing since we have manually adjusted the
                // size of other stacks above and we don't want to trigger a docked stack
                // resize when we remove task from it below and it is detached from the
                // display because it no longer contains any tasks.
                mAllowDockedStackResize = false;
            }

            // If we are moving from the pinned stack, then the animation takes care of updating
            // the picture-in-picture mode.
            final boolean schedulePictureInPictureModeChange = inPinnedWindowingMode;
            final ArrayList<TaskRecord> tasks = fromStack.getAllTasks();

            if (!tasks.isEmpty()) {
                mTmpOptions.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
                final int size = tasks.size();
                for (int i = 0; i < size; ++i) {
                    final TaskRecord task = tasks.get(i);
                    final ActivityStack toStack = toDisplay.getOrCreateStack(
                                null, mTmpOptions, task, task.getActivityType(), onTop);

                    if (onTop) {
                        final boolean isTopTask = i == (size - 1);
                        // Defer resume until all the tasks have been moved to the fullscreen stack
                        task.reparent(toStack, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT,
                                isTopTask /* animate */, DEFER_RESUME,
                                schedulePictureInPictureModeChange,
                                "moveTasksToFullscreenStack - onTop");
                        MetricsLoggerWrapper.logPictureInPictureFullScreen(mService.mContext,
                                task.effectiveUid, task.realActivity.flattenToString());
                    } else {
                        // Position the tasks in the fullscreen stack in order at the bottom of the
                        // stack. Also defer resume until all the tasks have been moved to the
                        // fullscreen stack.
                        task.reparent(toStack, ON_TOP,
                                REPARENT_LEAVE_STACK_IN_PLACE, !ANIMATE, DEFER_RESUME,
                                schedulePictureInPictureModeChange,
                                "moveTasksToFullscreenStack - NOT_onTop");
                    }
                }
            }

            mRootActivityContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
            mRootActivityContainer.resumeFocusedStacksTopActivities();
        } finally {
            mAllowDockedStackResize = true;
            mWindowManager.continueSurfaceLayout();
        }
    }

    void moveTasksToFullscreenStackLocked(ActivityStack fromStack, boolean onTop) {
        moveTasksToFullscreenStackLocked(fromStack, DEFAULT_DISPLAY, onTop);
    }

    void moveTasksToFullscreenStackLocked(ActivityStack fromStack, int toDisplayId, boolean onTop) {
        mWindowManager.inSurfaceTransaction(() ->
                moveTasksToFullscreenStackInSurfaceTransaction(fromStack, toDisplayId, onTop));
    }

    void setSplitScreenResizing(boolean resizing) {
        if (resizing == mDockedStackResizing) {
            return;
        }

        mDockedStackResizing = resizing;
        mWindowManager.setDockedStackResizing(resizing);

        if (!resizing && mHasPendingDockedBounds) {
            resizeDockedStackLocked(mPendingDockedBounds, mPendingTempDockedTaskBounds,
                    mPendingTempDockedTaskInsetBounds, mPendingTempOtherTaskBounds,
                    mPendingTempOtherTaskInsetBounds, PRESERVE_WINDOWS);

            mHasPendingDockedBounds = false;
            mPendingDockedBounds = null;
            mPendingTempDockedTaskBounds = null;
            mPendingTempDockedTaskInsetBounds = null;
            mPendingTempOtherTaskBounds = null;
            mPendingTempOtherTaskInsetBounds = null;
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

        final ActivityStack stack =
                mRootActivityContainer.getDefaultDisplay().getSplitScreenPrimaryStack();
        if (stack == null) {
            Slog.w(TAG, "resizeDockedStackLocked: docked stack not found");
            return;
        }

        if (mDockedStackResizing) {
            mHasPendingDockedBounds = true;
            mPendingDockedBounds = copyOrNull(dockedBounds);
            mPendingTempDockedTaskBounds = copyOrNull(tempDockedTaskBounds);
            mPendingTempDockedTaskInsetBounds = copyOrNull(tempDockedTaskInsetBounds);
            mPendingTempOtherTaskBounds = copyOrNull(tempOtherTaskBounds);
            mPendingTempOtherTaskInsetBounds = copyOrNull(tempOtherTaskInsetBounds);
        }

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizeDockedStack");
        mWindowManager.deferSurfaceLayout();
        try {
            // Don't allow re-entry while resizing. E.g. due to docked stack detaching.
            mAllowDockedStackResize = false;
            ActivityRecord r = stack.topRunningActivityLocked();
            stack.resize(dockedBounds, tempDockedTaskBounds, tempDockedTaskInsetBounds);

            // TODO: Checking for isAttached might not be needed as if the user passes in null
            // dockedBounds then they want the docked stack to be dismissed.
            if (stack.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                    || (dockedBounds == null && !stack.isAttached())) {
                // The dock stack either was dismissed or went fullscreen, which is kinda the same.
                // In this case we make all other static stacks fullscreen and move all
                // docked stack tasks to the fullscreen stack.
                moveTasksToFullscreenStackLocked(stack, ON_TOP);

                // stack shouldn't contain anymore activities, so nothing to resume.
                r = null;
            } else {
                // Docked stacks occupy a dedicated region on screen so the size of all other
                // static stacks need to be adjusted so they don't overlap with the docked stack.
                // We get the bounds to use from window manager which has been adjusted for any
                // screen controls and is also the same for all stacks.
                final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
                final Rect otherTaskRect = new Rect();
                for (int i = display.getChildCount() - 1; i >= 0; --i) {
                    final ActivityStack current = display.getChildAt(i);
                    if (!current.inSplitScreenSecondaryWindowingMode()) {
                        continue;
                    }
                    if (!current.affectedBySplitScreenResize()) {
                        continue;
                    }
                    if (mDockedStackResizing && !current.isTopActivityVisible()) {
                        // Non-visible stacks get resized once we're done with the resize
                        // interaction.
                        continue;
                    }
                    current.getStackDockedModeBounds(dockedBounds,
                            tempOtherTaskBounds /* currentTempTaskBounds */,
                            tempRect /* outStackBounds */,
                            otherTaskRect /* outTempTaskBounds */);

                    mRootActivityContainer.resizeStack(current,
                            !tempRect.isEmpty() ? tempRect : null,
                            !otherTaskRect.isEmpty() ? otherTaskRect : tempOtherTaskBounds,
                            tempOtherTaskInsetBounds, preserveWindows,
                            true /* allowResizeInDockedMode */, deferResume);
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
    }

    void resizePinnedStackLocked(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        // TODO(multi-display): The display containing the stack should be passed in.
        final ActivityStack stack =
                mRootActivityContainer.getDefaultDisplay().getPinnedStack();
        if (stack == null) {
            Slog.w(TAG, "resizePinnedStackLocked: pinned stack not found");
            return;
        }

        // It is possible for the bounds animation from the WM to call this but be delayed by
        // another AM call that is holding the AMS lock. In such a case, the pinnedBounds may be
        // incorrect if AMS.resizeStackWithBoundsFromWindowManager() is already called while waiting
        // for the AMS lock to be freed. So check and make sure these bounds are still good.
        final TaskStack stackController = stack.getTaskStack();
        if (stackController.pinnedStackResizeDisallowed()) {
            return;
        }

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizePinnedStack");
        mWindowManager.deferSurfaceLayout();
        try {
            ActivityRecord r = stack.topRunningActivityLocked();
            Rect insetBounds = null;
            if (tempPinnedTaskBounds != null && stack.isAnimatingBoundsToFullscreen()) {
                // Use 0,0 as the position for the inset rect because we are headed for fullscreen.
                insetBounds = tempRect;
                insetBounds.top = 0;
                insetBounds.left = 0;
                insetBounds.right = tempPinnedTaskBounds.width();
                insetBounds.bottom = tempPinnedTaskBounds.height();
            }
            if (pinnedBounds != null && tempPinnedTaskBounds == null) {
                // We have finished the animation into PiP, and are resizing the tasks to match the
                // stack bounds, while layouts are deferred, update any task state as a part of
                // transitioning it from fullscreen into a floating state.
                stack.onPipAnimationEndResize();
            }
            stack.resize(pinnedBounds, tempPinnedTaskBounds, insetBounds);
            stack.ensureVisibleActivitiesConfigurationLocked(r, false);
        } finally {
            mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private void removeStackInSurfaceTransaction(ActivityStack stack) {
        final ArrayList<TaskRecord> tasks = stack.getAllTasks();
        if (stack.getWindowingMode() == WINDOWING_MODE_PINNED) {
            /**
             * Workaround: Force-stop all the activities in the pinned stack before we reparent them
             * to the fullscreen stack.  This is to guarantee that when we are removing a stack,
             * that the client receives onStop() before it is reparented.  We do this by detaching
             * the stack from the display so that it will be considered invisible when
             * ensureActivitiesVisibleLocked() is called, and all of its activitys will be marked
             * invisible as well and added to the stopping list.  After which we process the
             * stopping list by handling the idle.
             */
            stack.mForceHidden = true;
            stack.ensureActivitiesVisibleLocked(null, 0, PRESERVE_WINDOWS);
            stack.mForceHidden = false;
            activityIdleInternalLocked(null, false /* fromTimeout */,
                    true /* processPausingActivites */, null /* configuration */);

            // Move all the tasks to the bottom of the fullscreen stack
            moveTasksToFullscreenStackLocked(stack, !ON_TOP);
        } else {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                removeTaskByIdLocked(tasks.get(i).taskId, true /* killProcess */,
                        REMOVE_FROM_RECENTS, "remove-stack");
            }
        }
    }

    /**
     * Removes the stack associated with the given {@param stack}. If the {@param stack} is the
     * pinned stack, then its tasks are not explicitly removed when the stack is destroyed, but
     * instead moved back onto the fullscreen stack.
     */
    void removeStack(ActivityStack stack) {
        mWindowManager.inSurfaceTransaction(() -> removeStackInSurfaceTransaction(stack));
    }

    /**
     * See {@link #removeTaskByIdLocked(int, boolean, boolean, boolean)}
     */
    boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents,
            String reason) {
        return removeTaskByIdLocked(taskId, killProcess, removeFromRecents, !PAUSE_IMMEDIATELY,
                reason);
    }

    /**
     * Removes the task with the specified task id.
     *
     * @param taskId Identifier of the task to be removed.
     * @param killProcess Kill any process associated with the task if possible.
     * @param removeFromRecents Whether to also remove the task from recents.
     * @param pauseImmediately Pauses all task activities immediately without waiting for the
     *                         pause-complete callback from the activity.
     * @return Returns true if the given task was found and removed.
     */
    boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents,
            boolean pauseImmediately, String reason) {
        final TaskRecord tr =
                mRootActivityContainer.anyTaskForId(taskId, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
        if (tr != null) {
            tr.removeTaskActivitiesLocked(pauseImmediately, reason);
            cleanUpRemovedTaskLocked(tr, killProcess, removeFromRecents);
            mService.getLockTaskController().clearLockedTask(tr);
            if (tr.isPersistable) {
                mService.notifyTaskPersisterLocked(null, true);
            }
            return true;
        }
        Slog.w(TAG, "Request to remove task ignored for non-existent task " + taskId);
        return false;
    }

    void cleanUpRemovedTaskLocked(TaskRecord tr, boolean killProcess, boolean removeFromRecents) {
        if (removeFromRecents) {
            mRecentTasks.remove(tr);
        }
        ComponentName component = tr.getBaseIntent().getComponent();
        if (component == null) {
            Slog.w(TAG, "No component for base intent of task: " + tr);
            return;
        }

        // Find any running services associated with this app and stop if needed.
        final Message msg = PooledLambda.obtainMessage(ActivityManagerInternal::cleanUpServices,
                mService.mAmInternal, tr.userId, component, new Intent(tr.getBaseIntent()));
        mService.mH.sendMessage(msg);

        if (!killProcess) {
            return;
        }

        // Determine if the process(es) for this task should be killed.
        final String pkg = component.getPackageName();
        ArrayList<Object> procsToKill = new ArrayList<>();
        ArrayMap<String, SparseArray<WindowProcessController>> pmap =
                mService.mProcessNames.getMap();
        for (int i = 0; i < pmap.size(); i++) {

            SparseArray<WindowProcessController> uids = pmap.valueAt(i);
            for (int j = 0; j < uids.size(); j++) {
                WindowProcessController proc = uids.valueAt(j);
                if (proc.mUserId != tr.userId) {
                    // Don't kill process for a different user.
                    continue;
                }
                if (proc == mService.mHomeProcess) {
                    // Don't kill the home process along with tasks from the same package.
                    continue;
                }
                if (!proc.mPkgList.contains(pkg)) {
                    // Don't kill process that is not associated with this task.
                    continue;
                }

                if (!proc.shouldKillProcessForRemovedTask(tr)) {
                    // Don't kill process(es) that has an activity in a different task that is also
                    // in recents, or has an activity not stopped.
                    return;
                }

                if (proc.hasForegroundServices()) {
                    // Don't kill process(es) with foreground service.
                    return;
                }

                // Add process to kill list.
                procsToKill.add(proc);
            }
        }

        // Kill the running processes. Post on handle since we don't want to hold the service lock
        // while calling into AM.
        final Message m = PooledLambda.obtainMessage(
                ActivityManagerInternal::killProcessesForRemovedTask, mService.mAmInternal,
                procsToKill);
        mService.mH.sendMessage(m);
    }

    /**
     * Called to restore the state of the task into the stack that it's supposed to go into.
     *
     * @param task The recent task to be restored.
     * @param aOptions The activity options to use for restoration.
     * @param onTop If the stack for the task should be the topmost on the display.
     * @return true if the task has been restored successfully.
     */
    boolean restoreRecentTaskLocked(TaskRecord task, ActivityOptions aOptions, boolean onTop) {
        final ActivityStack stack =
                mRootActivityContainer.getLaunchStack(null, aOptions, task, onTop);
        final ActivityStack currentStack = task.getStack();
        if (currentStack != null) {
            // Task has already been restored once. See if we need to do anything more
            if (currentStack == stack) {
                // Nothing else to do since it is already restored in the right stack.
                return true;
            }
            // Remove current stack association, so we can re-associate the task with the
            // right stack below.
            currentStack.removeTask(task, "restoreRecentTaskLocked", REMOVE_TASK_MODE_MOVING);
        }

        stack.addTask(task, onTop, "restoreRecentTask");
        // TODO: move call for creation here and other place into Stack.addTask()
        task.createTask(onTop, true /* showForAllUsers */);
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                "Added restored task=" + task + " to stack=" + stack);
        final ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
            activities.get(activityNdx).createAppWindowToken();
        }
        return true;
    }

    @Override
    public void onRecentTaskAdded(TaskRecord task) {
        task.touchActiveTime();
    }

    @Override
    public void onRecentTaskRemoved(TaskRecord task, boolean wasTrimmed, boolean killProcess) {
        if (wasTrimmed) {
            // Task was trimmed from the recent tasks list -- remove the active task record as well
            // since the user won't really be able to go back to it
            removeTaskByIdLocked(task.taskId, killProcess, false /* removeFromRecents */,
                    !PAUSE_IMMEDIATELY, "recent-task-trimmed");
        }
        task.removedFromRecents();
    }

    /**
     * Returns the reparent target stack, creating the stack if necessary.  This call also enforces
     * the various checks on tasks that are going to be reparented from one stack to another.
     */
    // TODO: Look into changing users to this method to ActivityDisplay.resolveWindowingMode()
    ActivityStack getReparentTargetStack(TaskRecord task, ActivityStack stack, boolean toTop) {
        final ActivityStack prevStack = task.getStack();
        final int stackId = stack.mStackId;
        final boolean inMultiWindowMode = stack.inMultiWindowMode();

        // Check that we aren't reparenting to the same stack that the task is already in
        if (prevStack != null && prevStack.mStackId == stackId) {
            Slog.w(TAG, "Can not reparent to same stack, task=" + task
                    + " already in stackId=" + stackId);
            return prevStack;
        }

        // Ensure that we aren't trying to move into a multi-window stack without multi-window
        // support
        if (inMultiWindowMode && !mService.mSupportsMultiWindow) {
            throw new IllegalArgumentException("Device doesn't support multi-window, can not"
                    + " reparent task=" + task + " to stack=" + stack);
        }

        // Ensure that we're not moving a task to a dynamic stack if device doesn't support
        // multi-display.
        if (stack.mDisplayId != DEFAULT_DISPLAY && !mService.mSupportsMultiDisplay) {
            throw new IllegalArgumentException("Device doesn't support multi-display, can not"
                    + " reparent task=" + task + " to stackId=" + stackId);
        }

        // Ensure that we aren't trying to move into a freeform stack without freeform support
        if (stack.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && !mService.mSupportsFreeformWindowManagement) {
            throw new IllegalArgumentException("Device doesn't support freeform, can not reparent"
                    + " task=" + task);
        }

        // Leave the task in its current stack or a fullscreen stack if it isn't resizeable and the
        // preferred stack is in multi-window mode.
        if (inMultiWindowMode && !task.isResizeable()) {
            Slog.w(TAG, "Can not move unresizeable task=" + task + " to multi-window stack=" + stack
                    + " Moving to a fullscreen stack instead.");
            if (prevStack != null) {
                return prevStack;
            }
            stack = stack.getDisplay().createStack(
                    WINDOWING_MODE_FULLSCREEN, stack.getActivityType(), toTop);
        }
        return stack;
    }

    void goingToSleepLocked() {
        scheduleSleepTimeout();
        if (!mGoingToSleepWakeLock.isHeld()) {
            mGoingToSleepWakeLock.acquire();
            if (mLaunchingActivityWakeLock.isHeld()) {
                if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != Process.myUid()) {
                    throw new IllegalStateException("Calling must be system uid");
                }
                mLaunchingActivityWakeLock.release();
                mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
            }
        }

        mRootActivityContainer.applySleepTokens(false /* applyToStacks */);

        checkReadyForSleepLocked(true /* allowDelay */);
    }

    boolean shutdownLocked(int timeout) {
        goingToSleepLocked();

        boolean timedout = false;
        final long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            if (!mRootActivityContainer.putStacksToSleep(
                    true /* allowDelay */, true /* shuttingDown */)) {
                long timeRemaining = endTime - System.currentTimeMillis();
                if (timeRemaining > 0) {
                    try {
                        mService.mGlobalLock.wait(timeRemaining);
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
        checkReadyForSleepLocked(false /* allowDelay */);

        return timedout;
    }

    void comeOutOfSleepIfNeededLocked() {
        removeSleepTimeouts();
        if (mGoingToSleepWakeLock.isHeld()) {
            mGoingToSleepWakeLock.release();
        }
    }

    void activitySleptLocked(ActivityRecord r) {
        mGoingToSleepActivities.remove(r);
        final ActivityStack s = r.getActivityStack();
        if (s != null) {
            s.checkReadyForSleep();
        } else {
            checkReadyForSleepLocked(true);
        }
    }

    void checkReadyForSleepLocked(boolean allowDelay) {
        if (!mService.isSleepingOrShuttingDownLocked()) {
            // Do not care.
            return;
        }

        if (!mRootActivityContainer.putStacksToSleep(
                allowDelay, false /* shuttingDown */)) {
            return;
        }

        // Send launch end powerhint before going sleep
        mRootActivityContainer.sendPowerHintForLaunchEndIfNeeded();

        removeSleepTimeouts();

        if (mGoingToSleepWakeLock.isHeld()) {
            mGoingToSleepWakeLock.release();
        }
        if (mService.mShuttingDown) {
            mService.mGlobalLock.notifyAll();
        }
    }

    boolean reportResumedActivityLocked(ActivityRecord r) {
        // A resumed activity cannot be stopping. remove from list
        mStoppingActivities.remove(r);

        final ActivityStack stack = r.getActivityStack();
        if (stack.getDisplay().allResumedActivitiesComplete()) {
            mRootActivityContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
            // Make sure activity & window visibility should be identical
            // for all displays in this stage.
            mRootActivityContainer.executeAppTransitionForAllDisplay();
            return true;
        }
        return false;
    }

    // Called when WindowManager has finished animating the launchingBehind activity to the back.
    private void handleLaunchTaskBehindCompleteLocked(ActivityRecord r) {
        final TaskRecord task = r.getTaskRecord();
        final ActivityStack stack = task.getStack();

        r.mLaunchTaskBehind = false;
        mRecentTasks.add(task);
        mService.getTaskChangeNotificationController().notifyTaskStackChanged();
        r.setVisibility(false);

        // When launching tasks behind, update the last active time of the top task after the new
        // task has been shown briefly
        final ActivityRecord top = stack.getTopActivity();
        if (top != null) {
            top.getTaskRecord().touchActiveTime();
        }
    }

    void scheduleLaunchTaskBehindComplete(IBinder token) {
        mHandler.obtainMessage(LAUNCH_TASK_BEHIND_COMPLETE, token).sendToTarget();
    }

    /** Checks whether the userid is a profile of the current user. */
    boolean isCurrentProfileLocked(int userId) {
        if (userId == mRootActivityContainer.mCurrentUser) return true;
        return mService.mAmInternal.isCurrentProfile(userId);
    }

    /**
     * Returns whether a stopping activity is present that should be stopped after visible, rather
     * than idle.
     * @return {@code true} if such activity is present. {@code false} otherwise.
     */
    boolean isStoppingNoHistoryActivity() {
        // Activities that are marked as nohistory should be stopped immediately after the resumed
        // activity has become visible.
        for (ActivityRecord record : mStoppingActivities) {
            if (record.isNoHistory()) {
                return true;
            }
        }

        return false;
    }

    final ArrayList<ActivityRecord> processStoppingActivitiesLocked(ActivityRecord idleActivity,
            boolean remove, boolean processPausingActivities) {
        ArrayList<ActivityRecord> stops = null;

        final boolean nowVisible = mRootActivityContainer.allResumedActivitiesVisible();
        for (int activityNdx = mStoppingActivities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord s = mStoppingActivities.get(activityNdx);

            final boolean animating = s.mAppWindowToken.isSelfAnimating();

            if (DEBUG_STATES) Slog.v(TAG, "Stopping " + s + ": nowVisible=" + nowVisible
                    + " animating=" + animating + " finishing=" + s.finishing);
            if (nowVisible && s.finishing) {

                // If this activity is finishing, it is sitting on top of
                // everyone else but we now know it is no longer needed...
                // so get rid of it.  Otherwise, we need to go through the
                // normal flow and hide it once we determine that it is
                // hidden by the activities in front of it.
                if (DEBUG_STATES) Slog.v(TAG, "Before stopping, can hide: " + s);
                s.setVisibility(false);
            }
            if (remove) {
                final ActivityStack stack = s.getActivityStack();
                final boolean shouldSleepOrShutDown = stack != null
                        ? stack.shouldSleepOrShutDownActivities()
                        : mService.isSleepingOrShuttingDownLocked();
                if (!animating || shouldSleepOrShutDown) {
                    if (!processPausingActivities && s.isState(PAUSING)) {
                        // Defer processing pausing activities in this iteration and reschedule
                        // a delayed idle to reprocess it again
                        removeTimeoutsForActivityLocked(idleActivity);
                        scheduleIdleTimeoutLocked(idleActivity);
                        continue;
                    }

                    if (DEBUG_STATES) Slog.v(TAG, "Ready to stop: " + s);
                    if (stops == null) {
                        stops = new ArrayList<>();
                    }
                    stops.add(s);

                    mStoppingActivities.remove(activityNdx);
                }
            }
        }

        return stops;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println();
        pw.println("ActivityStackSupervisor state:");
        mRootActivityContainer.dump(pw, prefix);
        pw.print(prefix);
        pw.println("mCurTaskIdForUser=" + mCurTaskIdForUser);
        pw.println(prefix + "mUserStackInFront=" + mRootActivityContainer.mUserStackInFront);
        if (!mWaitingForActivityVisible.isEmpty()) {
            pw.println(prefix + "mWaitingForActivityVisible=");
            for (int i = 0; i < mWaitingForActivityVisible.size(); ++i) {
                pw.print(prefix + prefix); mWaitingForActivityVisible.get(i).dump(pw, prefix);
            }
        }
        pw.print(prefix); pw.print("isHomeRecentsComponent=");
        pw.print(mRecentTasks.isRecentsComponentHomeActivity(mRootActivityContainer.mCurrentUser));

        getKeyguardController().dump(pw, prefix);
        mService.getLockTaskController().dump(pw, prefix);
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

    static boolean dumpHistoryList(FileDescriptor fd, PrintWriter pw, List<ActivityRecord> list,
            String prefix, String label, boolean complete, boolean brief, boolean client,
            String dumpPackage, boolean needNL, String header, TaskRecord lastTask) {
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
            if (header != null) {
                pw.println(header);
                header = null;
            }
            if (lastTask != r.getTaskRecord()) {
                lastTask = r.getTaskRecord();
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
            if (client && r.attachedToProcess()) {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.app.getThread().dumpActivity(
                                tp.getWriteFd(), r.appToken, innerPrefix, args);
                        // Short timeout, since blocking here can deadlock with the application.
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

    /**
     * Updates the record of top resumed activity when it changes and handles reporting of the
     * state changes to previous and new top activities. It will immediately dispatch top resumed
     * state loss message to previous top activity (if haven't done it already). After the previous
     * activity releases the top state and reports back, message about acquiring top state will be
     * sent to the new top resumed activity.
     */
    void updateTopResumedActivityIfNeeded() {
        final ActivityRecord prevTopActivity = mTopResumedActivity;
        final ActivityStack topStack = mRootActivityContainer.getTopDisplayFocusedStack();
        if (topStack == null || topStack.mResumedActivity == prevTopActivity) {
            return;
        }

        // Ask previous activity to release the top state.
        final boolean prevActivityReceivedTopState =
                prevTopActivity != null && !mTopResumedActivityWaitingForPrev;
        // mTopResumedActivityWaitingForPrev == true at this point would mean that an activity
        // before the prevTopActivity one hasn't reported back yet. So server never sent the top
        // resumed state change message to prevTopActivity.
        if (prevActivityReceivedTopState) {
            prevTopActivity.scheduleTopResumedActivityChanged(false /* onTop */);
            scheduleTopResumedStateLossTimeout(prevTopActivity);
            mTopResumedActivityWaitingForPrev = true;
        }

        // Update the current top activity.
        mTopResumedActivity = topStack.mResumedActivity;
        scheduleTopResumedActivityStateIfNeeded();
    }

    /** Schedule top resumed state change if previous top activity already reported back. */
    private void scheduleTopResumedActivityStateIfNeeded() {
        if (mTopResumedActivity != null && !mTopResumedActivityWaitingForPrev) {
            mTopResumedActivity.scheduleTopResumedActivityChanged(true /* onTop */);
        }
    }

    /**
     * Limit the time given to the app to report handling of the state loss.
     */
    private void scheduleTopResumedStateLossTimeout(ActivityRecord r) {
        final Message msg = mHandler.obtainMessage(TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG);
        msg.obj = r;
        r.topResumedStateLossTime = SystemClock.uptimeMillis();
        mHandler.sendMessageDelayed(msg, TOP_RESUMED_STATE_LOSS_TIMEOUT);
        if (DEBUG_STATES) Slog.v(TAG_STATES, "Waiting for top state to be released by " + r);
    }

    /**
     * Handle a loss of top resumed state by an activity - update internal state and inform next top
     * activity if needed.
     */
    void handleTopResumedStateReleased(boolean timeout) {
        if (DEBUG_STATES) {
            Slog.v(TAG_STATES, "Top resumed state released "
                    + (timeout ? " (due to timeout)" : " (transition complete)"));
        }
        mHandler.removeMessages(TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG);
        if (!mTopResumedActivityWaitingForPrev) {
            // Top resumed activity state loss already handled.
            return;
        }
        mTopResumedActivityWaitingForPrev = false;
        scheduleTopResumedActivityStateIfNeeded();
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
        mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
    }

    final void scheduleSleepTimeout() {
        removeSleepTimeouts();
        mHandler.sendEmptyMessageDelayed(SLEEP_TIMEOUT_MSG, SLEEP_TIMEOUT);
    }

    void removeRestartTimeouts(ActivityRecord r) {
        mHandler.removeMessages(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r);
    }

    final void scheduleRestartTimeout(ActivityRecord r) {
        removeRestartTimeouts(r);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r),
                WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION);
    }

    void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredWindowingMode,
            int preferredDisplayId, ActivityStack actualStack) {
        handleNonResizableTaskIfNeeded(task, preferredWindowingMode, preferredDisplayId,
                actualStack, false /* forceNonResizable */);
    }

    void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredWindowingMode,
            int preferredDisplayId, ActivityStack actualStack, boolean forceNonResizable) {
        final boolean isSecondaryDisplayPreferred =
                (preferredDisplayId != DEFAULT_DISPLAY && preferredDisplayId != INVALID_DISPLAY);
        final boolean inSplitScreenMode = actualStack != null
                && actualStack.getDisplay().hasSplitScreenPrimaryStack();
        if (((!inSplitScreenMode && preferredWindowingMode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)
                && !isSecondaryDisplayPreferred) || !task.isActivityTypeStandardOrUndefined()) {
            return;
        }

        // Handle incorrect launch/move to secondary display if needed.
        if (isSecondaryDisplayPreferred) {
            final int actualDisplayId = task.getStack().mDisplayId;
            if (!task.canBeLaunchedOnDisplay(actualDisplayId)) {
                throw new IllegalStateException("Task resolved to incompatible display");
            }

            final ActivityDisplay preferredDisplay =
                    mRootActivityContainer.getActivityDisplay(preferredDisplayId);

            final boolean singleTaskInstance = preferredDisplay != null
                    && preferredDisplay.isSingleTaskInstance();

            if (preferredDisplayId != actualDisplayId) {
                // Suppress the warning toast if the preferredDisplay was set to singleTask.
                // The singleTaskInstance displays will only contain one task and any attempt to
                // launch new task will re-route to the default display.
                if (singleTaskInstance) {
                    mService.getTaskChangeNotificationController()
                            .notifyActivityLaunchOnSecondaryDisplayRerouted(task.getTaskInfo(),
                                    preferredDisplayId);
                    return;
                }

                Slog.w(TAG, "Failed to put " + task + " on display " + preferredDisplayId);
                // Display a warning toast that we failed to put a task on a secondary display.
                mService.getTaskChangeNotificationController()
                        .notifyActivityLaunchOnSecondaryDisplayFailed(task.getTaskInfo(),
                                preferredDisplayId);
                return;
            } else if (!forceNonResizable && handleForcedResizableTask(task,
                    FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY)) {
                return;
            }
        }

        if (!task.supportsSplitScreenWindowingMode() || forceNonResizable) {
            // Display a warning toast that we tried to put an app that doesn't support split-screen
            // in split-screen.
            mService.getTaskChangeNotificationController().notifyActivityDismissingDockedStack();

            // Dismiss docked stack. If task appeared to be in docked stack but is not resizable -
            // we need to move it to top of fullscreen stack, otherwise it will be covered.

            final ActivityStack dockedStack =
                    task.getStack().getDisplay().getSplitScreenPrimaryStack();
            if (dockedStack != null) {
                moveTasksToFullscreenStackLocked(dockedStack, actualStack == dockedStack);
            }
            return;
        }

        handleForcedResizableTask(task, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN);
    }

    /**
     * @return {@code true} if the top activity of the task is forced to be resizable and the user
     *         was notified about activity being forced resized.
     */
    private boolean handleForcedResizableTask(TaskRecord task, int reason) {
        final ActivityRecord topActivity = task.getTopActivity();
        if (topActivity != null && topActivity.isNonResizableOrForcedResizable()
                && !topActivity.noDisplay) {
            final String packageName = topActivity.appInfo.packageName;
            mService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                    task.taskId, reason, packageName);
            return true;
        }
        return false;
    }

    void activityRelaunchedLocked(IBinder token) {
        mWindowManager.notifyAppRelaunchingFinished(token);
        final ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null) {
            if (r.getActivityStack().shouldSleepOrShutDownActivities()) {
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

    void scheduleUpdateMultiWindowMode(TaskRecord task) {
        // If the stack is animating in a way where we will be forcing a multi-mode change at the
        // end, then ensure that we defer all in between multi-window mode changes
        if (task.getStack().deferScheduleMultiWindowModeChanged()) {
            return;
        }

        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = task.mActivities.get(i);
            if (r.attachedToProcess()) {
                mMultiWindowModeChangedActivities.add(r);
            }
        }

        if (!mHandler.hasMessages(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG)) {
            mHandler.sendEmptyMessage(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG);
        }
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, ActivityStack prevStack) {
        final ActivityStack stack = task.getStack();
        if (prevStack == null || prevStack == stack
                || (!prevStack.inPinnedWindowingMode() && !stack.inPinnedWindowingMode())) {
            return;
        }

        scheduleUpdatePictureInPictureModeIfNeeded(task, stack.getRequestedOverrideBounds());
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, Rect targetStackBounds) {
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = task.mActivities.get(i);
            if (r.attachedToProcess()) {
                mPipModeChangedActivities.add(r);
                // If we are scheduling pip change, then remove this activity from multi-window
                // change list as the processing of pip change will make sure multi-window changed
                // message is processed in the right order relative to pip changed.
                mMultiWindowModeChangedActivities.remove(r);
            }
        }
        mPipModeChangedTargetStackBounds = targetStackBounds;

        if (!mHandler.hasMessages(REPORT_PIP_MODE_CHANGED_MSG)) {
            mHandler.sendEmptyMessage(REPORT_PIP_MODE_CHANGED_MSG);
        }
    }

    void updatePictureInPictureMode(TaskRecord task, Rect targetStackBounds, boolean forceUpdate) {
        mHandler.removeMessages(REPORT_PIP_MODE_CHANGED_MSG);
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = task.mActivities.get(i);
            if (r.attachedToProcess()) {
                r.updatePictureInPictureMode(targetStackBounds, forceUpdate);
            }
        }
    }

    void wakeUp(String reason) {
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_APPLICATION,
                "android.server.am:TURN_ON:" + reason);
    }

    /**
     * Begin deferring resume to avoid duplicate resumes in one pass.
     */
    void beginDeferResume() {
        mDeferResumeCount++;
    }

    /**
     * End deferring resume and determine if resume can be called.
     */
    void endDeferResume() {
        mDeferResumeCount--;
    }

    /** @return True if resume can be called. */
    boolean readyToResume() {
        return mDeferResumeCount == 0;
    }

    private final class ActivityStackSupervisorHandler extends Handler {

        public ActivityStackSupervisorHandler(Looper looper) {
            super(looper);
        }

        void activityIdleInternal(ActivityRecord r, boolean processPausingActivities) {
            synchronized (mService.mGlobalLock) {
                activityIdleInternalLocked(r != null ? r.appToken : null, true /* fromTimeout */,
                        processPausingActivities, null);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REPORT_MULTI_WINDOW_MODE_CHANGED_MSG: {
                    synchronized (mService.mGlobalLock) {
                        for (int i = mMultiWindowModeChangedActivities.size() - 1; i >= 0; i--) {
                            final ActivityRecord r = mMultiWindowModeChangedActivities.remove(i);
                            r.updateMultiWindowMode();
                        }
                    }
                } break;
                case REPORT_PIP_MODE_CHANGED_MSG: {
                    synchronized (mService.mGlobalLock) {
                        for (int i = mPipModeChangedActivities.size() - 1; i >= 0; i--) {
                            final ActivityRecord r = mPipModeChangedActivities.remove(i);
                            r.updatePictureInPictureMode(mPipModeChangedTargetStackBounds,
                                    false /* forceUpdate */);
                        }
                    }
                } break;
                case IDLE_TIMEOUT_MSG: {
                    if (DEBUG_IDLE) Slog.d(TAG_IDLE,
                            "handleMessage: IDLE_TIMEOUT_MSG: r=" + msg.obj);
                    // We don't at this point know if the activity is fullscreen,
                    // so we need to be conservative and assume it isn't.
                    activityIdleInternal((ActivityRecord) msg.obj,
                            true /* processPausingActivities */);
                } break;
                case IDLE_NOW_MSG: {
                    if (DEBUG_IDLE) Slog.d(TAG_IDLE, "handleMessage: IDLE_NOW_MSG: r=" + msg.obj);
                    activityIdleInternal((ActivityRecord) msg.obj,
                            false /* processPausingActivities */);
                } break;
                case RESUME_TOP_ACTIVITY_MSG: {
                    synchronized (mService.mGlobalLock) {
                        mRootActivityContainer.resumeFocusedStacksTopActivities();
                    }
                } break;
                case SLEEP_TIMEOUT_MSG: {
                    synchronized (mService.mGlobalLock) {
                        if (mService.isSleepingOrShuttingDownLocked()) {
                            Slog.w(TAG, "Sleep timeout!  Sleeping now.");
                            checkReadyForSleepLocked(false /* allowDelay */);
                        }
                    }
                } break;
                case LAUNCH_TIMEOUT_MSG: {
                    synchronized (mService.mGlobalLock) {
                        if (mLaunchingActivityWakeLock.isHeld()) {
                            Slog.w(TAG, "Launch timeout has expired, giving up wake lock!");
                            if (VALIDATE_WAKE_LOCK_CALLER
                                    && Binder.getCallingUid() != Process.myUid()) {
                                throw new IllegalStateException("Calling must be system uid");
                            }
                            mLaunchingActivityWakeLock.release();
                        }
                    }
                } break;
                case LAUNCH_TASK_BEHIND_COMPLETE: {
                    synchronized (mService.mGlobalLock) {
                        ActivityRecord r = ActivityRecord.forTokenLocked((IBinder) msg.obj);
                        if (r != null) {
                            handleLaunchTaskBehindCompleteLocked(r);
                        }
                    }
                } break;
                case RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG: {
                    final ActivityRecord r = (ActivityRecord) msg.obj;
                    String processName = null;
                    int uid = 0;
                    synchronized (mService.mGlobalLock) {
                        if (r.attachedToProcess()
                                && r.isState(ActivityStack.ActivityState.RESTARTING_PROCESS)) {
                            processName = r.app.mName;
                            uid = r.app.mUid;
                        }
                    }
                    if (processName != null) {
                        mService.mAmInternal.killProcess(processName, uid,
                                "restartActivityProcessTimeout");
                    }
                } break;
                case REPORT_HOME_CHANGED_MSG: {
                    synchronized (mService.mGlobalLock) {
                        mHandler.removeMessages(REPORT_HOME_CHANGED_MSG);

                        // Start home activities on displays with no activities.
                        mRootActivityContainer.startHomeOnEmptyDisplays("homeChanged");
                    }
                } break;
                case TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG: {
                    ActivityRecord r = (ActivityRecord) msg.obj;
                    Slog.w(TAG, "Activity top resumed state loss timeout for " + r);
                    synchronized (mService.mGlobalLock) {
                        if (r.hasProcess()) {
                            mService.logAppTooSlow(r.app, r.topResumedStateLossTime,
                                    "top state loss for " + r);
                        }
                    }
                    handleTopResumedStateReleased(true /* timeout */);
                } break;
            }
        }
    }

    /**
     * Puts a task into resizing mode during the next app transition.
     *
     * @param task The task to put into resizing mode
     */
    void setResizingDuringAnimation(TaskRecord task) {
        mResizingTasksDuringAnimation.add(task.taskId);
        task.setTaskDockedResizing(true);
    }

    int startActivityFromRecents(int callingPid, int callingUid, int taskId,
            SafeActivityOptions options) {
        TaskRecord task = null;
        final String callingPackage;
        final Intent intent;
        final int userId;
        int activityType = ACTIVITY_TYPE_UNDEFINED;
        int windowingMode = WINDOWING_MODE_UNDEFINED;
        final ActivityOptions activityOptions = options != null
                ? options.getOptions(this)
                : null;
        if (activityOptions != null) {
            activityType = activityOptions.getLaunchActivityType();
            windowingMode = activityOptions.getLaunchWindowingMode();
            if (activityOptions.freezeRecentTasksReordering()) {
                mRecentTasks.setFreezeTaskListReordering();
            }
        }
        if (activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS) {
            throw new IllegalArgumentException("startActivityFromRecents: Task "
                    + taskId + " can't be launch in the home/recents stack.");
        }

        mWindowManager.deferSurfaceLayout();
        try {
            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                mWindowManager.setDockedStackCreateState(
                        activityOptions.getSplitScreenCreateMode(), null /* initialBounds */);

                // Defer updating the stack in which recents is until the app transition is done, to
                // not run into issues where we still need to draw the task in recents but the
                // docked stack is already created.
                deferUpdateRecentsHomeStackBounds();
                // TODO(multi-display): currently recents animation only support default display.
                mWindowManager.prepareAppTransition(TRANSIT_DOCK_TASK_FROM_RECENTS, false);
            }

            task = mRootActivityContainer.anyTaskForId(taskId,
                    MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, activityOptions, ON_TOP);
            if (task == null) {
                continueUpdateRecentsHomeStackBounds();
                mWindowManager.executeAppTransition();
                throw new IllegalArgumentException(
                        "startActivityFromRecents: Task " + taskId + " not found.");
            }

            if (windowingMode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                // We always want to return to the home activity instead of the recents activity
                // from whatever is started from the recents activity, so move the home stack
                // forward.
                // TODO (b/115289124): Multi-display supports for recents.
                mRootActivityContainer.getDefaultDisplay().moveHomeStackToFront(
                        "startActivityFromRecents");
            }

            // If the user must confirm credentials (e.g. when first launching a work app and the
            // Work Challenge is present) let startActivityInPackage handle the intercepting.
            if (!mService.mAmInternal.shouldConfirmCredentials(task.userId)
                    && task.getRootActivity() != null) {
                final ActivityRecord targetActivity = task.getTopActivity();

                mRootActivityContainer.sendPowerHintForLaunchStartIfNeeded(
                        true /* forceSend */, targetActivity);
                mActivityMetricsLogger.notifyActivityLaunching(task.intent);
                try {
                    mService.moveTaskToFrontLocked(task.taskId, 0, options,
                            true /* fromRecents */);
                    // Apply options to prevent pendingOptions be taken by client to make sure
                    // the override pending app transition will be applied immediately.
                    targetActivity.applyOptionsLocked();
                } finally {
                    mActivityMetricsLogger.notifyActivityLaunched(START_TASK_TO_FRONT,
                            targetActivity);
                }

                mService.getActivityStartController().postStartActivityProcessingForLastStarter(
                        task.getTopActivity(), ActivityManager.START_TASK_TO_FRONT,
                        task.getStack());
                return ActivityManager.START_TASK_TO_FRONT;
            }
            callingPackage = task.mCallingPackage;
            intent = task.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            userId = task.userId;
            return mService.getActivityStartController().startActivityInPackage(
                    task.mCallingUid, callingPid, callingUid, callingPackage, intent, null, null,
                    null, 0, 0, options, userId, task, "startActivityFromRecents",
                    false /* validateIncomingUser */, null /* originatingPendingIntent */,
                    false /* allowBackgroundActivityStart */);
        } finally {
            if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY && task != null) {
                // If we are launching the task in the docked stack, put it into resizing mode so
                // the window renders full-screen with the background filling the void. Also only
                // call this at the end to make sure that tasks exists on the window manager side.
                setResizingDuringAnimation(task);

                final ActivityDisplay display = task.getStack().getDisplay();
                final ActivityStack topSecondaryStack =
                        display.getTopStackInWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
                if (topSecondaryStack.isActivityTypeHome()) {
                    // If the home activity is the top split-screen secondary stack, then the
                    // primary split-screen stack is in the minimized mode which means it can't
                    // receive input keys, so we should move the focused app to the home app so that
                    // window manager can correctly calculate the focus window that can receive
                    // input keys.
                    display.moveHomeStackToFront(
                            "startActivityFromRecents: homeVisibleInSplitScreen");

                    // Immediately update the minimized docked stack mode, the upcoming animation
                    // for the docked activity (WMS.overridePendingAppTransitionMultiThumbFuture)
                    // will do the animation to the target bounds
                    mWindowManager.checkSplitScreenMinimizedChanged(false /* animate */);
                }
            }
            mWindowManager.continueSurfaceLayout();
        }
    }

    /**
     * Internal container to store a match qualifier alongside a WaitResult.
     */
    static class WaitInfo {
        private final ComponentName mTargetComponent;
        private final WaitResult mResult;
        /** Time stamp when we started to wait for {@link WaitResult}. */
        private final long mStartTimeMs;

        WaitInfo(ComponentName targetComponent, WaitResult result, long startTimeMs) {
            this.mTargetComponent = targetComponent;
            this.mResult = result;
            this.mStartTimeMs = startTimeMs;
        }

        public boolean matches(ComponentName targetComponent) {
            return mTargetComponent == null || mTargetComponent.equals(targetComponent);
        }

        public WaitResult getResult() {
            return mResult;
        }

        public long getStartTime() {
            return mStartTimeMs;
        }

        public ComponentName getComponent() {
            return mTargetComponent;
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "WaitInfo:");
            pw.println(prefix + "  mTargetComponent=" + mTargetComponent);
            pw.println(prefix + "  mResult=");
            mResult.dump(pw, prefix);
        }
    }
}
