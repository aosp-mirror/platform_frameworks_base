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
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.StyleRes;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.keyguard.KeyguardIndication;

/**
 * A view to show hints on Keyguard ("Swipe up to unlock", "Tap again to open").
 */
public class KeyguardIndicationTextView extends TextView {
    @StyleRes
    private static int sStyleId = R.style.TextAppearance_Keyguard_BottomArea;
    @StyleRes
    private static int sButtonStyleId = R.style.TextAppearance_Keyguard_BottomArea_Button;

    private boolean mAnimationsEnabled = true;
    private CharSequence mMessage;
    private KeyguardIndication mKeyguardIndicationInfo;

    private Animator mLastAnimator;

    public KeyguardIndicationTextView(Context context) {
        super(context);
    }

    public KeyguardIndicationTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public KeyguardIndicationTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public KeyguardIndicationTextView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Clears message queue and currently shown message.
     */
    public void clearMessages() {
        if (mLastAnimator != null) {
            mLastAnimator.cancel();
        }
        setText("");
    }

    /**
     * Changes the text with an animation.
     */
    public void switchIndication(int textResId) {
        switchIndication(getResources().getText(textResId), null);
    }

    /**
     * Changes the text with an animation.
     *
     * @param indication The text to show.
     */
    public void switchIndication(KeyguardIndication indication) {
        switchIndication(indication == null ? null : indication.getMessage(), indication);
    }

    /**
     * Changes the text with an animation.
     */
    public void switchIndication(CharSequence text, KeyguardIndication indication) {
        switchIndication(text, indication, true, null);
    }

    /**
     * Updates the text with an optional animation.
     *
     * @param text The text to show.
     * @param indication optional display information for the text
     * @param animate whether to animate this indication in - we may not want this on AOD
     * @param onAnimationEndCallback runnable called after this indication is animated in
     */
    public void switchIndication(CharSequence text, KeyguardIndication indication,
            boolean animate, Runnable onAnimationEndCallback) {
        mMessage = text;
        mKeyguardIndicationInfo = indication;

        if (animate) {
            final boolean hasIcon = indication != null && indication.getIcon() != null;
            AnimatorSet animator = new AnimatorSet();
            // Make sure each animation is visible for a minimum amount of time, while not worrying
            // about fading in blank text
            if (!TextUtils.isEmpty(mMessage) || hasIcon) {
                Animator inAnimator = getInAnimator();
                inAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        if (onAnimationEndCallback != null) {
                            onAnimationEndCallback.run();
                        }
                    }
                });
                animator.playSequentially(getOutAnimator(), inAnimator);
            } else {
                Animator outAnimator = getOutAnimator();
                outAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        if (onAnimationEndCallback != null) {
                            onAnimationEndCallback.run();
                        }
                    }
                });
                animator.play(outAnimator);
            }

            if (mLastAnimator != null) {
                mLastAnimator.cancel();
            }
            mLastAnimator = animator;
            animator.start();
        } else {
            setAlpha(1f);
            setTranslationY(0f);
            setNextIndication();
            if (onAnimationEndCallback != null) {
                onAnimationEndCallback.run();
            }
            if (mLastAnimator != null) {
                mLastAnimator.cancel();
                mLastAnimator = null;
            }
        }
    }

    private AnimatorSet getOutAnimator() {
        AnimatorSet animatorSet = new AnimatorSet();
        Animator fadeOut = ObjectAnimator.ofFloat(this, View.ALPHA, 0f);
        fadeOut.setDuration(getFadeOutDuration());
        fadeOut.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
        fadeOut.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled = false;
            @Override
            public void onAnimationEnd(Animator animator) {
                super.onAnimationEnd(animator);
                if (!mCancelled) {
                    setNextIndication();
                }
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                super.onAnimationCancel(animator);
                mCancelled = true;
                setAlpha(0);
            }
        });

        Animator yTranslate =
                ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0, -getYTranslationPixels());
        yTranslate.setDuration(getFadeOutDuration());
        animatorSet.playTogether(fadeOut, yTranslate);

        return animatorSet;
    }

    private void setNextIndication() {
        if (mKeyguardIndicationInfo != null) {
            // First, update the style.
            // If a background is set on the text, we don't want shadow on the text
            if (mKeyguardIndicationInfo.getBackground() != null) {
                setTextAppearance(sButtonStyleId);
            } else {
                setTextAppearance(sStyleId);
            }
            setBackground(mKeyguardIndicationInfo.getBackground());
            setTextColor(mKeyguardIndicationInfo.getTextColor());
            setOnClickListener(mKeyguardIndicationInfo.getClickListener());
            setClickable(mKeyguardIndicationInfo.getClickListener() != null);
            final Drawable icon = mKeyguardIndicationInfo.getIcon();
            if (icon != null) {
                icon.setTint(getCurrentTextColor());
                if (icon instanceof AnimatedVectorDrawable) {
                    ((AnimatedVectorDrawable) icon).start();
                }
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        }
        setText(mMessage);
    }

    private AnimatorSet getInAnimator() {
        AnimatorSet animatorSet = new AnimatorSet();
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(this, View.ALPHA, 1f);
        fadeIn.setStartDelay(getFadeInDelay());
        fadeIn.setDuration(getFadeInDuration());
        fadeIn.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);

        Animator yTranslate =
                ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, getYTranslationPixels(), 0);
        yTranslate.setDuration(getYInDuration());
        yTranslate.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                setTranslationY(0);
                setAlpha(1f);
            }
        });
        animatorSet.playTogether(yTranslate, fadeIn);

        return animatorSet;
    }

    @VisibleForTesting
    public void setAnimationsEnabled(boolean enabled) {
        mAnimationsEnabled = enabled;
    }

    private long getFadeInDelay() {
        if (!mAnimationsEnabled) return 0L;
        return 150L;
    }

    private long getFadeInDuration() {
        if (!mAnimationsEnabled) return 0L;
        return 317L;
    }

    private long getYInDuration() {
        if (!mAnimationsEnabled) return 0L;
        return 600L;
    }

    private long getFadeOutDuration() {
        if (!mAnimationsEnabled) return 0L;
        return 167L;
    }

    private int getYTranslationPixels() {
        return mContext.getResources().getDimensionPixelSize(
                com.android.systemui.R.dimen.keyguard_indication_y_translation);
    }
}
