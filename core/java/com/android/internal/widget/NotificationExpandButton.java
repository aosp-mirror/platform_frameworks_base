/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RemoteViews;

/**
 * An expand button in a notification
 */
@RemoteViews.RemoteView
public class NotificationExpandButton extends ImageView {

    public NotificationExpandButton(Context context) {
        super(context);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationExpandButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        super.getBoundsOnScreen(outRect, clipToParent);
        extendRectToMinTouchSize(outRect);
    }

    private void extendRectToMinTouchSize(Rect rect) {
        int touchTargetSize = (int) (getResources().getDisplayMetrics().density * 48);
        rect.left = rect.centerX() - touchTargetSize / 2;
        rect.right = rect.left + touchTargetSize;
        rect.top = rect.centerY() - touchTargetSize / 2;
        rect.bottom = rect.top + touchTargetSize;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }
}
