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
 * limitations under the License
 */

package com.android.systemui.shared.system;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;

import java.util.ArrayList;

/**
 * Helper class to apply surface transactions in sync with RenderThread.
 */
public class SyncRtSurfaceTransactionApplier {

    private final Object mLock = new Object();
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
     * @param params The parameters for the surface to apply.
     */
    public void scheduleApply(SurfaceParams params) {
        ArrayList<SurfaceParams> list = new ArrayList<>(1);
        list.add(params);
        scheduleApply(list);
    }

    /**
     * Schedules applying surface parameters on the next frame.
     *
     * @param params The surface parameters to apply. DO NOT MODIFY the list after passing into
     *               this method to avoid synchronization issues.
     */
    public void scheduleApply(ArrayList<SurfaceParams> params) {
        if (mTargetViewRootImpl != null) {

            // Acquire mLock to establish a happens-before relationship to ensure the other thread
            // sees the surface parameters.
            synchronized (mLock) {
                mTargetViewRootImpl.registerRtFrameCallback(frame -> {
                    synchronized (mLock) {
                        if (mTargetSurface == null || !mTargetSurface.isValid()) {
                            return;
                        }
                        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                        for (int i = params.size() - 1; i >= 0; i--) {
                            SurfaceParams surfaceParams = params.get(i);
                            SurfaceControl surface = surfaceParams.surface;
                            t.deferTransactionUntilSurface(surface, mTargetSurface, frame);
                            t.setMatrix(surface, surfaceParams.matrix, mTmpFloat9);
                            t.setWindowCrop(surface, surfaceParams.windowCrop);
                            t.setAlpha(surface, surfaceParams.alpha);
                            t.setLayer(surface, surfaceParams.layer);
                            t.show(surface);
                        }
                        t.setEarlyWakeup();
                        t.apply();
                    }
                });
            }
        }
    }

    public static class SurfaceParams {

        /**
         * Constructs surface parameters to be applied when the current view state gets pushed to
         * RenderThread.
         *
         * @param surface The surface to modify.
         * @param alpha Alpha to apply.
         * @param matrix Matrix to apply.
         * @param windowCrop Crop to apply.
         */
        public SurfaceParams(SurfaceControl surface, float alpha, Matrix matrix, Rect windowCrop,
                int layer) {
            this.surface = surface;
            this.alpha = alpha;
            this.matrix = new Matrix(matrix);
            this.windowCrop = new Rect(windowCrop);
            this.layer = layer;
        }

        final SurfaceControl surface;
        final float alpha;
        final Matrix matrix;
        final Rect windowCrop;
        final int layer;
    }
}
