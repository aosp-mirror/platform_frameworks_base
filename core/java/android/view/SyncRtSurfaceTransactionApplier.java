/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Consumer;

/**
 * Helper class to apply surface transactions in sync with RenderThread.
 * @hide
 */
public class SyncRtSurfaceTransactionApplier {

    public static final int FLAG_ALL = 0xffffffff;
    public static final int FLAG_ALPHA = 1;
    public static final int FLAG_MATRIX = 1 << 1;
    public static final int FLAG_WINDOW_CROP = 1 << 2;
    public static final int FLAG_LAYER = 1 << 3;
    public static final int FLAG_CORNER_RADIUS = 1 << 4;
    public static final int FLAG_VISIBILITY = 1 << 5;

    private final Surface mTargetSurface;
    private final ViewRootImpl mTargetViewRootImpl;
    private final float[] mTmpFloat9 = new float[9];

    /**
     * @param targetView The view in the surface that acts as synchronization anchor.
     */
    public SyncRtSurfaceTransactionApplier(View targetView) {
        mTargetViewRootImpl = targetView != null ? targetView.getViewRootImpl() : null;
        mTargetSurface = mTargetViewRootImpl != null ? mTargetViewRootImpl.mSurface : null;
    }

    /**
     * Schedules applying surface parameters on the next frame.
     *
     * @param params The surface parameters to apply. DO NOT MODIFY the list after passing into
     *               this method to avoid synchronization issues.
     */
    public void scheduleApply(final SurfaceParams... params) {
        if (mTargetViewRootImpl == null) {
            return;
        }
        mTargetViewRootImpl.registerRtFrameCallback(frame -> {
            if (mTargetSurface == null || !mTargetSurface.isValid()) {
                return;
            }
            Transaction t = new Transaction();
            for (int i = params.length - 1; i >= 0; i--) {
                SurfaceParams surfaceParams = params[i];
                SurfaceControl surface = surfaceParams.surface;
                t.deferTransactionUntilSurface(surface, mTargetSurface, frame);
                applyParams(t, surfaceParams, mTmpFloat9);
            }
            t.setEarlyWakeup();
            t.apply();
        });

        // Make sure a frame gets scheduled.
        mTargetViewRootImpl.getView().invalidate();
    }

    public static void applyParams(Transaction t, SurfaceParams params, float[] tmpFloat9) {
        if ((params.flags & FLAG_MATRIX) != 0) {
            t.setMatrix(params.surface, params.matrix, tmpFloat9);
        }
        if ((params.flags & FLAG_WINDOW_CROP) != 0) {
            t.setWindowCrop(params.surface, params.windowCrop);
        }
        if ((params.flags & FLAG_ALPHA) != 0) {
            t.setAlpha(params.surface, params.alpha);
        }
        if ((params.flags & FLAG_LAYER) != 0) {
            t.setLayer(params.surface, params.layer);
        }
        if ((params.flags & FLAG_CORNER_RADIUS) != 0) {
            t.setCornerRadius(params.surface, params.cornerRadius);
        }
        if ((params.flags & FLAG_VISIBILITY) != 0) {
            if (params.visible) {
                t.show(params.surface);
            } else {
                t.hide(params.surface);
            }
        }
    }

