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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Container view for overflowing notification icons on Keyguard.
 */
public class NotificationOverflowContainer extends FrameLayout
        implements LatestItemView.OnActivatedListener {

    private NotificationOverflowIconsView mIconsView;
    private LatestItemView.OnActivatedListener mOnActivatedListener;
    private NotificationActivator mActivator;

    public NotificationOverflowContainer(Context context) {
        super(context);
    }

    public NotificationOverflowContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationOverflowContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationOverflowContainer(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIconsView = (NotificationOverflowIconsView) findViewById(R.id.overflow_icons_view);
        mIconsView.setMoreText((TextView) findViewById(R.id.more_text));

        LatestItemView latestItemView = (LatestItemView) findViewById(R.id.container);
        mActivator = new NotificationActivator(this);
        mActivator.setDimmed(true);
        latestItemView.setOnActivatedListener(this);
        latestItemView.setLocked(true);
    }

    public NotificationOverflowIconsView getIconsView() {
        return mIconsView;
    }

    public void setOnActivatedListener(LatestItemView.OnActivatedListener onActivatedListener) {
        mOnActivatedListener = onActivatedListener;
    }

    @Override
    public void onActivated(View view) {
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onActivated(this);
        }
    }

    @Override
    public void onReset(View view) {
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onReset(this);
        }
    }

    public NotificationActivator getActivator() {
        return mActivator;
    }
}
