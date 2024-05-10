/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.view.SurfaceControl.Transaction;

/**
 * Interface that is invoked when the ISurfaceSyncGroup has completed. The parent ISurfaceSyncGroup
 * creates an ITransactionReadyCallback and sends it to the children who will invoke the
 * {@link onTransactionReady} when they have completed, including waiting on their children.
 *
 * @hide
 */
interface ITransactionReadyCallback {
    /**
     * Invoked when ISurfaceSyncGroup has completed. This means the ISurfaceSyncGroup has been
     * marked as ready and all children it was waiting on have been completed.
     *
     * @param t The transaction that contains everything to be included in the sync. This can be
                null if there's nothing to sync
     */
    void onTransactionReady(in @nullable Transaction t);
}