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
package android.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.FloatMath;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes and moves views in or out from the edges of the
 * scene. Visibility is determined by both the
 * {@link View#setVisibility(int)} state of the view as well as whether it
 * is parented in the current view hierarchy. Disappearing Views are
 * limited as described in {@link Visibility#onDisappear(android.view.ViewGroup,
 * TransitionValues, int, TransitionValues, int)}.
 * <p>Views move away from the focal View or the center of the Scene if
 * no epicenter was provided.</p>
 */
public class Explode extends Visibility {
    private static final TimeInterpolator sDecelerate = new DecelerateInterpolator();
    private static final TimeInterpolator sAccelerate = new AccelerateInterpolator();
    private static final String TAG = "Explode";

    private static final String PROPNAME_SCREEN_BOUNDS = "android:out:screenBounds";

    private int[] mTempLoc = new int[2];

    public Explode() {
        setPropagation(new CircularPropagation());
    }

    private void captureValues(TransitionValues transitionValues) {
        View view = transitionValues.view;
        view.getLocationOnScreen(mTempLoc);
        int left = mTempLoc[0] + Math.round(view.getTranslationX());
        int top = mTempLoc[1] + Math.round(view.getTranslationY());
        int right = left + view.getWidth();
        int bottom = top + view.getHeight();
        transitionValues.values.put(PROPNAME_SCREEN_BOUNDS, new Rect(left, top, right, bottom));
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        super.captureStartValues(transitionValues);
        captureValues(transitionValues);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        super.captureEndValues(transitionValues);
        captureValues(transitionValues);
    }

    private Animator createAnimation(final View view, float startX, float startY, float endX,
            float endY, float terminalX, float terminalY, TimeInterpolator interpolator) {
        view.setTranslationX(startX);
        view.setTranslationY(startY);
        if (startY == endY && startX == endX) {
            return null;
        }
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        ObjectAnimator pathAnimator = ObjectAnimator.ofFloat(view, View.TRANSLATION_X,
                View.TRANSLATION_Y, path);
        pathAnimator.setInterpolator(interpolator);
        OutAnimatorListener listener = new OutAnimatorListener(view, terminalX, terminalY,
                endX, endY);
        pathAnimator.addListener(listener);
        pathAnimator.addPauseListener(listener);

        return pathAnimator;
    }

    @Override
    public Animator onAppear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        if (endValues == null) {
            return null;
        }
        Rect bounds = (Rect) endValues.values.get(PROPNAME_SCREEN_BOUNDS);
        calculateOut(sceneRoot, bounds, mTempLoc);

        final float endX = view.getTranslationX();
        final float startX = endX + mTempLoc[0];
        final float endY = view.getTranslationY();
        final float startY = endY + mTempLoc[1];

        return createAnimation(view, startX, startY, endX, endY, endX, endY, sDecelerate);
    }

    @Override
    public Animator onDisappear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        Rect bounds = (Rect) startValues.values.get(PROPNAME_SCREEN_BOUNDS);
        calculateOut(sceneRoot, bounds, mTempLoc);

        final float startX = view.getTranslationX();
        final float endX = startX + mTempLoc[0];
        final float startY = view.getTranslationY();
        final float endY = startY + mTempLoc[1];

        return createAnimation(view, startX, startY, endX, endY, startX, startY,
                sAccelerate);
    }

    private void calculateOut(View sceneRoot, Rect bounds, int[] outVector) {
        sceneRoot.getLocationOnScreen(mTempLoc);
        int sceneRootX = mTempLoc[0];
        int sceneRootY = mTempLoc[1];
        int focalX;
        int focalY;

        Rect epicenter = getEpicenter();
        if (epicenter == null) {
            focalX = sceneRootX + (sceneRoot.getWidth() / 2)
                    + Math.round(sceneRoot.getTranslationX());
            focalY = sceneRootY + (sceneRoot.getHeight() / 2)
                    + Math.round(sceneRoot.getTranslationY());
        } else {
            focalX = epicenter.centerX();
            focalY = epicenter.centerY();
        }

        int centerX = bounds.centerX();
        int centerY = bounds.centerY();
        float xVector = centerX - focalX;
        float yVector = centerY - focalY;

        if (xVector == 0 && yVector == 0) {
            // Random direction when View is centered on focal View.
            xVector = (float)(Math.random() * 2) - 1;
            yVector = (float)(Math.random() * 2) - 1;
        }
        float vectorSize = calculateDistance(xVector, yVector);
        xVector /= vectorSize;
        yVector /= vectorSize;

        float maxDistance =
                calculateMaxDistance(sceneRoot, focalX - sceneRootX, focalY - sceneRootY);

        outVector[0] = Math.round(maxDistance * xVector);
        outVector[1] = Math.round(maxDistance * yVector);
    }

    private static float calculateMaxDistance(View sceneRoot, int focalX, int focalY) {
        int maxX = Math.max(focalX, sceneRoot.getWidth() - focalX);
        int maxY = Math.max(focalY, sceneRoot.getHeight() - focalY);
        return calculateDistance(maxX, maxY);
    }

    private static float calculateDistance(float x, float y) {
        return FloatMath.sqrt((x * x) + (y * y));
    }

    private static class OutAnimatorListener extends AnimatorListenerAdapter {
        private final View mView;
        private boolean mCanceled = false;
        private float mPausedX;
        private float mPausedY;
        private final float mTerminalX;
        private final float mTerminalY;
        private final float mEndX;
        private final float mEndY;

        public OutAnimatorListener(View view, float terminalX, float terminalY,
                float endX, float endY) {
            mView = view;
            mTerminalX = terminalX;
            mTerminalY = terminalY;
            mEndX = endX;
            mEndY = endY;
        }

        @Override
        public void onAnimationCancel(Animator animator) {
            mView.setTranslationX(mTerminalX);
            mView.setTranslationY(mTerminalY);
            mCanceled = true;
        }

        @Override
        public void onAnimationEnd(Animator animator) {
            if (!mCanceled) {
                mView.setTranslationX(mTerminalX);
                mView.setTranslationY(mTerminalY);
            }
        }

        @Override
        public void onAnimationPause(Animator animator) {
            mPausedX = mView.getTranslationX();
            mPausedY = mView.getTranslationY();
            mView.setTranslationY(mEndX);
            mView.setTranslationY(mEndY);
        }

        @Override
        public void onAnimationResume(Animator animator) {
            mView.setTranslationX(mPausedX);
            mView.setTranslationY(mPausedY);
        }
    }
}
