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

package com.android.documentsui.dirlist;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.ColorRes;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.android.documentsui.Events;

/**
 * A {@link SwipeRefreshLayout} that only refresh on touch events.
 */
public class TouchSwipeRefreshLayout extends SwipeRefreshLayout {

    private static final int[] COLOR_RES = new int[] { android.R.attr.colorAccent };
    private static int COLOR_ACCENT_INDEX = 0;

    public TouchSwipeRefreshLayout(Context context) {
        this(context, null);
    }

    public TouchSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(COLOR_RES);
        @ColorRes int colorId = a.getResourceId(COLOR_ACCENT_INDEX, -1);
        a.recycle();
        setColorSchemeResources(colorId);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return Events.isMouseEvent(e) ? false : super.onInterceptTouchEvent(e);
    }
}
