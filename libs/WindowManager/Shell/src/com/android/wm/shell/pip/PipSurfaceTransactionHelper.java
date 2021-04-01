/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.SurfaceControl;

import com.android.wm.shell.R;

/**
 * Abstracts the common operations on {@link SurfaceControl.Transaction} for PiP transition.
 */
public class PipSurfaceTransactionHelper {

    private final boolean mEnableCornerRadius;
    private int mCornerRadius;

    /** for {@link #scale(SurfaceControl.Transaction, SurfaceControl, Rect, Rect)} operation */
    private final Matrix mTmpTransform = new Matrix();
    private final float[] mTmpFloat9 = new float[9];
    private final RectF mTmpSourceRectF = new RectF();
    private final RectF mTmpDestinationRectF = new RectF();
    private final Rect mTmpDestinationRect = new Rect();

    public PipSurfaceTransactionHelper(Context context) {
        final Resources res = context.getResources();
        mEnableCornerRadius = res.getBoolean(R.bool.config_pipEnableRoundCorner);
    }

    /**
     * Called when display size or font size of settings changed
     *
     * @param context the current context
     */
    public void onDensityOrFontScaleChanged(Context context) {
        if (mEnableCornerRadius) {
            final Resources res = context.getResources();
            mCornerRadius = res.getDimensionPixelSize(R.dimen.pip_corner_radius);
        }
    }

    /**
     * Operates the alpha on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper alpha(SurfaceControl.Transaction tx, SurfaceControl leash,
            float alpha) {
        tx.setAlpha(leash, alpha);
        return this;
    }

    /**
     * Operates the crop (and position) on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper crop(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect destinationBounds) {
        tx.setWindowCrop(leash, destinationBounds.width(), destinationBounds.height())
                .setPosition(leash, destinationBounds.left, destinationBounds.top);
        return this;
    }

    /**
     * Operates the scale (setMatrix) on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper scale(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds) {
        return scale(tx, leash, sourceBounds, destinationBounds, 0 /* degrees */);
    }

    /**
     * Operates the scale (setMatrix) on a given transaction and leash, along with a rotation.
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper scale(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds, float degrees) {
        mTmpSourceRectF.set(sourceBounds);
        // We want the matrix to position the surface relative to the screen coordinates so offset
        // the source to 0,0
        mTmpSourceRectF.offsetTo(0, 0);
        mTmpDestinationRectF.set(destinationBounds);
        mTmpTransform.setRectToRect(mTmpSourceRectF, mTmpDestinationRectF, Matrix.ScaleToFit.FILL);
        mTmpTransform.postRotate(degrees,
                mTmpDestinationRectF.centerX(), mTmpDestinationRectF.centerY());
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9);
        return this;
    }

    /**
     * Operates the scale (setMatrix) on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper scaleAndCrop(SurfaceControl.Transaction tx,
            SurfaceControl leash,
            Rect sourceBounds, Rect destinationBounds, Rect insets) {
        mTmpSourceRectF.set(sourceBounds);
        mTmpDestinationRect.set(sourceBounds);
        mTmpDestinationRect.inset(insets);
        // Scale by the shortest edge and offset such that the top/left of the scaled inset source
        // rect aligns with the top/left of the destination bounds
        final float scale = sourceBounds.width() <= sourceBounds.height()
                ? (float) destinationBounds.width() / sourceBounds.width()
                : (float) destinationBounds.height() / sourceBounds.height();
        final float left = destinationBounds.left - insets.left * scale;
        final float top = destinationBounds.top - insets.top * scale;
        mTmpTransform.setScale(scale, scale);
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9)
                .setWindowCrop(leash, mTmpDestinationRect)
                .setPosition(leash, left, top);
        return this;
    }

    /**
     * Operates the rotation according to the given degrees and scale (setMatrix) according to the
     * source bounds and rotated destination bounds. The crop will be the unscaled source bounds.
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper rotateAndScaleWithCrop(SurfaceControl.Transaction tx,
            SurfaceControl leash, Rect sourceBounds, Rect destinationBounds, float degrees,
            float positionX, float positionY) {
        mTmpDestinationRect.set(sourceBounds);
        final int dw = destinationBounds.width();
        final int dh = destinationBounds.height();
        // Scale by the short side so there won't be empty area if the aspect ratio of source and
        // destination are different.
        final float scale = dw <= dh
                ? (float) sourceBounds.width() / dw
                : (float) sourceBounds.height() / dh;
        // Inverse scale for crop to fit in screen coordinates.
        mTmpDestinationRect.scale(1 / scale);
        mTmpTransform.setRotate(degrees);
        mTmpTransform.postScale(scale, scale);
        mTmpTransform.postTranslate(positionX, positionY);
        tx.setMatrix(leash, mTmpTransform, mTmpFloat9)
                .setWindowCrop(leash, mTmpDestinationRect.width(), mTmpDestinationRect.height());
        return this;
    }

    /**
     * Resets the scale (setMatrix) on a given transaction and leash if there's any
     *
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper resetScale(SurfaceControl.Transaction tx,
            SurfaceControl leash,
            Rect destinationBounds) {
        tx.setMatrix(leash, Matrix.IDENTITY_MATRIX, mTmpFloat9)
                .setPosition(leash, destinationBounds.left, destinationBounds.top);
        return this;
    }

    /**
     * Operates the round corner radius on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    public PipSurfaceTransactionHelper round(SurfaceControl.Transaction tx, SurfaceControl leash,
            boolean applyCornerRadius) {
        if (mEnableCornerRadius) {
            tx.setCornerRadius(leash, applyCornerRadius ? mCornerRadius : 0);
        }
        return this;
    }

    /**
     * Re-parents the snapshot to the parent's surface control and shows it.
     */
    public PipSurfaceTransactionHelper reparentAndShowSurfaceSnapshot(
            SurfaceControl.Transaction t, SurfaceControl parent, SurfaceControl snapshot) {
        t.reparent(snapshot, parent);
        t.setLayer(snapshot, Integer.MAX_VALUE);
        t.show(snapshot);
        t.apply();
        return this;
    }

    public interface SurfaceControlTransactionFactory {
        SurfaceControl.Transaction getTransaction();
    }
}
