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
 * attaching app created SurfaceControl to the SurfaceControl hierarchy used
 * by the app, and enable SurfaceTransactions to be performed in sync with the
 * View hierarchy drawing.
 *
 * This object is obtained from {@link android.view.View#getRootSurfaceControl} and
 * {@link android.view.Window#getRootSurfaceControl}. It must be used from the UI thread of
 * the object it was obtained from.
 */
@UiThread
public interface AttachedSurfaceControl {
    /**
     * Create a transaction which will reparent {@param child} to the View hierarchy
     * root SurfaceControl. See
     * {@link SurfaceControl.Transaction#reparent}. This transacton must be applied
     * or merged in to another transaction by the caller, otherwise it will have
     * no effect.
     *
     * @param child The SurfaceControl to reparent.
     * @return A new transaction which performs the reparent operation when applied.
     */
    @Nullable SurfaceControl.Transaction buildReparentTransaction(@NonNull SurfaceControl child);

    /**
     * Consume the passed in transaction, and request the View hierarchy to apply it atomically
     * with the next draw. This transaction will be merged with the buffer transaction from the
     * ViewRoot and they will show up on-screen atomically synced.
     *
     * This will not cause a draw to be scheduled, and if there are no other changes
     * to the View hierarchy you may need to call {@link android.view.View#invalidate}
     */
    boolean applyTransactionOnDraw(@NonNull SurfaceControl.Transaction t);

    /**
     * The transform hint can be used by a buffer producer to pre-rotate the rendering such that the
     * final transformation in the system composer is identity. This can be very useful when used in
     * conjunction with the h/w composer HAL in situations where it cannot handle rotations or
     * handle them with an additional power cost.
     *
     * The transform hint should be used with ASurfaceControl APIs when submitting buffers.
     * Example usage:
     *
     * 1. After a configuration change, before dequeuing a buffer, the buffer producer queries the
     *    function for the transform hint.
     *
     * 2. The desired buffer width and height is rotated by the transform hint.
     *
     * 3. The producer dequeues a buffer of the new pre-rotated size.
     *
     * 4. The producer renders to the buffer such that the image is already transformed, that is
     *    applying the transform hint to the rendering.
     *
     * 5. The producer applies the inverse transform hint to the buffer it just rendered.
     *
     * 6. The producer queues the pre-transformed buffer with the buffer transform.
     *
     * 7. The composer combines the buffer transform with the display transform.  If the buffer
     *    transform happens to cancel out the display transform then no rotation is needed and there
     *    will be no performance penalties.
     *
     * Note, when using ANativeWindow APIs in conjunction with a NativeActivity Surface or
     * SurfaceView Surface, the buffer producer will already have access to the transform hint and
     * no additional work is needed.
     */
    default @Surface.Rotation int getSurfaceTransformHint() {
        return Surface.ROTATION_0;
    }

    /**
     * Surface transform hint change listener.
     * @see #getSurfaceTransformHint
     */
    @UiThread
    interface OnSurfaceTransformHintChangedListener {
        /**
         * @param hint new surface transform hint
         * @see #getSurfaceTransformHint
         */
        void onSurfaceTransformHintChanged(@Surface.Rotation int hint);
    }

    /**
     * Registers a surface transform hint changed listener to receive notifications about when
     * the transform hint changes.
     *
     * @see #getSurfaceTransformHint
     * @see #removeOnSurfaceTransformHintChangedListener
     */
    default void addOnSurfaceTransformHintChangedListener(
            @NonNull OnSurfaceTransformHintChangedListener listener) {
    }

    /**
     * Unregisters a surface transform hint changed listener.
     *
     * @see #addOnSurfaceTransformHintChangedListener
     */
    default void removeOnSurfaceTransformHintChangedListener(
            @NonNull OnSurfaceTransformHintChangedListener listener) {
    }
}
