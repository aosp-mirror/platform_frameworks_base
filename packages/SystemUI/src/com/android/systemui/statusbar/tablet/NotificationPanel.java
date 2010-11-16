/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Slog;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;

public class NotificationPanel extends RelativeLayout implements StatusBarPanel,
        View.OnClickListener {
    static final String TAG = "NotificationPanel";

    View mSettingsButton;
    View mNotificationButton;
    View mNotificationScroller;
    FrameLayout mSettingsFrame;
    View mSettingsPanel;

    public NotificationPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        mSettingsButton = (ImageView)findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mNotificationButton = (ImageView)findViewById(R.id.notification_button);
        mNotificationButton.setOnClickListener(this);

        mNotificationScroller = findViewById(R.id.notificationScroller);
        mSettingsFrame = (FrameLayout)findViewById(R.id.settings_frame);
    }

    @Override
    public void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        // when we hide, put back the notifications
        if (!isShown()) {
            switchToNotificationMode();
        }
    }

    public void onClick(View v) {
        if (v == mSettingsButton) {
            switchToSettingsMode();
        } else if (v == mNotificationButton) {
            switchToNotificationMode();
        }
    }

    public void switchToSettingsMode() {
        removeSettingsPanel();
        addSettingsPanel();
        mSettingsButton.setVisibility(View.INVISIBLE);
        mNotificationScroller.setVisibility(View.GONE);
        mNotificationButton.setVisibility(View.VISIBLE);
    }

    public void switchToNotificationMode() {
        removeSettingsPanel();
        mSettingsButton.setVisibility(View.VISIBLE);
        mNotificationScroller.setVisibility(View.VISIBLE);
        mNotificationButton.setVisibility(View.INVISIBLE);
    }

    public boolean isInContentArea(int x, int y) {
        final int l = getPaddingLeft();
        final int r = getWidth() - getPaddingRight();
        final int t = getPaddingTop();
        final int b = getHeight() - getPaddingBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    void removeSettingsPanel() {
        if (mSettingsPanel != null) {
            mSettingsFrame.removeViewAt(0);
            mSettingsPanel = null;
        }
    }

    void addSettingsPanel() {
        mSettingsPanel = View.inflate(getContext(), R.layout.sysbar_panel_settings, mSettingsFrame);
    }
}

