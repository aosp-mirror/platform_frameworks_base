/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.tv;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * A view containing PIP controls including fullscreen, close, and media controls.
 */
public class PipControlButtonView extends RelativeLayout {

    private OnFocusChangeListener mFocusChangeListener;
    private ImageView mIconImageView;
    ImageView mButtonImageView;
    private TextView mDescriptionTextView;
    private Animator mTextFocusGainAnimator;
    private Animator mButtonFocusGainAnimator;
    private Animator mTextFocusLossAnimator;
    private Animator mButtonFocusLossAnimator;

    private final OnFocusChangeListener mInternalFocusChangeListener =
            new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        startFocusGainAnimation();
                    } else {
                        startFocusLossAnimation();
                    }

                    if (mFocusChangeListener != null) {
                        mFocusChangeListener.onFocusChange(PipControlButtonView.this, hasFocus);
                    }
                }
            };

    public PipControlButtonView(Context context) {
        this(context, null, 0, 0);
    }

    public PipControlButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public PipControlButtonView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public PipControlButtonView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.tv_pip_control_button, this);

        mIconImageView = findViewById(R.id.icon);
        mButtonImageView = findViewById(R.id.button);
        mDescriptionTextView = findViewById(R.id.desc);

        int[] values = new int[] {android.R.attr.src, android.R.attr.text};
        TypedArray typedArray =
            context.obtainStyledAttributes(attrs, values, defStyleAttr, defStyleRes);

        setImageResource(typedArray.getResourceId(0, 0));
        setText(typedArray.getResourceId(1, 0));

        typedArray.recycle();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mButtonImageView.setOnFocusChangeListener(mInternalFocusChangeListener);

        mTextFocusGainAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.anim.tv_pip_controls_focus_gain_animation);
        mTextFocusGainAnimator.setTarget(mDescriptionTextView);
        mButtonFocusGainAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.anim.tv_pip_controls_focus_gain_animation);
        mButtonFocusGainAnimator.setTarget(mButtonImageView);

        mTextFocusLossAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.anim.tv_pip_controls_focus_loss_animation);
        mTextFocusLossAnimator.setTarget(mDescriptionTextView);
        mButtonFocusLossAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.anim.tv_pip_controls_focus_loss_animation);
        mButtonFocusLossAnimator.setTarget(mButtonImageView);
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        mButtonImageView.setOnClickListener(listener);
    }

    @Override
    public void setOnFocusChangeListener(OnFocusChangeListener listener) {
        mFocusChangeListener = listener;
    }

    /**
     * Sets the drawable for the button with the given drawable.
     */
    public void setImageDrawable(Drawable d) {
        mIconImageView.setImageDrawable(d);
    }

    /**
     * Sets the drawable for the button with the given resource id.
     */
    public void setImageResource(int resId) {
        if (resId != 0) {
            mIconImageView.setImageResource(resId);
        }
    }

    /**
     * Sets the text for description the with the given string.
     */
    public void setText(CharSequence text) {
        mButtonImageView.setContentDescription(text);
        mDescriptionTextView.setText(text);
    }

    /**
     * Sets the text for description the with the given resource id.
     */
    public void setText(int resId) {
        if (resId != 0) {
            mButtonImageView.setContentDescription(getContext().getString(resId));
            mDescriptionTextView.setText(resId);
        }
    }

    private static void cancelAnimator(Animator animator) {
        if (animator.isStarted()) {
            animator.cancel();
        }
    }

    /**
     * Starts the focus gain animation.
     */
    public void startFocusGainAnimation() {
        cancelAnimator(mButtonFocusLossAnimator);
        cancelAnimator(mTextFocusLossAnimator);
        mTextFocusGainAnimator.start();
        if (mButtonImageView.getAlpha() < 1f) {
            // If we had faded out the ripple drawable, run our manual focus change animation.
            // See the comment at {@link #startFocusLossAnimation()} for the reason of manual
            // animator.
            mButtonFocusGainAnimator.start();
        }
    }

    /**
     * Starts the focus loss animation.
     */
    public void startFocusLossAnimation() {
        cancelAnimator(mButtonFocusGainAnimator);
        cancelAnimator(mTextFocusGainAnimator);
        mTextFocusLossAnimator.start();
        if (mButtonImageView.hasFocus()) {
            // Button uses ripple that has the default animation for the focus changes.
            // Howevever, it doesn't expose the API to fade out while it is focused,
            // so we should manually run the fade out animation when PIP controls row loses focus.
            mButtonFocusLossAnimator.start();
        }
    }

    /**
     * Resets to initial state.
     */
    public void reset() {
        cancelAnimator(mButtonFocusGainAnimator);
        cancelAnimator(mTextFocusGainAnimator);
        cancelAnimator(mButtonFocusLossAnimator);
        cancelAnimator(mTextFocusLossAnimator);
        mButtonImageView.setAlpha(1f);
        mDescriptionTextView.setAlpha(mButtonImageView.hasFocus() ? 1f : 0f);
    }
}
