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
import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.Manifest.permission.START_ANY_ACTIVITY;
import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FLAG_DEBUG;
import static android.app.ActivityManager.START_FLAG_NATIVE_DEBUGGING;
import static android.app.ActivityManager.START_FLAG_TRACK_ALLOCATION;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityOptions.ANIM_REMOTE_ANIMATION;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.app.WaitResult.INVALID_DELAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.PowerManager.PARTIAL_WAKE_LOCK;
import static android.os.Process.INVALID_PID;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TASKS;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESTARTING_PROCESS;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ALL;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_IDLE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_IDLE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ROOT_TASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ANIMATE;
import static com.android.server.wm.ActivityTaskManagerService.H.FIRST_SUPERVISOR_TASK_MSG;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.ClientLifecycleManager.shouldDispatchCompatClientTransactionIndependently;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_ALLOWLISTED;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.wm.LockTaskController.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_PINNED_TASK;
import static com.android.server.wm.Task.REPARENT_KEEP_ROOT_TASK_AT_FRONT;
import static com.android.server.wm.Task.TAG_CLEANUP;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.BackgroundStartPrivileges;
import android.app.IActivityClientController;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.app.TaskInfo;
import android.app.WaitResult;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.LaunchActivityItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.MediaStore;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.window.ActivityWindowInfo;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.HostingRecord;
import com.android.server.am.UserState;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.utils.Slogf;
import com.android.server.wm.ActivityMetricsLogger.LaunchingState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

// TODO: This class has become a dumping ground. Let's
// - Move things relating to the hierarchy to RootWindowContainer
// - Move things relating to activity life cycles to maybe a new class called ActivityLifeCycler
// - Move interface things to ActivityTaskManagerService.
// - All other little things to other files.
public class ActivityTaskSupervisor implements RecentTasks.Callbacks {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityTaskSupervisor" : TAG_ATM;
    private static final String TAG_IDLE = TAG + POSTFIX_IDLE;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_ROOT_TASK = TAG + POSTFIX_ROOT_TASK;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;

    /** How long we wait until giving up on the last activity telling us it is idle. */
    private static final int IDLE_TIMEOUT = 10 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    /** How long we can hold the sleep wake lock before giving up. */
    private static final int SLEEP_TIMEOUT = 5 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    // How long we can hold the launch wake lock before giving up.
    private static final int LAUNCH_TIMEOUT = 10 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    // How long we delay processing the stopping and finishing activities.
    private static final int SCHEDULE_FINISHING_STOPPING_ACTIVITY_MS = 200;

    /** How long we wait until giving up on the activity telling us it released the top state. */
    private static final int TOP_RESUMED_STATE_LOSS_TIMEOUT = 500;

    /**
     * The timeout to kill task processes if its activity didn't complete destruction in time
     * when there is a request to remove the task with killProcess=true.
     */
    private static final int KILL_TASK_PROCESSES_TIMEOUT_MS = 1000;

    private static final int IDLE_TIMEOUT_MSG = FIRST_SUPERVISOR_TASK_MSG;
    private static final int IDLE_NOW_MSG = FIRST_SUPERVISOR_TASK_MSG + 1;
    private static final int RESUME_TOP_ACTIVITY_MSG = FIRST_SUPERVISOR_TASK_MSG + 2;
    private static final int SLEEP_TIMEOUT_MSG = FIRST_SUPERVISOR_TASK_MSG + 3;
    private static final int LAUNCH_TIMEOUT_MSG = FIRST_SUPERVISOR_TASK_MSG + 4;
    private static final int PROCESS_STOPPING_AND_FINISHING_MSG = FIRST_SUPERVISOR_TASK_MSG + 5;
    private static final int KILL_TASK_PROCESSES_TIMEOUT_MSG = FIRST_SUPERVISOR_TASK_MSG + 6;
    private static final int LAUNCH_TASK_BEHIND_COMPLETE = FIRST_SUPERVISOR_TASK_MSG + 12;
    private static final int RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG = FIRST_SUPERVISOR_TASK_MSG + 13;
    private static final int REPORT_MULTI_WINDOW_MODE_CHANGED_MSG = FIRST_SUPERVISOR_TASK_MSG + 14;
    private static final int REPORT_PIP_MODE_CHANGED_MSG = FIRST_SUPERVISOR_TASK_MSG + 15;
    private static final int START_HOME_MSG = FIRST_SUPERVISOR_TASK_MSG + 16;
    private static final int TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG = FIRST_SUPERVISOR_TASK_MSG + 17;

    // Used to indicate that windows of activities should be preserved during the resize.
    static final boolean PRESERVE_WINDOWS = true;

    // Used to indicate if an object (e.g. task) should be moved/created
    // at the top of its container (e.g. root task).
    static final boolean ON_TOP = true;

    // Don't execute any calls to resume.
    static final boolean DEFER_RESUME = true;

    // Used to indicate that a task is removed it should also be removed from recents.
    static final boolean REMOVE_FROM_RECENTS = true;

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
    private static final boolean VALIDATE_WAKE_LOCK_CALLER = false;
    /** The number of distinct task ids that can be assigned to the tasks of a single user */
    private static final int MAX_TASK_IDS_PER_USER = UserHandle.PER_USER_RANGE;

    final ActivityTaskManagerService mService;
    RootWindowContainer mRootWindowContainer;

    /** Helper class for checking if an activity transition meets security rules */
    BackgroundActivityStartController mBalController;

    /** The historial list of recent tasks including inactive tasks */
    RecentTasks mRecentTasks;

    /** Helper class to abstract out logic for fetching the set of currently running tasks */
    private RunningTasks mRunningTasks;

    /** Helper for {@link Task#fillTaskInfo}. */
    final TaskInfoHelper mTaskInfoHelper = new TaskInfoHelper();

    final OpaqueActivityHelper mOpaqueActivityHelper = new OpaqueActivityHelper();

    private final ActivityTaskSupervisorHandler mHandler;
    final Looper mLooper;

    /** Short cut */
    private WindowManagerService mWindowManager;

    private AppOpsManager mAppOpsManager;
    private VirtualDeviceManager mVirtualDeviceManager;

    /** Common synchronization logic used to save things to disks. */
    PersisterQueue mPersisterQueue;
    LaunchParamsPersister mLaunchParamsPersister;
    private LaunchParamsController mLaunchParamsController;

    /**
     * The processes with changed states that should eventually call
     * {@link WindowProcessController#computeProcessActivityState}.
     */
    private final ArrayList<WindowProcessController> mActivityStateChangedProcs = new ArrayList<>();

    /**
     * Maps the task identifier that activities are currently being started in to the userId of the
     * task. Each time a new task is created, the entry for the userId of the task is incremented
     */
    private final SparseIntArray mCurTaskIdForUser = new SparseIntArray(20);

    /** List of requests waiting for the target activity to be launched or visible. */
    private final ArrayList<WaitInfo> mWaitingActivityLaunched = new ArrayList<>();

    /** List of activities that are ready to be stopped, but waiting for the next activity to
     * settle down before doing so. */
    final ArrayList<ActivityRecord> mStoppingActivities = new ArrayList<>();

    /** List of activities that are ready to be finished, but waiting for the previous activity to
     * settle down before doing so.  It contains ActivityRecord objects. */
    final ArrayList<ActivityRecord> mFinishingActivities = new ArrayList<>();

    /**
     * Activities that specify No History must be removed once the user navigates away from them.
     * If the device goes to sleep with such an activity in the paused state then we save it
     * here and finish it later if another activity replaces it on wakeup.
     */
    final ArrayList<ActivityRecord> mNoHistoryActivities = new ArrayList<>();

    /** List of activities whose multi-window mode changed that we need to report to the
     * application */
    private final ArrayList<ActivityRecord> mMultiWindowModeChangedActivities = new ArrayList<>();

    /** List of activities whose picture-in-picture mode changed that we need to report to the
     * application */
    private final ArrayList<ActivityRecord> mPipModeChangedActivities = new ArrayList<>();

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

    /** The target root task bounds for the picture-in-picture mode changed that we need to
     * report to the application */
    private Rect mPipModeChangedTargetRootTaskBounds;

    /** Used on user changes */
    final ArrayList<UserState> mStartingUsers = new ArrayList<>();

    /** Set to indicate whether to issue an onUserLeaving callback when a newly launched activity
     * is being brought in front of us. */
    boolean mUserLeaving = false;

