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

package com.android.systemui.statusbar.notification.row;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.Utils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

/**
 * Base class for both {@link ExpandableNotificationRow} and {@link NotificationShelf}
 * to implement dimming/activating on Keyguard for the double-tap gesture
 */
public abstract class ActivatableNotificationView extends ExpandableOutlineView {

    private static final int BACKGROUND_ANIMATION_LENGTH_MS = 220;
    private static final int ACTIVATE_ANIMATION_LENGTH = 220;

    /**
     * The amount of width, which is kept in the end when performing a disappear animation (also
     * the amount from which the horizontal appearing begins)
     */
    private static final float HORIZONTAL_COLLAPSED_REST_PARTIAL = 0.05f;

    /**
     * At which point from [0,1] does the horizontal collapse animation end (or start when
     * expanding)? 1.0 meaning that it ends immediately and 0.0 that it is continuously animated.
     */
    private static final float HORIZONTAL_ANIMATION_END = 0.2f;

    /**
     * At which point from [0,1] does the alpha animation end (or start when
     * expanding)? 1.0 meaning that it ends immediately and 0.0 that it is continuously animated.
     */
    private static final float ALPHA_ANIMATION_END = 0.0f;

    /**
     * At which point from [0,1] does the horizontal collapse animation start (or start when
     * expanding)? 1.0 meaning that it starts immediately and 0.0 that it is animated at all.
     */
    private static final float HORIZONTAL_ANIMATION_START = 1.0f;

    /**
     * At which point from [0,1] does the vertical collapse animation start (or end when
     * expanding) 1.0 meaning that it starts immediately and 0.0 that it is animated at all.
     */
    private static final float VERTICAL_ANIMATION_START = 1.0f;

    /**
     * A sentinel value when no color should be used. Can be used with {@link #setTintColor(int)}
     * or {@link #setOverrideTintColor(int, float)}.
     */
    protected static final int NO_COLOR = 0;

    private static final Interpolator ACTIVATE_INVERSE_INTERPOLATOR
            = new PathInterpolator(0.6f, 0, 0.5f, 1);
    private static final Interpolator ACTIVATE_INVERSE_ALPHA_INTERPOLATOR
            = new PathInterpolator(0, 0, 0.5f, 1);
    private int mTintedRippleColor;
    private int mNormalRippleColor;
    private Gefingerpoken mTouchHandler;

    private boolean mDimmed;

    int mBgTint = NO_COLOR;

    /**
     * Flag to indicate that the notification has been touched once and the second touch will
     * click it.
     */
    private boolean mActivated;

    private OnActivatedListener mOnActivatedListener;

    private final Interpolator mSlowOutFastInInterpolator;
    private final Interpolator mSlowOutLinearInInterpolator;
    private Interpolator mCurrentAppearInterpolator;
    private Interpolator mCurrentAlphaInterpolator;

    NotificationBackgroundView mBackgroundNormal;
    private NotificationBackgroundView mBackgroundDimmed;
    private ObjectAnimator mBackgroundAnimator;
    private RectF mAppearAnimationRect = new RectF();
    private float mAnimationTranslationY;
    private boolean mDrawingAppearAnimation;
    private ValueAnimator mAppearAnimator;
    private ValueAnimator mBackgroundColorAnimator;
    private float mAppearAnimationFraction = -1.0f;
    private float mAppearAnimationTranslation;
    private int mNormalColor;
    private boolean mIsBelowSpeedBump;
    private long mLastActionUpTime;

