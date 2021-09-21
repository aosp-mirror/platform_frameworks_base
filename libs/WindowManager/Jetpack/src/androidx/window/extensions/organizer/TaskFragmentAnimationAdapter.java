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
    private final Transformation mTransformation = new Transformation();
    private final float[] mMatrix = new float[9];
    private boolean mIsFirstFrame = true;

    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target) {
        mAnimation = animation;
        mTarget = target;
        mLeash = target.leash;
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
    }

    /** Called after animation finished. */
    void onAnimationEnd(@NonNull SurfaceControl.Transaction t) {
        onAnimationUpdate(t, mAnimation.getDuration());
    }

    long getDurationHint() {
        return mAnimation.computeDurationHint();
    }
}
