/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.widget;

import android.compat.annotation.UnsupportedAppUsage;
import android.text.InputFilter;
import android.text.Spanned;
import android.widget.TextView;

/**
 * Helper class to disable input on a TextView. The input is disabled by swapping in an InputFilter
 * that discards all changes. Use with care if you have customized InputFilter on the target
 * TextView.
 */
public class TextViewInputDisabler {
    private TextView mTextView;
    private InputFilter[] mDefaultFilters;
    private InputFilter[] mNoInputFilters = new InputFilter[] {
            new InputFilter () {
                @Override
                public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                        int dstart, int dend) {
                    return "";
                }
            }
    };

    @UnsupportedAppUsage
    public TextViewInputDisabler(TextView textView) {
        mTextView = textView;
        mDefaultFilters = mTextView.getFilters();
    }

    @UnsupportedAppUsage
    public void setInputEnabled(boolean enabled) {
        mTextView.setFilters(enabled ? mDefaultFilters : mNoInputFilters);
    }
}
