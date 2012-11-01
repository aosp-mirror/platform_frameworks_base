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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.SpannableStringBuilder;
import android.text.style.TextAppearanceSpan;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

public class NumPadKey extends Button {
    // list of "ABC", etc per digit, starting with '0'
    static String sKlondike[];

    int mDigit = -1;
    int mTextViewResId;
    TextView mTextView = null;
    boolean mEnableHaptics;

    private View.OnClickListener mListener = new View.OnClickListener() {
        @Override
        public void onClick(View thisView) {
            if (mTextView == null) {
                if (mTextViewResId > 0) {
                    final View v = NumPadKey.this.getRootView().findViewById(mTextViewResId);
                    if (v != null && v instanceof TextView) {
                        mTextView = (TextView) v;
                    }
                }
            }
            if (mTextView != null) {
                mTextView.append(String.valueOf(mDigit));
            }
            doHapticKeyClick();
        }
    };

    public NumPadKey(Context context) {
        this(context, null);
    }

    public NumPadKey(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NumPadKey(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NumPadKey);
        mDigit = a.getInt(R.styleable.NumPadKey_digit, mDigit);
        setTextViewResId(a.getResourceId(R.styleable.NumPadKey_textView, 0));

        setOnClickListener(mListener);
        setOnHoverListener(new LiftToActivateListener(context));

        mEnableHaptics = new LockPatternUtils(context).isTactileFeedbackEnabled();

        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(String.valueOf(mDigit));
        if (mDigit >= 0) {
            if (sKlondike == null) {
                sKlondike = context.getResources().getStringArray(
                        R.array.lockscreen_num_pad_klondike);
            }
            if (sKlondike != null && sKlondike.length > mDigit) {
                final String extra = sKlondike[mDigit];
                final int extraLen = extra.length();
                if (extraLen > 0) {
                    builder.append(extra);
                    builder.setSpan(
                        new TextAppearanceSpan(context, R.style.TextAppearance_NumPadKey_Klondike),
                        builder.length()-extraLen, builder.length(), 0);
                }
            }
        }
        setText(builder);
    }

    public void setTextView(TextView tv) {
        mTextView = tv;
    }

    public void setTextViewResId(int resId) {
        mTextView = null;
        mTextViewResId = resId;
    }

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        if (mEnableHaptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    /**
     * Hover listener that implements lift-to-activate interaction for
     * accessibility. May be added to multiple views.
     */
    static class LiftToActivateListener implements View.OnHoverListener {
        /** Manager used to query accessibility enabled state. */
        private final AccessibilityManager mAccessibilityManager;

        public LiftToActivateListener(Context context) {
            mAccessibilityManager = (AccessibilityManager) context.getSystemService(
                    Context.ACCESSIBILITY_SERVICE);
        }

        @Override
        public boolean onHover(View v, MotionEvent event) {
            // When touch exploration is turned on, lifting a finger while
            // inside the view bounds should perform a click action.
            if (mAccessibilityManager.isEnabled()
                    && mAccessibilityManager.isTouchExplorationEnabled()) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                        // Lift-to-type temporarily disables double-tap
                        // activation.
                        v.setClickable(false);
                        break;
                    case MotionEvent.ACTION_HOVER_EXIT:
                        final int x = (int) event.getX();
                        final int y = (int) event.getY();
                        if ((x > v.getPaddingLeft()) && (y > v.getPaddingTop())
                                && (x < v.getWidth() - v.getPaddingRight())
                                && (y < v.getHeight() - v.getPaddingBottom())) {
                            v.performClick();
                        }
                        v.setClickable(true);
                        break;
                }
            }
            return false;
        }
    }
}
