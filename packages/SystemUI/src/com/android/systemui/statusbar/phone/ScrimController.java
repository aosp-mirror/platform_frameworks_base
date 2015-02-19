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
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.statusbar.BackDropView;
import com.android.systemui.statusbar.ScrimView;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
public class ScrimController implements ViewTreeObserver.OnPreDrawListener {
    public static final long ANIMATION_DURATION = 220;

    private static final float SCRIM_BEHIND_ALPHA = 0.62f;
    private static final float SCRIM_BEHIND_ALPHA_KEYGUARD = 0.55f;
    private static final float SCRIM_BEHIND_ALPHA_UNLOCKING = 0.2f;
    private static final float SCRIM_IN_FRONT_ALPHA = 0.75f;
    private static final int TAG_KEY_ANIM = R.id.scrim;

    private final ScrimView mScrimBehind;
    private final ScrimView mScrimInFront;
    private final UnlockMethodCache mUnlockMethodCache;

    private boolean mKeyguardShowing;
    private float mFraction;

    private boolean mDarkenWhileDragging;
    private boolean mBouncerShowing;
    private boolean mAnimateChange;
    private boolean mUpdatePending;
    private boolean mExpanding;
    private boolean mAnimateKeyguardFadingOut;
    private long mDurationOverride = -1;
    private long mAnimationDelay;
    private Runnable mOnAnimationFinished;
    private boolean mAnimationStarted;
    private final Interpolator mInterpolator = new DecelerateInterpolator();
    private final Interpolator mLinearOutSlowInInterpolator;
    private BackDropView mBackDropView;
    private boolean mScrimSrcEnabled;
    private boolean mDozing;
    private float mDozeInFrontAlpha;
    private float mDozeBehindAlpha;
    private float mCurrentInFrontAlpha;
    private float mCurrentBehindAlpha;

