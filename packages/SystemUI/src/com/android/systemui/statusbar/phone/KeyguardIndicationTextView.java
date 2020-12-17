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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Interpolators;

import java.util.LinkedList;

/**
 * A view to show hints on Keyguard ("Swipe up to unlock", "Tap again to open").
 */
public class KeyguardIndicationTextView extends TextView {

    private static final int FADE_OUT_MILLIS = 200;
    private static final int FADE_IN_MILLIS = 250;
    private static final long MSG_DURATION_MILLIS = 600;
    private long mNextAnimationTime = 0;
    private boolean mAnimationsEnabled = true;
    private LinkedList<CharSequence> mMessages = new LinkedList<>();

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
     * Changes the text with an animation and makes sure a single indication is shown long enough.
     *
     * @param text The text to show.
     */
    public void switchIndication(CharSequence text) {
        if (text == null) text = "";

        CharSequence lastPendingMessage = mMessages.peekLast();
        if (TextUtils.equals(lastPendingMessage, text)
                || (lastPendingMessage == null && TextUtils.equals(text, getText()))) {
            return;
        }
        mMessages.add(text);

        Animator fadeOut = ObjectAnimator.ofFloat(this, View.ALPHA, 0f);
        fadeOut.setDuration(getFadeOutMillis());
        fadeOut.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);

        final CharSequence nextText = text;
        fadeOut.addListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator animator) {
                    setText(mMessages.poll());
                }
            });

        final AnimatorSet animSet = new AnimatorSet();
        final AnimatorSet.Builder animSetBuilder = animSet.play(fadeOut);

        // Make sure each animation is visible for a minimum amount of time, while not worrying
        // about fading in blank text
        long timeInMillis = System.currentTimeMillis();
        long delay = Math.max(0, mNextAnimationTime - timeInMillis);
        setNextAnimationTime(timeInMillis + delay + getFadeOutMillis());

        if (!text.equals("")) {
            setNextAnimationTime(mNextAnimationTime + MSG_DURATION_MILLIS);

            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(this, View.ALPHA, 1f);
            fadeIn.setDuration(getFadeInMillis());
            fadeIn.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            animSetBuilder.before(fadeIn);
        }

        animSet.setStartDelay(delay);
        animSet.start();
    }

    @VisibleForTesting
    public void setAnimationsEnabled(boolean enabled) {
        mAnimationsEnabled = enabled;
    }

    private long getFadeInMillis() {
        if (mAnimationsEnabled) return FADE_IN_MILLIS;
        return 0L;
    }

    private long getFadeOutMillis() {
        if (mAnimationsEnabled) return FADE_OUT_MILLIS;
        return 0L;
    }

    private void setNextAnimationTime(long time) {
        if (mAnimationsEnabled) {
            mNextAnimationTime = time;
        } else {
            mNextAnimationTime = 0L;
        }
    }

    /**
     * See {@link #switchIndication}.
     */
    public void switchIndication(int textResId) {
        switchIndication(getResources().getText(textResId));
    }
}
