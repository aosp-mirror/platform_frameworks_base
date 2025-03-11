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

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import androidx.annotation.NonNull;

public class BottomHalfPipActivity extends PipActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.TranslucentTheme);
        updateLayout();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        updateLayout();
    }

    /**
     * Sets to match parent layout if the activity is
     * {@link Activity#isInPictureInPictureMode()}. Otherwise, set to bottom half
     * layout.
     *
     * @see #setToBottomHalfMode(boolean)
     */
    private void updateLayout() {
        setToBottomHalfMode(!isInPictureInPictureMode());
    }

    /**
     * Sets `useBottomHalfLayout` to `true` to use the bottom half layout. Use the
     * [LayoutParams.MATCH_PARENT] layout.
     */
    private void setToBottomHalfMode(boolean useBottomHalfLayout) {
        final WindowManager.LayoutParams attrs = getWindow().getAttributes();
        if (useBottomHalfLayout) {
            final int taskHeight = getWindowManager().getCurrentWindowMetrics().getBounds()
                    .height();
            attrs.y = taskHeight / 2;
            attrs.height = taskHeight / 2;
        } else {
            attrs.y = 0;
            attrs.height = LayoutParams.MATCH_PARENT;
        }
        getWindow().setAttributes(attrs);
    }
}
