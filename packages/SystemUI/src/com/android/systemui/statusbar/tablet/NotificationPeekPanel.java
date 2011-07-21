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
import android.view.MotionEvent;
import android.widget.RelativeLayout;

public class NotificationPeekPanel extends RelativeLayout implements StatusBarPanel {
    TabletStatusBar mBar;

    public NotificationPeekPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationPeekPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public boolean isInContentArea(int x, int y) {
        final int l = getPaddingLeft();
        final int r = getWidth() - getPaddingRight();
        final int t = getPaddingTop();
        final int b = getHeight() - getPaddingBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    public void setBar(TabletStatusBar bar) {
        mBar = bar;
    }

    // We don't really want to intercept the touch event, but we *do* want to reset the fade timer
    // in case the user is interacting with some custom controls or something.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        mBar.resetNotificationPeekFadeTimer();
        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }
}
