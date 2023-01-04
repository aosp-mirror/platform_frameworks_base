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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.window.TaskFragmentOrganizer.putErrorInfoInBundle;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.TaskFragment.EMBEDDING_ALLOWED;
import static com.android.server.wm.WindowOrganizerController.configurationsAreEqualForOrganizer;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentParentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
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
    private final WindowOrganizerController mWindowOrganizerController;

    /**
     * A Map which manages the relationship between
     * {@link ITaskFragmentOrganizer} and {@link TaskFragmentOrganizerState}
     */
    private final ArrayMap<IBinder, TaskFragmentOrganizerState> mTaskFragmentOrganizerState =
            new ArrayMap<>();
    /**
     * Map from {@link ITaskFragmentOrganizer} to a list of related {@link PendingTaskFragmentEvent}
     */
    private final ArrayMap<IBinder, List<PendingTaskFragmentEvent>> mPendingTaskFragmentEvents =
            new ArrayMap<>();

    private final ArraySet<Task> mTmpTaskSet = new ArraySet<>();

    TaskFragmentOrganizerController(@NonNull ActivityTaskManagerService atm,
            @NonNull WindowOrganizerController windowOrganizerController) {
        mAtmService = requireNonNull(atm);
        mGlobalLock = atm.mGlobalLock;
        mWindowOrganizerController = requireNonNull(windowOrganizerController);
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

        /**
         * Map from {@link TaskFragment} to the last {@link TaskFragmentInfo} sent to the
         * organizer.
         */
        private final Map<TaskFragment, TaskFragmentInfo> mLastSentTaskFragmentInfos =
                new WeakHashMap<>();

        /**
         * Map from {@link TaskFragment} to its leaf {@link Task#mTaskId}. Embedded
         * {@link TaskFragment} will not be reparented until it is removed.
         */
        private final Map<TaskFragment, Integer> mTaskFragmentTaskIds = new WeakHashMap<>();

        /**
         * Map from {@link Task#mTaskId} to the last {@link TaskFragmentParentInfo} sent to the
         * organizer.
         */
        private final SparseArray<TaskFragmentParentInfo> mLastSentTaskFragmentParentInfos =
                new SparseArray<>();

        /**
         * Map from temporary activity token to the corresponding {@link ActivityRecord}.
         */
        private final Map<IBinder, ActivityRecord> mTemporaryActivityTokens =
                new WeakHashMap<>();

        /**
         * {@link RemoteAnimationDefinition} for embedded activities transition animation that is
         * organized by this organizer.
         */
        @Nullable
        private RemoteAnimationDefinition mRemoteAnimationDefinition;

        /**
         * Map from {@link TaskFragmentTransaction#getTransactionToken()} to the
         * {@link Transition#getSyncId()} that has been deferred. {@link TransitionController} will
         * wait until the organizer finished handling the {@link TaskFragmentTransaction}.
         * @see #onTransactionFinished(IBinder)
         */
        private final ArrayMap<IBinder, Integer> mDeferredTransitions = new ArrayMap<>();

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
            for (int i = mOrganizedTaskFragments.size() - 1; i >= 0; i--) {
                // Cleanup the TaskFragmentOrganizer from all TaskFragments it organized before
                // removing the windows to prevent it from adding any additional TaskFragment
                // pending event.
                final TaskFragment taskFragment = mOrganizedTaskFragments.get(i);
                taskFragment.onTaskFragmentOrganizerRemoved();
            }

            // Defer to avoid unnecessary layout when there are multiple TaskFragments removal.
            mAtmService.deferWindowLayout();
            try {
                while (!mOrganizedTaskFragments.isEmpty()) {
                    final TaskFragment taskFragment = mOrganizedTaskFragments.remove(0);
                    taskFragment.removeImmediately();
                }
            } finally {
                mAtmService.continueWindowLayout();
            }

            for (int i = mDeferredTransitions.size() - 1; i >= 0; i--) {
                // Cleanup any running transaction to unblock the current transition.
                onTransactionFinished(mDeferredTransitions.keyAt(i));
            }
            mOrganizer.asBinder().unlinkToDeath(this, 0 /* flags */);
        }

        @NonNull
        TaskFragmentTransaction.Change prepareTaskFragmentAppeared(@NonNull TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment appeared name=%s", tf.getName());
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            final int taskId = tf.getTask().mTaskId;
            tf.mTaskFragmentAppearedSent = true;
            mLastSentTaskFragmentInfos.put(tf, info);
            mTaskFragmentTaskIds.put(tf, taskId);
            return new TaskFragmentTransaction.Change(
                    TYPE_TASK_FRAGMENT_APPEARED)
                    .setTaskFragmentToken(tf.getFragmentToken())
                    .setTaskFragmentInfo(info)
                    .setTaskId(taskId);
        }

        @NonNull
        TaskFragmentTransaction.Change prepareTaskFragmentVanished(@NonNull TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment vanished name=%s", tf.getName());
            tf.mTaskFragmentAppearedSent = false;
            mLastSentTaskFragmentInfos.remove(tf);

            // Cleanup TaskFragmentParentConfig if this is the last TaskFragment in the Task.
            final int taskId;
            if (mTaskFragmentTaskIds.containsKey(tf)) {
                taskId = mTaskFragmentTaskIds.remove(tf);
                if (!mTaskFragmentTaskIds.containsValue(taskId)) {
                    // No more TaskFragment in the Task.
                    mLastSentTaskFragmentParentInfos.remove(taskId);
                }
            } else {
                // This can happen if the appeared wasn't sent before remove.
                taskId = INVALID_TASK_ID;
            }

            return new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_VANISHED)
                    .setTaskFragmentToken(tf.getFragmentToken())
                    .setTaskFragmentInfo(tf.getTaskFragmentInfo())
                    .setTaskId(taskId);
        }

        @Nullable
        TaskFragmentTransaction.Change prepareTaskFragmentInfoChanged(
                @NonNull TaskFragment tf) {
            // Check if the info is different from the last reported info.
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            final TaskFragmentInfo lastInfo = mLastSentTaskFragmentInfos.get(tf);
            if (info.equalsForTaskFragmentOrganizer(lastInfo) && configurationsAreEqualForOrganizer(
                    info.getConfiguration(), lastInfo.getConfiguration())) {
                return null;
            }

            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment info changed name=%s",
                    tf.getName());
            mLastSentTaskFragmentInfos.put(tf, info);
            return new TaskFragmentTransaction.Change(
                    TYPE_TASK_FRAGMENT_INFO_CHANGED)
                    .setTaskFragmentToken(tf.getFragmentToken())
                    .setTaskFragmentInfo(info)
                    .setTaskId(tf.getTask().mTaskId);
        }

        @Nullable
        TaskFragmentTransaction.Change prepareTaskFragmentParentInfoChanged(@NonNull Task task) {
            final int taskId = task.mTaskId;
            // Check if the parent info is different from the last reported parent info.
            final TaskFragmentParentInfo parentInfo = task.getTaskFragmentParentInfo();
            final TaskFragmentParentInfo lastParentInfo = mLastSentTaskFragmentParentInfos
                    .get(taskId);
            final Configuration lastParentConfig = lastParentInfo != null
                    ? lastParentInfo.getConfiguration() : null;
            if (parentInfo.equalsForTaskFragmentOrganizer(lastParentInfo)
                    && configurationsAreEqualForOrganizer(parentInfo.getConfiguration(),
                            lastParentConfig)) {
                return null;
            }

            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "TaskFragment parent info changed name=%s parentTaskId=%d",
                    task.getName(), taskId);
            mLastSentTaskFragmentParentInfos.put(taskId, new TaskFragmentParentInfo(parentInfo));
            return new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED)
                    .setTaskId(taskId)
                    .setTaskFragmentParentInfo(parentInfo);
        }

        @NonNull
        TaskFragmentTransaction.Change prepareTaskFragmentError(
                @Nullable IBinder errorCallbackToken, @Nullable TaskFragment taskFragment,
                @TaskFragmentOperation.OperationType int opType, @NonNull Throwable exception) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Sending TaskFragment error exception=%s", exception.toString());
            final TaskFragmentInfo info =
                    taskFragment != null ? taskFragment.getTaskFragmentInfo() : null;
            final Bundle errorBundle = putErrorInfoInBundle(exception, info, opType);
            return new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_ERROR)
                    .setErrorCallbackToken(errorCallbackToken)
                    .setErrorBundle(errorBundle);
        }

        @Nullable
        TaskFragmentTransaction.Change prepareActivityReparentedToTask(
                @NonNull ActivityRecord activity) {
            if (activity.finishing) {
                Slog.d(TAG, "Reparent activity=" + activity.token + " is finishing");
                return null;
            }
            final Task task = activity.getTask();
            if (task == null || task.effectiveUid != mOrganizerUid) {
                Slog.d(TAG, "Reparent activity=" + activity.token
                        + " is not in a task belong to the organizer app.");
                return null;
            }
            if (task.isAllowedToEmbedActivity(activity, mOrganizerUid) != EMBEDDING_ALLOWED
                    || !task.isAllowedToEmbedActivityInTrustedMode(activity, mOrganizerUid)) {
                Slog.d(TAG, "Reparent activity=" + activity.token
                        + " is not allowed to be embedded in trusted mode.");
                return null;
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
            return new TaskFragmentTransaction.Change(TYPE_ACTIVITY_REPARENTED_TO_TASK)
                    .setTaskId(task.mTaskId)
                    .setActivityIntent(trimIntent(activity.intent))
                    .setActivityToken(activityToken);
        }

        void dispatchTransaction(@NonNull TaskFragmentTransaction transaction) {
            if (transaction.isEmpty()) {
                return;
            }
            try {
                mOrganizer.onTransactionReady(transaction);
            } catch (RemoteException e) {
                Slog.d(TAG, "Exception sending TaskFragmentTransaction", e);
                return;
            }
            onTransactionStarted(transaction.getTransactionToken());
        }

        /** Called when the transaction is sent to the organizer. */
        void onTransactionStarted(@NonNull IBinder transactionToken) {
            if (!mWindowOrganizerController.getTransitionController().isCollecting()) {
                return;
            }
            final int transitionId = mWindowOrganizerController.getTransitionController()
                    .getCollectingTransitionId();
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Defer transition id=%d for TaskFragmentTransaction=%s", transitionId,
                    transactionToken);
            mDeferredTransitions.put(transactionToken, transitionId);
            mWindowOrganizerController.getTransitionController().deferTransitionReady();
        }

        /** Called when the transaction is finished. */
        void onTransactionFinished(@NonNull IBinder transactionToken) {
            if (!mDeferredTransitions.containsKey(transactionToken)) {
                return;
            }
            final int transitionId = mDeferredTransitions.remove(transactionToken);
            if (!mWindowOrganizerController.getTransitionController().isCollecting()
                    || mWindowOrganizerController.getTransitionController()
                    .getCollectingTransitionId() != transitionId) {
                // This can happen when the transition is timeout or abort.
                ProtoLog.w(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "Deferred transition id=%d has been continued before the"
                                + " TaskFragmentTransaction=%s is finished",
                        transitionId, transactionToken);
                return;
            }
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "Continue transition id=%d for TaskFragmentTransaction=%s", transitionId,
                    transactionToken);
            mWindowOrganizerController.getTransitionController().continueTransitionReady();
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
    public void registerOrganizer(@NonNull ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register task fragment organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            if (isOrganizerRegistered(organizer)) {
                throw new IllegalStateException(
                        "Replacing existing organizer currently unsupported");
            }
            mTaskFragmentOrganizerState.put(organizer.asBinder(),
                    new TaskFragmentOrganizerState(organizer, pid, uid));
            mPendingTaskFragmentEvents.put(organizer.asBinder(), new ArrayList<>());
        }
    }

    @Override
    public void unregisterOrganizer(@NonNull ITaskFragmentOrganizer organizer) {
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
    public void registerRemoteAnimations(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull RemoteAnimationDefinition definition) {
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
            if (organizerState.mRemoteAnimationDefinition != null) {
                throw new IllegalStateException(
                        "The organizer has already registered remote animations="
                                + organizerState.mRemoteAnimationDefinition);
            }

            definition.setCallingPidUid(pid, uid);
            organizerState.mRemoteAnimationDefinition = definition;
        }
    }

    @Override
    public void unregisterRemoteAnimations(@NonNull ITaskFragmentOrganizer organizer) {
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

            organizerState.mRemoteAnimationDefinition = null;
        }
    }

    @Override
    public void onTransactionHandled(@NonNull IBinder transactionToken,
            @NonNull WindowContainerTransaction wct,
            @WindowManager.TransitionType int transitionType, boolean shouldApplyIndependently) {
        // Keep the calling identity to avoid unsecure change.
        synchronized (mGlobalLock) {
            if (isValidTransaction(wct)) {
                applyTransaction(wct, transitionType, shouldApplyIndependently);
            }
            // Even if the transaction is empty, we still need to invoke #onTransactionFinished
            // unless the organizer has been unregistered.
            final ITaskFragmentOrganizer organizer = wct.getTaskFragmentOrganizer();
            final TaskFragmentOrganizerState state = organizer != null
                    ? mTaskFragmentOrganizerState.get(organizer.asBinder())
                    : null;
            if (state != null) {
                state.onTransactionFinished(transactionToken);
            }
        }
    }

    @Override
    public void applyTransaction(@NonNull WindowContainerTransaction wct,
            @WindowManager.TransitionType int transitionType, boolean shouldApplyIndependently) {
        // Keep the calling identity to avoid unsecure change.
        synchronized (mGlobalLock) {
            if (!isValidTransaction(wct)) {
                return;
            }
            mWindowOrganizerController.applyTaskFragmentTransactionLocked(wct, transitionType,
                    shouldApplyIndependently);
        }
    }

    /**
     * Gets the {@link RemoteAnimationDefinition} set on the given organizer if exists. Returns
     * {@code null} if it doesn't.
     */
    @Nullable
    public RemoteAnimationDefinition getRemoteAnimationDefinition(
            @NonNull ITaskFragmentOrganizer organizer) {
        synchronized (mGlobalLock) {
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            return organizerState != null
                    ? organizerState.mRemoteAnimationDefinition
                    : null;
        }
    }

    int getTaskFragmentOrganizerUid(@NonNull ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        return state.mOrganizerUid;
    }

    void onTaskFragmentAppeared(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragment taskFragment) {
        if (taskFragment.mTaskFragmentVanishedSent) {
            return;
        }
        if (taskFragment.getTask() == null) {
            Slog.w(TAG, "onTaskFragmentAppeared failed because it is not attached tf="
                    + taskFragment);
            return;
        }
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        if (!state.addTaskFragment(taskFragment)) {
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_APPEARED);
        if (pendingEvent == null) {
            addPendingEvent(new PendingTaskFragmentEvent.Builder(
                    PendingTaskFragmentEvent.EVENT_APPEARED, organizer)
                    .setTaskFragment(taskFragment)
                    .build());
        }
    }

    void onTaskFragmentInfoChanged(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragment taskFragment) {
        if (taskFragment.mTaskFragmentVanishedSent) {
            return;
        }
        validateAndGetState(organizer);
        if (!taskFragment.mTaskFragmentAppearedSent) {
            // Skip if TaskFragment still not appeared.
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getLastPendingLifecycleEvent(taskFragment);
        if (pendingEvent == null) {
            pendingEvent = new PendingTaskFragmentEvent.Builder(
                    PendingTaskFragmentEvent.EVENT_INFO_CHANGED, organizer)
                    .setTaskFragment(taskFragment)
                    .build();
        } else {
            // Remove and add for re-ordering.
            removePendingEvent(pendingEvent);
            // Reset the defer time when TaskFragment is changed, so that it can check again if
            // the event should be sent to the organizer, for example the TaskFragment may become
            // empty.
            pendingEvent.mDeferTime = 0;
        }
        addPendingEvent(pendingEvent);
    }

    void onTaskFragmentVanished(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragment taskFragment) {
        if (taskFragment.mTaskFragmentVanishedSent) {
            return;
        }
        taskFragment.mTaskFragmentVanishedSent = true;
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        final List<PendingTaskFragmentEvent> pendingEvents = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        // Remove any pending events since this TaskFragment is being removed.
        for (int i = pendingEvents.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = pendingEvents.get(i);
            if (taskFragment == event.mTaskFragment) {
                pendingEvents.remove(i);
            }
        }
        addPendingEvent(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_VANISHED, organizer)
                .setTaskFragment(taskFragment)
                .build());
        state.removeTaskFragment(taskFragment);
        // Make sure the vanished event will be dispatched if there are no other changes.
        mAtmService.mWindowManager.mWindowPlacerLocked.requestTraversal();
    }

    void onTaskFragmentError(@NonNull ITaskFragmentOrganizer organizer,
            @Nullable IBinder errorCallbackToken, @Nullable TaskFragment taskFragment,
            @TaskFragmentOperation.OperationType int opType, @NonNull Throwable exception) {
        if (taskFragment != null && taskFragment.mTaskFragmentVanishedSent) {
            return;
        }
        validateAndGetState(organizer);
        Slog.w(TAG, "onTaskFragmentError ", exception);
        addPendingEvent(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_ERROR, organizer)
                .setErrorCallbackToken(errorCallbackToken)
                .setTaskFragment(taskFragment)
                .setException(exception)
                .setOpType(opType)
                .build());
        // Make sure the error event will be dispatched if there are no other changes.
        mAtmService.mWindowManager.mWindowPlacerLocked.requestTraversal();
    }

    void onActivityReparentedToTask(@NonNull ActivityRecord activity) {
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
        if (!isOrganizerRegistered(organizer)) {
            Slog.w(TAG, "The last TaskFragmentOrganizer no longer exists");
            return;
        }
        addPendingEvent(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_ACTIVITY_REPARENTED_TO_TASK, organizer)
                .setActivity(activity)
                .build());
    }

    void onTaskFragmentParentInfoChanged(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull Task task) {
        validateAndGetState(organizer);
        final PendingTaskFragmentEvent pendingEvent = getLastPendingParentInfoChangedEvent(
                organizer, task);
        if (pendingEvent == null) {
            addPendingEvent(new PendingTaskFragmentEvent.Builder(
                    PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED, organizer)
                    .setTask(task)
                    .build());
        }
    }

    @Nullable
    private PendingTaskFragmentEvent getLastPendingParentInfoChangedEvent(
            @NonNull ITaskFragmentOrganizer organizer, @NonNull Task task) {
        final List<PendingTaskFragmentEvent> events = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        for (int i = events.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = events.get(i);
            if (task == event.mTask
                    && event.mEventType == PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED) {
                return event;
            }
        }
        return null;
    }

    private void addPendingEvent(@NonNull PendingTaskFragmentEvent event) {
        mPendingTaskFragmentEvents.get(event.mTaskFragmentOrg.asBinder()).add(event);
    }

    private void removePendingEvent(@NonNull PendingTaskFragmentEvent event) {
        mPendingTaskFragmentEvents.get(event.mTaskFragmentOrg.asBinder()).remove(event);
    }

    private boolean isOrganizerRegistered(@NonNull ITaskFragmentOrganizer organizer) {
        return mTaskFragmentOrganizerState.containsKey(organizer.asBinder());
    }

    private void removeOrganizer(@NonNull ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = mTaskFragmentOrganizerState.get(
                organizer.asBinder());
        if (state == null) {
            Slog.w(TAG, "The organizer has already been removed.");
            return;
        }
        // Remove any pending event of this organizer first because state.dispose() may trigger
        // event dispatch as result of surface placement.
        mPendingTaskFragmentEvents.remove(organizer.asBinder());
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
    @NonNull
    private TaskFragmentOrganizerState validateAndGetState(
            @NonNull ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(organizer.asBinder());
        if (state == null) {
            throw new IllegalArgumentException(
                    "TaskFragmentOrganizer has not been registered. Organizer=" + organizer);
        }
        return state;
    }

    boolean isValidTransaction(@NonNull WindowContainerTransaction t) {
        if (t.isEmpty()) {
            return false;
        }
        final ITaskFragmentOrganizer organizer = t.getTaskFragmentOrganizer();
        if (t.getTaskFragmentOrganizer() == null || !isOrganizerRegistered(organizer)) {
            // Transaction from an unregistered organizer should not be applied. This can happen
            // when the organizer process died before the transaction is applied.
            Slog.e(TAG, "Caller organizer=" + organizer + " is no longer registered");
            return false;
        }
        return true;
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
        static final int EVENT_ACTIVITY_REPARENTED_TO_TASK = 5;

        @IntDef(prefix = "EVENT_", value = {
                EVENT_APPEARED,
                EVENT_VANISHED,
                EVENT_INFO_CHANGED,
                EVENT_PARENT_INFO_CHANGED,
                EVENT_ERROR,
                EVENT_ACTIVITY_REPARENTED_TO_TASK
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
        @Nullable
        private final Task mTask;
        // Set when the event is deferred due to the host task is invisible. The defer time will
        // be the last active time of the host task.
        private long mDeferTime;
        @TaskFragmentOperation.OperationType
        private int mOpType;

        private PendingTaskFragmentEvent(@EventType int eventType,
                ITaskFragmentOrganizer taskFragmentOrg,
                @Nullable TaskFragment taskFragment,
                @Nullable IBinder errorCallbackToken,
                @Nullable Throwable exception,
                @Nullable ActivityRecord activity,
                @Nullable Task task,
                @TaskFragmentOperation.OperationType int opType) {
            mEventType = eventType;
            mTaskFragmentOrg = taskFragmentOrg;
            mTaskFragment = taskFragment;
            mErrorCallbackToken = errorCallbackToken;
            mException = exception;
            mActivity = activity;
            mTask = task;
            mOpType = opType;
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
            @Nullable
            private Task mTask;
            @TaskFragmentOperation.OperationType
            private int mOpType;

            Builder(@EventType int eventType, @NonNull ITaskFragmentOrganizer taskFragmentOrg) {
                mEventType = eventType;
                mTaskFragmentOrg = requireNonNull(taskFragmentOrg);
            }

            Builder setTaskFragment(@Nullable TaskFragment taskFragment) {
                mTaskFragment = taskFragment;
                return this;
            }

            Builder setErrorCallbackToken(@Nullable IBinder errorCallbackToken) {
                mErrorCallbackToken = errorCallbackToken;
                return this;
            }

            Builder setException(@NonNull Throwable exception) {
                mException = requireNonNull(exception);
                return this;
            }

            Builder setActivity(@NonNull ActivityRecord activity) {
                mActivity = requireNonNull(activity);
                return this;
            }

            Builder setTask(@NonNull Task task) {
                mTask = requireNonNull(task);
                return this;
            }

            Builder setOpType(@TaskFragmentOperation.OperationType int opType) {
                mOpType = opType;
                return this;
            }

            PendingTaskFragmentEvent build() {
                return new PendingTaskFragmentEvent(mEventType, mTaskFragmentOrg, mTaskFragment,
                        mErrorCallbackToken, mException, mActivity, mTask, mOpType);
            }
        }
    }

    @Nullable
    private PendingTaskFragmentEvent getLastPendingLifecycleEvent(@NonNull TaskFragment tf) {
        final ITaskFragmentOrganizer organizer = tf.getTaskFragmentOrganizer();
        final List<PendingTaskFragmentEvent> events = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        for (int i = events.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = events.get(i);
            if (tf == event.mTaskFragment && event.isLifecycleEvent()) {
                return event;
            }
        }
        return null;
    }

    @Nullable
    private PendingTaskFragmentEvent getPendingTaskFragmentEvent(@NonNull TaskFragment taskFragment,
            int type) {
        final ITaskFragmentOrganizer organizer = taskFragment.getTaskFragmentOrganizer();
        final List<PendingTaskFragmentEvent> events = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        for (int i = events.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = events.get(i);
            if (taskFragment == event.mTaskFragment && type == event.mEventType) {
                return event;
            }
        }
        return null;
    }

    void dispatchPendingEvents() {
        if (mAtmService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()
                || mPendingTaskFragmentEvents.isEmpty()) {
            return;
        }
        final int organizerNum = mPendingTaskFragmentEvents.size();
        for (int i = 0; i < organizerNum; i++) {
            final TaskFragmentOrganizerState state =
                    mTaskFragmentOrganizerState.get(mPendingTaskFragmentEvents.keyAt(i));
            dispatchPendingEvents(state, mPendingTaskFragmentEvents.valueAt(i));
        }
    }

    private void dispatchPendingEvents(@NonNull TaskFragmentOrganizerState state,
            @NonNull List<PendingTaskFragmentEvent> pendingEvents) {
        if (pendingEvents.isEmpty()) {
            return;
        }
        if (shouldDeferPendingEvents(state, pendingEvents)) {
            return;
        }
        mTmpTaskSet.clear();
        final int numEvents = pendingEvents.size();
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        for (int i = 0; i < numEvents; i++) {
            final PendingTaskFragmentEvent event = pendingEvents.get(i);
            if (event.mEventType == PendingTaskFragmentEvent.EVENT_APPEARED
                    || event.mEventType == PendingTaskFragmentEvent.EVENT_INFO_CHANGED) {
                final Task task = event.mTaskFragment.getTask();
                if (mTmpTaskSet.add(task)) {
                    // Make sure the organizer know about the Task config.
                    transaction.addChange(prepareChange(new PendingTaskFragmentEvent.Builder(
                            PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED, state.mOrganizer)
                            .setTask(task)
                            .build()));
                }
            }
            transaction.addChange(prepareChange(event));
        }
        mTmpTaskSet.clear();
        state.dispatchTransaction(transaction);
        pendingEvents.clear();
    }

    /**
     * Whether or not to defer sending the events to the organizer to avoid waking the app process
     * when it is in background. We want to either send all events or none to avoid inconsistency.
     */
    private boolean shouldDeferPendingEvents(@NonNull TaskFragmentOrganizerState state,
            @NonNull List<PendingTaskFragmentEvent> pendingEvents) {
        final ArrayList<Task> visibleTasks = new ArrayList<>();
        final ArrayList<Task> invisibleTasks = new ArrayList<>();
        for (int i = 0, n = pendingEvents.size(); i < n; i++) {
            final PendingTaskFragmentEvent event = pendingEvents.get(i);
            if (event.mEventType != PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED
                    && event.mEventType != PendingTaskFragmentEvent.EVENT_INFO_CHANGED
                    && event.mEventType != PendingTaskFragmentEvent.EVENT_APPEARED) {
                // Send events for any other types.
                return false;
            }

            // Check if we should send the event given the Task visibility and events.
            final Task task;
            if (event.mEventType == PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED) {
                task = event.mTask;
            } else {
                task = event.mTaskFragment.getTask();
            }
            if (task.lastActiveTime > event.mDeferTime
                    && isTaskVisible(task, visibleTasks, invisibleTasks)) {
                // Send events when the app has at least one visible Task.
                return false;
            } else if (shouldSendEventWhenTaskInvisible(task, state, event)) {
                // Sent events even if the Task is invisible.
                return false;
            }

            // Defer sending events to the organizer until the host task is active (visible) again.
            event.mDeferTime = task.lastActiveTime;
        }
        // Defer for invisible Task.
        return true;
    }

    private static boolean isTaskVisible(@NonNull Task task,
            @NonNull ArrayList<Task> knownVisibleTasks,
            @NonNull ArrayList<Task> knownInvisibleTasks) {
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

    private boolean shouldSendEventWhenTaskInvisible(@NonNull Task task,
            @NonNull TaskFragmentOrganizerState state,
            @NonNull PendingTaskFragmentEvent event) {
        final TaskFragmentParentInfo lastParentInfo = state.mLastSentTaskFragmentParentInfos
                .get(task.mTaskId);
        if (lastParentInfo == null || lastParentInfo.isVisible()) {
            // When the Task was visible, or when there was no Task info changed sent (in which case
            // the organizer will consider it as visible by default), always send the event to
            // update the Task visibility.
            return true;
        }
        if (event.mEventType == PendingTaskFragmentEvent.EVENT_INFO_CHANGED) {
            // Send info changed if the TaskFragment is becoming empty/non-empty so the
            // organizer can choose whether or not to remove the TaskFragment.
            final TaskFragmentInfo lastInfo = state.mLastSentTaskFragmentInfos
                    .get(event.mTaskFragment);
            final boolean isEmpty = event.mTaskFragment.getNonFinishingActivityCount() == 0;
            return lastInfo == null || lastInfo.isEmpty() != isEmpty;
        }
        return false;
    }

    void dispatchPendingInfoChangedEvent(@NonNull TaskFragment taskFragment) {
        final PendingTaskFragmentEvent event = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_INFO_CHANGED);
        if (event == null) {
            return;
        }

        final ITaskFragmentOrganizer organizer = taskFragment.getTaskFragmentOrganizer();
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        // Make sure the organizer know about the Task config.
        transaction.addChange(prepareChange(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED, organizer)
                .setTask(taskFragment.getTask())
                .build()));
        transaction.addChange(prepareChange(event));
        state.dispatchTransaction(transaction);
        mPendingTaskFragmentEvents.get(organizer.asBinder()).remove(event);
    }

    @Nullable
    private TaskFragmentTransaction.Change prepareChange(
            @NonNull PendingTaskFragmentEvent event) {
        final ITaskFragmentOrganizer taskFragmentOrg = event.mTaskFragmentOrg;
        final TaskFragment taskFragment = event.mTaskFragment;
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(taskFragmentOrg.asBinder());
        if (state == null) {
            return null;
        }
        switch (event.mEventType) {
            case PendingTaskFragmentEvent.EVENT_APPEARED:
                return state.prepareTaskFragmentAppeared(taskFragment);
            case PendingTaskFragmentEvent.EVENT_VANISHED:
                return state.prepareTaskFragmentVanished(taskFragment);
            case PendingTaskFragmentEvent.EVENT_INFO_CHANGED:
                return state.prepareTaskFragmentInfoChanged(taskFragment);
            case PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED:
                return state.prepareTaskFragmentParentInfoChanged(event.mTask);
            case PendingTaskFragmentEvent.EVENT_ERROR:
                return state.prepareTaskFragmentError(event.mErrorCallbackToken, taskFragment,
                        event.mOpType, event.mException);
            case PendingTaskFragmentEvent.EVENT_ACTIVITY_REPARENTED_TO_TASK:
                return state.prepareActivityReparentedToTask(event.mActivity);
            default:
                throw new IllegalArgumentException("Unknown TaskFragmentEvent=" + event.mEventType);
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
            return taskFragment != null && taskFragment.isEmbeddedWithBoundsOverride();
        }
    }

    /**
     * Trims the given Intent to only those that are needed to for embedding rules. This helps to
     * make it safer for cross-uid embedding even if we only send the Intent for trusted embedding.
     */
    private static Intent trimIntent(@NonNull Intent intent) {
        return new Intent()
                .setComponent(intent.getComponent())
                .setPackage(intent.getPackage())
                .setAction(intent.getAction());
    }
}
