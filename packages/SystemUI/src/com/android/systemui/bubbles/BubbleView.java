/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * A floating object on the screen that can post message updates.
 */
public class BubbleView extends FrameLayout {
    private static final String TAG = "BubbleView";

    // Same value as Launcher3 badge code
    private static final float WHITE_SCRIM_ALPHA = 0.54f;
    private Context mContext;

    private BadgedImageView mBadgedImageView;
    private TextView mMessageView;
    private int mPadding;
    private int mIconInset;

    private NotificationEntry mEntry;

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
        mContext = context;
        // XXX: can this padding just be on the view and we look it up?
        mPadding = getResources().getDimensionPixelSize(R.dimen.bubble_view_padding);
        mIconInset = getResources().getDimensionPixelSize(R.dimen.bubble_icon_inset);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBadgedImageView = (BadgedImageView) findViewById(R.id.bubble_image);
        mMessageView = (TextView) findViewById(R.id.message_view);
        mMessageView.setVisibility(GONE);
        mMessageView.setPivotX(0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateViews();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        measureChild(mBadgedImageView, widthSpec, heightSpec);
        measureChild(mMessageView, widthSpec, heightSpec);
        boolean messageGone = mMessageView.getVisibility() == GONE;
        int imageHeight = mBadgedImageView.getMeasuredHeight();
        int imageWidth = mBadgedImageView.getMeasuredWidth();
        int messageHeight = messageGone ? 0 : mMessageView.getMeasuredHeight();
        int messageWidth = messageGone ? 0 : mMessageView.getMeasuredWidth();
        setMeasuredDimension(
                getPaddingStart() + imageWidth + mPadding + messageWidth + getPaddingEnd(),
                getPaddingTop() + Math.max(imageHeight, messageHeight) + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        left = getPaddingStart();
        top = getPaddingTop();
        int imageWidth = mBadgedImageView.getMeasuredWidth();
        int imageHeight = mBadgedImageView.getMeasuredHeight();
        int messageWidth = mMessageView.getMeasuredWidth();
        int messageHeight = mMessageView.getMeasuredHeight();
        mBadgedImageView.layout(left, top, left + imageWidth, top + imageHeight);
        mMessageView.layout(left + imageWidth + mPadding, top,
                left + imageWidth + mPadding + messageWidth, top + messageHeight);
    }

    /**
     * Populates this view with a notification.
     * <p>
     * This should only be called when a new notification is being set on the view, updates to the
     * current notification should use {@link #update(NotificationEntry)}.
     *
     * @param entry the notification to display as a bubble.
     */
    public void setNotif(NotificationEntry entry) {
        mEntry = entry;
        updateViews();
    }

    /**
     * The {@link NotificationEntry} associated with this view, if one exists.
     */
    @Nullable
    public NotificationEntry getEntry() {
        return mEntry;
    }

    /**
     * The key for the {@link NotificationEntry} associated with this view, if one exists.
     */
    @Nullable
    public String getKey() {
        return (mEntry != null) ? mEntry.key : null;
    }

    /**
     * Updates the UI based on the entry, updates badge and animates messages as needed.
     */
    public void update(NotificationEntry entry) {
        mEntry = entry;
        updateViews();
    }

    /**
     * @return the {@link ExpandableNotificationRow} view to display notification content when the
     * bubble is expanded.
     */
    @Nullable
    public ExpandableNotificationRow getRowView() {
        return (mEntry != null) ? mEntry.getRow() : null;
    }

    /**
     * Marks this bubble as "read", i.e. no badge should show.
     */
    public void updateDotVisibility() {
        boolean showDot = getEntry().showInShadeWhenBubble();
        animateDot(showDot);
    }

    /**
     * Animates the badge to show or hide.
     */
    private void animateDot(boolean showDot) {
        if (mBadgedImageView.isShowingDot() != showDot) {
            mBadgedImageView.setShowDot(showDot);
            mBadgedImageView.clearAnimation();
            mBadgedImageView.animate().setDuration(200)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setUpdateListener((valueAnimator) -> {
                        float fraction = valueAnimator.getAnimatedFraction();
                        fraction = showDot ? fraction : 1 - fraction;
                        mBadgedImageView.setDotScale(fraction);
                    }).withEndAction(() -> {
                if (!showDot) {
                    mBadgedImageView.setShowDot(false);
                }
            }).start();
        }
    }

    private void updateViews() {
        if (mEntry == null) {
            return;
        }
        Notification n = mEntry.notification.getNotification();
        boolean isLarge = n.getLargeIcon() != null;
        Icon ic = isLarge ? n.getLargeIcon() : n.getSmallIcon();
        Drawable iconDrawable = ic.loadDrawable(mContext);
        if (!isLarge) {
            // Center icon on coloured background
            iconDrawable.setTint(Color.WHITE); // TODO: dark mode
            Drawable bg = new ColorDrawable(n.color);
            InsetDrawable d = new InsetDrawable(iconDrawable, mIconInset);
            Drawable[] layers = {bg, d};
            mBadgedImageView.setImageDrawable(new LayerDrawable(layers));
        } else {
            mBadgedImageView.setImageDrawable(iconDrawable);
        }
        int badgeColor = determineDominateColor(iconDrawable, n.color);
        mBadgedImageView.setDotColor(badgeColor);
        animateDot(mEntry.showInShadeWhenBubble() /* showDot */);
    }

    private int determineDominateColor(Drawable d, int defaultTint) {
        // XXX: should we pull from the drawable, app icon, notif tint?
        return ColorUtils.blendARGB(defaultTint, Color.WHITE, WHITE_SCRIM_ALPHA);
    }
}
