/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.window;

import android.view.SurfaceControl;

import android.os.IBinder;
import android.view.RemoteAnimationAdapter;
import android.window.IDisplayAreaOrganizerController;
import android.window.ITaskFragmentOrganizerController;
import android.window.ITaskOrganizerController;
import android.window.ITransitionMetricsReporter;
import android.window.ITransitionPlayer;
import android.window.IWindowContainerTransactionCallback;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

/** @hide */
interface IWindowOrganizerController {

    /**
     * Apply multiple WindowContainer operations at once.
     * @param t The transaction to apply.
     */
    void applyTransaction(in WindowContainerTransaction t);

    /**
     * Apply multiple WindowContainer operations at once.
     * @param t The transaction to apply.
     * @param callback This transaction will use the synchronization scheme described in
     *        BLASTSyncEngine.java. The SurfaceControl transaction containing the effects of this
     *        WindowContainer transaction will be passed to this callback when ready.
     * @return An ID for the sync operation which will later be passed to transactionReady callback.
     *         This lets the caller differentiate overlapping sync operations.
     */
    int applySyncTransaction(in WindowContainerTransaction t,
            in IWindowContainerTransactionCallback callback);

    /**
     * Starts a new transition.
     * @param type The transition type.
     * @param t Operations that are part of the transition.
     * @return a token representing the transition.
     */
    IBinder startNewTransition(int type, in @nullable WindowContainerTransaction t);

    /**
     * Starts the given transition.
     * @param transitionToken A token associated with the transition to start.
     * @param t Operations that are part of the transition.
     */
    void startTransition(IBinder transitionToken, in @nullable WindowContainerTransaction t);

    /**
     * Starts a legacy transition.
     * @param type The transition type.
     * @param adapter The animation to use.
     * @param syncCallback A sync callback for the contents of `t`
     * @param t Operations that are part of the transition.
     * @return sync-id or -1 if this no-op'd because a transition is already running.
     */
    int startLegacyTransition(int type, in RemoteAnimationAdapter adapter,
            in IWindowContainerTransactionCallback syncCallback, in WindowContainerTransaction t);

    /**
     * Finishes a transition. This must be called for all created transitions.
     * @param transitionToken Which transition to finish
     * @param t Changes to make before finishing but in the same SF Transaction. Can be null.
     */
    void finishTransition(in IBinder transitionToken, in @nullable WindowContainerTransaction t);

    /** @return An interface enabling the management of task organizers. */
    ITaskOrganizerController getTaskOrganizerController();

    /** @return An interface enabling the management of display area organizers. */
    IDisplayAreaOrganizerController getDisplayAreaOrganizerController();

    /** @return An interface enabling the management of task fragment organizers. */
    ITaskFragmentOrganizerController getTaskFragmentOrganizerController();

    /**
     * Registers a transition player with Core. There is only one of these active at a time so
     * calling this will replace the existing one (if set) until it is unregistered.
     */
    void registerTransitionPlayer(in ITransitionPlayer player);

    /**
     * Un-registers a transition player from Core. This will restore whichever player was active
     * prior to registering this one.
     */
    void unregisterTransitionPlayer(in ITransitionPlayer player);

    /** @return An interface enabling the transition players to report its metrics. */
    ITransitionMetricsReporter getTransitionMetricsReporter();

    /** @return The transaction queue token used by WM. */
    IBinder getApplyToken();
}
