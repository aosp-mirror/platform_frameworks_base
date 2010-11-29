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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

public class NotificationPanel extends LinearLayout implements StatusBarPanel,
        View.OnClickListener {
    static final String TAG = "NotificationPanel";

    View mTitleArea;
    View mSettingsButton;
    View mNotificationButton;
    View mNotificationScroller;
    FrameLayout mContentFrame;
    View mSettingsView;

    public NotificationPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        mTitleArea = findViewById(R.id.title_area);

        mSettingsButton = (ImageView)findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mNotificationButton = (ImageView)findViewById(R.id.notification_button);
        mNotificationButton.setOnClickListener(this);

        mNotificationScroller = findViewById(R.id.notificationScroller);
        mContentFrame = (FrameLayout)findViewById(R.id.content_frame);
    }

    @Override
    public void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        // when we hide, put back the notifications
        if (!isShown()) {
            switchToNotificationMode();
            mNotificationScroller.scrollTo(0, 0);
        }
    }

    /**
     * We need to be aligned at the bottom.  LinearLayout can't do this, so instead,
     * let LinearLayout do all the hard work, and then shift everything down to the bottom.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // We know that none of our children are GONE, so don't worry about skipping GONE views.
        final int N = getChildCount();
        if (N == 0) {
            return;
        }
        final int allocatedBottom = getChildAt(N-1).getBottom();
        final int shift = b - allocatedBottom - getPaddingBottom();
        if (shift <= 0) {
            return;
        }
        for (int i=0; i<N; i++) {
            final View c = getChildAt(i);
            c.layout(c.getLeft(), c.getTop() + shift, c.getRight(), c.getBottom() + shift);
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
        removeSettingsView();
        addSettingsView();
        mSettingsButton.setVisibility(View.INVISIBLE);
        mNotificationScroller.setVisibility(View.GONE);
        mNotificationButton.setVisibility(View.VISIBLE);
    }

    public void switchToNotificationMode() {
        removeSettingsView();
        mSettingsButton.setVisibility(View.VISIBLE);
        mNotificationScroller.setVisibility(View.VISIBLE);
        mNotificationButton.setVisibility(View.INVISIBLE);
    }

    public boolean isInContentArea(int x, int y) {
        final int l = mContentFrame.getLeft();
        final int r = mContentFrame.getRight();
        final int t = mTitleArea.getTop();
        final int b = mContentFrame.getBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    void removeSettingsView() {
        if (mSettingsView != null) {
            mContentFrame.removeView(mSettingsView);
            mSettingsView = null;
        }
    }

    void addSettingsView() {
        LayoutInflater infl = LayoutInflater.from(getContext());
        mSettingsView = infl.inflate(R.layout.status_bar_settings_view, mContentFrame, false);
        mContentFrame.addView(mSettingsView);
    }
}

