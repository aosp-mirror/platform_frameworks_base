/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.Surface;

import com.android.systemui.R;

/** Temporarily shown view when using QuickSwitch to switch between apps of different rotations */
public class QuickswitchOrientedNavHandle extends NavigationHandle {
    private final int mWidth;
    private final RectF mTmpBoundsRectF = new RectF();
    private @Surface.Rotation int mDeltaRotation;

    public QuickswitchOrientedNavHandle(Context context) {
        super(context);
        mWidth = context.getResources().getDimensionPixelSize(R.dimen.navigation_home_handle_width);
    }

    public void setDeltaRotation(@Surface.Rotation int rotation) {
        mDeltaRotation = rotation;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(computeHomeHandleBounds(), mRadius, mRadius, mPaint);
    }

    public RectF computeHomeHandleBounds() {
        int left;
        int top;
        int bottom;
        int right;
        int radiusOffset = mRadius * 2;
        int topStart = getLocationOnScreen()[1];

        switch (mDeltaRotation) {
            default:
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                int height = mRadius * 2;
                left = getWidth() / 2 - mWidth / 2;
                top = (getHeight() - mBottom - height);
                right = getWidth() / 2 + mWidth / 2;
                bottom = top + height;
                break;
            case Surface.ROTATION_90:
                left = mBottom;
                right = left + radiusOffset;
                top = getHeight() / 2 - (mWidth / 2) - (topStart / 2);
                bottom = top + mWidth;
                break;
            case Surface.ROTATION_270:
                right = getWidth() - mBottom;
                left = right - radiusOffset;
                top = getHeight() / 2 - (mWidth / 2) - (topStart / 2);
                bottom = top + mWidth;
                break;
        }
        mTmpBoundsRectF.set(left, top, right, bottom);
        return mTmpBoundsRectF;
    }
}
