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

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;

import java.util.Locale;

/**
 * An expand button in a notification
 */
@RemoteViews.RemoteView
public class NotificationExpandButton extends FrameLayout {

    private View mPillView;
    private TextView mNumberView;
    private ImageView mIconView;
    private boolean mExpanded;
    private int mNumber;
    private int mDefaultPillColor;
    private int mDefaultTextColor;
    private int mHighlightPillColor;
    private int mHighlightTextColor;
    private boolean mDisallowColor;

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
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPillView = findViewById(R.id.expand_button_pill);
        mNumberView = findViewById(R.id.expand_button_number);
        mIconView = findViewById(R.id.expand_button_icon);
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

    /**
     * Disable the use of the accent colors for this view, if true.
     */
    public void setGrayedOut(boolean shouldApply) {
        mDisallowColor = shouldApply;
        updateColors();
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
        updateExpandedState();
    }

    private void updateExpandedState() {
        int drawableId;
        int contentDescriptionId;
        if (mExpanded) {
            drawableId = R.drawable.ic_collapse_notification;
            contentDescriptionId = R.string.expand_button_content_description_expanded;
        } else {
            drawableId = R.drawable.ic_expand_notification;
            contentDescriptionId = R.string.expand_button_content_description_collapsed;
        }
        setContentDescription(mContext.getText(contentDescriptionId));
        mIconView.setImageDrawable(getContext().getDrawable(drawableId));

        // changing the expanded state can affect the number display
        updateNumber();
    }

    private void updateNumber() {
        if (shouldShowNumber()) {
            CharSequence text = mNumber >= 100
                    ? getResources().getString(R.string.unread_convo_overflow, 99)
                    : String.format(Locale.getDefault(), "%d", mNumber);
            mNumberView.setText(text);
            mNumberView.setVisibility(VISIBLE);
        } else {
            mNumberView.setVisibility(GONE);
        }

        // changing number can affect the color
        updateColors();
    }

    private void updateColors() {
        if (shouldShowNumber() && !mDisallowColor) {
            mPillView.setBackgroundTintList(ColorStateList.valueOf(mHighlightPillColor));
            mIconView.setColorFilter(mHighlightTextColor);
            mNumberView.setTextColor(mHighlightTextColor);
        } else {
            mPillView.setBackgroundTintList(ColorStateList.valueOf(mDefaultPillColor));
            mIconView.setColorFilter(mDefaultTextColor);
            mNumberView.setTextColor(mDefaultTextColor);
        }
    }

    private boolean shouldShowNumber() {
        return !mExpanded && mNumber > 1;
    }

    /**
     * Set the color used for the expand chevron and the text
     */
    @RemotableViewMethod
    public void setDefaultTextColor(int color) {
        mDefaultTextColor = color;
        updateColors();
    }

    /**
     * Sets the color used to for the expander when there is no number shown
     */
    @RemotableViewMethod
    public void setDefaultPillColor(@ColorInt int color) {
        mDefaultPillColor = color;
        updateColors();
    }

    /**
     * Set the color used for the expand chevron and the text
     */
    @RemotableViewMethod
    public void setHighlightTextColor(int color) {
        mHighlightTextColor = color;
        updateColors();
    }

    /**
     * Sets the color used to highlight the expander when there is a number shown
     */
    @RemotableViewMethod
    public void setHighlightPillColor(@ColorInt int color) {
        mHighlightPillColor = color;
        updateColors();
    }

    /**
     * Sets the number shown inside the expand button.
     * This only appears when the expand button is collapsed, and when greater than 1.
     */
    @RemotableViewMethod
    public void setNumber(int number) {
        if (mNumber != number) {
            mNumber = number;
            updateNumber();
        }
    }
}
