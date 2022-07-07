/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.window.TaskFragmentOrganizer.putExceptionInBundle;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.TaskFragment.EMBEDDING_ALLOWED;
import static com.android.server.wm.WindowOrganizerController.configurationsAreEqualForOrganizer;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.RemoteAnimationDefinition;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.TaskFragmentInfo;

import com.android.internal.protolog.common.ProtoLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores and manages the client {@link android.window.TaskFragmentOrganizer}.
 */
public class TaskFragmentOrganizerController extends ITaskFragmentOrganizerController.Stub {
    private static final String TAG = "TaskFragmentOrganizerController";
    private static final long TEMPORARY_ACTIVITY_TOKEN_TIMEOUT_MS = 5000;

    private final ActivityTaskManagerService mAtmService;
    private final WindowManagerGlobalLock mGlobalLock;
    /**
     * A Map which manages the relationship between
     * {@link ITaskFragmentOrganizer} and {@link TaskFragmentOrganizerState}
     */
    private final ArrayMap<IBinder, TaskFragmentOrganizerState> mTaskFragmentOrganizerState =
            new ArrayMap<>();
    /**
     * A List which manages the TaskFragment pending event {@link PendingTaskFragmentEvent}
     */
    private final ArrayList<PendingTaskFragmentEvent> mPendingTaskFragmentEvents =
            new ArrayList<>();

    TaskFragmentOrganizerController(ActivityTaskManagerService atm) {
        mAtmService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    /**
     * A class to manage {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments}.
     */
    private class TaskFragmentOrganizerState implements IBinder.DeathRecipient {
        private final ArrayList<TaskFragment> mOrganizedTaskFragments = new ArrayList<>();
        private final ITaskFragmentOrganizer mOrganizer;
        private final int mOrganizerPid;
        private final int mOrganizerUid;
        private final Map<TaskFragment, TaskFragmentInfo> mLastSentTaskFragmentInfos =
                new WeakHashMap<>();
        private final Map<TaskFragment, Configuration> mLastSentTaskFragmentParentConfigs =
                new WeakHashMap<>();
        private final Map<IBinder, ActivityRecord> mTemporaryActivityTokens =
                new WeakHashMap<>();

        /**
         * Map from Task Id to {@link RemoteAnimationDefinition}.
         * @see android.window.TaskFragmentOrganizer#registerRemoteAnimations(int,
         * RemoteAnimationDefinition) )
         */
        private final SparseArray<RemoteAnimationDefinition> mRemoteAnimationDefinitions =
                new SparseArray<>();

        TaskFragmentOrganizerState(ITaskFragmentOrganizer organizer, int pid, int uid) {
            mOrganizer = organizer;
            mOrganizerPid = pid;
            mOrganizerUid = uid;
            try {
                mOrganizer.asBinder().linkToDeath(this, 0 /*flags*/);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskFragmentOrganizer failed to register death recipient");
            }
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                removeOrganizer(mOrganizer);
            }
        }

        /**
         * @return {@code true} if taskFragment is organized and not sent the appeared event before.
         */
        boolean addTaskFragment(TaskFragment taskFragment) {
            if (taskFragment.mTaskFragmentAppearedSent) {
                return false;
            }
            if (mOrganizedTaskFragments.contains(taskFragment)) {
                return false;
            }
            mOrganizedTaskFragments.add(taskFragment);
            return true;
        }

        void removeTaskFragment(TaskFragment taskFragment) {
            mOrganizedTaskFragments.remove(taskFragment);
        }

        void dispose() {
            while (!mOrganizedTaskFragments.isEmpty()) {
                final TaskFragment taskFragment = mOrganizedTaskFragments.get(0);
                taskFragment.removeImmediately();
                mOrganizedTaskFragments.remove(taskFragment);
            }
            mOrganizer.asBinder().unlinkToDeath(this, 0 /*flags*/);
        }

        void onTaskFragmentAppeared(TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment appeared name=%s", tf.getName());
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            try {
                mOrganizer.onTaskFragmentAppeared(info);
                mLastSentTaskFragmentInfos.put(tf, info);
                tf.mTaskFragmentAppearedSent = true;
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending onTaskFragmentAppeared callback", e);
            }
            onTaskFragmentParentInfoChanged(tf);
        }

        void onTaskFragmentVanished(TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment vanished name=%s", tf.getName());
            try {
                mOrganizer.onTaskFragmentVanished(tf.getTaskFragmentInfo());
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending onTaskFragmentVanished callback", e);
            }
            tf.mTaskFragmentAppearedSent = false;
            mLastSentTaskFragmentInfos.remove(tf);
            mLastSentTaskFragmentParentConfigs.remove(tf);
        }

        void onTaskFragmentInfoChanged(TaskFragment tf) {
            // Parent config may have changed. The controller will check if there is any important
            // config change for the organizer.
            onTaskFragmentParentInfoChanged(tf);

            // Check if the info is different from the last reported info.
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            final TaskFragmentInfo lastInfo = mLastSentTaskFragmentInfos.get(tf);
            if (info.equalsForTaskFragmentOrganizer(lastInfo) && configurationsAreEqualForOrganizer(
                    info.getConfiguration(), lastInfo.getConfiguration())) {
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment info changed name=%s",
                    tf.getName());
            try {
                mOrganizer.onTaskFragmentInfoChanged(info);
                mLastSentTaskFragmentInfos.put(tf, info);
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending onTaskFragmentInfoChanged callback", e);
            }
        }

        void onTaskFragmentParentInfoChanged(TaskFragment tf) {
            // Check if the parent info is different from the last reported parent info.
            if (tf.getParent() == null || tf.getParent().asTask() == null) {
                mLastSentTaskFragmentParentConfigs.remove(tf);
                return;
            }
            final Task parent = tf.getParent().asTask();
            final Configuration parentConfig = parent.getConfiguration();
            final Configuration lastParentConfig = mLastSentTaskFragmentParentConfigs.get(tf);
            if (configurationsAreEqualForOrganizer(parentConfig, lastParentConfig)
                    && parentConfig.windowConfiguration.getWindowingMode()
                    == lastParentConfig.windowConfiguration.getWindowingMode()) {
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "TaskFragment parent info changed name=%s parentTaskId=%d",
                    tf.getName(), parent.mTaskId);
            try {
                mOrganizer.onTaskFragmentParentInfoChanged(tf.getFragmentToken(), parentConfig);
                mLastSentTaskFragmentParentConfigs.put(tf, new Configuration(parentConfig));
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending onTaskFragmentParentInfoChanged callback", e);
            }
        }

        void onTaskFragmentError(IBinder errorCallbackToken, Throwable exception) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Sending TaskFragment error exception=%s", exception.toString());
            final Bundle exceptionBundle = putExceptionInBundle(exception);
            try {
                mOrganizer.onTaskFragmentError(errorCallbackToken, exceptionBundle);
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending onTaskFragmentError callback", e);
            }
        }

