/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget;

import android.app.Flags;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/**
 * An image view that holds the icon displayed on the left side of a notification row.
 */
@RemoteViews.RemoteView
public class NotificationRowIconView extends CachingIconView {
    public NotificationRowIconView(Context context) {
        super(context);
    }

    public NotificationRowIconView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationRowIconView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationRowIconView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        // If showing the app icon, we don't need background or padding.
        if (Flags.notificationsUseAppIcon()) {
            setPadding(0, 0, 0, 0);
            setBackground(null);
        }

        super.onFinishInflate();
    }
}
