/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.policy;

import android.graphics.Rect;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.Transformation;

/**
 * Special case of ClipRectAnimation that animates only the top/bottom
 * dimensions of the clip, picking up the other dimensions from whatever is
 * set on the transform already. In addition to that, information about a vertical translation
 * animation can be specified so this animation simulates as the clip would be applied after instead
 * of before applying the translation.
 */
public class ClipRectTBAnimation extends ClipRectAnimation {

    private final int mFromTranslateY;
    private final int mToTranslateY;
    private final Interpolator mTranslateInterpolator;
    private float mNormalizedTime;

    /**
     * Constructor. Passes in 0 for Left/Right parameters of ClipRectAnimation
     */
    public ClipRectTBAnimation(int fromT, int fromB, int toT, int toB,
            int fromTranslateY, int toTranslateY, Interpolator translateInterpolator) {
        super(0, fromT, 0, fromB, 0, toT, 0, toB);
        mFromTranslateY = fromTranslateY;
        mToTranslateY = toTranslateY;
        mTranslateInterpolator = translateInterpolator;
    }

    @Override
    public boolean getTransformation(long currentTime, Transformation outTransformation) {

        // Hack: Because translation animation has a different interpolator, we need to duplicate
        // code from Animation here and use it to calculate/store the uninterpolated normalized
        // time.
        final long startOffset = getStartOffset();
        final long duration = getDuration();
        float normalizedTime;
        if (duration != 0) {
            normalizedTime = ((float) (currentTime - (getStartTime() + startOffset))) /
                    (float) duration;
        } else {
            // time is a step-change with a zero duration
            normalizedTime = currentTime < getStartTime() ? 0.0f : 1.0f;
        }
        mNormalizedTime = normalizedTime;
        return super.getTransformation(currentTime, outTransformation);
    }

    /**
     * Calculates and sets clip rect on given transformation. It uses existing values
     * on the Transformation for Left/Right clip parameters.
     */
    @Override
    protected void applyTransformation(float it, Transformation tr) {
        float translationT = mTranslateInterpolator.getInterpolation(mNormalizedTime);
        int translation =
                (int) (mFromTranslateY + (mToTranslateY - mFromTranslateY) * translationT);
        Rect oldClipRect = tr.getClipRect();
        tr.setClipRect(oldClipRect.left,
                mFromRect.top - translation + (int) ((mToRect.top - mFromRect.top) * it),
                oldClipRect.right,
                mFromRect.bottom - translation + (int) ((mToRect.bottom - mFromRect.bottom) * it));
    }

}
