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

package com.android.layoutlib.test.myapplication;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A widget to test obtaining arrays from resources.
 */
public class ArraysCheckWidget extends LinearLayout {
    public ArraysCheckWidget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ArraysCheckWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ArraysCheckWidget(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources resources = context.getResources();
        for (CharSequence chars : resources.getTextArray(R.array.array)) {
            addTextView(context, chars);
        }
        for (int i : resources.getIntArray(R.array.int_array)) {
            addTextView(context, String.valueOf(i));
        }
        for (String string : resources.getStringArray(R.array.string_array)) {
            addTextView(context, string);
        }
    }

    private void addTextView(Context context, CharSequence string) {
        TextView textView = new TextView(context);
        textView.setText(string);
        textView.setTextSize(30);
        addView(textView);
    }
}
