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
import android.view.SurfaceControl.Transaction;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewRootImpl;

import java.util.function.Consumer;

/**
 * Helper class to apply surface transactions in sync with RenderThread.
 */
public class SyncRtSurfaceTransactionApplierCompat {

    private final SyncRtSurfaceTransactionApplier mApplier;

    /**
     * @param targetView The view in the surface that acts as synchronization anchor.
     */
    public SyncRtSurfaceTransactionApplierCompat(View targetView) {
        mApplier = new SyncRtSurfaceTransactionApplier(targetView);
    }

    private SyncRtSurfaceTransactionApplierCompat(SyncRtSurfaceTransactionApplier applier) {
        mApplier = applier;
    }

    /**
     * Schedules applying surface parameters on the next frame.
     *
     * @param params The surface parameters to apply. DO NOT MODIFY the list after passing into
     *               this method to avoid synchronization issues.
     */
    public void scheduleApply(final SurfaceParams... params) {
        mApplier.scheduleApply(convert(params));
    }

    private SyncRtSurfaceTransactionApplier.SurfaceParams[] convert(SurfaceParams[] params) {
        SyncRtSurfaceTransactionApplier.SurfaceParams[] result =
                new SyncRtSurfaceTransactionApplier.SurfaceParams[params.length];
        for (int i = 0; i < params.length; i++) {
            result[i] = params[i].mParams;
        }
        return result;
    }

    public static void applyParams(TransactionCompat t, SurfaceParams params) {
        SyncRtSurfaceTransactionApplier.applyParams(t.mTransaction, params.mParams, t.mTmpValues);
    }

    public static void create(final View targetView,
            final Consumer<SyncRtSurfaceTransactionApplierCompat> callback) {
        SyncRtSurfaceTransactionApplier.create(targetView,
                new Consumer<SyncRtSurfaceTransactionApplier>() {
                    @Override
                    public void accept(SyncRtSurfaceTransactionApplier applier) {
                        callback.accept(new SyncRtSurfaceTransactionApplierCompat(applier));
                    }
                });
    }

    public static class SurfaceParams {

        private final SyncRtSurfaceTransactionApplier.SurfaceParams mParams;

        /**
         * Constructs surface parameters to be applied when the current view state gets pushed to
         * RenderThread.
         *
         * @param surface The surface to modify.
         * @param alpha Alpha to apply.
         * @param matrix Matrix to apply.
         * @param windowCrop Crop to apply.
         */
        public SurfaceParams(SurfaceControlCompat surface, float alpha, Matrix matrix,
                Rect windowCrop, int layer, float cornerRadius) {
            mParams = new SyncRtSurfaceTransactionApplier.SurfaceParams(surface.mSurfaceControl,
                    alpha, matrix, windowCrop, layer, cornerRadius);
        }
    }
}
