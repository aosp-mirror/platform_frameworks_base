/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;

/**
 * Root view to insert Lock screen media controls into the notification stack.
 */
public class MediaHeaderView extends ActivatableNotificationView {

    private View mContentView;

    public MediaHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContentView = findViewById(R.id.keyguard_media_view);
    }

    @Override
    protected View getContentView() {
        return mContentView;
    }

    /**
     * Sets the background color, to be used when album art changes.
     * @param color background
     */
    public void setBackgroundColor(int color) {
        setTintColor(color);
    }
}
