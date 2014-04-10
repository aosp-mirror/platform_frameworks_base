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

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.IconMerger;

/**
 * A view to display all the overflowing icons on Keyguard.
 */
public class NotificationOverflowIconsView extends IconMerger {

    private TextView mMoreText;
    private int mTintColor;

    public NotificationOverflowIconsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTintColor = getResources().getColor(R.color.keyguard_overflow_content_color);
    }

    public void setMoreText(TextView moreText) {
        mMoreText = moreText;
    }

    public void addNotification(NotificationData.Entry notification) {
        StatusBarIconView v = new StatusBarIconView(getContext(), "",
                notification.notification.getNotification());
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setColorFilter(mTintColor, PorterDuff.Mode.MULTIPLY);
        addView(v);
        v.set(notification.icon.getStatusBarIcon());
        updateMoreText();
    }

    private void updateMoreText() {
        mMoreText.setText(getResources().getQuantityString(
                R.plurals.keyguard_more_overflow_text, getChildCount(), getChildCount()));
    }
}
