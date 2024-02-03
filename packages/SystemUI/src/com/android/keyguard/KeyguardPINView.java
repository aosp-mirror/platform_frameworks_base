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

import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_PIN_APPEAR;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_PIN_DISAPPEAR;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.app.animation.Interpolators;
import com.android.settingslib.animation.DisappearAnimationUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.DevicePostureController.DevicePostureInt;

/**
 * Displays a PIN pad for unlocking.
 */
public class KeyguardPINView extends KeyguardPinBasedInputView {

    ValueAnimator mAppearAnimator = ValueAnimator.ofFloat(0f, 1f);
    private final DisappearAnimationUtils mDisappearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtilsLocked;
    private ConstraintLayout mContainer;
    private int mDisappearYTranslation;
    private View[][] mViews;
    private int mYTrans;
    private int mYTransOffset;
    private View mBouncerMessageArea;
    @DevicePostureInt private int mLastDevicePosture = DEVICE_POSTURE_UNKNOWN;
    public static final long ANIMATION_DURATION = 650;

    public KeyguardPINView(Context context) {
        this(context, null);
    }

    public KeyguardPINView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisappearAnimationUtils = new DisappearAnimationUtils(context,
                125, 0.6f /* translationScale */,
                0.45f /* delayScale */, AnimationUtils.loadInterpolator(
                        mContext, android.R.interpolator.fast_out_linear_in));
        mDisappearAnimationUtilsLocked = new DisappearAnimationUtils(context,
                (long) (125 * KeyguardPatternView.DISAPPEAR_MULTIPLIER_LOCKED),
                0.6f /* translationScale */,
                0.45f /* delayScale */, AnimationUtils.loadInterpolator(
                       mContext, android.R.interpolator.fast_out_linear_in));
        mDisappearYTranslation = getResources().getDimensionPixelSize(
                R.dimen.disappear_y_translation);
        mYTrans = getResources().getDimensionPixelSize(R.dimen.pin_view_trans_y_entry);
        mYTransOffset = getResources().getDimensionPixelSize(R.dimen.pin_view_trans_y_entry_offset);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        updateMargins();
    }

    void onDevicePostureChanged(@DevicePostureInt int posture) {
        if (mLastDevicePosture != posture) {
            mLastDevicePosture = posture;
            updateMargins();
        }
    }

    @Override
    protected void resetState() {
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.pinEntry;
    }

    private void updateMargins() {
        // Re-apply everything to the keys...
        int bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.num_pad_entry_row_margin_bottom);
        int rightMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.num_pad_key_margin_end);
        String ratio = mContext.getResources().getString(R.string.num_pad_key_ratio);

        // mView contains all Views that make up the PIN pad; row0 = the entry test field, then
        // rows 1-4 contain the buttons. Iterate over all views that make up the buttons in the pad,
        // and re-set all the margins.
        for (int row = 1; row < 5; row++) {
            for (int column = 0; column < 3; column++) {
                View key = mViews[row][column];

                ConstraintLayout.LayoutParams lp =
                        (ConstraintLayout.LayoutParams) key.getLayoutParams();

                lp.dimensionRatio = ratio;

                // Don't set any margins on the last row of buttons.
                if (row != 4) {
                    lp.bottomMargin = bottomMargin;
                }

                // Don't set margins on the rightmost buttons.
                if (column != 2) {
                    lp.rightMargin = rightMargin;
                }

                key.setLayoutParams(lp);
            }
        }

        // Update the guideline based on the device posture...
        float halfOpenPercentage =
                mContext.getResources().getFloat(R.dimen.half_opened_bouncer_height_ratio);

        ConstraintSet cs = new ConstraintSet();
        cs.clone(mContainer);
        cs.setGuidelinePercent(R.id.pin_pad_top_guideline,
                mLastDevicePosture == DEVICE_POSTURE_HALF_OPENED ? halfOpenPercentage : 0.0f);
        cs.applyTo(mContainer);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mContainer = findViewById(R.id.pin_container);
        mBouncerMessageArea = findViewById(R.id.bouncer_message_area);
        mViews = new View[][]{
                new View[]{
                        findViewById(R.id.row0), null, null
                },
                new View[]{
                        findViewById(R.id.key1), findViewById(R.id.key2),
                        findViewById(R.id.key3)
                },
                new View[]{
                        findViewById(R.id.key4), findViewById(R.id.key5),
                        findViewById(R.id.key6)
                },
                new View[]{
                        findViewById(R.id.key7), findViewById(R.id.key8),
                        findViewById(R.id.key9)
                },
                new View[]{
                        findViewById(R.id.delete_button), findViewById(R.id.key0),
                        findViewById(R.id.key_enter)
                },
                new View[]{
                        null, mEcaView, null
                }};
    }

    @Override
    public int getWrongPasswordStringId() {
        return R.string.kg_wrong_pin;
    }

    @Override
    public void startAppearAnimation() {
        setAlpha(1f);
        setTranslationY(0);
        if (mAppearAnimator.isRunning()) {
            mAppearAnimator.cancel();
        }
        mAppearAnimator.setDuration(ANIMATION_DURATION);
        mAppearAnimator.addUpdateListener(animation -> animate(animation.getAnimatedFraction()));
        mAppearAnimator.addListener(getAnimationListener(CUJ_LOCKSCREEN_PIN_APPEAR));
        mAppearAnimator.start();
    }

    public boolean startDisappearAnimation(boolean needsSlowUnlockTransition,
            final Runnable finishRunnable) {
        if (mAppearAnimator.isRunning()) {
            mAppearAnimator.cancel();
        }

        setTranslationY(0);
        DisappearAnimationUtils disappearAnimationUtils = needsSlowUnlockTransition
                        ? mDisappearAnimationUtilsLocked
                        : mDisappearAnimationUtils;
        disappearAnimationUtils.createAnimation(
                this, 0, 200, mDisappearYTranslation, false,
                mDisappearAnimationUtils.getInterpolator(), () -> {
                    if (finishRunnable != null) {
                        finishRunnable.run();
                    }
                },
                getAnimationListener(CUJ_LOCKSCREEN_PIN_DISAPPEAR));
        return true;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Animate subviews according to expansion or time. */
    private void animate(float progress) {
        Interpolator standardDecelerate = Interpolators.STANDARD_DECELERATE;
        Interpolator legacyDecelerate = Interpolators.LEGACY_DECELERATE;
        float standardProgress = standardDecelerate.getInterpolation(progress);

        mBouncerMessageArea.setTranslationY(
                mYTrans - mYTrans * standardProgress);
        mBouncerMessageArea.setAlpha(standardProgress);

        for (int i = 0; i < mViews.length; i++) {
            View[] row = mViews[i];
            for (View view : row) {
                if (view == null) {
                    continue;
                }

                float scaledProgress = legacyDecelerate.getInterpolation(MathUtils.constrain(
                        (progress - 0.075f * i) / (1f - 0.075f * mViews.length),
                        0f,
                        1f
                ));
                view.setAlpha(scaledProgress);
                int yDistance = mYTrans + mYTransOffset * i;
                view.setTranslationY(
                        yDistance - (yDistance * standardProgress));
                if (view instanceof NumPadAnimationListener) {
                    ((NumPadAnimationListener) view).setProgress(scaledProgress);
                }
            }
        }
    }
}
