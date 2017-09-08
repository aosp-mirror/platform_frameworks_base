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

package com.android.server.am;

import static android.app.ActivityManager.LOCK_TASK_MODE_LOCKED;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.StatusBarManager.DISABLE_BACK;
import static android.app.StatusBarManager.DISABLE_HOME;
import static android.app.StatusBarManager.DISABLE_MASK;
import static android.app.StatusBarManager.DISABLE_NONE;
import static android.app.StatusBarManager.DISABLE_RECENT;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Context.DEVICE_POLICY_SERVICE;
import static android.content.Context.STATUS_BAR_SERVICE;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;
import static android.provider.Settings.Secure.LOCK_TO_APP_EXIT_LOCKED;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LOCKTASK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_DONT_LOCK;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_LAUNCHABLE_PRIV;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_PINNABLE;
import static com.android.server.am.TaskRecord.LOCK_TASK_AUTH_WHITELISTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.IDevicePolicyManager;
import android.content.Context;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.WindowManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Helper class that deals with all things related to task locking. This includes the screen pinning
 * mode that can be launched via System UI as well as the fully locked mode that can be achieved
 * on fully managed devices.
 *
 * Note: All methods in this class should only be called with the ActivityManagerService lock held.
 *
 * @see Activity#startLockTask()
 * @see Activity#stopLockTask()
 */
