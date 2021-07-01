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

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.animation.DisappearAnimationUtils;
import com.android.systemui.R;

import java.util.List;

/**
 * Displays a PIN pad for unlocking.
 */
public class KeyguardPINView extends KeyguardPinBasedInputView {

    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtilsLocked;
    private ViewGroup mContainer;
    private ViewGroup mRow0;
    private ViewGroup mRow1;
    private ViewGroup mRow2;
    private ViewGroup mRow3;
    private int mDisappearYTranslation;
    private View[][] mViews;

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

    @Override
    protected void resetState() {
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.pinEntry;
    }

    private void updateMargins() {
        int bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.num_pad_row_margin_bottom);

        for (ViewGroup vg : List.of(mRow1, mRow2, mRow3)) {
            ((LinearLayout.LayoutParams) vg.getLayoutParams()).setMargins(0, 0, 0, bottomMargin);
        }

        bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.num_pad_entry_row_margin_bottom);
        ((LinearLayout.LayoutParams) mRow0.getLayoutParams()).setMargins(0, 0, 0, bottomMargin);

        if (mEcaView != null) {
            int ecaTopMargin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.keyguard_eca_top_margin);
            int ecaBottomMargin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.keyguard_eca_bottom_margin);
            ((LinearLayout.LayoutParams) mEcaView.getLayoutParams()).setMargins(0, ecaTopMargin,
                    0, ecaBottomMargin);
        }

        View entryView = findViewById(R.id.pinEntry);
        ViewGroup.LayoutParams lp = entryView.getLayoutParams();
        lp.height = mContext.getResources().getDimensionPixelSize(R.dimen.keyguard_password_height);
        entryView.setLayoutParams(lp);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mContainer = findViewById(R.id.pin_container);
        mRow0 = findViewById(R.id.row0);
        mRow1 = findViewById(R.id.row1);
        mRow2 = findViewById(R.id.row2);
        mRow3 = findViewById(R.id.row3);
        mViews = new View[][]{
                new View[]{
                        mRow0, null, null
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
        mRow1.setClipToPadding(enable);
        mRow2.setClipToPadding(enable);
        mRow3.setClipToPadding(enable);
        setClipChildren(enable);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
