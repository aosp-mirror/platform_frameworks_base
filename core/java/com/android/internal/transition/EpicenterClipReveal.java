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
import android.animation.ObjectAnimator;
import android.animation.RectEvaluator;
import android.content.Context;
import android.graphics.Rect;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * EpicenterClipReveal captures the {@link View#getClipBounds()} before and
 * after the scene change and animates between those and the epicenter bounds
 * during a visibility transition.
 */
public class EpicenterClipReveal extends Visibility {
    private static final String PROPNAME_CLIP = "android:epicenterReveal:clip";
    private static final String PROPNAME_BOUNDS = "android:epicenterReveal:bounds";

    public EpicenterClipReveal() {}

    public EpicenterClipReveal(Context context, AttributeSet attrs) {
        super(context, attrs);
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

        return createRectAnimator(view, start, end, endValues);
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

        return createRectAnimator(view, start, end, endValues);
    }

    private Rect getEpicenterOrCenter(Rect bestRect) {
        final Rect epicenter = getEpicenter();
        if (epicenter != null) {
            return epicenter;
        }

        int centerX = bestRect.centerX();
        int centerY = bestRect.centerY();
        return new Rect(centerX, centerY, centerX, centerY);
    }

    private Rect getBestRect(TransitionValues values) {
        final Rect clipRect = (Rect) values.values.get(PROPNAME_CLIP);
        if (clipRect == null) {
            return (Rect) values.values.get(PROPNAME_BOUNDS);
        }
        return clipRect;
    }

    private Animator createRectAnimator(final View view, Rect start, Rect end,
            TransitionValues endValues) {
        final Rect terminalClip = (Rect) endValues.values.get(PROPNAME_CLIP);
        final RectEvaluator evaluator = new RectEvaluator(new Rect());
        ObjectAnimator anim = ObjectAnimator.ofObject(view, "clipBounds", evaluator, start, end);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setClipBounds(terminalClip);
            }
        });
        return anim;
    }
}
