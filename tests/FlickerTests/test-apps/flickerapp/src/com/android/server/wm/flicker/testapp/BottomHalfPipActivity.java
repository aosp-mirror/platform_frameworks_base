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

package com.android.server.wm.flicker.testapp;

import static com.android.server.wm.flicker.testapp.ActivityOptions.BottomHalfPip.EXTRA_BOTTOM_HALF_LAYOUT;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import androidx.annotation.NonNull;

public class BottomHalfPipActivity extends PipActivity {

    private boolean mUseBottomHalfLayout = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bottom_half_pip);
        setTheme(R.style.TranslucentTheme);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateLayout();
    }

    /**
     * Toggles the layout mode between fill task and half-bottom modes.
     */
    public void toggleBottomHalfLayout(View v) {
        mUseBottomHalfLayout = !mUseBottomHalfLayout;
        updateLayout();
    }

    /**
     * Sets to match parent layout if the activity is
     * {@link Activity#isInPictureInPictureMode()}. Otherwise,
     * follows {@link #mUseBottomHalfLayout}.
     *
     * @see #setToBottomHalfMode(boolean)
     */
    private void updateLayout() {
        final boolean useBottomHalfLayout;
        if (isInPictureInPictureMode()) {
            useBottomHalfLayout = false;
        } else {
            useBottomHalfLayout = mUseBottomHalfLayout;
        }
        setToBottomHalfMode(useBottomHalfLayout);
    }

    /**
     * Sets `useBottomHalfLayout` to `true` to use the bottom half layout. Use the
     * [LayoutParams.MATCH_PARENT] layout.
     */
    private void setToBottomHalfMode(boolean useBottomHalfLayout) {
        final WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.gravity = Gravity.BOTTOM;
        if (useBottomHalfLayout) {
            final int taskHeight = getWindowManager().getCurrentWindowMetrics().getBounds()
                    .height();
            attrs.height = taskHeight / 2;
        } else {
            attrs.height = LayoutParams.MATCH_PARENT;
        }
        getWindow().setAttributes(attrs);
    }

    @Override
    void handleIntentExtra(@NonNull Intent intent) {
        super.handleIntentExtra(intent);
        if (intent.hasExtra(EXTRA_BOTTOM_HALF_LAYOUT)) {
            final String booleanString = intent.getStringExtra(EXTRA_BOTTOM_HALF_LAYOUT);
            // We don't use Boolean#parseBoolean here because the impl only checks if the string
            // equals to "true", and returns for any other cases. We use our own impl here to
            // prevent false positive.
            if ("true".equals(booleanString)) {
                mUseBottomHalfLayout = true;
            } else if ("false".equals(booleanString)) {
                mUseBottomHalfLayout = false;
            }
        }
        updateLayout();
    }
}
