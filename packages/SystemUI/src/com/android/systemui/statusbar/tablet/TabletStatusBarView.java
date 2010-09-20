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
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class TabletStatusBarView extends FrameLayout {
    private Handler mHandler;

    private View[] mIgnoreChildren = new View[2];
    private View[] mPanels = new View[2];
    private int[] mPos = new int[2];

    public TabletStatusBarView(Context context) {
        super(context);
    }

    public TabletStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mHandler.removeMessages(TabletStatusBarService.MSG_CLOSE_NOTIFICATION_PANEL);
            mHandler.sendEmptyMessage(TabletStatusBarService.MSG_CLOSE_NOTIFICATION_PANEL);
            mHandler.removeMessages(TabletStatusBarService.MSG_CLOSE_SYSTEM_PANEL);
            mHandler.sendEmptyMessage(TabletStatusBarService.MSG_CLOSE_SYSTEM_PANEL);

            for (int i=0; i<mPanels.length; i++) {
                if (mPanels[i].getVisibility() == View.VISIBLE) {
                    if (eventInside(mIgnoreChildren[i], ev)) {
                        return true;
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    private boolean eventInside(View v, MotionEvent ev) {
        // assume that x and y are window coords because we are.
        final int x = (int)ev.getX();
        final int y = (int)ev.getY();

        final int[] p = mPos;
        v.getLocationInWindow(p);

        final int l = p[0];
        final int t = p[1];
        final int r = p[0] + v.getWidth();
        final int b = p[1] + v.getHeight();

        return x >= l && x < r && y >= t && y < b;
    }

    public void setHandler(Handler h) {
        mHandler = h;
    }

    public void setIgnoreChildren(int index, View ignore, View panel) {
        mIgnoreChildren[index] = ignore;
        mPanels[index] = panel;
    }
}
