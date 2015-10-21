/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Base class for mocked views.
 * <p/>
 * FrameLayout with a single TextView. Doesn't allow adding any other views to itself.
 */
public class MockView extends FrameLayout {

    private final TextView mView;

    public MockView(Context context) {
        this(context, null);
    }

    public MockView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MockView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MockView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mView = new TextView(context, attrs);
        mView.setTextColor(0xFF000000);
        setGravity(Gravity.CENTER);
        setText(getClass().getSimpleName());
        addView(mView);
        setBackgroundColor(0xFF7F7F7F);
    }

    // Only allow adding one TextView.
    @Override
    public void addView(View child) {
        if (child == mView) {
            super.addView(child);
        }
    }

    @Override
    public void addView(View child, int index) {
        if (child == mView) {
            super.addView(child, index);
        }
    }

    @Override
    public void addView(View child, int width, int height) {
        if (child == mView) {
            super.addView(child, width, height);
        }
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        if (child == mView) {
            super.addView(child, params);
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (child == mView) {
            super.addView(child, index, params);
        }
    }

    // The following methods are called by the IDE via reflection, and should be considered part
    // of the API.
    // Historically, MockView used to be a textView and had these methods. Now, we simply delegate
    // them to the contained textView.

    public void setText(CharSequence text) {
        mView.setText(text);
    }

    public void setGravity(int gravity) {
        mView.setGravity(gravity);
    }
}
