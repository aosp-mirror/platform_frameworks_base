/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.bubbles;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * A floating object on the screen that has a collapsed and expanded state.
 */
public class BubbleView extends LinearLayout implements BubbleTouchHandler.FloatingView {
    private static final String TAG = "BubbleView";

    private Context mContext;
    private View mIconView;

    private NotificationData.Entry mEntry;
    private int mBubbleSize;
    private int mIconSize;

    public BubbleView(Context context) {
        this(context, null);
    }

    public BubbleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setOrientation(LinearLayout.VERTICAL);
        mContext = context;
        mBubbleSize = getResources().getDimensionPixelSize(R.dimen.bubble_size);
        mIconSize = getResources().getDimensionPixelSize(R.dimen.bubble_icon_size);
    }

    /**
     * Populates this view with a notification.
     *
     * @param entry the notification to display as a bubble.
     */
    public void setNotif(NotificationData.Entry entry) {
        removeAllViews();
        // TODO: migrate to inflater
        mIconView = new ImageView(mContext);
        addView(mIconView);

        LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) mIconView.getLayoutParams();
        iconLp.width = mBubbleSize;
        iconLp.height = mBubbleSize;
        mIconView.setLayoutParams(iconLp);

        update(entry);
    }

    /**
     * Updates the UI based on the entry.
     */
    public void update(NotificationData.Entry entry) {
        mEntry = entry;
        Notification n = entry.notification.getNotification();
        Icon ic = n.getLargeIcon() != null ? n.getLargeIcon() : n.getSmallIcon();

        if (n.getLargeIcon() == null) {
            createCircledIcon(n.color, ic, ((ImageView) mIconView));
        } else {
            ((ImageView) mIconView).setImageIcon(ic);
        }
    }

    /**
     * @return the key identifying this bubble / notification entry associated with this
     * bubble, if it exists.
     */
    public String getKey() {
        return mEntry == null ? null : mEntry.key;
    }

    /**
     * @return the notification entry associated with this bubble.
     */
    public NotificationData.Entry getEntry() {
        return mEntry;
    }

    /**
     * @return the view to display when the bubble is expanded.
     */
    public ExpandableNotificationRow getRowView() {
        return mEntry.getRow();
    }

    @Override
    public void setPosition(int x, int y) {
        setTranslationX(x);
        setTranslationY(y);
    }

    @Override
    public void setPositionX(int x) {
        setTranslationX(x);
    }

    @Override
    public void setPositionY(int y) {
        setTranslationY(y);
    }

    @Override
    public Point getPosition() {
        return new Point((int) getTranslationX(), (int) getTranslationY());
    }

    // Seems sub optimal
    private void createCircledIcon(int tint, Icon icon, ImageView v) {
        // TODO: dark mode
        icon.setTint(Color.WHITE);
        icon.scaleDownIfNecessary(mIconSize, mIconSize);
        v.setImageDrawable(icon.loadDrawable(mContext));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        int color = ContrastColorUtil.ensureContrast(tint, Color.WHITE,
                false /* isBgDarker */, 3);
        Drawable d = new ShapeDrawable(new OvalShape());
        d.setTint(color);
        v.setBackgroundDrawable(d);

        lp.width = mBubbleSize;
        lp.height = mBubbleSize;
        v.setLayoutParams(lp);
    }
}
