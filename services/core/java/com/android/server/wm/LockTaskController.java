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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Context.DEVICE_POLICY_SERVICE;
import static android.content.Context.STATUS_BAR_SERVICE;
import static android.content.Intent.ACTION_CALL_EMERGENCY;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;
import static android.telecom.TelecomManager.EMERGENCY_DIALER_COMPONENT;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_PINNABLE;
import static com.android.server.wm.TaskRecord.LOCK_TASK_AUTH_WHITELISTED;

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
import com.android.internal.statusbar.IStatusBarService;
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

    private final IBinder mToken = new LockTaskToken();
    private final ActivityStackSupervisor mSupervisor;
    private final Context mContext;

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
     * of the stack by {@link ActivityStack#moveTaskToBackLocked(int)};
     *
     * Calling {@link Activity#stopLockTask()} on the root task will finish all tasks but itself in
     * this list, and the device will exit LockTask mode.
     *
     * The list is empty if LockTask is inactive.
     */
    private final ArrayList<TaskRecord> mLockTaskModeTasks = new ArrayList<>();

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
    private int mLockTaskModeState = LOCK_TASK_MODE_NONE;

    /**
     * This is ActivityStackSupervisor's Handler.
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

    LockTaskController(Context context, ActivityStackSupervisor supervisor,
            Handler handler) {
        mContext = context;
        mSupervisor = supervisor;
        mHandler = handler;
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
    boolean isTaskLocked(TaskRecord task) {
        return mLockTaskModeTasks.contains(task);
    }

    /**
     * @return {@code true} whether this task first started the current LockTask session.
     */
    private boolean isRootTask(TaskRecord task) {
        return mLockTaskModeTasks.indexOf(task) == 0;
    }

    /**
     * @return whether the given activity is blocked from finishing, because it is the only activity
     * of the last locked task and finishing it would mean that lock task mode is ended illegally.
     */
    boolean activityBlockedFromFinish(ActivityRecord activity) {
        final TaskRecord task = activity.getTaskRecord();
        if (activity == task.getRootActivity()
                && activity == task.getTopActivity()
                && task.mLockTaskAuth != LOCK_TASK_AUTH_LAUNCHABLE_PRIV
                && isRootTask(task)) {
            Slog.i(TAG, "Not finishing task in lock task mode");
            showLockTaskToast();
            return true;
        }
        return false;
    }

    /**
     * @return whether the given task can be moved to the back of the stack with
     * {@link ActivityStack#moveTaskToBackLocked(int)}
     * @see #mLockTaskModeTasks
     */
    boolean canMoveTaskToBack(TaskRecord task) {
        if (isRootTask(task)) {
            showLockTaskToast();
            return false;
        }
        return true;
    }

    /**
     * @return whether the requested task is allowed to be locked (either whitelisted, or declares
     * lockTaskMode="always" in the manifest).
     */
    boolean isTaskWhitelisted(TaskRecord task) {
        switch(task.mLockTaskAuth) {
            case LOCK_TASK_AUTH_WHITELISTED:
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
    boolean isLockTaskModeViolation(TaskRecord task) {
        return isLockTaskModeViolation(task, false);
    }

    /**
     * @param isNewClearTask whether the task would be cleared as part of the operation.
     * @return whether the requested task is disallowed to be launched.
     */
    boolean isLockTaskModeViolation(TaskRecord task, boolean isNewClearTask) {
        if (isLockTaskModeViolationInternal(task, isNewClearTask)) {
            showLockTaskToast();
            return true;
        }
        return false;
    }

    /**
     * @return the root task of the lock task.
     */
    TaskRecord getRootTask() {
        if (mLockTaskModeTasks.isEmpty()) {
            return null;
        }
        return mLockTaskModeTasks.get(0);
    }

    private boolean isLockTaskModeViolationInternal(TaskRecord task, boolean isNewClearTask) {
        // TODO: Double check what's going on here. If the task is already in lock task mode, it's
        // likely whitelisted, so will return false below.
        if (isTaskLocked(task) && !isNewClearTask) {
            // If the task is already at the top and won't be cleared, then allow the operation
            return false;
        }

        // Allow recents activity if enabled by policy
        if (task.isActivityTypeRecents() && isRecentsAllowed(task.userId)) {
            return false;
        }

        // Allow emergency calling when the device is protected by a locked keyguard
        if (isKeyguardAllowed(task.userId) && isEmergencyCallTask(task)) {
            return false;
        }

        return !(isTaskWhitelisted(task) || mLockTaskModeTasks.isEmpty());
    }

    private boolean isRecentsAllowed(int userId) {
        return (getLockTaskFeaturesForUser(userId)
                & DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW) != 0;
    }

    private boolean isKeyguardAllowed(int userId) {
        return (getLockTaskFeaturesForUser(userId)
                & DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD) != 0;
    }

    private boolean isEmergencyCallTask(TaskRecord task) {
        final Intent intent = task.intent;
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
     * @param isSystemCaller indicates whether this request comes from the system via
     *                       {@link ActivityTaskManagerService#stopSystemLockTaskMode()}. If
     *                       {@code true}, it means the user intends to stop pinned mode through UI;
     *                       otherwise, it's called by an app and we need to stop locked or pinned
     *                       mode, subject to checks.
     * @param callingUid the caller that requested the end of lock task mode.
     * @throws IllegalArgumentException if the calling task is invalid (e.g., {@code null} or not in
     *                                  foreground)
     * @throws SecurityException if the caller is not authorized to stop the lock task mode, i.e. if
     *                           they differ from the one that launched lock task mode.
     */
    void stopLockTaskMode(@Nullable TaskRecord task, boolean isSystemCaller, int callingUid) {
        if (mLockTaskModeState == LOCK_TASK_MODE_NONE) {
            return;
        }

        if (isSystemCaller) {
            if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                clearLockedTasks("stopAppPinning");
            } else {
                Slog.e(TAG_LOCKTASK, "Attempted to stop LockTask with isSystemCaller=true");
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
            // {@link TaskRecord.mLockTaskUid} will be 0, so we compare the callingUid to the
            // {@link TaskRecord.effectiveUid} instead. Also caller with
            // {@link MANAGE_ACTIVITY_STACKS} can stop any lock task.
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
     * unlike {@link #stopLockTaskMode(TaskRecord, boolean, int)}, it doesn't perform the checks.
     */
    void clearLockedTasks(String reason) {
        if (DEBUG_LOCKTASK) Slog.i(TAG_LOCKTASK, "clearLockedTasks: " + reason);
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
    void clearLockedTask(final TaskRecord task) {
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
        task.performClearTaskLocked();
        mSupervisor.mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    /**
     * Remove the given task from the locked task list. If this was the last task in the list,
     * lock task mode is stopped.
     */
    private void removeLockedTask(final TaskRecord task) {
        if (!mLockTaskModeTasks.remove(task)) {
            return;
        }
        if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "removeLockedTask: removed " + task);
        if (mLockTaskModeTasks.isEmpty()) {
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "removeLockedTask: task=" + task +
                    " last task, reverting locktask mode. Callers=" + Debug.getCallers(3));
            mHandler.post(() -> performStopLockTask(task.userId));
        }
    }

    // This method should only be called on the handler thread
    private void performStopLockTask(int userId) {
        // When lock task ends, we enable the status bars.
        try {
            setStatusBarState(LOCK_TASK_MODE_NONE, userId);
            setKeyguardState(LOCK_TASK_MODE_NONE, userId);
            if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                lockKeyguardIfNeeded();
            }
            if (getDevicePolicyManager() != null) {
                getDevicePolicyManager().notifyLockTaskModeChanged(false, null, userId);
            }
            if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                getStatusBarService().showPinningEnterExitToast(false /* entering */);
            }
            mWindowManager.onLockTaskStateChanged(LOCK_TASK_MODE_NONE);
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        } finally {
            mLockTaskModeState = LOCK_TASK_MODE_NONE;
        }
    }

    /**
     * Show the lock task violation toast. Currently we only show toast for screen pinning mode, and
     * no-op if the device is in locked mode.
     */
    void showLockTaskToast() {
        if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
            try {
                getStatusBarService().showPinningEscapeToast();
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
    void startLockTaskMode(@NonNull TaskRecord task, boolean isSystemCaller, int callingUid) {
        if (!isSystemCaller) {
            task.mLockTaskUid = callingUid;
            if (task.mLockTaskAuth == LOCK_TASK_AUTH_PINNABLE) {
                // startLockTask() called by app, but app is not part of lock task whitelist. Show
                // app pinning request. We will come back here with isSystemCaller true.
                if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "Mode default, asking user");
                StatusBarManagerInternal statusBarManager = LocalServices.getService(
                        StatusBarManagerInternal.class);
                if (statusBarManager != null) {
                    statusBarManager.showScreenPinningRequest(task.taskId);
                }
                return;
            }
        }

        // System can only initiate screen pinning, not full lock task mode
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                isSystemCaller ? "Locking pinned" : "Locking fully");
        setLockTaskMode(task, isSystemCaller ? LOCK_TASK_MODE_PINNED : LOCK_TASK_MODE_LOCKED,
                "startLockTask", true);
    }

    /**
     * Start lock task mode on the given task.
     * @param lockTaskModeState whether fully locked or pinned mode.
     * @param andResume whether the task should be brought to foreground as part of the operation.
     */
    private void setLockTaskMode(@NonNull TaskRecord task, int lockTaskModeState,
                                 String reason, boolean andResume) {
        // Should have already been checked, but do it again.
        if (task.mLockTaskAuth == LOCK_TASK_AUTH_DONT_LOCK) {
            if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                    "setLockTaskMode: Can't lock due to auth");
            return;
        }
        if (isLockTaskModeViolation(task)) {
            Slog.e(TAG_LOCKTASK, "setLockTaskMode: Attempt to start an unauthorized lock task.");
            return;
        }

        final Intent taskIntent = task.intent;
        if (mLockTaskModeTasks.isEmpty() && taskIntent != null) {
            mSupervisor.mRecentTasks.onLockTaskModeStateChanged(lockTaskModeState, task.userId);
            // Start lock task on the handler thread
            mHandler.post(() -> performStartLockTask(
                    taskIntent.getComponent().getPackageName(),
                    task.userId,
                    lockTaskModeState));
        }
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "setLockTaskMode: Locking to " + task +
                " Callers=" + Debug.getCallers(4));

        if (!mLockTaskModeTasks.contains(task)) {
            mLockTaskModeTasks.add(task);
        }

        if (task.mLockTaskUid == -1) {
            task.mLockTaskUid = task.effectiveUid;
        }

        if (andResume) {
            mSupervisor.findTaskToMoveToFront(task, 0, null, reason,
                    lockTaskModeState != LOCK_TASK_MODE_NONE);
            mSupervisor.mRootActivityContainer.resumeFocusedStacksTopActivities();
            final ActivityStack stack = task.getStack();
            if (stack != null) {
                stack.getDisplay().mDisplayContent.executeAppTransition();
            }
        } else if (lockTaskModeState != LOCK_TASK_MODE_NONE) {
            mSupervisor.handleNonResizableTaskIfNeeded(task, WINDOWING_MODE_UNDEFINED,
                    DEFAULT_DISPLAY, task.getStack(), true /* forceNonResizable */);
        }
    }

    // This method should only be called on the handler thread
    private void performStartLockTask(String packageName, int userId, int lockTaskModeState) {
        // When lock task starts, we disable the status bars.
        try {
            if (lockTaskModeState == LOCK_TASK_MODE_PINNED) {
                getStatusBarService().showPinningEnterExitToast(true /* entering */);
            }
            mWindowManager.onLockTaskStateChanged(lockTaskModeState);
            mLockTaskModeState = lockTaskModeState;
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
     * @param userId Which user this whitelist is associated with
     * @param packages The whitelist of packages allowed in lock task mode
     * @see #mLockTaskPackages
     */
    void updateLockTaskPackages(int userId, String[] packages) {
        mLockTaskPackages.put(userId, packages);

        boolean taskChanged = false;
        for (int taskNdx = mLockTaskModeTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord lockedTask = mLockTaskModeTasks.get(taskNdx);
            final boolean wasWhitelisted = lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE
                    || lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_WHITELISTED;
            lockedTask.setLockTaskAuth();
            final boolean isWhitelisted = lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE
                    || lockedTask.mLockTaskAuth == LOCK_TASK_AUTH_WHITELISTED;

            if (mLockTaskModeState != LOCK_TASK_MODE_LOCKED
                    || lockedTask.userId != userId
                    || !wasWhitelisted || isWhitelisted) {
                continue;
            }

            // Terminate locked tasks that have recently lost whitelist authorization.
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "onLockTaskPackagesUpdated: removing " +
                    lockedTask + " mLockTaskAuth()=" + lockedTask.lockTaskAuthToString());
            removeLockedTask(lockedTask);
            lockedTask.performClearTaskLocked();
            taskChanged = true;
        }

        for (int displayNdx = mSupervisor.mRootActivityContainer.getChildCount() - 1;
             displayNdx >= 0; --displayNdx) {
            mSupervisor.mRootActivityContainer.getChildAt(displayNdx).onLockTaskPackagesUpdated();
        }

        final ActivityRecord r = mSupervisor.mRootActivityContainer.topRunningActivity();
        final TaskRecord task = (r != null) ? r.getTaskRecord() : null;
        if (mLockTaskModeTasks.isEmpty() && task!= null
                && task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE) {
            // This task must have just been authorized.
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK,
                    "onLockTaskPackagesUpdated: starting new locktask task=" + task);
            setLockTaskMode(task, LOCK_TASK_MODE_LOCKED, "package updated", false);
            taskChanged = true;
        }

        if (taskChanged) {
            mSupervisor.mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    boolean isPackageWhitelisted(int userId, String pkg) {
        if (pkg == null) {
            return false;
        }
        String[] whitelist;
        whitelist = mLockTaskPackages.get(userId);
        if (whitelist == null) {
            return false;
        }
        for (String whitelistedPkg : whitelist) {
            if (pkg.equals(whitelistedPkg)) {
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
        if (!mLockTaskModeTasks.isEmpty() && userId == mLockTaskModeTasks.get(0).userId) {
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
    private void lockKeyguardIfNeeded() {
        try {
            boolean shouldLockKeyguard = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_TO_APP_EXIT_LOCKED,
                    USER_CURRENT) != 0;
            if (shouldLockKeyguard) {
                mWindowManager.lockNow(null);
                mWindowManager.dismissKeyguard(null /* callback */, null /* message */);
                getLockPatternUtils().requireCredentialEntry(USER_ALL);
            }
        } catch (Settings.SettingNotFoundException e) {
            // No setting, don't lock.
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
        pw.println(prefix + "LockTaskController");
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
