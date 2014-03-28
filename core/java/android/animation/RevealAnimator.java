/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.animation;

import android.view.View;

import java.util.ArrayList;

/**
 * Reveals a View with an animated clipping circle.
 * The clipping is implemented efficiently by talking to a private reveal API on View.
 * This hidden class currently only accessed by the {@link android.view.View}.
 *
 * @hide
 */
public class RevealAnimator extends ValueAnimator {
    private final static String LOGTAG = "RevealAnimator";
    private ValueAnimator.AnimatorListener mListener;
    private ValueAnimator.AnimatorUpdateListener mUpdateListener;
    private RevealCircle mReuseRevealCircle = new RevealCircle(0);
    private RevealAnimator(final View clipView, final int x, final int y,
            float startRadius, float endRadius, final boolean inverseClip) {

        setObjectValues(new RevealCircle(startRadius), new RevealCircle(endRadius));
        setEvaluator(new RevealCircleEvaluator(mReuseRevealCircle));

        mUpdateListener = new ValueAnimator.AnimatorUpdateListener() {
                @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                RevealCircle circle = (RevealCircle) animation.getAnimatedValue();
                float radius = circle.getRadius();
                clipView.setRevealClip(true, inverseClip, x, y, radius);
            }
        };
        mListener = new AnimatorListenerAdapter() {
                @Override
            public void onAnimationCancel(Animator animation) {
                clipView.setRevealClip(false, false, 0, 0, 0);
            }

                @Override
            public void onAnimationEnd(Animator animation) {
                clipView.setRevealClip(false, false, 0, 0, 0);
            }
        };
        addUpdateListener(mUpdateListener);
        addListener(mListener);
    }

    public static RevealAnimator ofRevealCircle(View clipView, int x, int y,
            float startRadius, float endRadius, boolean inverseClip) {
        RevealAnimator anim = new RevealAnimator(clipView, x, y,
                startRadius, endRadius, inverseClip);
        return anim;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAllUpdateListeners() {
        super.removeAllUpdateListeners();
        addUpdateListener(mUpdateListener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAllListeners() {
        super.removeAllListeners();
        addListener(mListener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ArrayList<AnimatorListener> getListeners() {
        ArrayList<AnimatorListener> allListeners =
                (ArrayList<AnimatorListener>) super.getListeners().clone();
        allListeners.remove(mListener);
        return allListeners;
    }

    private class RevealCircle {
        float mRadius;

        public RevealCircle(float radius) {
            mRadius = radius;
        }

        public void setRadius(float radius) {
            mRadius = radius;
        }

        public float getRadius() {
            return mRadius;
        }
    }

    private class RevealCircleEvaluator implements TypeEvaluator<RevealCircle> {

        private RevealCircle mRevealCircle;

        public RevealCircleEvaluator() {
        }

        public RevealCircleEvaluator(RevealCircle reuseCircle) {
            mRevealCircle = reuseCircle;
        }

        @Override
        public RevealCircle evaluate(float fraction, RevealCircle startValue,
                RevealCircle endValue) {
            float currentRadius = startValue.mRadius
                    + ((endValue.mRadius - startValue.mRadius) * fraction);
            if (mRevealCircle == null) {
                return new RevealCircle(currentRadius);
            } else {
                mRevealCircle.setRadius(currentRadius);
                return mRevealCircle;
            }
        }
    }
}
