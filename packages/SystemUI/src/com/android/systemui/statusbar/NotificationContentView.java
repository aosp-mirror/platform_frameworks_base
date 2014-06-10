/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import android.widget.FrameLayout;
import com.android.systemui.R;

/**
 * A frame layout containing the actual payload of the notification, including the contracted and
 * expanded layout. This class is responsible for clipping the content and and switching between the
 * expanded and contracted view depending on its clipped size.
 */
public class NotificationContentView extends FrameLayout {

    private final Rect mClipBounds = new Rect();

    private View mContractedChild;
    private View mExpandedChild;

    private int mSmallHeight;
    private int mClipTopAmount;
    private int mActualHeight;

    public NotificationContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSmallHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        mActualHeight = mSmallHeight;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateClipping();
    }

    public void setContractedChild(View child) {
        if (mContractedChild != null) {
            removeView(mContractedChild);
        }
        sanitizeContractedLayoutParams(child);
        addView(child);
        mContractedChild = child;
        selectLayout();
    }

    public void setExpandedChild(View child) {
        if (mExpandedChild != null) {
            removeView(mExpandedChild);
        }
        addView(child);
        mExpandedChild = child;
        selectLayout();
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        selectLayout();
        updateClipping();
    }

    public int getMaxHeight() {

        // The maximum height is just the laid out height.
        return getHeight();
    }

    public int getMinHeight() {
        return mSmallHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        updateClipping();
    }

    private void updateClipping() {
        mClipBounds.set(0, mClipTopAmount, getWidth(), mActualHeight);
        setClipBounds(mClipBounds);
    }

    private void sanitizeContractedLayoutParams(View contractedChild) {
        LayoutParams lp = (LayoutParams) contractedChild.getLayoutParams();
        lp.height = mSmallHeight;
        contractedChild.setLayoutParams(lp);
    }

    private void selectLayout() {
        if (mActualHeight <= mSmallHeight || mExpandedChild == null) {
            if (mContractedChild != null && mContractedChild.getVisibility() != View.VISIBLE) {
                mContractedChild.setVisibility(View.VISIBLE);
            }
            if (mExpandedChild != null && mExpandedChild.getVisibility() != View.INVISIBLE) {
                mExpandedChild.setVisibility(View.INVISIBLE);
            }
        } else {
            if (mExpandedChild.getVisibility() != View.VISIBLE) {
                mExpandedChild.setVisibility(View.VISIBLE);
            }
            if (mContractedChild != null && mContractedChild.getVisibility() != View.INVISIBLE) {
                mContractedChild.setVisibility(View.INVISIBLE);
            }
        }
    }

    public void notifyContentUpdated() {
        selectLayout();
    }

    public boolean isContentExpandable() {
        return mExpandedChild != null;
    }
}
