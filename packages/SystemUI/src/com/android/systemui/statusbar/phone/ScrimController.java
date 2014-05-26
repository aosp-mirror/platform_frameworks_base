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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
public class ScrimController implements ViewTreeObserver.OnPreDrawListener {

    private static final float SCRIM_BEHIND_ALPHA = 0.62f;
    private static final float SCRIM_BEHIND_ALPHA_KEYGUARD = 0.5f;
    private static final float SCRIM_IN_FRONT_ALPHA = 0.75f;
    private static final long ANIMATION_DURATION = 220;

    private final View mScrimBehind;
    private final View mScrimInFront;
    private final UnlockMethodCache mUnlockMethodCache;

    private boolean mKeyguardShowing;
    private float mFraction;

    private boolean mDarkenWhileDragging;
    private boolean mBouncerShowing;
    private boolean mAnimateChange;
    private boolean mUpdatePending;

    private final Interpolator mInterpolator = new DecelerateInterpolator();

    public ScrimController(View scrimBehind, View scrimInFront) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        mUnlockMethodCache = UnlockMethodCache.getInstance(scrimBehind.getContext());
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
        scheduleUpdate();
    }

    public void onTrackingStarted() {
        mDarkenWhileDragging = !mUnlockMethodCache.isMethodInsecure();
    }

    public void setPanelExpansion(float fraction) {
        mFraction = fraction;
        scheduleUpdate();
    }

    public void setBouncerShowing(boolean showing) {
        mBouncerShowing = showing;
        mAnimateChange = true;
        scheduleUpdate();
    }

    private void scheduleUpdate() {
        if (mUpdatePending) return;
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    private void updateScrims() {
        if (!mKeyguardShowing) {
            updateScrimNormal();
            setScrimInFrontColor(0);
        } else {
            updateScrimKeyguard();
        }
        mAnimateChange = false;
    }

    private void updateScrimKeyguard() {
        if (mBouncerShowing) {
            setScrimInFrontColor(SCRIM_IN_FRONT_ALPHA);
            setScrimBehindColor(0f);
        } else if (mDarkenWhileDragging) {
            float behindFraction = Math.max(0, Math.min(mFraction, 1));
            float fraction = 1 - behindFraction;
            setScrimInFrontColor(fraction * SCRIM_IN_FRONT_ALPHA);
            setScrimBehindColor(behindFraction * SCRIM_BEHIND_ALPHA_KEYGUARD);
        } else {
            setScrimInFrontColor(0f);
            setScrimBehindColor(SCRIM_BEHIND_ALPHA_KEYGUARD);
        }
    }

    private void updateScrimNormal() {
        float frac = mFraction;
        // let's start this 20% of the way down the screen
        frac = frac * 1.2f - 0.2f;
        if (frac <= 0) {
            setScrimBehindColor(0);
        } else {
            // woo, special effects
            final float k = (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2f))));
            setScrimBehindColor(k * SCRIM_BEHIND_ALPHA);
        }
    }

    private void setScrimBehindColor(float alpha) {
        setScrimColor(mScrimBehind, alpha);
    }

    private void setScrimInFrontColor(float alpha) {
        setScrimColor(mScrimInFront, alpha);
        if (alpha == 0f) {
            mScrimInFront.setClickable(false);
        } else {

            // Eat touch events.
            mScrimInFront.setClickable(true);
        }
    }

    private void setScrimColor(View scrim, float alpha) {
        int color = Color.argb((int) (alpha * 255), 0, 0, 0);
        if (mAnimateChange) {
            startScrimAnimation(scrim, color);
        } else {
            scrim.setBackgroundColor(color);
        }
    }

    private void startScrimAnimation(final View scrim, int targetColor) {
        int current = getBackgroundAlpha(scrim);
        int target = Color.alpha(targetColor);
        if (current == targetColor) {
            return;
        }
        ValueAnimator anim = ValueAnimator.ofInt(current, target);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int value = (int) animation.getAnimatedValue();
                scrim.setBackgroundColor(Color.argb(value, 0, 0, 0));
            }
        });
        anim.setInterpolator(mInterpolator);
        anim.setDuration(ANIMATION_DURATION);
        anim.start();
    }

    private int getBackgroundAlpha(View scrim) {
        if (scrim.getBackground() instanceof ColorDrawable) {
            ColorDrawable drawable = (ColorDrawable) scrim.getBackground();
            return Color.alpha(drawable.getColor());
        } else {
            return 0;
        }
    }

    @Override
    public boolean onPreDraw() {
        mScrimBehind.getViewTreeObserver().removeOnPreDrawListener(this);
        mUpdatePending = false;
        updateScrims();
        return true;
    }
}
