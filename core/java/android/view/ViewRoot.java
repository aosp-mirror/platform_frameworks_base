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
package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;

/**
 * Provides an interface to the root-Surface of a View Hierarchy or Window. This
 * is used in combination with the {@link android.view.SurfaceControl} API to enable
 * attaching app created SurfaceControl to the ViewRoot's surface hierarchy, and enable
 * SurfaceTransactions to be performed in sync with the ViewRoot drawing. This object
 * is obtained from {@link android.view.View#getViewRoot} and
 * {@link android.view.Window#getViewRoot}. It must be used from the UI thread of
 * the object it was obtained from.
 */
@UiThread
public interface ViewRoot {
    /**
     * Create a transaction which will reparent {@param child} to the ViewRoot. See
     * {@link SurfaceControl.Transaction#reparent}. This transacton must be applied
     * or merged in to another transaction by the caller, otherwise it will have
     * no effect.
     *
     * @param child The SurfaceControl to reparent.
     * @return A new transaction which performs the reparent operation when applied.
     */
    @Nullable SurfaceControl.Transaction buildReparentTransaction(@NonNull SurfaceControl child);

    /**
     * Consume the passed in transaction, and request the ViewRoot to apply it with the
     * next draw. This transaction will be merged with the buffer transaction from the ViewRoot
     * and they will show up on-screen atomically synced.
     *
     * This will not cause a draw to be scheduled, and if there are no other changes
     * to the View hierarchy you may need to call {@link android.view.View#invalidate}
     */
    boolean applyTransactionOnDraw(@NonNull SurfaceControl.Transaction t);
}