    private float mNormalBackgroundVisibilityAmount;
    private float mDimmedBackgroundFadeInAmount = -1;
    private ValueAnimator.AnimatorUpdateListener mBackgroundVisibilityUpdater
            = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            setNormalBackgroundVisibilityAmount(mBackgroundNormal.getAlpha());
            mDimmedBackgroundFadeInAmount = mBackgroundDimmed.getAlpha();
        }
    };
    private FakeShadowView mFakeShadow;
    private int mCurrentBackgroundTint;
    private int mTargetTint;
    private int mStartTint;
    private int mOverrideTint;
    private float mOverrideAmount;
    private boolean mShadowHidden;
    /**
     * Similar to mDimmed but is also true if it's not dimmable but should be
     */
    private boolean mNeedsDimming;
    private int mDimmedAlpha;
    private boolean mIsHeadsUpAnimation;
    private int mHeadsUpAddStartLocation;
    private float mHeadsUpLocation;
    private boolean mIsAppearing;
    private boolean mDismissed;
    private boolean mRefocusOnDismiss;
    private OnDimmedListener mOnDimmedListener;
    private AccessibilityManager mAccessibilityManager;

    public ActivatableNotificationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSlowOutFastInInterpolator = new PathInterpolator(0.8f, 0.0f, 0.6f, 1.0f);
        mSlowOutLinearInInterpolator = new PathInterpolator(0.8f, 0.0f, 1.0f, 1.0f);
        setClipChildren(false);
        setClipToPadding(false);
        updateColors();
        initDimens();
    }

    private void updateColors() {
        mNormalColor = Utils.getColorAttr(mContext, android.R.attr.colorBackground)
                .getDefaultColor();
        mTintedRippleColor = mContext.getColor(
                R.color.notification_ripple_tinted_color);
        mNormalRippleColor = mContext.getColor(
                R.color.notification_ripple_untinted_color);
        mDimmedAlpha = Color.alpha(mContext.getColor(
                R.color.notification_background_dimmed_color));
    }

    private void initDimens() {
        mHeadsUpAddStartLocation = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_start);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        super.onDensityOrFontScaleChanged();
        initDimens();
    }

    /**
     * Reload background colors from resources and invalidate views.
     */
    public void updateBackgroundColors() {
        updateColors();
        initBackground();
        updateBackgroundTint();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackgroundNormal = findViewById(R.id.backgroundNormal);
        mFakeShadow = findViewById(R.id.fake_shadow);
        mShadowHidden = mFakeShadow.getVisibility() != VISIBLE;
        mBackgroundDimmed = findViewById(R.id.backgroundDimmed);
        initBackground();
        updateBackground();
        updateBackgroundTint();
        updateOutlineAlpha();
    }

    /**
     * Sets the custom backgrounds on {@link #mBackgroundNormal} and {@link #mBackgroundDimmed}.
     * This method can also be used to reload the backgrounds on both of those views, which can
     * be useful in a configuration change.
     */
    protected void initBackground() {
        mBackgroundNormal.setCustomBackground(R.drawable.notification_material_bg);
        mBackgroundDimmed.setCustomBackground(R.drawable.notification_material_bg_dim);
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mTouchHandler != null && mTouchHandler.onInterceptTouchEvent(ev)) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    /** Sets the last action up time this view was touched. */
    void setLastActionUpTime(long eventTime) {
        mLastActionUpTime = eventTime;
    }

    /**
     * Returns the last action up time. The last time will also be cleared because the source of
     * action is not only from touch event. That prevents the caller from utilizing the time with
     * unrelated event. The time can be 0 if the event is unavailable.
     */
    public long getAndResetLastActionUpTime() {
        long lastActionUpTime = mLastActionUpTime;
        mLastActionUpTime = 0;
        return lastActionUpTime;
    }

    protected boolean disallowSingleClick(MotionEvent ev) {
        return false;
    }

    protected boolean handleSlideBack() {
        return false;
    }

    /**
     * @return whether this view is interactive and can be double tapped
     */
    protected boolean isInteractive() {
        return true;
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (!mDimmed){
            mBackgroundNormal.drawableHotspotChanged(x, y);
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mDimmed) {
            mBackgroundDimmed.setState(getDrawableState());
        } else {
            mBackgroundNormal.setState(getDrawableState());
        }
    }

    void setRippleAllowed(boolean allowed) {
        mBackgroundNormal.setPressedAllowed(allowed);
    }

    void makeActive() {
        startActivateAnimation(false /* reverse */);
        mActivated = true;
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onActivated(this);
        }
    }

    public boolean isActive() {
        return mActivated;
    }

    private void startActivateAnimation(final boolean reverse) {
        if (!isAttachedToWindow()) {
            return;
        }
        if (!isDimmable()) {
            return;
        }
        int widthHalf = mBackgroundNormal.getWidth()/2;
        int heightHalf = mBackgroundNormal.getActualHeight()/2;
        float radius = (float) Math.sqrt(widthHalf*widthHalf + heightHalf*heightHalf);
        Animator animator;
        if (reverse) {
            animator = ViewAnimationUtils.createCircularReveal(mBackgroundNormal,
                    widthHalf, heightHalf, radius, 0);
        } else {
            animator = ViewAnimationUtils.createCircularReveal(mBackgroundNormal,
                    widthHalf, heightHalf, 0, radius);
        }
        mBackgroundNormal.setVisibility(View.VISIBLE);
        Interpolator interpolator;
        Interpolator alphaInterpolator;
        if (!reverse) {
            interpolator = Interpolators.LINEAR_OUT_SLOW_IN;
            alphaInterpolator = Interpolators.LINEAR_OUT_SLOW_IN;
        } else {
            interpolator = ACTIVATE_INVERSE_INTERPOLATOR;
            alphaInterpolator = ACTIVATE_INVERSE_ALPHA_INTERPOLATOR;
        }
        animator.setInterpolator(interpolator);
        animator.setDuration(ACTIVATE_ANIMATION_LENGTH);
        if (reverse) {
            mBackgroundNormal.setAlpha(1f);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    updateBackground();
                }
            });
            animator.start();
        } else {
            mBackgroundNormal.setAlpha(0.4f);
            animator.start();
        }
        mBackgroundNormal.animate()
                .alpha(reverse ? 0f : 1f)
                .setInterpolator(alphaInterpolator)
                .setUpdateListener(animation -> {
                    float animatedFraction = animation.getAnimatedFraction();
                    if (reverse) {
                        animatedFraction = 1.0f - animatedFraction;
                    }
                    setNormalBackgroundVisibilityAmount(animatedFraction);
                })
                .setDuration(ACTIVATE_ANIMATION_LENGTH);
    }

    /**
     * Cancels the hotspot and makes the notification inactive.
     */
    public void makeInactive(boolean animate) {
        if (mActivated) {
            mActivated = false;
            if (mDimmed) {
                if (animate) {
                    startActivateAnimation(true /* reverse */);
                } else {
                    updateBackground();
                }
            }
        }
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onActivationReset(this);
        }
    }

    public void setDimmed(boolean dimmed, boolean fade) {
        mNeedsDimming = dimmed;
        if (mOnDimmedListener != null) {
            mOnDimmedListener.onSetDimmed(dimmed);
        }
        dimmed &= isDimmable();
        if (mDimmed != dimmed) {
            mDimmed = dimmed;
            resetBackgroundAlpha();
            if (fade) {
                fadeDimmedBackground();
            } else {
                updateBackground();
            }
        }
    }

    public boolean isDimmable() {
        return true;
    }

    public boolean isDimmed() {
        return mDimmed;
    }

    private void updateOutlineAlpha() {
        float alpha = NotificationStackScrollLayout.BACKGROUND_ALPHA_DIMMED;
        alpha = (alpha + (1.0f - alpha) * mNormalBackgroundVisibilityAmount);
        setOutlineAlpha(alpha);
    }

    private void setNormalBackgroundVisibilityAmount(float normalBackgroundVisibilityAmount) {
        mNormalBackgroundVisibilityAmount = normalBackgroundVisibilityAmount;
        updateOutlineAlpha();
    }

    @Override
    public void setBelowSpeedBump(boolean below) {
        super.setBelowSpeedBump(below);
        if (below != mIsBelowSpeedBump) {
            mIsBelowSpeedBump = below;
            updateBackgroundTint();
            onBelowSpeedBumpChanged();
        }
    }

    protected void onBelowSpeedBumpChanged() {
    }

    /**
     * @return whether we are below the speed bump
     */
    public boolean isBelowSpeedBump() {
        return mIsBelowSpeedBump;
    }

    /**
     * Sets the tint color of the background
     */
    protected void setTintColor(int color) {
        setTintColor(color, false);
    }

    /**
     * Sets the tint color of the background
     */
    void setTintColor(int color, boolean animated) {
        if (color != mBgTint) {
            mBgTint = color;
            updateBackgroundTint(animated);
        }
    }

    @Override
    public void setDistanceToTopRoundness(float distanceToTopRoundness) {
        super.setDistanceToTopRoundness(distanceToTopRoundness);
        mBackgroundNormal.setDistanceToTopRoundness(distanceToTopRoundness);
        mBackgroundDimmed.setDistanceToTopRoundness(distanceToTopRoundness);
    }

    /** Sets whether this view is the last notification in a section. */
    @Override
    public void setLastInSection(boolean lastInSection) {
        if (lastInSection != mLastInSection) {
            super.setLastInSection(lastInSection);
            mBackgroundNormal.setLastInSection(lastInSection);
            mBackgroundDimmed.setLastInSection(lastInSection);
        }
    }

    /** Sets whether this view is the first notification in a section. */
    @Override
    public void setFirstInSection(boolean firstInSection) {
        if (firstInSection != mFirstInSection) {
            super.setFirstInSection(firstInSection);
            mBackgroundNormal.setFirstInSection(firstInSection);
            mBackgroundDimmed.setFirstInSection(firstInSection);
        }
    }

    /**
     * Set an override tint color that is used for the background.
     *
     * @param color the color that should be used to tint the background.
     *              This can be {@link #NO_COLOR} if the tint should be normally computed.
     * @param overrideAmount a value from 0 to 1 how much the override tint should be used. The
     *                       background color will then be the interpolation between this and the
     *                       regular background color, where 1 means the overrideTintColor is fully
     *                       used and the background color not at all.
     */
    public void setOverrideTintColor(int color, float overrideAmount) {
        mOverrideTint = color;
        mOverrideAmount = overrideAmount;
        int newColor = calculateBgColor();
        setBackgroundTintColor(newColor);
        if (!isDimmable() && mNeedsDimming) {
           mBackgroundNormal.setDrawableAlpha((int) NotificationUtils.interpolate(255,
                   mDimmedAlpha,
                   overrideAmount));
        } else {
            mBackgroundNormal.setDrawableAlpha(255);
        }
    }

    protected void updateBackgroundTint() {
        updateBackgroundTint(false /* animated */);
    }

    private void updateBackgroundTint(boolean animated) {
        if (mBackgroundColorAnimator != null) {
            mBackgroundColorAnimator.cancel();
        }
        int rippleColor = getRippleColor();
        mBackgroundDimmed.setRippleColor(rippleColor);
        mBackgroundNormal.setRippleColor(rippleColor);
        int color = calculateBgColor();
        if (!animated) {
            setBackgroundTintColor(color);
        } else if (color != mCurrentBackgroundTint) {
            mStartTint = mCurrentBackgroundTint;
            mTargetTint = color;
            mBackgroundColorAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            mBackgroundColorAnimator.addUpdateListener(animation -> {
                int newColor = NotificationUtils.interpolateColors(mStartTint, mTargetTint,
                        animation.getAnimatedFraction());
                setBackgroundTintColor(newColor);
            });
            mBackgroundColorAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
            mBackgroundColorAnimator.setInterpolator(Interpolators.LINEAR);
            mBackgroundColorAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mBackgroundColorAnimator = null;
                }
            });
            mBackgroundColorAnimator.start();
        }
    }

    protected void setBackgroundTintColor(int color) {
        if (color != mCurrentBackgroundTint) {
            mCurrentBackgroundTint = color;
            if (color == mNormalColor) {
                // We don't need to tint a normal notification
                color = 0;
            }
            mBackgroundDimmed.setTint(color);
            mBackgroundNormal.setTint(color);
        }
    }

    /**
     * Fades the background when the dimmed state changes.
     */
    private void fadeDimmedBackground() {
        mBackgroundDimmed.animate().cancel();
        mBackgroundNormal.animate().cancel();
        if (mActivated) {
            updateBackground();
            return;
        }
        if (!shouldHideBackground()) {
            if (mDimmed) {
                mBackgroundDimmed.setVisibility(View.VISIBLE);
            } else {
                mBackgroundNormal.setVisibility(View.VISIBLE);
            }
        }
        float startAlpha = mDimmed ? 1f : 0;
        float endAlpha = mDimmed ? 0 : 1f;
        int duration = BACKGROUND_ANIMATION_LENGTH_MS;
        // Check whether there is already a background animation running.
        if (mBackgroundAnimator != null) {
            startAlpha = (Float) mBackgroundAnimator.getAnimatedValue();
            duration = (int) mBackgroundAnimator.getCurrentPlayTime();
            mBackgroundAnimator.removeAllListeners();
            mBackgroundAnimator.cancel();
            if (duration <= 0) {
                updateBackground();
                return;
            }
        }
        mBackgroundNormal.setAlpha(startAlpha);
        mBackgroundAnimator =
                ObjectAnimator.ofFloat(mBackgroundNormal, View.ALPHA, startAlpha, endAlpha);
        mBackgroundAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mBackgroundAnimator.setDuration(duration);
        mBackgroundAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                updateBackground();
                mBackgroundAnimator = null;
                mDimmedBackgroundFadeInAmount = -1;
            }
        });
        mBackgroundAnimator.addUpdateListener(mBackgroundVisibilityUpdater);
        mBackgroundAnimator.start();
    }

    protected void updateBackgroundAlpha(float transformationAmount) {
        float bgAlpha = isChildInGroup() && mDimmed ? transformationAmount : 1f;
        if (mDimmedBackgroundFadeInAmount != -1) {
            bgAlpha *= mDimmedBackgroundFadeInAmount;
        }
        mBackgroundDimmed.setAlpha(bgAlpha);
    }

    protected void resetBackgroundAlpha() {
        updateBackgroundAlpha(0f /* transformationAmount */);
    }

    protected void updateBackground() {
        cancelFadeAnimations();
        if (shouldHideBackground()) {
            mBackgroundDimmed.setVisibility(INVISIBLE);
            mBackgroundNormal.setVisibility(mActivated ? VISIBLE : INVISIBLE);
        } else if (mDimmed) {
            // When groups are animating to the expanded state from the lockscreen, show the
            // normal background instead of the dimmed background.
            final boolean dontShowDimmed = isGroupExpansionChanging() && isChildInGroup();
            mBackgroundDimmed.setVisibility(dontShowDimmed ? View.INVISIBLE : View.VISIBLE);
            mBackgroundNormal.setVisibility((mActivated || dontShowDimmed)
                    ? View.VISIBLE
                    : View.INVISIBLE);
        } else {
            mBackgroundDimmed.setVisibility(View.INVISIBLE);
            mBackgroundNormal.setVisibility(View.VISIBLE);
            mBackgroundNormal.setAlpha(1f);
            // make in inactive to avoid it sticking around active
            makeInactive(false /* animate */);
        }
        setNormalBackgroundVisibilityAmount(
                mBackgroundNormal.getVisibility() == View.VISIBLE ? 1.0f : 0.0f);
    }

    protected void updateBackgroundClipping() {
        mBackgroundNormal.setBottomAmountClips(!isChildInGroup());
        mBackgroundDimmed.setBottomAmountClips(!isChildInGroup());
    }

    protected boolean shouldHideBackground() {
        return false;
    }

    private void cancelFadeAnimations() {
        if (mBackgroundAnimator != null) {
            mBackgroundAnimator.cancel();
        }
        mBackgroundDimmed.animate().cancel();
        mBackgroundNormal.animate().cancel();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setPivotX(getWidth() / 2);
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        super.setActualHeight(actualHeight, notifyListeners);
        setPivotY(actualHeight / 2);
        mBackgroundNormal.setActualHeight(actualHeight);
        mBackgroundDimmed.setActualHeight(actualHeight);
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        mBackgroundNormal.setClipTopAmount(clipTopAmount);
        mBackgroundDimmed.setClipTopAmount(clipTopAmount);
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        super.setClipBottomAmount(clipBottomAmount);
        mBackgroundNormal.setClipBottomAmount(clipBottomAmount);
        mBackgroundDimmed.setClipBottomAmount(clipBottomAmount);
    }

    @Override
    public long performRemoveAnimation(long duration, long delay,
            float translationDirection, boolean isHeadsUpAnimation, float endLocation,
            Runnable onFinishedRunnable, AnimatorListenerAdapter animationListener) {
        enableAppearDrawing(true);
        mIsHeadsUpAnimation = isHeadsUpAnimation;
        mHeadsUpLocation = endLocation;
        if (mDrawingAppearAnimation) {
            startAppearAnimation(false /* isAppearing */, translationDirection,
                    delay, duration, onFinishedRunnable, animationListener);
        } else if (onFinishedRunnable != null) {
            onFinishedRunnable.run();
        }
        return 0;
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear) {
        enableAppearDrawing(true);
        mIsHeadsUpAnimation = isHeadsUpAppear;
        mHeadsUpLocation = mHeadsUpAddStartLocation;
        if (mDrawingAppearAnimation) {
            startAppearAnimation(true /* isAppearing */, isHeadsUpAppear ? 0.0f : -1.0f, delay,
                    duration, null, null);
        }
    }

    private void startAppearAnimation(boolean isAppearing, float translationDirection, long delay,
            long duration, final Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        cancelAppearAnimation();
        mAnimationTranslationY = translationDirection * getActualHeight();
        if (mAppearAnimationFraction == -1.0f) {
            // not initialized yet, we start anew
            if (isAppearing) {
                mAppearAnimationFraction = 0.0f;
                mAppearAnimationTranslation = mAnimationTranslationY;
            } else {
                mAppearAnimationFraction = 1.0f;
                mAppearAnimationTranslation = 0;
            }
        }
        mIsAppearing = isAppearing;

        float targetValue;
        if (isAppearing) {
            mCurrentAppearInterpolator = mSlowOutFastInInterpolator;
            mCurrentAlphaInterpolator = Interpolators.LINEAR_OUT_SLOW_IN;
            targetValue = 1.0f;
            if (!mIsHeadsUpAnimation && isChildInGroup()) {
                // slower fade in of children to avoid visibly overlapping with other children
                mCurrentAlphaInterpolator = Interpolators.SLOW_OUT_LINEAR_IN;
            }
        } else {
            mCurrentAppearInterpolator = Interpolators.FAST_OUT_SLOW_IN;
            mCurrentAlphaInterpolator = mSlowOutLinearInInterpolator;
            targetValue = 0.0f;
        }
        mAppearAnimator = ValueAnimator.ofFloat(mAppearAnimationFraction,
                targetValue);
        mAppearAnimator.setInterpolator(Interpolators.LINEAR);
        mAppearAnimator.setDuration(
                (long) (duration * Math.abs(mAppearAnimationFraction - targetValue)));
        mAppearAnimator.addUpdateListener(animation -> {
            mAppearAnimationFraction = (float) animation.getAnimatedValue();
            updateAppearAnimationAlpha();
            updateAppearRect();
            invalidate();
        });
        if (animationListener != null) {
            mAppearAnimator.addListener(animationListener);
        }
        if (delay > 0) {
            // we need to apply the initial state already to avoid drawn frames in the wrong state
            updateAppearAnimationAlpha();
            updateAppearRect();
            mAppearAnimator.setStartDelay(delay);
        }
        mAppearAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mWasCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (onFinishedRunnable != null) {
                    onFinishedRunnable.run();
                }
                if (!mWasCancelled) {
                    enableAppearDrawing(false);
                    onAppearAnimationFinished(isAppearing);
                    InteractionJankMonitor.getInstance().end(getCujType(isAppearing));
                } else {
                    InteractionJankMonitor.getInstance().cancel(getCujType(isAppearing));
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                mWasCancelled = false;
                InteractionJankMonitor.getInstance().begin(ActivatableNotificationView.this,
                        getCujType(isAppearing));
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mWasCancelled = true;
            }
        });
        mAppearAnimator.start();
    }

    private int getCujType(boolean isAppearing) {
        if (mIsHeadsUpAnimation) {
            return isAppearing
                    ? InteractionJankMonitor.CUJ_NOTIFICATION_HEADS_UP_APPEAR
                    : InteractionJankMonitor.CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR;
        } else {
            return isAppearing
                    ? InteractionJankMonitor.CUJ_NOTIFICATION_ADD
                    : InteractionJankMonitor.CUJ_NOTIFICATION_REMOVE;
        }
    }

    protected void onAppearAnimationFinished(boolean wasAppearing) {
    }

    private void cancelAppearAnimation() {
        if (mAppearAnimator != null) {
            mAppearAnimator.cancel();
            mAppearAnimator = null;
        }
    }

    public void cancelAppearDrawing() {
        cancelAppearAnimation();
        enableAppearDrawing(false);
    }

    private void updateAppearRect() {
        float inverseFraction = (1.0f - mAppearAnimationFraction);
        float translationFraction = mCurrentAppearInterpolator.getInterpolation(inverseFraction);
        float translateYTotalAmount = translationFraction * mAnimationTranslationY;
        mAppearAnimationTranslation = translateYTotalAmount;

        // handle width animation
        float widthFraction = (inverseFraction - (1.0f - HORIZONTAL_ANIMATION_START))
                / (HORIZONTAL_ANIMATION_START - HORIZONTAL_ANIMATION_END);
        widthFraction = Math.min(1.0f, Math.max(0.0f, widthFraction));
        widthFraction = mCurrentAppearInterpolator.getInterpolation(widthFraction);
        float startWidthFraction = HORIZONTAL_COLLAPSED_REST_PARTIAL;
        if (mIsHeadsUpAnimation && !mIsAppearing) {
            startWidthFraction = 0;
        }
        if (mIsAppearing && !mIsHeadsUpAnimation && isChildInGroup()) {
            // Children in a group (when not heads up) should simply fade in.
            startWidthFraction = 1;
        }
        float width = MathUtils.lerp(startWidthFraction, 1.0f, 1.0f - widthFraction)
                        * getWidth();
        float left;
        float right;
        if (mIsHeadsUpAnimation) {
            left = MathUtils.lerp(mHeadsUpLocation, 0, 1.0f - widthFraction);
            right = left + width;
        } else {
            left = getWidth() * 0.5f - width / 2.0f;
            right = getWidth() - left;
        }

        // handle top animation
        float heightFraction = (inverseFraction - (1.0f - VERTICAL_ANIMATION_START)) /
                VERTICAL_ANIMATION_START;
        heightFraction = Math.max(0.0f, heightFraction);
        heightFraction = mCurrentAppearInterpolator.getInterpolation(heightFraction);

        float top;
        float bottom;
        final int actualHeight = getActualHeight();
        if (mAnimationTranslationY > 0.0f) {
            bottom = actualHeight - heightFraction * mAnimationTranslationY * 0.1f
                    - translateYTotalAmount;
            top = bottom * heightFraction;
        } else {
            top = heightFraction * (actualHeight + mAnimationTranslationY) * 0.1f -
                    translateYTotalAmount;
            bottom = actualHeight * (1 - heightFraction) + top * heightFraction;
        }
        mAppearAnimationRect.set(left, top, right, bottom);
        setOutlineRect(left, top + mAppearAnimationTranslation, right,
                bottom + mAppearAnimationTranslation);
    }

    private void updateAppearAnimationAlpha() {
        float contentAlphaProgress = mAppearAnimationFraction;
        contentAlphaProgress = contentAlphaProgress / (1.0f - ALPHA_ANIMATION_END);
        contentAlphaProgress = Math.min(1.0f, contentAlphaProgress);
        contentAlphaProgress = mCurrentAlphaInterpolator.getInterpolation(contentAlphaProgress);
        setContentAlpha(contentAlphaProgress);
    }

    private void setContentAlpha(float contentAlpha) {
        View contentView = getContentView();
        if (contentView.hasOverlappingRendering()) {
            int layerType = contentAlpha == 0.0f || contentAlpha == 1.0f ? LAYER_TYPE_NONE
                    : LAYER_TYPE_HARDWARE;
            int currentLayerType = contentView.getLayerType();
            if (currentLayerType != layerType) {
                contentView.setLayerType(layerType, null);
            }
        }
        contentView.setAlpha(contentAlpha);
    }

    @Override
    protected void applyRoundness() {
        super.applyRoundness();
        applyBackgroundRoundness(getCurrentBackgroundRadiusTop(),
                getCurrentBackgroundRadiusBottom());
    }

    private void applyBackgroundRoundness(float topRadius, float bottomRadius) {
        mBackgroundDimmed.setRadius(topRadius, bottomRadius);
        mBackgroundNormal.setRadius(topRadius, bottomRadius);
    }

    @Override
    protected void setBackgroundTop(int backgroundTop) {
        mBackgroundDimmed.setBackgroundTop(backgroundTop);
        mBackgroundNormal.setBackgroundTop(backgroundTop);
    }

    protected abstract View getContentView();

    public int calculateBgColor() {
        return calculateBgColor(true /* withTint */, true /* withOverRide */);
    }

    @Override
    protected boolean childNeedsClipping(View child) {
        if (child instanceof NotificationBackgroundView && isClippingNeeded()) {
            return true;
        }
        return super.childNeedsClipping(child);
    }

    /**
     * @param withTint should a possible tint be factored in?
     * @param withOverride should the value be interpolated with {@link #mOverrideTint}
     * @return the calculated background color
     */
    private int calculateBgColor(boolean withTint, boolean withOverride) {
        if (withOverride && mOverrideTint != NO_COLOR) {
            int defaultTint = calculateBgColor(withTint, false);
            return NotificationUtils.interpolateColors(defaultTint, mOverrideTint, mOverrideAmount);
        }
        if (withTint && mBgTint != NO_COLOR) {
            return mBgTint;
        } else {
            return mNormalColor;
        }
    }

    private int getRippleColor() {
        if (mBgTint != 0) {
            return mTintedRippleColor;
        } else {
            return mNormalRippleColor;
        }
    }

    /**
     * When we draw the appear animation, we render the view in a bitmap and render this bitmap
     * as a shader of a rect. This call creates the Bitmap and switches the drawing mode,
     * such that the normal drawing of the views does not happen anymore.
     *
     * @param enable Should it be enabled.
     */
    private void enableAppearDrawing(boolean enable) {
        if (enable != mDrawingAppearAnimation) {
            mDrawingAppearAnimation = enable;
            if (!enable) {
                setContentAlpha(1.0f);
                mAppearAnimationFraction = -1;
                setOutlineRect(null);
            }
            invalidate();
        }
    }

    public boolean isDrawingAppearAnimation() {
        return mDrawingAppearAnimation;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mDrawingAppearAnimation) {
            canvas.save();
            canvas.translate(0, mAppearAnimationTranslation);
        }
        super.dispatchDraw(canvas);
        if (mDrawingAppearAnimation) {
            canvas.restore();
        }
    }

    public void setOnActivatedListener(OnActivatedListener onActivatedListener) {
        mOnActivatedListener = onActivatedListener;
    }

    @Override
    public void setFakeShadowIntensity(float shadowIntensity, float outlineAlpha, int shadowYEnd,
            int outlineTranslation) {
        boolean hiddenBefore = mShadowHidden;
        mShadowHidden = shadowIntensity == 0.0f;
        if (!mShadowHidden || !hiddenBefore) {
            mFakeShadow.setFakeShadowTranslationZ(shadowIntensity * (getTranslationZ()
                            + FakeShadowView.SHADOW_SIBLING_TRESHOLD), outlineAlpha, shadowYEnd,
                    outlineTranslation);
        }
    }

    public int getBackgroundColorWithoutTint() {
        return calculateBgColor(false /* withTint */, false /* withOverride */);
    }

    public int getCurrentBackgroundTint() {
        return mCurrentBackgroundTint;
    }

    public boolean isHeadsUp() {
        return false;
    }

    @Override
    public int getHeadsUpHeightWithoutHeader() {
        return getHeight();
    }

    /** Mark that this view has been dismissed. */
    public void dismiss(boolean refocusOnDismiss) {
        mDismissed = true;
        mRefocusOnDismiss = refocusOnDismiss;
    }

    /** Mark that this view is no longer dismissed. */
    public void unDismiss() {
        mDismissed = false;
    }

    /** Is this view marked as dismissed? */
    public boolean isDismissed() {
        return mDismissed;
    }

    /** Should a re-focus occur upon dismissing this view? */
    public boolean shouldRefocusOnDismiss() {
        return mRefocusOnDismiss || isAccessibilityFocused();
    }

    void setTouchHandler(Gefingerpoken touchHandler) {
        mTouchHandler = touchHandler;
    }

    void setOnDimmedListener(OnDimmedListener onDimmedListener) {
        mOnDimmedListener = onDimmedListener;
    }

    public void setAccessibilityManager(AccessibilityManager accessibilityManager) {
        mAccessibilityManager = accessibilityManager;
    }

    public interface OnActivatedListener {
        void onActivated(ActivatableNotificationView view);
        void onActivationReset(ActivatableNotificationView view);
    }

    interface OnDimmedListener {
        void onSetDimmed(boolean dimmed);
    }
}
