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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.window.StartingWindowRemovalInfo.DEFER_MODE_NONE;
import static android.window.StartingWindowRemovalInfo.DEFER_MODE_NORMAL;
import static android.window.StartingWindowRemovalInfo.DEFER_MODE_ROTATION;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.ActivityTaskManagerService.enforceTaskPermission;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_STARTING_REVEAL;
import static com.android.server.wm.WindowOrganizerController.configurationsAreEqualForOrganizer;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.ITaskOrganizerController;
import android.window.IWindowlessStartingSurfaceCallback;
import android.window.SplashScreenView;
import android.window.StartingWindowInfo;
import android.window.StartingWindowRemovalInfo;
import android.window.TaskAppearedInfo;
import android.window.TaskSnapshot;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Stores the TaskOrganizers associated with a given windowing mode and
 * their associated state.
 */
class TaskOrganizerController extends ITaskOrganizerController.Stub {
    private static final String TAG = "TaskOrganizerController";

    @VisibleForTesting
    class DeathRecipient implements IBinder.DeathRecipient {
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer) {
            mTaskOrganizer = organizer;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(
                        mTaskOrganizer.asBinder());
                if (state != null) {
                    state.dispose();
                }
            }
        }
    }

    /**
     * A wrapper class around ITaskOrganizer to ensure that the calls are made in the right
     * lifecycle order since we may be updating the visibility of task surface controls in a pending
     * transaction before they are presented to the task org.
     */
    private static class TaskOrganizerCallbacks {
        final ITaskOrganizer mTaskOrganizer;

        TaskOrganizerCallbacks(ITaskOrganizer taskOrg) {
            mTaskOrganizer = taskOrg;
        }

        IBinder getBinder() {
            return mTaskOrganizer.asBinder();
        }

        SurfaceControl prepareLeash(Task task, String reason) {
            return new SurfaceControl(task.getSurfaceControl(), reason);
        }

        void onTaskAppeared(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task appeared taskId=%d", task.mTaskId);
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            try {
                mTaskOrganizer.onTaskAppeared(taskInfo, prepareLeash(task,
                        "TaskOrganizerController.onTaskAppeared"));
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskAppeared callback", e);
            }
        }

        void onTaskVanished(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task vanished taskId=%d", task.mTaskId);
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            try {
                mTaskOrganizer.onTaskVanished(taskInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskVanished callback", e);
            }
        }

        void onTaskInfoChanged(Task task, ActivityManager.RunningTaskInfo taskInfo) {
            if (!task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared().
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task info changed taskId=%d", task.mTaskId);
            if (!task.isOrganized()) {
                // This is safe to ignore if the task is no longer organized
                return;
            }
            try {
                // Purposely notify of task info change immediately instead of deferring (like
                // appear and vanish) to allow info changes (such as new PIP params) to flow
                // without waiting.
                mTaskOrganizer.onTaskInfoChanged(taskInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskInfoChanged callback", e);
            }
        }

        void onBackPressedOnTaskRoot(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task back pressed on root taskId=%d",
                    task.mTaskId);
            if (!task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared().
                return;
            }
            if (!task.isOrganized()) {
                // This is safe to ignore if the task is no longer organized
                return;
            }
            try {
                mTaskOrganizer.onBackPressedOnTaskRoot(task.getTaskInfo());
            } catch (Exception e) {
                Slog.e(TAG, "Exception sending onBackPressedOnTaskRoot callback", e);
            }
        }
    }

    /**
     * Maintains a list of all the pending events for a given {@link android.window.TaskOrganizer}
     */
    static final class TaskOrganizerPendingEventsQueue {
        private final WeakHashMap<Task, RunningTaskInfo> mLastSentTaskInfos = new WeakHashMap<>();
        private final TaskOrganizerState mOrganizerState;
        private RunningTaskInfo mTmpTaskInfo;
        // Pending task events due to layout deferred.
        private final ArrayList<PendingTaskEvent> mPendingTaskEvents = new ArrayList<>();

        TaskOrganizerPendingEventsQueue(TaskOrganizerState taskOrganizerState) {
            mOrganizerState = taskOrganizerState;
        }

        @VisibleForTesting
        public ArrayList<PendingTaskEvent> getPendingEventList() {
            return mPendingTaskEvents;
        }

        int numPendingTaskEvents() {
            return mPendingTaskEvents.size();
        }

        void clearPendingTaskEvents() {
            mPendingTaskEvents.clear();
        }

        void addPendingTaskEvent(PendingTaskEvent event) {
            mPendingTaskEvents.add(event);
        }

        void removePendingTaskEvent(PendingTaskEvent event) {
            mPendingTaskEvents.remove(event);
        }

        /**
         * Removes all the pending task events for the given {@code task}.
         *
         * @param task
         * @return true if a {@link PendingTaskEvent#EVENT_APPEARED} is still pending for the given
         * {code task}.
         */
        boolean removePendingTaskEvents(Task task) {
            boolean foundPendingAppearedEvents = false;
            for (int i = mPendingTaskEvents.size() - 1; i >= 0; i--) {
                PendingTaskEvent entry = mPendingTaskEvents.get(i);
                if (task.mTaskId == entry.mTask.mTaskId) {
                    // This task is vanished so remove all pending event of it.
                    mPendingTaskEvents.remove(i);

                    if (entry.mEventType == PendingTaskEvent.EVENT_APPEARED) {
                        foundPendingAppearedEvents = true;
                    }
                }
            }
            return foundPendingAppearedEvents;
        }

        @Nullable
        private PendingTaskEvent getPendingTaskEvent(Task task, int type) {
            for (int i = mPendingTaskEvents.size() - 1; i >= 0; i--) {
                PendingTaskEvent entry = mPendingTaskEvents.get(i);
                if (task.mTaskId == entry.mTask.mTaskId && type == entry.mEventType) {
                    return entry;
                }
            }
            return null;
        }

        @VisibleForTesting
        @Nullable
        PendingTaskEvent getPendingLifecycleTaskEvent(Task task) {
            for (int i = mPendingTaskEvents.size() - 1; i >= 0; i--) {
                PendingTaskEvent entry = mPendingTaskEvents.get(i);
                if (task.mTaskId == entry.mTask.mTaskId && entry.isLifecycleEvent()) {
                    return entry;
                }
            }
            return null;
        }

        void dispatchPendingEvents() {
            if (mPendingTaskEvents.isEmpty()) {
                return;
            }
            for (int i = 0, n = mPendingTaskEvents.size(); i < n; i++) {
                dispatchPendingEvent(mPendingTaskEvents.get(i));
            }
            mPendingTaskEvents.clear();
        }

        private void dispatchPendingEvent(PendingTaskEvent event) {
            final Task task = event.mTask;
            switch (event.mEventType) {
                case PendingTaskEvent.EVENT_APPEARED:
                    if (task.taskAppearedReady()) {
                        mOrganizerState.mOrganizer.onTaskAppeared(task);
                    }
                    break;
                case PendingTaskEvent.EVENT_VANISHED:
                    mOrganizerState.mOrganizer.onTaskVanished(task);
                    mLastSentTaskInfos.remove(task);
                    break;
                case PendingTaskEvent.EVENT_INFO_CHANGED:
                    dispatchTaskInfoChanged(event.mTask, event.mForce);
                    break;
                case PendingTaskEvent.EVENT_ROOT_BACK_PRESSED:
                    mOrganizerState.mOrganizer.onBackPressedOnTaskRoot(task);
                    break;
            }
        }

        private void dispatchTaskInfoChanged(Task task, boolean force) {
            RunningTaskInfo lastInfo = mLastSentTaskInfos.get(task);
            if (mTmpTaskInfo == null) {
                mTmpTaskInfo = new RunningTaskInfo();
            }
            mTmpTaskInfo.configuration.unset();
            task.fillTaskInfo(mTmpTaskInfo);

            boolean changed = !mTmpTaskInfo
                    .equalsForTaskOrganizer(lastInfo)
                    || !configurationsAreEqualForOrganizer(
                    mTmpTaskInfo.configuration,
                    lastInfo.configuration);
            if (!(changed || force)) {
                // mTmpTaskInfo will be reused next time.
                return;
            }
            final RunningTaskInfo newInfo = mTmpTaskInfo;
            mLastSentTaskInfos.put(task,
                    mTmpTaskInfo);
            // Since we've stored this, clean up the reference so a new one will be created next
            // time.
            // Transferring it this way means we only have to construct new RunningTaskInfos when
            // they change.
            mTmpTaskInfo = null;

            if (task.isOrganized()) {
                // Because we defer sending taskAppeared() until the app has drawn, we may receive a
                // configuration change before the state actually has the task registered. As such
                // we should ignore these change events to the organizer until taskAppeared(). If
                // the task was created by the organizer, then we always send the info change.
                mOrganizerState.mOrganizer.onTaskInfoChanged(task, newInfo);
            }
        }
    }

    @VisibleForTesting
    class TaskOrganizerState {
        private final TaskOrganizerCallbacks mOrganizer;
        private final DeathRecipient mDeathRecipient;
        private final ArrayList<Task> mOrganizedTasks = new ArrayList<>();
        private final TaskOrganizerPendingEventsQueue mPendingEventsQueue;
        private final int mUid;

        TaskOrganizerState(ITaskOrganizer organizer, int uid) {
            mOrganizer = new TaskOrganizerCallbacks(organizer);
            mDeathRecipient = new DeathRecipient(organizer);
            mPendingEventsQueue = new TaskOrganizerPendingEventsQueue(this);
            try {
                organizer.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskOrganizer failed to register death recipient");
            }
            mUid = uid;
        }

        @VisibleForTesting
        DeathRecipient getDeathRecipient() {
            return mDeathRecipient;
        }

        @VisibleForTesting
        TaskOrganizerPendingEventsQueue getPendingEventsQueue() {
            return mPendingEventsQueue;
        }

        /**
         * Register this task with this state, but doesn't trigger the task appeared callback to
         * the organizer.
         */
        SurfaceControl addTaskWithoutCallback(Task t, String reason) {
            t.mTaskAppearedSent = true;
            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }
            return mOrganizer.prepareLeash(t, reason);
        }

        private boolean addTask(Task t) {
            if (t.mTaskAppearedSent) {
                return false;
            }

            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }

            if (t.taskAppearedReady()) {
                t.mTaskAppearedSent = true;
                return true;
            }
            return false;
        }

        private boolean removeTask(Task t, boolean removeFromSystem) {
            mOrganizedTasks.remove(t);
            mInterceptBackPressedOnRootTasks.remove(t.mTaskId);
            boolean taskAppearedSent = t.mTaskAppearedSent;
            if (taskAppearedSent) {
                if (t.getSurfaceControl() != null) {
                    t.migrateToNewSurfaceControl(t.getSyncTransaction());
                }
                t.mTaskAppearedSent = false;
            }
            if (removeFromSystem) {
                mService.removeTask(t);
            }
            return taskAppearedSent;
        }

        void dispose() {
            // Move organizer from managing specific windowing modes
            mTaskOrganizers.remove(mOrganizer.mTaskOrganizer);

            // Update tasks currently managed by this organizer to the next one available if
            // possible.
            while (!mOrganizedTasks.isEmpty()) {
                final Task t = mOrganizedTasks.get(0);
                if (t.mCreatedByOrganizer) {
                    // The tasks created by this organizer should ideally be deleted when this
                    // organizer is disposed off to avoid inconsistent behavior.
                    t.removeImmediately();
                } else {
                    t.updateTaskOrganizerState();
                }
                if (mOrganizedTasks.contains(t)) {
                    // updateTaskOrganizerState should remove the task from the list, but still
                    // check it again to avoid while-loop isn't terminate.
                    if (removeTask(t, t.mRemoveWithTaskOrganizer)) {
                        TaskOrganizerController.this.onTaskVanishedInternal(this, t);
                    }
                }
                if (mService.getTransitionController().isShellTransitionsEnabled()) {
                    // dispose is only called outside of transitions (eg during unregister). Since
                    // we "migrate" surfaces when replacing organizers, visibility gets delegated
                    // to transitions; however, since there is no transition at this point, we have
                    // to manually show the surface here.
                    if (t.mTaskOrganizer != null && t.getSurfaceControl() != null) {
                        t.getSyncTransaction().show(t.getSurfaceControl());
                    }
                }
            }

            // Pending events queue for this organizer need to be cleared because this organizer
            // has either died or unregistered itself.
            mPendingEventsQueue.clearPendingTaskEvents();
            mTaskOrganizerStates.remove(mOrganizer.getBinder());
        }

        void unlinkDeath() {
            mOrganizer.getBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    }

    static class PendingTaskEvent {
        static final int EVENT_APPEARED = 0;
        static final int EVENT_VANISHED = 1;
        static final int EVENT_INFO_CHANGED = 2;
        static final int EVENT_ROOT_BACK_PRESSED = 3;

        final int mEventType;
        final Task mTask;
        final ITaskOrganizer mTaskOrg;
        boolean mForce;

        PendingTaskEvent(Task task, int event) {
            this(task, task.mTaskOrganizer, event);
        }

        PendingTaskEvent(Task task, ITaskOrganizer taskOrg, int eventType) {
            mTask = task;
            mTaskOrg = taskOrg;
            mEventType = eventType;
        }

        boolean isLifecycleEvent() {
            return mEventType == EVENT_APPEARED || mEventType == EVENT_VANISHED
                    || mEventType == EVENT_INFO_CHANGED;
        }
    }

    private final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;

    // List of task organizers by priority
    private final ArrayDeque<ITaskOrganizer> mTaskOrganizers = new ArrayDeque<>();
    private final ArrayMap<IBinder, TaskOrganizerState> mTaskOrganizerStates = new ArrayMap<>();
    // Set of organized tasks (by taskId) that dispatch back pressed to their organizers
    private final HashSet<Integer> mInterceptBackPressedOnRootTasks = new HashSet<>();

    TaskOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw ActivityTaskManagerService.logAndRethrowRuntimeExceptionOnTransact(TAG, e);
        }
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter the a supported windowing mode.
     */
    @Override
    public ParceledListSlice<TaskAppearedInfo> registerTaskOrganizer(ITaskOrganizer organizer) {
        enforceTaskPermission("registerTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            final ArrayList<TaskAppearedInfo> taskInfos = new ArrayList<>();
            final Runnable withGlobalLock = () -> {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Register task organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                if (!mTaskOrganizerStates.containsKey(organizer.asBinder())) {
                    mTaskOrganizers.add(organizer);
                    mTaskOrganizerStates.put(organizer.asBinder(),
                            new TaskOrganizerState(organizer, uid));
                }

                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                mService.mRootWindowContainer.forAllTasks((task) -> {
                    boolean returnTask = !task.mCreatedByOrganizer;
                    task.updateTaskOrganizerState(returnTask /* skipTaskAppeared */);
                    // It is possible for the task to not yet have a surface control, so ensure that
                    // the update succeeded in setting the organizer for the task before returning
                    if (task.isOrganized() && returnTask) {
                        SurfaceControl taskLeash = state.addTaskWithoutCallback(task,
                                "TaskOrganizerController.registerTaskOrganizer");
                        taskInfos.add(new TaskAppearedInfo(task.getTaskInfo(), taskLeash));
                    }
                });
            };
            if (mService.getTransitionController().isShellTransitionsEnabled()) {
                mService.getTransitionController().mRunningLock.runWhenIdle(1000, withGlobalLock);
            } else {
                synchronized (mGlobalLock) {
                    withGlobalLock.run();
                }
            }
            return new ParceledListSlice<>(taskInfos);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterTaskOrganizer(ITaskOrganizer organizer) {
        enforceTaskPermission("unregisterTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            final Runnable withGlobalLock = () -> {
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                if (state == null) {
                    return;
                }
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Unregister task organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                state.unlinkDeath();
                state.dispose();
            };
            if (mService.getTransitionController().isShellTransitionsEnabled()) {
                mService.getTransitionController().mRunningLock.runWhenIdle(1000, withGlobalLock);
            } else {
                synchronized (mGlobalLock) {
                    withGlobalLock.run();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * @return the task organizer key for a given windowing mode.
     */
    ITaskOrganizer getTaskOrganizer() {
        return mTaskOrganizers.peekLast();
    }

    // Capture the animation surface control for activity's main window
    static class StartingWindowAnimationAdaptor implements AnimationAdapter {

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                int type, @NonNull SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
        }

        @Override
        public long getDurationHint() {
            return 0;
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return 0;
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
        }

        @Override
        public void dumpDebug(ProtoOutputStream proto) {
        }
    }

    static SurfaceControl applyStartingWindowAnimation(WindowState window) {
        final SurfaceControl.Transaction t = window.getPendingTransaction();
        final StartingWindowAnimationAdaptor adaptor = new StartingWindowAnimationAdaptor();
        window.startAnimation(t, adaptor, false, ANIMATION_TYPE_STARTING_REVEAL);
        final SurfaceControl leash = window.getAnimationLeash();
        if (leash == null) {
            Slog.e(TAG, "Cannot start starting window animation, the window " + window
                    + " was removed");
            return null;
        }
        t.setPosition(leash, window.mSurfacePosition.x, window.mSurfacePosition.y);
        return leash;
    }

    boolean addStartingWindow(Task task, ActivityRecord activity, int launchTheme,
            TaskSnapshot taskSnapshot) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null || activity.mStartingData == null) {
            return false;
        }
        final ITaskOrganizer lastOrganizer = getTaskOrganizer();
        if (lastOrganizer == null) {
            return false;
        }
        final StartingWindowInfo info = task.getStartingWindowInfo(activity);
        if (launchTheme != 0) {
            info.splashScreenThemeResId = launchTheme;
        }
        info.taskSnapshot = taskSnapshot;
        info.appToken = activity.token;
        // make this happen prior than prepare surface
        try {
            lastOrganizer.addStartingWindow(info);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onTaskStart callback", e);
            return false;
        }
        return true;
    }

    void removeStartingWindow(Task task, ITaskOrganizer taskOrganizer, boolean prepareAnimation,
            boolean hasImeSurface) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null) {
            return;
        }
        final ITaskOrganizer lastOrganizer = taskOrganizer != null ? taskOrganizer
                : getTaskOrganizer();
        if (lastOrganizer == null) {
            return;
        }
        final StartingWindowRemovalInfo removalInfo = new StartingWindowRemovalInfo();
        removalInfo.taskId = task.mTaskId;
        removalInfo.playRevealAnimation = prepareAnimation
                && task.getDisplayContent() != null
                && task.getDisplayInfo().state == Display.STATE_ON;
        final boolean playShiftUpAnimation = !task.inMultiWindowMode();
        final ActivityRecord topActivity = task.topActivityContainsStartingWindow();
        if (topActivity != null) {
            // Set defer remove mode for IME
            final DisplayContent dc = topActivity.getDisplayContent();
            if (hasImeSurface) {
                if (topActivity.isVisibleRequested() && dc.mInputMethodWindow != null
                        && dc.isFixedRotationLaunchingApp(topActivity)) {
                    removalInfo.deferRemoveMode = DEFER_MODE_ROTATION;
                } else {
                    removalInfo.deferRemoveMode = DEFER_MODE_NORMAL;
                }
            }

            final WindowState mainWindow =
                    topActivity.findMainWindow(false/* includeStartingApp */);
            // No app window for this activity, app might be crashed.
            // Remove starting window immediately without playing reveal animation.
            if (mainWindow == null || mainWindow.mRemoved) {
                removalInfo.playRevealAnimation = false;
            } else if (removalInfo.playRevealAnimation && playShiftUpAnimation) {
                removalInfo.roundedCornerRadius =
                        topActivity.mAppCompatController.getAppCompatLetterboxPolicy()
                                .getRoundedCornersRadius(mainWindow);
                removalInfo.windowAnimationLeash = applyStartingWindowAnimation(mainWindow);
                removalInfo.mainFrame = new Rect(mainWindow.getFrame());
                removalInfo.mainFrame.offsetTo(mainWindow.mSurfacePosition.x,
                        mainWindow.mSurfacePosition.y);
            }
        }
        try {
            lastOrganizer.removeStartingWindow(removalInfo);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onStartTaskFinished callback", e);
        }
    }

    /**
     * Create a starting surface which attach on a given surface.
     * @param activity Target activity, this isn't necessary to be the top activity.
     * @param root The root surface which the created surface will attach on.
     * @param taskSnapshot Whether to draw snapshot.
     * @param callback Called when surface is drawn and attached to the root surface.
     * @return The taskId, this is a token and should be used to remove the surface, even if
     *         the task was removed from hierarchy.
     */
    int addWindowlessStartingSurface(Task task, ActivityRecord activity, SurfaceControl root,
            TaskSnapshot taskSnapshot, Configuration configuration,
            IWindowlessStartingSurfaceCallback callback) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null) {
            return INVALID_TASK_ID;
        }
        final ITaskOrganizer lastOrganizer = mTaskOrganizers.peekLast();
        if (lastOrganizer == null) {
            return INVALID_TASK_ID;
        }
        final StartingWindowInfo info = task.getStartingWindowInfo(activity);
        info.taskInfo.configuration.setTo(configuration);
        info.taskInfo.taskDescription = activity.taskDescription;
        info.taskSnapshot = taskSnapshot;
        info.windowlessStartingSurfaceCallback = callback;
        info.rootSurface = root;
        try {
            lastOrganizer.addStartingWindow(info);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending addWindowlessStartingSurface ", e);
            return INVALID_TASK_ID;
        }
        return task.mTaskId;
    }

    void removeWindowlessStartingSurface(int taskId, boolean immediately) {
        final ITaskOrganizer lastOrganizer = mTaskOrganizers.peekLast();
        if (lastOrganizer == null || taskId == 0) {
            return;
        }
        final StartingWindowRemovalInfo removalInfo = new StartingWindowRemovalInfo();
        removalInfo.taskId = taskId;
        removalInfo.windowlessSurface = true;
        removalInfo.removeImmediately = immediately;
        removalInfo.deferRemoveMode = DEFER_MODE_NONE;
        try {
            lastOrganizer.removeStartingWindow(removalInfo);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending removeWindowlessStartingSurface ", e);
        }
    }

    boolean copySplashScreenView(Task task, ITaskOrganizer taskOrganizer) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null) {
            return false;
        }
        final ITaskOrganizer lastOrganizer = taskOrganizer != null ? taskOrganizer
                : getTaskOrganizer();
        if (lastOrganizer == null) {
            return false;
        }
        try {
            lastOrganizer.copySplashScreenView(task.mTaskId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending copyStartingWindowView callback", e);
            return false;
        }
        return true;
    }

    boolean isSupportWindowlessStartingSurface() {
        final ITaskOrganizer lastOrganizer = mTaskOrganizers.peekLast();
        return lastOrganizer != null;
    }
    /**
     * Notify the shell ({@link com.android.wm.shell.ShellTaskOrganizer} that the client has
     * removed the splash screen view.
     * @see com.android.wm.shell.ShellTaskOrganizer#onAppSplashScreenViewRemoved(int)
     * @see SplashScreenView#remove()
     */
    public void onAppSplashScreenViewRemoved(Task task, ITaskOrganizer organizer) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null) {
            return;
        }
        final ITaskOrganizer lastOrganizer = organizer != null ? organizer : getTaskOrganizer();
        if (lastOrganizer == null) {
            return;
        }
        try {
            lastOrganizer.onAppSplashScreenViewRemoved(task.mTaskId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onAppSplashScreenViewRemoved callback", e);
        }
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        if (state != null && state.addTask(task)) {
            final TaskOrganizerPendingEventsQueue pendingEvents =
                    state.mPendingEventsQueue;
            PendingTaskEvent pending = pendingEvents.getPendingTaskEvent(task,
                    PendingTaskEvent.EVENT_APPEARED);
            if (pending == null) {
                pendingEvents.addPendingTaskEvent(new PendingTaskEvent(task,
                        PendingTaskEvent.EVENT_APPEARED));
            }
        }
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        if (state != null && state.removeTask(task, task.mRemoveWithTaskOrganizer)) {
            onTaskVanishedInternal(state, task);
        }
    }

    private void onTaskVanishedInternal(TaskOrganizerState organizerState, Task task) {
        if (organizerState == null) {
            Slog.i(TAG, "cannot send onTaskVanished because organizer state is not "
                    + "present for this organizer");
            return;
        }
        TaskOrganizerPendingEventsQueue pendingEventsQueue =
                organizerState.mPendingEventsQueue;
        boolean hadPendingAppearedEvents =
                pendingEventsQueue.removePendingTaskEvents(task);
        if (hadPendingAppearedEvents) {
            return;
        }
        pendingEventsQueue.addPendingTaskEvent(new PendingTaskEvent(task,
                organizerState.mOrganizer.mTaskOrganizer, PendingTaskEvent.EVENT_VANISHED));
    }

    @Override
    public void createRootTask(int displayId, int windowingMode, @Nullable IBinder launchCookie,
            boolean removeWithTaskOrganizer) {
        enforceTaskPermission("createRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    ProtoLog.e(WM_DEBUG_WINDOW_ORGANIZER,
                            "createRootTask unknown displayId=%d", displayId);
                    return;
                }

                createRootTask(display, windowingMode, launchCookie, removeWithTaskOrganizer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @VisibleForTesting
    Task createRootTask(DisplayContent display, int windowingMode, @Nullable IBinder launchCookie) {
        return createRootTask(display, windowingMode, launchCookie,
                false /* removeWithTaskOrganizer */);
    }

    Task createRootTask(DisplayContent display, int windowingMode, @Nullable IBinder launchCookie,
            boolean removeWithTaskOrganizer) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Create root task displayId=%d winMode=%d",
                display.mDisplayId, windowingMode);
        // We want to defer the task appear signal until the task is fully created and attached to
        // to the hierarchy so that the complete starting configuration is in the task info we send
        // over to the organizer.
        final Task task = new Task.Builder(mService)
                .setWindowingMode(windowingMode)
                .setIntent(new Intent())
                .setCreatedByOrganizer(true)
                .setDeferTaskAppear(true)
                .setLaunchCookie(launchCookie)
                .setParent(display.getDefaultTaskDisplayArea())
                .setRemoveWithTaskOrganizer(removeWithTaskOrganizer)
                .build();
        task.setDeferTaskAppear(false /* deferTaskAppear */);
        return task;
    }

    @Override
    public boolean deleteRootTask(WindowContainerToken token) {
        enforceTaskPermission("deleteRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null) return false;
                final Task task = wc.asTask();
                if (task == null) return false;
                if (!task.mCreatedByOrganizer) {
                    throw new IllegalArgumentException(
                            "Attempt to delete task not created by organizer task=" + task);
                }

                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Delete root task display=%d winMode=%d",
                        task.getDisplayId(), task.getWindowingMode());
                task.remove(true /* withTransition */, "deleteRootTask");
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void dispatchPendingEvents() {
        if (mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()) {
            return;
        }
        for (int taskOrgIdx = 0; taskOrgIdx < mTaskOrganizerStates.size(); taskOrgIdx++) {
            TaskOrganizerState taskOrganizerState = mTaskOrganizerStates.valueAt(taskOrgIdx);
            taskOrganizerState.mPendingEventsQueue.dispatchPendingEvents();
        }
    }

    void reportImeDrawnOnTask(Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(task.mTaskOrganizer.asBinder());
        if (state != null) {
            try {
                state.mOrganizer.mTaskOrganizer.onImeDrawnOnTask(task.mTaskId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onImeDrawnOnTask callback", e);
            }
        }
    }

    void onTaskInfoChanged(Task task, boolean force) {
        if (!task.mTaskAppearedSent) {
            // Skip if task still not appeared.
            return;
        }
        final TaskOrganizerState taskOrganizerState =
                mTaskOrganizerStates.get(task.mTaskOrganizer.asBinder());
        final TaskOrganizerPendingEventsQueue pendingEventsQueue =
                taskOrganizerState.mPendingEventsQueue;
        if (pendingEventsQueue == null) {
            Slog.i(TAG, "cannot send onTaskInfoChanged because pending events queue is not "
                    + "present for this organizer");
            return;
        }
        if (force && pendingEventsQueue.numPendingTaskEvents() == 0) {
            // There are task-info changed events do not result in
            // - RootWindowContainer#performSurfacePlacementNoTrace OR
            // - WindowAnimator#animate
            // For instance, when an app requesting aspect ratio change when in PiP mode.
            // To solve this, we directly dispatch the pending event if there are no events queued (
            // otherwise, all pending events should be dispatched on next drawn).
            pendingEventsQueue.dispatchTaskInfoChanged(task, true /* force */);
            return;
        }

        // Defer task info reporting while layout is deferred. This is because layout defer
        // blocks tend to do lots of re-ordering which can mess up animations in receivers.
        PendingTaskEvent pending = pendingEventsQueue
                .getPendingLifecycleTaskEvent(task);
        if (pending == null) {
            pending = new PendingTaskEvent(task, PendingTaskEvent.EVENT_INFO_CHANGED);
        } else {
            if (pending.mEventType != PendingTaskEvent.EVENT_INFO_CHANGED) {
                // If queued event is appeared, it means task still not appeared so ignore
                // this info changed. If queued event is vanished, it means task should
                // will vanished early so do not need this info changed.
                return;
            }
            // Remove and add for re-ordering.
            pendingEventsQueue.removePendingTaskEvent(pending);
        }
        pending.mForce |= force;
        pendingEventsQueue.addPendingTaskEvent(pending);
    }

    @Override
    public WindowContainerToken getImeTarget(int displayId) {
        enforceTaskPermission("getImeTarget()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc = mService.mWindowManager.mRoot
                        .getDisplayContent(displayId);
                if (dc == null) {
                    return null;
                }

                final InsetsControlTarget imeLayeringTarget = dc.getImeTarget(IME_TARGET_LAYERING);
                if (imeLayeringTarget == null || imeLayeringTarget.getWindow() == null) {
                    return null;
                }

                // Avoid WindowState#getRootTask() so we don't attribute system windows to a task.
                final Task task = imeLayeringTarget.getWindow().getTask();
                if (task == null) {
                    return null;
                }

                return task.mRemoteToken.toWindowContainerToken();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<RunningTaskInfo> getChildTasks(WindowContainerToken parent,
            @Nullable int[] activityTypes) {
        enforceTaskPermission("getChildTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (parent == null) {
                    throw new IllegalArgumentException("Can't get children of null parent");
                }
                final WindowContainer container = WindowContainer.fromBinder(parent.asBinder());
                if (container == null) {
                    Slog.e(TAG, "Can't get children of " + parent + " because it is not valid.");
                    return null;
                }
                final Task task = container.asTask();
                if (task == null) {
                    Slog.e(TAG, container + " is not a task...");
                    return null;
                }
                // For now, only support returning children of tasks created by the organizer.
                if (!task.mCreatedByOrganizer) {
                    Slog.w(TAG, "Can only get children of root tasks created via createRootTask");
                    return null;
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                for (int i = task.getChildCount() - 1; i >= 0; --i) {
                    final Task child = task.getChildAt(i).asTask();
                    if (child == null) continue;
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, child.getActivityType())) {
                        continue;
                    }
                    out.add(child.getTaskInfo());
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<RunningTaskInfo> getRootTasks(int displayId, @Nullable int[] activityTypes) {
        enforceTaskPermission("getRootTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc =
                        mService.mRootWindowContainer.getDisplayContent(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Display " + displayId + " doesn't exist");
                }
                final ArrayList<RunningTaskInfo> out = new ArrayList<>();
                dc.forAllRootTasks(task -> {
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, task.getActivityType())) {
                        return;
                    }
                    out.add(task.getTaskInfo());
                });
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setInterceptBackPressedOnTaskRoot(WindowContainerToken token,
            boolean interceptBackPressed) {
        enforceTaskPermission("setInterceptBackPressedOnTaskRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Set intercept back pressed on root=%b",
                        interceptBackPressed);
                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null) {
                    Slog.w(TAG, "Could not resolve window from token");
                    return;
                }
                final Task task = wc.asTask();
                if (task == null) {
                    Slog.w(TAG, "Could not resolve task from token");
                    return;
                }
                if (interceptBackPressed) {
                    mInterceptBackPressedOnRootTasks.add(task.mTaskId);
                } else {
                    mInterceptBackPressedOnRootTasks.remove(task.mTaskId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void restartTaskTopActivityProcessIfVisible(WindowContainerToken token) {
        enforceTaskPermission("restartTopActivityProcessIfVisible()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null) {
                    Slog.w(TAG, "Could not resolve window from token");
                    return;
                }
                final Task task = wc.asTask();
                if (task == null) {
                    Slog.w(TAG, "Could not resolve task from token");
                    return;
                }
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Restart top activity process of Task taskId=%d", task.mTaskId);
                final ActivityRecord activity = task.getTopNonFinishingActivity();
                if (activity != null) {
                    activity.restartProcessIfVisible();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean handleInterceptBackPressedOnTaskRoot(Task task) {
        if (!shouldInterceptBackPressedOnRootTask(task)) {
            return false;
        }
        final TaskOrganizerPendingEventsQueue pendingEventsQueue =
                mTaskOrganizerStates.get(task.mTaskOrganizer.asBinder())
                        .mPendingEventsQueue;
        if (pendingEventsQueue == null) {
            Slog.w(TAG, "cannot get handle BackPressedOnTaskRoot because organizerState is "
                    + "not present");
            return false;
        }

        PendingTaskEvent pendingVanished =
                pendingEventsQueue.getPendingTaskEvent(task,
                        PendingTaskEvent.EVENT_VANISHED);
        if (pendingVanished != null) {
            // This task will vanish before this callback so just ignore.
            return false;
        }

        PendingTaskEvent pending = pendingEventsQueue.getPendingTaskEvent(
                task, PendingTaskEvent.EVENT_ROOT_BACK_PRESSED);
        if (pending == null) {
            pending = new PendingTaskEvent(task, PendingTaskEvent.EVENT_ROOT_BACK_PRESSED);
        } else {
            // Pending already exist, remove and add for re-ordering.
            pendingEventsQueue.removePendingTaskEvent(pending);
        }
        pendingEventsQueue.addPendingTaskEvent(pending);
        mService.mWindowManager.mWindowPlacerLocked.requestTraversal();
        return true;
    }

    boolean shouldInterceptBackPressedOnRootTask(Task task) {
        return task != null && task.isOrganized()
                && mInterceptBackPressedOnRootTasks.contains(task.mTaskId);
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println("TaskOrganizerController:");
        final ITaskOrganizer lastOrganizer = mTaskOrganizers.peekLast();
        for (ITaskOrganizer organizer : mTaskOrganizers) {
            final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
            final ArrayList<Task> tasks = state.mOrganizedTasks;
            pw.print(innerPrefix + "  ");
            pw.print(state.mOrganizer.mTaskOrganizer + " uid=" + state.mUid);
            if (lastOrganizer == organizer) {
                pw.print(" (active)");
            }
            pw.println(':');
            for (int k = 0; k < tasks.size(); k++) {
                final Task task = tasks.get(k);
                final int mode = task.getWindowingMode();
                pw.println(innerPrefix + "    ("
                        + WindowConfiguration.windowingModeToString(mode) + ") " + task);
            }

        }
        pw.println();
    }

    @VisibleForTesting
    TaskOrganizerState getTaskOrganizerState(IBinder taskOrganizer) {
        return mTaskOrganizerStates.get(taskOrganizer);
    }

    @VisibleForTesting
    TaskOrganizerPendingEventsQueue getTaskOrganizerPendingEvents(IBinder taskOrganizer) {
        return mTaskOrganizerStates.get(taskOrganizer).mPendingEventsQueue;
    }
}
