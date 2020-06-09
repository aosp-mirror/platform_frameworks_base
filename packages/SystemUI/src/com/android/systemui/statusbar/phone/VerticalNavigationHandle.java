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
import android.graphics.RectF;

import com.android.systemui.R;

/** Temporarily shown view when using QuickSwitch to switch between apps of different rotations */
public class VerticalNavigationHandle extends NavigationHandle {
    private final int mWidth;
    private final RectF mTmpBoundsRectF = new RectF();

    public VerticalNavigationHandle(Context context) {
        super(context);
        mWidth = context.getResources().getDimensionPixelSize(R.dimen.navigation_home_handle_width);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(computeHomeHandleBounds(), mRadius, mRadius, mPaint);
    }

    RectF computeHomeHandleBounds() {
        int left;
        int top;
        int bottom;
        int right;
        int topStart = getLocationOnScreen()[1];
        int radiusOffset = mRadius * 2;
        right = getWidth() - mBottom;
        top = getHeight() / 2 - (mWidth / 2) - (topStart / 2);
        left = getWidth() - mBottom - radiusOffset;
        bottom = top + mWidth;
        mTmpBoundsRectF.set(left, top, right, bottom);
        return mTmpBoundsRectF;
    }
}
