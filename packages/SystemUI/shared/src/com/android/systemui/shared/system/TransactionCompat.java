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
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

public class TransactionCompat {

    final Transaction mTransaction;

    final float[] mTmpValues = new float[9];

    public TransactionCompat() {
        mTransaction = new Transaction();
    }

    public void apply() {
        mTransaction.apply();
    }

    public TransactionCompat show(SurfaceControl surfaceControl) {
        mTransaction.show(surfaceControl);
        return this;
    }

    public TransactionCompat hide(SurfaceControl surfaceControl) {
        mTransaction.hide(surfaceControl);
        return this;
    }

    public TransactionCompat setPosition(SurfaceControl surfaceControl, float x, float y) {
        mTransaction.setPosition(surfaceControl, x, y);
        return this;
    }

    public TransactionCompat setSize(SurfaceControl surfaceControl, int w, int h) {
        mTransaction.setBufferSize(surfaceControl, w, h);
        return this;
    }

    public TransactionCompat setLayer(SurfaceControl surfaceControl, int z) {
        mTransaction.setLayer(surfaceControl, z);
        return this;
    }

    public TransactionCompat setAlpha(SurfaceControl surfaceControl, float alpha) {
        mTransaction.setAlpha(surfaceControl, alpha);
        return this;
    }

    public TransactionCompat setOpaque(SurfaceControl surfaceControl, boolean opaque) {
        mTransaction.setOpaque(surfaceControl, opaque);
        return this;
    }

    public TransactionCompat setMatrix(SurfaceControl surfaceControl, float dsdx, float dtdx,
            float dtdy, float dsdy) {
        mTransaction.setMatrix(surfaceControl, dsdx, dtdx, dtdy, dsdy);
        return this;
    }

    public TransactionCompat setMatrix(SurfaceControl surfaceControl, Matrix matrix) {
        mTransaction.setMatrix(surfaceControl, matrix, mTmpValues);
        return this;
    }

    public TransactionCompat setWindowCrop(SurfaceControl surfaceControl, Rect crop) {
        mTransaction.setWindowCrop(surfaceControl, crop);
        return this;
    }

    public TransactionCompat setCornerRadius(SurfaceControl surfaceControl, float radius) {
        mTransaction.setCornerRadius(surfaceControl, radius);
        return this;
    }

    public TransactionCompat setBackgroundBlurRadius(SurfaceControl surfaceControl, int radius) {
        mTransaction.setBackgroundBlurRadius(surfaceControl, radius);
        return this;
    }

    public TransactionCompat setColor(SurfaceControl surfaceControl, float[] color) {
        mTransaction.setColor(surfaceControl, color);
        return this;
    }

    public static void setRelativeLayer(Transaction t, SurfaceControl surfaceControl,
            SurfaceControl relativeTo, int z) {
        t.setRelativeLayer(surfaceControl, relativeTo, z);
    }
}
