/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;

/**
 * {@link DreamOverlayStatusBarView} is the view responsible for displaying the status bar in a
 * dream. The status bar displays conditional status icons such as "priority mode" and "no wifi".
 */
public class DreamOverlayStatusBarView extends ConstraintLayout {

    private ImageView mWifiStatusView;

    public DreamOverlayStatusBarView(Context context) {
        this(context, null);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DreamOverlayStatusBarView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mWifiStatusView = Preconditions.checkNotNull(findViewById(R.id.dream_overlay_wifi_status),
                "R.id.dream_overlay_wifi_status must not be null");
    }

    /**
     * Whether to show the wifi status icon.
     * @param show True if the wifi status icon should be shown.
     */
    void showWifiStatus(boolean show) {
        // Only show the wifi status icon when wifi isn't available.
        mWifiStatusView.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
