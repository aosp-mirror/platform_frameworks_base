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
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.Executor;

/**
 * Interface for WindowManager to delegate control of {@link com.android.server.wm.TaskFragment}.
 * @hide
 */
public class TaskFragmentOrganizer extends WindowOrganizer {

    /**
     * Key to the exception in {@link Bundle} in {@link ITaskFragmentOrganizer#onTaskFragmentError}.
     */
    private static final String KEY_ERROR_CALLBACK_EXCEPTION = "fragment_exception";

    /**
     * Creates a {@link Bundle} with an exception that can be passed to
     * {@link ITaskFragmentOrganizer#onTaskFragmentError}.
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

    /** Called when a TaskFragment is created and organized by this organizer. */
    public void onTaskFragmentAppeared(
            @NonNull TaskFragmentAppearedInfo taskFragmentAppearedInfo) {}

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

    private final ITaskFragmentOrganizer mInterface = new ITaskFragmentOrganizer.Stub() {
        @Override
        public void onTaskFragmentAppeared(@NonNull TaskFragmentAppearedInfo taskFragmentInfo) {
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
    };

    private ITaskFragmentOrganizerController getController() {
        try {
            return getWindowOrganizerController().getTaskFragmentOrganizerController();
        } catch (RemoteException e) {
            return null;
        }
    }
}
