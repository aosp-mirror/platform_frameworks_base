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

import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.animation.DisappearAnimationUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.DevicePostureController.DevicePostureInt;

/**
 * Displays a PIN pad for unlocking.
 */
public class KeyguardPINView extends KeyguardPinBasedInputView {

    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtilsLocked;
    private ConstraintLayout mContainer;
    private int mDisappearYTranslation;
    private View[][] mViews;
    @DevicePostureInt private int mLastDevicePosture = DEVICE_POSTURE_UNKNOWN;

    public KeyguardPINView(Context context) {
        this(context, null);
    }

    public KeyguardPINView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAppearAnimationUtils = new AppearAnimationUtils(context);
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
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        updateMargins();
    }

    void onDevicePostureChanged(@DevicePostureInt int posture) {
        mLastDevicePosture = posture;
        updateMargins();
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
        enableClipping(false);
        setAlpha(1f);
        setTranslationY(mAppearAnimationUtils.getStartTranslation());
        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */, 500 /* duration */,
                0, mAppearAnimationUtils.getInterpolator(),
                getAnimationListener(InteractionJankMonitor.CUJ_LOCKSCREEN_PIN_APPEAR));
        mAppearAnimationUtils.startAnimation2d(mViews,
                new Runnable() {
                    @Override
                    public void run() {
                        enableClipping(true);
                    }
                });
    }

    public boolean startDisappearAnimation(boolean needsSlowUnlockTransition,
            final Runnable finishRunnable) {

        enableClipping(false);
        setTranslationY(0);
        AppearAnimationUtils.startTranslationYAnimation(this, 0 /* delay */, 280 /* duration */,
                mDisappearYTranslation, mDisappearAnimationUtils.getInterpolator(),
                getAnimationListener(InteractionJankMonitor.CUJ_LOCKSCREEN_PIN_DISAPPEAR));
        DisappearAnimationUtils disappearAnimationUtils = needsSlowUnlockTransition
                        ? mDisappearAnimationUtilsLocked
                        : mDisappearAnimationUtils;
        disappearAnimationUtils.startAnimation2d(mViews,
                () -> {
                    enableClipping(true);
                    if (finishRunnable != null) {
                        finishRunnable.run();
                    }
                });
        return true;
    }

    private void enableClipping(boolean enable) {
        mContainer.setClipToPadding(enable);
        mContainer.setClipChildren(enable);
        setClipChildren(enable);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
