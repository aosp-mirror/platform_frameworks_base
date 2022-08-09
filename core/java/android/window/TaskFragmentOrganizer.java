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

package android.window;

import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.SparseArray;
import android.view.RemoteAnimationDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Interface for WindowManager to delegate control of {@code TaskFragment}.
 * @hide
 */
@TestApi
public class TaskFragmentOrganizer extends WindowOrganizer {

    /**
     * Key to the exception in {@link Bundle} in {@link ITaskFragmentOrganizer#onTaskFragmentError}.
     */
    private static final String KEY_ERROR_CALLBACK_EXCEPTION = "fragment_exception";
    private static final String KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO = "task_fragment_info";
    private static final String KEY_ERROR_CALLBACK_OP_TYPE = "operation_type";

    /**
     * Creates a {@link Bundle} with an exception, operation type and TaskFragmentInfo (if any)
     * that can be passed to {@link ITaskFragmentOrganizer#onTaskFragmentError}.
     * @hide
     */
    public static @NonNull Bundle putErrorInfoInBundle(@NonNull Throwable exception,
            @Nullable TaskFragmentInfo info, int opType) {
        final Bundle errorBundle = new Bundle();
        errorBundle.putSerializable(KEY_ERROR_CALLBACK_EXCEPTION, exception);
        if (info != null) {
            errorBundle.putParcelable(KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO, info);
        }
        errorBundle.putInt(KEY_ERROR_CALLBACK_OP_TYPE, opType);
        return errorBundle;
    }

    /**
     * Callbacks from WM Core are posted on this executor.
     */
    private final Executor mExecutor;

    // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next release.
    /** Map from Task id to client tokens of TaskFragments in the Task. */
    private final SparseArray<List<IBinder>> mTaskIdToFragmentTokens = new SparseArray<>();
    /** Map from Task id to Task configuration. */
    private final SparseArray<Configuration> mTaskIdToConfigurations = new SparseArray<>();

    public TaskFragmentOrganizer(@NonNull Executor executor) {
        mExecutor = executor;
    }

