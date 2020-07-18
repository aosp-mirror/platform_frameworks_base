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

package com.android.systemui.pip.phone;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import javax.inject.Inject;

/**
 * Helper class to calculate and place the menu icons on the PIP Menu.
 */
public class PipMenuIconsAlgorithm {

    private static final String TAG = "PipMenuIconsAlgorithm";

    private boolean mFinishedLayout = false;
    protected ViewGroup mViewRoot;
    protected ViewGroup mTopEndContainer;
    protected View mDragHandle;
    protected View mSettingsButton;
    protected View mDismissButton;

    @Inject
    public PipMenuIconsAlgorithm(Context context) {
    }

    /**
     * Bind the necessary views.
     */
    public void bindViews(ViewGroup viewRoot, ViewGroup topEndContainer, View dragHandle,
            View settingsButton, View dismissButton) {
        mViewRoot = viewRoot;
        mTopEndContainer = topEndContainer;
        mDragHandle = dragHandle;
        mSettingsButton = settingsButton;
        mDismissButton = dismissButton;
    }


    /**
     * Updates the position of the drag handle based on where the PIP window is on the screen.
     */
    public void onBoundsChanged(Rect bounds) {
        if (mViewRoot == null || mTopEndContainer == null || mDragHandle == null
                || mSettingsButton == null || mDismissButton == null) {
            Log.e(TAG, "One if the required views is null.");
        }

        //We only need to calculate the layout once since it does not change.
        if (!mFinishedLayout) {
            mTopEndContainer.removeView(mSettingsButton);
            mViewRoot.addView(mSettingsButton);

            setLayoutGravity(mDragHandle, Gravity.START | Gravity.TOP);
            setLayoutGravity(mSettingsButton, Gravity.START | Gravity.TOP);
            mFinishedLayout = true;
        }
    }

    /**
     * Set the gravity on the given view.
     */
    protected static void setLayoutGravity(View v, int gravity) {
        if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
            params.gravity = gravity;
            v.setLayoutParams(params);
        }
    }
}
