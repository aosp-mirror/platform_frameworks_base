/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.RectEvaluator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.util.AttributeSet;
import android.util.Property;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.PathInterpolator;

import com.android.internal.R;

/**
 * EpicenterClipReveal captures the {@link View#getClipBounds()} before and
 * after the scene change and animates between those and the epicenter bounds
 * during a visibility transition.
 */
public class EpicenterClipReveal extends Visibility {
    private static final String PROPNAME_CLIP = "android:epicenterReveal:clip";
    private static final String PROPNAME_BOUNDS = "android:epicenterReveal:bounds";

    private final TimeInterpolator mInterpolatorX;
    private final TimeInterpolator mInterpolatorY;
    private final boolean mCenterClipBounds;

    public EpicenterClipReveal() {
        mInterpolatorX = null;
        mInterpolatorY = null;
        mCenterClipBounds = false;
    }

    public EpicenterClipReveal(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.EpicenterClipReveal, 0, 0);

        mCenterClipBounds = a.getBoolean(R.styleable.EpicenterClipReveal_centerClipBounds, false);

        final int interpolatorX = a.getResourceId(R.styleable.EpicenterClipReveal_interpolatorX, 0);
        if (interpolatorX != 0) {
            mInterpolatorX = AnimationUtils.loadInterpolator(context, interpolatorX);
        } else {
            mInterpolatorX = TransitionConstants.LINEAR_OUT_SLOW_IN;
        }

        final int interpolatorY = a.getResourceId(R.styleable.EpicenterClipReveal_interpolatorY, 0);
        if (interpolatorY != 0) {
            mInterpolatorY = AnimationUtils.loadInterpolator(context, interpolatorY);
        } else {
            mInterpolatorY = TransitionConstants.FAST_OUT_SLOW_IN;
        }

        a.recycle();
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

    private void captureValues(TransitionValues values) {
        final View view = values.view;
        if (view.getVisibility() == View.GONE) {
            return;
        }

        final Rect clip = view.getClipBounds();
        values.values.put(PROPNAME_CLIP, clip);

        if (clip == null) {
            final Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
            values.values.put(PROPNAME_BOUNDS, bounds);
        }
    }

    @Override
    public Animator onAppear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        if (endValues == null) {
            return null;
        }

        final Rect end = getBestRect(endValues);
        final Rect start = getEpicenterOrCenter(end);

        // Prepare the view.
        view.setClipBounds(start);

        return createRectAnimator(view, start, end, endValues, mInterpolatorX, mInterpolatorY);
    }

    @Override
    public Animator onDisappear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        if (startValues == null) {
            return null;
        }

        final Rect start = getBestRect(startValues);
        final Rect end = getEpicenterOrCenter(start);

        // Prepare the view.
        view.setClipBounds(start);

        return createRectAnimator(view, start, end, endValues, mInterpolatorX, mInterpolatorY);
    }

    private Rect getEpicenterOrCenter(Rect bestRect) {
        final Rect epicenter = getEpicenter();
        if (epicenter != null) {
            // Translate the clip bounds to be centered within the target bounds.
            if (mCenterClipBounds) {
                final int offsetX = bestRect.centerX() - epicenter.centerX();
                final int offsetY = bestRect.centerY() - epicenter.centerY();
                epicenter.offset(offsetX, offsetY);
            }
            return epicenter;
        }

        final int centerX = bestRect.centerX();
        final int centerY = bestRect.centerY();
        return new Rect(centerX, centerY, centerX, centerY);
    }

    private Rect getBestRect(TransitionValues values) {
        final Rect clipRect = (Rect) values.values.get(PROPNAME_CLIP);
        if (clipRect == null) {
            return (Rect) values.values.get(PROPNAME_BOUNDS);
        }
        return clipRect;
    }

    private static Animator createRectAnimator(final View view, Rect start, Rect end,
            TransitionValues endValues, TimeInterpolator interpolatorX,
            TimeInterpolator interpolatorY) {
        final RectEvaluator evaluator = new RectEvaluator(new Rect());
        final Rect terminalClip = (Rect) endValues.values.get(PROPNAME_CLIP);

        final ClipDimenProperty propX = new ClipDimenProperty(ClipDimenProperty.TARGET_X);
        final ObjectAnimator animX = ObjectAnimator.ofObject(view, propX, evaluator, start, end);
        if (interpolatorX != null) {
            animX.setInterpolator(interpolatorX);
        }

        final ClipDimenProperty propY = new ClipDimenProperty(ClipDimenProperty.TARGET_Y);
        final ObjectAnimator animY = ObjectAnimator.ofObject(view, propY, evaluator, start, end);
        if (interpolatorY != null) {
            animY.setInterpolator(interpolatorY);
        }

        final AnimatorSet animSet = new AnimatorSet();
        animSet.playTogether(animX, animY);
        animSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setClipBounds(terminalClip);
            }
        });
        return animSet;
    }

    private static class ClipDimenProperty extends Property<View, Rect> {
        public static final char TARGET_X = 'x';
        public static final char TARGET_Y = 'y';

        private final Rect mTempRect = new Rect();

        private final int mTargetDimension;

        public ClipDimenProperty(char targetDimension) {
            super(Rect.class, "clip_bounds_" + targetDimension);

            mTargetDimension = targetDimension;
        }

        @Override
        public Rect get(View object) {
            final Rect tempRect = mTempRect;
            if (!object.getClipBounds(tempRect)) {
                tempRect.setEmpty();
            }
            return tempRect;
        }

        @Override
        public void set(View object, Rect value) {
            final Rect tempRect = mTempRect;
            if (object.getClipBounds(tempRect)) {
                if (mTargetDimension == TARGET_X) {
                    tempRect.left = value.left;
                    tempRect.right = value.right;
                } else {
                    tempRect.top = value.top;
                    tempRect.bottom = value.bottom;
                }
                object.setClipBounds(tempRect);
            }
        }
    }
}
