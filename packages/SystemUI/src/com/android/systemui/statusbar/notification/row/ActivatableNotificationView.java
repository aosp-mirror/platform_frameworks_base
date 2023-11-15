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
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.app.animation.Interpolators;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.settingslib.Utils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.FakeShadowView;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class for both {@link ExpandableNotificationRow} and {@link NotificationShelf}
 * to implement dimming/activating on Keyguard for the double-tap gesture
 */
public abstract class ActivatableNotificationView extends ExpandableOutlineView {

    /**
     * A sentinel value when no color should be used. Can be used with {@link #setTintColor(int)}
     * or {@link #setOverrideTintColor(int, float)}.
     */
    protected static final int NO_COLOR = 0;
    /**
     * The content of the view should start showing at animation progress value of
     * #ALPHA_APPEAR_START_FRACTION.
     */
    private static final float ALPHA_APPEAR_START_FRACTION = .4f;
    /**
     * The content should show fully with progress at #ALPHA_APPEAR_END_FRACTION
     * The start of the animation is at #ALPHA_APPEAR_START_FRACTION
     */
    private static final float ALPHA_APPEAR_END_FRACTION = 1;
    private final Set<SourceType> mOnDetachResetRoundness = new HashSet<>();
    private int mTintedRippleColor;
    private int mNormalRippleColor;
    private Gefingerpoken mTouchHandler;

    int mBgTint = NO_COLOR;

    /**
     * Flag to indicate that the notification has been touched once and the second touch will
     * click it.
     */
    private boolean mActivated;

    private final Interpolator mSlowOutFastInInterpolator;
    private Interpolator mCurrentAppearInterpolator;

    NotificationBackgroundView mBackgroundNormal;
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
    private FakeShadowView mFakeShadow;
    private int mCurrentBackgroundTint;
    private int mTargetTint;
    private int mStartTint;
    private int mOverrideTint;
    private float mOverrideAmount;
    private boolean mShadowHidden;
    private boolean mIsHeadsUpAnimation;
    /* In order to track headsup longpress coorindate. */
    protected Point mTargetPoint;
    private boolean mDismissed;
    private boolean mRefocusOnDismiss;

