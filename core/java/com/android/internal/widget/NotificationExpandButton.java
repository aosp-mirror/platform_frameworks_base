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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.R;

/**
 * An expand button in a notification
 */
@RemoteViews.RemoteView
public class NotificationExpandButton extends ImageView {

    private boolean mExpanded;
    private int mOriginalNotificationColor;

    public NotificationExpandButton(Context context) {
        super(context);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        super.getBoundsOnScreen(outRect, clipToParent);
        extendRectToMinTouchSize(outRect);
    }

    @RemotableViewMethod
    public void setOriginalNotificationColor(int color) {
        mOriginalNotificationColor = color;
    }

    public int getOriginalNotificationColor() {
        return mOriginalNotificationColor;
    }

    private void extendRectToMinTouchSize(Rect rect) {
        int touchTargetSize = (int) (getResources().getDisplayMetrics().density * 48);
        rect.left = rect.centerX() - touchTargetSize / 2;
        rect.right = rect.left + touchTargetSize;
        rect.top = rect.centerY() - touchTargetSize / 2;
        rect.bottom = rect.top + touchTargetSize;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }

    /**
     * Update the button's drawable, content description, and color for the given expanded state.
     */
    @RemotableViewMethod
    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
        updateExpandButton();
    }

    private void updateExpandButton() {
        int drawableId;
        int contentDescriptionId;
        if (mExpanded) {
            drawableId = R.drawable.ic_collapse_notification;
            contentDescriptionId = R.string.expand_button_content_description_expanded;
        } else {
            drawableId = R.drawable.ic_expand_notification;
            contentDescriptionId = R.string.expand_button_content_description_collapsed;
        }
        setImageDrawable(getContext().getDrawable(drawableId));
        setColorFilter(mOriginalNotificationColor);
        setContentDescription(mContext.getText(contentDescriptionId));
    }
}
