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
package com.android.statsd.loadtest;

import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.TextView;

public abstract class NumericalWatcher implements TextWatcher {

    private static final String TAG = "loadtest.NumericalWatcher";

    private final TextView mTextView;
    private final int mMin;
    private final int mMax;
    private int currentValue = -1;

    public NumericalWatcher(TextView textView, int min, int max) {
        mTextView = textView;
        mMin = min;
        mMax = max;
    }

    public abstract void onNewValue(int newValue);

    @Override
    final public void afterTextChanged(Editable editable) {
        String s = mTextView.getText().toString();
        if (s.isEmpty()) {
          return;
        }
        int unsanitized = Integer.parseInt(s);
        int newValue = sanitize(unsanitized);
        if (currentValue != newValue || unsanitized != newValue) {
            currentValue = newValue;
            editable.clear();
            editable.append(newValue + "");
        }
        onNewValue(newValue);
    }

    @Override
    final public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    final public void onTextChanged(CharSequence s, int start, int before, int count) {}

    private int sanitize(int val) {
        if (val > mMax) {
            val = mMax;
        } else if (val < mMin) {
            val = mMin;
        }
        return val;
    }
}
