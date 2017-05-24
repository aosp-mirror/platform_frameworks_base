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
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v4.graphics.ColorUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.stack.ViewState;

import com.google.android.colorextraction.ColorExtractor;

/**
 * Controls both the scrim behind the notifications and in front of the notifications (when a
 * security method gets shown).
 */
public class ScrimController implements ViewTreeObserver.OnPreDrawListener,
        OnHeadsUpChangedListener, ColorExtractor.OnColorsChangedListener {
    public static final long ANIMATION_DURATION = 220;
    public static final Interpolator KEYGUARD_FADE_OUT_INTERPOLATOR
            = new PathInterpolator(0f, 0, 0.7f, 1f);
    public static final Interpolator KEYGUARD_FADE_OUT_INTERPOLATOR_LOCKED
            = new PathInterpolator(0.3f, 0f, 0.8f, 1f);
    public static final float GRADIENT_SCRIM_ALPHA = 0.75f;
    protected static final float SCRIM_BEHIND_ALPHA_KEYGUARD = 0.45f;
    protected static final float SCRIM_BEHIND_ALPHA_UNLOCKING = 0.2f;
    private static final float SCRIM_IN_FRONT_ALPHA = GRADIENT_SCRIM_ALPHA;
    private static final float SCRIM_IN_FRONT_ALPHA_LOCKED = GRADIENT_SCRIM_ALPHA;
    private static final int TAG_KEY_ANIM = R.id.scrim;
    private static final int TAG_KEY_ANIM_TARGET = R.id.scrim_target;
    private static final int TAG_START_ALPHA = R.id.scrim_alpha_start;
    private static final int TAG_END_ALPHA = R.id.scrim_alpha_end;
    private static final float NOT_INITIALIZED = -1;

    private final LightBarController mLightBarController;
    protected final ScrimView mScrimBehind;
    protected final ScrimView mScrimInFront;
    private final UnlockMethodCache mUnlockMethodCache;
    private final View mHeadsUpScrim;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private final ColorExtractor mColorExtractor;
    private ColorExtractor.GradientColors mLockColors;
    private ColorExtractor.GradientColors mSystemColors;
    private boolean mNeedsDrawableColorUpdate;

    protected float mScrimBehindAlpha;
    protected float mScrimBehindAlphaKeyguard = SCRIM_BEHIND_ALPHA_KEYGUARD;
    protected float mScrimBehindAlphaUnlocking = SCRIM_BEHIND_ALPHA_UNLOCKING;

    protected boolean mKeyguardShowing;
    private float mFraction;

    private boolean mDarkenWhileDragging;
    protected boolean mBouncerShowing;
    protected boolean mBouncerIsKeyguard = false;
    private boolean mWakeAndUnlocking;
    protected boolean mAnimateChange;
    private boolean mUpdatePending;
    private boolean mTracking;
    private boolean mAnimateKeyguardFadingOut;
    protected long mDurationOverride = -1;
    private long mAnimationDelay;
    private Runnable mOnAnimationFinished;
    private final Interpolator mInterpolator = new DecelerateInterpolator();
    private boolean mDozing;
    private float mDozeInFrontAlpha;
    private float mDozeBehindAlpha;
    private float mCurrentInFrontAlpha  = NOT_INITIALIZED;
    private float mCurrentBehindAlpha = NOT_INITIALIZED;
    private float mCurrentHeadsUpAlpha = NOT_INITIALIZED;
    private int mPinnedHeadsUpCount;
    private float mTopHeadsUpDragAmount;
    private View mDraggedHeadsUpView;
    private boolean mForceHideScrims;
    private boolean mSkipFirstFrame;
    private boolean mDontAnimateBouncerChanges;
    private boolean mKeyguardFadingOutInProgress;
    private boolean mAnimatingDozeUnlock;
    private ValueAnimator mKeyguardFadeoutAnimation;

    public ScrimController(LightBarController lightBarController, ScrimView scrimBehind,
            ScrimView scrimInFront, View headsUpScrim) {
        mScrimBehind = scrimBehind;
        mScrimInFront = scrimInFront;
        mHeadsUpScrim = headsUpScrim;
        final Context context = scrimBehind.getContext();
        mUnlockMethodCache = UnlockMethodCache.getInstance(context);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mLightBarController = lightBarController;
        mScrimBehindAlpha = context.getResources().getFloat(R.dimen.scrim_behind_alpha);

        mColorExtractor = Dependency.get(ColorExtractor.class);
        mColorExtractor.addOnColorsChangedListener(this);
        mLockColors = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK);
        mSystemColors = mColorExtractor.getColors(WallpaperManager.FLAG_SYSTEM);
        mNeedsDrawableColorUpdate = true;

        updateHeadsUpScrim(false);
        updateScrims();
    }

    public void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;

        // Showing/hiding the keyguard means that scrim colors
        // will probably have to be switched
        mNeedsDrawableColorUpdate = true;
        scheduleUpdate();
    }

    protected void setScrimBehindValues(float scrimBehindAlphaKeyguard,
            float scrimBehindAlphaUnlocking) {
        mScrimBehindAlphaKeyguard = scrimBehindAlphaKeyguard;
        mScrimBehindAlphaUnlocking = scrimBehindAlphaUnlocking;
        scheduleUpdate();
    }

    public void onTrackingStarted() {
        mTracking = true;
        mDarkenWhileDragging = !mUnlockMethodCache.canSkipBouncer();
    }

    public void onExpandingFinished() {
        mTracking = false;
    }

    public void setPanelExpansion(float fraction) {
        if (mFraction != fraction) {
            mFraction = fraction;
            scheduleUpdate();
            if (mPinnedHeadsUpCount != 0) {
                updateHeadsUpScrim(false);
            }
            if (mKeyguardFadeoutAnimation != null && mTracking) {
                mKeyguardFadeoutAnimation.cancel();
            }
        }
    }

    public void setBouncerShowing(boolean showing) {
        mBouncerShowing = showing;
        mAnimateChange = !mTracking && !mDontAnimateBouncerChanges;
        scheduleUpdate();
    }

    public void setWakeAndUnlocking() {
        mWakeAndUnlocking = true;
        mAnimatingDozeUnlock = true;
        scheduleUpdate();
    }

    public void animateKeyguardFadingOut(long delay, long duration, Runnable onAnimationFinished,
            boolean skipFirstFrame) {
        mWakeAndUnlocking = false;
        mAnimateKeyguardFadingOut = true;
        mDurationOverride = duration;
        mAnimationDelay = delay;
        mAnimateChange = true;
        mSkipFirstFrame = skipFirstFrame;
        mOnAnimationFinished = onAnimationFinished;

        if (!mKeyguardUpdateMonitor.needsSlowUnlockTransition()) {
            scheduleUpdate();

            // No need to wait for the next frame to be drawn for this case - onPreDraw will execute
            // the changes we just scheduled.
            onPreDraw();
        } else {

            // In case the user isn't unlocked, make sure to delay a bit because the system is hosed
            // with too many things in this case, in order to not skip the initial frames.
            mScrimInFront.postOnAnimationDelayed(this::scheduleUpdate, 16);
        }
    }

    public void abortKeyguardFadingOut() {
        if (mAnimateKeyguardFadingOut) {
            endAnimateKeyguardFadingOut(true /* force */);
        }
    }

    public void animateKeyguardUnoccluding(long duration) {
        mAnimateChange = false;
        setScrimBehindAlpha(0f);
        mAnimateChange = true;
        scheduleUpdate();
        mDurationOverride = duration;
    }

    public void animateGoingToFullShade(long delay, long duration) {
        mDurationOverride = duration;
        mAnimationDelay = delay;
        mAnimateChange = true;
        scheduleUpdate();
    }

    public void setDozing(boolean dozing) {
        if (mDozing != dozing) {
            mDozing = dozing;
            scheduleUpdate();
        }
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

    private float getScrimInFrontAlpha() {
        return mKeyguardUpdateMonitor.needsSlowUnlockTransition()
                ? SCRIM_IN_FRONT_ALPHA_LOCKED
                : SCRIM_IN_FRONT_ALPHA;
    }

    /**
     * Sets the given drawable as the background of the scrim that shows up behind the
     * notifications.
     */
    public void setScrimBehindDrawable(Drawable drawable) {
        mScrimBehind.setDrawable(drawable);
    }

    protected void scheduleUpdate() {
        if (mUpdatePending) return;

        // Make sure that a frame gets scheduled.
        mScrimBehind.invalidate();
        mScrimBehind.getViewTreeObserver().addOnPreDrawListener(this);
        mUpdatePending = true;
    }

    protected void updateScrims() {

        // Make sure we have the right gradients
        if (mNeedsDrawableColorUpdate) {
            mNeedsDrawableColorUpdate = false;
            if (mKeyguardShowing) {
                mScrimInFront.setColors(mLockColors);
                mScrimBehind.setColors(mLockColors);
            } else {
                mScrimInFront.setColors(mSystemColors, true);
                mScrimBehind.setColors(mSystemColors, true);
            }
        }

        if (mAnimateKeyguardFadingOut || mForceHideScrims) {
            setScrimInFrontAlpha(0f);
            setScrimBehindAlpha(0f);
        } else if (mWakeAndUnlocking) {
            // During wake and unlock, we first hide everything behind a black scrim, which then
            // gets faded out from animateKeyguardFadingOut.
            if (mDozing) {
                setScrimInFrontAlpha(0f);
                setScrimBehindAlpha(1f);
            } else {
                setScrimInFrontAlpha(1f);
                setScrimBehindAlpha(0f);
            }
        } else if (!mKeyguardShowing && !mBouncerShowing) {
            updateScrimNormal();
            setScrimInFrontAlpha(0);
        } else {
            updateScrimKeyguard();
        }
        mAnimateChange = false;
    }

    private void updateScrimKeyguard() {
        if (mTracking && mDarkenWhileDragging) {
            float behindFraction = Math.max(0, Math.min(mFraction, 1));
            float fraction = 1 - behindFraction;
            fraction = (float) Math.pow(fraction, 0.8f);
            behindFraction = (float) Math.pow(behindFraction, 0.8f);
            setScrimInFrontAlpha(fraction * getScrimInFrontAlpha());
            setScrimBehindAlpha(behindFraction * mScrimBehindAlphaKeyguard);
        } else if (mBouncerShowing && !mBouncerIsKeyguard) {
            setScrimInFrontAlpha(getScrimInFrontAlpha());
            updateScrimNormal();
        } else if (mBouncerShowing) {
            setScrimInFrontAlpha(0f);
            setScrimBehindAlpha(mScrimBehindAlpha);
        } else {
            float fraction = Math.max(0, Math.min(mFraction, 1));
            setScrimInFrontAlpha(0f);
            setScrimBehindAlpha(fraction
                    * (mScrimBehindAlphaKeyguard - mScrimBehindAlphaUnlocking)
                    + mScrimBehindAlphaUnlocking);
        }
    }

    private void updateScrimNormal() {
        float frac = mFraction;
        // let's start this 20% of the way down the screen
        frac = frac * 1.2f - 0.2f;
        if (frac <= 0) {
            setScrimBehindAlpha(0);
        } else {
            // woo, special effects
            final float k = (float)(1f-0.5f*(1f-Math.cos(3.14159f * Math.pow(1f-frac, 2f))));
            setScrimBehindAlpha(k * mScrimBehindAlpha);
        }
    }

    private void setScrimBehindAlpha(float alpha) {
        setScrimAlpha(mScrimBehind, alpha);
    }

    private void setScrimInFrontAlpha(float alpha) {
        setScrimAlpha(mScrimInFront, alpha);
        if (alpha == 0f) {
            mScrimInFront.setClickable(false);
        } else {
            // Eat touch events (unless dozing).
            mScrimInFront.setClickable(!mDozing);
        }
    }

    private void setScrimAlpha(View scrim, float alpha) {
        updateScrim(mAnimateChange, scrim, alpha, getCurrentScrimAlpha(scrim));
    }

    protected float getDozeAlpha(View scrim) {
        return scrim == mScrimBehind ? mDozeBehindAlpha : mDozeInFrontAlpha;
    }

    protected float getCurrentScrimAlpha(View scrim) {
        return scrim == mScrimBehind ? mCurrentBehindAlpha
                : scrim == mScrimInFront ? mCurrentInFrontAlpha
                : mCurrentHeadsUpAlpha;
    }

    private void setCurrentScrimAlpha(View scrim, float alpha) {
        if (scrim == mScrimBehind) {
            mCurrentBehindAlpha = alpha;
            mLightBarController.setScrimAlpha(mCurrentBehindAlpha);
        } else if (scrim == mScrimInFront) {
            mCurrentInFrontAlpha = alpha;
        } else {
            alpha = Math.max(0.0f, Math.min(1.0f, alpha));
            mCurrentHeadsUpAlpha = alpha;
        }
    }

    private void updateScrimColor(View scrim) {
        float alpha1 = getCurrentScrimAlpha(scrim);
        if (scrim instanceof ScrimView) {
            ScrimView scrimView = (ScrimView) scrim;
            float dozeAlpha = getDozeAlpha(scrim);
            float alpha = 1 - (1 - alpha1) * (1 - dozeAlpha);
            alpha = Math.max(0, Math.min(1.0f, alpha));
            scrimView.setViewAlpha(alpha);

            int dozeTint = Color.TRANSPARENT;
            if (mAnimatingDozeUnlock || mDozing) {
                dozeTint = Color.BLACK;
            }
            scrimView.setTint(dozeTint);
        } else {
            scrim.setAlpha(alpha1);
        }
    }

    private void startScrimAnimation(final View scrim, float target) {
        float current = getCurrentScrimAlpha(scrim);
        ValueAnimator anim = ValueAnimator.ofFloat(current, target);
        anim.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            setCurrentScrimAlpha(scrim, alpha);
            updateScrimColor(scrim);
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
                if (mKeyguardFadingOutInProgress) {
                    mKeyguardFadeoutAnimation = null;
                    mKeyguardFadingOutInProgress = false;
                    mAnimatingDozeUnlock = false;
                }
                scrim.setTag(TAG_KEY_ANIM, null);
                scrim.setTag(TAG_KEY_ANIM_TARGET, null);
            }
        });
        anim.start();
        if (mAnimateKeyguardFadingOut) {
            mKeyguardFadingOutInProgress = true;
            mKeyguardFadeoutAnimation = anim;
        }
        if (mSkipFirstFrame) {
            anim.setCurrentPlayTime(16);
        }
        scrim.setTag(TAG_KEY_ANIM, anim);
        scrim.setTag(TAG_KEY_ANIM_TARGET, target);
    }

    protected Interpolator getInterpolator() {
        if (mAnimateKeyguardFadingOut && mKeyguardUpdateMonitor.needsSlowUnlockTransition()) {
            return KEYGUARD_FADE_OUT_INTERPOLATOR_LOCKED;
        } else if (mAnimateKeyguardFadingOut) {
            return KEYGUARD_FADE_OUT_INTERPOLATOR;
        } else {
            return mInterpolator;
        }
    }

    @Override
    public boolean onPreDraw() {
        mScrimBehind.getViewTreeObserver().removeOnPreDrawListener(this);
        mUpdatePending = false;
        if (mDontAnimateBouncerChanges) {
            mDontAnimateBouncerChanges = false;
        }
        updateScrims();
        mDurationOverride = -1;
        mAnimationDelay = 0;
        mSkipFirstFrame = false;

        // Make sure that we always call the listener even if we didn't start an animation.
        endAnimateKeyguardFadingOut(false /* force */);
        return true;
    }

    private void endAnimateKeyguardFadingOut(boolean force) {
        mAnimateKeyguardFadingOut = false;
        if (force || (!isAnimating(mScrimInFront) && !isAnimating(mScrimBehind))) {
            if (mOnAnimationFinished != null) {
                mOnAnimationFinished.run();
                mOnAnimationFinished = null;
            }
            mKeyguardFadingOutInProgress = false;
            if (!mWakeAndUnlocking || force)
                mAnimatingDozeUnlock = false;
        }
    }

    private boolean isAnimating(View scrim) {
        return scrim.getTag(TAG_KEY_ANIM) != null;
    }

    public void setDrawBehindAsSrc(boolean asSrc) {
        mScrimBehind.setDrawAsSrc(asSrc);
    }

    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
    }

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
        mPinnedHeadsUpCount++;
        updateHeadsUpScrim(true);
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {
        mPinnedHeadsUpCount--;
        if (headsUp == mDraggedHeadsUpView) {
            mDraggedHeadsUpView = null;
            mTopHeadsUpDragAmount = 0.0f;
        }
        updateHeadsUpScrim(true);
    }

    @Override
    public void onHeadsUpStateChanged(NotificationData.Entry entry, boolean isHeadsUp) {
    }

    private void updateHeadsUpScrim(boolean animate) {
        updateScrim(animate, mHeadsUpScrim, calculateHeadsUpAlpha(), mCurrentHeadsUpAlpha);
    }

    private void updateScrim(boolean animate, View scrim, float alpha, float currentAlpha) {
        if (mKeyguardFadingOutInProgress && mKeyguardFadeoutAnimation.getCurrentPlayTime() != 0) {
            return;
        }

        ValueAnimator previousAnimator = ViewState.getChildTag(scrim,
                TAG_KEY_ANIM);
        float animEndValue = -1;
        if (previousAnimator != null) {
            if (animate || alpha == currentAlpha) {
                previousAnimator.cancel();
            } else {
                animEndValue = ViewState.getChildTag(scrim, TAG_END_ALPHA);
            }
        }
        if (alpha != currentAlpha && alpha != animEndValue) {
            if (animate) {
                startScrimAnimation(scrim, alpha);
                scrim.setTag(TAG_START_ALPHA, currentAlpha);
                scrim.setTag(TAG_END_ALPHA, alpha);
            } else {
                if (previousAnimator != null) {
                    float previousStartValue = ViewState.getChildTag(scrim, TAG_START_ALPHA);
                    float previousEndValue = ViewState.getChildTag(scrim, TAG_END_ALPHA);
                    // we need to increase all animation keyframes of the previous animator by the
                    // relative change to the end value
                    PropertyValuesHolder[] values = previousAnimator.getValues();
                    float relativeDiff = alpha - previousEndValue;
                    float newStartValue = previousStartValue + relativeDiff;
                    newStartValue = Math.max(0, Math.min(1.0f, newStartValue));
                    values[0].setFloatValues(newStartValue, alpha);
                    scrim.setTag(TAG_START_ALPHA, newStartValue);
                    scrim.setTag(TAG_END_ALPHA, alpha);
                    previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                } else {
                    // update the alpha directly
                    setCurrentScrimAlpha(scrim, alpha);
                    updateScrimColor(scrim);
                }
            }
        }
    }

    /**
     * Set the amount the current top heads up view is dragged. The range is from 0 to 1 and 0 means
     * the heads up is in its resting space and 1 means it's fully dragged out.
     *
     * @param draggedHeadsUpView the dragged view
     * @param topHeadsUpDragAmount how far is it dragged
     */
    public void setTopHeadsUpDragAmount(View draggedHeadsUpView, float topHeadsUpDragAmount) {
        mTopHeadsUpDragAmount = topHeadsUpDragAmount;
        mDraggedHeadsUpView = draggedHeadsUpView;
        updateHeadsUpScrim(false);
    }

    private float calculateHeadsUpAlpha() {
        float alpha;
        if (mPinnedHeadsUpCount >= 2) {
            alpha = 1.0f;
        } else if (mPinnedHeadsUpCount == 0) {
            alpha = 0.0f;
        } else {
            alpha = 1.0f - mTopHeadsUpDragAmount;
        }
        float expandFactor = (1.0f - mFraction);
        expandFactor = Math.max(expandFactor, 0.0f);
        return alpha * expandFactor;
    }

    public void forceHideScrims(boolean hide) {
        mForceHideScrims = hide;
        mAnimateChange = false;
        scheduleUpdate();
    }

    public void dontAnimateBouncerChangesUntilNextFrame() {
        mDontAnimateBouncerChanges = true;
    }

    public void setExcludedBackgroundArea(Rect area) {
        mScrimBehind.setExcludedArea(area);
    }

    public int getBackgroundColor() {
        int color = mLockColors.getMainColor();
        return Color.argb((int) (mScrimBehind.getAlpha() * Color.alpha(color)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setScrimBehindChangeRunnable(Runnable changeRunnable) {
        mScrimBehind.setChangeRunnable(changeRunnable);
    }

    public void onDensityOrFontScaleChanged() {
        ViewGroup.LayoutParams layoutParams = mHeadsUpScrim.getLayoutParams();
        layoutParams.height = mHeadsUpScrim.getResources().getDimensionPixelSize(
                R.dimen.heads_up_scrim_height);
        mHeadsUpScrim.setLayoutParams(layoutParams);
    }

    public void setCurrentUser(int currentUser) {
        // Don't care in the base class.
    }

    @Override
    public void onColorsChanged(ColorExtractor.GradientColors colors, int which) {
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            mLockColors = colors;
            mNeedsDrawableColorUpdate = true;
            scheduleUpdate();
        }
        if ((which & WallpaperManager.FLAG_SYSTEM) != 0) {
            mSystemColors = colors;
            mNeedsDrawableColorUpdate = true;
            scheduleUpdate();
        }
    }
}