    public ActivatableNotificationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSlowOutFastInInterpolator = new PathInterpolator(0.8f, 0.0f, 0.6f, 1.0f);
        setClipChildren(false);
        setClipToPadding(false);
        updateColors();
    }

    private void updateColors() {
        mNormalColor = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorSurfaceContainerHigh);
        mTintedRippleColor = mContext.getColor(
                R.color.notification_ripple_tinted_color);
        mNormalRippleColor = mContext.getColor(
                R.color.notification_ripple_untinted_color);
        // Reset background color tint and override tint, as they are from an old theme
        mBgTint = NO_COLOR;
        mOverrideTint = NO_COLOR;
        mOverrideAmount = 0.0f;
    }

    /**
     * Reload background colors from resources and invalidate views.
     */
    public void updateBackgroundColors() {
        updateColors();
        initBackground();
        updateBackgroundTint();
    }

    /**
     * @param width The actual width to apply to the background view.
     */
    public void setBackgroundWidth(int width) {
        if (mBackgroundNormal == null) {
            return;
        }
        mBackgroundNormal.setActualWidth(width);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackgroundNormal = findViewById(R.id.backgroundNormal);
        mFakeShadow = findViewById(R.id.fake_shadow);
        mShadowHidden = mFakeShadow.getVisibility() != VISIBLE;
        initBackground();
        updateBackgroundTint();
        updateOutlineAlpha();
    }

    /**
     * Sets the custom background on {@link #mBackgroundNormal}
     * This method can also be used to reload the backgrounds on both of those views, which can
     * be useful in a configuration change.
     */
    protected void initBackground() {
        mBackgroundNormal.setCustomBackground(R.drawable.notification_material_bg);
    }

    protected boolean hideBackground() {
        return false;
    }

    protected void updateBackground() {
        mBackgroundNormal.setVisibility(hideBackground() ? INVISIBLE : VISIBLE);
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mTouchHandler != null && mTouchHandler.onInterceptTouchEvent(ev)) {
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    /** Sets the last action up time this view was touched. */
    public void setLastActionUpTime(long eventTime) {
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

    /**
     * @return whether this view is interactive and can be double tapped
     */
    protected boolean isInteractive() {
        return true;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        mBackgroundNormal.setState(getDrawableState());
    }

    private void updateOutlineAlpha() {
        float alpha = NotificationStackScrollLayout.BACKGROUND_ALPHA_DIMMED;
        alpha = (alpha + (1.0f - alpha) * mNormalBackgroundVisibilityAmount);
        setOutlineAlpha(alpha);
    }

    @Override
    public void setBelowSpeedBump(boolean below) {
        NotificationIconContainerRefactor.assertInLegacyMode();
        super.setBelowSpeedBump(below);
        if (below != mIsBelowSpeedBump) {
            mIsBelowSpeedBump = below;
            updateBackgroundTint();
        }
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
    }

    protected void updateBackgroundTint() {
        updateBackgroundTint(false /* animated */);
    }

    private void updateBackgroundTint(boolean animated) {
        if (mBackgroundColorAnimator != null) {
            mBackgroundColorAnimator.cancel();
        }
        int rippleColor = getRippleColor();
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
            // TODO(282173943): re-enable this tinting optimization when Resources are thread-safe
            if (false && color == mNormalColor) {
                // We don't need to tint a normal notification
                color = 0;
            }
            mBackgroundNormal.setTint(color);
        }
    }

    protected void updateBackgroundClipping() {
        mBackgroundNormal.setBottomAmountClips(!isChildInGroup());
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
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        mBackgroundNormal.setClipTopAmount(clipTopAmount);
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        super.setClipBottomAmount(clipBottomAmount);
        mBackgroundNormal.setClipBottomAmount(clipBottomAmount);
    }

    @Override
    public long performRemoveAnimation(long duration, long delay, float translationDirection,
            boolean isHeadsUpAnimation, Runnable onStartedRunnable, Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        enableAppearDrawing(true);
        mIsHeadsUpAnimation = isHeadsUpAnimation;
        if (mDrawingAppearAnimation) {
            startAppearAnimation(false /* isAppearing */, translationDirection,
                    delay, duration, onStartedRunnable, onFinishedRunnable, animationListener);
        } else {
            if (onStartedRunnable != null) {
                onStartedRunnable.run();
            }
            if (onFinishedRunnable != null) {
                onFinishedRunnable.run();
            }
        }
        return 0;
    }

    @Override
    public void performAddAnimation(long delay, long duration, boolean isHeadsUpAppear,
            Runnable onFinishRunnable) {
        enableAppearDrawing(true);
        mIsHeadsUpAnimation = isHeadsUpAppear;
        if (mDrawingAppearAnimation) {
            startAppearAnimation(true /* isAppearing */, isHeadsUpAppear ? 0.0f : -1.0f, delay,
                    duration, null, null, null);
        }
    }

    private void startAppearAnimation(boolean isAppearing, float translationDirection, long delay,
            long duration, final Runnable onStartedRunnable, final Runnable onFinishedRunnable,
            AnimatorListenerAdapter animationListener) {
        mAnimationTranslationY = translationDirection * getActualHeight();
        cancelAppearAnimation();
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

        float targetValue;
        if (isAppearing) {
            mCurrentAppearInterpolator = Interpolators.FAST_OUT_SLOW_IN;
            targetValue = 1.0f;
        } else {
            mCurrentAppearInterpolator = mSlowOutFastInInterpolator;
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
        // we need to apply the initial state already to avoid drawn frames in the wrong state
        updateAppearAnimationAlpha();
        updateAppearRect();
        mAppearAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mRunWithoutInterruptions;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (onFinishedRunnable != null) {
                    onFinishedRunnable.run();
                }
                if (mRunWithoutInterruptions) {
                    enableAppearDrawing(false);
                }

                // We need to reset the View state, even if the animation was cancelled
                onAppearAnimationFinished(isAppearing);

                if (mRunWithoutInterruptions) {
                    InteractionJankMonitor.getInstance().end(getCujType(isAppearing));
                } else {
                    InteractionJankMonitor.getInstance().cancel(getCujType(isAppearing));
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                if (onStartedRunnable != null) {
                    onStartedRunnable.run();
                }
                mRunWithoutInterruptions = true;
                Configuration.Builder builder = Configuration.Builder
                        .withView(getCujType(isAppearing), ActivatableNotificationView.this);
                InteractionJankMonitor.getInstance().begin(builder);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mRunWithoutInterruptions = false;
            }
        });

        // Cache the original animator so we can check if the animation should be started in the
        // Choreographer callback. It's possible that the original animator (mAppearAnimator) is
        // replaced with a new value before the callback is called.
        ValueAnimator cachedAnimator = mAppearAnimator;
        // Even when delay=0, starting the animation on the next frame is necessary to avoid jank.
        // Not doing so will increase the chances our Animator will be forced to skip a value of
        // the animation's progression, causing stutter.
        Choreographer.getInstance().postFrameCallbackDelayed(
                frameTimeNanos -> {
                    if (mAppearAnimator == cachedAnimator) {
                        mAppearAnimator.start();
                    }
                }, delay);
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
        float interpolatedFraction = mCurrentAppearInterpolator.getInterpolation(
                mAppearAnimationFraction);
        mAppearAnimationTranslation = (1.0f - interpolatedFraction) * mAnimationTranslationY;
        final int actualHeight = getActualHeight();
        float bottom = actualHeight * interpolatedFraction;

        if (mTargetPoint != null) {
            int width = getWidth();
            float fraction = 1 - mAppearAnimationFraction;

            setOutlineRect(mTargetPoint.x * fraction,
                    mAnimationTranslationY
                            + (mAnimationTranslationY - mTargetPoint.y) * fraction,
                    width - (width - mTargetPoint.x) * fraction,
                    actualHeight - (actualHeight - mTargetPoint.y) * fraction);
        } else {
            setOutlineRect(0, mAppearAnimationTranslation, getWidth(),
                    bottom + mAppearAnimationTranslation);
        }
    }

    private float getInterpolatedAppearAnimationFraction() {
        if (mAppearAnimationFraction >= 0) {
            return mCurrentAppearInterpolator.getInterpolation(mAppearAnimationFraction);
        }
        return 1.0f;
    }

    private void updateAppearAnimationAlpha() {
        float contentAlphaProgress = MathUtils.constrain(mAppearAnimationFraction,
                ALPHA_APPEAR_START_FRACTION, ALPHA_APPEAR_END_FRACTION);
        float range = ALPHA_APPEAR_END_FRACTION - ALPHA_APPEAR_START_FRACTION;
        float alpha = (contentAlphaProgress - ALPHA_APPEAR_START_FRACTION) / range;
        setContentAlpha(Interpolators.ALPHA_IN.getInterpolation(alpha));
    }

    private void setContentAlpha(float contentAlpha) {
        View contentView = getContentView();
        if (contentView.hasOverlappingRendering()) {
            int layerType = contentAlpha == 0.0f || contentAlpha == 1.0f ? LAYER_TYPE_NONE
                    : LAYER_TYPE_HARDWARE;
            contentView.setLayerType(layerType, null);
        }
        contentView.setAlpha(contentAlpha);
        // After updating the current view, reset all views.
        if (contentAlpha == 1f) {
            resetAllContentAlphas();
        }
    }

    /**
     * If a subclass's {@link #getContentView()} returns different views depending on state,
     * this method is an opportunity to reset the alpha of ALL content views, not just the
     * current one, which may prevent a content view that is temporarily hidden from being reset.
     *
     * This should setAlpha(1.0f) and setLayerType(LAYER_TYPE_NONE) for all content views.
     */
    protected void resetAllContentAlphas() {}

    @Override
    public void applyRoundnessAndInvalidate() {
        applyBackgroundRoundness(getTopCornerRadius(), getBottomCornerRadius());
        super.applyRoundnessAndInvalidate();
    }

    @Override
    public float getTopCornerRadius() {
        if (mImprovedHunAnimation.isEnabled()) {
            return super.getTopCornerRadius();
        }

        float fraction = getInterpolatedAppearAnimationFraction();
        return MathUtils.lerp(0, super.getTopCornerRadius(), fraction);
    }

    @Override
    public float getBottomCornerRadius() {
        if (mImprovedHunAnimation.isEnabled()) {
            return super.getBottomCornerRadius();
        }

        float fraction = getInterpolatedAppearAnimationFraction();
        return MathUtils.lerp(0, super.getBottomCornerRadius(), fraction);
    }

    private void applyBackgroundRoundness(float topRadius, float bottomRadius) {
        mBackgroundNormal.setRadius(topRadius, bottomRadius);
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

    public void setTouchHandler(Gefingerpoken touchHandler) {
        mTouchHandler = touchHandler;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!mOnDetachResetRoundness.isEmpty()) {
            for (SourceType sourceType : mOnDetachResetRoundness) {
                requestRoundnessReset(sourceType);
            }
            mOnDetachResetRoundness.clear();
        }
    }

    /**
     * SourceType which should be reset when this View is detached
     * @param sourceType will be reset on View detached
     */
    public void addOnDetachResetRoundness(SourceType sourceType) {
        mOnDetachResetRoundness.add(sourceType);
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        super.dump(pw, args);
        if (DUMP_VERBOSE) {
            DumpUtilsKt.withIncreasedIndent(pw, () -> {
                dumpBackgroundView(pw, args);
            });
        }
    }

    protected void dumpBackgroundView(IndentingPrintWriter pw, String[] args) {
        pw.println("Background View: " + mBackgroundNormal);
        if (DUMP_VERBOSE && mBackgroundNormal != null) {
            DumpUtilsKt.withIncreasedIndent(pw, () -> {
                mBackgroundNormal.dump(pw, args);
            });
        }
    }
}
