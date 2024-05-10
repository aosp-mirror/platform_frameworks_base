/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.keyguard;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternView;
import com.android.settingslib.animation.AppearAnimationCreator;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.animation.DisappearAnimationUtils;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.DevicePostureController.DevicePostureInt;

public class KeyguardPatternView extends KeyguardInputView
        implements AppearAnimationCreator<LockPatternView.CellState> {

    private static final String TAG = "SecurityPatternView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;


    // how long we stay awake after each key beyond MIN_PATTERN_BEFORE_POKE_WAKELOCK
    private static final int UNLOCK_PATTERN_WAKE_INTERVAL_MS = 7000;

    // How much we scale up the duration of the disappear animation when the current user is locked
    public static final float DISAPPEAR_MULTIPLIER_LOCKED = 1.5f;

    // Extra padding, in pixels, that should eat touch events.
    private static final int PATTERNS_TOUCH_AREA_EXTENSION = 40;

    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtilsLocked;
    private final int[] mTmpPosition = new int[2];
    private final Rect mTempRect = new Rect();
    private final Rect mLockPatternScreenBounds = new Rect();

    private LockPatternView mLockPatternView;

    /**
     * Keeps track of the last time we poked the wake lock during dispatching of the touch event.
     * Initialized to something guaranteed to make us poke the wakelock when the user starts
     * drawing the pattern.
     * @see #dispatchTouchEvent(android.view.MotionEvent)
     */
    private long mLastPokeTime = -UNLOCK_PATTERN_WAKE_INTERVAL_MS;

    BouncerKeyguardMessageArea mSecurityMessageDisplay;
    private View mEcaView;
    @Nullable private MotionLayout mContainerMotionLayout;
    // TODO (b/293252410) - usage of mContainerConstraintLayout should be removed
    //  when the flag is enabled/removed
    @Nullable private ConstraintLayout mContainerConstraintLayout;
    private boolean mAlreadyUsingSplitBouncer = false;
    private boolean mIsSmallLockScreenLandscapeEnabled = false;
    @DevicePostureInt private int mLastDevicePosture = DEVICE_POSTURE_UNKNOWN;

    public KeyguardPatternView(Context context) {
        this(context, null);
    }

    public KeyguardPatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAppearAnimationUtils = new AppearAnimationUtils(context,
                AppearAnimationUtils.DEFAULT_APPEAR_DURATION, 1.5f /* translationScale */,
                2.0f /* delayScale */, AnimationUtils.loadInterpolator(
                        mContext, android.R.interpolator.linear_out_slow_in));
        mDisappearAnimationUtils = new DisappearAnimationUtils(context,
                125, 1.2f /* translationScale */,
                0.6f /* delayScale */, AnimationUtils.loadInterpolator(
                        mContext, android.R.interpolator.fast_out_linear_in));
        mDisappearAnimationUtilsLocked = new DisappearAnimationUtils(context,
                (long) (125 * DISAPPEAR_MULTIPLIER_LOCKED), 1.2f /* translationScale */,
                0.6f /* delayScale */, AnimationUtils.loadInterpolator(
                mContext, android.R.interpolator.fast_out_linear_in));
    }

    /**
     * Use motion layout (new bouncer implementation) if LOCKSCREEN_ENABLE_LANDSCAPE flag is
     * enabled, instead of constraint layout (old bouncer implementation)
     */
    public void setIsLockScreenLandscapeEnabled(boolean isLockScreenLandscapeEnabled) {
        mIsSmallLockScreenLandscapeEnabled = isLockScreenLandscapeEnabled;
        findContainerLayout();
    }

    private void findContainerLayout() {
        if (mIsSmallLockScreenLandscapeEnabled) {
            mContainerMotionLayout = findViewById(R.id.pattern_container);
        } else {
            mContainerConstraintLayout = findViewById(R.id.pattern_container);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        updateMargins();
    }

    void onDevicePostureChanged(@DevicePostureInt int posture) {
        if (mLastDevicePosture == posture) return;
        mLastDevicePosture = posture;

        if (mIsSmallLockScreenLandscapeEnabled) {
            boolean useSplitBouncerAfterFold =
                    mLastDevicePosture == DEVICE_POSTURE_CLOSED
                    && getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE
                    && getResources().getBoolean(R.bool.update_bouncer_constraints);

            if (mAlreadyUsingSplitBouncer != useSplitBouncerAfterFold) {
                updateConstraints(useSplitBouncerAfterFold);
            }
        }

        updateMargins();
    }

    private void updateMargins() {
        if (mIsSmallLockScreenLandscapeEnabled) {
            updateHalfFoldedConstraints();
        } else {
            updateHalfFoldedGuideline();
        }
    }

    private void updateHalfFoldedConstraints() {
        // Update the constraints based on the device posture...
        if (mAlreadyUsingSplitBouncer) return;

        boolean shouldCollapsePattern =
                mLastDevicePosture == DEVICE_POSTURE_HALF_OPENED
                        && mContext.getResources().getConfiguration().orientation
                        == ORIENTATION_PORTRAIT;

        int expectedMotionLayoutState = shouldCollapsePattern
                ? R.id.half_folded_single_constraints
                : R.id.single_constraints;

        transitionToMotionLayoutState(expectedMotionLayoutState);
    }

    // TODO (b/293252410) - this method can be removed when the flag is enabled/removed
    private void updateHalfFoldedGuideline() {
        // Update the guideline based on the device posture...
        float halfOpenPercentage =
                mContext.getResources().getFloat(R.dimen.half_opened_bouncer_height_ratio);

        ConstraintSet cs = new ConstraintSet();
        cs.clone(mContainerConstraintLayout);
        cs.setGuidelinePercent(R.id.pattern_top_guideline,
                mLastDevicePosture == DEVICE_POSTURE_HALF_OPENED ? halfOpenPercentage : 0.0f);
        cs.applyTo(mContainerConstraintLayout);
    }

    private void transitionToMotionLayoutState(int state) {
        if (mContainerMotionLayout.getCurrentState() != state) {
            mContainerMotionLayout.transitionToState(state);
        }
    }

    /**
     * Updates the keyguard view's constraints (single or split constraints).
     * Split constraints are only used for small landscape screens.
     * Only called when flag LANDSCAPE_ENABLE_LOCKSCREEN is enabled.
     */
    @Override
    protected void updateConstraints(boolean useSplitBouncer) {
        if (!mIsSmallLockScreenLandscapeEnabled) return;

        mAlreadyUsingSplitBouncer = useSplitBouncer;

        if (useSplitBouncer) {
            mContainerMotionLayout.jumpToState(R.id.split_constraints);
            mContainerMotionLayout.setMaxWidth(Integer.MAX_VALUE);
        } else {
            boolean useHalfFoldedConstraints =
                    mLastDevicePosture == DEVICE_POSTURE_HALF_OPENED
                            && mContext.getResources().getConfiguration().orientation
                            == ORIENTATION_PORTRAIT;

            if (useHalfFoldedConstraints) {
                mContainerMotionLayout.jumpToState(R.id.half_folded_single_constraints);
            } else {
                mContainerMotionLayout.jumpToState(R.id.single_constraints);
            }
            mContainerMotionLayout.setMaxWidth(getResources()
                    .getDimensionPixelSize(R.dimen.biometric_auth_pattern_view_max_size));
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mLockPatternView = findViewById(R.id.lockPatternView);

        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mSecurityMessageDisplay = findViewById(R.id.bouncer_message_area);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        // as long as the user is entering a pattern (i.e sending a touch event that was handled
        // by this screen), keep poking the wake lock so that the screen will stay on.
        final long elapsed = SystemClock.elapsedRealtime() - mLastPokeTime;
        if (result && (elapsed > (UNLOCK_PATTERN_WAKE_INTERVAL_MS - 100))) {
            mLastPokeTime = SystemClock.elapsedRealtime();
        }
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mLockPatternView, mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = mLockPatternView.dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mLockPatternView.getLocationOnScreen(mTmpPosition);
        mLockPatternScreenBounds.set(mTmpPosition[0] - PATTERNS_TOUCH_AREA_EXTENSION,
                mTmpPosition[1] - PATTERNS_TOUCH_AREA_EXTENSION,
                mTmpPosition[0] + mLockPatternView.getWidth() + PATTERNS_TOUCH_AREA_EXTENSION,
                mTmpPosition[1] + mLockPatternView.getHeight() + PATTERNS_TOUCH_AREA_EXTENSION);
    }

    @Override
    boolean disallowInterceptTouch(MotionEvent event) {
        return !mLockPatternView.isEmpty()
                || mLockPatternScreenBounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    public void startAppearAnimation() {
        enableClipping(false);
        setAlpha(0f);
        setTranslationY(mAppearAnimationUtils.getStartTranslation());
        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */, 500 /* duration */,
                0, mAppearAnimationUtils.getInterpolator(),
                getAnimationListener(InteractionJankMonitor.CUJ_LOCKSCREEN_PATTERN_APPEAR));
        mLockPatternView.post(() -> {
            setAlpha(1f);
            mAppearAnimationUtils.startAnimation2d(
                    mLockPatternView.getCellStates(),
                    () -> enableClipping(true),
                    KeyguardPatternView.this);
        });
        if (!TextUtils.isEmpty(mSecurityMessageDisplay.getText())) {
            mAppearAnimationUtils.createAnimation(mSecurityMessageDisplay, 0,
                    AppearAnimationUtils.DEFAULT_APPEAR_DURATION,
                    mAppearAnimationUtils.getStartTranslation(),
                    true /* appearing */,
                    mAppearAnimationUtils.getInterpolator(),
                    null /* finishRunnable */);
        }
    }

    public boolean startDisappearAnimation(boolean needsSlowUnlockTransition,
            final Runnable finishRunnable) {
        float durationMultiplier = needsSlowUnlockTransition ? DISAPPEAR_MULTIPLIER_LOCKED : 1f;
        mLockPatternView.clearPattern();
        enableClipping(false);
        setTranslationY(0);
        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */,
                (long) (300 * durationMultiplier),
                -mDisappearAnimationUtils.getStartTranslation(),
                mDisappearAnimationUtils.getInterpolator(),
                getAnimationListener(InteractionJankMonitor.CUJ_LOCKSCREEN_PATTERN_DISAPPEAR));

        DisappearAnimationUtils disappearAnimationUtils = needsSlowUnlockTransition
                ? mDisappearAnimationUtilsLocked : mDisappearAnimationUtils;
        disappearAnimationUtils.startAnimation2d(mLockPatternView.getCellStates(),
                () -> {
                    enableClipping(true);
                    if (finishRunnable != null) {
                        finishRunnable.run();
                    }
                }, KeyguardPatternView.this);
        if (!TextUtils.isEmpty(mSecurityMessageDisplay.getText())) {
            mDisappearAnimationUtils.createAnimation(mSecurityMessageDisplay, 0,
                    (long) (200 * durationMultiplier),
                    -mDisappearAnimationUtils.getStartTranslation() * 3,
                    false /* appearing */,
                    mDisappearAnimationUtils.getInterpolator(),
                    null /* finishRunnable */);
        }
        return true;
    }

    private void enableClipping(boolean enable) {
        if (mContainerConstraintLayout != null) {
            setClipChildren(enable);
            mContainerConstraintLayout.setClipToPadding(enable);
            mContainerConstraintLayout.setClipChildren(enable);
        }
        if (mContainerMotionLayout != null) {
            setClipChildren(enable);
            mContainerMotionLayout.setClipToPadding(enable);
            mContainerMotionLayout.setClipChildren(enable);
        }
    }

    @Override
    public void createAnimation(final LockPatternView.CellState animatedCell, long delay,
            long duration, float translationY, final boolean appearing,
            Interpolator interpolator,
            final Runnable finishListener) {
        mLockPatternView.startCellStateAnimation(animatedCell,
                1f, appearing ? 1f : 0f, /* alpha */
                appearing ? translationY : 0f, appearing ? 0f : translationY, /* translation */
                appearing ? 0f : 1f, 1f /* scale */,
                delay, duration, interpolator, finishListener);
        if (finishListener != null) {
            // Also animate the Emergency call
            mAppearAnimationUtils.createAnimation(mEcaView, delay, duration, translationY,
                    appearing, interpolator, null);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public CharSequence getTitle() {
        return getResources().getString(
                com.android.internal.R.string.keyguard_accessibility_pattern_unlock);
    }
}
