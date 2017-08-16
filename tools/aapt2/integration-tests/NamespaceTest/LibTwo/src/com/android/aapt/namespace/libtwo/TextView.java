/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.aapt.namespace.libtwo;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

public class TextView extends android.widget.TextView {

    private String mTextViewAttr;

    public TextView(Context context) {
        this(context, null);
    }

    public TextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TextView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray ta = context.getTheme().obtainStyledAttributes(attrs, R.styleable.TextView,
                0, 0);
        try {
            mTextViewAttr = ta.getString(R.styleable.TextView_textview_attr);
        } finally {
            ta.recycle();
        }
    }

    public String getTextViewAttr() {
        return mTextViewAttr;
    }
}