public class LockTaskController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "LockTaskController" : TAG_AM;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;

    @VisibleForTesting
    static final int STATUS_BAR_MASK_LOCKED = DISABLE_MASK
            & (~DISABLE_BACK);
    @VisibleForTesting
    static final int STATUS_BAR_MASK_PINNED = DISABLE_MASK
            & (~DISABLE_BACK)
            & (~DISABLE_HOME)
            & (~DISABLE_RECENT);

    /** Tag used for disabling of keyguard */
    private static final String LOCK_TASK_TAG = "Lock-to-App";

    private final IBinder mToken = new Binder();
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

    /**
     * Helper that is responsible for showing the right toast when a disallowed activity operation
     * occurred. In pinned mode, we show instructions on how to break out of this mode, whilst in
     * fully locked mode we only show that unlocking is blocked.
     */
    @VisibleForTesting
    LockTaskNotify mLockTaskNotify;

    /**
     * The chain of tasks in lockTask mode. The current frontmost task is at the top, and tasks
     * may be finished until there is only one entry left. If this is empty the system is not
     * in lockTask mode.
     */
    private final ArrayList<TaskRecord> mLockTaskModeTasks = new ArrayList<>();

    /**
     * Packages that are allowed to be launched into the lock task mode for each user.
     */
    private final SparseArray<String[]> mLockTaskPackages = new SparseArray<>();

    /**
     * Store the current lock task mode. Possible values:
     * {@link ActivityManager#LOCK_TASK_MODE_NONE}, {@link ActivityManager#LOCK_TASK_MODE_LOCKED},
     * {@link ActivityManager#LOCK_TASK_MODE_PINNED}
     */
    private int mLockTaskModeState;

    /**
     * This is ActivityStackSupervisor's Handler.
     */
    private final Handler mHandler;

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
    boolean checkLockedTask(TaskRecord task) {
        if (mLockTaskModeTasks.contains(task)) {
            showLockTaskToast();
            return true;
        }
        return false;
    }

    /**
     * @return whether the given activity is blocked from finishing, because it is the root activity
     * of the last locked task and finishing it would mean that lock task mode is ended illegally.
     */
    boolean activityBlockedFromFinish(ActivityRecord activity) {
        TaskRecord task = activity.getTask();
        if (activity == task.getRootActivity()
                && task.mLockTaskAuth != LOCK_TASK_AUTH_LAUNCHABLE_PRIV
                && mLockTaskModeTasks.size() == 1
                && mLockTaskModeTasks.contains(task)) {
            Slog.i(TAG, "Not finishing task in lock task mode");
            showLockTaskToast();
            return true;
        }
        return false;
    }

    /**
     * @return whether the requested task is allowed to be launched.
     */
    boolean isLockTaskModeViolation(TaskRecord task) {
        return isLockTaskModeViolation(task, false);
    }

    /**
     * @param isNewClearTask whether the task would be cleared as part of the operation.
     * @return whether the requested task is allowed to be launched.
     */
    boolean isLockTaskModeViolation(TaskRecord task, boolean isNewClearTask) {
        if (isLockTaskModeViolationInternal(task, isNewClearTask)) {
            showLockTaskToast();
            return true;
        }
        return false;
    }

    private boolean isLockTaskModeViolationInternal(TaskRecord task, boolean isNewClearTask) {
        // TODO: Double check what's going on here. If the task is already in lock task mode, it's
        // likely whitelisted, so will return false below.
        if (getLockedTask() == task && !isNewClearTask) {
            // If the task is already at the top and won't be cleared, then allow the operation
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

    /**
     * Stop the current lock task mode.
     *
     * @param isSystemInitiated indicates whether this request was initiated by the system via
     *                          {@link ActivityManagerService#stopSystemLockTaskMode()}.
     * @param callingUid the caller that requested the end of lock task mode.
     * @throws SecurityException if the caller is not authorized to stop the lock task mode, i.e. if
     *                           they differ from the one that launched lock task mode.
     */
    void stopLockTaskMode(boolean isSystemInitiated, int callingUid) {
        final TaskRecord lockTask = getLockedTask();
        if (lockTask == null || mLockTaskModeState == LOCK_TASK_MODE_NONE) {
            // Our work here is done.
            return;
        }

        if (isSystemInitiated && mLockTaskModeState == LOCK_TASK_MODE_LOCKED) {
            // As system can only start app pinning, we also only let it unlock in this mode.
            showLockTaskToast();
            return;
        }

        // Ensure the same caller for startLockTaskMode and stopLockTaskMode.
        // It is possible lockTaskMode was started by the system process because
        // android:lockTaskMode is set to a locking value in the application manifest
        // instead of the app calling startLockTaskMode. In this case
        // {@link TaskRecord.mLockTaskUid} will be 0, so we compare the callingUid to the
        // {@link TaskRecord.effectiveUid} instead. Also caller with
        // {@link MANAGE_ACTIVITY_STACKS} can stop any lock task.
        if (!isSystemInitiated && callingUid != lockTask.mLockTaskUid
                && (lockTask.mLockTaskUid != 0 || callingUid != lockTask.effectiveUid)) {
            throw new SecurityException("Invalid uid, expected " + lockTask.mLockTaskUid
                    + " callingUid=" + callingUid + " effectiveUid=" + lockTask.effectiveUid);
        }

        clearLockTaskMode("stopLockTask");
    }

    /**
     * Remove the given task from the locked task list. If this was the last task in the list,
     * lock task mode is stopped.
     */
    void removeLockedTask(final TaskRecord task) {
        if (!mLockTaskModeTasks.remove(task)) {
            return;
        }
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "removeLockedTask: removed " + task);
        if (mLockTaskModeTasks.isEmpty()) {
            // Last one.
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK, "removeLockedTask: task=" + task +
                    " last task, reverting locktask mode. Callers=" + Debug.getCallers(3));
            mHandler.post(() -> performStopLockTask(task.userId));
        }
    }

    /**
     * Remove the topmost task from the locked task list. If this is the last task in the list, it
     * will result in the end of locked task mode.
     */
    void clearLockTaskMode(String reason) {
        // Take out of lock task mode if necessary
        final TaskRecord lockedTask = getLockedTask();
        if (lockedTask != null) {
            removeLockedTask(lockedTask);
            if (!mLockTaskModeTasks.isEmpty()) {
                // There are locked tasks remaining, can only finish this task, not unlock it.
                if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                        "setLockTaskMode: Tasks remaining, can't unlock");
                lockedTask.performClearTaskLocked();
                mSupervisor.resumeFocusedStackTopActivityLocked();
                return;
            }
        }
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK,
                "setLockTaskMode: No tasks to unlock. Callers=" + Debug.getCallers(4));
    }

    // This method should only be called on the handler thread
    private void performStopLockTask(int userId) {
        // When lock task ends, we enable the status bars.
        try {
            if (getStatusBarService() != null) {
                getStatusBarService().disable(DISABLE_NONE, mToken,
                        mContext.getPackageName());
            }
            mWindowManager.reenableKeyguard(mToken);
            if (getDevicePolicyManager() != null) {
                getDevicePolicyManager().notifyLockTaskModeChanged(false, null, userId);
            }
            getLockTaskNotify().show(false);
            try {
                boolean shouldLockKeyguard = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        LOCK_TO_APP_EXIT_LOCKED,
                        USER_CURRENT) != 0;
                if (mLockTaskModeState == LOCK_TASK_MODE_PINNED && shouldLockKeyguard) {
                    mWindowManager.lockNow(null);
                    mWindowManager.dismissKeyguard(null /* callback */);
                    getLockPatternUtils().requireCredentialEntry(USER_ALL);
                }
            } catch (Settings.SettingNotFoundException e) {
                // No setting, don't lock.
            }
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        } finally {
            mLockTaskModeState = LOCK_TASK_MODE_NONE;
        }
    }

    /**
     * Show the lock task violation toast.
     */
    void showLockTaskToast() {
        mHandler.post(() -> getLockTaskNotify().showToast(mLockTaskModeState));
    }

    // Starting lock task

    /**
     * Method to start lock task mode on a given task.
     *
     * @param task the task that should be locked.
     * @param isSystemInitiated indicates whether this request was initiated by the system via
     *                          {@link ActivityManagerService#startSystemLockTaskMode(int)}.
     * @param callingUid the caller that requested the launch of lock task mode.
     */
    void startLockTaskMode(@NonNull TaskRecord task, boolean isSystemInitiated,
            int callingUid) {
        if (!isSystemInitiated) {
            task.mLockTaskUid = callingUid;
            if (task.mLockTaskAuth == LOCK_TASK_AUTH_PINNABLE) {
                // startLockTask() called by app, but app is not part of lock task whitelist. Show
                // app pinning request. We will come back here with isSystemInitiated true.
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
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, isSystemInitiated ? "Locking pinned" : "Locking fully");
        setLockTaskMode(task, isSystemInitiated ? LOCK_TASK_MODE_PINNED : LOCK_TASK_MODE_LOCKED,
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

        if (mLockTaskModeTasks.isEmpty()) {
            // Start lock task on the handler thread
            mHandler.post(() -> performStartLockTask(
                    task.intent.getComponent().getPackageName(),
                    task.userId,
                    lockTaskModeState));
        }

        // Add it or move it to the top.
        if (DEBUG_LOCKTASK) Slog.w(TAG_LOCKTASK, "setLockTaskMode: Locking to " + task +
                " Callers=" + Debug.getCallers(4));
        mLockTaskModeTasks.remove(task);
        mLockTaskModeTasks.add(task);

        if (task.mLockTaskUid == -1) {
            task.mLockTaskUid = task.effectiveUid;
        }

        if (andResume) {
            mSupervisor.findTaskToMoveToFrontLocked(task, 0, null, reason,
                    lockTaskModeState != LOCK_TASK_MODE_NONE);
            mSupervisor.resumeFocusedStackTopActivityLocked();
            mWindowManager.executeAppTransition();
        } else if (lockTaskModeState != LOCK_TASK_MODE_NONE) {
            mSupervisor.handleNonResizableTaskIfNeeded(task, WINDOWING_MODE_UNDEFINED,
                    DEFAULT_DISPLAY, task.getStackId(), true /* forceNonResizable */);
        }
    }

    // This method should only be called on the handler thread
    private void performStartLockTask(String packageName, int userId, int lockTaskModeState) {
        // When lock task starts, we disable the status bars.
        try {
            getLockTaskNotify().show(true);
            mLockTaskModeState = lockTaskModeState;
            if (getStatusBarService() != null) {
                int flags = 0;
                if (mLockTaskModeState == LOCK_TASK_MODE_LOCKED) {
                    flags = STATUS_BAR_MASK_LOCKED;
                } else if (mLockTaskModeState == LOCK_TASK_MODE_PINNED) {
                    flags = STATUS_BAR_MASK_PINNED;
                }
                getStatusBarService().disable(flags, mToken, mContext.getPackageName());
            }
            mWindowManager.disableKeyguard(mToken, LOCK_TASK_TAG);
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

        for (int displayNdx = mSupervisor.getChildCount() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mSupervisor.getChildAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                stack.onLockTaskPackagesUpdatedLocked();
            }
        }

        final ActivityRecord r = mSupervisor.topRunningActivityLocked();
        final TaskRecord task = (r != null) ? r.getTask() : null;
        if (mLockTaskModeTasks.isEmpty() && task!= null
                && task.mLockTaskAuth == LOCK_TASK_AUTH_LAUNCHABLE) {
            // This task must have just been authorized.
            if (DEBUG_LOCKTASK) Slog.d(TAG_LOCKTASK,
                    "onLockTaskPackagesUpdated: starting new locktask task=" + task);
            setLockTaskMode(task, LOCK_TASK_MODE_LOCKED, "package updated", false);
            taskChanged = true;
        }

        if (taskChanged) {
            mSupervisor.resumeFocusedStackTopActivityLocked();
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
     * @return the topmost locked task
     */
    private TaskRecord getLockedTask() {
        final int top = mLockTaskModeTasks.size() - 1;
        if (top >= 0) {
            return mLockTaskModeTasks.get(top);
        }
        return null;
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

    // Should only be called on the handler thread
    @NonNull
    private LockTaskNotify getLockTaskNotify() {
        if (mLockTaskNotify == null) {
            mLockTaskNotify = new LockTaskNotify(mContext);
        }
        return mLockTaskNotify;
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
}
