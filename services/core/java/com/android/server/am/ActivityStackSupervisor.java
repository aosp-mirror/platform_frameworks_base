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

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.START_ANY_ACTIVITY;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
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
import static android.app.ActivityManager.StackId.RECENTS_STACK_ID;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY;
import static android.app.ITaskStackListener.FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.PowerManager.PARTIAL_WAKE_LOCK;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.Display.REMOVE_MODE_DESTROY_CONTENT;
import static android.view.Display.TYPE_VIRTUAL;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_PICTURE_IN_PICTURE_EXPANDED_TO_FULLSCREEN;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_IDLE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PAUSE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RELEASE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_IDLE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RELEASE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ANIMATE;
import static com.android.server.am.ActivityManagerService.FIRST_SUPERVISOR_STACK_MSG;
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
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
import static com.android.server.am.TaskRecord.REPARENT_KEEP_STACK_AT_FRONT;
import static com.android.server.am.TaskRecord.REPARENT_LEAVE_STACK_IN_PLACE;
import static com.android.server.am.TaskRecord.REPARENT_MOVE_STACK_TO_FRONT;
import static com.android.server.wm.AppTransition.TRANSIT_DOCK_TASK_FROM_RECENTS;
import static java.lang.Integer.MAX_VALUE;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackId;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerInternal.SleepToken;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.app.StatusBarManager;
import android.app.WaitResult;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
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
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManagerInternal;
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
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
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
import android.util.IntArray;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.TransferPipe;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.WindowManagerService;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class ActivityStackSupervisor extends ConfigurationContainer implements DisplayListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStackSupervisor" : TAG_AM;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_IDLE = TAG + POSTFIX_IDLE;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_RELEASE = TAG + POSTFIX_RELEASE;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_TASKS = TAG + POSTFIX_TASKS;

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
    static final int LOCK_TASK_START_MSG = FIRST_SUPERVISOR_STACK_MSG + 9;
    static final int LOCK_TASK_END_MSG = FIRST_SUPERVISOR_STACK_MSG + 10;
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

    // Don't execute any calls to resume.
    static final boolean DEFER_RESUME = true;

    // Used to indicate that a task is removed it should also be removed from recents.
    static final boolean REMOVE_FROM_RECENTS = true;

    // Used to indicate that pausing an activity should occur immediately without waiting for
    // the activity callback indicating that it has completed pausing
    static final boolean PAUSE_IMMEDIATELY = true;

    /**
     * The modes which affect which tasks are returned when calling
     * {@link ActivityStackSupervisor#anyTaskForIdLocked(int)}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MATCH_TASK_IN_STACKS_ONLY,
            MATCH_TASK_IN_STACKS_OR_RECENT_TASKS,
            MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE
    })
    public @interface AnyTaskForIdMatchTaskMode {}
    // Match only tasks in the current stacks
    static final int MATCH_TASK_IN_STACKS_ONLY = 0;
    // Match either tasks in the current stacks, or in the recent tasks if not found in the stacks
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS = 1;
    // Match either tasks in the current stacks, or in the recent tasks, restoring it to the
    // provided stack id
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE = 2;

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
    // TODO: Remove mActivitiesWaitingForVisibleActivity list and just remove activity from
    // mStoppingActivities when something else comes up.
    final ArrayList<ActivityRecord> mActivitiesWaitingForVisibleActivity = new ArrayList<>();

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
    PowerManager.WakeLock mLaunchingActivity;

    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    PowerManager.WakeLock mGoingToSleep;

    /**
     * A list of tokens that cause the top activity to be put to sleep.
     * They are used by components that may hide and block interaction with underlying
     * activities.
     */
    final ArrayList<SleepToken> mSleepTokens = new ArrayList<SleepToken>();

    /** Stack id of the front stack when user switched, indexed by userId. */
    SparseIntArray mUserStackInFront = new SparseIntArray(2);

    // TODO: Add listener for removal of references.
    /** Mapping from (ActivityStack/TaskStack).mStackId to their current state */
    SparseArray<ActivityStack> mStacks = new SparseArray<>();

    // TODO: There should be an ActivityDisplayController coordinating am/wm interaction.
    /** Mapping from displayId to display current state */
    private final SparseArray<ActivityDisplay> mActivityDisplays = new SparseArray<>();

    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();

    private DisplayManagerInternal mDisplayManagerInternal;
    private InputManagerInternal mInputManagerInternal;

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

    /**
     * Temporary rect used during docked stack resize calculation so we don't need to create a new
     * object each time.
     */
    private final Rect tempRect = new Rect();

    // The default minimal size that will be used if the activity doesn't specify its minimal size.
    // It will be calculated when the default display gets added.
    int mDefaultMinSizeOfResizeableTask = -1;

    // Whether tasks have moved and we need to rank the tasks before next OOM scoring
    private boolean mTaskLayersChanged = true;

    final ActivityMetricsLogger mActivityMetricsLogger;

    private final ArrayList<ActivityRecord> mTmpActivityList = new ArrayList<>();

    @Override
    protected int getChildCount() {
        return mActivityDisplays.size();
    }

    @Override
    protected ActivityDisplay getChildAt(int index) {
        return mActivityDisplays.valueAt(index);
    }

    @Override
    protected ConfigurationContainer getParent() {
        return null;
    }

    Configuration getDisplayOverrideConfiguration(int displayId) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }

        return activityDisplay.getOverrideConfiguration();
    }

    void setDisplayOverrideConfiguration(Configuration overrideConfiguration, int displayId) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }

        activityDisplay.onOverrideConfigurationChanged(overrideConfiguration);
    }

    /** Check if placing task or activity on specified display is allowed. */
    boolean canPlaceEntityOnDisplay(int displayId, boolean resizeable, int callingPid,
            int callingUid, ActivityInfo activityInfo) {
        if (displayId == DEFAULT_DISPLAY) {
            // No restrictions for the default display.
            return true;
        }
        if (!mService.mSupportsMultiDisplay) {
            // Can't launch on secondary displays if feature is not supported.
            return false;
        }
        if (!resizeable && !displayConfigMatchesGlobal(displayId)) {
            // Can't apply wrong configuration to non-resizeable activities.
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
     * Check if configuration of specified display matches current global config.
     * Used to check if we can put a non-resizeable activity on a secondary display and it will get
     * the same config as on the default display.
     * @param displayId Id of the display to check.
     * @return {@code true} if configuration matches.
     */
    private boolean displayConfigMatchesGlobal(int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            return true;
        }
        if (displayId == INVALID_DISPLAY) {
            return false;
        }
        final ActivityDisplay targetDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (targetDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }
        return getConfiguration().equals(targetDisplay.getConfiguration());
    }

    static class FindTaskResult {
        ActivityRecord r;
        boolean matchedByRootAffinity;
    }
    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();

    /**
     * Temp storage for display ids sorted in focus order.
     * Maps position to id. Using {@link SparseIntArray} instead of {@link ArrayList} because
     * it's more efficient, as the number of displays is usually small.
     */
    private SparseIntArray mTmpOrderedDisplayIds = new SparseIntArray();

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

    final KeyguardController mKeyguardController;

    private PowerManager mPowerManager;
    private int mDeferResumeCount;

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

    public ActivityStackSupervisor(ActivityManagerService service, Looper looper) {
        mService = service;
        mHandler = new ActivityStackSupervisorHandler(looper);
        mActivityMetricsLogger = new ActivityMetricsLogger(this, mService.mContext);
        mKeyguardController = new KeyguardController(service, this);
    }

    void setRecentTasks(RecentTasks recentTasks) {
        mRecentTasks = recentTasks;
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize our wakelocks afterwards.
     */
    void initPowerManagement() {
        mPowerManager = (PowerManager)mService.mContext.getSystemService(Context.POWER_SERVICE);
        mGoingToSleep = mPowerManager
                .newWakeLock(PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        mLaunchingActivity = mPowerManager.newWakeLock(PARTIAL_WAKE_LOCK, "*launch*");
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
            mKeyguardController.setWindowManager(wm);

            mDisplayManager =
                    (DisplayManager)mService.mContext.getSystemService(Context.DISPLAY_SERVICE);
            mDisplayManager.registerDisplayListener(this, null);
            mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);

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

    ActivityStack getFocusedStack() {
        return mFocusedStack;
    }

    ActivityStack getLastStack() {
        return mLastFocusedStack;
    }

    boolean isFocusedStack(ActivityStack stack) {
        return stack != null && stack == mFocusedStack;
    }

    /** The top most stack on its display. */
    boolean isFrontStackOnDisplay(ActivityStack stack) {
        return isFrontOfStackList(stack, stack.getDisplay().mStacks);
    }

    private boolean isFrontOfStackList(ActivityStack stack, List<ActivityStack> stackList) {
        return stack == stackList.get((stackList.size() - 1));
    }

    /** NOTE: Should only be called from {@link ActivityStack#moveToFront} */
    void setFocusStackUnchecked(String reason, ActivityStack focusCandidate) {
        if (!focusCandidate.isFocusable()) {
            // The focus candidate isn't focusable. Move focus to the top stack that is focusable.
            focusCandidate = getNextFocusableStackLocked(focusCandidate);
        }

        if (focusCandidate != mFocusedStack) {
            mLastFocusedStack = mFocusedStack;
            mFocusedStack = focusCandidate;

            EventLogTags.writeAmFocusedStack(
                    mCurrentUser, mFocusedStack == null ? -1 : mFocusedStack.getStackId(),
                    mLastFocusedStack == null ? -1 : mLastFocusedStack.getStackId(), reason);
        }

        final ActivityRecord r = topRunningActivityLocked();
        if (mService.mBooting || !mService.mBooted) {
            if (r != null && r.idle) {
                checkFinishBootingLocked();
            }
        }
    }

    void moveHomeStackToFront(String reason) {
        mHomeStack.moveToFront(reason);
    }

    void moveRecentsStackToFront(String reason) {
        final ActivityStack recentsStack = getStack(RECENTS_STACK_ID);
        if (recentsStack != null) {
            recentsStack.moveToFront(reason);
        }
    }

    /** Returns true if the focus activity was adjusted to the home stack top activity. */
    boolean moveHomeStackTaskToTop(String reason) {
        mHomeStack.moveHomeStackTaskToTop();

        final ActivityRecord top = getHomeActivity();
        if (top == null) {
            return false;
        }
        moveFocusableActivityStackToFrontLocked(top, reason);
        return true;
    }

    boolean resumeHomeStackTask(ActivityRecord prev, String reason) {
        if (!mService.mBooting && !mService.mBooted) {
            // Not ready yet!
            return false;
        }

        if (prev != null) {
            prev.getTask().setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
        }

        mHomeStack.moveHomeStackTaskToTop();
        ActivityRecord r = getHomeActivity();
        final String myReason = reason + " resumeHomeStackTask";

        // Only resume home activity if isn't finishing.
        if (r != null && !r.finishing) {
            moveFocusableActivityStackToFrontLocked(r, myReason);
            return resumeFocusedStackTopActivityLocked(mHomeStack, prev, null);
        }
        return mService.startHomeActivityLocked(mCurrentUser, myReason);
    }

    TaskRecord anyTaskForIdLocked(int id) {
        return anyTaskForIdLocked(id, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE,
                INVALID_STACK_ID);
    }

    /**
     * Returns a {@link TaskRecord} for the input id if available. {@code null} otherwise.
     * @param id Id of the task we would like returned.
     * @param matchMode The mode to match the given task id in.
     * @param stackId The stack to restore the task to (default launch stack will be used if
     *                stackId is {@link android.app.ActivityManager.StackId#INVALID_STACK_ID}). Only
     *                valid if the matchMode is
     *                {@link #MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE}.
     */
    TaskRecord anyTaskForIdLocked(int id, @AnyTaskForIdMatchTaskMode int matchMode, int stackId) {
        // If there is a stack id set, ensure that we are attempting to actually restore a task
        if (matchMode != MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE &&
                stackId != INVALID_STACK_ID) {
            throw new IllegalArgumentException("Should not specify stackId for non-restore lookup");
        }

        int numDisplays = mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                ActivityStack stack = stacks.get(stackNdx);
                final TaskRecord task = stack.taskForIdLocked(id);
                if (task != null) {
                    return task;
                }
            }
        }

        // If we are matching stack tasks only, return now
        if (matchMode == MATCH_TASK_IN_STACKS_ONLY) {
            return null;
        }

        // Otherwise, check the recent tasks and return if we find it there and we are not restoring
        // the task from recents
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS, "Looking for task id=" + id + " in recents");
        final TaskRecord task = mRecentTasks.taskForIdLocked(id);

        if (task == null) {
            if (DEBUG_RECENTS) {
                Slog.d(TAG_RECENTS, "\tDidn't find task id=" + id + " in recents");
            }

            return null;
        }

        if (matchMode == MATCH_TASK_IN_STACKS_OR_RECENT_TASKS) {
            return task;
        }

        // Implicitly, this case is MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE
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
     * Detects whether we should show a lock screen in front of this task for a locked user.
     * <p>
     * We'll do this if either of the following holds:
     * <ul>
     *   <li>The top activity explicitly belongs to {@param userId}.</li>
     *   <li>The top activity returns a result to an activity belonging to {@param userId}.</li>
     * </ul>
     *
     * @return {@code true} if the top activity looks like it belongs to {@param userId}.
     */
    private boolean taskTopActivityIsUser(TaskRecord task, @UserIdInt int userId) {
        // To handle the case that work app is in the task but just is not the top one.
        final ActivityRecord activityRecord = task.getTopActivity();
        final ActivityRecord resultTo = (activityRecord != null ? activityRecord.resultTo : null);

        return (activityRecord != null && activityRecord.userId == userId)
                || (resultTo != null && resultTo.userId == userId);
    }

    /**
     * Find all visible task stacks containing {@param userId} and intercept them with an activity
     * to block out the contents and possibly start a credential-confirming intent.
     *
     * @param userId user handle for the locked managed profile.
     */
    void lockAllProfileTasks(@UserIdInt int userId) {
        mWindowManager.deferSurfaceLayout();
        try {
            final List<ActivityStack> stacks = getStacks();
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; stackNdx--) {
                final List<TaskRecord> tasks = stacks.get(stackNdx).getAllTasks();
                for (int taskNdx = tasks.size() - 1; taskNdx >= 0; taskNdx--) {
                    final TaskRecord task = tasks.get(taskNdx);

                    // Check the task for a top activity belonging to userId, or returning a result
                    // to an activity belonging to userId. Example case: a document picker for
                    // personal files, opened by a work app, should still get locked.
                    if (taskTopActivityIsUser(task, userId)) {
                        mService.mTaskChangeNotificationController.notifyTaskProfileLocked(
                                task.taskId, userId);
                    }
                }
            }
        } finally {
            mWindowManager.continueSurfaceLayout();
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
        while (mRecentTasks.taskIdTakenForUserLocked(candidateTaskId, userId)
                || anyTaskForIdLocked(candidateTaskId, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS,
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

    ActivityRecord getResumedActivityLocked() {
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
                stack.getAllRunningVisibleActivitiesLocked(mTmpActivityList);
                final ActivityRecord top = stack.topRunningActivityLocked();
                final int size = mTmpActivityList.size();
                for (int i = 0; i < size; i++) {
                    final ActivityRecord activity = mTmpActivityList.get(i);
                    if (activity.app == null && app.uid == activity.info.applicationInfo.uid
                            && processName.equals(activity.processName)) {
                        try {
                            if (realStartActivityLocked(activity, app,
                                    top == activity /* andResume */, true /* checkConfig */)) {
                                didSomething = true;
                            }
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Exception in new application when starting activity "
                                    + top.intent.getComponent().flattenToShortString(), e);
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
                    if (!r.nowVisible || mActivitiesWaitingForVisibleActivity.contains(r)) {
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

    void cancelInitializingActivities() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stacks.get(stackNdx).cancelInitializingActivities();
            }
        }
    }

    void waitActivityVisible(ComponentName name, WaitResult result) {
        final WaitInfo waitInfo = new WaitInfo(name, result);
        mWaitingForActivityVisible.add(waitInfo);
    }

    void cleanupActivity(ActivityRecord r) {
        // Make sure this record is no longer in the pending finishes list.
        // This could happen, for example, if we are trimming activities
        // down to the max limit while they are still waiting to finish.
        mFinishingActivities.remove(r);
        mActivitiesWaitingForVisibleActivity.remove(r);

        for (int i = mWaitingForActivityVisible.size() - 1; i >= 0; --i) {
            if (mWaitingForActivityVisible.get(i).matches(r.realActivity)) {
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
            if (w.matches(r.realActivity)) {
                final WaitResult result = w.getResult();
                changed = true;
                result.timeout = false;
                result.who = w.getComponent();
                result.totalTime = SystemClock.uptimeMillis() - result.thisTime;
                result.thisTime = result.totalTime;
                mWaitingForActivityVisible.remove(w);
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
        mWindowManager.getDisplaysInFocusOrder(mTmpOrderedDisplayIds);

        for (int i = mTmpOrderedDisplayIds.size() - 1; i >= 0; --i) {
            final int displayId = mTmpOrderedDisplayIds.get(i);
            final List<ActivityStack> stacks = mActivityDisplays.get(displayId).mStacks;
            if (stacks == null) {
                continue;
            }
            for (int j = stacks.size() - 1; j >= 0; --j) {
                final ActivityStack stack = stacks.get(j);
                if (stack != focusedStack && isFrontStackOnDisplay(stack) && stack.isFocusable()) {
                    r = stack.topRunningActivityLocked();
                    if (r != null) {
                        return r;
                    }
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
            final String intentLaunchToken = intent.getLaunchToken();
            if (aInfo.launchToken == null && intentLaunchToken != null) {
                aInfo.launchToken = intentLaunchToken;
            }
        }
        return aInfo;
    }

    ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId) {
        return resolveIntent(intent, resolvedType, userId, 0);
    }

    ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId, int flags) {
        synchronized (mService) {
            return mService.getPackageManagerInternalLocked().resolveIntent(intent, resolvedType,
                    PackageManager.MATCH_INSTANT | PackageManager.MATCH_DEFAULT_ONLY | flags
                    | ActivityManagerService.STOCK_PM_FLAGS, userId);
        }
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

        final TaskRecord task = r.getTask();
        final ActivityStack stack = task.getStack();

        beginDeferResume();

        try {
            r.startFreezingScreenLocked(app, 0);

            // schedule launch ticks to collect information about slow apps.
            r.startLaunchTickingLocked();

            r.app = app;

            if (mKeyguardController.isKeyguardLocked()) {
                r.notifyUnknownVisibilityLaunched();
            }

            // Have the window manager re-evaluate the orientation of the screen based on the new
            // activity order.  Note that as a result of this, it can call back into the activity
            // manager with a new orientation.  We don't care about that, because the activity is
            // not currently running so we are just restarting it anyway.
            if (checkConfig) {
                final int displayId = r.getDisplayId();
                final Configuration config = mWindowManager.updateOrientationFromAppTokens(
                        getDisplayOverrideConfiguration(displayId),
                        r.mayFreezeScreenLocked(app) ? r.appToken : null, displayId);
                // Deferring resume here because we're going to launch new activity shortly.
                // We don't want to perform a redundant launch of the same record while ensuring
                // configurations and trying to resume top activity of focused stack.
                mService.updateDisplayOverrideConfigurationLocked(config, r, true /* deferResume */,
                        displayId);
            }

            if (r.getStack().checkKeyguardVisibility(r, true /* shouldBeVisible */,
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
            if ((r.userId != app.userId) || (r.appInfo.uid != applicationInfoUid)) {
                Slog.wtf(TAG,
                        "User ID for activity changing for " + r
                                + " appInfo.uid=" + r.appInfo.uid
                                + " info.ai.uid=" + applicationInfoUid
                                + " old=" + r.app + " new=" + app);
            }

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

            if (task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE ||
                    task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE_PRIV) {
                setLockTaskModeLocked(task, LOCK_TASK_MODE_LOCKED, "mLockTaskAuth==LAUNCHABLE",
                        false);
            }

            try {
                if (app.thread == null) {
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
                EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY, r.userId,
                        System.identityHashCode(r), task.taskId, r.shortComponentName);
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
                        ProfilerInfo profilerInfoSvc = mService.mProfilerInfo;
                        if (profilerInfoSvc != null && profilerInfoSvc.profileFile != null) {
                            if (profilerInfoSvc.profileFd != null) {
                                try {
                                    profilerInfoSvc.profileFd = profilerInfoSvc.profileFd.dup();
                                } catch (IOException e) {
                                    profilerInfoSvc.closeFd();
                                }
                            }

                            profilerInfo = new ProfilerInfo(profilerInfoSvc);
                        }
                    }
                }

                app.hasShownUi = true;
                app.pendingUiClean = true;
                app.forceProcessStateUpTo(mService.mTopProcessState);
                // Because we could be starting an Activity in the system process this may not go
                // across a Binder interface which would create a new Configuration. Consequently
                // we have to always create a new Configuration here.

                final MergedConfiguration mergedConfiguration = new MergedConfiguration(
                        mService.getGlobalConfiguration(), r.getMergedOverrideConfiguration());
                r.setLastReportedConfiguration(mergedConfiguration);

                logIfTransactionTooLarge(r.intent, r.icicle);
                app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                        System.identityHashCode(r), r.info,
                        // TODO: Have this take the merged configuration instead of separate global
                        // and override configs.
                        mergedConfiguration.getGlobalConfiguration(),
                        mergedConfiguration.getOverrideConfiguration(), r.compat,
                        r.launchedFromPackage, task.voiceInteractor, app.repProcState, r.icicle,
                        r.persistentState, results, newIntents, !andResume,
                        mService.isNextTransitionForward(), profilerInfo);

                if ((app.info.privateFlags & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
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
                r.launchFailed = true;
                app.activities.remove(r);
                throw e;
            }
        } finally {
            endDeferResume();
        }

        r.launchFailed = false;
        if (stack.updateLRUListLocked(r)) {
            Slog.w(TAG, "Activity " + r + " being launched, but already in LRU list");
        }

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

    void startSpecificActivityLocked(ActivityRecord r,
            boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        ProcessRecord app = mService.getProcessRecordLocked(r.processName,
                r.info.applicationInfo.uid, true);

        r.getStack().setLaunchTime(r);

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
        if (startAnyPerm == PERMISSION_GRANTED) {
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
        if (options != null) {
            if (options.getLaunchTaskId() != INVALID_STACK_ID) {
                final int startInTaskPerm = mService.checkPermission(START_TASKS_FROM_RECENTS,
                        callingPid, callingUid);
                if (startInTaskPerm == PERMISSION_DENIED) {
                    final String msg = "Permission Denial: starting " + intent.toString()
                            + " from " + callerApp + " (pid=" + callingPid
                            + ", uid=" + callingUid + ") with launchTaskId="
                            + options.getLaunchTaskId();
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
            // Check if someone tries to launch an activity on a private display with a different
            // owner.
            final int launchDisplayId = options.getLaunchDisplayId();
            if (launchDisplayId != INVALID_DISPLAY && !isCallerAllowedToLaunchOnDisplay(callingPid,
                    callingUid, launchDisplayId, aInfo)) {
                final String msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ") with launchDisplayId="
                        + launchDisplayId;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
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

        final ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(launchDisplayId);
        if (activityDisplay == null) {
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

    /** Update lists of UIDs that are present on displays and have access to them. */
    void updateUIDsPresentOnDisplay() {
        mDisplayAccessUIDs.clear();
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay activityDisplay = mActivityDisplays.valueAt(displayNdx);
            // Only bother calculating the whitelist for private displays
            if (activityDisplay.isPrivate()) {
                mDisplayAccessUIDs.append(
                        activityDisplay.mDisplayId, activityDisplay.getPresentUIDs());
            }
        }
        // Store updated lists in DisplayManager. Callers from outside of AM should get them there.
        mDisplayManagerInternal.setDisplayAccessUIDs(mDisplayAccessUIDs);
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

        if (mService.checkPermission(permission, callingPid, callingUid) == PERMISSION_DENIED) {
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
                reportActivityLaunchedLocked(fromTimeout, r, -1, -1);
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
            if (isFocusedStack(r.getStack()) || fromTimeout) {
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
            final ActivityStack stack = r.getStack();
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
            final ActivityStack stack = r.getStack();
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

        if (!readyToResume()) {
            return false;
        }

        if (targetStack != null && isFocusedStack(targetStack)) {
            return targetStack.resumeTopActivityUncheckedLocked(target, targetOptions);
        }

        final ActivityRecord r = mFocusedStack.topRunningActivityLocked();
        if (r == null || r.state != RESUMED) {
            mFocusedStack.resumeTopActivityUncheckedLocked(null, null);
        } else if (r.state == RESUMED) {
            // Kick off any lingering app transitions form the MoveTaskToFront operation.
            mFocusedStack.executeAppTransition(targetOptions);
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
        ActivityStack currentStack = task.getStack();
        if (currentStack == null) {
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
                if (stackId != currentStack.mStackId) {
                    task.reparent(stackId, ON_TOP, REPARENT_KEEP_STACK_AT_FRONT, !ANIMATE,
                            DEFER_RESUME, "findTaskToMoveToFrontLocked");
                    stackId = currentStack.mStackId;
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
                    task.resizeWindowContainer();
                }
            }
        }

        final ActivityRecord r = task.getTopActivity();
        currentStack.moveTaskToFrontLocked(task, false /* noAnimation */, options,
                r == null ? null : r.appTimeTracker, reason);

        if (DEBUG_STACK) Slog.d(TAG_STACK,
                "findTaskToMoveToFront: moved to front of stack=" + currentStack);

        handleNonResizableTaskIfNeeded(task, INVALID_STACK_ID, DEFAULT_DISPLAY,
                currentStack.mStackId, forceNonResizeable);
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

    protected <T extends ActivityStack> T getStack(int stackId) {
        return getStack(stackId, !CREATE_IF_NEEDED, !ON_TOP);
    }

    protected <T extends ActivityStack> T getStack(int stackId, boolean createStaticStackIfNeeded,
            boolean createOnTop) {
        final ActivityStack stack = mStacks.get(stackId);
        if (stack != null) {
            return (T) stack;
        }
        if (!createStaticStackIfNeeded || !StackId.isStaticStack(stackId)) {
            return null;
        }
        if (stackId == DOCKED_STACK_ID) {
            // Make sure recents stack exist when creating a dock stack as it normally need to be on
            // the other side of the docked stack and we make visibility decisions based on that.
            getStack(RECENTS_STACK_ID, CREATE_IF_NEEDED, createOnTop);
        }
        return (T) createStackOnDisplay(stackId, DEFAULT_DISPLAY, createOnTop);
    }

    /**
     * Get a topmost stack on the display, that is a valid launch stack for specified activity.
     * If there is no such stack, new dynamic stack can be created.
     * @param displayId Target display.
     * @param r Activity that should be launched there.
     * @return Existing stack if there is a valid one, new dynamic stack if it is valid or null.
     */
    ActivityStack getValidLaunchStackOnDisplay(int displayId, @NonNull ActivityRecord r) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException(
                    "Display with displayId=" + displayId + " not found.");
        }

        // Return the topmost valid stack on the display.
        for (int i = activityDisplay.mStacks.size() - 1; i >= 0; --i) {
            final ActivityStack stack = activityDisplay.mStacks.get(i);
            if (mService.mActivityStarter.isValidLaunchStackId(stack.mStackId, displayId, r)) {
                return stack;
            }
        }

        // If there is no valid stack on the external display - check if new dynamic stack will do.
        if (displayId != Display.DEFAULT_DISPLAY) {
            final int newDynamicStackId = getNextStackId();
            if (mService.mActivityStarter.isValidLaunchStackId(newDynamicStackId, displayId, r)) {
                return createStackOnDisplay(newDynamicStackId, displayId, true /*onTop*/);
            }
        }

        Slog.w(TAG, "getValidLaunchStackOnDisplay: can't launch on displayId " + displayId);
        return null;
    }

    ArrayList<ActivityStack> getStacks() {
        ArrayList<ActivityStack> allStacks = new ArrayList<>();
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            allStacks.addAll(mActivityDisplays.valueAt(displayNdx).mStacks);
        }
        return allStacks;
    }

    ArrayList<ActivityStack> getStacksOnDefaultDisplay() {
        return mActivityDisplays.valueAt(DEFAULT_DISPLAY).mStacks;
    }

    /**
     * Get next focusable stack in the system. This will search across displays and stacks
     * in last-focused order for a focusable and visible stack, different from the target stack.
     *
     * @param currentFocus The stack that previously had focus and thus needs to be ignored when
     *                     searching for next candidate.
     * @return Next focusable {@link ActivityStack}, null if not found.
     */
    ActivityStack getNextFocusableStackLocked(ActivityStack currentFocus) {
        mWindowManager.getDisplaysInFocusOrder(mTmpOrderedDisplayIds);

        for (int i = mTmpOrderedDisplayIds.size() - 1; i >= 0; --i) {
            final int displayId = mTmpOrderedDisplayIds.get(i);
            // If a display is registered in WM, it must also be available in AM.
            @SuppressWarnings("ConstantConditions")
            final List<ActivityStack> stacks = getActivityDisplayOrCreateLocked(displayId).mStacks;
            for (int j = stacks.size() - 1; j >= 0; --j) {
                final ActivityStack stack = stacks.get(j);
                if (stack != currentFocus && stack.isFocusable()
                        && stack.shouldBeVisible(null) != STACK_INVISIBLE) {
                    return stack;
                }
            }
        }

        return null;
    }

    /**
     * Get next valid stack for launching provided activity in the system. This will search across
     * displays and stacks in last-focused order for a focusable and visible stack, except those
     * that are on a currently focused display.
     *
     * @param r The activity that is being launched.
     * @param currentFocus The display that previously had focus and thus needs to be ignored when
     *                     searching for the next candidate.
     * @return Next valid {@link ActivityStack}, null if not found.
     */
    ActivityStack getNextValidLaunchStackLocked(@NonNull ActivityRecord r, int currentFocus) {
        mWindowManager.getDisplaysInFocusOrder(mTmpOrderedDisplayIds);
        for (int i = mTmpOrderedDisplayIds.size() - 1; i >= 0; --i) {
            final int displayId = mTmpOrderedDisplayIds.get(i);
            if (displayId == currentFocus) {
                continue;
            }
            final ActivityStack stack = getValidLaunchStackOnDisplay(displayId, r);
            if (stack != null) {
                return stack;
            }
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

        if (!allowResizeInDockedMode && !StackId.tasksAreFloating(stackId) &&
                getStack(DOCKED_STACK_ID) != null) {
            // If the docked stack exists, don't resize non-floating stacks independently of the
            // size computed from the docked stack size (otherwise they will be out of sync)
            return;
        }

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizeStack_" + stackId);
        mWindowManager.deferSurfaceLayout();
        try {
            stack.resize(bounds, tempTaskBounds, tempTaskInsetBounds);
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
        continueUpdateBounds(RECENTS_STACK_ID);
        for (int i = mResizingTasksDuringAnimation.size() - 1; i >= 0; i--) {
            final int taskId = mResizingTasksDuringAnimation.valueAt(i);
            final TaskRecord task =
                    anyTaskForIdLocked(taskId, MATCH_TASK_IN_STACKS_ONLY, INVALID_STACK_ID);
            if (task != null) {
                task.setTaskDockedResizing(false);
            }
        }
        mResizingTasksDuringAnimation.clear();
    }

    private void moveTasksToFullscreenStackInSurfaceTransaction(int fromStackId,
            boolean onTop) {

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
            } else if (fromStackId == PINNED_STACK_ID) {
                if (onTop) {
                    // Log if we are expanding the PiP to fullscreen
                    MetricsLogger.action(mService.mContext,
                            ACTION_PICTURE_IN_PICTURE_EXPANDED_TO_FULLSCREEN);
                }
            }
            ActivityStack fullscreenStack = getStack(FULLSCREEN_WORKSPACE_STACK_ID);
            final boolean isFullscreenStackVisible = fullscreenStack != null &&
                    fullscreenStack.shouldBeVisible(null) == STACK_VISIBLE;
            // If we are moving from the pinned stack, then the animation takes care of updating
            // the picture-in-picture mode.
            final boolean schedulePictureInPictureModeChange = (fromStackId == PINNED_STACK_ID);
            final ArrayList<TaskRecord> tasks = stack.getAllTasks();
            final int size = tasks.size();
            if (onTop) {
                for (int i = 0; i < size; i++) {
                    final TaskRecord task = tasks.get(i);
                    final boolean isTopTask = i == (size - 1);
                    if (fromStackId == PINNED_STACK_ID) {
                        // Update the return-to to reflect where the pinned stack task was moved
                        // from so that we retain the stack that was previously visible if the
                        // pinned stack is recreated. See moveActivityToPinnedStackLocked().
                        task.setTaskToReturnTo(isFullscreenStackVisible && onTop ?
                                APPLICATION_ACTIVITY_TYPE : HOME_ACTIVITY_TYPE);
                    }
                    // Defer resume until all the tasks have been moved to the fullscreen stack
                    task.reparent(FULLSCREEN_WORKSPACE_STACK_ID, ON_TOP,
                            REPARENT_MOVE_STACK_TO_FRONT, isTopTask /* animate */, DEFER_RESUME,
                            schedulePictureInPictureModeChange,
                            "moveTasksToFullscreenStack - onTop");
                }
            } else {
                for (int i = 0; i < size; i++) {
                    final TaskRecord task = tasks.get(i);
                    // Position the tasks in the fullscreen stack in order at the bottom of the
                    // stack. Also defer resume until all the tasks have been moved to the
                    // fullscreen stack.
                    task.reparent(FULLSCREEN_WORKSPACE_STACK_ID, i /* position */,
                            REPARENT_LEAVE_STACK_IN_PLACE, !ANIMATE, DEFER_RESUME,
                            schedulePictureInPictureModeChange,
                            "moveTasksToFullscreenStack - NOT_onTop");
                }
            }

            ensureActivitiesVisibleLocked(null, 0, PRESERVE_WINDOWS);
            resumeFocusedStackTopActivityLocked();
        } finally {
            mAllowDockedStackResize = true;
            mWindowManager.continueSurfaceLayout();
        }
    }

    void moveTasksToFullscreenStackLocked(int fromStackId, boolean onTop) {
        mWindowManager.inSurfaceTransaction(
                () -> moveTasksToFullscreenStackInSurfaceTransaction(fromStackId, onTop));
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
            stack.resize(dockedBounds, tempDockedTaskBounds, tempDockedTaskInsetBounds);

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
                final Rect otherTaskRect = new Rect();
                for (int i = FIRST_STATIC_STACK_ID; i <= LAST_STATIC_STACK_ID; i++) {
                    final ActivityStack current = getStack(i);
                    if (current != null && StackId.isResizeableByDockedStack(i)) {
                        current.getStackDockedModeBounds(
                                tempOtherTaskBounds /* currentTempTaskBounds */,
                                tempRect /* outStackBounds */,
                                otherTaskRect /* outTempTaskBounds */, true /* ignoreVisibility */);

                        resizeStackLocked(i, !tempRect.isEmpty() ? tempRect : null,
                                !otherTaskRect.isEmpty() ? otherTaskRect : tempOtherTaskBounds,
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
    }

    void resizePinnedStackLocked(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        final PinnedActivityStack stack = getStack(PINNED_STACK_ID);
        if (stack == null) {
            Slog.w(TAG, "resizePinnedStackLocked: pinned stack not found");
            return;
        }

        // It is possible for the bounds animation from the WM to call this but be delayed by
        // another AM call that is holding the AMS lock. In such a case, the pinnedBounds may be
        // incorrect if AMS.resizeStackWithBoundsFromWindowManager() is already called while waiting
        // for the AMS lock to be freed. So check and make sure these bounds are still good.
        final PinnedStackWindowController stackController = stack.getWindowContainerController();
        if (stackController.pinnedStackResizeDisallowed()) {
            return;
        }

        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "am.resizePinnedStack");
        mWindowManager.deferSurfaceLayout();
        try {
            ActivityRecord r = stack.topRunningActivityLocked();
            Rect insetBounds = null;
            if (tempPinnedTaskBounds != null) {
                // We always use 0,0 as the position for the inset rect because
                // if we are getting insets at all in the pinned stack it must mean
                // we are headed for fullscreen.
                insetBounds = tempRect;
                insetBounds.top = 0;
                insetBounds.left = 0;
                insetBounds.right = tempPinnedTaskBounds.width();
                insetBounds.bottom = tempPinnedTaskBounds.height();
            }
            stack.resize(pinnedBounds, tempPinnedTaskBounds, insetBounds);
            stack.ensureVisibleActivitiesConfigurationLocked(r, false);
        } finally {
            mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    ActivityStack createStackOnDisplay(int stackId, int displayId, boolean onTop) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            return null;
        }
        return createStack(stackId, activityDisplay, onTop);

    }

    ActivityStack createStack(int stackId, ActivityDisplay display, boolean onTop) {
        switch (stackId) {
            case PINNED_STACK_ID:
                return new PinnedActivityStack(display, stackId, this, mRecentTasks, onTop);
            default:
                return new ActivityStack(display, stackId, this, mRecentTasks, onTop);
        }
    }

    void removeStackInSurfaceTransaction(int stackId) {
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            return;
        }

        final ArrayList<TaskRecord> tasks = stack.getAllTasks();
        if (stack.getStackId() == PINNED_STACK_ID) {
            /**
             * Workaround: Force-stop all the activities in the pinned stack before we reparent them
             * to the fullscreen stack.  This is to guarantee that when we are removing a stack,
             * that the client receives onStop() before it is reparented.  We do this by detaching
             * the stack from the display so that it will be considered invisible when
             * ensureActivitiesVisibleLocked() is called, and all of its activitys will be marked
             * invisible as well and added to the stopping list.  After which we process the
             * stopping list by handling the idle.
             */
            final PinnedActivityStack pinnedStack = (PinnedActivityStack) stack;
            pinnedStack.mForceHidden = true;
            pinnedStack.ensureActivitiesVisibleLocked(null, 0, PRESERVE_WINDOWS);
            pinnedStack.mForceHidden = false;
            activityIdleInternalLocked(null, false /* fromTimeout */,
                    true /* processPausingActivites */, null /* configuration */);

            // Move all the tasks to the bottom of the fullscreen stack
            moveTasksToFullscreenStackLocked(PINNED_STACK_ID, !ON_TOP);
        } else {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                removeTaskByIdLocked(tasks.get(i).taskId, true /* killProcess */,
                        REMOVE_FROM_RECENTS);
            }
        }
    }

    /**
     * Removes the stack associated with the given {@param stackId}.  If the {@param stackId} is the
     * pinned stack, then its tasks are not explicitly removed when the stack is destroyed, but
     * instead moved back onto the fullscreen stack.
     */
    void removeStackLocked(int stackId) {
        mWindowManager.inSurfaceTransaction(
                () -> removeStackInSurfaceTransaction(stackId));
    }

    /**
     * See {@link #removeTaskByIdLocked(int, boolean, boolean, boolean)}
     */
    boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents) {
        return removeTaskByIdLocked(taskId, killProcess, removeFromRecents, !PAUSE_IMMEDIATELY);
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
            boolean pauseImmediately) {
        final TaskRecord tr = anyTaskForIdLocked(taskId, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS,
                INVALID_STACK_ID);
        if (tr != null) {
            tr.removeTaskActivitiesLocked(pauseImmediately);
            cleanUpRemovedTaskLocked(tr, killProcess, removeFromRecents);
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
            tr.removedFromRecents();
        }
        ComponentName component = tr.getBaseIntent().getComponent();
        if (component == null) {
            Slog.w(TAG, "No component for base intent of task: " + tr);
            return;
        }

        // Find any running services associated with this app and stop if needed.
        mService.mServices.cleanUpRemovedTaskLocked(tr, component, new Intent(tr.getBaseIntent()));

        if (!killProcess) {
            return;
        }

        // Determine if the process(es) for this task should be killed.
        final String pkg = component.getPackageName();
        ArrayList<ProcessRecord> procsToKill = new ArrayList<>();
        ArrayMap<String, SparseArray<ProcessRecord>> pmap = mService.mProcessNames.getMap();
        for (int i = 0; i < pmap.size(); i++) {

            SparseArray<ProcessRecord> uids = pmap.valueAt(i);
            for (int j = 0; j < uids.size(); j++) {
                ProcessRecord proc = uids.valueAt(j);
                if (proc.userId != tr.userId) {
                    // Don't kill process for a different user.
                    continue;
                }
                if (proc == mService.mHomeProcess) {
                    // Don't kill the home process along with tasks from the same package.
                    continue;
                }
                if (!proc.pkgList.containsKey(pkg)) {
                    // Don't kill process that is not associated with this task.
                    continue;
                }

                for (int k = 0; k < proc.activities.size(); k++) {
                    TaskRecord otherTask = proc.activities.get(k).getTask();
                    if (tr.taskId != otherTask.taskId && otherTask.inRecents) {
                        // Don't kill process(es) that has an activity in a different task that is
                        // also in recents.
                        return;
                    }
                }

                if (proc.foregroundServices) {
                    // Don't kill process(es) with foreground service.
                    return;
                }

                // Add process to kill list.
                procsToKill.add(proc);
            }
        }

        // Kill the running processes.
        for (int i = 0; i < procsToKill.size(); i++) {
            ProcessRecord pr = procsToKill.get(i);
            if (pr.setSchedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                    && pr.curReceivers.isEmpty()) {
                pr.kill("remove task", true);
            } else {
                // We delay killing processes that are not in the background or running a receiver.
                pr.waitingToKill = "remove task";
            }
        }
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
     *                if stackId is {@link android.app.ActivityManager.StackId#INVALID_STACK_ID}
     *                or is not a static stack).
     * @return true if the task has been restored successfully.
     */
    boolean restoreRecentTaskLocked(TaskRecord task, int stackId) {
        if (!StackId.isStaticStack(stackId)) {
            // If stack is not static (or stack id is invalid) - use the default one.
            // This means that tasks that were on external displays will be restored on the
            // primary display.
            stackId = task.getLaunchStackId();
        } else if (stackId == DOCKED_STACK_ID && !task.supportsSplitScreen()) {
            // Preferred stack is the docked stack, but the task can't go in the docked stack.
            // Put it in the fullscreen stack.
            stackId = FULLSCREEN_WORKSPACE_STACK_ID;
        }

        final ActivityStack currentStack = task.getStack();
        if (currentStack != null) {
            // Task has already been restored once. See if we need to do anything more
            if (currentStack.mStackId == stackId) {
                // Nothing else to do since it is already restored in the right stack.
                return true;
            }
            // Remove current stack association, so we can re-associate the task with the
            // right stack below.
            currentStack.removeTask(task, "restoreRecentTaskLocked", REMOVE_TASK_MODE_MOVING);
        }

        final ActivityStack stack =
                getStack(stackId, CREATE_IF_NEEDED, !ON_TOP);

        if (stack == null) {
            // What does this mean??? Not sure how we would get here...
            if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                    "Unable to find/create stack to restore recent task=" + task);
            return false;
        }

        stack.addTask(task, false /* toTop */, "restoreRecentTask");
        // TODO: move call for creation here and other place into Stack.addTask()
        task.createWindowContainer(false /* toTop */, true /* showForAllUsers */);
        if (DEBUG_RECENTS) Slog.v(TAG_RECENTS,
                "Added restored task=" + task + " to stack=" + stack);
        final ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
            activities.get(activityNdx).createWindowContainer();
        }
        return true;
    }

    /**
     * Move stack with all its existing content to specified display.
     * @param stackId Id of stack to move.
     * @param displayId Id of display to move stack to.
     * @param onTop Indicates whether container should be place on top or on bottom.
     */
    void moveStackToDisplayLocked(int stackId, int displayId, boolean onTop) {
        final ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("moveStackToDisplayLocked: Unknown displayId="
                    + displayId);
        }
        final ActivityStack stack = mStacks.get(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveStackToDisplayLocked: Unknown stackId="
                    + stackId);
        }

        final ActivityDisplay currentDisplay = stack.getDisplay();
        if (currentDisplay == null) {
            throw new IllegalStateException("moveStackToDisplayLocked: Stack with stack=" + stack
                    + " is not attached to any display.");
        }

        if (currentDisplay.mDisplayId == displayId) {
            throw new IllegalArgumentException("Trying to move stack=" + stack
                    + " to its current displayId=" + displayId);
        }

        stack.reparent(activityDisplay, onTop);
        // TODO(multi-display): resize stacks properly if moved from split-screen.
    }

    /**
     * Returns the reparent target stack, creating the stack if necessary.  This call also enforces
     * the various checks on tasks that are going to be reparented from one stack to another.
     */
    ActivityStack getReparentTargetStack(TaskRecord task, int stackId, boolean toTop) {
        final ActivityStack prevStack = task.getStack();

        // Check that we aren't reparenting to the same stack that the task is already in
        if (prevStack != null && prevStack.mStackId == stackId) {
            Slog.w(TAG, "Can not reparent to same stack, task=" + task
                    + " already in stackId=" + stackId);
            return prevStack;
        }

        // Ensure that we aren't trying to move into a multi-window stack without multi-window
        // support
        if (StackId.isMultiWindowStack(stackId) && !mService.mSupportsMultiWindow) {
            throw new IllegalArgumentException("Device doesn't support multi-window, can not"
                    + " reparent task=" + task + " to stackId=" + stackId);
        }

        // Ensure that we're not moving a task to a dynamic stack if device doesn't support
        // multi-display.
        // TODO(multi-display): Support non-dynamic stacks on secondary displays.
        if (StackId.isDynamicStack(stackId) && !mService.mSupportsMultiDisplay) {
            throw new IllegalArgumentException("Device doesn't support multi-display, can not"
                    + " reparent task=" + task + " to stackId=" + stackId);
        }

        // Ensure that we aren't trying to move into a freeform stack without freeform
        // support
        if (stackId == FREEFORM_WORKSPACE_STACK_ID && !mService.mSupportsFreeformWindowManagement) {
            throw new IllegalArgumentException("Device doesn't support freeform, can not reparent"
                    + " task=" + task);
        }

        // We don't allow moving a unresizeable task to the docked stack since the docked stack is
        // used for split-screen mode and will cause things like the docked divider to show up. We
        // instead leave the task in its current stack or move it to the fullscreen stack if it
        // isn't currently in a stack.
        if (stackId == DOCKED_STACK_ID && !task.isResizeable()) {
            stackId = (prevStack != null) ? prevStack.mStackId : FULLSCREEN_WORKSPACE_STACK_ID;
            Slog.w(TAG, "Can not move unresizeable task=" + task + " to docked stack."
                    + " Moving to stackId=" + stackId + " instead.");
        }

        // Temporarily disable resizeablility of the task as we don't want it to be resized if, for
        // example, a docked stack is created which will lead to the stack we are moving from being
        // resized and and its resizeable tasks being resized.
        try {
            task.mTemporarilyUnresizable = true;
            return getStack(stackId, CREATE_IF_NEEDED, toTop);
        } finally {
            task.mTemporarilyUnresizable = false;
        }
    }

    boolean moveTopStackActivityToPinnedStackLocked(int stackId, Rect destBounds) {
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

        moveActivityToPinnedStackLocked(r, null /* sourceBounds */, 0f /* aspectRatio */,
                true /* moveHomeStackToFront */, "moveTopActivityToPinnedStack");
        return true;
    }

    void moveActivityToPinnedStackLocked(ActivityRecord r, Rect sourceHintBounds, float aspectRatio,
            boolean moveHomeStackToFront, String reason) {

        mWindowManager.deferSurfaceLayout();

        // This will clear the pinned stack by moving an existing task to the full screen stack,
        // ensuring only one task is present.
        moveTasksToFullscreenStackLocked(PINNED_STACK_ID, !ON_TOP);

        // Need to make sure the pinned stack exist so we can resize it below...
        final PinnedActivityStack stack = getStack(PINNED_STACK_ID, CREATE_IF_NEEDED, ON_TOP);

        try {
            final TaskRecord task = r.getTask();
            // Resize the pinned stack to match the current size of the task the activity we are
            // going to be moving is currently contained in. We do this to have the right starting
            // animation bounds for the pinned stack to the desired bounds the caller wants.
            resizeStackLocked(PINNED_STACK_ID, task.mBounds, null /* tempTaskBounds */,
                    null /* tempTaskInsetBounds */, !PRESERVE_WINDOWS,
                    true /* allowResizeInDockedMode */, !DEFER_RESUME);

            if (task.mActivities.size() == 1) {
                // There is only one activity in the task. So, we can just move the task over to
                // the stack without re-parenting the activity in a different task.  We don't
                // move the home stack forward if we are currently entering picture-in-picture
                // while pausing because that changes the focused stack and may prevent the new
                // starting activity from resuming.
                if (moveHomeStackToFront && task.getTaskToReturnTo() == HOME_ACTIVITY_TYPE
                        && (r.state == RESUMED || !r.supportsEnterPipOnTaskSwitch)) {
                    // Move the home stack forward if the task we just moved to the pinned stack
                    // was launched from home so home should be visible behind it.
                    moveHomeStackToFront(reason);
                }
                // Defer resume until below, and do not schedule PiP changes until we animate below
                task.reparent(PINNED_STACK_ID, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, !ANIMATE,
                        DEFER_RESUME, false /* schedulePictureInPictureModeChange */, reason);
            } else {
                // There are multiple activities in the task and moving the top activity should
                // reveal/leave the other activities in their original task.

                // Currently, we don't support reparenting activities across tasks in two different
                // stacks, so instead, just create a new task in the same stack, reparent the
                // activity into that task, and then reparent the whole task to the new stack. This
                // ensures that all the necessary work to migrate states in the old and new stacks
                // is also done.
                final TaskRecord newTask = task.getStack().createTaskRecord(
                        getNextTaskIdForUserLocked(r.userId), r.info, r.intent, null, null, true,
                        r.mActivityType);
                r.reparent(newTask, MAX_VALUE, "moveActivityToStack");

                // Defer resume until below, and do not schedule PiP changes until we animate below
                newTask.reparent(PINNED_STACK_ID, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, !ANIMATE,
                        DEFER_RESUME, false /* schedulePictureInPictureModeChange */, reason);
            }

            // Reset the state that indicates it can enter PiP while pausing after we've moved it
            // to the pinned stack
            r.supportsEnterPipOnTaskSwitch = false;
        } finally {
            mWindowManager.continueSurfaceLayout();
        }

        // Calculate the default bounds (don't use existing stack bounds as we may have just created
        // the stack, and schedule the start of the animation into PiP (the bounds animator that
        // is triggered by this is posted on another thread)
        final Rect destBounds = stack.getDefaultPictureInPictureBounds(aspectRatio);

        stack.animateResizePinnedStack(sourceHintBounds, destBounds, -1 /* animationDuration */,
                true /* fromFullscreen */);

        // Update the visibility of all activities after the they have been reparented to the new
        // stack.  This MUST run after the animation above is scheduled to ensure that the windows
        // drawn signal is scheduled after the bounds animation start call on the bounds animator
        // thread.
        ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
        resumeFocusedStackTopActivityLocked();

        mService.mTaskChangeNotificationController.notifyActivityPinned(r.packageName, r.userId,
                r.getTask().taskId);
    }

    /** Move activity with its stack to front and make the stack focused. */
    boolean moveFocusableActivityStackToFrontLocked(ActivityRecord r, String reason) {
        if (r == null || !r.isFocusable()) {
            if (DEBUG_FOCUS) Slog.d(TAG_FOCUS,
                    "moveActivityStackToFront: unfocusable r=" + r);
            return false;
        }

        final TaskRecord task = r.getTask();
        final ActivityStack stack = r.getStack();
        if (stack == null) {
            Slog.w(TAG, "moveActivityStackToFront: invalid task or stack: r="
                    + r + " task=" + task);
            return false;
        }

        if (stack == mFocusedStack && stack.topRunningActivityLocked() == r) {
            if (DEBUG_FOCUS) Slog.d(TAG_FOCUS,
                    "moveActivityStackToFront: already on top, r=" + r);
            return false;
        }

        if (DEBUG_FOCUS) Slog.d(TAG_FOCUS,
                "moveActivityStackToFront: r=" + r);

        stack.moveToFront(reason, task);
        return true;
    }

    ActivityRecord findTaskLocked(ActivityRecord r, int displayId) {
        mTmpFindTaskResult.r = null;
        mTmpFindTaskResult.matchedByRootAffinity = false;
        ActivityRecord affinityMatch = null;
        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Looking for task of " + r);
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (!checkActivityBelongsInStack(r, stack)) {
                    if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Skipping stack: (mismatch activity/stack) "
                            + stack);
                    continue;
                }
                stack.findTaskLocked(r, mTmpFindTaskResult);
                // It is possible to have tasks in multiple stacks with the same root affinity, so
                // we should keep looking after finding an affinity match to see if there is a
                // better match in another stack. Also, task affinity isn't a good enough reason
                // to target a display which isn't the source of the intent, so skip any affinity
                // matches not on the specified display.
                if (mTmpFindTaskResult.r != null) {
                    if (!mTmpFindTaskResult.matchedByRootAffinity) {
                        return mTmpFindTaskResult.r;
                    } else if (mTmpFindTaskResult.r.getDisplayId() == displayId) {
                        // Note: since the traversing through the stacks is top down, the floating
                        // tasks should always have lower priority than any affinity-matching tasks
                        // in the fullscreen stacks
                        affinityMatch = mTmpFindTaskResult.r;
                    }
                }
            }
        }

        if (DEBUG_TASKS && affinityMatch == null) Slog.d(TAG_TASKS, "No task found");
        return affinityMatch;
    }

    /**
     * Checks that for the given activity {@param r}, its activity type matches the {@param stack}
     * type.
     */
    private boolean checkActivityBelongsInStack(ActivityRecord r, ActivityStack stack) {
        if (r.isHomeActivity()) {
            return stack.isHomeStack();
        } else if (r.isRecentsActivity()) {
            return stack.isRecentsStack();
        } else if (r.isAssistantActivity()) {
            return stack.isAssistantStack();
        }
        return true;
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

    boolean hasAwakeDisplay() {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ActivityDisplay display = mActivityDisplays.valueAt(displayNdx);
            if (!display.shouldSleep()) {
                return true;
            }
        }
        return false;
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

        applySleepTokensLocked(false /* applyToStacks */);

        checkReadyForSleepLocked(true /* allowDelay */);
    }

    void prepareForShutdownLocked() {
        for (int i = 0; i < mActivityDisplays.size(); i++) {
            createSleepTokenLocked("shutdown", mActivityDisplays.keyAt(i));
        }
    }

    boolean shutdownLocked(int timeout) {
        goingToSleepLocked();

        boolean timedout = false;
        final long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            if (!putStacksToSleepLocked(true /* allowDelay */, true /* shuttingDown */)) {
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
        checkReadyForSleepLocked(false /* allowDelay */);

        return timedout;
    }

    void comeOutOfSleepIfNeededLocked() {
        removeSleepTimeouts();
        if (mGoingToSleep.isHeld()) {
            mGoingToSleep.release();
        }
    }

    void applySleepTokensLocked(boolean applyToStacks) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            // Set the sleeping state of the display.
            final ActivityDisplay display = mActivityDisplays.valueAt(displayNdx);
            final boolean displayShouldSleep = display.shouldSleep();
            if (displayShouldSleep == display.isSleeping()) {
                continue;
            }
            display.setIsSleeping(displayShouldSleep);

            if (!applyToStacks) {
                continue;
            }

            // Set the sleeping state of the stacks on the display.
            final ArrayList<ActivityStack> stacks = display.mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (displayShouldSleep) {
                    stack.goToSleepIfPossible(false /* shuttingDown */);
                } else {
                    stack.awakeFromSleepingLocked();
                    if (isFocusedStack(stack)) {
                        resumeFocusedStackTopActivityLocked();
                    }
                }
            }

            if (displayShouldSleep || mGoingToSleepActivities.isEmpty()) {
                continue;
            }
            // The display is awake now, so clean up the going to sleep list.
            for (Iterator<ActivityRecord> it = mGoingToSleepActivities.iterator(); it.hasNext(); ) {
                final ActivityRecord r = it.next();
                if (r.getDisplayId() == display.mDisplayId) {
                    it.remove();
                }
            }
        }
    }

    void activitySleptLocked(ActivityRecord r) {
        mGoingToSleepActivities.remove(r);
        final ActivityStack s = r.getStack();
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

        if (!putStacksToSleepLocked(allowDelay, false /* shuttingDown */)) {
            return;
        }

        // Send launch end powerhint before going sleep
        mService.mActivityStarter.sendPowerHintForLaunchEndIfNeeded();

        removeSleepTimeouts();

        if (mGoingToSleep.isHeld()) {
            mGoingToSleep.release();
        }
        if (mService.mShuttingDown) {
            mService.notifyAll();
        }
    }

    // Tries to put all activity stacks to sleep. Returns true if all stacks were
    // successfully put to sleep.
    private boolean putStacksToSleepLocked(boolean allowDelay, boolean shuttingDown) {
        boolean allSleep = true;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                if (allowDelay) {
                    allSleep &= stacks.get(stackNdx).goToSleepIfPossible(shuttingDown);
                } else {
                    stacks.get(stackNdx).goToSleep();
                }
            }
        }
        return allSleep;
    }

    boolean reportResumedActivityLocked(ActivityRecord r) {
        // A resumed activity cannot be stopping. remove from list
        mStoppingActivities.remove(r);

        final ActivityStack stack = r.getStack();
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

    // Called when WindowManager has finished animating the launchingBehind activity to the back.
    private void handleLaunchTaskBehindCompleteLocked(ActivityRecord r) {
        final TaskRecord task = r.getTask();
        final ActivityStack stack = task.getStack();

        r.mLaunchTaskBehind = false;
        task.setLastThumbnailLocked(r.screenshotActivityLocked());
        mRecentTasks.addLocked(task);
        mService.mTaskChangeNotificationController.notifyTaskStackChanged();
        r.setVisibility(false);

        // When launching tasks behind, update the last active time of the top task after the new
        // task has been shown briefly
        final ActivityRecord top = stack.topActivity();
        if (top != null) {
            top.getTask().touchActiveTime();
        }
    }

    void scheduleLaunchTaskBehindComplete(IBinder token) {
        mHandler.obtainMessage(LAUNCH_TASK_BEHIND_COMPLETE, token).sendToTarget();
    }

    void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges,
            boolean preserveWindows) {
        mKeyguardController.beginActivityVisibilityUpdate();
        try {
            // First the front stacks. In case any are not fullscreen and are in front of home.
            for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
                final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
                final int topStackNdx = stacks.size() - 1;
                for (int stackNdx = topStackNdx; stackNdx >= 0; --stackNdx) {
                    final ActivityStack stack = stacks.get(stackNdx);
                    stack.ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows);
                }
            }
        } finally {
            mKeyguardController.endActivityVisibilityUpdate();
        }
    }

    void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            final int topStackNdx = stacks.size() - 1;
            for (int stackNdx = topStackNdx; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.addStartingWindowsForVisibleActivities(taskSwitch);
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

            final TaskRecord task = r.getTask();
            if (task != null) {
                if (DEBUG_RELEASE) Slog.d(TAG_RELEASE, "Collecting release task " + task
                        + " from " + r);
                if (firstTask == null) {
                    firstTask = task;
                } else if (firstTask != task) {
                    if (tasks == null) {
                        tasks = new ArraySet<>();
                        tasks.add(firstTask);
                    }
                    tasks.add(task);
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
        // Also dismiss the pinned stack whenever we switch users. Removing the pinned stack will
        // also cause all tasks to be moved to the fullscreen stack at a position that is
        // appropriate.
        removeStackLocked(PINNED_STACK_ID);

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
                    stack.positionChildWindowContainerAtTop(task);
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
            resumeHomeStackTask(null, "switchUserOnOtherDisplay");
        }
        return homeInFront;
    }

    /** Checks whether the userid is a profile of the current user. */
    boolean isCurrentProfileLocked(int userId) {
        if (userId == mCurrentUser) return true;
        return mService.mUserController.isCurrentProfileLocked(userId);
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

        final boolean nowVisible = allResumedActivitiesVisible();
        for (int activityNdx = mStoppingActivities.size() - 1; activityNdx >= 0; --activityNdx) {
            ActivityRecord s = mStoppingActivities.get(activityNdx);
            boolean waitingVisible = mActivitiesWaitingForVisibleActivity.contains(s);
            if (DEBUG_STATES) Slog.v(TAG, "Stopping " + s + ": nowVisible=" + nowVisible
                    + " waitingVisible=" + waitingVisible + " finishing=" + s.finishing);
            if (waitingVisible && nowVisible) {
                mActivitiesWaitingForVisibleActivity.remove(s);
                waitingVisible = false;
                if (s.finishing) {
                    // If this activity is finishing, it is sitting on top of
                    // everyone else but we now know it is no longer needed...
                    // so get rid of it.  Otherwise, we need to go through the
                    // normal flow and hide it once we determine that it is
                    // hidden by the activities in front of it.
                    if (DEBUG_STATES) Slog.v(TAG, "Before stopping, can hide: " + s);
                    s.setVisibility(false);
                }
            }
            if (remove) {
                final ActivityStack stack = s.getStack();
                final boolean shouldSleepOrShutDown = stack != null
                        ? stack.shouldSleepOrShutDownActivities()
                        : mService.isSleepingOrShuttingDownLocked();
                if (!waitingVisible || shouldSleepOrShutDown) {
                    if (!processPausingActivities && s.state == PAUSING) {
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
        pw.print(prefix);
        pw.println("mCurTaskIdForUser=" + mCurTaskIdForUser);
        pw.print(prefix); pw.println("mUserStackInFront=" + mUserStackInFront);
        pw.print(prefix); pw.println("mStacks=" + mStacks);
        pw.print(prefix); pw.print("mLockTaskModeState=" + lockTaskModeToString());
        final SparseArray<String[]> packages = mService.mLockTaskPackages;
        if (packages.size() > 0) {
            pw.print(prefix); pw.println("mLockTaskPackages (userId:packages)=");
            for (int i = 0; i < packages.size(); ++i) {
                pw.print(prefix); pw.print(prefix); pw.print(packages.keyAt(i));
                pw.print(":"); pw.println(Arrays.toString(packages.valueAt(i)));
            }
        }
        if (!mWaitingForActivityVisible.isEmpty()) {
            pw.print(prefix); pw.println("mWaitingForActivityVisible=");
            for (int i = 0; i < mWaitingForActivityVisible.size(); ++i) {
                pw.print(prefix); pw.print(prefix); mWaitingForActivityVisible.get(i).dump(pw, prefix);
            }
        }

        pw.println(" mLockTaskModeTasks" + mLockTaskModeTasks);
        mKeyguardController.dump(pw, prefix);
    }

    /**
     * Dump all connected displays' configurations.
     * @param prefix Prefix to apply to each line of the dump.
     */
    void dumpDisplayConfigs(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.println("Display override configurations:");
        final int displayCount = mActivityDisplays.size();
        for (int i = 0; i < displayCount; i++) {
            final ActivityDisplay activityDisplay = mActivityDisplays.valueAt(i);
            pw.print(prefix); pw.print("  "); pw.print(activityDisplay.mDisplayId); pw.print(": ");
                    pw.println(activityDisplay.getOverrideConfiguration());
        }
    }

    /**
     * Dumps the activities matching the given {@param name} in the either the focused stack
     * or all visible stacks if {@param dumpVisibleStacks} is true.
     */
    ArrayList<ActivityRecord> getDumpActivitiesLocked(String name, boolean dumpVisibleStacksOnly,
            boolean dumpFocusedStackOnly) {
        if (dumpFocusedStackOnly) {
            return mFocusedStack.getDumpActivitiesLocked(name);
        } else {
            ArrayList<ActivityRecord> activities = new ArrayList<>();
            int numDisplays = mActivityDisplays.size();
            for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
                ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
                for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                    ActivityStack stack = stacks.get(stackNdx);
                    if (!dumpVisibleStacksOnly ||
                            stack.shouldBeVisible(null) == STACK_VISIBLE) {
                        activities.addAll(stack.getDumpActivitiesLocked(name));
                    }
                }
            }
            return activities;
        }
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
                stackHeader.append("  isSleeping=" + stack.shouldSleepActivities());
                stackHeader.append("\n");
                stackHeader.append("  mBounds=" + stack.mBounds);

                final boolean printedStackHeader = stack.dumpActivitiesLocked(fd, pw, dumpAll,
                        dumpClient, dumpPackage, needSep, stackHeader.toString());
                printed |= printedStackHeader;
                if (!printedStackHeader) {
                    // Ensure we always dump the stack header even if there are no activities
                    pw.println();
                    pw.println(stackHeader);
                }

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
        printed |= dumpHistoryList(fd, pw, mActivitiesWaitingForVisibleActivity, "  ", "Wait",
                false, !dumpAll, false, dumpPackage, true,
                "  Activities waiting for another to become visible:", null);
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
            if (lastTask != r.getTask()) {
                lastTask = r.getTask();
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
                        r.app.thread.dumpActivity(tp.getWriteFd(), r.appToken, innerPrefix, args);
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
        synchronized (mService) {
            getActivityDisplayOrCreateLocked(displayId);
        }
    }

    /** Check if display with specified id is added to the list. */
    boolean isDisplayAdded(int displayId) {
        return getActivityDisplayOrCreateLocked(displayId) != null;
    }

    ActivityDisplay getActivityDisplay(int displayId) {
        return mActivityDisplays.get(displayId);
    }

    /**
     * Get an existing instance of {@link ActivityDisplay} or create new if there is a
     * corresponding record in display manager.
     */
    private ActivityDisplay getActivityDisplayOrCreateLocked(int displayId) {
        ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
        if (activityDisplay != null) {
            return activityDisplay;
        }
        if (mDisplayManager == null) {
            // The system isn't fully initialized yet.
            return null;
        }
        final Display display = mDisplayManager.getDisplay(displayId);
        if (display == null) {
            // The display is not registered in DisplayManager.
            return null;
        }
        // The display hasn't been added to ActivityManager yet, create a new record now.
        activityDisplay = new ActivityDisplay(displayId);
        if (activityDisplay.mDisplay == null) {
            Slog.w(TAG, "Display " + displayId + " gone before initialization complete");
            return null;
        }
        mActivityDisplays.put(displayId, activityDisplay);
        calculateDefaultMinimalSizeOfResizeableTasks(activityDisplay);
        mWindowManager.onDisplayAdded(displayId);
        return activityDisplay;
    }

    private void calculateDefaultMinimalSizeOfResizeableTasks(ActivityDisplay display) {
        mDefaultMinSizeOfResizeableTask =
                mService.mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.default_minimal_size_resizable_task);
    }

    private void handleDisplayRemoved(int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("Can't remove the primary display.");
        }

        synchronized (mService) {
            ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
            if (activityDisplay != null) {
                final boolean destroyContentOnRemoval
                        = activityDisplay.shouldDestroyContentOnRemove();
                final ArrayList<ActivityStack> stacks = activityDisplay.mStacks;
                while (!stacks.isEmpty()) {
                    final ActivityStack stack = stacks.get(0);
                    if (destroyContentOnRemoval) {
                        moveStackToDisplayLocked(stack.mStackId, DEFAULT_DISPLAY,
                                false /* onTop */);
                        stack.finishAllActivitiesLocked(true /* immediately */);
                    } else {
                        // Moving all tasks to fullscreen stack, because it's guaranteed to be
                        // a valid launch stack for all activities. This way the task history from
                        // external display will be preserved on primary after move.
                        moveTasksToFullscreenStackLocked(stack.getStackId(), true /* onTop */);
                    }
                }

                releaseSleepTokens(activityDisplay);

                mActivityDisplays.remove(displayId);
                mWindowManager.onDisplayRemoved(displayId);
            }
        }
    }

    private void handleDisplayChanged(int displayId) {
        synchronized (mService) {
            ActivityDisplay activityDisplay = mActivityDisplays.get(displayId);
            if (activityDisplay != null) {
                // The window policy is responsible for stopping activities on the default display
                if (displayId != Display.DEFAULT_DISPLAY) {
                    int displayState = activityDisplay.mDisplay.getState();
                    if (displayState == Display.STATE_OFF && activityDisplay.mOffToken == null) {
                        activityDisplay.mOffToken =
                                mService.acquireSleepToken("Display-off", displayId);
                    } else if (displayState == Display.STATE_ON
                            && activityDisplay.mOffToken != null) {
                        activityDisplay.mOffToken.release();
                        activityDisplay.mOffToken = null;
                    }
                }
                // TODO: Update the bounds.
            }
            mWindowManager.onDisplayChanged(displayId);
        }
    }

    SleepToken createSleepTokenLocked(String tag, int displayId) {
        ActivityDisplay display = mActivityDisplays.get(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Invalid display: " + displayId);
        }

        final SleepTokenImpl token = new SleepTokenImpl(tag, displayId);
        mSleepTokens.add(token);
        display.mAllSleepTokens.add(token);
        return token;
    }

    private void removeSleepTokenLocked(SleepTokenImpl token) {
        mSleepTokens.remove(token);

        ActivityDisplay display = mActivityDisplays.get(token.mDisplayId);
        if (display != null) {
            display.mAllSleepTokens.remove(token);
            if (display.mAllSleepTokens.isEmpty()) {
                mService.updateSleepIfNeededLocked();
            }
        }
    }

    private void releaseSleepTokens(ActivityDisplay display) {
        if (display.mAllSleepTokens.isEmpty()) {
            return;
        }
        for (SleepTokenImpl token : display.mAllSleepTokens) {
            mSleepTokens.remove(token);
        }
        display.mAllSleepTokens.clear();

        mService.updateSleepIfNeededLocked();
    }

    private StackInfo getStackInfoLocked(ActivityStack stack) {
        final int displayId = stack.mDisplayId;
        final ActivityDisplay display = mActivityDisplays.get(displayId);
        StackInfo info = new StackInfo();
        stack.getWindowContainerBounds(info.bounds);
        info.displayId = displayId;
        info.stackId = stack.mStackId;
        info.userId = stack.mCurrentUser;
        info.visible = stack.shouldBeVisible(null) == STACK_VISIBLE;
        // A stack might be not attached to a display.
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
            task.getWindowContainerBounds(taskBounds[i]);
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

    void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredStackId,
            int preferredDisplayId, int actualStackId) {
        handleNonResizableTaskIfNeeded(task, preferredStackId, preferredDisplayId, actualStackId,
                false /* forceNonResizable */);
    }

    private void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredStackId,
            int preferredDisplayId, int actualStackId, boolean forceNonResizable) {
        final boolean isSecondaryDisplayPreferred =
                (preferredDisplayId != DEFAULT_DISPLAY && preferredDisplayId != INVALID_DISPLAY)
                || StackId.isDynamicStack(preferredStackId);
        if (((!isStackDockedInEffect(actualStackId) && preferredStackId != DOCKED_STACK_ID)
                && !isSecondaryDisplayPreferred) || task.isHomeTask()) {
            return;
        }

        // Handle incorrect launch/move to secondary display if needed.
        final boolean launchOnSecondaryDisplayFailed;
        if (isSecondaryDisplayPreferred) {
            final int actualDisplayId = task.getStack().mDisplayId;
            if (!task.canBeLaunchedOnDisplay(actualDisplayId)) {
                // The task landed on an inappropriate display somehow, move it to the default
                // display.
                // TODO(multi-display): Find proper stack for the task on the default display.
                mService.moveTaskToStack(task.taskId, FULLSCREEN_WORKSPACE_STACK_ID,
                        true /* toTop */);
                launchOnSecondaryDisplayFailed = true;
            } else {
                // The task might have landed on a display different from requested.
                launchOnSecondaryDisplayFailed = actualDisplayId == DEFAULT_DISPLAY
                        || (preferredDisplayId != INVALID_DISPLAY
                            && preferredDisplayId != actualDisplayId);
            }
        } else {
            // The task wasn't requested to be on a secondary display.
            launchOnSecondaryDisplayFailed = false;
        }

        final ActivityRecord topActivity = task.getTopActivity();
        if (launchOnSecondaryDisplayFailed || !task.supportsSplitScreen() || forceNonResizable) {
            if (launchOnSecondaryDisplayFailed) {
                // Display a warning toast that we tried to put a non-resizeable task on a secondary
                // display with config different from global config.
                mService.mTaskChangeNotificationController
                        .notifyActivityLaunchOnSecondaryDisplayFailed();
            } else {
                // Display a warning toast that we tried to put a non-dockable task in the docked
                // stack.
                mService.mTaskChangeNotificationController.notifyActivityDismissingDockedStack();
            }

            // Dismiss docked stack. If task appeared to be in docked stack but is not resizable -
            // we need to move it to top of fullscreen stack, otherwise it will be covered.
            moveTasksToFullscreenStackLocked(DOCKED_STACK_ID, actualStackId == DOCKED_STACK_ID);
        } else if (topActivity != null && topActivity.isNonResizableOrForcedResizable()
                && !topActivity.noDisplay) {
            final String packageName = topActivity.appInfo.packageName;
            final int reason = isSecondaryDisplayPreferred
                    ? FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY
                    : FORCED_RESIZEABLE_REASON_SPLIT_SCREEN;
            mService.mTaskChangeNotificationController.notifyActivityForcedResizable(
                    task.taskId, reason, packageName);
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
            mWindowManager.executeAppTransition();
        } else if (lockTaskModeState != LOCK_TASK_MODE_NONE) {
            handleNonResizableTaskIfNeeded(task, INVALID_STACK_ID, DEFAULT_DISPLAY,
                    task.getStackId(), true /* forceNonResizable */);
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
        final TaskRecord task = r != null ? r.getTask() : null;
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
        final ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null) {
            if (r.getStack().shouldSleepOrShutDownActivities()) {
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
            if (r.app != null && r.app.thread != null) {
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
                || (prevStack.mStackId != PINNED_STACK_ID && stack.mStackId != PINNED_STACK_ID)) {
            return;
        }

        scheduleUpdatePictureInPictureModeIfNeeded(task, stack.mBounds);
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, Rect targetStackBounds) {
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            final ActivityRecord r = task.mActivities.get(i);
            if (r.app != null && r.app.thread != null) {
                mPipModeChangedActivities.add(r);
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
            if (r.app != null && r.app.thread != null) {
                r.updatePictureInPictureMode(targetStackBounds, forceUpdate);
            }
        }
    }

    void setDockedStackMinimized(boolean minimized) {
        mIsDockMinimized = minimized;
    }

    void wakeUp(String reason) {
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), "android.server.am:TURN_ON:" + reason);
    }

    /**
     * Begin deferring resume to avoid duplicate resumes in one pass.
     */
    private void beginDeferResume() {
        mDeferResumeCount++;
    }

    /**
     * End deferring resume and determine if resume can be called.
     */
    private void endDeferResume() {
        mDeferResumeCount--;
    }

    /**
     * @return True if resume can be called.
     */
    private boolean readyToResume() {
        return mDeferResumeCount == 0;
    }

    private final class ActivityStackSupervisorHandler extends Handler {

        public ActivityStackSupervisorHandler(Looper looper) {
            super(looper);
        }

        void activityIdleInternal(ActivityRecord r, boolean processPausingActivities) {
            synchronized (mService) {
                activityIdleInternalLocked(r != null ? r.appToken : null, true /* fromTimeout */,
                        processPausingActivities, null);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REPORT_MULTI_WINDOW_MODE_CHANGED_MSG: {
                    synchronized (mService) {
                        for (int i = mMultiWindowModeChangedActivities.size() - 1; i >= 0; i--) {
                            final ActivityRecord r = mMultiWindowModeChangedActivities.remove(i);
                            r.updateMultiWindowMode();
                        }
                    }
                } break;
                case REPORT_PIP_MODE_CHANGED_MSG: {
                    synchronized (mService) {
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
                    synchronized (mService) {
                        resumeFocusedStackTopActivityLocked();
                    }
                } break;
                case SLEEP_TIMEOUT_MSG: {
                    synchronized (mService) {
                        if (mService.isSleepingOrShuttingDownLocked()) {
                            Slog.w(TAG, "Sleep timeout!  Sleeping now.");
                            checkReadyForSleepLocked(false /* allowDelay */);
                        }
                    }
                } break;
                case LAUNCH_TIMEOUT_MSG: {
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
                            boolean shouldLockKeyguard = Settings.Secure.getIntForUser(
                                    mService.mContext.getContentResolver(),
                                    Settings.Secure.LOCK_TO_APP_EXIT_LOCKED,
                                    UserHandle.USER_CURRENT) != 0;
                            if (mLockTaskModeState == LOCK_TASK_MODE_PINNED && shouldLockKeyguard) {
                                mWindowManager.lockNow(null);
                                mWindowManager.dismissKeyguard(null /* callback */);
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

    // TODO: Move to its own file.
    /** Exactly one of these classes per Display in the system. Capable of holding zero or more
     * attached {@link ActivityStack}s */
    class ActivityDisplay extends ConfigurationContainer {
        /** Actual Display this object tracks. */
        int mDisplayId;
        Display mDisplay;

        /** All of the stacks on this display. Order matters, topmost stack is in front of all other
         * stacks, bottommost behind. Accessed directly by ActivityManager package classes */
        final ArrayList<ActivityStack> mStacks = new ArrayList<>();

        /** Array of all UIDs that are present on the display. */
        private IntArray mDisplayAccessUIDs = new IntArray();

        /** All tokens used to put activities on this stack to sleep (including mOffToken) */
        final ArrayList<SleepTokenImpl> mAllSleepTokens = new ArrayList<>();
        /** The token acquired by ActivityStackSupervisor to put stacks on the display to sleep */
        SleepToken mOffToken;

        private boolean mSleeping;

        @VisibleForTesting
        ActivityDisplay() {
            mActivityDisplays.put(mDisplayId, this);
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
        }

        void attachStack(ActivityStack stack, int position) {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "attachStack: attaching " + stack
                    + " to displayId=" + mDisplayId + " position=" + position);
            mStacks.add(position, stack);
            mService.updateSleepIfNeededLocked();
        }

        void detachStack(ActivityStack stack) {
            if (DEBUG_STACK) Slog.v(TAG_STACK, "detachStack: detaching " + stack
                    + " from displayId=" + mDisplayId);
            mStacks.remove(stack);
            mService.updateSleepIfNeededLocked();
        }

        @Override
        public String toString() {
            return "ActivityDisplay={" + mDisplayId + " numStacks=" + mStacks.size() + "}";
        }

        @Override
        protected int getChildCount() {
            return mStacks.size();
        }

        @Override
        protected ConfigurationContainer getChildAt(int index) {
            return mStacks.get(index);
        }

        @Override
        protected ConfigurationContainer getParent() {
            return ActivityStackSupervisor.this;
        }

        boolean isPrivate() {
            return (mDisplay.getFlags() & FLAG_PRIVATE) != 0;
        }

        boolean isUidPresent(int uid) {
            for (ActivityStack stack : mStacks) {
                if (stack.isUidPresent(uid)) {
                    return true;
                }
            }
            return false;
        }

        /** Update and get all UIDs that are present on the display and have access to it. */
        private IntArray getPresentUIDs() {
            mDisplayAccessUIDs.clear();
            for (ActivityStack stack : mStacks) {
                stack.getPresentUIDs(mDisplayAccessUIDs);
            }
            return mDisplayAccessUIDs;
        }

        boolean shouldDestroyContentOnRemove() {
            return mDisplay.getRemoveMode() == REMOVE_MODE_DESTROY_CONTENT;
        }

        boolean shouldSleep() {
            return (mStacks.isEmpty() || !mAllSleepTokens.isEmpty())
                    && (mService.mRunningVoice == null);
        }

        boolean isSleeping() {
            return mSleeping;
        }

        void setIsSleeping(boolean asleep) {
            mSleeping = asleep;
        }
    }

    ActivityStack findStackBehind(ActivityStack stack) {
        // TODO(multi-display): We are only looking for stacks on the default display.
        final ActivityDisplay display = mActivityDisplays.get(DEFAULT_DISPLAY);
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
     * @param task The task to put into resizing mode
     */
    private void setResizingDuringAnimation(TaskRecord task) {
        mResizingTasksDuringAnimation.add(task.taskId);
        task.setTaskDockedResizing(true);
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
        if (StackId.isHomeOrRecentsStack(launchStackId)) {
            throw new IllegalArgumentException("startActivityFromRecentsInner: Task "
                    + taskId + " can't be launch in the home/recents stack.");
        }

        mWindowManager.deferSurfaceLayout();
        try {
            if (launchStackId == DOCKED_STACK_ID) {
                mWindowManager.setDockedStackCreateState(
                        activityOptions.getDockCreateMode(), null /* initialBounds */);

                // Defer updating the stack in which recents is until the app transition is done, to
                // not run into issues where we still need to draw the task in recents but the
                // docked stack is already created.
                deferUpdateBounds(RECENTS_STACK_ID);
                mWindowManager.prepareAppTransition(TRANSIT_DOCK_TASK_FROM_RECENTS, false);
            }

            task = anyTaskForIdLocked(taskId, MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE,
                    launchStackId);
            if (task == null) {
                continueUpdateBounds(RECENTS_STACK_ID);
                mWindowManager.executeAppTransition();
                throw new IllegalArgumentException(
                        "startActivityFromRecentsInner: Task " + taskId + " not found.");
            }

            // Since we don't have an actual source record here, we assume that the currently
            // focused activity was the source.
            final ActivityStack focusedStack = getFocusedStack();
            final ActivityRecord sourceRecord =
                    focusedStack != null ? focusedStack.topActivity() : null;

            if (launchStackId != INVALID_STACK_ID) {
                if (task.getStackId() != launchStackId) {
                    task.reparent(launchStackId, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, ANIMATE,
                            DEFER_RESUME, "startActivityFromRecents");
                }
            }

            // If the user must confirm credentials (e.g. when first launching a work app and the
            // Work Challenge is present) let startActivityInPackage handle the intercepting.
            if (!mService.mUserController.shouldConfirmCredentials(task.userId)
                    && task.getRootActivity() != null) {
                final ActivityRecord targetActivity = task.getTopActivity();

                mService.mActivityStarter.sendPowerHintForLaunchStartIfNeeded(true /* forceSend */,
                        targetActivity);
                mActivityMetricsLogger.notifyActivityLaunching();
                mService.moveTaskToFrontLocked(task.taskId, 0, bOptions, true /* fromRecents */);
                mActivityMetricsLogger.notifyActivityLaunched(ActivityManager.START_TASK_TO_FRONT,
                        targetActivity);

                // If we are launching the task in the docked stack, put it into resizing mode so
                // the window renders full-screen with the background filling the void. Also only
                // call this at the end to make sure that tasks exists on the window manager side.
                if (launchStackId == DOCKED_STACK_ID) {
                    setResizingDuringAnimation(task);
                }

                mService.mActivityStarter.postStartActivityProcessing(task.getTopActivity(),
                        ActivityManager.START_TASK_TO_FRONT,
                        sourceRecord != null
                                ? sourceRecord.getTask().getStackId() : INVALID_STACK_ID,
                        sourceRecord, task.getStack());
                return ActivityManager.START_TASK_TO_FRONT;
            }
            callingUid = task.mCallingUid;
            callingPackage = task.mCallingPackage;
            intent = task.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            userId = task.userId;
            int result = mService.startActivityInPackage(callingUid, callingPackage, intent, null,
                    null, null, 0, 0, bOptions, userId, task, "startActivityFromRecents");
            if (launchStackId == DOCKED_STACK_ID) {
                setResizingDuringAnimation(task);
            }
            return result;
        } finally {
            mWindowManager.continueSurfaceLayout();
        }
    }

    /**
     * @return a list of activities which are the top ones in each visible stack. The first
     * entry will be the focused activity.
     */
    List<IBinder> getTopVisibleActivities() {
        final ArrayList<IBinder> topActivityTokens = new ArrayList<>();
        // Traverse all displays.
        for (int i = mActivityDisplays.size() - 1; i >= 0; i--) {
            final ActivityDisplay display = mActivityDisplays.valueAt(i);
            // Traverse all stacks on a display.
            for (int j = display.mStacks.size() - 1; j >= 0; j--) {
                final ActivityStack stack = display.mStacks.get(j);
                // Get top activity from a visible stack and add it to the list.
                if (stack.shouldBeVisible(null /* starting */)
                        == ActivityStack.STACK_VISIBLE) {
                    final ActivityRecord top = stack.topActivity();
                    if (top != null) {
                        if (stack == mFocusedStack) {
                            topActivityTokens.add(0, top.appToken);
                        } else {
                            topActivityTokens.add(top.appToken);
                        }
                    }
                }
            }
        }
        return topActivityTokens;
    }

    /**
     * Internal container to store a match qualifier alongside a WaitResult.
     */
    static class WaitInfo {
        private final ComponentName mTargetComponent;
        private final WaitResult mResult;

        public WaitInfo(ComponentName targetComponent, WaitResult result) {
            this.mTargetComponent = targetComponent;
            this.mResult = result;
        }

        public boolean matches(ComponentName targetComponent) {
            return mTargetComponent == null || mTargetComponent.equals(targetComponent);
        }

        public WaitResult getResult() {
            return mResult;
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

    private final class SleepTokenImpl extends SleepToken {
        private final String mTag;
        private final long mAcquireTime;
        private final int mDisplayId;

        public SleepTokenImpl(String tag, int displayId) {
            mTag = tag;
            mDisplayId = displayId;
            mAcquireTime = SystemClock.uptimeMillis();
        }

        @Override
        public void release() {
            synchronized (mService) {
                removeSleepTokenLocked(this);
            }
        }

        @Override
        public String toString() {
            return "{\"" + mTag + "\", display " + mDisplayId
                    + ", acquire at " + TimeUtils.formatUptime(mAcquireTime) + "}";
        }
    }

}
