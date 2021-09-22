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

package androidx.window.extensions.organizer;

import android.graphics.Rect;
import android.view.Choreographer;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import androidx.annotation.NonNull;

/**
 * Wrapper to handle the TaskFragment animation update in one {@link SurfaceControl.Transaction}.
 */
class TaskFragmentAnimationAdapter {
    private final Animation mAnimation;
    private final RemoteAnimationTarget mTarget;
    private final SurfaceControl mLeash;
    private final boolean mSizeChanged;
    private final Transformation mTransformation = new Transformation();
    private final float[] mMatrix = new float[9];
    private final float[] mVecs = new float[4];
    private final Rect mRect = new Rect();
    private boolean mIsFirstFrame = true;

    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target) {
        this(animation, target, target.leash, false /* sizeChanged */);
    }

    /**
     * @param sizeChanged whether the surface size needs to be changed.
     */
    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target, @NonNull SurfaceControl leash,
            boolean sizeChanged) {
        mAnimation = animation;
        mTarget = target;
        mLeash = leash;
        mSizeChanged = sizeChanged;
    }

    /** Called on frame update. */
    void onAnimationUpdate(@NonNull SurfaceControl.Transaction t, long currentPlayTime) {
        if (mIsFirstFrame) {
            t.show(mLeash);
            mIsFirstFrame = false;
        }

        currentPlayTime = Math.min(currentPlayTime, mAnimation.getDuration());
        mAnimation.getTransformation(currentPlayTime, mTransformation);
        mTransformation.getMatrix().postTranslate(
                mTarget.localBounds.left, mTarget.localBounds.top);
        t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
        t.setAlpha(mLeash, mTransformation.getAlpha());
        t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());

        if (mSizeChanged) {
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

    /** Called after animation finished. */
    void onAnimationEnd(@NonNull SurfaceControl.Transaction t) {
        onAnimationUpdate(t, mAnimation.getDuration());
    }

    long getDurationHint() {
        return mAnimation.computeDurationHint();
    }
}
