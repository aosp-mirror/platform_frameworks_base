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
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.window.SurfaceSyncGroup;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
     *
     * @see HardwareBuffer
     */
    default @SurfaceControl.BufferTransform int getBufferTransformHint() {
        return SurfaceControl.BUFFER_TRANSFORM_IDENTITY;
    }

    /**
     * Buffer transform hint change listener.
     * @see #getBufferTransformHint
     */
    @UiThread
    interface OnBufferTransformHintChangedListener {
        /**
         * @param hint new surface transform hint
         * @see #getBufferTransformHint
         */
        void onBufferTransformHintChanged(@SurfaceControl.BufferTransform int hint);
    }

    /**
     * Registers a {@link OnBufferTransformHintChangedListener} to receive notifications about when
     * the transform hint changes.
     *
     * @see #getBufferTransformHint
     * @see #removeOnBufferTransformHintChangedListener
     */
    default void addOnBufferTransformHintChangedListener(
            @NonNull OnBufferTransformHintChangedListener listener) {
    }

    /**
     * Unregisters a {@link OnBufferTransformHintChangedListener}.
     *
     * @see #addOnBufferTransformHintChangedListener
     */
    default void removeOnBufferTransformHintChangedListener(
            @NonNull OnBufferTransformHintChangedListener listener) {
    }

    /**
     * Sets the touchable region for this SurfaceControl, expressed in surface local
     * coordinates. By default the touchable region is the entire Layer, indicating
     * that if the layer is otherwise eligible to receive touch it receives touch
     * on the entire surface. Setting the touchable region allows the SurfaceControl
     * to receive touch in some regions, while allowing for pass-through in others.
     *
     * @param r The region to use or null to use the entire Layer bounds
     */
    default void setTouchableRegion(@Nullable Region r) {
    }

    /**
     * Returns a SurfaceSyncGroup that can be used to sync {@link AttachedSurfaceControl} in a
     * {@link SurfaceSyncGroup}
     *
     * @hide
     */
    @Nullable
    default SurfaceSyncGroup getOrCreateSurfaceSyncGroup() {
        return null;
    }

    /**
     * Set a crop region on all children parented to the layer represented by this
     * AttachedSurfaceControl. This includes SurfaceView, and an example usage may
     * be to ensure that SurfaceView with {@link android.view.SurfaceView#setZOrderOnTop}
     * are cropped to a region not including the app bar.
     * <p>
     * This cropped is expressed in terms of insets in window-space. Negative insets
     * are considered invalid and will produce an exception. Insets of zero will produce
     * the same result as if this function had never been called.
     *
     * @param insets The insets in each direction by which to bound the children
     *               expressed in window-space.
     * @throws IllegalArgumentException If negative insets are provided.
     */
    default void setChildBoundingInsets(@NonNull Rect insets) {
    }

    /**
     * Add a trusted presentation listener on the SurfaceControl associated with this window.
     *
     * @param t          Transaction that the trusted presentation listener is added on. This should
     *                   be applied by the caller.
     * @param thresholds The {@link SurfaceControl.TrustedPresentationThresholds} that will specify
     *                   when the to invoke the callback.
     * @param executor   The {@link Executor} where the callback will be invoked on.
     * @param listener   The {@link Consumer} that will receive the callbacks when entered or
     *                   exited the threshold.
     *
     * @see SurfaceControl.Transaction#setTrustedPresentationCallback(SurfaceControl,
     * SurfaceControl.TrustedPresentationThresholds, Executor, Consumer)
     *
     * @hide b/287076178 un-hide with API bump
     */
    default void addTrustedPresentationCallback(@NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl.TrustedPresentationThresholds thresholds,
            @NonNull Executor executor, @NonNull Consumer<Boolean> listener) {
    }

    /**
     * Remove a trusted presentation listener on the SurfaceControl associated with this window.
     *
     * @param t          Transaction that the trusted presentation listener removed on. This should
     *                   be applied by the caller.
     * @param listener   The {@link Consumer} that was previously registered with
     *                   addTrustedPresentationCallback that should be removed.
     *
     * @see SurfaceControl.Transaction#clearTrustedPresentationCallback(SurfaceControl)
     * @hide b/287076178 un-hide with API bump
     */
    default void removeTrustedPresentationCallback(@NonNull SurfaceControl.Transaction t,
            @NonNull Consumer<Boolean> listener) {
    }
}
