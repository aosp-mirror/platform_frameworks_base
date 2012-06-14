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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextSwitcher;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NotificationRowLayout;


public class StatusBarWindowView extends FrameLayout
{
    private static final String TAG = "StatusBarWindowView";

    private ExpandHelper mExpandHelper;
    private NotificationRowLayout latestItems;

    PhoneStatusBar mService;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMotionEventSplittingEnabled(false);
    }

    @Override
    protected void onAttachedToWindow () {
        super.onAttachedToWindow();
        latestItems = (NotificationRowLayout) findViewById(R.id.latestItems);
        ScrollView scroller = (ScrollView) findViewById(R.id.scroll);
        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_max_height);
        mExpandHelper = new ExpandHelper(mContext, latestItems, minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_BACK:
            if (!down) {
                mService.animateCollapse();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        MotionEvent cancellation = MotionEvent.obtain(ev);
        cancellation.setAction(MotionEvent.ACTION_CANCEL);

        boolean intercept = mExpandHelper.onInterceptTouchEvent(ev) ||
                super.onInterceptTouchEvent(ev);
        if (intercept) {
            latestItems.onInterceptTouchEvent(cancellation);
        }
        return intercept;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = mExpandHelper.onTouchEvent(ev) ||
                super.onTouchEvent(ev);
        return handled;
    }
}

