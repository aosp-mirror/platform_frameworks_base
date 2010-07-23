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
import android.content.res.Resources;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;

import com.android.systemui.R;


public class NotificationIconArea extends LinearLayout {
    private static final String TAG = "NotificationIconArea";

    MoreView mMoreView;
    IconLayout mIconLayout;
    DraggerView mDraggerView;

    public NotificationIconArea(Context context, AttributeSet attrs) {
        super(context, attrs);

        mMoreView = (MoreView) findViewById(R.id.more);
        mIconLayout = (IconLayout)findViewById(R.id.icons);
        mDraggerView = (DraggerView) findViewById(R.id.handle);
    }

    static class MoreView extends ImageView {
        public MoreView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }

    static class IconLayout extends LinearLayout {
        public IconLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }

    static class DraggerView extends View {
        public DraggerView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }
}

