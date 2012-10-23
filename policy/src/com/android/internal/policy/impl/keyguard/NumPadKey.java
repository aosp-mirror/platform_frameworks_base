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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

public class NumPadKey extends Button {
    // XXX localize
    static final String KLONDIKE[] = {
        "", "", " ABC", " DEF", " GHI", " JKL", " MNO", " PQRS", " TUV", " WXYZ" };

    int mDigit = -1;
    int mTextViewResId;
    TextView mTextView = null;

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

        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(String.valueOf(mDigit));
        if (mDigit >= 0) {
            final String extra = KLONDIKE[mDigit];
            final int extraLen = extra.length();
            if (extraLen > 0) {
                builder.append(extra);
                builder.setSpan(
                        new TextAppearanceSpan(context, R.style.TextAppearance_NumPadKey_Klondike),
                        builder.length()-extraLen, builder.length(), 0);
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
}
