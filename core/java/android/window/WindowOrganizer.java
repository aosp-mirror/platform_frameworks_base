/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.ActivityTaskManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Singleton;
import android.view.RemoteAnimationAdapter;

/**
 * Base class for organizing specific types of windows like Tasks and DisplayAreas
 *
 * @hide
 */
@TestApi
public class WindowOrganizer {

    /**
     * Apply multiple WindowContainer operations at once.
     *
     * Note that using this API requires the caller to hold
     * {@link android.Manifest.permission#MANAGE_ACTIVITY_TASKS}, unless the caller is using
     * {@link TaskFragmentOrganizer}, in which case it is allowed to change TaskFragment that is
     * created by itself.
     *
     * @param t The transaction to apply.
     */
    @RequiresPermission(value = android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            conditional = true)
    public void applyTransaction(@NonNull WindowContainerTransaction t) {
        try {
            if (!t.isEmpty()) {
                getWindowOrganizerController().applyTransaction(t);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apply multiple WindowContainer operations at once.
     *
     * Note that using this API requires the caller to hold
     * {@link android.Manifest.permission#MANAGE_ACTIVITY_TASKS}, unless the caller is using
     * {@link TaskFragmentOrganizer}, in which case it is allowed to change TaskFragment that is
     * created by itself.
     *
     * @param t The transaction to apply.
     * @param callback This transaction will use the synchronization scheme described in
     *        BLASTSyncEngine.java. The SurfaceControl transaction containing the effects of this
     *        WindowContainer transaction will be passed to this callback when ready.
     * @return An ID for the sync operation which will later be passed to transactionReady callback.
     *         This lets the caller differentiate overlapping sync operations.
     */
    @RequiresPermission(value = android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            conditional = true)
    public int applySyncTransaction(@NonNull WindowContainerTransaction t,
            @NonNull WindowContainerTransactionCallback callback) {
        try {
            return getWindowOrganizerController().applySyncTransaction(t, callback.mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start a transition.
     * @param type The type of the transition. This is ignored if a transitionToken is provided.
     * @param transitionToken An existing transition to start. If null, a new transition is created.
     * @param t The set of window operations that are part of this transition.
     * @return A token identifying the transition. This will be the same as transitionToken if it
     *         was provided.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @NonNull
    public IBinder startTransition(int type, @Nullable IBinder transitionToken,
            @Nullable WindowContainerTransaction t) {
        try {
            return getWindowOrganizerController().startTransition(type, transitionToken, t);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Finishes a running transition.
     * @param transitionToken The transition to finish. Can't be null.
     * @param t A set of window operations to apply before finishing.
     * @param callback A sync callback (if provided). See {@link #applySyncTransaction}.
     * @return An ID for the sync operation if performed. See {@link #applySyncTransaction}.
     *
     * @hide
     */
    @SuppressLint("ExecutorRegistration")
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public int finishTransition(@NonNull IBinder transitionToken,
            @Nullable WindowContainerTransaction t,
            @Nullable WindowContainerTransactionCallback callback) {
        try {
            return getWindowOrganizerController().finishTransition(transitionToken, t,
                    callback != null ? callback.mInterface : null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start a legacy transition.
     * @param type The type of the transition. This is ignored if a transitionToken is provided.
     * @param adapter An existing transition to start. If null, a new transition is created.
     * @param t The set of window operations that are part of this transition.
     * @return true on success, false if a transition was already running.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @NonNull
    public int startLegacyTransition(int type, @NonNull RemoteAnimationAdapter adapter,
            @NonNull WindowContainerTransactionCallback syncCallback,
            @NonNull WindowContainerTransaction t) {
        try {
            return getWindowOrganizerController().startLegacyTransition(
                    type, adapter, syncCallback.mInterface, t);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register an ITransitionPlayer to handle transition animations.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void registerTransitionPlayer(@Nullable ITransitionPlayer player) {
        try {
            getWindowOrganizerController().registerTransitionPlayer(player);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see TransitionMetrics
     * @hide
     */
    public static ITransitionMetricsReporter getTransitionMetricsReporter() {
        try {
            return getWindowOrganizerController().getTransitionMetricsReporter();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    static IWindowOrganizerController getWindowOrganizerController() {
        return IWindowOrganizerControllerSingleton.get();
    }

    private static final Singleton<IWindowOrganizerController> IWindowOrganizerControllerSingleton =
            new Singleton<IWindowOrganizerController>() {
                @Override
                protected IWindowOrganizerController create() {
                    try {
                        return ActivityTaskManager.getService().getWindowOrganizerController();
                    } catch (RemoteException e) {
                        return null;
                    }
                }
            };
}
