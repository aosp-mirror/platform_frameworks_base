/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;
import static android.app.ActivityTaskManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ANIMATE;
import static com.android.server.am.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.am.TaskRecord.REPARENT_KEEP_STACK_AT_FRONT;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Rect;
import android.os.Binder;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * System service for managing activities and their containers (task, stacks, displays,... ).
 *
 * {@hide}
 */
public class ActivityTaskManagerService extends IActivityTaskManager.Stub {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityTaskManagerService" : TAG_AM;
    private static final String TAG_STACK = TAG + POSTFIX_STACK;

    private Context mContext;
    private ActivityManagerService mAm;
    /* Global service lock used by the package the owns this service. */
    Object mGlobalLock;
    private ActivityStackSupervisor mStackSupervisor;

    ActivityTaskManagerService(Context context) {
        mContext = context;
    }

    // TODO: Will be converted to WM lock once transition is complete.
    void setActivityManagerService(ActivityManagerService am) {
        mAm = am;
        mGlobalLock = mAm;
        mStackSupervisor = mAm.mStackSupervisor;
    }

    public static final class Lifecycle extends SystemService {
        private final ActivityTaskManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new ActivityTaskManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.ACTIVITY_TASK_SERVICE, mService);
        }

        public ActivityTaskManagerService getService() {
            return mService;
        }
    }

    @Override
    public void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) {
        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            setTaskWindowingModeSplitScreenPrimary(taskId, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT,
                    toTop, ANIMATE, null /* initialBounds */, true /* showRecents */);
            return;
        }
        mAm.enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "setTaskWindowingMode()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mStackSupervisor.anyTaskForIdLocked(taskId);
                if (task == null) {
                    Slog.w(TAG, "setTaskWindowingMode: No task for id=" + taskId);
                    return;
                }

                if (DEBUG_STACK) Slog.d(TAG_STACK, "setTaskWindowingMode: moving task=" + taskId
                        + " to windowingMode=" + windowingMode + " toTop=" + toTop);

                if (!task.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("setTaskWindowingMode: Attempt to move"
                            + " non-standard task " + taskId + " to windowing mode="
                            + windowingMode);
                }

                final ActivityStack stack = task.getStack();
                if (toTop) {
                    stack.moveToFront("setTaskWindowingMode", task);
                }
                stack.setWindowingMode(windowingMode);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return getFilteredTasks(maxNum, ACTIVITY_TYPE_UNDEFINED, WINDOWING_MODE_UNDEFINED);
    }

    @Override
    public List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum,
            @WindowConfiguration.ActivityType int ignoreActivityType,
            @WindowConfiguration.WindowingMode int ignoreWindowingMode) {
        final int callingUid = Binder.getCallingUid();
        ArrayList<ActivityManager.RunningTaskInfo> list = new ArrayList<>();

        synchronized (mGlobalLock) {
            if (DEBUG_ALL) Slog.v(TAG, "getTasks: max=" + maxNum);

            final boolean allowed = mAm.isGetTasksAllowed("getTasks", Binder.getCallingPid(),
                    callingUid);
            mStackSupervisor.getRunningTasks(maxNum, list, ignoreActivityType,
                    ignoreWindowingMode, callingUid, allowed);
        }

        return list;
    }

    @Override
    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        mAm.enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "moveTaskToStack()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mStackSupervisor.anyTaskForIdLocked(taskId);
                if (task == null) {
                    Slog.w(TAG, "moveTaskToStack: No task for id=" + taskId);
                    return;
                }

                if (DEBUG_STACK) Slog.d(TAG_STACK, "moveTaskToStack: moving task=" + taskId
                        + " to stackId=" + stackId + " toTop=" + toTop);

                final ActivityStack stack = mStackSupervisor.getStack(stackId);
                if (stack == null) {
                    throw new IllegalStateException(
                            "moveTaskToStack: No stack for stackId=" + stackId);
                }
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("moveTaskToStack: Attempt to move task "
                            + taskId + " to stack " + stackId);
                }
                if (stack.inSplitScreenPrimaryWindowingMode()) {
                    mAm.mWindowManager.setDockedStackCreateState(
                            SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, null /* initialBounds */);
                }
                task.reparent(stack, toTop, REPARENT_KEEP_STACK_AT_FRONT, ANIMATE, !DEFER_RESUME,
                        "moveTaskToStack");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void resizeStack(int stackId, Rect destBounds, boolean allowResizeInDockedMode,
            boolean preserveWindows, boolean animate, int animationDuration) {
        mAm.enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS, "resizeStack()");

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (animate) {
                    final PinnedActivityStack stack = mStackSupervisor.getStack(stackId);
                    if (stack == null) {
                        Slog.w(TAG, "resizeStack: stackId " + stackId + " not found.");
                        return;
                    }
                    if (stack.getWindowingMode() != WINDOWING_MODE_PINNED) {
                        throw new IllegalArgumentException("Stack: " + stackId
                                + " doesn't support animated resize.");
                    }
                    stack.animateResizePinnedStack(null /* sourceHintBounds */, destBounds,
                            animationDuration, false /* fromFullscreen */);
                } else {
                    final ActivityStack stack = mStackSupervisor.getStack(stackId);
                    if (stack == null) {
                        Slog.w(TAG, "resizeStack: stackId " + stackId + " not found.");
                        return;
                    }
                    mStackSupervisor.resizeStackLocked(stack, destBounds,
                            null /* tempTaskBounds */, null /* tempTaskInsetBounds */,
                            preserveWindows, allowResizeInDockedMode, !DEFER_RESUME);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Moves the specified task to the primary-split-screen stack.
     *
     * @param taskId Id of task to move.
     * @param createMode The mode the primary split screen stack should be created in if it doesn't
     *                   exist already. See
     *                   {@link android.app.ActivityTaskManager#SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT}
     *                   and
     *                   {@link android.app.ActivityTaskManager#SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT}
     * @param toTop If the task and stack should be moved to the top.
     * @param animate Whether we should play an animation for the moving the task.
     * @param initialBounds If the primary stack gets created, it will use these bounds for the
     *                      stack. Pass {@code null} to use default bounds.
     * @param showRecents If the recents activity should be shown on the other side of the task
     *                    going into split-screen mode.
     */
    @Override
    public boolean setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode,
            boolean toTop, boolean animate, Rect initialBounds, boolean showRecents) {
        mAm.enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "setTaskWindowingModeSplitScreenPrimary()");
        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mStackSupervisor.anyTaskForIdLocked(taskId);
                if (task == null) {
                    Slog.w(TAG, "setTaskWindowingModeSplitScreenPrimary: No task for id=" + taskId);
                    return false;
                }
                if (DEBUG_STACK) Slog.d(TAG_STACK,
                        "setTaskWindowingModeSplitScreenPrimary: moving task=" + taskId
                                + " to createMode=" + createMode + " toTop=" + toTop);
                if (!task.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("setTaskWindowingMode: Attempt to move"
                            + " non-standard task " + taskId + " to split-screen windowing mode");
                }

                mAm.mWindowManager.setDockedStackCreateState(createMode, initialBounds);
                final int windowingMode = task.getWindowingMode();
                final ActivityStack stack = task.getStack();
                if (toTop) {
                    stack.moveToFront("setTaskWindowingModeSplitScreenPrimary", task);
                }
                stack.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, animate, showRecents,
                        false /* enteringSplitScreenMode */, false /* deferEnsuringVisibility */);
                return windowingMode != task.getWindowingMode();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    @Override
    public void removeStacksInWindowingModes(int[] windowingModes) {
        mAm.enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "removeStacksInWindowingModes()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mStackSupervisor.removeStacksInWindowingModes(windowingModes);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void removeStacksWithActivityTypes(int[] activityTypes) {
        mAm.enforceCallerIsRecentsOrHasPermission(MANAGE_ACTIVITY_STACKS,
                "removeStacksWithActivityTypes()");

        synchronized (mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mStackSupervisor.removeStacksWithActivityTypes(activityTypes);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
