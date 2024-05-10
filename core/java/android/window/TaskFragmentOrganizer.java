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

import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.RemoteAnimationDefinition;
import android.view.WindowManager;

import com.android.window.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
     * Key to the {@link TaskFragmentOperation.OperationType} in
     * {@link TaskFragmentTransaction.Change#getErrorBundle()}.
     */
    public static final String KEY_ERROR_CALLBACK_OP_TYPE = "operation_type";

    /**
     * No change set.
     */
    @WindowManager.TransitionType
    @TaskFragmentTransitionType
    public static final int TASK_FRAGMENT_TRANSIT_NONE = TRANSIT_NONE;

    /**
     * A window that didn't exist before has been created and made visible.
     */
    @WindowManager.TransitionType
    @TaskFragmentTransitionType
    public static final int TASK_FRAGMENT_TRANSIT_OPEN = TRANSIT_OPEN;

    /**
     * A window that was visible no-longer exists (was finished or destroyed).
     */
    @WindowManager.TransitionType
    @TaskFragmentTransitionType
    public static final int TASK_FRAGMENT_TRANSIT_CLOSE = TRANSIT_CLOSE;

    /**
     * A window is visible before and after but changes in some way (eg. it resizes or changes
     * windowing-mode).
     */
    @WindowManager.TransitionType
    @TaskFragmentTransitionType
    public static final int TASK_FRAGMENT_TRANSIT_CHANGE = TRANSIT_CHANGE;

    /**
     * Introduced a sub set of {@link WindowManager.TransitionType} for the types that are used for
     * TaskFragment transition.
     *
     * Doing this instead of exposing {@link WindowManager.TransitionType} because we want to keep
     * the Shell transition API hidden until it comes fully stable.
     * @hide
     */
    @IntDef(prefix = { "TASK_FRAGMENT_TRANSIT_" }, value = {
            TASK_FRAGMENT_TRANSIT_NONE,
            TASK_FRAGMENT_TRANSIT_OPEN,
            TASK_FRAGMENT_TRANSIT_CLOSE,
            TASK_FRAGMENT_TRANSIT_CHANGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TaskFragmentTransitionType {}

    /**
     * Creates a {@link Bundle} with an exception, operation type and TaskFragmentInfo (if any)
     * that can be passed to {@link ITaskFragmentOrganizer#onTaskFragmentError}.
     * @hide
     */
    public static @NonNull Bundle putErrorInfoInBundle(@NonNull Throwable exception,
            @Nullable TaskFragmentInfo info, @TaskFragmentOperation.OperationType int opType) {
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
     * Registers a {@link TaskFragmentOrganizer} to manage TaskFragments.
     */
    @CallSuper
    public void registerOrganizer() {
        // TODO(b/302420256) point to registerOrganizer(boolean) when flag is removed.
        try {
            getController().registerOrganizer(mInterface, false /* isSystemOrganizer */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link TaskFragmentOrganizer} to manage TaskFragments.
     *
     * Registering a system organizer requires MANAGE_ACTIVITY_TASKS permission, and the organizer
     * will have additional system capabilities, including: (1) it will receive SurfaceControl for
     * the organized TaskFragment, and (2) it needs to update the
     * {@link android.view.SurfaceControl} following the window change accordingly.
     *
     * @hide
     */
    @CallSuper
    @RequiresPermission(value = "android.permission.MANAGE_ACTIVITY_TASKS", conditional = true)
    @FlaggedApi(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG)
    public void registerOrganizer(boolean isSystemOrganizer) {
        try {
            getController().registerOrganizer(mInterface, isSystemOrganizer);
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
     * TaskFragments, and at least one of the transition window is embedded (not filling the Task).
     * @hide
     */
    @CallSuper
    public void registerRemoteAnimations(@NonNull RemoteAnimationDefinition definition) {
        try {
            getController().registerRemoteAnimations(mInterface, definition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters remote animations per transition type for the organizer.
     * @hide
     */
    @CallSuper
    public void unregisterRemoteAnimations() {
        try {
            getController().unregisterRemoteAnimations(mInterface);
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
     * @param transitionType    {@link TaskFragmentTransitionType} if it needs to start a
     *                          transition.
     * @param shouldApplyIndependently  If {@code true}, the {@code wct} will request a new
     *                                  transition, which will be queued until the sync engine is
     *                                  free if there is any other active sync. If {@code false},
     *                                  the {@code wct} will be directly applied to the active sync.
     * @see com.android.server.wm.WindowOrganizerController#enforceTaskFragmentOrganizerPermission
     * for permission enforcement.
     */
    public void onTransactionHandled(@NonNull IBinder transactionToken,
            @NonNull WindowContainerTransaction wct,
            @TaskFragmentTransitionType int transitionType, boolean shouldApplyIndependently) {
        wct.setTaskFragmentOrganizer(mInterface);
        try {
            getController().onTransactionHandled(transactionToken, wct, transitionType,
                    shouldApplyIndependently);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Must use {@link #applyTransaction(WindowContainerTransaction, int, boolean)} instead.
     * @see #applyTransaction(WindowContainerTransaction, int, boolean)
     */
    @Override
    public void applyTransaction(@NonNull WindowContainerTransaction wct) {
        throw new RuntimeException("Not allowed!");
    }

    /**
     * Requests the server to apply the given {@link WindowContainerTransaction}.
     *
     * @param wct   {@link WindowContainerTransaction} to apply.
     * @param transitionType    {@link TaskFragmentTransitionType} if it needs to start a
     *                          transition.
     * @param shouldApplyIndependently  If {@code true}, the {@code wct} will request a new
     *                                  transition, which will be queued until the sync engine is
     *                                  free if there is any other active sync. If {@code false},
     *                                  the {@code wct} will be directly applied to the active sync.
     * @see com.android.server.wm.WindowOrganizerController#enforceTaskFragmentOrganizerPermission
     * for permission enforcement.
     */
    public void applyTransaction(@NonNull WindowContainerTransaction wct,
            @TaskFragmentTransitionType int transitionType, boolean shouldApplyIndependently) {
        if (wct.isEmpty()) {
            return;
        }
        wct.setTaskFragmentOrganizer(mInterface);
        try {
            getController().applyTransaction(
                    wct, transitionType, shouldApplyIndependently, null /* remoteTransition */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Applies a transaction with a {@link RemoteTransition}. Only a system organizer is allowed to
     * use {@link RemoteTransition}. See {@link TaskFragmentOrganizer#registerOrganizer(boolean)}.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_TASK_FRAGMENT_SYSTEM_ORGANIZER_FLAG)
    public void applySystemTransaction(@NonNull WindowContainerTransaction wct,
            @TaskFragmentTransitionType int transitionType,
            @Nullable RemoteTransition remoteTransition) {
        if (wct.isEmpty()) {
            return;
        }
        wct.setTaskFragmentOrganizer(mInterface);
        try {
            getController().applyTransaction(
                    wct, transitionType, remoteTransition != null /* shouldApplyIndependently */,
                    remoteTransition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when the transaction is ready so that the organizer can update the TaskFragments based
     * on the changes in transaction.
     */
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        // Notify the server to finish the transaction.
        onTransactionHandled(transaction.getTransactionToken(), new WindowContainerTransaction(),
                TASK_FRAGMENT_TRANSIT_NONE, false /* shouldApplyIndependently */);
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
