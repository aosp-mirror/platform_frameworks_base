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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.RemoteAnimationDefinition;

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

    /**
     * Creates a {@link Bundle} with an exception that can be passed to
     * {@link ITaskFragmentOrganizer#onTaskFragmentError}.
     * @hide
     */
    public static Bundle putExceptionInBundle(@NonNull Throwable exception) {
        final Bundle exceptionBundle = new Bundle();
        exceptionBundle.putSerializable(KEY_ERROR_CALLBACK_EXCEPTION, exception);
        return exceptionBundle;
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

    /** Called when a TaskFragment is created and organized by this organizer. */
    public void onTaskFragmentAppeared(@NonNull TaskFragmentInfo taskFragmentInfo) {}

    /** Called when the status of an organized TaskFragment is changed. */
    public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {}

    /** Called when an organized TaskFragment is removed. */
    public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {}

    /**
     * Called when the parent leaf Task of organized TaskFragments is changed.
     * When the leaf Task is changed, the organizer may want to update the TaskFragments in one
     * transaction.
     *
     * For case like screen size change, it will trigger onTaskFragmentParentInfoChanged with new
     * Task bounds, but may not trigger onTaskFragmentInfoChanged because there can be an override
     * bounds.
     */
    public void onTaskFragmentParentInfoChanged(
            @NonNull IBinder fragmentToken, @NonNull Configuration parentConfig) {}

    /**
     * Called when the {@link WindowContainerTransaction} created with
     * {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)} failed on the server side.
     *
     * @param errorCallbackToken    token set in
     *                             {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)}
     * @param exception             exception from the server side.
     */
    public void onTaskFragmentError(
            @NonNull IBinder errorCallbackToken, @NonNull Throwable exception) {}

    /**
     * Called when an Activity is reparented to the Task with organized TaskFragment. For example,
     * when an Activity enters and then exits Picture-in-picture, it will be reparented back to its
     * orginial Task. In this case, we need to notify the organizer so that it can check if the
     * Activity matches any split rule.
     *
     * @param taskId            The Task that the activity is reparented to.
     * @param activityIntent    The intent that the activity is original launched with.
     * @param activityToken     If the activity belongs to the same process as the organizer, this
     *                          will be the actual activity token; if the activity belongs to a
     *                          different process, the server will generate a temporary token that
     *                          the organizer can use to reparent the activity through
     *                          {@link WindowContainerTransaction} if needed.
     * @hide
     */
    public void onActivityReparentToTask(int taskId, @NonNull Intent activityIntent,
            @NonNull IBinder activityToken) {}

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
        public void onTaskFragmentAppeared(@NonNull TaskFragmentInfo taskFragmentInfo) {
            mExecutor.execute(
                    () -> TaskFragmentOrganizer.this.onTaskFragmentAppeared(taskFragmentInfo));
        }

        @Override
        public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {
            mExecutor.execute(
                    () -> TaskFragmentOrganizer.this.onTaskFragmentInfoChanged(taskFragmentInfo));
        }

        @Override
        public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {
            mExecutor.execute(
                    () -> TaskFragmentOrganizer.this.onTaskFragmentVanished(taskFragmentInfo));
        }

        @Override
        public void onTaskFragmentParentInfoChanged(
                @NonNull IBinder fragmentToken, @NonNull Configuration parentConfig) {
            mExecutor.execute(
                    () -> TaskFragmentOrganizer.this.onTaskFragmentParentInfoChanged(
                            fragmentToken, parentConfig));
        }

        @Override
        public void onTaskFragmentError(
                @NonNull IBinder errorCallbackToken, @NonNull Bundle exceptionBundle) {
            mExecutor.execute(() -> TaskFragmentOrganizer.this.onTaskFragmentError(
                    errorCallbackToken,
                    (Throwable) exceptionBundle.getSerializable(KEY_ERROR_CALLBACK_EXCEPTION)));
        }

        @Override
        public void onActivityReparentToTask(int taskId, @NonNull Intent activityIntent,
                @NonNull IBinder activityToken) {
            mExecutor.execute(
                    () -> TaskFragmentOrganizer.this.onActivityReparentToTask(
                            taskId, activityIntent, activityToken));
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
