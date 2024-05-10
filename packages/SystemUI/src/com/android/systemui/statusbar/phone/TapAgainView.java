/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.ColorInt;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.wm.shell.animation.Interpolators;

/**
 * View to show a toast-like popup on the notification shade and quick settings.
 */
public class TapAgainView extends TextView {
    private TextView mTextView;

    public TapAgainView(
            @NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        updateColor();
    }

    void updateColor() {
        final @ColorInt int onSurface = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorOnSurface);
        setTextColor(onSurface);
        setBackground(getResources().getDrawable(R.drawable.rounded_bg_full, mContext.getTheme()));
    }

    /** Make the view visible. */
    public void animateIn() {
        int yTranslation = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_y_translation);

        AnimatorSet animatorSet = new AnimatorSet();
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(this, View.ALPHA, 1f);
        fadeIn.setStartDelay(150);  // From KeyguardIndicationTextView#getFadeInDelay
        fadeIn.setDuration(317);  // From KeyguardIndicationTextView#getFadeInDuration
        fadeIn.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);

        Animator yTranslate =
                ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, yTranslation, 0);
        yTranslate.setDuration(600);  // From KeyguardIndicationTextView#getYInDuration
        yTranslate.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                setTranslationY(0);
            }
        });
        animatorSet.playTogether(yTranslate, fadeIn);
        animatorSet.start();
        setVisibility(View.VISIBLE);
    }

    /** Make the view gone. */
    public void animateOut() {
        long fadeOutDuration = 167L;  // From KeyguardIndicationTextView#getFadeOutDuration
        int yTranslation = mContext.getResources().getDimensionPixelSize(
                com.android.systemui.res.R.dimen.keyguard_indication_y_translation);

        AnimatorSet animatorSet = new AnimatorSet();
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(this, View.ALPHA, 0f);
        fadeOut.setDuration(fadeOutDuration);
        fadeOut.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);

        Animator yTranslate =
                ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0, -yTranslation);
        yTranslate.setDuration(fadeOutDuration);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                setVisibility(GONE);
            }
        });
        animatorSet.playTogether(yTranslate, fadeOut);
        animatorSet.start();
    }
}
