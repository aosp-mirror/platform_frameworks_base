/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.ActivityManager;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

public class BarTransitions {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_COLORS = false;

    public static final int MODE_OPAQUE = 0;
    public static final int MODE_SEMI_TRANSPARENT = 1;
    public static final int MODE_TRANSLUCENT = 2;
    public static final int MODE_LIGHTS_OUT = 3;

    public static final int LIGHTS_IN_DURATION = 250;
    public static final int LIGHTS_OUT_DURATION = 750;
    public static final int BACKGROUND_DURATION = 200;

    private final String mTag;
    private final View mView;
    private final boolean mSupportsTransitions = ActivityManager.isHighEndGfx();

    private final int mOpaque;
    private final int mSemiTransparent;

    private int mMode;
    private ValueAnimator mColorDrawableAnimator;
    private boolean mColorDrawableShowing;

    private final ColorDrawable mColorDrawable;
    private final TransitionDrawable mTransitionDrawable;
    private final AnimatorUpdateListener mAnimatorListener = new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animator) {
            mColorDrawable.setColor((Integer) animator.getAnimatedValue());
        }
    };

    public BarTransitions(View view, int gradientResourceId) {
        mTag = "BarTransitions." + view.getClass().getSimpleName();
        mView = view;
        final Resources res = mView.getContext().getResources();

        if (DEBUG_COLORS) {
            mOpaque = 0xff0000ff;
            mSemiTransparent = 0x7f0000ff;
        } else {
            mOpaque = res.getColor(R.color.system_bar_background_opaque);
            mSemiTransparent = res.getColor(R.color.system_bar_background_semi_transparent);
        }

        mColorDrawable = new ColorDrawable(mOpaque);
        mTransitionDrawable = new TransitionDrawable(
                new Drawable[] { res.getDrawable(gradientResourceId), mColorDrawable });
        mTransitionDrawable.setCrossFadeEnabled(true);
        mTransitionDrawable.resetTransition();
        if (mSupportsTransitions) {
            mView.setBackground(mTransitionDrawable);
        }
    }

    public int getMode() {
        return mMode;
    }

    public void transitionTo(int mode, boolean animate) {
        if (mMode == mode) return;
        int oldMode = mMode;
        mMode = mode;
        if (DEBUG) Log.d(mTag, String.format("%s -> %s animate=%s",
                modeToString(oldMode), modeToString(mode),  animate));
        if (mSupportsTransitions) {
            onTransition(oldMode, mMode, animate);
        }
    }

    private Integer getBackgroundColor(int mode) {
        if (mode == MODE_SEMI_TRANSPARENT) return mSemiTransparent;
        if (mode == MODE_OPAQUE) return mOpaque;
        if (mode == MODE_LIGHTS_OUT) return mOpaque;
        return null;
    }

    protected void onTransition(int oldMode, int newMode, boolean animate) {
        applyModeBackground(oldMode, newMode, animate);
    }

    protected void applyModeBackground(int oldMode, int newMode, boolean animate) {
        if (DEBUG) Log.d(mTag, String.format("applyModeBackground %s animate=%s",
                modeToString(newMode), animate));
        cancelColorAnimation();
        Integer oldColor = getBackgroundColor(oldMode);
        Integer newColor = getBackgroundColor(newMode);
        if (newColor != null) {
            if (animate && oldColor != null && !oldColor.equals(newColor)) {
                startColorAnimation(oldColor, newColor);
            } else if (!newColor.equals(mColorDrawable.getColor())) {
                if (DEBUG) Log.d(mTag, String.format("setColor = %08x", newColor));
                mColorDrawable.setColor(newColor);
            }
        }
        if (newColor == null && mColorDrawableShowing) {
            if (DEBUG) Log.d(mTag, "Hide color layer");
            if (animate) {
                mTransitionDrawable.reverseTransition(BACKGROUND_DURATION);
            } else {
                mTransitionDrawable.resetTransition();
            }
            mColorDrawableShowing = false;
        } else if (newColor != null && !mColorDrawableShowing) {
            if (DEBUG) Log.d(mTag, "Show color layer");
            mTransitionDrawable.startTransition(animate ? BACKGROUND_DURATION : 0);
            mColorDrawableShowing = true;
        }
    }

    private void startColorAnimation(int from, int to) {
        if (DEBUG) Log.d(mTag, String.format("startColorAnimation %08x -> %08x", from, to));
        mColorDrawableAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        mColorDrawableAnimator.addUpdateListener(mAnimatorListener);
        mColorDrawableAnimator.start();
    }

    private void cancelColorAnimation() {
        if (mColorDrawableAnimator != null && mColorDrawableAnimator.isStarted()) {
            mColorDrawableAnimator.cancel();
            mColorDrawableAnimator = null;
        }
    }

    public static String modeToString(int mode) {
        if (mode == MODE_OPAQUE) return "MODE_OPAQUE";
        if (mode == MODE_SEMI_TRANSPARENT) return "MODE_SEMI_TRANSPARENT";
        if (mode == MODE_TRANSLUCENT) return "MODE_TRANSLUCENT";
        if (mode == MODE_LIGHTS_OUT) return "MODE_LIGHTS_OUT";
        throw new IllegalArgumentException("Unknown mode " + mode);
    }
}
