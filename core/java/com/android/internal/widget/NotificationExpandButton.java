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

import static com.android.internal.widget.ColoredIconHelper.applyGrayTint;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.ViewGroup;
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

    private final int mMinTouchTargetSize;
    private boolean mExpanded;
    private int mOriginalNotificationColor;

    public NotificationExpandButton(Context context) {
        this(context, null, 0, 0);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mMinTouchTargetSize = (int) (getResources().getDisplayMetrics().density * 48 + 0.5);
    }

    /**
     * Show the touchable area of the view for a11y.
     * If the parent is the touch container, then that view's bounds are the touchable area.
     */
    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null && parent.getId() == R.id.expand_button_touch_container) {
            parent.getBoundsOnScreen(outRect, clipToParent);
        } else {
            super.getBoundsOnScreen(outRect, clipToParent);
        }
        extendRectToMinTouchSize(outRect);
    }

    /**
     * Determined if the given point should be touchable.
     * If the parent is the touch container, then any point in that view should be touchable.
     */
    @Override
    public boolean pointInView(float localX, float localY, float slop) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null && parent.getId() == R.id.expand_button_touch_container) {
            // If our parent is checking with us, then the point must be within its bounds.
            return true;
        }
        return super.pointInView(localX, localY, slop);
    }

    @RemotableViewMethod
    public void setOriginalNotificationColor(int color) {
        mOriginalNotificationColor = color;
    }

    public int getOriginalNotificationColor() {
        return mOriginalNotificationColor;
    }

    /**
     * Set the button's color filter: to gray if true, otherwise colored.
     * If this button has no original color, this has no effect.
     */
    public void setGrayedOut(boolean shouldApply) {
        applyGrayTint(mContext, getDrawable(), shouldApply, mOriginalNotificationColor);
    }

    private void extendRectToMinTouchSize(Rect rect) {
        if (rect.width() < mMinTouchTargetSize) {
            rect.left = rect.centerX() - mMinTouchTargetSize / 2;
            rect.right = rect.left + mMinTouchTargetSize;
        }
        if (rect.height() < mMinTouchTargetSize) {
            rect.top = rect.centerY() - mMinTouchTargetSize / 2;
            rect.bottom = rect.top + mMinTouchTargetSize;
        }
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
