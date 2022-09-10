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

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.WindowConfiguration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Interface for WindowManager to delegate control of {@code TaskFragment}.
 * @hide
 */
@TestApi
public class TaskFragmentOrganizer extends WindowOrganizer {

    /**
     * Key to the {@link Throwable} in {@link TaskFragmentTransaction.Change#getErrorBundle()}.
     */
    public static final String KEY_ERROR_CALLBACK_THROWABLE = "fragment_throwable";

    /**
     * Key to the {@link TaskFragmentInfo} in
     * {@link TaskFragmentTransaction.Change#getErrorBundle()}.
     */
    public static final String KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO = "task_fragment_info";

    /**
     * Key to the {@link WindowContainerTransaction.HierarchyOp} in
     * {@link TaskFragmentTransaction.Change#getErrorBundle()}.
     */
    public static final String KEY_ERROR_CALLBACK_OP_TYPE = "operation_type";

    /**
     * Creates a {@link Bundle} with an exception, operation type and TaskFragmentInfo (if any)
     * that can be passed to {@link ITaskFragmentOrganizer#onTaskFragmentError}.
     * @hide
     */
    public static @NonNull Bundle putErrorInfoInBundle(@NonNull Throwable exception,
            @Nullable TaskFragmentInfo info, int opType) {
        final Bundle errorBundle = new Bundle();
        errorBundle.putSerializable(KEY_ERROR_CALLBACK_THROWABLE, exception);
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
     * @param transitionType    {@link WindowManager.TransitionType} if it needs to start a
     *                          transition.
     * @param shouldApplyIndependently  If {@code true}, the {@code wct} will request a new
     *                                  transition, which will be queued until the sync engine is
     *                                  free if there is any other active sync. If {@code false},
     *                                  the {@code wct} will be directly applied to the active sync.
     * @see com.android.server.wm.WindowOrganizerController#enforceTaskFragmentOrganizerPermission
     * for permission enforcement.
     * @hide
     */
    public void onTransactionHandled(@NonNull IBinder transactionToken,
            @NonNull WindowContainerTransaction wct,
            @WindowManager.TransitionType int transitionType, boolean shouldApplyIndependently) {
        wct.setTaskFragmentOrganizer(mInterface);
        try {
            getController().onTransactionHandled(transactionToken, wct, transitionType,
                    shouldApplyIndependently);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Routes to {@link ITaskFragmentOrganizerController#applyTransaction} instead of
     * {@link IWindowOrganizerController#applyTransaction} for the different transition options.
     *
     * @see #applyTransaction(WindowContainerTransaction, int, boolean)
     */
    @Override
    public void applyTransaction(@NonNull WindowContainerTransaction wct) {
        // TODO(b/207070762) doing so to keep CTS compatibility. Remove in the next release.
        applyTransaction(wct, getTransitionType(wct), false /* shouldApplyIndependently */);
    }

    /**
     * Requests the server to apply the given {@link WindowContainerTransaction}.
     *
     * @param wct   {@link WindowContainerTransaction} to apply.
     * @param transitionType    {@link WindowManager.TransitionType} if it needs to start a
     *                          transition.
     * @param shouldApplyIndependently  If {@code true}, the {@code wct} will request a new
     *                                  transition, which will be queued until the sync engine is
     *                                  free if there is any other active sync. If {@code false},
     *                                  the {@code wct} will be directly applied to the active sync.
     * @see com.android.server.wm.WindowOrganizerController#enforceTaskFragmentOrganizerPermission
     * for permission enforcement.
     * @hide
     */
    public void applyTransaction(@NonNull WindowContainerTransaction wct,
            @WindowManager.TransitionType int transitionType, boolean shouldApplyIndependently) {
        if (wct.isEmpty()) {
            return;
        }
        wct.setTaskFragmentOrganizer(mInterface);
        try {
            getController().applyTransaction(wct, transitionType, shouldApplyIndependently);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the default {@link WindowManager.TransitionType} based on the requested
     * {@link WindowContainerTransaction}.
     * @hide
     */
    // TODO(b/207070762): let Extensions to set the transition type instead.
    @WindowManager.TransitionType
    public static int getTransitionType(@NonNull WindowContainerTransaction wct) {
        if (wct.isEmpty()) {
            return TRANSIT_NONE;
        }
        for (WindowContainerTransaction.Change change : wct.getChanges().values()) {
            if ((change.getWindowSetMask() & WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0) {
                // Treat as TRANSIT_CHANGE when there is TaskFragment resizing.
                return TRANSIT_CHANGE;
            }
        }
        boolean containsCreatingTaskFragment = false;
        boolean containsDeleteTaskFragment = false;
        final List<WindowContainerTransaction.HierarchyOp> ops = wct.getHierarchyOps();
        for (int i = ops.size() - 1; i >= 0; i--) {
            final int type = ops.get(i).getType();
            if (type == HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT) {
                // Treat as TRANSIT_CHANGE when there is activity reparent.
                return TRANSIT_CHANGE;
            }
            if (type == HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT) {
                containsCreatingTaskFragment = true;
            } else if (type == HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT) {
                containsDeleteTaskFragment = true;
            }
        }
        if (containsCreatingTaskFragment) {
            return TRANSIT_OPEN;
        }
        if (containsDeleteTaskFragment) {
            return TRANSIT_CLOSE;
        }

        // Use TRANSIT_CHANGE as default.
        return TRANSIT_CHANGE;
    }

    /**
     * Called when the transaction is ready so that the organizer can update the TaskFragments based
     * on the changes in transaction.
     */
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        // Notify the server to finish the transaction.
        onTransactionHandled(transaction.getTransactionToken(), new WindowContainerTransaction(),
                TRANSIT_NONE, false /* shouldApplyIndependently */);
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
