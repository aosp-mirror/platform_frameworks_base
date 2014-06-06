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
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.util.Log;
import android.util.Property;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * This transition tracks changes to the visibility of target views in the
 * start and end scenes and moves views in or out from one of the edges of the
 * scene. Visibility is determined by both the
 * {@link View#setVisibility(int)} state of the view as well as whether it
 * is parented in the current view hierarchy. Disappearing Views are
 * limited as described in {@link Visibility#onDisappear(android.view.ViewGroup,
 * TransitionValues, int, TransitionValues, int)}.
 */
public class Slide extends Visibility {
    private static final String TAG = "Slide";

    private static final TimeInterpolator sDecelerate = new DecelerateInterpolator();
    private static final TimeInterpolator sAccelerate = new AccelerateInterpolator();

    private CalculateSlide mSlideCalculator = sCalculateBottom;

    private interface CalculateSlide {
        /** Returns the translation value for view when it out of the scene */
        float getGone(ViewGroup sceneRoot, View view);

        /** Returns the translation value for view when it is in the scene */
        float getHere(View view);

        /** Returns the property to animate translation */
        Property<View, Float> getProperty();
    }

    private static abstract class CalculateSlideHorizontal implements CalculateSlide {
        @Override
        public float getHere(View view) {
            return view.getTranslationX();
        }

        @Override
        public Property<View, Float> getProperty() {
            return View.TRANSLATION_X;
        }
    }

    private static abstract class CalculateSlideVertical implements CalculateSlide {
        @Override
        public float getHere(View view) {
            return view.getTranslationY();
        }

        @Override
        public Property<View, Float> getProperty() {
            return View.TRANSLATION_Y;
        }
    }

    private static final CalculateSlide sCalculateLeft = new CalculateSlideHorizontal() {
        @Override
        public float getGone(ViewGroup sceneRoot, View view) {
            return view.getTranslationX() - sceneRoot.getWidth();
        }
    };

    private static final CalculateSlide sCalculateTop = new CalculateSlideVertical() {
        @Override
        public float getGone(ViewGroup sceneRoot, View view) {
            return view.getTranslationY() - sceneRoot.getHeight();
        }
    };

    private static final CalculateSlide sCalculateRight = new CalculateSlideHorizontal() {
        @Override
        public float getGone(ViewGroup sceneRoot, View view) {
            return view.getTranslationX() + sceneRoot.getWidth();
        }
    };

    private static final CalculateSlide sCalculateBottom = new CalculateSlideVertical() {
        @Override
        public float getGone(ViewGroup sceneRoot, View view) {
            return view.getTranslationY() + sceneRoot.getHeight();
        }
    };

    /**
     * Constructor using the default {@link Gravity#BOTTOM}
     * slide edge direction.
     */
    public Slide() {
        setSlideEdge(Gravity.BOTTOM);
    }

    /**
     * Constructor using the provided slide edge direction.
     */
    public Slide(int slideEdge) {
        setSlideEdge(slideEdge);
    }

    /**
     * Change the edge that Views appear and disappear from.
     * @param slideEdge The edge of the scene to use for Views appearing and disappearing. One of
     *                  {@link android.view.Gravity#LEFT}, {@link android.view.Gravity#TOP},
     *                  {@link android.view.Gravity#RIGHT}, {@link android.view.Gravity#BOTTOM}.
     */
    public void setSlideEdge(int slideEdge) {
        switch (slideEdge) {
            case Gravity.LEFT:
                mSlideCalculator = sCalculateLeft;
                break;
            case Gravity.TOP:
                mSlideCalculator = sCalculateTop;
                break;
            case Gravity.RIGHT:
                mSlideCalculator = sCalculateRight;
                break;
            case Gravity.BOTTOM:
                mSlideCalculator = sCalculateBottom;
                break;
            default:
                throw new IllegalArgumentException("Invalid slide direction");
        }
        SidePropagation propagation = new SidePropagation();
        propagation.setSide(slideEdge);
        setPropagation(propagation);
    }

    private Animator createAnimation(final View view, Property<View, Float> property,
            float start, float end, float terminalValue, TimeInterpolator interpolator) {
        view.setTranslationY(start);
        if (start == end) {
            return null;
        }
        final ObjectAnimator anim = ObjectAnimator.ofFloat(view, property, start, end);

        SlideAnimatorListener listener = new SlideAnimatorListener(view, terminalValue, end);
        anim.addListener(listener);
        anim.addPauseListener(listener);
        anim.setInterpolator(interpolator);
        return anim;
    }

    @Override
    public Animator onAppear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        if (endValues == null) {
            return null;
        }
        float end = mSlideCalculator.getHere(view);
        float start = mSlideCalculator.getGone(sceneRoot, view);
        return createAnimation(view, mSlideCalculator.getProperty(), start, end, end, sDecelerate);
    }

    @Override
    public Animator onDisappear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        float start = mSlideCalculator.getHere(view);
        float end = mSlideCalculator.getGone(sceneRoot, view);

        return createAnimation(view, mSlideCalculator.getProperty(), start, end, start,
                sAccelerate);
    }

    private static class SlideAnimatorListener extends AnimatorListenerAdapter {
        private boolean mCanceled = false;
        private float mPausedY;
        private final View mView;
        private final float mEndY;
        private final float mTerminalY;

        public SlideAnimatorListener(View view, float terminalY, float endY) {
            mView = view;
            mTerminalY = terminalY;
            mEndY = endY;
        }

        @Override
        public void onAnimationCancel(Animator animator) {
            mView.setTranslationY(mTerminalY);
            mCanceled = true;
        }

        @Override
        public void onAnimationEnd(Animator animator) {
            if (!mCanceled) {
                mView.setTranslationY(mTerminalY);
            }
        }

        @Override
        public void onAnimationPause(Animator animator) {
            mPausedY = mView.getTranslationY();
            mView.setTranslationY(mEndY);
        }

        @Override
        public void onAnimationResume(Animator animator) {
            mView.setTranslationY(mPausedY);
        }
    }
}
