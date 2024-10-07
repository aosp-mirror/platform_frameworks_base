/**
 * Copyright (c) 2021 The Android Open Source Project
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

import android.os.Bundle;
import android.os.IBinder;
import android.view.RemoteAnimationDefinition;
import android.window.ITaskFragmentOrganizer;
import android.window.RemoteTransition;
import android.window.WindowContainerTransaction;

/** @hide */
interface ITaskFragmentOrganizerController {
    /**
     * Registers a TaskFragmentOrganizer to manage TaskFragments. Registering a system
     * organizer requires MANAGE_ACTIVITY_TASKS permission, and the organizer will have additional
     * system capabilities.
     *
     * @param organizer          The TaskFragmentOrganizer to register
     * @param isSystemOrganizer  If it is a system organizer
     * @param outSavedState      Returning the saved state (if any) that previously saved. This is
     *                           useful when retrieve the state from the same TaskFragmentOrganizer
     *                           that was killed by the system (e.g. to reclaim memory). Note that
     *                           the save state is dropped and unable to retrieve once the system
     *                           restarts or the organizer is unregistered.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value=android.Manifest.permission.MANAGE_ACTIVITY_TASKS, conditional=true)")
    void registerOrganizer(in ITaskFragmentOrganizer organizer, in boolean isSystemOrganizer, out Bundle outSavedState);

    /**
     * Unregisters a previously registered TaskFragmentOrganizer.
     */
    void unregisterOrganizer(in ITaskFragmentOrganizer organizer);

    /**
     * Registers remote animations per transition type for the organizer. It will override the
     * animations if the transition only contains windows that belong to the organized
     * TaskFragments in the given Task.
     */
    void registerRemoteAnimations(in ITaskFragmentOrganizer organizer,
        in RemoteAnimationDefinition definition);

    /**
     * Unregisters remote animations per transition type for the organizer.
     */
    void unregisterRemoteAnimations(in ITaskFragmentOrganizer organizer);

    /**
     * Saves the state in the system, where the state can be restored if the process of
     * the TaskFragmentOrganizer is restarted.
     */
    void setSavedState(in ITaskFragmentOrganizer organizer, in Bundle savedState);

    /**
     * Notifies the server that the organizer has finished handling the given transaction. The
     * server should apply the given {@link WindowContainerTransaction} for the necessary changes.
     */
    void onTransactionHandled(in IBinder transactionToken, in WindowContainerTransaction wct,
        int transitionType, boolean shouldApplyIndependently);

    /**
     * Requests the server to apply the given {@link WindowContainerTransaction}.
     *
     * {@link RemoteTransition} can only be used by a system organizer and
     * {@code shouldApplyIndependently} must be {@code true}. See {@link registerOrganizer}.
     */
    void applyTransaction(in WindowContainerTransaction wct, int transitionType,
        boolean shouldApplyIndependently, in RemoteTransition remoteTransition);
}
