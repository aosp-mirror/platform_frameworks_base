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
package com.android.packageinstaller.permission.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class ManualLayoutFrame extends ViewGroup {
    private int mContentBottom;
    private int mWidth;

    public ManualLayoutFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void onConfigurationChanged() {
        mContentBottom = 0;
        mWidth = 0;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mWidth != 0) {
            int newWidth = mWidth;
            final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            switch (widthMode) {
                case MeasureSpec.AT_MOST: {
                    newWidth = Math.min(mWidth, MeasureSpec.getSize(widthMeasureSpec));
                } break;
                case MeasureSpec.EXACTLY: {
                    newWidth = MeasureSpec.getSize(widthMeasureSpec);
                } break;
            }
            if (newWidth != mWidth) {
                mWidth = newWidth;
            }
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(mWidth, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mWidth == 0) {
            mWidth = getMeasuredWidth();
        }

        measureChild(getChildAt(0), widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // We want to keep the content bottom at the same place to avoid movement of the "Allow"
        // button.
        // Try to keep the content bottom at the same height. If this would move the dialog out of
        // the top of the screen move it down as much as possible, then keep it at that position for
        // the rest of the sequence of permission dialogs.
        View content = getChildAt(0);
        if (mContentBottom == 0 || content.getMeasuredHeight() > mContentBottom) {
            mContentBottom = (getMeasuredHeight() + content.getMeasuredHeight()) / 2;
        }
        final int contentLeft = (getMeasuredWidth() - content.getMeasuredWidth()) / 2;
        final int contentTop = mContentBottom - content.getMeasuredHeight();
        final int contentRight = contentLeft + content.getMeasuredWidth();
        content.layout(contentLeft, contentTop, contentRight, mContentBottom);
    }
}