    public ScrimController(ScrimView scrimBehind, ScrimView scrimInFront, boolean scrimSrcEnabled) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        final Context context = scrimBehind.getContext();
        mUnlockMethodCache = UnlockMethodCache.getInstance(context);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.linear_out_slow_in);
        mScrimSrcEnabled = scrimSrcEnabled;
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
        scheduleUpdate();
    }

    public void onTrackingStarted() {
        mExpanding = true;
        mDarkenWhileDragging = !mUnlockMethodCache.isCurrentlyInsecure();
    }

    public void onExpandingFinished() {
        mExpanding = false;
    }

    public void setPanelExpansion(float fraction) {
        if (mFraction != fraction) {
            mFraction = fraction;
            scheduleUpdate();
        }
    }

    public void setBouncerShowing(boolean showing) {
        mBouncerShowing = showing;
        mAnimateChange = !mExpanding;
        scheduleUpdate();
    }

    public void animateKeyguardFadingOut(long delay, long duration, Runnable onAnimationFinished) {
        mAnimateKeyguardFadingOut = true;
        mDurationOverride = duration;
        mAnimationDelay = delay;
        mAnimateChange = true;
        mOnAnimationFinished = onAnimationFinished;
        scheduleUpdate();
    }

    public void animateGoingToFullShade(long delay, long duration) {
        mDurationOverride = duration;
        mAnimationDelay = delay;
        mAnimateChange = true;
        scheduleUpdate();
    }

    public void setDozing(boolean dozing) {
        mDozing = dozing;
        scheduleUpdate();
    }

    public void setDozeInFrontAlpha(float alpha) {
        mDozeInFrontAlpha = alpha;
        updateScrimColor(mScrimInFront);
    }

    public void setDozeBehindAlpha(float alpha) {
        mDozeBehindAlpha = alpha;
        updateScrimColor(mScrimBehind);
    }

    public float getDozeBehindAlpha() {
        return mDozeBehindAlpha;
    }

    public float getDozeInFrontAlpha() {
        return mDozeInFrontAlpha;
    }

    private void scheduleUpdate() {
        if (mUpdatePending) return;

        // Make sure that a frame gets scheduled.
        mScrimBehind.invalidate();
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    private void updateScrims() {
        if (mAnimateKeyguardFadingOut) {
            setScrimInFrontColor(0f);
            setScrimBehindColor(0f);
        } else if (!mKeyguardShowing && !mBouncerShowing) {
            updateScrimNormal();
            setScrimInFrontColor(0);
        } else {
            updateScrimKeyguard();
        }
        mAnimateChange = false;
    }

    private void updateScrimKeyguard() {
        if (mExpanding && mDarkenWhileDragging) {
            float behindFraction = Math.max(0, Math.min(mFraction, 1));
            float fraction = 1 - behindFraction;
            fraction = (float) Math.pow(fraction, 0.8f);
            behindFraction = (float) Math.pow(behindFraction, 0.8f);
            setScrimInFrontColor(fraction * SCRIM_IN_FRONT_ALPHA);
            setScrimBehindColor(behindFraction * SCRIM_BEHIND_ALPHA_KEYGUARD);
        } else if (mBouncerShowing) {
            setScrimInFrontColor(SCRIM_IN_FRONT_ALPHA);
            setScrimBehindColor(0f);
        } else {
            float fraction = Math.max(0, Math.min(mFraction, 1));
            setScrimInFrontColor(0f);
            setScrimBehindColor(fraction
                    * (SCRIM_BEHIND_ALPHA_KEYGUARD - SCRIM_BEHIND_ALPHA_UNLOCKING)
                    + SCRIM_BEHIND_ALPHA_UNLOCKING);
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

            // Eat touch events (unless dozing).
            mScrimInFront.setClickable(!mDozing);
        }
    }

    private void setScrimColor(ScrimView scrim, float alpha) {
        Object runningAnim = scrim.getTag(TAG_KEY_ANIM);
        if (runningAnim instanceof ValueAnimator) {
            ((ValueAnimator) runningAnim).cancel();
            scrim.setTag(TAG_KEY_ANIM, null);
        }
        if (mAnimateChange) {
            startScrimAnimation(scrim, alpha);
        } else {
            setCurrentScrimAlpha(scrim, alpha);
            updateScrimColor(scrim);
        }
    }

    private float getDozeAlpha(View scrim) {
        return scrim == mScrimBehind ? mDozeBehindAlpha : mDozeInFrontAlpha;
    }

    private float getCurrentScrimAlpha(View scrim) {
        return scrim == mScrimBehind ? mCurrentBehindAlpha : mCurrentInFrontAlpha;
    }

    private void setCurrentScrimAlpha(View scrim, float alpha) {
        if (scrim == mScrimBehind) {
            mCurrentBehindAlpha = alpha;
        } else {
            mCurrentInFrontAlpha = alpha;
        }
    }

    private void updateScrimColor(ScrimView scrim) {
        float alpha1 = getCurrentScrimAlpha(scrim);
        float alpha2 = getDozeAlpha(scrim);
        float alpha = 1 - (1 - alpha1) * (1 - alpha2);
        scrim.setScrimColor(Color.argb((int) (alpha * 255), 0, 0, 0));
    }

    private void startScrimAnimation(final ScrimView scrim, float target) {
        float current = getCurrentScrimAlpha(scrim);
        ValueAnimator anim = ValueAnimator.ofFloat(current, target);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float alpha = (float) animation.getAnimatedValue();
                setCurrentScrimAlpha(scrim, alpha);
                updateScrimColor(scrim);
            }
        });
        anim.setInterpolator(getInterpolator());
        anim.setStartDelay(mAnimationDelay);
        anim.setDuration(mDurationOverride != -1 ? mDurationOverride : ANIMATION_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mOnAnimationFinished != null) {
                    mOnAnimationFinished.run();
                    mOnAnimationFinished = null;
                }
                scrim.setTag(TAG_KEY_ANIM, null);
            }
        });
        anim.start();
        scrim.setTag(TAG_KEY_ANIM, anim);
        mAnimationStarted = true;
    }

    private Interpolator getInterpolator() {
        return mAnimateKeyguardFadingOut ? mLinearOutSlowInInterpolator : mInterpolator;
    }

    @Override
    public boolean onPreDraw() {
        mScrimBehind.getViewTreeObserver().removeOnPreDrawListener(this);
        mUpdatePending = false;
        updateScrims();
        mAnimateKeyguardFadingOut = false;
        mDurationOverride = -1;
        mAnimationDelay = 0;

        // Make sure that we always call the listener even if we didn't start an animation.
        if (!mAnimationStarted && mOnAnimationFinished != null) {
            mOnAnimationFinished.run();
            mOnAnimationFinished = null;
        }
        mAnimationStarted = false;
        return true;
    }

    public void setBackDropView(BackDropView backDropView) {
        mBackDropView = backDropView;
        mBackDropView.setOnVisibilityChangedRunnable(new Runnable() {
            @Override
            public void run() {
                updateScrimBehindDrawingMode();
            }
        });
        updateScrimBehindDrawingMode();
    }

    private void updateScrimBehindDrawingMode() {
        boolean asSrc = mBackDropView.getVisibility() != View.VISIBLE && mScrimSrcEnabled;
        mScrimBehind.setDrawAsSrc(asSrc);
    }
}
