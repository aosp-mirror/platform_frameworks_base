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

import android.window.ITaskOrganizerController;
import android.window.IWindowContainerTransactionCallback;
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

    /** @return An interface enabling the management of task organizers. */
    ITaskOrganizerController getTaskOrganizerController();
}
