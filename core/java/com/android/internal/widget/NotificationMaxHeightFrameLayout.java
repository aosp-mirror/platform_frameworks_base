/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

/**
 * This custom subclass of FrameLayout enforces that its calculated height be no larger than the
 * standard height of a notification.  This is not required in the normal case, as the
 * NotificationContentView gets this same value from the ExpandableNotificationRow, and enforces it
 * as a maximum.  It is required in the case of the HUN version of the headerless notification,
 * because that style puts the actions below the headerless portion.  If we don't cap this, then in
 * certain situations (larger fonts, decorated custom views) the contents of the headerless
 * notification push on the margins and increase the size of that view, which causes the actions to
 * be cropped on the bottom by the HUN notification max height.
 */
@RemoteViews.RemoteView
public class NotificationMaxHeightFrameLayout extends FrameLayout {
    private final int mNotificationMaxHeight;

    public NotificationMaxHeightFrameLayout(Context context) {
        this(context, null, 0, 0);
    }

    public NotificationMaxHeightFrameLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public NotificationMaxHeightFrameLayout(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationMaxHeightFrameLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        // `notification_min_height` refers to "minimized" not "minimum"; it is a max height
        mNotificationMaxHeight = getFontScaledHeight(mContext,
                com.android.internal.R.dimen.notification_min_height);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getSize(heightMeasureSpec) > mNotificationMaxHeight) {
            final int mode = MeasureSpec.getMode(heightMeasureSpec);
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(mNotificationMaxHeight, mode);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * NOTE: This is copied from com.android.systemui.statusbar.notification.NotificationUtils
     *
     * @param dimenId the dimen to look up
     * @return the font scaled dimen as if it were in sp but doesn't shrink sizes below dp
     */
    private static int getFontScaledHeight(Context context, int dimenId) {
        final int dimensionPixelSize = context.getResources().getDimensionPixelSize(dimenId);
        final float factor = Math.max(1.0f, context.getResources().getDisplayMetrics().scaledDensity
                / context.getResources().getDisplayMetrics().density);
        return (int) (dimensionPixelSize * factor);
    }
}
