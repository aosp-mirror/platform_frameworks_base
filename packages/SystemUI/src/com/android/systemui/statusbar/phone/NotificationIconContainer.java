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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;
import com.android.systemui.statusbar.stack.ViewState;

import java.util.WeakHashMap;

/**
 * A container for notification icons. It handles overflowing icons properly and positions them
 * correctly on the screen.
 */
public class NotificationIconContainer extends AlphaOptimizedFrameLayout {
    private static final String TAG = "NotificationIconContainer";

    private boolean mShowAllIcons = true;
    private int mIconTint;
    private WeakHashMap<View, ViewState> mIconStates = new WeakHashMap<>();

    public NotificationIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float centerY = getHeight() / 2.0f;
        // we layout all our children on the left at the top
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (centerY - height / 2.0f);
            child.layout(0, top, width, top + height);
        }
        if (mShowAllIcons) {
            resetViewStates(mIconStates);
            calculateIconStates(getChildCount());
            applyIconStates(mIconStates);
        }
    }

    public void applyIconStates(WeakHashMap<View, ViewState> iconStates) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewState childState = iconStates.get(child);
            if (childState != null) {
                childState.applyToView(child);
            }
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        mIconStates.put(child, new ViewState());
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        mIconStates.remove(child);
    }

    public void setIconTint(int iconTint) {
        mIconTint = iconTint;
    }

    public void resetViewStates(WeakHashMap<View, ViewState> viewStates) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            ViewState iconState = mIconStates.get(view);
            iconState.initFrom(view);
        }
    }

    /**
     * Gets a new state based on the number of visible icons starting from the right.
     * If this is not a whole number, the fraction means by how much the icon is appearing.
     */
    public WeakHashMap<View, ViewState> calculateIconStates(float numberOfVisibleIcons) {
        int childCount = getChildCount();
        float visibleIconStart = childCount - numberOfVisibleIcons;
        int firstIconIndex = (int) visibleIconStart;
        float translationX = 0.0f;
        for (int i = 0; i < childCount; i++) {
            View view = getChildAt(i);
            ViewState iconState = mIconStates.get(view);
            if (i >= firstIconIndex) {
                iconState.xTranslation = translationX;
                float appearAmount = 1.0f;
                if (i == firstIconIndex) {
                    appearAmount = 1.0f - (visibleIconStart - firstIconIndex);
                }
                translationX += appearAmount * view.getWidth();
            }
        }
        return mIconStates;
    }

    public WeakHashMap<View, ViewState> getIconStates() {
        return mIconStates;
    }

    /**
     * Sets whether the layout should always show all icons.
     * If this is true, the icon positions will be updated on layout.
     * If this if false, the layout is managed from the outside and layouting won't trigger a
     * repositioning of the icons.
     */
    public void setShowAllIcons(boolean showAllIcons) {
        mShowAllIcons = showAllIcons;
    }
}
