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
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

public class BarTransitions {
    private static final boolean DEBUG = false;

    public static final int MODE_OPAQUE = 0;
    public static final int MODE_SEMI_TRANSPARENT = 1;
    public static final int MODE_TRANSPARENT = 2;

    private final String mTag;
    protected final View mTarget;
    protected final int mOpaque;
    protected final int mSemiTransparent;

    protected Drawable mTransparent;
    private int mMode;
    private ValueAnimator mBackgroundColorAnimator;

    private final AnimatorUpdateListener mBackgroundColorListener = new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animator) {
            mTarget.setBackgroundColor((Integer) animator.getAnimatedValue());
        }
    };

    public BarTransitions(Context context, View target) {
        mTag = "BarTransitions." + target.getClass().getSimpleName();
        mTarget = target;
        final Resources res = context.getResources();
        mOpaque = res.getColor(R.drawable.status_bar_background);
        mSemiTransparent = res.getColor(R.color.status_bar_background_semi_transparent);
    }

    public void setTransparent(Drawable transparent) {
        mTransparent = transparent;
        if (mMode == MODE_TRANSPARENT) {
            transitionTo(MODE_TRANSPARENT);
        }
    }

    public void transitionTo(int mode) {
        transitionTo(mode, false);
    }

    public void transitionTo(int mode, boolean animate) {
        if (mMode == mode) return;
        int oldMode = mMode;
        mMode = mode;
        if (!ActivityManager.isHighEndGfx()) return;
        if (DEBUG) Log.d(mTag, modeToString(oldMode) + " -> " + modeToString(mode));
        onTransition(oldMode, mMode, animate);
    }

    protected void onTransition(int oldMode, int newMode, boolean animate) {
        cancelBackgroundColorAnimation();
        if (animate && oldMode == MODE_SEMI_TRANSPARENT && newMode == MODE_OPAQUE) {
            startBackgroundColorAnimation(mSemiTransparent, mOpaque);
        } else if (animate && oldMode == MODE_OPAQUE && newMode == MODE_SEMI_TRANSPARENT) {
            startBackgroundColorAnimation(mOpaque, mSemiTransparent);
        } else if (newMode == MODE_OPAQUE || newMode == MODE_SEMI_TRANSPARENT) {
            mTarget.setBackgroundColor(newMode == MODE_OPAQUE ? mOpaque : mSemiTransparent);
        } else {
            mTarget.setBackground(newMode == MODE_TRANSPARENT? mTransparent
                    : newMode == MODE_SEMI_TRANSPARENT ? new ColorDrawable(mSemiTransparent)
                    : new ColorDrawable(mOpaque));
        }
    }

    private void startBackgroundColorAnimation(int from, int to) {
        mBackgroundColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        mBackgroundColorAnimator.addUpdateListener(mBackgroundColorListener);
        mBackgroundColorAnimator.start();
    }

    private void cancelBackgroundColorAnimation() {
        if (mBackgroundColorAnimator != null && mBackgroundColorAnimator.isStarted()) {
            mBackgroundColorAnimator.cancel();
            mBackgroundColorAnimator = null;
        }
    }

    public static String modeToString(int mode) {
        if (mode == MODE_OPAQUE) return "MODE_OPAQUE";
        if (mode == MODE_SEMI_TRANSPARENT) return "MODE_SEMI_TRANSPARENT";
        if (mode == MODE_TRANSPARENT) return "MODE_TRANSPARENT";
        throw new IllegalArgumentException("Unknown mode " + mode);
    }
}
