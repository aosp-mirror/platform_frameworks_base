/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.phone.NotificationPanelView;

/**
 * A hybrid view which may contain information about one ore more notifications.
 */
public class HybridNotificationView extends AlphaOptimizedLinearLayout {

    protected TextView mTitleView;
    protected TextView mTextView;
    private ViewInvertHelper mInvertHelper;

    public HybridNotificationView(Context context) {
        this(context, null);
    }

    public HybridNotificationView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HybridNotificationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HybridNotificationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitleView = (TextView) findViewById(R.id.notification_title);
        mTextView = (TextView) findViewById(R.id.notification_text);
        mInvertHelper = new ViewInvertHelper(this, NotificationPanelView.DOZE_ANIMATION_DURATION);
    }

    public void bind(CharSequence title) {
        bind(title, null);
    }

    public void bind(CharSequence title, CharSequence text) {
        mTitleView.setText(title);
        mTextView.setText(text);
        requestLayout();
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        mInvertHelper.setInverted(dark, fade, delay);
    }
}
