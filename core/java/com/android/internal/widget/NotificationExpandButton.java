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

import static android.app.Flags.notificationsRedesignTemplates;

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;

import java.util.Locale;

/**
 * An expand button in a notification
 */
@RemoteViews.RemoteView
public class NotificationExpandButton extends FrameLayout {

    private Drawable mPillDrawable;
    private TextView mNumberView;
    private ImageView mIconView;
    private LinearLayout mPillView;
    private boolean mExpanded;
    private int mNumber;
    private int mDefaultPillColor;
    private int mDefaultTextColor;
    private int mHighlightPillColor;
    private int mHighlightTextColor;
    // Track whether this ever had mExpanded = true, so that we don't highlight it anymore.
    private boolean mWasExpanded = false;

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
        final LayerDrawable layeredPill = (LayerDrawable) mPillView.getBackground();
        mPillDrawable = layeredPill.findDrawableByLayerId(R.id.expand_button_pill_colorized_layer);
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
            if (notificationsRedesignTemplates()) {
                mWasExpanded = true;
                drawableId = R.drawable.ic_notification_2025_collapse;
            } else {
                drawableId = R.drawable.ic_collapse_notification;
            }
            contentDescriptionId = R.string.expand_button_content_description_expanded;
        } else {
            if (notificationsRedesignTemplates()) {
                drawableId = R.drawable.ic_notification_2025_expand;
            } else {
                drawableId = R.drawable.ic_expand_notification;
            }
            contentDescriptionId = R.string.expand_button_content_description_collapsed;
        }
        setContentDescription(mContext.getText(contentDescriptionId));
        mIconView.setImageDrawable(getContext().getDrawable(drawableId));

        if (!notificationsRedesignTemplates()) {
            // changing the expanded state can affect the number display
            updateNumber();
        } else {
            updateColors();
        }
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

        // changing number can affect the color and padding
        updateColors();
        updatePadding();
    }

    private void updatePadding() {
        if (!notificationsRedesignTemplates()) {
            return;
        }

        // Reduce the padding at the end when showing the number, since the arrow icon has more
        // inherent spacing than the number does. This makes the content look more centered.
        // Vertical padding remains unchanged.
        int reducedPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_2025_expand_button_reduced_end_padding);
        int normalPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_2025_expand_button_horizontal_icon_padding);
        mPillView.setPaddingRelative(
                /* start = */ normalPadding,
                /* top = */ mPillView.getPaddingTop(),
                /* end = */ shouldShowNumber() ? reducedPadding : normalPadding,
                /* bottom = */ mPillView.getPaddingBottom()
        );
    }

    /**
     * Use highlight colors for the expander for groups (when the number is showing) that haven't
     * been opened before, as long as the colors are available.
     */
    private boolean shouldBeHighlighted() {
        return !mWasExpanded && shouldShowNumber()
                && mHighlightPillColor != 0 && mHighlightTextColor != 0;
    }

    private void updateColors() {
        if (notificationsRedesignTemplates()) {
            if (shouldBeHighlighted()) {
                mPillDrawable.setTintList(ColorStateList.valueOf(mHighlightPillColor));
                mIconView.setColorFilter(mHighlightTextColor);
                mNumberView.setTextColor(mHighlightTextColor);
            } else {
                mPillDrawable.setTintList(ColorStateList.valueOf(mDefaultPillColor));
                mIconView.setColorFilter(mDefaultTextColor);
                mNumberView.setTextColor(mDefaultTextColor);
            }
        } else {
            if (shouldShowNumber()) {
                if (mHighlightPillColor != 0) {
                    mPillDrawable.setTintList(ColorStateList.valueOf(mHighlightPillColor));
                }
                mIconView.setColorFilter(mHighlightTextColor);
                if (mHighlightTextColor != 0) {
                    mNumberView.setTextColor(mHighlightTextColor);
                }
            } else {
                if (mDefaultPillColor != 0) {
                    mPillDrawable.setTintList(ColorStateList.valueOf(mDefaultPillColor));
                }
                mIconView.setColorFilter(mDefaultTextColor);
                if (mDefaultTextColor != 0) {
                    mNumberView.setTextColor(mDefaultTextColor);
                }
            }
        }
    }

    private boolean shouldShowNumber() {
        if (notificationsRedesignTemplates()) {
            return mNumber > 1;
        }
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
     * This only appears when {@link this#shouldShowNumber()} is true.
     */
    @RemotableViewMethod
    public void setNumber(int number) {
        if (mNumber != number) {
            mNumber = number;
            updateNumber();
        }
    }
}
