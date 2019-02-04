/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R;

public class NavigationBarEdgePanel extends View {
    private static final String TAG = "NavigationBarEdgePanel";

    public static NavigationBarEdgePanel create(@NonNull Context context, int width, int height,
            int gravity) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.setTitle(TAG + context.getDisplayId());
        lp.accessibilityTitle = context.getString(R.string.nav_bar_edge_panel);
        lp.windowAnimations = 0;
        NavigationBarEdgePanel panel = new NavigationBarEdgePanel(context);
        panel.setLayoutParams(lp);
        return panel;
    }

    private NavigationBarEdgePanel(Context context) {
        super(context);
    }

    public void setWindowFlag(int flags, boolean enable) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp == null || enable == ((lp.flags & flags) != 0)) {
            return;
        }
        if (enable) {
            lp.flags |= flags;
        } else {
            lp.flags &= ~flags;
        }
        updateLayout(lp);
    }

    public void setDimensions(int width, int height) {
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            updateLayout(lp);
        }
    }

    private void updateLayout(WindowManager.LayoutParams lp) {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.updateViewLayout(this, lp);
    }
}
