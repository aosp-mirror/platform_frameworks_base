/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;

import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ITaskOrganizerController;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.view.ITaskOrganizer;
import android.view.IWindowContainer;
import android.view.WindowContainerTransaction;

import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores the TaskOrganizers associated with a given windowing mode and
 * their associated state.
 */
class TaskOrganizerController extends ITaskOrganizerController.Stub {
    private static final String TAG = "TaskOrganizerController";

    /** Flag indicating that an applied transaction may have effected lifecycle */
    private static final int TRANSACT_EFFECTS_CLIENT_CONFIG = 1;
    private static final int TRANSACT_EFFECTS_LIFECYCLE = 1 << 1;

    private final WindowManagerGlobalLock mGlobalLock;

    private class DeathRecipient implements IBinder.DeathRecipient {
        int mWindowingMode;
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer, int windowingMode) {
            mTaskOrganizer = organizer;
            mWindowingMode = windowingMode;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(mTaskOrganizer);
                for (int i = 0; i < state.mOrganizedTasks.size(); i++) {
                    state.mOrganizedTasks.get(i).taskOrganizerDied();
                }
                mTaskOrganizerStates.remove(mTaskOrganizer);
                if (mTaskOrganizersForWindowingMode.get(mWindowingMode) == mTaskOrganizer) {
                    mTaskOrganizersForWindowingMode.remove(mWindowingMode);
                }
            }
        }
    };

    class TaskOrganizerState {
        ITaskOrganizer mOrganizer;
        DeathRecipient mDeathRecipient;

        ArrayList<Task> mOrganizedTasks = new ArrayList<>();

        void addTask(Task t) {
            mOrganizedTasks.add(t);
        }

        void removeTask(Task t) {
            mOrganizedTasks.remove(t);
        }

        TaskOrganizerState(ITaskOrganizer organizer, DeathRecipient deathRecipient) {
            mOrganizer = organizer;
            mDeathRecipient = deathRecipient;
        }
    };


    final HashMap<Integer, TaskOrganizerState> mTaskOrganizersForWindowingMode = new HashMap();
    final HashMap<ITaskOrganizer, TaskOrganizerState> mTaskOrganizerStates = new HashMap();

    final HashMap<Integer, ITaskOrganizer> mTaskOrganizersByPendingSyncId = new HashMap();

    private final WeakHashMap<Task, RunningTaskInfo> mLastSentTaskInfos = new WeakHashMap<>();
    private final ArrayList<Task> mPendingTaskInfoChanges = new ArrayList<>();

    final ActivityTaskManagerService mService;

    RunningTaskInfo mTmpTaskInfo;

    TaskOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    private void enforceStackPermission(String func) {
        mService.mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, func);
    }

    private void clearIfNeeded(int windowingMode) {
        final TaskOrganizerState oldState = mTaskOrganizersForWindowingMode.get(windowingMode);
        if (oldState != null) {
            oldState.mOrganizer.asBinder().unlinkToDeath(oldState.mDeathRecipient, 0);
        }
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
     * If there was already a TaskOrganizer for this windowing mode it will be evicted
     * and receive taskVanished callbacks in the process.
     */
    @Override
    public void registerTaskOrganizer(ITaskOrganizer organizer, int windowingMode) {
        if (windowingMode != WINDOWING_MODE_PINNED
                && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                && windowingMode != WINDOWING_MODE_MULTI_WINDOW) {
            throw new UnsupportedOperationException("As of now only Pinned/Split/Multiwindow"
                    + " windowing modes are supported for registerTaskOrganizer");
        }
        enforceStackPermission("registerTaskOrganizer()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                clearIfNeeded(windowingMode);
                DeathRecipient dr = new DeathRecipient(organizer, windowingMode);
                try {
                    organizer.asBinder().linkToDeath(dr, 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "TaskOrganizer failed to register death recipient");
                }

                final TaskOrganizerState state = new TaskOrganizerState(organizer, dr);
                mTaskOrganizersForWindowingMode.put(windowingMode, state);

                mTaskOrganizerStates.put(organizer, state);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    ITaskOrganizer getTaskOrganizer(int windowingMode) {
        final TaskOrganizerState state = mTaskOrganizersForWindowingMode.get(windowingMode);
        if (state == null) {
            return null;
        }
        return state.mOrganizer;
    }

    private void sendTaskAppeared(ITaskOrganizer organizer, Task task) {
        try {
            organizer.taskAppeared(task.getTaskInfo());
        } catch (Exception e) {
            Slog.e(TAG, "Exception sending taskAppeared callback" + e);
        }
    }

    private void sendTaskVanished(ITaskOrganizer organizer, Task task) {
        try {
            organizer.taskVanished(task.getRemoteToken());
        } catch (Exception e) {
            Slog.e(TAG, "Exception sending taskVanished callback" + e);
        }
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        TaskOrganizerState state = mTaskOrganizerStates.get(organizer);

        state.addTask(task);
        sendTaskAppeared(organizer, task);
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer);
        sendTaskVanished(organizer, task);

        // This could trigger TaskAppeared for other tasks in the same stack so make sure
        // we do this AFTER sending taskVanished.
        state.removeTask(task);
    }

    @Override
    public RunningTaskInfo createRootTask(int displayId, int windowingMode) {
        enforceStackPermission("createRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    return null;
                }
                final int nextId = display.getNextStackId();
                TaskTile tile = new TaskTile(mService, nextId, windowingMode);
                display.addTile(tile);
                RunningTaskInfo out = new RunningTaskInfo();
                tile.fillTaskInfo(out);
                mLastSentTaskInfos.put(tile, out);
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean deleteRootTask(IWindowContainer token) {
        enforceStackPermission("deleteRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                TaskTile tile = TaskTile.forToken(token.asBinder());
                if (tile == null) {
                    return false;
                }
                tile.removeImmediately();
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void dispatchPendingTaskInfoChanges() {
        if (mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            return;
        }
        for (int i = 0, n = mPendingTaskInfoChanges.size(); i < n; ++i) {
            dispatchTaskInfoChanged(mPendingTaskInfoChanges.get(i), false /* force */);
        }
        mPendingTaskInfoChanges.clear();
    }

    void dispatchTaskInfoChanged(Task task, boolean force) {
        if (!force && mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            // Defer task info reporting while layout is deferred. This is because layout defer
            // blocks tend to do lots of re-ordering which can mess up animations in receivers.
            mPendingTaskInfoChanges.remove(task);
            mPendingTaskInfoChanges.add(task);
            return;
        }
        RunningTaskInfo lastInfo = mLastSentTaskInfos.get(task);
        if (mTmpTaskInfo == null) {
            mTmpTaskInfo = new RunningTaskInfo();
        }
        task.fillTaskInfo(mTmpTaskInfo);
        boolean changed = lastInfo == null
                || mTmpTaskInfo.topActivityType != lastInfo.topActivityType
                || mTmpTaskInfo.isResizable() != lastInfo.isResizable();
        if (!(changed || force)) {
            return;
        }
        final RunningTaskInfo newInfo = mTmpTaskInfo;
        mLastSentTaskInfos.put(task, mTmpTaskInfo);
        // Since we've stored this, clean up the reference so a new one will be created next time.
        // Transferring it this way means we only have to construct new RunningTaskInfos when they
        // change.
        mTmpTaskInfo = null;

        if (task.mTaskOrganizer != null) {
            try {
                task.mTaskOrganizer.onTaskInfoChanged(newInfo);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public IWindowContainer getImeTarget(int displayId) {
        enforceStackPermission("getImeTarget()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent dc = mService.mWindowManager.mRoot
                        .getDisplayContent(displayId);
                if (dc == null || dc.mInputMethodTarget == null) {
                    return null;
                }
                // Avoid WindowState#getRootTask() so we don't attribute system windows to a task.
                final Task task = dc.mInputMethodTarget.getTask();
                if (task == null) {
                    return null;
                }
                ActivityStack rootTask = (ActivityStack) task.getRootTask();
                final TaskTile tile = rootTask.getTile();
                if (tile != null) {
                    rootTask = tile;
                }
                return rootTask.mRemoteToken;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setLaunchRoot(int displayId, @Nullable IWindowContainer tile) {
        enforceStackPermission("setLaunchRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    return;
                }
                TaskTile taskTile = tile == null ? null : TaskTile.forToken(tile.asBinder());
                if (taskTile == null) {
                    display.mLaunchTile = null;
                    return;
                }
                if (taskTile.getDisplay() != display) {
                    throw new RuntimeException("Can't set launch root for display " + displayId
                            + " to task on display " + taskTile.getDisplay().getDisplayId());
                }
                display.mLaunchTile = taskTile;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private int sanitizeAndApplyChange(WindowContainer container,
            WindowContainerTransaction.Change change) {
        if (!(container instanceof Task)) {
            throw new RuntimeException("Invalid token in task transaction");
        }
        // The "client"-facing API should prevent bad changes; however, just in case, sanitize
        // masks here.
        int configMask = change.getConfigSetMask();
        int windowMask = change.getWindowSetMask();
        configMask &= ActivityInfo.CONFIG_WINDOW_CONFIGURATION
                | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        windowMask &= WindowConfiguration.WINDOW_CONFIG_BOUNDS;
        int effects = 0;
        if (configMask != 0) {
            Configuration c = new Configuration(container.getRequestedOverrideConfiguration());
            c.setTo(change.getConfiguration(), configMask, windowMask);
            container.onRequestedOverrideConfigurationChanged(c);
            // TODO(b/145675353): remove the following once we could apply new bounds to the
            // pinned stack together with its children.
            resizePinnedStackIfNeeded(container, configMask, windowMask, c);
            effects |= TRANSACT_EFFECTS_CLIENT_CONFIG;
        }
        if ((change.getChangeMask() & WindowContainerTransaction.Change.CHANGE_FOCUSABLE) != 0) {
            if (container.setFocusable(change.getFocusable())) {
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }
        return effects;
    }

    private void resizePinnedStackIfNeeded(ConfigurationContainer container, int configMask,
            int windowMask, Configuration config) {
        if ((container instanceof ActivityStack)
                && ((configMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0)
                && ((windowMask & WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)) {
            final ActivityStack stack = (ActivityStack) container;
            if (stack.inPinnedWindowingMode()) {
                stack.resize(config.windowConfiguration.getBounds(),
                        null /* tempTaskBounds */, null /* tempTaskInsetBounds */,
                        PRESERVE_WINDOWS, true /* deferResume */);
            }
        }
    }

    private int applyWindowContainerChange(WindowContainer wc,
            WindowContainerTransaction.Change c) {
        int effects = sanitizeAndApplyChange(wc, c);

        Rect enterPipBounds = c.getEnterPipBounds();
        if (enterPipBounds != null) {
            Task tr = (Task) wc;
            mService.mStackSupervisor.updatePictureInPictureMode(tr,
                    enterPipBounds, true);
        }
        return effects;
    }

    @Override
    public void applyContainerTransaction(WindowContainerTransaction t) {
        enforceStackPermission("applyContainerTransaction()");
        if (t == null) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                int effects = 0;
                mService.deferWindowLayout();
                try {
                    ArraySet<WindowContainer> haveConfigChanges = new ArraySet<>();
                    Iterator<Map.Entry<IBinder, WindowContainerTransaction.Change>> entries =
                            t.getChanges().entrySet().iterator();
                    while (entries.hasNext()) {
                        final Map.Entry<IBinder, WindowContainerTransaction.Change> entry =
                                entries.next();
                        final WindowContainer wc = WindowContainer.RemoteToken.fromBinder(
                                entry.getKey()).getContainer();
                        int containerEffect = applyWindowContainerChange(wc, entry.getValue());
                        effects |= containerEffect;
                        // Lifecycle changes will trigger ensureConfig for everything.
                        if ((effects & TRANSACT_EFFECTS_LIFECYCLE) == 0
                                && (containerEffect & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                            haveConfigChanges.add(wc);
                        }
                    }
                    if ((effects & TRANSACT_EFFECTS_LIFECYCLE) != 0) {
                        // Already calls ensureActivityConfig
                        mService.mRootWindowContainer.ensureActivitiesVisible(
                                null, 0, PRESERVE_WINDOWS);
                    } else if ((effects & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                        final PooledConsumer f = PooledLambda.obtainConsumer(
                                ActivityRecord::ensureActivityConfiguration,
                                PooledLambda.__(ActivityRecord.class), 0,
                                false /* preserveWindow */);
                        try {
                            for (int i = haveConfigChanges.size() - 1; i >= 0; --i) {
                                haveConfigChanges.valueAt(i).forAllActivities(f);
                            }
                        } finally {
                            f.recycle();
                        }
                    }
                } finally {
                    mService.continueWindowLayout();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