        void onActivityReparentToTask(ActivityRecord activity) {
            if (activity.finishing) {
                Slog.d(TAG, "Reparent activity=" + activity.token + " is finishing");
                return;
            }
            final Task task = activity.getTask();
            if (task == null || task.effectiveUid != mOrganizerUid) {
                Slog.d(TAG, "Reparent activity=" + activity.token
                        + " is not in a task belong to the organizer app.");
                return;
            }
            if (task.isAllowedToEmbedActivity(activity, mOrganizerUid) != EMBEDDING_ALLOWED) {
                Slog.d(TAG, "Reparent activity=" + activity.token
                        + " is not allowed to be embedded.");
                return;
            }

            final IBinder activityToken;
            if (activity.getPid() == mOrganizerPid) {
                // We only pass the actual token if the activity belongs to the organizer process.
                activityToken = activity.token;
            } else {
                // For security, we can't pass the actual token if the activity belongs to a
                // different process. In this case, we will pass a temporary token that organizer
                // can use to reparent through WindowContainerTransaction.
                activityToken = new Binder("TemporaryActivityToken");
                mTemporaryActivityTokens.put(activityToken, activity);
                final Runnable timeout = () -> {
                    synchronized (mGlobalLock) {
                        mTemporaryActivityTokens.remove(activityToken);
                    }
                };
                mAtmService.mWindowManager.mH.postDelayed(timeout,
                        TEMPORARY_ACTIVITY_TOKEN_TIMEOUT_MS);
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Activity=%s reparent to taskId=%d",
                    activity.token, task.mTaskId);
            try {
                mOrganizer.onActivityReparentToTask(task.mTaskId, activity.intent, activityToken);
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending onActivityReparentToTask callback", e);
            }
        }
    }

    @Nullable
    ActivityRecord getReparentActivityFromTemporaryToken(
            @Nullable ITaskFragmentOrganizer organizer, @Nullable IBinder activityToken) {
        if (organizer == null || activityToken == null) {
            return null;
        }
        final TaskFragmentOrganizerState state = mTaskFragmentOrganizerState.get(
                organizer.asBinder());
        return state != null
                ? state.mTemporaryActivityTokens.remove(activityToken)
                : null;
    }

    @Override
    public void registerOrganizer(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register task fragment organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            if (mTaskFragmentOrganizerState.containsKey(organizer.asBinder())) {
                throw new IllegalStateException(
                        "Replacing existing organizer currently unsupported");
            }
            mTaskFragmentOrganizerState.put(organizer.asBinder(),
                    new TaskFragmentOrganizerState(organizer, pid, uid));
        }
    }

    @Override
    public void unregisterOrganizer(ITaskFragmentOrganizer organizer) {
        validateAndGetState(organizer);
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Unregister task fragment organizer=%s uid=%d pid=%d",
                        organizer.asBinder(), uid, pid);
                removeOrganizer(organizer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void registerRemoteAnimations(ITaskFragmentOrganizer organizer, int taskId,
            RemoteAnimationDefinition definition) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register remote animations for organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            if (organizerState == null) {
                throw new IllegalStateException("The organizer hasn't been registered.");
            }
            if (organizerState.mRemoteAnimationDefinitions.contains(taskId)) {
                throw new IllegalStateException(
                        "The organizer has already registered remote animations="
                                + organizerState.mRemoteAnimationDefinitions.get(taskId)
                                + " for TaskId=" + taskId);
            }

            definition.setCallingPidUid(pid, uid);
            organizerState.mRemoteAnimationDefinitions.put(taskId, definition);
        }
    }

    @Override
    public void unregisterRemoteAnimations(ITaskFragmentOrganizer organizer, int taskId) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Unregister remote animations for organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            if (organizerState == null) {
                Slog.e(TAG, "The organizer hasn't been registered.");
                return;
            }

            organizerState.mRemoteAnimationDefinitions.remove(taskId);
        }
    }

    /**
     * Gets the {@link RemoteAnimationDefinition} set on the given organizer if exists. Returns
     * {@code null} if it doesn't, or if the organizer has activity(ies) embedded in untrusted mode.
     */
    @Nullable
    public RemoteAnimationDefinition getRemoteAnimationDefinition(
            ITaskFragmentOrganizer organizer, int taskId) {
        synchronized (mGlobalLock) {
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            return organizerState != null
                    ? organizerState.mRemoteAnimationDefinitions.get(taskId)
                    : null;
        }
    }

    int getTaskFragmentOrganizerUid(ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        return state.mOrganizerUid;
    }

    void onTaskFragmentAppeared(ITaskFragmentOrganizer organizer, TaskFragment taskFragment) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        if (!state.addTaskFragment(taskFragment)) {
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_APPEARED);
        if (pendingEvent == null) {
            pendingEvent = new PendingTaskFragmentEvent.Builder(
                    PendingTaskFragmentEvent.EVENT_APPEARED, organizer)
                    .setTaskFragment(taskFragment)
                    .build();
            mPendingTaskFragmentEvents.add(pendingEvent);
        }
    }

    void onTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment taskFragment) {
        handleTaskFragmentInfoChanged(organizer, taskFragment,
                PendingTaskFragmentEvent.EVENT_INFO_CHANGED);
    }

    void onTaskFragmentParentInfoChanged(ITaskFragmentOrganizer organizer,
            TaskFragment taskFragment) {
        handleTaskFragmentInfoChanged(organizer, taskFragment,
                PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED);
    }

    private void handleTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer,
            TaskFragment taskFragment, int eventType) {
        validateAndGetState(organizer);
        if (!taskFragment.mTaskFragmentAppearedSent) {
            // Skip if TaskFragment still not appeared.
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getLastPendingLifecycleEvent(taskFragment);
        if (pendingEvent == null) {
            pendingEvent = new PendingTaskFragmentEvent.Builder(eventType, organizer)
                            .setTaskFragment(taskFragment)
                            .build();
        } else {
            if (pendingEvent.mEventType == PendingTaskFragmentEvent.EVENT_VANISHED) {
                // Skipped the info changed event if vanished event is pending.
                return;
            }
            // Remove and add for re-ordering.
            mPendingTaskFragmentEvents.remove(pendingEvent);
            // Reset the defer time when TaskFragment is changed, so that it can check again if
            // the event should be sent to the organizer, for example the TaskFragment may become
            // empty.
            pendingEvent.mDeferTime = 0;
        }
        mPendingTaskFragmentEvents.add(pendingEvent);
    }

    void onTaskFragmentVanished(ITaskFragmentOrganizer organizer, TaskFragment taskFragment) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        for (int i = mPendingTaskFragmentEvents.size() - 1; i >= 0; i--) {
            PendingTaskFragmentEvent entry = mPendingTaskFragmentEvents.get(i);
            if (taskFragment == entry.mTaskFragment) {
                mPendingTaskFragmentEvents.remove(i);
                if (entry.mEventType == PendingTaskFragmentEvent.EVENT_APPEARED) {
                    // If taskFragment appeared callback is pending, ignore the vanished request.
                    return;
                }
            }
        }
        if (!taskFragment.mTaskFragmentAppearedSent) {
            return;
        }
        final PendingTaskFragmentEvent pendingEvent = new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_VANISHED, organizer)
                .setTaskFragment(taskFragment)
                .build();
        mPendingTaskFragmentEvents.add(pendingEvent);
        state.removeTaskFragment(taskFragment);
    }

    void onTaskFragmentError(ITaskFragmentOrganizer organizer, IBinder errorCallbackToken,
            Throwable exception) {
        validateAndGetState(organizer);
        Slog.w(TAG, "onTaskFragmentError ", exception);
        final PendingTaskFragmentEvent pendingEvent = new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_ERROR, organizer)
                .setErrorCallbackToken(errorCallbackToken)
                .setException(exception)
                .build();
        mPendingTaskFragmentEvents.add(pendingEvent);
    }

    void onActivityReparentToTask(ActivityRecord activity) {
        final ITaskFragmentOrganizer organizer;
        if (activity.mLastTaskFragmentOrganizerBeforePip != null) {
            // If the activity is previously embedded in an organized TaskFragment.
            organizer = activity.mLastTaskFragmentOrganizerBeforePip;
        } else {
            // Find the topmost TaskFragmentOrganizer.
            final Task task = activity.getTask();
            final TaskFragment[] organizedTf = new TaskFragment[1];
            task.forAllLeafTaskFragments(tf -> {
                if (tf.isOrganizedTaskFragment()) {
                    organizedTf[0] = tf;
                    return true;
                }
                return false;
            });
            if (organizedTf[0] == null) {
                return;
            }
            organizer = organizedTf[0].getTaskFragmentOrganizer();
        }
        if (!mTaskFragmentOrganizerState.containsKey(organizer.asBinder())) {
            Slog.w(TAG, "The last TaskFragmentOrganizer no longer exists");
            return;
        }
        final PendingTaskFragmentEvent pendingEvent = new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_ACTIVITY_REPARENT_TO_TASK, organizer)
                .setActivity(activity)
                .build();
        mPendingTaskFragmentEvents.add(pendingEvent);
    }

    private void removeOrganizer(ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        // remove all of the children of the organized TaskFragment
        state.dispose();
        mTaskFragmentOrganizerState.remove(organizer.asBinder());
    }

    /**
     * Makes sure that the organizer has been correctly registered to prevent any Sidecar
     * implementation from organizing {@link TaskFragment} without registering first. In such case,
     * we wouldn't register {@link DeathRecipient} for the organizer, and might not remove the
     * {@link TaskFragment} after the organizer process died.
     */
    private TaskFragmentOrganizerState validateAndGetState(ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(organizer.asBinder());
        if (state == null) {
            throw new IllegalArgumentException(
                    "TaskFragmentOrganizer has not been registered. Organizer=" + organizer);
        }
        return state;
    }

    /**
     * A class to store {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments} with different pending event request.
     */
    private static class PendingTaskFragmentEvent {
        static final int EVENT_APPEARED = 0;
        static final int EVENT_VANISHED = 1;
        static final int EVENT_INFO_CHANGED = 2;
        static final int EVENT_PARENT_INFO_CHANGED = 3;
        static final int EVENT_ERROR = 4;
        static final int EVENT_ACTIVITY_REPARENT_TO_TASK = 5;

        @IntDef(prefix = "EVENT_", value = {
                EVENT_APPEARED,
                EVENT_VANISHED,
                EVENT_INFO_CHANGED,
                EVENT_PARENT_INFO_CHANGED,
                EVENT_ERROR,
                EVENT_ACTIVITY_REPARENT_TO_TASK
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface EventType {}

        @EventType
        private final int mEventType;
        private final ITaskFragmentOrganizer mTaskFragmentOrg;
        @Nullable
        private final TaskFragment mTaskFragment;
        @Nullable
        private final IBinder mErrorCallbackToken;
        @Nullable
        private final Throwable mException;
        @Nullable
        private final ActivityRecord mActivity;
        // Set when the event is deferred due to the host task is invisible. The defer time will
        // be the last active time of the host task.
        private long mDeferTime;

        private PendingTaskFragmentEvent(@EventType int eventType,
                ITaskFragmentOrganizer taskFragmentOrg,
                @Nullable TaskFragment taskFragment,
                @Nullable IBinder errorCallbackToken,
                @Nullable Throwable exception,
                @Nullable ActivityRecord activity) {
            mEventType = eventType;
            mTaskFragmentOrg = taskFragmentOrg;
            mTaskFragment = taskFragment;
            mErrorCallbackToken = errorCallbackToken;
            mException = exception;
            mActivity = activity;
        }

        /**
         * @return {@code true} if the pending event is related with taskFragment created, vanished
         * and information changed.
         */
        boolean isLifecycleEvent() {
            switch (mEventType) {
                case EVENT_APPEARED:
                case EVENT_VANISHED:
                case EVENT_INFO_CHANGED:
                case EVENT_PARENT_INFO_CHANGED:
                    return true;
                default:
                    return false;
            }
        }

        private static class Builder {
            @EventType
            private final int mEventType;
            private final ITaskFragmentOrganizer mTaskFragmentOrg;
            @Nullable
            private TaskFragment mTaskFragment;
            @Nullable
            private IBinder mErrorCallbackToken;
            @Nullable
            private Throwable mException;
            @Nullable
            private ActivityRecord mActivity;

            Builder(@EventType int eventType, ITaskFragmentOrganizer taskFragmentOrg) {
                mEventType = eventType;
                mTaskFragmentOrg = taskFragmentOrg;
            }

            Builder setTaskFragment(@Nullable TaskFragment taskFragment) {
                mTaskFragment = taskFragment;
                return this;
            }

            Builder setErrorCallbackToken(@Nullable IBinder errorCallbackToken) {
                mErrorCallbackToken = errorCallbackToken;
                return this;
            }

            Builder setException(@Nullable Throwable exception) {
                mException = exception;
                return this;
            }

            Builder setActivity(@Nullable ActivityRecord activity) {
                mActivity = activity;
                return this;
            }

            PendingTaskFragmentEvent build() {
                return new PendingTaskFragmentEvent(mEventType, mTaskFragmentOrg, mTaskFragment,
                        mErrorCallbackToken, mException, mActivity);
            }
        }
    }

    @Nullable
    private PendingTaskFragmentEvent getLastPendingLifecycleEvent(TaskFragment tf) {
        for (int i = mPendingTaskFragmentEvents.size() - 1; i >= 0; i--) {
            PendingTaskFragmentEvent entry = mPendingTaskFragmentEvents.get(i);
            if (tf == entry.mTaskFragment && entry.isLifecycleEvent()) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private PendingTaskFragmentEvent getPendingTaskFragmentEvent(TaskFragment taskFragment,
            int type) {
        for (int i = mPendingTaskFragmentEvents.size() - 1; i >= 0; i--) {
            PendingTaskFragmentEvent entry = mPendingTaskFragmentEvents.get(i);
            if (taskFragment == entry.mTaskFragment && type == entry.mEventType) {
                return entry;
            }
        }
        return null;
    }

    private boolean shouldSendEventWhenTaskInvisible(@NonNull PendingTaskFragmentEvent event) {
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(event.mTaskFragmentOrg.asBinder());
        final TaskFragmentInfo lastInfo = state.mLastSentTaskFragmentInfos.get(event.mTaskFragment);
        final TaskFragmentInfo info = event.mTaskFragment.getTaskFragmentInfo();
        // Send an info changed callback if this event is for the last activities to finish in a
        // TaskFragment so that the {@link TaskFragmentOrganizer} can delete this TaskFragment.
        return event.mEventType == PendingTaskFragmentEvent.EVENT_INFO_CHANGED
                && lastInfo != null && lastInfo.hasRunningActivity() && info.isEmpty();
    }

    void dispatchPendingEvents() {
        if (mAtmService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()
                || mPendingTaskFragmentEvents.isEmpty()) {
            return;
        }

        final ArrayList<Task> visibleTasks = new ArrayList<>();
        final ArrayList<Task> invisibleTasks = new ArrayList<>();
        final ArrayList<PendingTaskFragmentEvent> candidateEvents = new ArrayList<>();
        for (int i = 0, n = mPendingTaskFragmentEvents.size(); i < n; i++) {
            final PendingTaskFragmentEvent event = mPendingTaskFragmentEvents.get(i);
            final Task task = event.mTaskFragment != null ? event.mTaskFragment.getTask() : null;
            if (task != null && (task.lastActiveTime <= event.mDeferTime
                    || !(isTaskVisible(task, visibleTasks, invisibleTasks)
                    || shouldSendEventWhenTaskInvisible(event)))) {
                // Defer sending events to the TaskFragment until the host task is active again.
                event.mDeferTime = task.lastActiveTime;
                continue;
            }
            candidateEvents.add(event);
        }
        final int numEvents = candidateEvents.size();
        for (int i = 0; i < numEvents; i++) {
            dispatchEvent(candidateEvents.get(i));
        }
        if (numEvents > 0) {
            mPendingTaskFragmentEvents.removeAll(candidateEvents);
        }
    }

    private static boolean isTaskVisible(Task task, ArrayList<Task> knownVisibleTasks,
            ArrayList<Task> knownInvisibleTasks) {
        if (knownVisibleTasks.contains(task)) {
            return true;
        }
        if (knownInvisibleTasks.contains(task)) {
            return false;
        }
        if (task.shouldBeVisible(null /* starting */)) {
            knownVisibleTasks.add(task);
            return true;
        } else {
            knownInvisibleTasks.add(task);
            return false;
        }
    }

    void dispatchPendingInfoChangedEvent(TaskFragment taskFragment) {
        PendingTaskFragmentEvent event = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_INFO_CHANGED);
        if (event == null) {
            return;
        }

        dispatchEvent(event);
        mPendingTaskFragmentEvents.remove(event);
    }

    private void dispatchEvent(PendingTaskFragmentEvent event) {
        final ITaskFragmentOrganizer taskFragmentOrg = event.mTaskFragmentOrg;
        final TaskFragment taskFragment = event.mTaskFragment;
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(taskFragmentOrg.asBinder());
        if (state == null) {
            return;
        }
        switch (event.mEventType) {
            case PendingTaskFragmentEvent.EVENT_APPEARED:
                state.onTaskFragmentAppeared(taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_VANISHED:
                state.onTaskFragmentVanished(taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_INFO_CHANGED:
                state.onTaskFragmentInfoChanged(taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED:
                state.onTaskFragmentParentInfoChanged(taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_ERROR:
                state.onTaskFragmentError(event.mErrorCallbackToken, event.mException);
                break;
            case PendingTaskFragmentEvent.EVENT_ACTIVITY_REPARENT_TO_TASK:
                state.onActivityReparentToTask(event.mActivity);
        }
    }

    // TODO(b/204399167): change to push the embedded state to the client side
    @Override
    public boolean isActivityEmbedded(IBinder activityToken) {
        synchronized (mGlobalLock) {
            final ActivityRecord activity = ActivityRecord.forTokenLocked(activityToken);
            if (activity == null) {
                return false;
            }
            final TaskFragment taskFragment = activity.getOrganizedTaskFragment();
            if (taskFragment == null) {
                return false;
            }
            final Task parentTask = taskFragment.getTask();
            if (parentTask != null) {
                final Rect taskBounds = parentTask.getBounds();
                final Rect taskFragBounds = taskFragment.getBounds();
                return !taskBounds.equals(taskFragBounds) && taskBounds.contains(taskFragBounds);
            }
            return false;
        }
    }
}
