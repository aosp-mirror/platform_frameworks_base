/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.Canvas;

import com.android.systemui.R;

/** Temporarily shown view when using QuickSwitch to switch between apps of different rotations */
public class VerticalNavigationHandle extends NavigationHandle {
    private final int mWidth;

    public VerticalNavigationHandle(Context context) {
        super(context);
        mWidth = context.getResources().getDimensionPixelSize(R.dimen.navigation_home_handle_width);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int left;
        int top;
        int bottom;
        int right;

        int radiusOffset = mRadius * 2;
        right = getWidth() - mBottom;
        top = getHeight() / 2 - (mWidth / 2); /* (height of screen / 2) - (height of bar / 2) */
        left = getWidth() - mBottom - radiusOffset;
        bottom = getHeight() / 2 + (mWidth / 2);
        canvas.drawRoundRect(left, top, right, bottom, mRadius, mRadius, mPaint);
    }
}