    /**
     * The system chooser activity which worked as a delegate of
     * {@link com.android.internal.app.ResolverActivity}.
     */
    private ComponentName mSystemChooserActivity;

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
     * Used to keep {@link RootWindowContainer#ensureActivitiesVisible} from being entered
     * recursively. And only update keyguard states once the nested updates are done.
     */
    private int mVisibilityTransactionDepth;

    /**
     * Whether to the visibility updates that started from {@code RootWindowContainer} should be
     * deferred.
     */
    private boolean mDeferRootVisibilityUpdate;

    private ActivityMetricsLogger mActivityMetricsLogger;

    /** Check if placing task or activity on specified display is allowed. */
    boolean canPlaceEntityOnDisplay(int displayId, int callingPid, int callingUid,
            ActivityInfo activityInfo) {
        return canPlaceEntityOnDisplay(displayId, callingPid, callingUid, null /* task */,
                activityInfo);
    }

    boolean canPlaceEntityOnDisplay(int displayId, int callingPid, int callingUid, Task task) {
        return canPlaceEntityOnDisplay(displayId, callingPid, callingUid, task,
                null /* activityInfo */);
    }

    private boolean canPlaceEntityOnDisplay(int displayId, int callingPid, int callingUid,
            Task task, ActivityInfo activityInfo) {
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

        final DisplayContent displayContent =
                mRootWindowContainer.getDisplayContentOrCreate(displayId);
        if (displayContent != null) {
            final ArrayList<ActivityInfo> activities = new ArrayList<>();
            if (activityInfo != null) {
                activities.add(activityInfo);
            }
            if (task != null) {
                task.forAllActivities((r) -> {
                    activities.add(r.info);
                });
            }
            return displayContent.mDwpcHelper.canContainActivities(activities,
                        displayContent.getWindowingMode());
        }

        return true;
    }

    /**
     * Used to keep track whether app visibilities got changed since the last pause. Useful to
     * determine whether to invoke the task stack change listener after pausing.
     */
    boolean mAppVisibilitiesChangedSinceLastPause;

    private KeyguardController mKeyguardController;

    private PowerManager mPowerManager;
    private int mDeferResumeCount;

    private boolean mInitialized;

    public ActivityTaskSupervisor(ActivityTaskManagerService service, Looper looper) {
        mService = service;
        mLooper = looper;
        mHandler = new ActivityTaskSupervisorHandler(looper);
    }

    public void initialize() {
        if (mInitialized) {
            return;
        }

        mInitialized = true;
        setRunningTasks(new RunningTasks());

        mActivityMetricsLogger = new ActivityMetricsLogger(this, mHandler.getLooper());
        mKeyguardController = new KeyguardController(mService, this);

        mPersisterQueue = new PersisterQueue();
        mLaunchParamsPersister = new LaunchParamsPersister(mPersisterQueue, this);
        mLaunchParamsController = new LaunchParamsController(mService, mLaunchParamsPersister);
        mLaunchParamsController.registerDefaultModifiers(this);

        mBalController = new BackgroundActivityStartController(mService, this);
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

        // Need to launch home again for those displays that do not have encryption aware home app.
        scheduleStartHome("userUnlocked");
    }

    public ActivityMetricsLogger getActivityMetricsLogger() {
        return mActivityMetricsLogger;
    }

    public KeyguardController getKeyguardController() {
        return mKeyguardController;
    }

    ComponentName getSystemChooserActivity() {
        if (mSystemChooserActivity == null) {
            mSystemChooserActivity = ComponentName.unflattenFromString(
                    mService.mContext.getResources().getString(R.string.config_chooserActivity));
        }
        return mSystemChooserActivity;
    }

    void setRecentTasks(RecentTasks recentTasks) {
        if (mRecentTasks != null) {
            mRecentTasks.unregisterCallback(this);
        }
        mRecentTasks = recentTasks;
        mRecentTasks.registerCallback(this);
    }

    @VisibleForTesting
    void setRunningTasks(RunningTasks runningTasks) {
        mRunningTasks = runningTasks;
    }

    RunningTasks getRunningTasks() {
        return mRunningTasks;
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

    void moveRecentsRootTaskToFront(String reason) {
        final Task recentsRootTask = mRootWindowContainer.getDefaultTaskDisplayArea()
                .getRootTask(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_RECENTS);
        if (recentsRootTask != null) {
            recentsRootTask.moveToFront(reason);
        }
    }

    void setNextTaskIdForUser(int taskId, int userId) {
        final int currentTaskId = mCurTaskIdForUser.get(userId, -1);
        if (taskId > currentTaskId) {
            mCurTaskIdForUser.put(userId, taskId);
        }
    }

    void finishNoHistoryActivitiesIfNeeded(ActivityRecord next) {
        for (int i = mNoHistoryActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord noHistoryActivity = mNoHistoryActivities.get(i);
            if (!noHistoryActivity.finishing && noHistoryActivity != next
                    && next.occludesParent()
                    && noHistoryActivity.getDisplayId() == next.getDisplayId()) {
                ProtoLog.d(WM_DEBUG_STATES, "no-history finish of %s on new resume",
                        noHistoryActivity);
                noHistoryActivity.finishIfPossible("resume-no-history", false /* oomAdj */);
                mNoHistoryActivities.remove(noHistoryActivity);
            }
        }
    }

    private static int nextTaskIdForUser(int taskId, int userId) {
        int nextTaskId = taskId + 1;
        if (nextTaskId == (userId + 1) * MAX_TASK_IDS_PER_USER) {
            // Wrap around as there will be smaller task ids that are available now.
            nextTaskId -= MAX_TASK_IDS_PER_USER;
        }
        return nextTaskId;
    }

    int getNextTaskIdForUser() {
        return getNextTaskIdForUser(mRootWindowContainer.mCurrentUser);
    }

    int getNextTaskIdForUser(int userId) {
        final int currentTaskId = mCurTaskIdForUser.get(userId, userId * MAX_TASK_IDS_PER_USER);
        // for a userId u, a taskId can only be in the range
        // [u*MAX_TASK_IDS_PER_USER, (u+1)*MAX_TASK_IDS_PER_USER-1], so if MAX_TASK_IDS_PER_USER
        // was 10, user 0 could only have taskIds 0 to 9, user 1: 10 to 19, user 2: 20 to 29, so on.
        int candidateTaskId = nextTaskIdForUser(currentTaskId, userId);
        while (mRecentTasks.containsTaskId(candidateTaskId, userId)
                || mRootWindowContainer.anyTaskForId(
                        candidateTaskId, MATCH_ATTACHED_TASK_OR_RECENT_TASKS) != null) {
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

    void waitActivityVisibleOrLaunched(WaitResult w, ActivityRecord r,
            LaunchingState launchingState) {
        if (w.result != ActivityManager.START_TASK_TO_FRONT
                && w.result != ActivityManager.START_SUCCESS) {
            // Not a result code that can make activity visible or launched.
            return;
        }
        final WaitInfo waitInfo = new WaitInfo(w, r.mActivityComponent, launchingState);
        mWaitingActivityLaunched.add(waitInfo);
        do {
            try {
                mService.mGlobalLock.wait();
            } catch (InterruptedException ignored) {
            }
        } while (mWaitingActivityLaunched.contains(waitInfo));
    }

    void cleanupActivity(ActivityRecord r) {
        // Make sure this record is no longer in the pending finishes list.
        // This could happen, for example, if we are trimming activities
        // down to the max limit while they are still waiting to finish.
        mFinishingActivities.remove(r);

        stopWaitingForActivityVisible(r);
    }

    /** There is no valid launch time, just stop waiting. */
    void stopWaitingForActivityVisible(ActivityRecord r) {
        reportActivityLaunched(false /* timeout */, r, WaitResult.INVALID_DELAY,
                WaitResult.LAUNCH_STATE_UNKNOWN);
    }

    void reportActivityLaunched(boolean timeout, ActivityRecord r, long totalTime,
            @WaitResult.LaunchState int launchState) {
        boolean changed = false;
        for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            final WaitInfo info = mWaitingActivityLaunched.get(i);
            if (!info.matches(r)) {
                continue;
            }
            final WaitResult w = info.mResult;
            w.timeout = timeout;
            w.who = r.mActivityComponent;
            w.totalTime = totalTime;
            w.launchState = launchState;
            mWaitingActivityLaunched.remove(i);
            changed = true;
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
            final WaitInfo info = mWaitingActivityLaunched.get(i);
            if (!info.matches(r)) {
                continue;
            }
            final WaitResult w = info.mResult;
            w.result = result;
            if (result == START_DELIVERED_TO_TOP) {
                // Unlike START_TASK_TO_FRONT, When an intent is delivered to top, there
                // will be no followup launch signals. Assign the result and launched component.
                w.who = r.mActivityComponent;
                mWaitingActivityLaunched.remove(i);
                changed = true;
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

            final boolean requestDebug = (startFlags & (START_FLAG_DEBUG
                    | START_FLAG_NATIVE_DEBUGGING | START_FLAG_TRACK_ALLOCATION)) != 0;
            final boolean requestProfile = profilerInfo != null;
            if (requestDebug || requestProfile) {
                final boolean debuggable = (Build.IS_DEBUGGABLE
                        || (aInfo.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                        && !aInfo.processName.equals("system");
                if ((requestDebug && !debuggable) || (requestProfile
                        && (!debuggable && !aInfo.applicationInfo.isProfileableByShell()))) {
                    Slog.w(TAG, "Ignore debugging for non-debuggable app: " + aInfo.packageName);
                } else {
                     // Mimic an AMS synchronous call by passing a message to AMS and wait for AMS
                     // to notify us that the task has completed.
                     // TODO(b/80414790) look into further untangling for the situation where the
                     // caller is on the same thread as the handler we are posting to.
                    synchronized (mService.mGlobalLock) {
                        // Post message to AMS.
                        mService.mH.post(() -> {
                            try {
                                mService.mAmInternal.setDebugFlagsForStartingActivity(aInfo,
                                        startFlags, profilerInfo, mService.mGlobalLock);
                            } catch (Throwable e) {
                                // Simply ignore it because the debugging doesn't take effect.
                                Slog.w(TAG, e);
                                synchronized (mService.mGlobalLockWithoutBoost) {
                                    mService.mGlobalLockWithoutBoost.notifyAll();
                                }
                            }
                        });
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
            int filterCallingUid, int callingPid) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "resolveIntent");
            int modifiedFlags = flags
                    | PackageManager.MATCH_DEFAULT_ONLY | ActivityManagerService.STOCK_PM_FLAGS;
            if (intent.isWebIntent()
                        || (intent.getFlags() & Intent.FLAG_ACTIVITY_MATCH_EXTERNAL) != 0) {
                modifiedFlags |= PackageManager.MATCH_INSTANT;
            }
            int privateResolveFlags  = 0;
            if (intent.isWebIntent()
                        && (intent.getFlags() & Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER) != 0) {
                privateResolveFlags |= PackageManagerInternal.RESOLVE_NON_BROWSER_ONLY;
            }
            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT) != 0) {
                privateResolveFlags |= PackageManagerInternal.RESOLVE_NON_RESOLVER_ONLY;
            }

            // In order to allow cross-profile lookup, we clear the calling identity here.
            // Note the binder identity won't affect the result, but filterCallingUid will.

            // Cross-user/profile call check are done at the entry points
            // (e.g. AMS.startActivityAsUser).
            final long token = Binder.clearCallingIdentity();
            try {
                return mService.getPackageManagerInternalLocked().resolveIntentExported(
                        intent, resolvedType, modifiedFlags, privateResolveFlags, userId, true,
                        filterCallingUid, callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags,
            ProfilerInfo profilerInfo, int userId, int filterCallingUid, int callingPid) {
        final ResolveInfo rInfo = resolveIntent(intent, resolvedType, userId, 0,
                filterCallingUid, callingPid);
        return resolveActivity(intent, rInfo, startFlags, profilerInfo);
    }

    boolean realStartActivityLocked(ActivityRecord r, WindowProcessController proc,
            boolean andResume, boolean checkConfig) throws RemoteException {

        if (!mRootWindowContainer.allPausedActivitiesComplete()) {
            // While there are activities pausing we skipping starting any new activities until
            // pauses are complete. NOTE: that we also do this for activities that are starting in
            // the paused state because they will first be resumed then paused on the client side.
            ProtoLog.v(WM_DEBUG_STATES,
                    "realStartActivityLocked: Skipping start of r=%s some activities pausing...",
                    r);
            return false;
        }

        final Task task = r.getTask();
        if (andResume) {
            // Try pausing the existing resumed activity in the Task if any.
            if (task.pauseActivityIfNeeded(r, "realStart")) {
                return false;
            }
            final TaskFragment taskFragment = r.getTaskFragment();
            if (taskFragment != null && taskFragment.getResumedActivity() != null) {
                if (taskFragment.startPausing(mUserLeaving, false /* uiSleeping */, r,
                        "realStart")) {
                    return false;
                }
            }
        }

        final Task rootTask = task.getRootTask();
        beginDeferResume();
        // The LaunchActivityItem also contains process configuration, so the configuration change
        // from WindowProcessController#setProcess can be deferred. The major reason is that if
        // the activity has FixedRotationAdjustments, it needs to be applied with configuration.
        // In general, this reduces a binder transaction if process configuration is changed.
        proc.pauseConfigurationDispatch();

        try {
            // schedule launch ticks to collect information about slow apps.
            r.startLaunchTickingLocked();
            r.lastLaunchTime = SystemClock.uptimeMillis();
            r.setProcess(proc);

            // Ensure activity is allowed to be resumed after process has set.
            if (andResume && !r.canResumeByCompat()) {
                andResume = false;
            }

            r.notifyUnknownVisibilityLaunchedForKeyguardTransition();

            // Have the window manager re-evaluate the orientation of the screen based on the new
            // activity order.  Note that as a result of this, it can call back into the activity
            // manager with a new orientation.  We don't care about that, because the activity is
            // not currently running so we are just restarting it anyway.
            if (checkConfig) {
                // Deferring resume here because we're going to launch new activity shortly.
                // We don't want to perform a redundant launch of the same record while ensuring
                // configurations and trying to resume top activity of focused root task.
                mRootWindowContainer.ensureVisibilityAndConfig(r, r.getDisplayId(),
                        true /* deferResume */);
            }

            if (mKeyguardController.checkKeyguardVisibility(r) && r.allowMoveToFront()) {
                // We only set the visibility to true if the activity is not being launched in
                // background, and is allowed to be visible based on keyguard state. This avoids
                // setting this into motion in window manager that is later cancelled due to later
                // calls to ensure visible activities that set visibility back to false.
                r.setVisibility(true);
            }

            final int applicationInfoUid =
                    (r.info.applicationInfo != null) ? r.info.applicationInfo.uid : -1;
            if ((r.mUserId != proc.mUserId) || (r.info.applicationInfo.uid != applicationInfoUid)) {
                Slog.wtf(TAG,
                        "User ID for activity changing for " + r
                                + " appInfo.uid=" + r.info.applicationInfo.uid
                                + " info.ai.uid=" + applicationInfoUid
                                + " old=" + r.app + " new=" + proc);
            }

            // Send the controller to client if the process is the first time to launch activity.
            // So the client can save binder transactions of getting the controller from activity
            // task manager service.
            final IActivityClientController activityClientController =
                    proc.hasEverLaunchedActivity() ? null : mService.mActivityClientController;
            r.launchCount++;

            if (DEBUG_ALL) Slog.v(TAG, "Launching: " + r);

            final LockTaskController lockTaskController = mService.getLockTaskController();
            if (task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE
                    || task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE_PRIV
                    || (task.mLockTaskAuth == LOCK_TASK_AUTH_ALLOWLISTED
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
                        "Launching: " + r + " savedState=" + r.getSavedState()
                                + " with results=" + results + " newIntents=" + newIntents
                                + " andResume=" + andResume);
                EventLogTags.writeWmRestartActivity(r.mUserId, System.identityHashCode(r),
                        task.mTaskId, r.shortComponentName);
                if (r.isActivityTypeHome()) {
                    // Home process is the root process of the task.
                    updateHomeProcess(task.getBottomMostActivity().app);
                }
                mService.getPackageManagerInternalLocked().notifyPackageUse(
                        r.intent.getComponent().getPackageName(), NOTIFY_PACKAGE_USE_ACTIVITY);
                mService.getAppWarningsLocked().onStartActivity(r);

                final Configuration procConfig = proc.prepareConfigurationForLaunchingActivity();
                final Configuration overrideConfig = r.getMergedOverrideConfiguration();
                r.setLastReportedConfiguration(procConfig, overrideConfig);

                final ActivityWindowInfo activityWindowInfo = r.getActivityWindowInfo();
                r.setLastReportedActivityWindowInfo(activityWindowInfo);

                logIfTransactionTooLarge(r.intent, r.getSavedState());

                final TaskFragment organizedTaskFragment = r.getOrganizedTaskFragment();
                if (organizedTaskFragment != null) {
                    // Sending TaskFragmentInfo to client to ensure the info is updated before
                    // the activity creation.
                    mService.mTaskFragmentOrganizerController.dispatchPendingInfoChangedEvent(
                            organizedTaskFragment);
                }

                // Create activity launch transaction.
                final boolean isTransitionForward = r.isTransitionForward();
                final IBinder fragmentToken = r.getTaskFragment().getFragmentToken();
                final int deviceId = getDeviceIdForDisplayId(r.getDisplayId());
                final LaunchActivityItem launchActivityItem = LaunchActivityItem.obtain(r.token,
                        r.intent, System.identityHashCode(r), r.info,
                        procConfig, overrideConfig, deviceId,
                        r.getFilteredReferrer(r.launchedFromPackage), task.voiceInteractor,
                        proc.getReportedProcState(), r.getSavedState(), r.getPersistentSavedState(),
                        results, newIntents, r.takeSceneTransitionInfo(), isTransitionForward,
                        proc.createProfilerInfoIfNeeded(), r.assistToken, activityClientController,
                        r.shareableActivityToken, r.getLaunchedFromBubble(), fragmentToken,
                        r.initialCallerInfoAccessToken, activityWindowInfo);

                // Set desired final state.
                final ActivityLifecycleItem lifecycleItem;
                if (andResume) {
                    lifecycleItem = ResumeActivityItem.obtain(r.token, isTransitionForward,
                            r.shouldSendCompatFakeFocus());
                } else {
                    lifecycleItem = PauseActivityItem.obtain(r.token);
                }

                // Schedule transaction.
                if (shouldDispatchCompatClientTransactionIndependently(r.mTargetSdk)) {
                    // LaunchActivityItem has @UnsupportedAppUsage usages.
                    // Guard the bundleClientTransactionFlag feature with targetSDK on Android 15+.
                    // To not bundle the transaction, dispatch the pending before schedule new
                    // transaction.
                    mService.getLifecycleManager().dispatchPendingTransaction(proc.getThread());
                }
                mService.getLifecycleManager().scheduleTransactionAndLifecycleItems(
                        proc.getThread(), launchActivityItem, lifecycleItem,
                        // Immediately dispatch the transaction, so that if it fails, the server can
                        // restart the process and retry now.
                        true /* shouldDispatchImmediately */);

                if (procConfig.seq > mRootWindowContainer.getConfiguration().seq) {
                    // If the seq is increased, there should be something changed (e.g. registered
                    // activity configuration).
                    proc.setLastReportedConfiguration(procConfig);
                }
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
                    proc.appDied("2nd-crash");
                    r.finishIfPossible("2nd-crash", false /* oomAdj */);
                    return false;
                }

                // This is the first time we failed -- restart process and
                // retry.
                r.launchFailed = true;
                r.detachFromProcess();
                throw e;
            }
        } finally {
            endDeferResume();
            proc.resumeConfigurationDispatch();
        }

        r.launchFailed = false;

        // TODO(lifecycler): Resume or pause requests are done as part of launch transaction,
        // so updating the state should be done accordingly.
        if (andResume && readyToResume()) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            rootTask.minimalResumeActivityLocked(r);
        } else {
            // This activity is not starting in the resumed state... which should look like we asked
            // it to pause+stop (but remain visible), and it has done so and reported back the
            // current icicle and other state.
            ProtoLog.v(WM_DEBUG_STATES, "Moving to PAUSED: %s "
                    + "(starting in paused state)", r);
            r.setState(PAUSED, "realStartActivityLocked");
            mRootWindowContainer.executeAppTransitionForAllDisplay();
        }
        // Perform OOM scoring after the activity state is set, so the process can be updated with
        // the latest state.
        proc.onStartActivity(mService.mTopProcessState, r.info);

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        if (mRootWindowContainer.isTopDisplayFocusedRootTask(rootTask)) {
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
            scheduleStartHome("homeChanged");
            mService.mHomeProcess = app;
        }
    }

    private void scheduleStartHome(String reason) {
        if (!mHandler.hasMessages(START_HOME_MSG)) {
            mHandler.obtainMessage(START_HOME_MSG, reason).sendToTarget();
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

    void startSpecificActivity(ActivityRecord r, boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        final WindowProcessController wpc =
                mService.getProcessController(r.processName, r.info.applicationInfo.uid);

        boolean knownToBeDead = false;
        if (wpc != null && wpc.hasThread()) {
            try {
                realStartActivityLocked(r, wpc, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
            knownToBeDead = true;
            // Remove the process record so it won't be considered as alive.
            mService.mProcessNames.remove(wpc.mName, wpc.mUid);
            mService.mProcessMap.remove(wpc.getPid());
        } else if (ActivityTaskManagerService.isSdkSandboxActivityIntent(
                mService.mContext, r.intent)) {
            Slog.e(TAG, "Abort sandbox activity launching as no sandbox process to host it.");
            r.finishIfPossible("No sandbox process for the activity", false /* oomAdj */);
            r.launchFailed = true;
            r.detachFromProcess();
            return;
        }

        r.notifyUnknownVisibilityLaunchedForKeyguardTransition();

        final boolean isTop = andResume && r.isTopRunningActivity();
        mService.startProcessAsync(r, knownToBeDead, isTop,
                isTop ? HostingRecord.HOSTING_TYPE_TOP_ACTIVITY
                        : HostingRecord.HOSTING_TYPE_ACTIVITY);
    }

    boolean checkStartAnyActivityPermission(Intent intent, ActivityInfo aInfo, String resultWho,
            int requestCode, int callingPid, int callingUid, String callingPackage,
            @Nullable String callingFeatureId, boolean ignoreTargetSecurity,
            boolean launchingInTask, WindowProcessController callerApp, ActivityRecord resultRecord,
            Task resultRootTask) {
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
        final int componentRestriction = getComponentRestrictionForCallingPackage(aInfo,
                callingPackage, callingFeatureId, callingPid, callingUid, ignoreTargetSecurity);
        final int actionRestriction = getActionRestrictionForCallingPackage(
                intent.getAction(), callingPackage, callingFeatureId, callingPid, callingUid);
        if (componentRestriction == ACTIVITY_RESTRICTION_PERMISSION
                || actionRestriction == ACTIVITY_RESTRICTION_PERMISSION) {
            if (resultRecord != null) {
                resultRecord.sendResult(INVALID_UID, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null /* data */, null /* callerToken */,
                        null /* dataGrants */);
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

    /** Check if caller is allowed to launch activities on specified task display area. */
    boolean isCallerAllowedToLaunchOnTaskDisplayArea(int callingPid, int callingUid,
            TaskDisplayArea taskDisplayArea, ActivityInfo aInfo) {
        return isCallerAllowedToLaunchOnDisplay(callingPid, callingUid,
                taskDisplayArea != null ? taskDisplayArea.getDisplayId() : DEFAULT_DISPLAY, aInfo);
    }

    /** Check if caller is allowed to launch activities on specified display. */
    boolean isCallerAllowedToLaunchOnDisplay(int callingPid, int callingUid, int launchDisplayId,
            ActivityInfo aInfo) {
        ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: displayId=%d callingPid=%d "
                + "callingUid=%d", launchDisplayId, callingPid, callingUid);

        if (callingPid == -1 && callingUid == -1) {
            ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: no caller info, skip check");
            return true;
        }

        final DisplayContent displayContent =
                mRootWindowContainer.getDisplayContentOrCreate(launchDisplayId);
        if (displayContent == null || displayContent.isRemoved()) {
            Slog.w(TAG, "Launch on display check: display not found");
            return false;
        }

        if ((displayContent.mDisplay.getFlags() & Display.FLAG_REAR) != 0) {
            Slog.w(TAG, "Launch on display check: activity launch is not allowed on rear display");
            return false;
        }

        // Check if the caller has enough privileges to embed activities and launch to private
        // displays.
        final int startAnyPerm = mService.checkPermission(INTERNAL_SYSTEM_WINDOW, callingPid,
                callingUid);
        if (startAnyPerm == PERMISSION_GRANTED) {
            ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: allow launch any on display");
            return true;
        }

        // Check if caller is already present on display
        final boolean uidPresentOnDisplay = displayContent.isUidPresent(callingUid);

        final Display display = displayContent.mDisplay;
        if (!display.isTrusted()) {
            // Limit launching on untrusted displays because their contents can be read from Surface
            // by apps that created them.
            if ((aInfo.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) == 0) {
                ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: disallow launch on "
                        + "virtual display for not-embedded activity.");
                return false;
            }
            // Check if the caller is allowed to embed activities from other apps.
            if (mService.checkPermission(ACTIVITY_EMBEDDING, callingPid, callingUid)
                    == PERMISSION_DENIED && !uidPresentOnDisplay) {
                ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: disallow activity "
                        + "embedding without permission.");
                return false;
            }
        }

        if (!displayContent.isPrivate()) {
            // Checks if the caller can be shown in the given public display.
            int userId = UserHandle.getUserId(callingUid);
            int displayId = display.getDisplayId();
            boolean allowed = mWindowManager.mUmInternal.isUserVisible(userId, displayId);
            ProtoLog.d(WM_DEBUG_TASKS,
                    "Launch on display check: %s launch for userId=%d on displayId=%d",
                    (allowed ? "allow" : "disallow"), userId, displayId);
            return allowed;
        }

        // Check if the caller is the owner of the display.
        if (display.getOwnerUid() == callingUid) {
            ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: allow launch for owner of the"
                    + " display");
            return true;
        }

        if (uidPresentOnDisplay) {
            ProtoLog.d(WM_DEBUG_TASKS, "Launch on display check: allow launch for caller "
                    + "present on the display");
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

    int getDeviceIdForDisplayId(int displayId) {
        if (displayId == DEFAULT_DISPLAY || displayId == INVALID_DISPLAY)  {
            return Context.DEVICE_ID_DEFAULT;
        }
        if (mVirtualDeviceManager == null) {
            if (mService.mHasCompanionDeviceSetupFeature) {
                mVirtualDeviceManager =
                        mService.mContext.getSystemService(VirtualDeviceManager.class);
            }
            if (mVirtualDeviceManager == null) {
                return Context.DEVICE_ID_DEFAULT;
            }
        }
        return mVirtualDeviceManager.getDeviceIdForDisplayId(displayId);
    }

    private AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = mService.mContext.getSystemService(AppOpsManager.class);
        }
        return mAppOpsManager;
    }

    BackgroundActivityStartController getBackgroundActivityLaunchController() {
        return mBalController;
    }

    private int getComponentRestrictionForCallingPackage(ActivityInfo activityInfo,
            String callingPackage, @Nullable String callingFeatureId, int callingPid,
            int callingUid, boolean ignoreTargetSecurity) {
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

        if (getAppOpsManager().noteOpNoThrow(opCode, callingUid,
                callingPackage, callingFeatureId, "") != AppOpsManager.MODE_ALLOWED) {
            if (!ignoreTargetSecurity) {
                return ACTIVITY_RESTRICTION_APPOP;
            }
        }

        return ACTIVITY_RESTRICTION_NONE;
    }

    private int getActionRestrictionForCallingPackage(String action, String callingPackage,
            @Nullable String callingFeatureId, int callingPid, int callingUid) {
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
                    .getPackageInfoAsUser(callingPackage, PackageManager.GET_PERMISSIONS,
                            UserHandle.getUserId(callingUid));
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

        if (getAppOpsManager().noteOpNoThrow(opCode, callingUid,
                callingPackage, callingFeatureId, "") != AppOpsManager.MODE_ALLOWED) {
            if (CAMERA.equals(permission)) {
                SensorPrivacyManagerInternal spmi =
                        LocalServices.getService(SensorPrivacyManagerInternal.class);

                final UserHandle user = UserHandle.getUserHandleForUid(callingUid);
                final boolean cameraPrivacyEnabled = spmi.isSensorPrivacyEnabled(
                        user.getIdentifier(), SensorPrivacyManager.Sensors.CAMERA);
                if (cameraPrivacyEnabled) {
                    AppOpsManagerInternal aomi = LocalServices.getService(
                            AppOpsManagerInternal.class);
                    int numCameraRestrictions = aomi.getOpRestrictionCount(
                            AppOpsManager.OP_CAMERA, user, callingPackage, null);
                    if (numCameraRestrictions == 1) {
                        // Only restricted by the toggles, do not restrict
                        return ACTIVITY_RESTRICTION_NONE;
                    }
                }
            }
            return ACTIVITY_RESTRICTION_APPOP;
        }

        return ACTIVITY_RESTRICTION_NONE;
    }

    void setLaunchSource(int uid) {
        mLaunchingActivityWakeLock.setWorkSource(new WorkSource(uid));
    }

    void acquireLaunchWakelock() {
        if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != SYSTEM_UID) {
            throw new IllegalStateException("Calling must be system uid");
        }
        mLaunchingActivityWakeLock.acquire();
        if (!mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
            // To be safe, don't allow the wake lock to be held for too long.
            mHandler.sendEmptyMessageDelayed(LAUNCH_TIMEOUT_MSG, LAUNCH_TIMEOUT);
        }
    }

    /**
     * Called when all resumed tasks/root-tasks are idle.
     */
    @GuardedBy("mService")
    private void checkFinishBootingLocked() {
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
    }

    void activityIdleInternal(ActivityRecord r, boolean fromTimeout,
            boolean processPausingActivities, Configuration config) {
        if (DEBUG_ALL) Slog.v(TAG, "Activity idle: " + r);

        if (r != null) {
            if (DEBUG_IDLE) Slog.d(TAG_IDLE, "activityIdleInternal: Callers="
                    + Debug.getCallers(4));
            mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
            r.finishLaunchTickingLocked();
            if (fromTimeout) {
                reportActivityLaunched(fromTimeout, r, INVALID_DELAY, -1 /* launchState */);
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

            // Check if able to finish booting when device is booting and all resumed activities
            // are idle.
            if ((mService.isBooting() && mRootWindowContainer.allResumedActivitiesIdle())
                    || fromTimeout) {
                checkFinishBootingLocked();
            }

            // When activity is idle, we consider the relaunch must be successful, so let's clear
            // the flag.
            r.mRelaunchReason = RELAUNCH_REASON_NONE;
        }

        if (mRootWindowContainer.allResumedActivitiesIdle()) {
            if (r != null) {
                mService.scheduleAppGcsLocked();
                mRecentTasks.onActivityIdle(r);
            }

            if (mLaunchingActivityWakeLock.isHeld()) {
                mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != SYSTEM_UID) {
                    throw new IllegalStateException("Calling must be system uid");
                }
                mLaunchingActivityWakeLock.release();
            }
            mRootWindowContainer.ensureActivitiesVisible();
        }

        // Atomically retrieve all of the other things to do.
        processStoppingAndFinishingActivities(r, processPausingActivities, "idle");

        if (DEBUG_IDLE) {
            Slogf.i(TAG, "activityIdleInternal(): r=%s, mStartingUsers=%s", r, mStartingUsers);
        }

        if (!mStartingUsers.isEmpty()) {
            final ArrayList<UserState> startingUsers = new ArrayList<>(mStartingUsers);
            mStartingUsers.clear();
            // Complete user switch.
            for (int i = 0; i < startingUsers.size(); i++) {
                UserState userState = startingUsers.get(i);
                Slogf.i(TAG, "finishing switch of user %d", userState.mHandle.getIdentifier());
                mService.mAmInternal.finishUserSwitch(userState);
            }
        }

        mService.mH.post(() -> mService.mAmInternal.trimApplications());
    }

    /** This doesn't just find a task, it also moves the task to front. */
    void findTaskToMoveToFront(Task task, int flags, ActivityOptions options, String reason,
            boolean forceNonResizeable) {
        Task currentRootTask = task.getRootTask();
        if (currentRootTask == null) {
            Slog.e(TAG, "findTaskToMoveToFront: can't move task="
                    + task + " to front. Root task is null");
            return;
        }

        try {
            if ((flags & ActivityManager.MOVE_TASK_NO_USER_ACTION) == 0) {
                mUserLeaving = true;
            }

            mService.deferWindowLayout();
            final Transition newTransition = task.mTransitionController.isShellTransitionsEnabled()
                    ? task.mTransitionController.isCollecting() ? null
                    : task.mTransitionController.createTransition(TRANSIT_TO_FRONT) : null;
            task.mTransitionController.collect(task);
            reason = reason + " findTaskToMoveToFront";
            boolean reparented = false;
            if (task.isResizeable() && canUseActivityOptionsLaunchBounds(options)) {
                Task targetRootTask =
                        mRootWindowContainer.getOrCreateRootTask(null, options, task, ON_TOP);

                if (targetRootTask != currentRootTask) {
                    moveHomeRootTaskToFrontIfNeeded(flags, targetRootTask.getDisplayArea(), reason);
                    task.reparent(targetRootTask, ON_TOP, REPARENT_KEEP_ROOT_TASK_AT_FRONT,
                            !ANIMATE, DEFER_RESUME, reason);
                    currentRootTask = targetRootTask;
                    reparented = true;
                    // task.reparent() should already placed the task on top,
                    // still need moveTaskToFrontLocked() below for any transition settings.
                }
                // The resizeTask must be done after the task is moved to the correct root task,
                // because Task's setBounds() also updates dim layer's bounds, but that has
                // dependency on the root task.
                final Rect bounds = options.getLaunchBounds();
                task.setBounds(bounds);
            }

            if (!reparented) {
                moveHomeRootTaskToFrontIfNeeded(flags, currentRootTask.getDisplayArea(), reason);
            }

            final ActivityRecord r = task.getTopNonFinishingActivity();
            currentRootTask.moveTaskToFront(task, false /* noAnimation */, options,
                    r == null ? null : r.appTimeTracker, reason);

            if (DEBUG_ROOT_TASK) Slog.d(TAG_ROOT_TASK,
                    "findTaskToMoveToFront: moved to front of root task=" + currentRootTask);

            handleNonResizableTaskIfNeeded(task, WINDOWING_MODE_UNDEFINED,
                    mRootWindowContainer.getDefaultTaskDisplayArea(), currentRootTask,
                    forceNonResizeable);
            if (r != null && (options == null || !options.getDisableStartingWindow())) {
                // Use a starting window to reduce the transition latency for reshowing the task.
                // Note that with shell transition, this should be executed before requesting
                // transition to avoid delaying the starting window.
                r.showStartingWindow(true /* taskSwitch */);
            }
            if (newTransition != null) {
                task.mTransitionController.requestStartTransition(newTransition, task,
                        options != null ? options.getRemoteTransition() : null,
                        null /* displayChange */);
            }
        } finally {
            mUserLeaving = false;
            mService.continueWindowLayout();
        }
    }

    private void moveHomeRootTaskToFrontIfNeeded(int flags, TaskDisplayArea taskDisplayArea,
            String reason) {
        final Task focusedRootTask = taskDisplayArea.getFocusedRootTask();

        if ((taskDisplayArea.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                && (flags & ActivityManager.MOVE_TASK_WITH_HOME) != 0)
                || (focusedRootTask != null && focusedRootTask.isActivityTypeRecents())) {
            // We move root home task to front when we are on a fullscreen display area and
            // caller has requested the home activity to move with it. Or the previous root task
            // is recents.
            taskDisplayArea.moveHomeRootTaskToFront(reason);
        }
    }

    boolean canUseActivityOptionsLaunchBounds(ActivityOptions options) {
        // We use the launch bounds in the activity options is the device supports freeform
        // window management or is launching into the root pinned task.
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

    private void removePinnedRootTaskInSurfaceTransaction(Task rootTask) {
        rootTask.mTransitionController.requestTransitionIfNeeded(TRANSIT_TO_BACK, 0 /* flags */,
                rootTask, rootTask.mDisplayContent, null /* remoteTransition */,
                null /* displayChange */);
        /**
         * Workaround: Force-stop all the activities in the root pinned task before we reparent them
         * to the fullscreen root task.  This is to guarantee that when we are removing a root task,
         * that the client receives onStop() before new windowing mode is set.
         * We do this by detaching the root task from the display so that it will be considered
         * invisible when ensureActivitiesVisible() is called, and all of its activities will be
         * marked invisible as well and added to the stopping list.  After which we process the
         * stopping list by handling the idle.
         */
        rootTask.cancelAnimation();
        rootTask.setForceHidden(FLAG_FORCE_HIDDEN_FOR_PINNED_TASK, true /* set */);
        rootTask.ensureActivitiesVisible(null /* starting */);
        activityIdleInternal(null /* idleActivity */, false /* fromTimeout */,
                true /* processPausingActivities */, null /* configuration */);

        // Reparent all the tasks to the bottom of the display
        final DisplayContent toDisplay =
                mRootWindowContainer.getDisplayContent(DEFAULT_DISPLAY);

        mService.deferWindowLayout();
        try {
            rootTask.setWindowingMode(WINDOWING_MODE_UNDEFINED);
            if (rootTask.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
                rootTask.setBounds(null);
            }
            toDisplay.getDefaultTaskDisplayArea().positionTaskBehindHome(rootTask);

            // Follow on the workaround: activities are kept force hidden till the new windowing
            // mode is set.
            rootTask.setForceHidden(FLAG_FORCE_HIDDEN_FOR_PINNED_TASK, false /* set */);
            mRootWindowContainer.ensureActivitiesVisible();
            mRootWindowContainer.resumeFocusedTasksTopActivities();
        } finally {
            mService.continueWindowLayout();
        }
    }

    /**
     * Removes the root task associated with the given {@param rootTask}. If the {@param rootTask}
     * is the pinned task, then its child tasks are not explicitly removed when the root task is
     * destroyed, but instead moved back onto the TaskDisplayArea.
     */
    void removeRootTask(Task rootTask) {
        if (rootTask.getWindowingMode() == WINDOWING_MODE_PINNED) {
            removePinnedRootTaskInSurfaceTransaction(rootTask);
        } else {
            rootTask.forAllLeafTasks(task -> {
                removeTask(task, true /* killProcess */, REMOVE_FROM_RECENTS, "remove-root-task");
            }, true /* traverseTopToBottom */);
        }
    }

    /**
     * Removes the task with the specified task id.
     *
     * @param taskId Identifier of the task to be removed.
     * @param killProcess Kill any process associated with the task if possible.
     * @param removeFromRecents Whether to also remove the task from recents.
     * @return Returns true if the given task was found and removed.
     */
    boolean removeTaskById(int taskId, boolean killProcess, boolean removeFromRecents,
            String reason, int callingUid, int callingPid) {
        final Task task =
                mRootWindowContainer.anyTaskForId(taskId, MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
        if (task != null) {
            removeTask(task, killProcess, removeFromRecents, reason, callingUid, callingPid, null);
            return true;
        }
        Slog.w(TAG, "Request to remove task ignored for non-existent task " + taskId);
        return false;
    }

    void removeTask(Task task, boolean killProcess, boolean removeFromRecents, String reason) {
        removeTask(task, killProcess, removeFromRecents, reason, SYSTEM_UID, INVALID_PID, null);
    }

    void removeTask(Task task, boolean killProcess, boolean removeFromRecents, String reason,
            int callingUid, int callingPid, String callerActivityClassName) {
        if (task.mInRemoveTask) {
            // Prevent recursion.
            return;
        }
        task.mTransitionController.requestCloseTransitionIfNeeded(task);
        // Consume the stopping activities immediately so activity manager won't skip killing
        // the process because it is still foreground state, i.e. RESUMED -> PAUSING set from
        // removeActivities -> finishIfPossible.
        if (killProcess) {
            ArrayList<ActivityRecord> activities = null;
            for (int i = mStoppingActivities.size() - 1; i >= 0; i--) {
                final ActivityRecord r = mStoppingActivities.get(i);
                if (!r.finishing && r.isState(RESUMED) && r.getTask() == task) {
                    if (activities == null) {
                        activities = new ArrayList<>();
                    }
                    activities.add(r);
                    mStoppingActivities.remove(i);
                }
            }
            if (activities != null) {
                // This can update to background state.
                for (int i = activities.size() - 1; i >= 0; i--) {
                    activities.get(i).stopIfPossible();
                }
            }
        }
        task.mInRemoveTask = true;
        try {
            task.removeActivities(reason, false /* excludingTaskOverlay */);
            cleanUpRemovedTask(task, killProcess, removeFromRecents);
            mService.getLockTaskController().clearLockedTask(task);
            mService.getTaskChangeNotificationController().notifyTaskStackChanged();
            if (task.isPersistable) {
                mService.notifyTaskPersisterLocked(null, true);
            }
            mBalController.checkActivityAllowedToClearTask(
                            task, callingUid, callingPid, callerActivityClassName);
        } finally {
            task.mInRemoveTask = false;
        }
    }
    static CharSequence getApplicationLabel(PackageManager pm, String packageName) {
        try {
            ApplicationInfo launchedFromPackageInfo = pm.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0));
            return pm.getApplicationLabel(launchedFromPackageInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /** This method should only be called for leaf task. */
    private void cleanUpRemovedTask(Task task, boolean killProcess, boolean removeFromRecents) {
        if (removeFromRecents) {
            mRecentTasks.remove(task);
        }
        final Intent baseIntent = task.getBaseIntent();
        final ComponentName component = baseIntent != null ? baseIntent.getComponent() : null;
        if (component == null) {
            Slog.w(TAG, "No component for base intent of task: " + task);
            return;
        }

        // Find any running services associated with this app and stop if needed.
        final Message msg = PooledLambda.obtainMessage(ActivityManagerInternal::cleanUpServices,
                mService.mAmInternal, task.mUserId, component, new Intent(baseIntent));
        mService.mH.sendMessage(msg);

        if (!killProcess) {
            return;
        }
        // Give a chance for the client to handle Activity#onStop(). The timeout waits for
        // onDestroy because the client defers to report completion of stopped, the callback from
        // DestroyActivityItem may be called first.
        final ActivityRecord top = task.getTopMostActivity();
        if (top != null && top.finishing && !top.mAppStopped && top.lastVisibleTime > 0
                && !task.mKillProcessesOnDestroyed && top.hasProcess()) {
            task.mKillProcessesOnDestroyed = true;
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(KILL_TASK_PROCESSES_TIMEOUT_MSG, task),
                    KILL_TASK_PROCESSES_TIMEOUT_MS);
            return;
        }
        killTaskProcessesIfPossible(task);
    }

    void removeTimeoutOfKillProcessesOnProcessDied(@NonNull ActivityRecord r, @NonNull Task task) {
        if (r.packageName.equals(task.getBasePackageName())) {
            task.mKillProcessesOnDestroyed = false;
            mHandler.removeMessages(KILL_TASK_PROCESSES_TIMEOUT_MSG, task);
        }
    }

    void killTaskProcessesOnDestroyedIfNeeded(Task task) {
        if (task == null || !task.mKillProcessesOnDestroyed) return;
        final int[] numDestroyingActivities = new int[1];
        task.forAllActivities(r ->  {
            if (r.finishing && r.lastVisibleTime > 0 && r.attachedToProcess()) {
                numDestroyingActivities[0]++;
            }
        });
        if (numDestroyingActivities[0] > 1) {
            // Skip if there are still destroying activities. When the last activity reports
            // destroyed, the number will be 1 to proceed the kill.
            return;
        }
        mHandler.removeMessages(KILL_TASK_PROCESSES_TIMEOUT_MSG, task);
        killTaskProcessesIfPossible(task);
    }

    /** Kills the processes in the task if it doesn't contain perceptible components. */
    private void killTaskProcessesIfPossible(Task task) {
        task.mKillProcessesOnDestroyed = false;
        final String pkg = task.getBasePackageName();
        ArrayList<Object> procsToKill = null;
        ArrayMap<String, SparseArray<WindowProcessController>> pmap =
                mService.mProcessNames.getMap();
        for (int i = 0; i < pmap.size(); i++) {

            SparseArray<WindowProcessController> uids = pmap.valueAt(i);
            for (int j = 0; j < uids.size(); j++) {
                WindowProcessController proc = uids.valueAt(j);
                if (proc.mUserId != task.mUserId) {
                    // Don't kill process for a different user.
                    continue;
                }
                if (proc == mService.mHomeProcess) {
                    // Don't kill the home process along with tasks from the same package.
                    continue;
                }
                if (!proc.containsPackage(pkg)) {
                    // Don't kill process that is not associated with this task.
                    continue;
                }

                if (!proc.shouldKillProcessForRemovedTask(task)) {
                    // Don't kill process(es) that has an activity in a different task that is also
                    // in recents, or has an activity not stopped.
                    return;
                }

                if (proc.hasForegroundServices()) {
                    // Don't kill process(es) with foreground service.
                    return;
                }

                if (procsToKill == null) {
                    procsToKill = new ArrayList<>();
                }
                // Add process to kill list.
                procsToKill.add(proc);
            }
        }
        if (procsToKill == null) return;

        // Kill the running processes. Post on handle since we don't want to hold the service lock
        // while calling into AM.
        final Message m = PooledLambda.obtainMessage(
                ActivityManagerInternal::killProcessesForRemovedTask, mService.mAmInternal,
                procsToKill);
        mService.mH.sendMessage(m);
    }

    /**
     * Called to restore the state of the task into the root task that it's supposed to go into.
     *
     * @param task The recent task to be restored.
     * @param aOptions The activity options to use for restoration.
     * @param onTop If the root task for the task should be the topmost on the display.
     * @return true if the task has been restored successfully.
     */
    boolean restoreRecentTaskLocked(Task task, ActivityOptions aOptions, boolean onTop) {
        final Task rootTask =
                mRootWindowContainer.getOrCreateRootTask(null, aOptions, task, onTop);
        final WindowContainer parent = task.getParent();

        if (parent == rootTask || task == rootTask) {
            // Nothing else to do since it is already restored in the right root task.
            return true;
        }

        if (parent != null) {
            // Task has already been restored once. Just re-parent it to the new root task.
            task.reparent(rootTask, POSITION_TOP, true /*moveParents*/, "restoreRecentTaskLocked");
            return true;
        }

        rootTask.addChild(task, onTop, true /* showForAllUsers */);
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                "Added restored task=" + task + " to root task=" + rootTask);
        return true;
    }

    @Override
    public void onRecentTaskAdded(Task task) {
        task.touchActiveTime();
    }

    @Override
    public void onRecentTaskRemoved(Task task, boolean wasTrimmed, boolean killProcess) {
        if (wasTrimmed) {
            // Task was trimmed from the recent tasks list -- remove the active task record as well
            // since the user won't really be able to go back to it
            removeTaskById(task.mTaskId, killProcess, false /* removeFromRecents */,
                    "recent-task-trimmed", SYSTEM_UID, INVALID_PID);
        }
        task.removedFromRecents();
    }

    /**
     * Returns the reparent target root task, creating the root task if necessary.  This call
     * also enforces the various checks on tasks that are going to be reparented from one root
     * task to another.
     */
    // TODO: Look into changing users to this method to DisplayContent.resolveWindowingMode()
    Task getReparentTargetRootTask(Task task, Task rootTask, boolean toTop) {
        final Task prevRootTask = task.getRootTask();
        final int rootTaskId = rootTask.mTaskId;
        final boolean inMultiWindowMode = rootTask.inMultiWindowMode();

        // Check that we aren't reparenting to the same root task that the task is already in
        if (prevRootTask != null && prevRootTask.mTaskId == rootTaskId) {
            Slog.w(TAG, "Can not reparent to same root task, task=" + task
                    + " already in rootTaskId=" + rootTaskId + " by " + Debug.getCallers(8));
            return prevRootTask;
        }

        // Ensure that we aren't trying to move into a multi-window root task without multi-window
        // support
        if (inMultiWindowMode && !mService.mSupportsMultiWindow) {
            throw new IllegalArgumentException("Device doesn't support multi-window, can not"
                    + " reparent task=" + task + " to root-task=" + rootTask);
        }

        // Ensure that we're not moving a task to a dynamic root task if device doesn't support
        // multi-display.
        if (rootTask.getDisplayId() != DEFAULT_DISPLAY && !mService.mSupportsMultiDisplay) {
            throw new IllegalArgumentException("Device doesn't support multi-display, can not"
                    + " reparent task=" + task + " to rootTaskId=" + rootTaskId);
        }

        // Ensure that we aren't trying to move into a freeform root task without freeform support
        if (rootTask.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && !mService.mSupportsFreeformWindowManagement) {
            throw new IllegalArgumentException("Device doesn't support freeform, can not reparent"
                    + " task=" + task);
        }

        if (rootTask.inPinnedWindowingMode()) {
            throw new IllegalArgumentException("No support to reparent to PIP, task=" + task);
        }

        // Leave the task in its current root task or a fullscreen root task if it isn't
        // resizeable and the preferred root task is in multi-window mode.
        if (inMultiWindowMode
                && !task.supportsMultiWindowInDisplayArea(rootTask.getDisplayArea())) {
            Slog.w(TAG, "Can not move unresizeable task=" + task + " to multi-window root task="
                    + rootTask + " Moving to a fullscreen root task instead.");
            if (prevRootTask != null) {
                return prevRootTask;
            }
            rootTask = rootTask.getDisplayArea().createRootTask(
                    WINDOWING_MODE_FULLSCREEN, rootTask.getActivityType(), toTop);
        }
        return rootTask;
    }

    void goingToSleepLocked() {
        scheduleSleepTimeout();
        if (!mGoingToSleepWakeLock.isHeld()) {
            mGoingToSleepWakeLock.acquire();
            if (mLaunchingActivityWakeLock.isHeld()) {
                if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != SYSTEM_UID) {
                    throw new IllegalStateException("Calling must be system uid");
                }
                mLaunchingActivityWakeLock.release();
                mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
            }
        }

        mRootWindowContainer.applySleepTokens(false /* applyToRootTasks */);

        checkReadyForSleepLocked(true /* allowDelay */);
    }

    boolean shutdownLocked(int timeout) {
        goingToSleepLocked();

        boolean timedout = false;
        final long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            if (!mRootWindowContainer.putTasksToSleep(
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

    void checkReadyForSleepLocked(boolean allowDelay) {
        if (!mService.isSleepingOrShuttingDownLocked()) {
            // Do not care.
            return;
        }

        if (!mRootWindowContainer.putTasksToSleep(
                allowDelay, false /* shuttingDown */)) {
            return;
        }

        // End power mode launch before going sleep
        mService.endPowerMode(ActivityTaskManagerService.POWER_MODE_REASON_ALL);

        // Rank task layers to make sure the {@link Task#mLayerRank} is updated.
        mRootWindowContainer.rankTaskLayers();

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

        final Task rootTask = r.getRootTask();
        if (rootTask.getDisplayArea().allResumedActivitiesComplete()) {
            mRootWindowContainer.ensureActivitiesVisible();
            // Make sure activity & window visibility should be identical
            // for all displays in this stage.
            mRootWindowContainer.executeAppTransitionForAllDisplay();
            return true;
        }
        return false;
    }

    // Called when WindowManager has finished animating the launchingBehind activity to the back.
    private void handleLaunchTaskBehindCompleteLocked(ActivityRecord r) {
        final Task task = r.getTask();
        final Task rootTask = task.getRootTask();

        mRecentTasks.add(task);
        mService.getTaskChangeNotificationController().notifyTaskStackChanged();
        rootTask.ensureActivitiesVisible(null /* starting */);

        // When launching tasks behind, update the last active time of the top task after the new
        // task has been shown briefly
        final ActivityRecord top = rootTask.getTopNonFinishingActivity();
        if (top != null) {
            top.getTask().touchActiveTime();
        }
    }

    void scheduleLaunchTaskBehindComplete(IBinder token) {
        mHandler.obtainMessage(LAUNCH_TASK_BEHIND_COMPLETE, token).sendToTarget();
    }

    /**
     * Processes the activities to be stopped or destroyed. This should be called when the resumed
     * activities are idle or drawn.
     */
    void processStoppingAndFinishingActivities(ActivityRecord launchedActivity,
            boolean processPausingActivities, String reason) {
        // Stop any activities that are scheduled to do so but have been waiting for the transition
        // animation to finish.
        boolean displaySwapping = false;
        ArrayList<ActivityRecord> readyToStopActivities = null;
        for (int i = 0; i < mStoppingActivities.size(); i++) {
            final ActivityRecord s = mStoppingActivities.get(i);
            // Activity in a force hidden task should not be counted as animating, i.e., we want to
            // send onStop before any configuration change when removing pip transition is ongoing.
            final boolean animating = s.isInTransition()
                    && s.getTask() != null && !s.getTask().isForceHidden();
            displaySwapping |= s.isDisplaySleepingAndSwapping();
            ProtoLog.v(WM_DEBUG_STATES, "Stopping %s: nowVisible=%b animating=%b "
                    + "finishing=%s", s, s.nowVisible, animating, s.finishing);
            if ((!animating && !displaySwapping) || mService.mShuttingDown
                    || s.getRootTask().isForceHiddenForPinnedTask()) {
                if (!processPausingActivities && s.isState(PAUSING)) {
                    // Defer processing pausing activities in this iteration and reschedule
                    // a delayed idle to reprocess it again
                    removeIdleTimeoutForActivity(launchedActivity);
                    scheduleIdleTimeout(launchedActivity);
                    continue;
                }

                ProtoLog.v(WM_DEBUG_STATES, "Ready to stop: %s", s);
                if (readyToStopActivities == null) {
                    readyToStopActivities = new ArrayList<>();
                }
                readyToStopActivities.add(s);

                mStoppingActivities.remove(i);
                i--;
            }
        }

        // Stopping activities are deferred processing if the display is swapping. Check again
        // later to ensure the stopping activities can be stopped after display swapped.
        if (displaySwapping) {
            mHandler.postDelayed(() -> {
                synchronized (mService.mGlobalLock) {
                    scheduleProcessStoppingAndFinishingActivitiesIfNeeded();
                }
            }, SCHEDULE_FINISHING_STOPPING_ACTIVITY_MS);
        }

        final int numReadyStops = readyToStopActivities == null ? 0 : readyToStopActivities.size();
        for (int i = 0; i < numReadyStops; i++) {
            final ActivityRecord r = readyToStopActivities.get(i);
            if (r.isInHistory()) {
                if (r.finishing) {
                    // TODO(b/137329632): Wait for idle of the right activity, not just any.
                    r.destroyIfPossible(reason);
                } else {
                    r.stopIfPossible();
                }
            }
        }

        final int numFinishingActivities = mFinishingActivities.size();
        if (numFinishingActivities == 0) {
            return;
        }

        // Finish any activities that are scheduled to do so but have been waiting for the next one
        // to start.
        final ArrayList<ActivityRecord> finishingActivities = new ArrayList<>(mFinishingActivities);
        mFinishingActivities.clear();
        for (int i = 0; i < numFinishingActivities; i++) {
            final ActivityRecord r = finishingActivities.get(i);
            if (r.isInHistory()) {
                r.destroyImmediately("finish-" + reason);
            }
        }
    }

    void removeHistoryRecords(WindowProcessController app) {
        removeHistoryRecords(mStoppingActivities, app, "mStoppingActivities");
        removeHistoryRecords(mFinishingActivities, app, "mFinishingActivities");
        removeHistoryRecords(mNoHistoryActivities, app, "mNoHistoryActivities");
    }

    private void removeHistoryRecords(ArrayList<ActivityRecord> list, WindowProcessController app,
            String listName) {
        int i = list.size();
        if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                "Removing app " + this + " from list " + listName + " with " + i + " entries");
        while (i > 0) {
            i--;
            ActivityRecord r = list.get(i);
            if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "Record #" + i + " " + r);
            if (r.app == app) {
                if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP, "---> REMOVING this entry!");
                list.remove(i);
                r.removeTimeouts();
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println();
        pw.println("ActivityTaskSupervisor state:");
        mRootWindowContainer.dump(pw, prefix, true /* dumpAll */);
        getKeyguardController().dump(pw, prefix);
        mService.getLockTaskController().dump(pw, prefix);
        pw.print(prefix);
        pw.println("mCurTaskIdForUser=" + mCurTaskIdForUser);
        pw.println(prefix + "mUserRootTaskInFront=" + mRootWindowContainer.mUserRootTaskInFront);
        pw.println(prefix + "mVisibilityTransactionDepth=" + mVisibilityTransactionDepth);
        pw.print(prefix); pw.print("isHomeRecentsComponent=");
        pw.println(mRecentTasks.isRecentsComponentHomeActivity(mRootWindowContainer.mCurrentUser));
        if (!mWaitingActivityLaunched.isEmpty()) {
            pw.println(prefix + "mWaitingActivityLaunched=");
            for (int i = mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
                mWaitingActivityLaunched.get(i).dump(pw, prefix + "  ");
            }
        }
        pw.println(prefix + "mNoHistoryActivities=" + mNoHistoryActivities);
        pw.println();
    }

    static boolean printThisActivity(PrintWriter pw, ActivityRecord activity, String dumpPackage,
            boolean needSep, String prefix, Runnable header) {
        return printThisActivity(pw, activity, dumpPackage, INVALID_DISPLAY, needSep, prefix,
                header);
    }

    static boolean printThisActivity(PrintWriter pw, ActivityRecord activity, String dumpPackage,
            int displayIdFilter, boolean needSep, String prefix, Runnable header) {
        if (activity != null && (displayIdFilter == INVALID_DISPLAY
                || displayIdFilter == activity.getDisplayId())) {
            if (dumpPackage == null || dumpPackage.equals(activity.packageName)) {
                if (needSep) {
                    pw.println();
                }
                if (header != null) {
                    header.run();
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
            String dumpPackage, boolean needNL, Runnable header, Task lastTask) {
        boolean printed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            final ActivityRecord r = list.get(i);
            ActivityRecord.dumpActivity(fd, pw, i, r, prefix, label, complete, brief,
                    client, dumpPackage, needNL, header, lastTask);
            lastTask = r.getTask();
            header = null;
            needNL = client && r.attachedToProcess();
        }
        return printed;
    }

    void scheduleIdleTimeout(ActivityRecord next) {
        if (DEBUG_IDLE) Slog.d(TAG_IDLE, "scheduleIdleTimeout: Callers=" + Debug.getCallers(4));
        Message msg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG, next);
        mHandler.sendMessageDelayed(msg, IDLE_TIMEOUT);
    }

    final void scheduleIdle() {
        if (!mHandler.hasMessages(IDLE_NOW_MSG)) {
            if (DEBUG_IDLE) Slog.d(TAG_IDLE, "scheduleIdle: Callers=" + Debug.getCallers(4));
            mHandler.sendEmptyMessage(IDLE_NOW_MSG);
        }
    }

    /**
     * Updates the record of top resumed activity when it changes and handles reporting of the
     * state changes to previous and new top activities. It will immediately dispatch top resumed
     * state loss message to previous top activity (if haven't done it already). After the previous
     * activity releases the top state and reports back, message about acquiring top state will be
     * sent to the new top resumed activity.
     */
    void updateTopResumedActivityIfNeeded(String reason) {
        final ActivityRecord prevTopActivity = mTopResumedActivity;
        final Task topRootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
        if (topRootTask == null || topRootTask.getTopResumedActivity() == prevTopActivity) {
            if (topRootTask == null) {
                // There's no focused task and there won't have any resumed activity either.
                scheduleTopResumedActivityStateLossIfNeeded();
            }
            if (mService.isSleepingLocked()) {
                // There won't be a next resumed activity. The top process should still be updated
                // according to the current top focused activity.
                mService.updateTopApp(null /* topResumedActivity */);
            }
            return;
        }

        // Ask previous activity to release the top state.
        scheduleTopResumedActivityStateLossIfNeeded();

        // Update the current top activity.
        mTopResumedActivity = topRootTask.getTopResumedActivity();
        // Update process state if there is no activity state change (e.g. focus change between
        // multi-window mode activities) to make sure that the current top has top oom-adj.
        // If the previous top is null, there should be activity state change from it, Then the
        // process state should also have been updated so no need to update again.
        if (mTopResumedActivity != null && prevTopActivity != null) {
            if (mTopResumedActivity.app != null) {
                mTopResumedActivity.app.addToPendingTop();
            }
            mService.updateOomAdj();
        }
        // Update the last resumed activity and focused app when the top resumed activity changed
        // because the new top resumed activity might be already resumed and thus won't have
        // activity state change to update the records to AMS.
        if (mTopResumedActivity != null) {
            mService.setLastResumedActivityUncheckLocked(mTopResumedActivity, reason);
        }
        scheduleTopResumedActivityStateIfNeeded();

        mService.updateTopApp(mTopResumedActivity);
    }

    /** Schedule current top resumed activity state loss */
    private void scheduleTopResumedActivityStateLossIfNeeded() {
        if (mTopResumedActivity == null) {
            return;
        }

        // mTopResumedActivityWaitingForPrev == true at this point would mean that an activity
        // before the prevTopActivity one hasn't reported back yet. So server never sent the top
        // resumed state change message to prevTopActivity.
        if (!mTopResumedActivityWaitingForPrev
                && mTopResumedActivity.scheduleTopResumedActivityChanged(false /* onTop */)) {
            scheduleTopResumedStateLossTimeout(mTopResumedActivity);
            mTopResumedActivityWaitingForPrev = true;
            mTopResumedActivity = null;
        }
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
        ProtoLog.v(WM_DEBUG_STATES, "Waiting for top state to be released by %s", r);
    }

    /**
     * Handle a loss of top resumed state by an activity - update internal state and inform next top
     * activity if needed.
     */
    void handleTopResumedStateReleased(boolean timeout) {
        ProtoLog.v(WM_DEBUG_STATES, "Top resumed state released %s",
                    (timeout ? "(due to timeout)" : "(transition complete)"));

        mHandler.removeMessages(TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG);
        if (!mTopResumedActivityWaitingForPrev) {
            // Top resumed activity state loss already handled.
            return;
        }
        mTopResumedActivityWaitingForPrev = false;
        scheduleTopResumedActivityStateIfNeeded();
    }

    void removeIdleTimeoutForActivity(ActivityRecord r) {
        if (DEBUG_IDLE) Slog.d(TAG_IDLE, "removeTimeoutsForActivity: Callers="
                + Debug.getCallers(4));
        mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
    }

    final void scheduleResumeTopActivities() {
        if (!mHandler.hasMessages(RESUME_TOP_ACTIVITY_MSG)) {
            mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
        }
    }

    void scheduleProcessStoppingAndFinishingActivitiesIfNeeded() {
        if (mStoppingActivities.isEmpty() && mFinishingActivities.isEmpty()) {
            return;
        }
        if (mRootWindowContainer.allResumedActivitiesIdle()) {
            scheduleIdle();
            return;
        }
        if (!mHandler.hasMessages(PROCESS_STOPPING_AND_FINISHING_MSG)
                && mRootWindowContainer.allResumedActivitiesVisible()) {
            mHandler.sendEmptyMessage(PROCESS_STOPPING_AND_FINISHING_MSG);
        }
    }

    void removeSleepTimeouts() {
        mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
    }

    final void scheduleSleepTimeout() {
        removeSleepTimeouts();
        mHandler.sendEmptyMessageDelayed(SLEEP_TIMEOUT_MSG, SLEEP_TIMEOUT);
    }

    boolean hasScheduledRestartTimeouts(ActivityRecord r) {
        return mHandler.hasMessages(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r);
    }

    void removeRestartTimeouts(ActivityRecord r) {
        mHandler.removeMessages(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r);
    }

    final void scheduleRestartTimeout(ActivityRecord r) {
        removeRestartTimeouts(r);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r),
                WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION);
    }

    void handleNonResizableTaskIfNeeded(Task task, int preferredWindowingMode,
            TaskDisplayArea preferredTaskDisplayArea, Task actualRootTask) {
        handleNonResizableTaskIfNeeded(task, preferredWindowingMode, preferredTaskDisplayArea,
                actualRootTask, false /* forceNonResizable */);
    }

    void handleNonResizableTaskIfNeeded(Task task, int preferredWindowingMode,
            TaskDisplayArea preferredTaskDisplayArea, Task actualRootTask,
            boolean forceNonResizable) {
        final boolean isSecondaryDisplayPreferred = preferredTaskDisplayArea != null
                && preferredTaskDisplayArea.getDisplayId() != DEFAULT_DISPLAY;
        if (!task.isActivityTypeStandardOrUndefined()) {
            return;
        }

        // Handle incorrect launch/move to secondary display if needed.
        if (isSecondaryDisplayPreferred) {
            if (!task.canBeLaunchedOnDisplay(task.getDisplayId())) {
                throw new IllegalStateException("Task resolved to incompatible display");
            }

            final DisplayContent preferredDisplay = preferredTaskDisplayArea.mDisplayContent;
            if (preferredDisplay != task.getDisplayContent()) {
                Slog.w(TAG, "Failed to put " + task + " on display " + preferredDisplay.mDisplayId);
                // Display a warning toast that we failed to put a task on a secondary display.
                mService.getTaskChangeNotificationController()
                        .notifyActivityLaunchOnSecondaryDisplayFailed(task.getTaskInfo(),
                                preferredDisplay.mDisplayId);
            } else if (!forceNonResizable) {
                handleForcedResizableTaskIfNeeded(task, FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY);
            }
            // The information about not support secondary display should already be notified, we
            // don't want to show another message on default display about split-screen. And it may
            // be the case that a resizable activity is launched on a non-resizable task.
            return;
        }

        if (!forceNonResizable) {
            handleForcedResizableTaskIfNeeded(task, FORCED_RESIZEABLE_REASON_SPLIT_SCREEN);
        }
    }

    /** Notifies that the top activity of the task is forced to be resizeable. */
    private void handleForcedResizableTaskIfNeeded(Task task, int reason) {
        final ActivityRecord topActivity = task.getTopNonFinishingActivity();
        if (topActivity == null || topActivity.noDisplay
                || !topActivity.canForceResizeNonResizable(task.getWindowingMode())) {
            return;
        }
        mService.getTaskChangeNotificationController().notifyActivityForcedResizable(
                task.mTaskId, reason, topActivity.info.applicationInfo.packageName);
    }

    void scheduleUpdateMultiWindowMode(Task task) {
        task.forAllActivities(r -> {
            if (r.attachedToProcess()) {
                mMultiWindowModeChangedActivities.add(r);
            }
        });

        if (!mHandler.hasMessages(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG)) {
            mHandler.sendEmptyMessage(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG);
        }
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(Task task, Task prevRootTask) {
        final Task rootTask = task.getRootTask();
        if ((prevRootTask == null || (prevRootTask != rootTask
                && !prevRootTask.inPinnedWindowingMode() && !rootTask.inPinnedWindowingMode()))) {
            return;
        }

        scheduleUpdatePictureInPictureModeIfNeeded(task, rootTask.getRequestedOverrideBounds());
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(Task task, Rect targetRootTaskBounds) {
        task.forAllActivities(r -> {
            if (!r.attachedToProcess()) return;
            mPipModeChangedActivities.add(r);
            // If we are scheduling pip change, then remove this activity from multi-window
            // change list as the processing of pip change will make sure multi-window changed
            // message is processed in the right order relative to pip changed.
            mMultiWindowModeChangedActivities.remove(r);
        });

        mPipModeChangedTargetRootTaskBounds = targetRootTaskBounds;

        if (!mHandler.hasMessages(REPORT_PIP_MODE_CHANGED_MSG)) {
            mHandler.sendEmptyMessage(REPORT_PIP_MODE_CHANGED_MSG);
        }
    }

    void wakeUp(String reason) {
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_APPLICATION,
                "android.server.am:TURN_ON:" + reason);
    }

    /** Starts a batch of visibility updates. */
    void beginActivityVisibilityUpdate() {
        if (mVisibilityTransactionDepth == 0) {
            getKeyguardController().updateVisibility();
        }
        mVisibilityTransactionDepth++;
    }

    /** Ends a batch of visibility updates. */
    void endActivityVisibilityUpdate() {
        mVisibilityTransactionDepth--;
        if (mVisibilityTransactionDepth == 0) {
            computeProcessActivityStateBatch();
        }
    }

    /** Returns {@code true} if the caller is on the path to update visibility. */
    boolean inActivityVisibilityUpdate() {
        return mVisibilityTransactionDepth > 0;
    }

    void setDeferRootVisibilityUpdate(boolean deferUpdate) {
        mDeferRootVisibilityUpdate = deferUpdate;
    }

    boolean isRootVisibilityUpdateDeferred() {
        return mDeferRootVisibilityUpdate;
    }

    /**
     * Called when the state or visibility of an attached activity is changed.
     *
     * @param wpc The process who owns the activity.
     * @param forceBatch Whether to put the changed record to a pending list. If the caller is not
     *                   in the path of visibility update ({@link #inActivityVisibilityUpdate}), it
     *                   must call {@link #computeProcessActivityStateBatch} manually.
     */
    void onProcessActivityStateChanged(WindowProcessController wpc, boolean forceBatch) {
        if (forceBatch || inActivityVisibilityUpdate()) {
            if (!mActivityStateChangedProcs.contains(wpc)) {
                mActivityStateChangedProcs.add(wpc);
            }
            return;
        }
        wpc.computeProcessActivityState();
    }

    void computeProcessActivityStateBatch() {
        if (mActivityStateChangedProcs.isEmpty()) {
            return;
        }
        for (int i = mActivityStateChangedProcs.size() - 1; i >= 0; i--) {
            mActivityStateChangedProcs.get(i).computeProcessActivityState();
        }
        mActivityStateChangedProcs.clear();
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

    private final class ActivityTaskSupervisorHandler extends Handler {

        ActivityTaskSupervisorHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mService.mGlobalLock) {
                if (handleMessageInner(msg)) {
                    return;
                }
            }
            // The cases that some invocations cannot be locked by WM.
            switch (msg.what) {
                case RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG: {
                    final ActivityRecord r = (ActivityRecord) msg.obj;
                    String processName = null;
                    int uid = 0;
                    synchronized (mService.mGlobalLock) {
                        if (r.attachedToProcess() && r.isState(RESTARTING_PROCESS)) {
                            processName = r.app.mName;
                            uid = r.app.mUid;
                        }
                    }
                    if (processName != null) {
                        mService.mAmInternal.killProcess(processName, uid,
                                "restartActivityProcessTimeout");
                    }
                } break;
            }
        }

        private void activityIdleFromMessage(ActivityRecord idleActivity, boolean fromTimeout) {
            activityIdleInternal(idleActivity, fromTimeout,
                    fromTimeout /* processPausingActivities */, null /* config */);
        }

        /**
         * Handles the message with lock held.
         *
         * @return {@code true} if the message is handled.
         */
        private boolean handleMessageInner(Message msg) {
            switch (msg.what) {
                case REPORT_MULTI_WINDOW_MODE_CHANGED_MSG: {
                    for (int i = mMultiWindowModeChangedActivities.size() - 1; i >= 0; i--) {
                        final ActivityRecord r = mMultiWindowModeChangedActivities.remove(i);
                        r.updateMultiWindowMode();
                    }
                } break;
                case REPORT_PIP_MODE_CHANGED_MSG: {
                    for (int i = mPipModeChangedActivities.size() - 1; i >= 0; i--) {
                        final ActivityRecord r = mPipModeChangedActivities.remove(i);
                        r.updatePictureInPictureMode(mPipModeChangedTargetRootTaskBounds,
                                false /* forceUpdate */);
                    }
                } break;
                case IDLE_TIMEOUT_MSG: {
                    if (DEBUG_IDLE) Slog.d(TAG_IDLE,
                            "handleMessage: IDLE_TIMEOUT_MSG: r=" + msg.obj);
                    // We don't at this point know if the activity is fullscreen, so we need to be
                    // conservative and assume it isn't.
                    activityIdleFromMessage((ActivityRecord) msg.obj, true /* fromTimeout */);
                } break;
                case IDLE_NOW_MSG: {
                    if (DEBUG_IDLE) Slog.d(TAG_IDLE, "handleMessage: IDLE_NOW_MSG: r=" + msg.obj);
                    activityIdleFromMessage((ActivityRecord) msg.obj, false /* fromTimeout */);
                } break;
                case RESUME_TOP_ACTIVITY_MSG: {
                    mRootWindowContainer.resumeFocusedTasksTopActivities();
                } break;
                case SLEEP_TIMEOUT_MSG: {
                    if (mService.isSleepingOrShuttingDownLocked()) {
                        Slog.w(TAG, "Sleep timeout!  Sleeping now.");
                        checkReadyForSleepLocked(false /* allowDelay */);
                    }
                } break;
                case LAUNCH_TIMEOUT_MSG: {
                    if (mLaunchingActivityWakeLock.isHeld()) {
                        Slog.w(TAG, "Launch timeout has expired, giving up wake lock!");
                        if (VALIDATE_WAKE_LOCK_CALLER && Binder.getCallingUid() != SYSTEM_UID) {
                            throw new IllegalStateException("Calling must be system uid");
                        }
                        mLaunchingActivityWakeLock.release();
                    }
                } break;
                case PROCESS_STOPPING_AND_FINISHING_MSG: {
                    processStoppingAndFinishingActivities(null /* launchedActivity */,
                            false /* processPausingActivities */, "transit");
                } break;
                case KILL_TASK_PROCESSES_TIMEOUT_MSG: {
                    final Task task = (Task) msg.obj;
                    if (task.mKillProcessesOnDestroyed && task.hasActivity()) {
                        Slog.i(TAG, "Destroy timeout of remove-task, attempt to kill " + task);
                        killTaskProcessesIfPossible(task);
                    }
                } break;
                case LAUNCH_TASK_BEHIND_COMPLETE: {
                    final ActivityRecord r = ActivityRecord.forTokenLocked((IBinder) msg.obj);
                    if (r != null) {
                        handleLaunchTaskBehindCompleteLocked(r);
                    }
                } break;
                case START_HOME_MSG: {
                    mHandler.removeMessages(START_HOME_MSG);

                    // Start home activities on displays with no activities.
                    mRootWindowContainer.startHomeOnEmptyDisplays((String) msg.obj);
                } break;
                case TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG: {
                    final ActivityRecord r = (ActivityRecord) msg.obj;
                    Slog.w(TAG, "Activity top resumed state loss timeout for " + r);
                    if (r.hasProcess()) {
                        mService.logAppTooSlow(r.app, r.topResumedStateLossTime,
                                "top state loss for " + r);
                    }
                    handleTopResumedStateReleased(true /* timeout */);
                } break;
                default:
                    return false;
            }
            return true;
        }
    }

    /**
     * Start the given task from the recent tasks. Do not hold WM global lock when calling this
     * method to avoid potential deadlock or permission deny by UriGrantsManager when resolving
     * activity (see {@link ActivityStarter.Request#resolveActivity} and
     * {@link com.android.server.am.ContentProviderHelper#checkContentProviderUriPermission}).
     *
     * @return The result code of starter.
     */
    int startActivityFromRecents(int callingPid, int callingUid, int taskId,
            SafeActivityOptions options) {
        final Task task;
        final int taskCallingUid;
        final String callingPackage;
        final String callingFeatureId;
        final Intent intent;
        final int userId;
        final ActivityOptions activityOptions = options != null
                ? options.getOptions(this)
                : null;
        boolean moveHomeTaskForward = true;
        synchronized (mService.mGlobalLock) {
            final boolean isCallerRecents = mRecentTasks.isCallerRecents(callingUid);
            int activityType = ACTIVITY_TYPE_UNDEFINED;
            if (activityOptions != null) {
                activityType = activityOptions.getLaunchActivityType();
                if (activityOptions.freezeRecentTasksReordering() && (isCallerRecents
                        || ActivityTaskManagerService.checkPermission(MANAGE_ACTIVITY_TASKS,
                                callingPid, callingUid) == PERMISSION_GRANTED)) {
                    mRecentTasks.setFreezeTaskListReordering();
                }
                if (activityOptions.getLaunchRootTask() != null) {
                    // Don't move home activity forward if there is a launch root set.
                    moveHomeTaskForward = false;
                }
            }
            if (activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS) {
                throw new IllegalArgumentException("startActivityFromRecents: Task "
                        + taskId + " can't be launch in the home/recents root task.");
            }

            boolean shouldStartActivity = false;
            mService.deferWindowLayout();
            try {
                task = mRootWindowContainer.anyTaskForId(taskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE, activityOptions, ON_TOP);
                if (task == null) {
                    mWindowManager.executeAppTransition();
                    throw new IllegalArgumentException(
                            "startActivityFromRecents: Task " + taskId + " not found.");
                }

                if (moveHomeTaskForward) {
                    // We always want to return to the home activity instead of the recents
                    // activity from whatever is started from the recents activity, so move
                    // the home root task forward.
                    // TODO (b/115289124): Multi-display supports for recents.
                    mRootWindowContainer.getDefaultTaskDisplayArea().moveHomeRootTaskToFront(
                            "startActivityFromRecents");
                }

                // If the user must confirm credentials (e.g. when first launching a work
                // app and the Work Challenge is present) let startActivityInPackage handle
                // the intercepting.
                if (!mService.mAmInternal.shouldConfirmCredentials(task.mUserId)
                        && task.getRootActivity() != null) {
                    final ActivityRecord targetActivity = task.getTopNonFinishingActivity();

                    mRootWindowContainer.startPowerModeLaunchIfNeeded(
                            true /* forceSend */, targetActivity);
                    final LaunchingState launchingState =
                            mActivityMetricsLogger.notifyActivityLaunching(task.intent,
                                    // Recents always has a new launching state (not combinable).
                                    null /* caller */, isCallerRecents ? INVALID_UID : callingUid);
                    try {
                        mService.moveTaskToFrontLocked(null /* appThread */,
                                null /* callingPackage */, task.mTaskId, 0, options);
                        // Apply options to prevent pendingOptions be taken when scheduling
                        // activity lifecycle transaction to make sure the override pending app
                        // transition will be applied immediately.
                        if (activityOptions != null
                                && activityOptions.getAnimationType() == ANIM_REMOTE_ANIMATION) {
                            targetActivity.mPendingRemoteAnimation =
                                    activityOptions.getRemoteAnimationAdapter();
                        }
                        targetActivity.applyOptionsAnimation();
                        if (activityOptions != null && activityOptions.getLaunchCookie() != null) {
                            targetActivity.mLaunchCookie = activityOptions.getLaunchCookie();
                        }
                    } finally {
                        mActivityMetricsLogger.notifyActivityLaunched(launchingState,
                                START_TASK_TO_FRONT, false /* newActivityCreated */,
                                targetActivity, activityOptions);
                    }

                    mService.getActivityStartController().postStartActivityProcessingForLastStarter(
                            task.getTopNonFinishingActivity(), ActivityManager.START_TASK_TO_FRONT,
                            task.getRootTask());

                    // As it doesn't go to ActivityStarter.executeRequest() path, we need to resume
                    // app switching here also.
                    mService.resumeAppSwitches();
                    return ActivityManager.START_TASK_TO_FRONT;
                }
                // The task is empty or needs to show the confirmation for credential.
                shouldStartActivity = true;
            } finally {
                if (!shouldStartActivity) {
                    mService.continueWindowLayout();
                }
            }
            taskCallingUid = task.mCallingUid;
            callingPackage = task.mCallingPackage;
            callingFeatureId = task.mCallingFeatureId;
            intent = task.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            userId = task.mUserId;
        }
        // ActivityStarter will acquire the lock where the places need, so execute the request
        // outside of the lock.
        try {
            // We need to temporarily disable the explicit intent filter matching enforcement
            // because Task does not store the resolved type of the intent data, causing filter
            // mismatch in certain cases. (b/240373119)
            PackageManagerServiceUtils.DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS.set(true);
            return mService.getActivityStartController().startActivityInPackage(taskCallingUid,
                    callingPid, callingUid, callingPackage, callingFeatureId, intent, null, null,
                    null, 0, 0, options, userId, task, "startActivityFromRecents",
                    false /* validateIncomingUser */, null /* originatingPendingIntent */,
                    BackgroundStartPrivileges.NONE);
        } finally {
            PackageManagerServiceUtils.DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS.set(false);
            synchronized (mService.mGlobalLock) {
                mService.continueWindowLayout();
            }
        }
    }

    /** The helper to get the top opaque activity of a container. */
    static class OpaqueActivityHelper implements Predicate<ActivityRecord> {
        private ActivityRecord mStarting;
        private boolean mIncludeInvisibleAndFinishing;
        private boolean mIgnoringKeyguard;

        ActivityRecord getOpaqueActivity(
                @NonNull WindowContainer<?> container, boolean ignoringKeyguard) {
            mIncludeInvisibleAndFinishing = true;
            mIgnoringKeyguard = ignoringKeyguard;
            return container.getActivity(this,
                    true /* traverseTopToBottom */, null /* boundary */);
        }

        ActivityRecord getVisibleOpaqueActivity(
                @NonNull WindowContainer<?> container, @Nullable ActivityRecord starting,
                boolean ignoringKeyguard) {
            mStarting = starting;
            mIncludeInvisibleAndFinishing = false;
            mIgnoringKeyguard = ignoringKeyguard;
            final ActivityRecord opaque = container.getActivity(this,
                    true /* traverseTopToBottom */, null /* boundary */);
            mStarting = null;
            return opaque;
        }

        @Override
        public boolean test(ActivityRecord r) {
            if (!mIncludeInvisibleAndFinishing && r != mStarting
                    && ((mIgnoringKeyguard && !r.visibleIgnoringKeyguard)
                    || (!mIgnoringKeyguard && !r.isVisible()))) {
                // Ignore invisible activities that are not the currently starting activity
                // (about to be visible).
                return false;
            }
            return r.occludesParent(mIncludeInvisibleAndFinishing /* includingFinishing */);
        }
    }

    /**
     * Fills the info that needs to iterate all activities of task, such as the number of
     * non-finishing activities and collecting launch cookies.
     */
    static class TaskInfoHelper implements Consumer<ActivityRecord> {
        private TaskInfo mInfo;
        private ActivityRecord mTopRunning;

        ActivityRecord fillAndReturnTop(Task task, TaskInfo info) {
            info.numActivities = 0;
            info.baseActivity = null;
            mInfo = info;
            task.forAllActivities(this);
            final ActivityRecord top = mTopRunning;
            mTopRunning = null;
            mInfo = null;
            return top;
        }

        @Override
        public void accept(ActivityRecord r) {
            if (r.mLaunchCookie != null) {
                mInfo.addLaunchCookie(r.mLaunchCookie);
            }
            if (r.finishing) {
                return;
            }
            mInfo.numActivities++;
            mInfo.baseActivity = r.mActivityComponent;
            if (mTopRunning == null) {
                mTopRunning = r;
            }
        }
    }

    /**
     * Internal container to store a match qualifier alongside a WaitResult.
     */
    private static class WaitInfo {
        final WaitResult mResult;
        final ComponentName mTargetComponent;
        /**
         * The target component may not be the final drawn activity. The launching state is managed
         * by {@link ActivityMetricsLogger} that can track consecutive launching sequence.
         */
        final LaunchingState mLaunchingState;

        WaitInfo(WaitResult result, ComponentName component, LaunchingState launchingState) {
            mResult = result;
            mTargetComponent = component;
            mLaunchingState = launchingState;
        }

        boolean matches(ActivityRecord r) {
            if (!mLaunchingState.hasActiveTransitionInfo()) {
                return mTargetComponent.equals(r.mActivityComponent);
            }
            return mLaunchingState.contains(r);
        }

        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "WaitInfo:");
            pw.println(prefix + "  mTargetComponent=" + mTargetComponent);
            pw.println(prefix + "  mResult=");
            mResult.dump(pw, prefix + "    ");
        }
    }
}