    /**
     * Gets the executor to run callbacks on.
     */
    @NonNull
    public Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Registers a TaskFragmentOrganizer to manage TaskFragments.
     */
    @CallSuper
    public void registerOrganizer() {
        try {
            getController().registerOrganizer(mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a previously registered TaskFragmentOrganizer.
     */
    @CallSuper
    public void unregisterOrganizer() {
        try {
            getController().unregisterOrganizer(mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers remote animations per transition type for the organizer. It will override the
     * animations if the transition only contains windows that belong to the organized
     * TaskFragments in the given Task.
     *
     * @param taskId overrides if the transition only contains windows belonging to this Task.
     * @hide
     */
    @CallSuper
    public void registerRemoteAnimations(int taskId,
            @NonNull RemoteAnimationDefinition definition) {
        try {
            getController().registerRemoteAnimations(mInterface, taskId, definition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters remote animations per transition type for the organizer.
     * @hide
     */
    @CallSuper
    public void unregisterRemoteAnimations(int taskId) {
        try {
            getController().unregisterRemoteAnimations(mInterface, taskId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the server that the organizer has finished handling the given transaction. The
     * server should apply the given {@link WindowContainerTransaction} for the necessary changes.
     *
     * @param transactionToken  {@link TaskFragmentTransaction#getTransactionToken()} from
     *                          {@link #onTransactionReady(TaskFragmentTransaction)}
     * @param wct               {@link WindowContainerTransaction} that the server should apply for
     *                          update of the transaction.
     * @see com.android.server.wm.WindowOrganizerController#enforceTaskPermission for permission
     * requirement.
     * @hide
     */
    public void onTransactionHandled(@NonNull IBinder transactionToken,
            @NonNull WindowContainerTransaction wct) {
        wct.setTaskFragmentOrganizer(mInterface);
        try {
            getController().onTransactionHandled(mInterface, transactionToken, wct);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when a TaskFragment is created and organized by this organizer.
     *
     * @param taskFragmentInfo  Info of the TaskFragment that is created.
     * @deprecated Use {@link #onTaskFragmentAppeared(WindowContainerTransaction, TaskFragmentInfo)}
     *             instead.
     */
    @Deprecated
    public void onTaskFragmentAppeared(@NonNull TaskFragmentInfo taskFragmentInfo) {}

    /**
     * Called when a TaskFragment is created and organized by this organizer.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed. No
     *              need to call {@link #applyTransaction} as it will be applied by the caller.
     * @param taskFragmentInfo  Info of the TaskFragment that is created.
     */
    public void onTaskFragmentAppeared(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentInfo taskFragmentInfo) {
        // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next release.
        onTaskFragmentAppeared(taskFragmentInfo);
    }

    /**
     * Called when the status of an organized TaskFragment is changed.
     *
     * @param taskFragmentInfo  Info of the TaskFragment that is changed.
     * @deprecated Use {@link #onTaskFragmentInfoChanged(WindowContainerTransaction,
     *             TaskFragmentInfo)} instead.
     */
    @Deprecated
    public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {}

    /**
     * Called when the status of an organized TaskFragment is changed.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed. No
     *              need to call {@link #applyTransaction} as it will be applied by the caller.
     * @param taskFragmentInfo  Info of the TaskFragment that is changed.
     */
    public void onTaskFragmentInfoChanged(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentInfo taskFragmentInfo) {
        // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next release.
        onTaskFragmentInfoChanged(taskFragmentInfo);
    }

    /**
     * Called when an organized TaskFragment is removed.
     *
     * @param taskFragmentInfo  Info of the TaskFragment that is removed.
     *  @deprecated Use {@link #onTaskFragmentVanished(WindowContainerTransaction,
     *              TaskFragmentInfo)} instead.
     */
    @Deprecated
    public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {}

    /**
     * Called when an organized TaskFragment is removed.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed. No
     *              need to call {@link #applyTransaction} as it will be applied by the caller.
     * @param taskFragmentInfo  Info of the TaskFragment that is removed.
     */
    public void onTaskFragmentVanished(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentInfo taskFragmentInfo) {
        // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next release.
        onTaskFragmentVanished(taskFragmentInfo);
    }

    /**
     * Called when the parent leaf Task of organized TaskFragments is changed.
     * When the leaf Task is changed, the organizer may want to update the TaskFragments in one
     * transaction.
     *
     * For case like screen size change, it will trigger onTaskFragmentParentInfoChanged with new
     * Task bounds, but may not trigger onTaskFragmentInfoChanged because there can be an override
     * bounds.
     *
     * @param fragmentToken The parent Task this TaskFragment is changed.
     * @param parentConfig  Config of the parent Task.
     * @deprecated Use {@link #onTaskFragmentParentInfoChanged(WindowContainerTransaction, int,
     *             Configuration)} instead.
     */
    @Deprecated
    public void onTaskFragmentParentInfoChanged(
            @NonNull IBinder fragmentToken, @NonNull Configuration parentConfig) {}

    /**
     * Called when the parent leaf Task of organized TaskFragments is changed.
     * When the leaf Task is changed, the organizer may want to update the TaskFragments in one
     * transaction.
     *
     * For case like screen size change, it will trigger onTaskFragmentParentInfoChanged with new
     * Task bounds, but may not trigger onTaskFragmentInfoChanged because there can be an override
     * bounds.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed. No
     *              need to call {@link #applyTransaction} as it will be applied by the caller.
     * @param taskId    Id of the parent Task that is changed.
     * @param parentConfig  Config of the parent Task.
     */
    public void onTaskFragmentParentInfoChanged(@NonNull WindowContainerTransaction wct, int taskId,
            @NonNull Configuration parentConfig) {
        // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next release.
        final List<IBinder> tokens = mTaskIdToFragmentTokens.get(taskId);
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        for (int i = tokens.size() - 1; i >= 0; i--) {
            onTaskFragmentParentInfoChanged(tokens.get(i), parentConfig);
        }
    }

    /**
     * Called when the {@link WindowContainerTransaction} created with
     * {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)} failed on the server side.
     *
     * @param errorCallbackToken    token set in
     *                             {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)}
     * @param exception             exception from the server side.
     * @deprecated Use {@link #onTaskFragmentError(WindowContainerTransaction, IBinder,
     *             TaskFragmentInfo, int, Throwable)} instead.
     */
    @Deprecated
    public void onTaskFragmentError(
            @NonNull IBinder errorCallbackToken, @NonNull Throwable exception) {}

    /**
     * Called when the {@link WindowContainerTransaction} created with
     * {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)} failed on the server side.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed. No
     *              need to call {@link #applyTransaction} as it will be applied by the caller.
     * @param errorCallbackToken    token set in
     *                             {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)}
     * @param taskFragmentInfo  The {@link TaskFragmentInfo}. This could be {@code null} if no
     *                          TaskFragment created.
     * @param opType            The {@link WindowContainerTransaction.HierarchyOp} of the failed
     *                          transaction operation.
     * @param exception             exception from the server side.
     */
    public void onTaskFragmentError(@NonNull WindowContainerTransaction wct,
            @NonNull IBinder errorCallbackToken, @Nullable TaskFragmentInfo taskFragmentInfo,
            int opType, @NonNull Throwable exception) {
        // Doing so to keep compatibility. This will be removed in the next release.
        onTaskFragmentError(errorCallbackToken, exception);
    }

    /**
     * Called when an Activity is reparented to the Task with organized TaskFragment. For example,
     * when an Activity enters and then exits Picture-in-picture, it will be reparented back to its
     * original Task. In this case, we need to notify the organizer so that it can check if the
     * Activity matches any split rule.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed. No
     *              need to call {@link #applyTransaction} as it will be applied by the caller.
     * @param taskId            The Task that the activity is reparented to.
     * @param activityIntent    The intent that the activity is original launched with.
     * @param activityToken     If the activity belongs to the same process as the organizer, this
     *                          will be the actual activity token; if the activity belongs to a
     *                          different process, the server will generate a temporary token that
     *                          the organizer can use to reparent the activity through
     *                          {@link WindowContainerTransaction} if needed.
     */
    public void onActivityReparentedToTask(@NonNull WindowContainerTransaction wct,
            int taskId, @NonNull Intent activityIntent, @NonNull IBinder activityToken) {}

    /**
     * Called when the transaction is ready so that the organizer can update the TaskFragments based
     * on the changes in transaction.
     * @hide
     */
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
        for (TaskFragmentTransaction.Change change : changes) {
            final int taskId = change.getTaskId();
            switch (change.getType()) {
                case TYPE_TASK_FRAGMENT_APPEARED:
                    // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next
                    // release.
                    if (!mTaskIdToFragmentTokens.contains(taskId)) {
                        mTaskIdToFragmentTokens.put(taskId, new ArrayList<>());
                    }
                    mTaskIdToFragmentTokens.get(taskId).add(change.getTaskFragmentToken());
                    onTaskFragmentParentInfoChanged(change.getTaskFragmentToken(),
                            mTaskIdToConfigurations.get(taskId));

                    onTaskFragmentAppeared(wct, change.getTaskFragmentInfo());
                    break;
                case TYPE_TASK_FRAGMENT_INFO_CHANGED:
                    onTaskFragmentInfoChanged(wct, change.getTaskFragmentInfo());
                    break;
                case TYPE_TASK_FRAGMENT_VANISHED:
                    // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next
                    // release.
                    if (mTaskIdToFragmentTokens.contains(taskId)) {
                        final List<IBinder> tokens = mTaskIdToFragmentTokens.get(taskId);
                        tokens.remove(change.getTaskFragmentToken());
                        if (tokens.isEmpty()) {
                            mTaskIdToFragmentTokens.remove(taskId);
                            mTaskIdToConfigurations.remove(taskId);
                        }
                    }

                    onTaskFragmentVanished(wct, change.getTaskFragmentInfo());
                    break;
                case TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED:
                    // TODO(b/240519866): doing so to keep CTS compatibility. Remove in the next
                    // release.
                    mTaskIdToConfigurations.put(taskId, change.getTaskConfiguration());

                    onTaskFragmentParentInfoChanged(wct, taskId, change.getTaskConfiguration());
                    break;
                case TYPE_TASK_FRAGMENT_ERROR:
                    final Bundle errorBundle = change.getErrorBundle();
                    onTaskFragmentError(
                            wct,
                            change.getErrorCallbackToken(),
                            errorBundle.getParcelable(
                                    KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO, TaskFragmentInfo.class),
                            errorBundle.getInt(KEY_ERROR_CALLBACK_OP_TYPE),
                            errorBundle.getSerializable(KEY_ERROR_CALLBACK_EXCEPTION,
                                    java.lang.Throwable.class));
                    break;
                case TYPE_ACTIVITY_REPARENTED_TO_TASK:
                    onActivityReparentedToTask(
                            wct,
                            change.getTaskId(),
                            change.getActivityIntent(),
                            change.getActivityToken());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unknown TaskFragmentEvent=" + change.getType());
            }
        }

        // Notify the server, and the server should apply the WindowContainerTransaction.
        onTransactionHandled(transaction.getTransactionToken(), wct);
    }

    @Override
    public void applyTransaction(@NonNull WindowContainerTransaction t) {
        t.setTaskFragmentOrganizer(mInterface);
        super.applyTransaction(t);
    }

    // Suppress the lint because it is not a registration method.
    @SuppressWarnings("ExecutorRegistration")
    @Override
    public int applySyncTransaction(@NonNull WindowContainerTransaction t,
            @NonNull WindowContainerTransactionCallback callback) {
        t.setTaskFragmentOrganizer(mInterface);
        return super.applySyncTransaction(t, callback);
    }

    private final ITaskFragmentOrganizer mInterface = new ITaskFragmentOrganizer.Stub() {
        @Override
        public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
            mExecutor.execute(() -> TaskFragmentOrganizer.this.onTransactionReady(transaction));
        }
    };

    private final TaskFragmentOrganizerToken mToken = new TaskFragmentOrganizerToken(mInterface);

    @NonNull
    public TaskFragmentOrganizerToken getOrganizerToken() {
        return mToken;
    }

    private ITaskFragmentOrganizerController getController() {
        try {
            return getWindowOrganizerController().getTaskFragmentOrganizerController();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Checks if an activity organized by a {@link android.window.TaskFragmentOrganizer} and
     * only occupies a portion of Task bounds.
     * @hide
     */
    public boolean isActivityEmbedded(@NonNull IBinder activityToken) {
        try {
            return getController().isActivityEmbedded(activityToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
