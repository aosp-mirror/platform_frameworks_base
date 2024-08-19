/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Context.DEVICE_POLICY_SERVICE;
import static android.content.Context.STATUS_BAR_SERVICE;
import static android.content.Intent.ACTION_CALL_EMERGENCY;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;
import static android.telecom.TelecomManager.EMERGENCY_DIALER_COMPONENT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IDevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.telephony.CellBroadcastUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Helper class that deals with all things related to task locking. This includes the screen pinning
 * mode that can be launched via System UI as well as the fully locked mode that can be achieved
 * on fully managed devices.
 *
 * Note: All methods in this class should only be called with the ActivityTaskManagerService lock
 * held.
 *
 * @see Activity#startLockTask()
 * @see Activity#stopLockTask()
 */
public class LockTaskController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "LockTaskController" : TAG_ATM;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;

    @VisibleForTesting
    static final int STATUS_BAR_MASK_LOCKED = StatusBarManager.DISABLE_MASK
            & (~StatusBarManager.DISABLE_EXPAND)
            & (~StatusBarManager.DISABLE_NOTIFICATION_TICKER)
            & (~StatusBarManager.DISABLE_SYSTEM_INFO)
            & (~StatusBarManager.DISABLE_BACK);
    @VisibleForTesting
    static final int STATUS_BAR_MASK_PINNED = StatusBarManager.DISABLE_MASK
            & (~StatusBarManager.DISABLE_BACK)
            & (~StatusBarManager.DISABLE_HOME)
            & (~StatusBarManager.DISABLE_RECENT);

    private static final SparseArray<Pair<Integer, Integer>> STATUS_BAR_FLAG_MAP_LOCKED;
    static {
        STATUS_BAR_FLAG_MAP_LOCKED = new SparseArray<>();

        STATUS_BAR_FLAG_MAP_LOCKED.append(DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
                new Pair<>(StatusBarManager.DISABLE_CLOCK, StatusBarManager.DISABLE2_SYSTEM_ICONS));

        STATUS_BAR_FLAG_MAP_LOCKED.append(DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS,
                new Pair<>(StatusBarManager.DISABLE_NOTIFICATION_ICONS
                        | StatusBarManager.DISABLE_NOTIFICATION_ALERTS,
                        StatusBarManager.DISABLE2_NOTIFICATION_SHADE));

        STATUS_BAR_FLAG_MAP_LOCKED.append(DevicePolicyManager.LOCK_TASK_FEATURE_HOME,
                new Pair<>(StatusBarManager.DISABLE_HOME, StatusBarManager.DISABLE2_NONE));

        STATUS_BAR_FLAG_MAP_LOCKED.append(DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW,
                new Pair<>(StatusBarManager.DISABLE_RECENT, StatusBarManager.DISABLE2_NONE));

        STATUS_BAR_FLAG_MAP_LOCKED.append(DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
                new Pair<>(StatusBarManager.DISABLE_NONE,
                        StatusBarManager.DISABLE2_GLOBAL_ACTIONS));
    }

    /** Tag used for disabling of keyguard */
    private static final String LOCK_TASK_TAG = "Lock-to-App";

    /** Can't be put in lockTask mode. */
    static final int LOCK_TASK_AUTH_DONT_LOCK = 0;
    /** Can enter app pinning with user approval. Can never start over existing lockTask task. */
    static final int LOCK_TASK_AUTH_PINNABLE = 1;
    /** Starts in LOCK_TASK_MODE_LOCKED automatically. Can start over existing lockTask task. */
    static final int LOCK_TASK_AUTH_LAUNCHABLE = 2;
    /** Can enter lockTask without user approval. Can start over existing lockTask task. */
    static final int LOCK_TASK_AUTH_ALLOWLISTED = 3;
    /** Priv-app that starts in LOCK_TASK_MODE_LOCKED automatically. Can start over existing
     * lockTask task. */
    static final int LOCK_TASK_AUTH_LAUNCHABLE_PRIV = 4;

    private final IBinder mToken = new LockTaskToken();
    private final ActivityTaskSupervisor mSupervisor;
    private final Context mContext;
    private final TaskChangeNotificationController mTaskChangeNotificationController;

    // The following system services cannot be final, because they do not exist when this class
    // is instantiated during device boot
    @VisibleForTesting
    IStatusBarService mStatusBarService;
    @VisibleForTesting
    IDevicePolicyManager mDevicePolicyManager;
    @VisibleForTesting
    WindowManagerService mWindowManager;
    @VisibleForTesting
    LockPatternUtils mLockPatternUtils;
    @VisibleForTesting
    TelecomManager mTelecomManager;

    /**
     * The chain of tasks in LockTask mode, in the order of when they first entered LockTask mode.
     *
     * The first task in the list, which started the current LockTask session, is called the root
     * task. It coincides with the Home task in a typical multi-app kiosk deployment. When there are
     * more than one locked tasks, the root task can't be finished. Nor can it be moved to the back
     * of the stack by {@link Task#moveTaskToBack(Task)};
     *
     * Calling {@link Activity#stopLockTask()} on the root task will finish all tasks but itself in
     * this list, and the device will exit LockTask mode.
     *
     * The list is empty if LockTask is inactive.
     */
    private final ArrayList<Task> mLockTaskModeTasks = new ArrayList<>();

    /**
     * Packages that are allowed to be launched into the lock task mode for each user.
     */
    private final SparseArray<String[]> mLockTaskPackages = new SparseArray<>();

    /**
     * Features that are allowed by DPC to show during LockTask mode.
     */
    private final SparseIntArray mLockTaskFeatures = new SparseIntArray();

    /**
     * Store the current lock task mode. Possible values:
     * {@link ActivityManager#LOCK_TASK_MODE_NONE}, {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     * {@link ActivityManager#LOCK_TASK_MODE_PINNED}
     */
    private volatile int mLockTaskModeState = LOCK_TASK_MODE_NONE;

    /**
     * This is ActivityTaskSupervisor's Handler.
     */
    private final Handler mHandler;

    /**
     * Stores the user for which we're trying to dismiss the keyguard and then subsequently
     * disable it.
     *
     * Tracking this ensures we don't mistakenly disable the keyguard if we've stopped trying to
     * between the dismiss request and when it succeeds.
     *
     * Must only be accessed from the Handler thread.
     */
    private int mPendingDisableFromDismiss = UserHandle.USER_NULL;

    LockTaskController(Context context, ActivityTaskSupervisor supervisor,
            Handler handler, TaskChangeNotificationController taskChangeNotificationController) {
        mContext = context;
        mSupervisor = supervisor;
        mHandler = handler;
        mTaskChangeNotificationController = taskChangeNotificationController;
    }

    /**
     * Set the window manager instance used in this class. This is necessary, because the window
     * manager does not exist during instantiation of this class.
     */
    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
    }

    /**
     * @return the current lock task state. This can be any of
     * {@link ActivityManager#LOCK_TASK_MODE_NONE}, {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     * {@link ActivityManager#LOCK_TASK_MODE_PINNED}.
     */
    int getLockTaskModeState() {
        return mLockTaskModeState;
    }

    /**
     * @return whether the given task is locked at the moment. Locked tasks cannot be moved to the
     * back of the stack.
     */
    @VisibleForTesting
    boolean isTaskLocked(Task task) {
        return mLockTaskModeTasks.contains(task);
    }

    /**
     * @return {@code true} whether this task first started the current LockTask session.
     */
    private boolean isRootTask(Task task) {
        return mLockTaskModeTasks.indexOf(task) == 0;
    }

    /**
     * @return whether the given activity is blocked from finishing, because it is the only activity
     * of the last locked task and finishing it would mean that lock task mode is ended illegally.
     */
    boolean activityBlockedFromFinish(ActivityRecord activity) {
        final Task task = activity.getTask();
        if (task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE_PRIV || !isRootTask(task)) {
            return false;
        }

        final ActivityRecord taskTop = task.getTopNonFinishingActivity();
        final ActivityRecord taskRoot = task.getRootActivity();
        // If task has more than one Activity, verify if there's only adjacent TaskFragments that
        // should be finish together in the Task.
        if (activity != taskRoot || activity != taskTop) {
            final TaskFragment taskFragment = activity.getTaskFragment();
            final TaskFragment adjacentTaskFragment = taskFragment.getAdjacentTaskFragment();
            if (taskFragment.asTask() != null
                    || !taskFragment.isDelayLastActivityRemoval()
                    || adjacentTaskFragment == null) {
                // Don't block activity from finishing if the TaskFragment don't have any adjacent
                // TaskFragment, or it won't finish together with its adjacent TaskFragment.
                return false;
            }

            final boolean hasOtherActivityInTaskFragment =
                    taskFragment.getActivity(a -> !a.finishing && a != activity) != null;
            if (hasOtherActivityInTaskFragment) {
                // Don't block activity from finishing if there's other Activity in the same
                // TaskFragment.
                return false;
            }

            final boolean hasOtherActivityInTask = task.getActivity(a -> !a.finishing
                    && a != activity && a.getTaskFragment() != adjacentTaskFragment) != null;
            if (hasOtherActivityInTask) {
                // Do not block activity from finishing if there are another running activities
                // after the current and adjacent TaskFragments are removed. Note that we don't
                // check activities in adjacent TaskFragment because it will be finished together
                // with TaskFragment regardless of numbers of activities.
                return false;
            }
        }

        Slog.i(TAG, "Not finishing task in lock task mode");
        showLockTaskToast();
        return true;
    }

    /**
     * @return whether the given task can be moved to the back of the stack with
     * {@link Task#moveTaskToBack(Task)}
     * @see #mLockTaskModeTasks
     */
    boolean canMoveTaskToBack(Task task) {
        if (isRootTask(task)) {
            showLockTaskToast();
            return false;
        }
        return true;
    }

    /**
     * @return whether the requested task auth is allowed to be locked.
     */
    static boolean isTaskAuthAllowlisted(int lockTaskAuth) {
        switch(lockTaskAuth) {
            case LOCK_TASK_AUTH_ALLOWLISTED:
            case LOCK_TASK_AUTH_LAUNCHABLE:
            case LOCK_TASK_AUTH_LAUNCHABLE_PRIV:
                return true;
            case LOCK_TASK_AUTH_PINNABLE:
            case LOCK_TASK_AUTH_DONT_LOCK:
            default:
                return false;
        }
    }

    /**
     * @return whether the requested task is disallowed to be launched.
     */
    boolean isLockTaskModeViolation(Task task) {
        return isLockTaskModeViolation(task, false);
    }

    /**
     * @param isNewClearTask whether the task would be cleared as part of the operation.
     * @return whether the requested task is disallowed to be launched.
     */
    boolean isLockTaskModeViolation(Task task, boolean isNewClearTask) {
        // TODO: Double check what's going on here. If the task is already in lock task mode, it's
        // likely allowlisted, so will return false below.
        if (isTaskLocked(task) && !isNewClearTask) {
            // If the task is already at the top and won't be cleared, then allow the operation
        } else if (isLockTaskModeViolationInternal(task, task.mUserId, task.intent,
                task.mLockTaskAuth)) {
            showLockTaskToast();
            return true;
        }
        return false;
    }

    /**
     * @param activity an activity that is going to be started in a new task as the root activity.
     * @return whether the given activity is allowed to be launched.
     */
    boolean isNewTaskLockTaskModeViolation(ActivityRecord activity) {
        // Use the belong task (if any) to perform the lock task checks
        if (activity.getTask() != null) {
            return isLockTaskModeViolation(activity.getTask());
        }

        int auth = getLockTaskAuth(activity, null /* task */);
        if (isLockTaskModeViolationInternal(activity, activity.mUserId, activity.intent, auth)) {
            showLockTaskToast();
            return true;
        }
        return false;
    }

    /**
     * @return the root task of the lock task.
     */
    Task getRootTask() {
        if (mLockTaskModeTasks.isEmpty()) {
            return null;
        }
        return mLockTaskModeTasks.get(0);
    }

    private boolean isLockTaskModeViolationInternal(WindowContainer wc, int userId,
            Intent intent, int taskAuth) {
        // Allow recents activity if enabled by policy
        if (wc.isActivityTypeRecents() && isRecentsAllowed(userId)) {
            return false;
        }

        // Allow emergency calling when the device is protected by a locked keyguard
        if (isKeyguardAllowed(userId) && isEmergencyCallIntent(intent)) {
            return false;
        }

        // Allow the dream to start during lock task mode
        if (wc.isActivityTypeDream()) {
            return false;
        }

        if (isWirelessEmergencyAlert(intent)) {
            return false;
        }

        return !(isTaskAuthAllowlisted(taskAuth) || mLockTaskModeTasks.isEmpty());
    }

    private boolean isRecentsAllowed(int userId) {
        return (getLockTaskFeaturesForUser(userId)
                & DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW) != 0;
    }

    private boolean isKeyguardAllowed(int userId) {
        return (getLockTaskFeaturesForUser(userId)
                & DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD) != 0;
    }

    private boolean isBlockingInTaskEnabled(int userId) {
        return (getLockTaskFeaturesForUser(userId)
                & DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK) != 0;
    }

    boolean isActivityAllowed(int userId, String packageName, int lockTaskLaunchMode) {
        if (mLockTaskModeState != LOCK_TASK_MODE_LOCKED || !isBlockingInTaskEnabled(userId)) {
            return true;
        }
        switch (lockTaskLaunchMode) {
            case LOCK_TASK_LAUNCH_MODE_ALWAYS:
                return true;
            case LOCK_TASK_LAUNCH_MODE_NEVER:
                return false;
            default:
        }
        return isPackageAllowlisted(userId, packageName);
    }

    private boolean isWirelessEmergencyAlert(Intent intent) {
        if (intent == null) {
            return false;
        }

        final ComponentName cellBroadcastAlertDialogComponentName =
                CellBroadcastUtils.getDefaultCellBroadcastAlertDialogComponent(mContext);

        if (cellBroadcastAlertDialogComponentName == null) {
            return false;
        }

        if (cellBroadcastAlertDialogComponentName.equals(intent.getComponent())) {
            return true;
        }

        return false;
    }

    private boolean isEmergencyCallIntent(Intent intent) {
        if (intent == null) {
            return false;
        }

        // 1. The emergency keypad activity launched on top of the keyguard
        if (EMERGENCY_DIALER_COMPONENT.equals(intent.getComponent())) {
            return true;
        }

        // 2. The intent sent by the keypad, which is handled by Telephony
        if (ACTION_CALL_EMERGENCY.equals(intent.getAction())) {
            return true;
        }

        // 3. Telephony then starts the default package for making the call
        final TelecomManager tm = getTelecomManager();
        final String dialerPackage = tm != null ? tm.getSystemDialerPackage() : null;
        if (dialerPackage != null && dialerPackage.equals(intent.getComponent().getPackageName())) {
            return true;
        }

        return false;
    }

    /**
     * Stop the current lock task mode.
     *
     * This is called by {@link ActivityManagerService} and performs various checks before actually
     * finishing the locked task.
     *
     * @param task the task that requested the end of lock task mode ({@code null} for quitting app
     *             pinning mode)
     * @param stopAppPinning indicates whether to stop app pinning mode or to stop a task from
     *                       being locked.
     * @param callingUid the caller that requested the end of lock task mode.
     * @throws IllegalArgumentException if the calling task is invalid (e.g., {@code null} or not in
     *                                  foreground)
     * @throws SecurityException if the caller is not authorized to stop the lock task mode, i.e. if
     *                           they differ from the one that launched lock task mode.
     */
    void stopLockTaskMode(@Nullable Task task, boolean stopAppPinning, int callingUid) {
        if (mLockTaskModeState == LOCK_TASK_MODE_NONE) {
            return;
        }

        if (stopAppPinning) {
            if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                clearLockedTasks("stopAppPinning");
            } else {
                Slog.e(TAG_LOCKTASK, "Attempted to stop app pinning while fully locked");
                showLockTaskToast();
            }

        } else {
            // Ensure calling activity is not null
            if (task == null) {
                throw new IllegalArgumentException("can't stop LockTask for null task");
            }

            // Ensure the same caller for startLockTaskMode and stopLockTaskMode.
            // It is possible lockTaskMode was started by the system process because
            // android:lockTaskMode is set to a locking value in the application manifest
            // instead of the app calling startLockTaskMode. In this case
            // {@link Task.mLockTaskUid} will be 0, so we compare the callingUid to the
            // {@link Task.effectiveUid} instead. Also caller with
            // {@link MANAGE_ACTIVITY_TASKS} can stop any lock task.
            if (callingUid != task.mLockTaskUid
                    && (task.mLockTaskUid != 0 || callingUid != task.effectiveUid)) {
                throw new SecurityException("Invalid uid, expected " + task.mLockTaskUid
                        + " callingUid=" + callingUid + " effectiveUid=" + task.effectiveUid);
            }

            // We don't care if it's pinned or locked mode; this will stop it anyways.
            clearLockedTask(task);
        }
    }

    /**
     * Clear all locked tasks and request the end of LockTask mode.
     *
     * This method is called by UserController when starting a new foreground user, and,
     * unlike {@link #stopLockTaskMode(Task, boolean, int)}, it doesn't perform the checks.
     */
    void clearLockedTasks(String reason) {
        ProtoLog.i(WM_DEBUG_LOCKTASK, "clearLockedTasks: %s", reason);
        if (!mLockTaskModeTasks.isEmpty()) {
            clearLockedTask(mLockTaskModeTasks.get(0));
        }
    }

    /**
     * Clear one locked task from LockTask mode.
     *
     * If the requested task is the root task (see {@link #mLockTaskModeTasks}), then all locked
     * tasks are cleared. Otherwise, only the requested task is cleared. LockTask mode is stopped
     * when the last locked task is cleared.
     *
     * @param task the task to be cleared from LockTask mode.
     */
    void clearLockedTask(final Task task) {
        if (task == null || mLockTaskModeTasks.isEmpty()) return;

        if (task == mLockTaskModeTasks.get(0)) {
            // We're removing the root task while there are other locked tasks. Therefore we should
            // clear all locked tasks in reverse order.
            for (int taskNdx = mLockTaskModeTasks.size() - 1; taskNdx > 0; --taskNdx) {
                clearLockedTask(mLockTaskModeTasks.get(taskNdx));
            }
        }

        removeLockedTask(task);
        if (mLockTaskModeTasks.isEmpty()) {
            return;
        }
        task.performClearTaskForReuse(false /* excludingTaskOverlay*/);
        mSupervisor.mRootWindowContainer.resumeFocusedTasksTopActivities();
    }

    /**
     * Remove the given task from the locked task list. If this was the last task in the list,
     * lock task mode is stopped.
     */
    private void removeLockedTask(final Task task) {
        if (!mLockTaskModeTasks.remove(task)) {
            return;
        }
        ProtoLog.d(WM_DEBUG_LOCKTASK, "removeLockedTask: removed %s", task);
        if (mLockTaskModeTasks.isEmpty()) {
            ProtoLog.d(WM_DEBUG_LOCKTASK, "removeLockedTask: task=%s last task, "
                    + "reverting locktask mode. Callers=%s", task, Debug.getCallers(3));
            mHandler.post(() -> performStopLockTask(task.mUserId));
        }
    }

    // This method should only be called on the handler thread
    private void performStopLockTask(int userId) {
        // Update the internal mLockTaskModeState early to avoid the scenario that SysUI queries
        // mLockTaskModeState (from setStatusBarState) and gets staled state.
        // TODO: revisit this approach.
        // The race condition raised above can be addressed by moving this function out of handler
        // thread, which makes it guarded by ATMS#mGlobalLock as ATMS#getLockTaskModeState.
        final int oldLockTaskModeState = mLockTaskModeState;
        mLockTaskModeState = LOCK_TASK_MODE_NONE;
        mTaskChangeNotificationController.notifyLockTaskModeChanged(mLockTaskModeState);
        // When lock task ends, we enable the status bars.
        try {
            setStatusBarState(mLockTaskModeState, userId);
            setKeyguardState(mLockTaskModeState, userId);
            if (oldLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                lockKeyguardIfNeeded(userId);
            }
            if (getDevicePolicyManager() != null) {
                getDevicePolicyManager().notifyLockTaskModeChanged(false, null, userId);
            }
            if (oldLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                final IStatusBarService statusBarService = getStatusBarService();
                if (statusBarService != null) {
                    statusBarService.showPinningEnterExitToast(false /* entering */);
                }
            }
            mWindowManager.onLockTaskStateChanged(mLockTaskModeState);
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Show the lock task violation toast. Currently we only show toast for screen pinning mode, and
     * no-op if the device is in locked mode.
     */
    void showLockTaskToast() {
        if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
            try {
                final IStatusBarService statusBarService = getStatusBarService();
                if (statusBarService != null) {
                    statusBarService.showPinningEscapeToast();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to send pinning escape toast", e);
            }
        }
    }

    // Starting lock task

    /**
     * Method to start lock task mode on a given task.
     *
     * @param task the task that should be locked.
     * @param isSystemCaller indicates whether this request was initiated by the system via
     *                       {@link ActivityTaskManagerService#startSystemLockTaskMode(int)}. If
     *                       {@code true}, this intends to start pinned mode; otherwise, we look
     *                       at the calling task's mLockTaskAuth to decide which mode to start.
     * @param callingUid the caller that requested the launch of lock task mode.
     */
    void startLockTaskMode(@NonNull Task task, boolean isSystemCaller, int callingUid) {
        if (task.mLockTaskAuth == LOCK_TASK_AUTH_DONT_LOCK) {
            ProtoLog.w(WM_DEBUG_LOCKTASK, "startLockTaskMode: Can't lock due to auth");
            return;
        }
        if (!isSystemCaller) {
            task.mLockTaskUid = callingUid;
            if (task.mLockTaskAuth == LOCK_TASK_AUTH_PINNABLE) {
                // startLockTask() called by app, but app is not part of lock task allowlist. Show
                // app pinning request. We will come back here with isSystemCaller true.
                ProtoLog.w(WM_DEBUG_LOCKTASK, "Mode default, asking user");
                StatusBarManagerInternal statusBarManager = LocalServices.getService(
                        StatusBarManagerInternal.class);
                if (statusBarManager != null) {
                    statusBarManager.showScreenPinningRequest(task.mTaskId);
                }
                return;
            } else if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                // startLockTask() called by app, and app is part of lock task allowlist.
                // Deactivate the currently pinned task before doing so.
                Slog.i(TAG, "Stop app pinning before entering full lock task mode");
                stopLockTaskMode(/* task= */ null, /* stopAppPinning= */ true, callingUid);
            }
        }

        // When a task is locked, dismiss the root pinned task if it exists 
        mSupervisor.mRootWindowContainer.removeRootTasksInWindowingModes(WINDOWING_MODE_PINNED);

        // System can only initiate screen pinning, not full lock task mode
        ProtoLog.w(WM_DEBUG_LOCKTASK, "%s", isSystemCaller ? "Locking pinned" : "Locking fully");
        setLockTaskMode(task, isSystemCaller ? LOCK_TASK_MODE_PINNED : LOCK_TASK_MODE_LOCKED,
                "startLockTask", true);
    }

    /**
     * Start lock task mode on the given task.
     * @param lockTaskModeState whether fully locked or pinned mode.
     * @param andResume whether the task should be brought to foreground as part of the operation.
     */
    private void setLockTaskMode(@NonNull Task task, int lockTaskModeState,
                                 String reason, boolean andResume) {
        // Should have already been checked, but do it again.
        if (task.mLockTaskAuth == LOCK_TASK_AUTH_DONT_LOCK) {
            ProtoLog.w(WM_DEBUG_LOCKTASK,
                    "setLockTaskMode: Can't lock due to auth");
            return;
        }
        if (isLockTaskModeViolation(task)) {
            Slog.e(TAG_LOCKTASK, "setLockTaskMode: Attempt to start an unauthorized lock task.");
            return;
        }

        final Intent taskIntent = task.intent;
        if (mLockTaskModeTasks.isEmpty() && taskIntent != null) {
            mSupervisor.mRecentTasks.onLockTaskModeStateChanged(lockTaskModeState, task.mUserId);
            // Start lock task on the handler thread
            mHandler.post(() -> performStartLockTask(
                    taskIntent.getComponent().getPackageName(),
                    task.mUserId,
                    lockTaskModeState));
        }
        ProtoLog.w(WM_DEBUG_LOCKTASK, "setLockTaskMode: Locking to %s Callers=%s",
                task, Debug.getCallers(4));

        if (!mLockTaskModeTasks.contains(task)) {
            mLockTaskModeTasks.add(task);
        }

        if (task.mLockTaskUid == -1) {
            task.mLockTaskUid = task.effectiveUid;
        }

        if (andResume) {
            mSupervisor.findTaskToMoveToFront(task, 0, null, reason,
                    lockTaskModeState != LOCK_TASK_MODE_NONE);
            mSupervisor.mRootWindowContainer.resumeFocusedTasksTopActivities();
            final Task rootTask = task.getRootTask();
            if (rootTask != null) {
                rootTask.mDisplayContent.executeAppTransition();
            }
        } else if (lockTaskModeState != LOCK_TASK_MODE_NONE) {
            mSupervisor.handleNonResizableTaskIfNeeded(task, WINDOWING_MODE_UNDEFINED,
                    mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea(),
                    task.getRootTask(), true /* forceNonResizable */);
        }
    }

    // This method should only be called on the handler thread
    private void performStartLockTask(String packageName, int userId, int lockTaskModeState) {
        // When lock task starts, we disable the status bars.
        try {
            if (lockTaskModeState == LOCK_TASK_MODE_PINNED) {
                final IStatusBarService statusBarService = getStatusBarService();
                if (statusBarService != null) {
                    statusBarService.showPinningEnterExitToast(true /* entering */);
                }
            }
            mWindowManager.onLockTaskStateChanged(lockTaskModeState);
            mLockTaskModeState = lockTaskModeState;
            mTaskChangeNotificationController.notifyLockTaskModeChanged(mLockTaskModeState);
            setStatusBarState(lockTaskModeState, userId);
            setKeyguardState(lockTaskModeState, userId);
            if (getDevicePolicyManager() != null) {
                getDevicePolicyManager().notifyLockTaskModeChanged(true, packageName, userId);
            }
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Update packages that are allowed to be launched in lock task mode.
     * @param userId Which user this allowlist is associated with
     * @param packages The allowlist of packages allowed in lock task mode
     * @see #mLockTaskPackages
     */
    void updateLockTaskPackages(int userId, String[] packages) {
        mLockTaskPackages.put(userId, packages);

        boolean taskChanged = false;
        for (int taskNdx = mLockTaskModeTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task lockedTask = mLockTaskModeTasks.get(taskNdx);
            final boolean wasAllowlisted = lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE
                    || lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_ALLOWLISTED;
            lockedTask.setLockTaskAuth();
            final boolean isAllowlisted = lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE
                    || lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_ALLOWLISTED;

            if (mLockTaskModeState != LOCK_TASK_MODE_LOCKED
                    || lockedTask.mUserId != userId
                    || !wasAllowlisted || isAllowlisted) {
                continue;
            }

            // Terminate locked tasks that have recently lost allowlist authorization.
            ProtoLog.d(WM_DEBUG_LOCKTASK, "onLockTaskPackagesUpdated: removing %s"
                    + " mLockTaskAuth()=%s", lockedTask, lockedTask.lockTaskAuthToString());
            removeLockedTask(lockedTask);
            lockedTask.performClearTaskForReuse(false /* excludingTaskOverlay*/);
            taskChanged = true;
        }

        mSupervisor.mRootWindowContainer.forAllTasks(Task::setLockTaskAuth);

        final ActivityRecord r = mSupervisor.mRootWindowContainer.topRunningActivity();
        final Task task = (r != null) ? r.getTask() : null;
        if (mLockTaskModeTasks.isEmpty() && task!= null
                && task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE) {
            // This task must have just been authorized.
            ProtoLog.d(WM_DEBUG_LOCKTASK, "onLockTaskPackagesUpdated: starting new "
                    + "locktask task=%s", task);
            setLockTaskMode(task, LOCK_TASK_MODE_LOCKED, "package updated", false);
            taskChanged = true;
        }

        if (taskChanged) {
            mSupervisor.mRootWindowContainer.resumeFocusedTasksTopActivities();
        }
    }

    int getLockTaskAuth(@Nullable ActivityRecord rootActivity, @Nullable Task task) {
        if (rootActivity == null && task == null) {
            return LOCK_TASK_AUTH_DONT_LOCK;
        }
        if (rootActivity == null) {
            return LOCK_TASK_AUTH_PINNABLE;
        }

        final String pkg = (task == null || task.realActivity == null) ? rootActivity.packageName
                : task.realActivity.getPackageName();
        final int userId = task != null ? task.mUserId : rootActivity.mUserId;
        int lockTaskAuth = LOCK_TASK_AUTH_DONT_LOCK;
        switch (rootActivity.lockTaskLaunchMode) {
            case LOCK_TASK_LAUNCH_MODE_DEFAULT:
                lockTaskAuth = isPackageAllowlisted(userId, pkg)
                        ? LOCK_TASK_AUTH_ALLOWLISTED : LOCK_TASK_AUTH_PINNABLE;
                break;

            case LOCK_TASK_LAUNCH_MODE_NEVER:
                lockTaskAuth = LOCK_TASK_AUTH_DONT_LOCK;
                break;

            case LOCK_TASK_LAUNCH_MODE_ALWAYS:
                lockTaskAuth = LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
                break;

            case LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED:
                lockTaskAuth = isPackageAllowlisted(userId, pkg)
                        ? LOCK_TASK_AUTH_LAUNCHABLE : LOCK_TASK_AUTH_PINNABLE;
                break;
        }
        return lockTaskAuth;
    }

    boolean isPackageAllowlisted(int userId, String pkg) {
        if (pkg == null) {
            return false;
        }
        String[] allowlist;
        allowlist = mLockTaskPackages.get(userId);
        if (allowlist == null) {
            return false;
        }
        for (String allowlistedPkg : allowlist) {
            if (pkg.equals(allowlistedPkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Update the UI features that are enabled for LockTask mode.
     * @param userId Which user these feature flags are associated with
     * @param flags Bitfield of feature flags
     * @see DevicePolicyManager#setLockTaskFeatures(ComponentName, int)
     */
    void updateLockTaskFeatures(int userId, int flags) {
        int oldFlags = getLockTaskFeaturesForUser(userId);
        if (flags == oldFlags) {
            return;
        }

        mLockTaskFeatures.put(userId, flags);
        if (!mLockTaskModeTasks.isEmpty() && userId == mLockTaskModeTasks.get(0).mUserId) {
            mHandler.post(() -> {
                if (mLockTaskModeState == LOCK_TASK_MODE_LOCKED) {
                    setStatusBarState(mLockTaskModeState, userId);
                    setKeyguardState(mLockTaskModeState, userId);
                }
            });
        }
    }

    /**
     * Helper method for configuring the status bar disabled state.
     * Should only be called on the handler thread to avoid race.
     */
    private void setStatusBarState(int lockTaskModeState, int userId) {
        IStatusBarService statusBar = getStatusBarService();
        if (statusBar == null) {
            Slog.e(TAG, "Can't find StatusBarService");
            return;
        }

        // Default state, when lockTaskModeState == LOCK_TASK_MODE_NONE
        int flags1 = StatusBarManager.DISABLE_NONE;
        int flags2 = StatusBarManager.DISABLE2_NONE;

        if (lockTaskModeState == LOCK_TASK_MODE_PINNED) {
            flags1 = STATUS_BAR_MASK_PINNED;

        } else if (lockTaskModeState == LOCK_TASK_MODE_LOCKED) {
            int lockTaskFeatures = getLockTaskFeaturesForUser(userId);
            Pair<Integer, Integer> statusBarFlags = getStatusBarDisableFlags(lockTaskFeatures);
            flags1 = statusBarFlags.first;
            flags2 = statusBarFlags.second;
        }

        try {
            statusBar.disable(flags1, mToken, mContext.getPackageName());
            statusBar.disable2(flags2, mToken, mContext.getPackageName());
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set status bar flags", e);
        }
    }

    /**
     * Helper method for configuring the keyguard disabled state.
     * Should only be called on the handler thread to avoid race.
     */
    private void setKeyguardState(int lockTaskModeState, int userId) {
        mPendingDisableFromDismiss = UserHandle.USER_NULL;
        if (lockTaskModeState == LOCK_TASK_MODE_NONE) {
            mWindowManager.reenableKeyguard(mToken, userId);

        } else if (lockTaskModeState == LOCK_TASK_MODE_LOCKED) {
            if (isKeyguardAllowed(userId)) {
                mWindowManager.reenableKeyguard(mToken, userId);
            } else {
                // If keyguard is not secure and it is locked, dismiss the keyguard before
                // disabling it, which avoids the platform to think the keyguard is still on.
                if (mWindowManager.isKeyguardLocked() && !mWindowManager.isKeyguardSecure(userId)) {
                    mPendingDisableFromDismiss = userId;
                    mWindowManager.dismissKeyguard(new IKeyguardDismissCallback.Stub() {
                        @Override
                        public void onDismissError() throws RemoteException {
                            Slog.i(TAG, "setKeyguardState: failed to dismiss keyguard");
                        }

                        @Override
                        public void onDismissSucceeded() throws RemoteException {
                            mHandler.post(
                                    () -> {
                                        if (mPendingDisableFromDismiss == userId) {
                                            mWindowManager.disableKeyguard(mToken, LOCK_TASK_TAG,
                                                    userId);
                                            mPendingDisableFromDismiss = UserHandle.USER_NULL;
                                        }
                                    });
                        }

                        @Override
                        public void onDismissCancelled() throws RemoteException {
                            Slog.i(TAG, "setKeyguardState: dismiss cancelled");
                        }
                    }, null);
                } else {
                    mWindowManager.disableKeyguard(mToken, LOCK_TASK_TAG, userId);
                }
            }

        } else { // lockTaskModeState == LOCK_TASK_MODE_PINNED
            mWindowManager.disableKeyguard(mToken, LOCK_TASK_TAG, userId);
        }
    }

    /**
     * Helper method for locking the device immediately. This may be necessary when the device
     * leaves the pinned mode.
     */
    private void lockKeyguardIfNeeded(int userId) {
        if (shouldLockKeyguard(userId)) {
            mWindowManager.lockNow(null);
            mWindowManager.dismissKeyguard(null /* callback */, null /* message */);
            getLockPatternUtils().requireCredentialEntry(USER_ALL);
        }
    }

    private boolean shouldLockKeyguard(int userId) {
        // This functionality should be kept consistent with
        // com.android.settings.security.ScreenPinningSettings (see b/127605586)
        try {
            return Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_TO_APP_EXIT_LOCKED, USER_CURRENT) != 0;
        } catch (Settings.SettingNotFoundException e) {
            // Log to SafetyNet for b/127605586
            android.util.EventLog.writeEvent(0x534e4554, "127605586", -1, "");
            return getLockPatternUtils().isSecure(userId);
        }
    }

    /**
     * Translates from LockTask feature flags to StatusBarManager disable and disable2 flags.
     * @param lockTaskFlags Bitfield of flags as per
     *                      {@link DevicePolicyManager#setLockTaskFeatures(ComponentName, int)}
     * @return A {@link Pair} of {@link StatusBarManager#disable(int)} and
     *         {@link StatusBarManager#disable2(int)} flags
     */
    @VisibleForTesting
    Pair<Integer, Integer> getStatusBarDisableFlags(int lockTaskFlags) {
        // Everything is disabled by default
        int flags1 = StatusBarManager.DISABLE_MASK;
        int flags2 = StatusBarManager.DISABLE2_MASK;
        for (int i = STATUS_BAR_FLAG_MAP_LOCKED.size() - 1; i >= 0; i--) {
            Pair<Integer, Integer> statusBarFlags = STATUS_BAR_FLAG_MAP_LOCKED.valueAt(i);
            if ((STATUS_BAR_FLAG_MAP_LOCKED.keyAt(i) & lockTaskFlags) != 0) {
                flags1 &= ~statusBarFlags.first;
                flags2 &= ~statusBarFlags.second;
            }
        }
        // Some flags are not used for LockTask purposes, so we mask them
        flags1 &= STATUS_BAR_MASK_LOCKED;
        return new Pair<>(flags1, flags2);
    }

    /**
     * @param packageName The package to check
     * @return Whether the package is the base of any locked task
     */
    boolean isBaseOfLockedTask(String packageName) {
        for (int i = 0; i < mLockTaskModeTasks.size(); i++) {
            if (packageName.equals(mLockTaskModeTasks.get(i).getBasePackageName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the cached value of LockTask feature flags for a specific user.
     */
    private int getLockTaskFeaturesForUser(int userId) {
        return mLockTaskFeatures.get(userId, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
    }

    // Should only be called on the handler thread
    @Nullable
    private IStatusBarService getStatusBarService() {
        if (mStatusBarService == null) {
            mStatusBarService = IStatusBarService.Stub.asInterface(
                    ServiceManager.checkService(STATUS_BAR_SERVICE));
            if (mStatusBarService == null) {
                Slog.w("StatusBarManager", "warning: no STATUS_BAR_SERVICE");
            }
        }
        return mStatusBarService;
    }

    // Should only be called on the handler thread
    @Nullable
    private IDevicePolicyManager getDevicePolicyManager() {
        if (mDevicePolicyManager == null) {
            mDevicePolicyManager = IDevicePolicyManager.Stub.asInterface(
                    ServiceManager.checkService(DEVICE_POLICY_SERVICE));
            if (mDevicePolicyManager == null) {
                Slog.w(TAG, "warning: no DEVICE_POLICY_SERVICE");
            }
        }
        return mDevicePolicyManager;
    }

    @NonNull
    private LockPatternUtils getLockPatternUtils() {
        if (mLockPatternUtils == null) {
            // We don't preserve the LPU object to save memory
            return new LockPatternUtils(mContext);
        }
        return mLockPatternUtils;
    }

    @Nullable
    private TelecomManager getTelecomManager() {
        if (mTelecomManager == null) {
            // We don't preserve the TelecomManager object to save memory
            return mContext.getSystemService(TelecomManager.class);
        }
        return mTelecomManager;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "LockTaskController:");
        prefix = prefix + "  ";
        pw.println(prefix + "mLockTaskModeState=" + lockTaskModeToString());
        pw.println(prefix + "mLockTaskModeTasks=");
        for (int i = 0; i < mLockTaskModeTasks.size(); ++i) {
            pw.println(prefix + "  #" + i + " " + mLockTaskModeTasks.get(i));
        }
        pw.println(prefix + "mLockTaskPackages (userId:packages)=");
        for (int i = 0; i < mLockTaskPackages.size(); ++i) {
            pw.println(prefix + "  u" + mLockTaskPackages.keyAt(i)
                    + ":" + Arrays.toString(mLockTaskPackages.valueAt(i)));
        }
        pw.println();
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

    /** Marker class for the token used to disable keyguard. */
    static class LockTaskToken extends Binder {
        private LockTaskToken() {
        }
    }
}
