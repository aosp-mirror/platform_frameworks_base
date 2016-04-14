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

package com.android.systemui.tv.pip;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View.OnFocusChangeListener;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * A view containing PIP controls including fullscreen, close, and media controls.
 */
public class PipControlButtonView extends LinearLayout {
    private OnFocusChangeListener mFocusChangeListener;
    private ImageView mButtonImageView;
    private TextView mDescriptionTextView;
    private Animator mFocusGainAnimator;
    private Animator mFocusLoseAnimator;

    private final OnFocusChangeListener mInternalFocusChangeListener =
            new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        if (mFocusLoseAnimator.isStarted()) {
                            mFocusLoseAnimator.cancel();
                        }
                        mFocusGainAnimator.start();
                    } else {
                        if (mFocusGainAnimator.isStarted()) {
                            mFocusGainAnimator.cancel();
                        }
                        mFocusLoseAnimator.start();
                    }

                    if (mFocusChangeListener != null) {
                        mFocusChangeListener.onFocusChange(v, hasFocus);
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

        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER);

        mButtonImageView = (ImageView) findViewById(R.id.button);
        mDescriptionTextView = (TextView) findViewById(R.id.desc);

        int[] values = new int[] {android.R.attr.src, android.R.attr.text};
        TypedArray typedArray =
            context.obtainStyledAttributes(attrs, values, defStyleAttr, defStyleRes);

        mButtonImageView.setImageDrawable(typedArray.getDrawable(0));
        mDescriptionTextView.setText(typedArray.getText(1));

        typedArray.recycle();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mButtonImageView.setOnFocusChangeListener(mInternalFocusChangeListener);

        mFocusGainAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.anim.tv_pip_controls_text_focus_gain_animation);
        mFocusGainAnimator.setTarget(mDescriptionTextView);
        mFocusLoseAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.anim.tv_pip_controls_text_focus_lose_animation);
        mFocusLoseAnimator.setTarget(mDescriptionTextView);
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
     * Sets the drawable for the button with the given resource id.
     */
    public void setImageResource(int resId) {
        mButtonImageView.setImageResource(resId);
    }

    /**
     * Sets the text for description the with the given resource id.
     */
    public void setText(int resId) {
        mDescriptionTextView.setText(resId);
    }

    @Override
    public boolean isFocused() {
        return mButtonImageView.isFocused();
    }
}
