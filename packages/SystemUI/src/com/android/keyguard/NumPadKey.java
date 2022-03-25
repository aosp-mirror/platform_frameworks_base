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
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.widget.LockPatternUtils;
import com.android.settingslib.Utils;
import com.android.systemui.R;

public class NumPadKey extends ViewGroup {
    // list of "ABC", etc per digit, starting with '0'
    static String sKlondike[];

    private final TextView mDigitText;
    private final TextView mKlondikeText;
    private final LockPatternUtils mLockPatternUtils;
    private final PowerManager mPM;

    private int mDigit = -1;
    private int mTextViewResId;
    private PasswordTextView mTextView;

    @Nullable
    private NumPadAnimator mAnimator;
    private int mOrientation;

    private View.OnClickListener mListener = new View.OnClickListener() {
        @Override
        public void onClick(View thisView) {
            if (mTextView == null && mTextViewResId > 0) {
                final View v = NumPadKey.this.getRootView().findViewById(mTextViewResId);
                if (v != null && v instanceof PasswordTextView) {
                    mTextView = (PasswordTextView) v;
                }
            }
            if (mTextView != null && mTextView.isEnabled()) {
                mTextView.append(Character.forDigit(mDigit, 10));
            }
            userActivity();
        }
    };

    public void userActivity() {
        mPM.userActivity(SystemClock.uptimeMillis(), false);
    }

    public NumPadKey(Context context) {
        this(context, null);
    }

    public NumPadKey(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.numPadKeyStyle);
    }

    public NumPadKey(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, R.layout.keyguard_num_pad_key);
    }

    protected NumPadKey(Context context, AttributeSet attrs, int defStyle, int contentResource) {
        super(context, attrs, defStyle);
        setFocusable(true);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NumPadKey, defStyle,
                contentResource);

        try {
            mDigit = a.getInt(R.styleable.NumPadKey_digit, mDigit);
            mTextViewResId = a.getResourceId(R.styleable.NumPadKey_textView, 0);
        } finally {
            a.recycle();
        }

        setOnClickListener(mListener);
        setOnHoverListener(new LiftToActivateListener(
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE)));

        mLockPatternUtils = new LockPatternUtils(context);
        mPM = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(contentResource, this, true);

        mDigitText = (TextView) findViewById(R.id.digit_text);
        mDigitText.setText(Integer.toString(mDigit));
        mKlondikeText = (TextView) findViewById(R.id.klondike_text);

        if (mDigit >= 0) {
            if (sKlondike == null) {
                sKlondike = getResources().getStringArray(R.array.lockscreen_num_pad_klondike);
            }
            if (sKlondike != null && sKlondike.length > mDigit) {
                String klondike = sKlondike[mDigit];
                final int len = klondike.length();
                if (len > 0) {
                    mKlondikeText.setText(klondike);
                } else if (mKlondikeText.getVisibility() != View.GONE) {
                    mKlondikeText.setVisibility(View.INVISIBLE);
                }
            }
        }

        setContentDescription(mDigitText.getText().toString());

        Drawable background = getBackground();
        if (background instanceof GradientDrawable) {
            mAnimator = new NumPadAnimator(context, background.mutate(),
                    R.style.NumPadKey, mDigitText);
        } else {
            mAnimator = null;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mOrientation = newConfig.orientation;
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        int textColor = Utils.getColorAttr(getContext(), android.R.attr.textColorPrimary)
                .getDefaultColor();
        int klondikeColor = Utils.getColorAttr(getContext(), android.R.attr.textColorSecondary)
                .getDefaultColor();
        mDigitText.setTextColor(textColor);
        mKlondikeText.setTextColor(klondikeColor);

        if (mAnimator != null) mAnimator.reloadColors(getContext());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                doHapticKeyClick();
                if (mAnimator != null) mAnimator.expand();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mAnimator != null) mAnimator.contract();
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        measureChildren(widthMeasureSpec, heightMeasureSpec);

        // Set width/height to the same value to ensure a smooth circle for the bg, but shrink
        // the height to match the old pin bouncer.
        // This is only used for PIN/PUK; the main PIN pad now uses ConstraintLayout, which will
        // force our width/height to conform to the ratio in the layout.
        int width = getMeasuredWidth();

        boolean shortenHeight = mAnimator == null
                || mOrientation == Configuration.ORIENTATION_LANDSCAPE;
        int height = shortenHeight ? (int) (width * .66f) : width;

        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int digitHeight = mDigitText.getMeasuredHeight();
        int klondikeHeight = mKlondikeText.getMeasuredHeight();
        int totalHeight = digitHeight + klondikeHeight;
        int top = getHeight() / 2 - totalHeight / 2;
        int centerX = getWidth() / 2;
        int left = centerX - mDigitText.getMeasuredWidth() / 2;
        int bottom = top + digitHeight;
        mDigitText.layout(left, top, left + mDigitText.getMeasuredWidth(), bottom);
        top = (int) (bottom - klondikeHeight * 0.35f);
        bottom = top + klondikeHeight;

        left = centerX - mKlondikeText.getMeasuredWidth() / 2;
        mKlondikeText.layout(left, top, left + mKlondikeText.getMeasuredWidth(), bottom);

        if (mAnimator != null) mAnimator.onLayout(b - t);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    }
}
