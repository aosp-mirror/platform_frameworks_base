/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import android.content.res.Configuration;
import android.view.LayoutInflater;

import com.android.systemui.R;
import com.android.systemui.SystemUI;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * Controls the docked stack divider.
 */
public class Divider extends SystemUI {
    private static final String TAG = "Divider";
    private int mDividerWindowWidth;
    private DividerWindowManager mWindowManager;

    @Override
    public void start() {
        mWindowManager = new DividerWindowManager(mContext);
        mDividerWindowWidth = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        update(mContext.getResources().getConfiguration());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        update(newConfig);
    }

    private void addDivider(Configuration configuration) {
        DividerView view = (DividerView)
                LayoutInflater.from(mContext).inflate(R.layout.docked_stack_divider, null);
        final boolean landscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        final int width = landscape ? mDividerWindowWidth : MATCH_PARENT;
        final int height = landscape ? MATCH_PARENT : mDividerWindowWidth;
        mWindowManager.add(view, width, height);
        view.setWindowManager(mWindowManager);
    }

    private void removeDivider() {
        mWindowManager.remove();
    }

    private void update(Configuration configuration) {
        removeDivider();
        addDivider(configuration);
    }
}
