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

package com.android.documentsui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.LinearLayout;

/**
 * Layout for a single item in List mode.  This class overrides the default focus listener in order
 * to light up a focus indicator when it is focused.
 */
public class ListItem extends LinearLayout
{
    public ListItem(Context context) {
        super(context);
    }

    public ListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        TypedValue color = new TypedValue();
        int colorId = gainFocus ? android.R.attr.colorAccent : android.R.color.transparent;
        getContext().getTheme().resolveAttribute(colorId, color, true);

        findViewById(R.id.focus_indicator).setBackgroundColor(color.data);
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }
}
