/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * Automatically reverses the order of children as they are added.
 * Also reverse the width and height values of layout params
 */
public class ReverseLinearLayout extends LinearLayout {

    private boolean mIsLayoutRtl;

    public ReverseLinearLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIsLayoutRtl = getResources().getConfiguration()
                .getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    @Override
    public void addView(View child) {
        reversParams(child.getLayoutParams());
        if (mIsLayoutRtl) {
            super.addView(child);
        } else {
            super.addView(child, 0);
        }
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        reversParams(params);
        if (mIsLayoutRtl) {
            super.addView(child, params);
        } else {
            super.addView(child, 0, params);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRTLOrder();
    }

    /**
     * In landscape, the LinearLayout is not auto mirrored since it is vertical. Therefore we
     * have to do it manually
     */
    private void updateRTLOrder() {
        boolean isLayoutRtl = getResources().getConfiguration()
                .getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (mIsLayoutRtl != isLayoutRtl) {
            // RTL changed, swap the order of all views.
            int childCount = getChildCount();
            ArrayList<View> childList = new ArrayList<>(childCount);
            for (int i = 0; i < childCount; i++) {
                childList.add(getChildAt(i));
            }
            removeAllViews();
            for (int i = childCount - 1; i >= 0; i--) {
                super.addView(childList.get(i));
            }
            mIsLayoutRtl = isLayoutRtl;
        }
    }

    private void reversParams(ViewGroup.LayoutParams params) {
        if (params == null) {
            return;
        }
        int width = params.width;
        params.width = params.height;
        params.height = width;
    }

}
