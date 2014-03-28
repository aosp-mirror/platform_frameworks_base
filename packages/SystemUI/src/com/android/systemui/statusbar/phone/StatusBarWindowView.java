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

import android.app.StatusBarManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.widget.FrameLayout;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;


public class StatusBarWindowView extends FrameLayout
{
    public static final String TAG = "StatusBarWindowView";
    public static final boolean DEBUG = BaseStatusBar.DEBUG;

    private ExpandHelper mExpandHelper;
    private ViewGroup latestItems;
    private NotificationPanelView mNotificationPanel;
    private View mNotificationScroller;

    PhoneStatusBar mService;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMotionEventSplittingEnabled(false);
        setWillNotDraw(!DEBUG);
    }

    @Override
    protected void onAttachedToWindow () {
        super.onAttachedToWindow();

        ExpandHelper.ScrollAdapter scrollAdapter;
        if (BaseStatusBar.ENABLE_NOTIFICATION_STACK) {
            NotificationStackScrollLayout stackScrollLayout =
                    (NotificationStackScrollLayout) findViewById(R.id.notification_stack_scroller);

            // ScrollView and notification container are unified in a single view now.
            latestItems = stackScrollLayout;
            scrollAdapter = stackScrollLayout;
            mNotificationScroller = stackScrollLayout;
        } else {
            latestItems = (ViewGroup) findViewById(R.id.latestItems);
            mNotificationScroller = findViewById(R.id.scroll);
            scrollAdapter = new ExpandHelper.ScrollAdapter() {
                @Override
                public boolean isScrolledToTop() {
                    return mNotificationScroller.getScrollY() == 0;
                }

                @Override
                public View getHostView() {
                    return mNotificationScroller;
                }
            };
        }
        mNotificationPanel = (NotificationPanelView) findViewById(R.id.notification_panel);
        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_row_max_height);
        mExpandHelper = new ExpandHelper(getContext(), (ExpandHelper.Callback) latestItems,
                minHeight, maxHeight);
        mExpandHelper.setEventSource(this);
        mExpandHelper.setScrollAdapter(scrollAdapter);

        // We really need to be able to animate while window animations are going on
        // so that activities may be started asynchronously from panel animations
        final ViewRootImpl root = getViewRootImpl();
        if (root != null) {
            root.setDrawDuringWindowsAnimating(true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_BACK:
            if (!down) {
                mService.animateCollapsePanels();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean intercept = false;
        if (mNotificationPanel.isFullyExpanded()
                && mNotificationScroller.getVisibility() == View.VISIBLE) {
            intercept = mExpandHelper.onInterceptTouchEvent(ev);
        }
        if (!intercept) {
            super.onInterceptTouchEvent(ev);
        }
        if (intercept) {
            MotionEvent cancellation = MotionEvent.obtain(ev);
            cancellation.setAction(MotionEvent.ACTION_CANCEL);
            latestItems.onInterceptTouchEvent(cancellation);
            cancellation.recycle();
        }
        return intercept;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = false;
        if (mNotificationPanel.isFullyExpanded()) {
            handled = mExpandHelper.onTouchEvent(ev);
        }
        if (!handled) {
            handled = super.onTouchEvent(ev);
        }
        final int action = ev.getAction();
        if (!handled && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            mService.setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
        }
        return handled;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (DEBUG) {
            Paint pt = new Paint();
            pt.setColor(0x80FFFF00);
            pt.setStrokeWidth(12.0f);
            pt.setStyle(Paint.Style.STROKE);
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), pt);
        }
    }

    public void cancelExpandHelper() {
        if (mExpandHelper != null) {
            mExpandHelper.cancel();
        }
    }
}

