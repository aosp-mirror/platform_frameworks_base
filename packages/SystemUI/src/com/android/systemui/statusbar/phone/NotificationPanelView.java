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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import com.android.systemui.R;

public class NotificationPanelView extends PanelView {

    Drawable mHandleBar;
    float mHandleBarHeight;

    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);

        Resources resources = context.getResources();
        mHandleBar = resources.getDrawable(R.drawable.status_bar_close);
        mHandleBarHeight = resources.getDimension(R.dimen.close_handle_height);
    }

    @Override
    public void fling(float vel, boolean always) {
        ((PhoneStatusBarView) mBar).mBar.getGestureRecorder().tag(
            "fling " + ((vel > 0) ? "open" : "closed"),
            "notifications,v=" + vel);
        super.fling(vel, always);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            mHandleBar.setBounds(0, 0, getWidth(), (int) mHandleBarHeight);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        canvas.translate(0, getHeight() - mHandleBarHeight);
        mHandleBar.draw(canvas);
        canvas.translate(0, -getHeight() + mHandleBarHeight);
    }
}
