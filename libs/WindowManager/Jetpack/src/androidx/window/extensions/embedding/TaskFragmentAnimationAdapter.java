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

package androidx.window.extensions.embedding;

import static android.graphics.Matrix.MTRANS_X;
import static android.graphics.Matrix.MTRANS_Y;

import android.graphics.Rect;
import android.view.Choreographer;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import androidx.annotation.NonNull;

/**
 * Wrapper to handle the TaskFragment animation update in one {@link SurfaceControl.Transaction}.
 *
 * The base adapter can be used for {@link RemoteAnimationTarget} that is simple open/close.
 */
class TaskFragmentAnimationAdapter {

    /**
     * If {@link #mOverrideLayer} is set to this value, we don't want to override the surface layer.
     */
    private static final int LAYER_NO_OVERRIDE = -1;

    @NonNull
    final Animation mAnimation;
    @NonNull
    final RemoteAnimationTarget mTarget;
    @NonNull
    final SurfaceControl mLeash;
    /** Area in absolute coordinate that the animation surface shouldn't go beyond. */
    @NonNull
    private final Rect mWholeAnimationBounds = new Rect();

    @NonNull
    final Transformation mTransformation = new Transformation();
    @NonNull
    final float[] mMatrix = new float[9];
    @NonNull
    final float[] mVecs = new float[4];
    @NonNull
    final Rect mRect = new Rect();
    private boolean mIsFirstFrame = true;
    private int mOverrideLayer = LAYER_NO_OVERRIDE;

    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target) {
        this(animation, target, target.leash, target.screenSpaceBounds);
    }

    /**
     * @param leash the surface to animate.
     * @param wholeAnimationBounds  area in absolute coordinate that the animation surface shouldn't
     *                              go beyond.
     */
    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target, @NonNull SurfaceControl leash,
            @NonNull Rect wholeAnimationBounds) {
        mAnimation = animation;
        mTarget = target;
        mLeash = leash;
        mWholeAnimationBounds.set(wholeAnimationBounds);
    }

    /**
     * Surface layer to be set at the first frame of the animation. We will not set the layer if it
     * is set to {@link #LAYER_NO_OVERRIDE}.
     */
    final void overrideLayer(int layer) {
        mOverrideLayer = layer;
    }

    /** Called on frame update. */
    final void onAnimationUpdate(@NonNull SurfaceControl.Transaction t, long currentPlayTime) {
        if (mIsFirstFrame) {
            t.show(mLeash);
            if (mOverrideLayer != LAYER_NO_OVERRIDE) {
                t.setLayer(mLeash, mOverrideLayer);
            }
            mIsFirstFrame = false;
        }

        // Extract the transformation to the current time.
        mAnimation.getTransformation(Math.min(currentPlayTime, mAnimation.getDuration()),
                mTransformation);
        t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());
        onAnimationUpdateInner(t);
    }

    /** To be overridden by subclasses to adjust the animation surface change. */
    void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
        // Update the surface position and alpha.
        mTransformation.getMatrix().postTranslate(
                mTarget.localBounds.left, mTarget.localBounds.top);
        t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
        t.setAlpha(mLeash, mTransformation.getAlpha());

        // Get current surface bounds in absolute coordinate.
        // positionX/Y are in local coordinate, so minus the local offset to get the slide amount.
        final int positionX = Math.round(mMatrix[MTRANS_X]);
        final int positionY = Math.round(mMatrix[MTRANS_Y]);
        final Rect cropRect = new Rect(mTarget.screenSpaceBounds);
        final Rect localBounds = mTarget.localBounds;
        cropRect.offset(positionX - localBounds.left, positionY - localBounds.top);

        // Store the current offset of the surface top left from (0,0) in absolute coordinate.
        final int offsetX = cropRect.left;
        final int offsetY = cropRect.top;

        // Intersect to make sure the animation happens within the whole animation bounds.
        if (!cropRect.intersect(mWholeAnimationBounds)) {
            // Hide the surface when it is outside of the animation area.
            t.setAlpha(mLeash, 0);
        }

        // cropRect is in absolute coordinate, so we need to translate it to surface top left.
        cropRect.offset(-offsetX, -offsetY);
        t.setCrop(mLeash, cropRect);
    }

    /** Called after animation finished. */
    final void onAnimationEnd(@NonNull SurfaceControl.Transaction t) {
        onAnimationUpdate(t, mAnimation.getDuration());
    }

    final long getDurationHint() {
        return mAnimation.computeDurationHint();
    }

    /**
     * Should be used for the animation of the snapshot of a {@link RemoteAnimationTarget} that has
     * size change.
     */
    static class SnapshotAdapter extends TaskFragmentAnimationAdapter {

        SnapshotAdapter(@NonNull Animation animation, @NonNull RemoteAnimationTarget target) {
            // Start leash is the snapshot of the starting surface.
            super(animation, target, target.startLeash, target.screenSpaceBounds);
        }

        @Override
        void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
            // Snapshot should always be placed at the top left of the animation leash.
            mTransformation.getMatrix().postTranslate(0, 0);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());
        }
    }

    /**
     * Should be used for the animation of the {@link RemoteAnimationTarget} that has size change.
     */
    static class BoundsChangeAdapter extends TaskFragmentAnimationAdapter {

        BoundsChangeAdapter(@NonNull Animation animation, @NonNull RemoteAnimationTarget target) {
            super(animation, target);
        }

        @Override
        void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
            mTransformation.getMatrix().postTranslate(
                    mTarget.localBounds.left, mTarget.localBounds.top);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());

            // The following applies an inverse scale to the clip-rect so that it crops "after" the
            // scale instead of before.
            mVecs[1] = mVecs[2] = 0;
            mVecs[0] = mVecs[3] = 1;
            mTransformation.getMatrix().mapVectors(mVecs);
            mVecs[0] = 1.f / mVecs[0];
            mVecs[3] = 1.f / mVecs[3];
            final Rect clipRect = mTransformation.getClipRect();
            mRect.left = (int) (clipRect.left * mVecs[0] + 0.5f);
            mRect.right = (int) (clipRect.right * mVecs[0] + 0.5f);
            mRect.top = (int) (clipRect.top * mVecs[3] + 0.5f);
            mRect.bottom = (int) (clipRect.bottom * mVecs[3] + 0.5f);
            t.setWindowCrop(mLeash, mRect);
        }
    }
}
