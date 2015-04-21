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

import com.android.internal.R;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

/**
 * EpicenterTranslate captures the {@link View#getTranslationX()} and
 * {@link View#getTranslationY()} before and after the scene change and
 * animates between those and the epicenter's center during a visibility
 * transition.
 */
public class EpicenterTranslate extends Visibility {
    private static final String PROPNAME_BOUNDS = "android:epicenterReveal:bounds";
    private static final String PROPNAME_TRANSLATE_X = "android:epicenterReveal:translateX";
    private static final String PROPNAME_TRANSLATE_Y = "android:epicenterReveal:translateY";
    private static final String PROPNAME_TRANSLATE_Z = "android:epicenterReveal:translateZ";
    private static final String PROPNAME_Z = "android:epicenterReveal:z";

    private final TimeInterpolator mInterpolatorX;
    private final TimeInterpolator mInterpolatorY;
    private final TimeInterpolator mInterpolatorZ;

    public EpicenterTranslate() {
        mInterpolatorX = null;
        mInterpolatorY = null;
        mInterpolatorZ = null;
    }

    public EpicenterTranslate(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.EpicenterTranslate, 0, 0);

        final int interpolatorX = a.getResourceId(R.styleable.EpicenterTranslate_interpolatorX, 0);
        if (interpolatorX != 0) {
            mInterpolatorX = AnimationUtils.loadInterpolator(context, interpolatorX);
        } else {
            mInterpolatorX = TransitionConstants.FAST_OUT_SLOW_IN;
        }

        final int interpolatorY = a.getResourceId(R.styleable.EpicenterTranslate_interpolatorY, 0);
        if (interpolatorY != 0) {
            mInterpolatorY = AnimationUtils.loadInterpolator(context, interpolatorY);
        } else {
            mInterpolatorY = TransitionConstants.FAST_OUT_SLOW_IN;
        }

        final int interpolatorZ = a.getResourceId(R.styleable.EpicenterTranslate_interpolatorZ, 0);
        if (interpolatorZ != 0) {
            mInterpolatorZ = AnimationUtils.loadInterpolator(context, interpolatorZ);
        } else {
            mInterpolatorZ = TransitionConstants.FAST_OUT_SLOW_IN;
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

        final Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        values.values.put(PROPNAME_BOUNDS, bounds);
        values.values.put(PROPNAME_TRANSLATE_X, view.getTranslationX());
        values.values.put(PROPNAME_TRANSLATE_Y, view.getTranslationY());
        values.values.put(PROPNAME_TRANSLATE_Z, view.getTranslationZ());
        values.values.put(PROPNAME_Z, view.getZ());
    }

    @Override
    public Animator onAppear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        if (endValues == null) {
            return null;
        }

        final Rect end = (Rect) endValues.values.get(PROPNAME_BOUNDS);
        final Rect start = getEpicenterOrCenter(end);
        final float startX = start.centerX() - end.centerX();
        final float startY = start.centerY() - end.centerY();
        final float startZ = 0 - (float) endValues.values.get(PROPNAME_Z);

        // Translate the view to be centered on the epicenter.
        view.setTranslationX(startX);
        view.setTranslationY(startY);
        view.setTranslationZ(startZ);

        final float endX = (float) endValues.values.get(PROPNAME_TRANSLATE_X);
        final float endY = (float) endValues.values.get(PROPNAME_TRANSLATE_Y);
        final float endZ = (float) endValues.values.get(PROPNAME_TRANSLATE_Z);
        return createAnimator(view, startX, startY, startZ, endX, endY, endZ,
                mInterpolatorX, mInterpolatorY, mInterpolatorZ);
    }

    @Override
    public Animator onDisappear(ViewGroup sceneRoot, View view,
            TransitionValues startValues, TransitionValues endValues) {
        if (startValues == null) {
            return null;
        }

        final Rect start = (Rect) endValues.values.get(PROPNAME_BOUNDS);
        final Rect end = getEpicenterOrCenter(start);
        final float endX = end.centerX() - start.centerX();
        final float endY = end.centerY() - start.centerY();
        final float endZ = 0 - (float) startValues.values.get(PROPNAME_Z);

        final float startX = (float) endValues.values.get(PROPNAME_TRANSLATE_X);
        final float startY = (float) endValues.values.get(PROPNAME_TRANSLATE_Y);
        final float startZ = (float) endValues.values.get(PROPNAME_TRANSLATE_Z);
        return createAnimator(view, startX, startY, startZ, endX, endY, endZ,
                mInterpolatorX, mInterpolatorY, mInterpolatorZ);
    }

    private Rect getEpicenterOrCenter(Rect bestRect) {
        final Rect epicenter = getEpicenter();
        if (epicenter != null) {
            return epicenter;
        }

        final int centerX = bestRect.centerX();
        final int centerY = bestRect.centerY();
        return new Rect(centerX, centerY, centerX, centerY);
    }

    private static Animator createAnimator(final View view, float startX, float startY,
            float startZ, float endX, float endY, float endZ, TimeInterpolator interpolatorX,
            TimeInterpolator interpolatorY, TimeInterpolator interpolatorZ) {
        final ObjectAnimator animX = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, startX, endX);
        if (interpolatorX != null) {
            animX.setInterpolator(interpolatorX);
        }

        final ObjectAnimator animY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, startY, endY);
        if (interpolatorY != null) {
            animY.setInterpolator(interpolatorY);
        }

        final ObjectAnimator animZ = ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, startZ, endZ);
        if (interpolatorZ != null) {
            animZ.setInterpolator(interpolatorZ);
        }

        final AnimatorSet animSet = new AnimatorSet();
        animSet.playTogether(animX, animY, animZ);
        return animSet;
    }
}