    /**
     * Creates an instance of SyncRtSurfaceTransactionApplier, deferring until the target view is
     * attached if necessary.
     */
    public static void create(final View targetView,
            final Consumer<SyncRtSurfaceTransactionApplier> callback) {
        if (targetView == null) {
            // No target view, no applier
            callback.accept(null);
        } else if (targetView.getViewRootImpl() != null) {
            // Already attached, we're good to go
            callback.accept(new SyncRtSurfaceTransactionApplier(targetView));
        } else {
            // Haven't been attached before we can get the view root
            targetView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    targetView.removeOnAttachStateChangeListener(this);
                    callback.accept(new SyncRtSurfaceTransactionApplier(targetView));
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    // Do nothing
                }
            });
        }
    }

    public static class SurfaceParams {

        public static class Builder {
            final SurfaceControl surface;
            int flags;
            float alpha;
            float cornerRadius;
            Matrix matrix;
            Rect windowCrop;
            int layer;
            boolean visible;

            /**
             * @param surface The surface to modify.
             */
            public Builder(SurfaceControl surface) {
                this.surface = surface;
            }

            /**
             * @param alpha The alpha value to apply to the surface.
             * @return this Builder
             */
            public Builder withAlpha(float alpha) {
                this.alpha = alpha;
                flags |= FLAG_ALPHA;
                return this;
            }

            /**
             * @param matrix The matrix to apply to the surface.
             * @return this Builder
             */
            public Builder withMatrix(Matrix matrix) {
                this.matrix = matrix;
                flags |= FLAG_MATRIX;
                return this;
            }

            /**
             * @param windowCrop The window crop to apply to the surface.
             * @return this Builder
             */
            public Builder withWindowCrop(Rect windowCrop) {
                this.windowCrop = windowCrop;
                flags |= FLAG_WINDOW_CROP;
                return this;
            }

            /**
             * @param layer The layer to assign the surface.
             * @return this Builder
             */
            public Builder withLayer(int layer) {
                this.layer = layer;
                flags |= FLAG_LAYER;
                return this;
            }

            /**
             * @param radius the Radius for rounded corners to apply to the surface.
             * @return this Builder
             */
            public Builder withCornerRadius(float radius) {
                this.cornerRadius = radius;
                flags |= FLAG_CORNER_RADIUS;
                return this;
            }

            /**
             * @param visible The visibility to apply to the surface.
             * @return this Builder
             */
            public Builder withVisibility(boolean visible) {
                this.visible = visible;
                flags |= FLAG_VISIBILITY;
                return this;
            }

            /**
             * @return a new SurfaceParams instance
             */
            public SurfaceParams build() {
                return new SurfaceParams(surface, flags, alpha, matrix, windowCrop, layer,
                        cornerRadius, visible);
            }
        }

        private SurfaceParams(SurfaceControl surface, int params, float alpha, Matrix matrix,
                Rect windowCrop, int layer, float cornerRadius, boolean visible) {
            this.flags = params;
            this.surface = surface;
            this.alpha = alpha;
            this.matrix = new Matrix(matrix);
            this.windowCrop = new Rect(windowCrop);
            this.layer = layer;
            this.cornerRadius = cornerRadius;
            this.visible = visible;
        }


        /**
         * Constructs surface parameters to be applied when the current view state gets pushed to
         * RenderThread.
         *
         * @param surface The surface to modify.
         * @param alpha Alpha to apply.
         * @param matrix Matrix to apply.
         * @param windowCrop Crop to apply.
         * @param layer The layer to apply.
         * @param cornerRadius The corner radius to apply.
         * @param visible The visibility to apply.
         *
         * @deprecated Use {@link SurfaceParams.Builder} to create an instance.
         */
        @Deprecated
        public SurfaceParams(SurfaceControl surface, float alpha, Matrix matrix,
                Rect windowCrop, int layer, float cornerRadius, boolean visible) {
            this.flags = FLAG_ALL;
            this.surface = surface;
            this.alpha = alpha;
            this.matrix = new Matrix(matrix);
            this.windowCrop = windowCrop != null ? new Rect(windowCrop) : null;
            this.layer = layer;
            this.cornerRadius = cornerRadius;
            this.visible = visible;
        }

        private final int flags;

        @VisibleForTesting
        public final SurfaceControl surface;

        @VisibleForTesting
        public final float alpha;

        @VisibleForTesting
        final float cornerRadius;

        @VisibleForTesting
        public final Matrix matrix;

        @VisibleForTesting
        public final Rect windowCrop;

        @VisibleForTesting
        public final int layer;

        public final boolean visible;
    }
}
