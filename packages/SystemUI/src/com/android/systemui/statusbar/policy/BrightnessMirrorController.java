/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.StatusBarWindowView;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;

/**
 * Controls showing and hiding of the brightness mirror.
 */
public class BrightnessMirrorController {

    public long TRANSITION_DURATION_OUT = 150;
    public long TRANSITION_DURATION_IN = 200;

    private final View mScrimBehind;
    private final View mBrightnessMirror;
    private final View mPanelHolder;
    private final int[] mInt2Cache = new int[2];

    public BrightnessMirrorController(StatusBarWindowView statusBarWindow) {
        mScrimBehind = statusBarWindow.findViewById(R.id.scrim_behind);
        mBrightnessMirror = statusBarWindow.findViewById(R.id.brightness_mirror);
        mPanelHolder = statusBarWindow.findViewById(R.id.panel_holder);
    }

    public void showMirror() {
        mBrightnessMirror.setVisibility(View.VISIBLE);
        outAnimation(mScrimBehind.animate());
        outAnimation(mPanelHolder.animate())
                .withLayer();
    }

    public void hideMirror() {
        inAnimation(mScrimBehind.animate());
        inAnimation(mPanelHolder.animate())
                .withLayer()
                .withEndAction(new Runnable() {
            @Override
            public void run() {
                mBrightnessMirror.setVisibility(View.GONE);
            }
        });
    }

    private ViewPropertyAnimator outAnimation(ViewPropertyAnimator a) {
        return a.alpha(0.0f)
                .setDuration(TRANSITION_DURATION_OUT)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT);
    }
    private ViewPropertyAnimator inAnimation(ViewPropertyAnimator a) {
        return a.alpha(1.0f)
                .setDuration(TRANSITION_DURATION_IN)
                .setInterpolator(PhoneStatusBar.ALPHA_IN);
    }


    public void setLocation(View original) {
        original.getLocationInWindow(mInt2Cache);
        int originalY = mInt2Cache[1];
        mBrightnessMirror.getLocationInWindow(mInt2Cache);
        int mirrorY = mInt2Cache[1];

        mBrightnessMirror.setTranslationY(mBrightnessMirror.getTranslationY()
                + originalY - mirrorY);
    }

    public View getMirror() {
        return mBrightnessMirror;
    }

    public void updateResources() {
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mBrightnessMirror.getLayoutParams();
        lp.width = mBrightnessMirror.getResources().getDimensionPixelSize(
                R.dimen.notification_panel_width);
        lp.gravity = mBrightnessMirror.getResources().getInteger(
                R.integer.notification_panel_layout_gravity);
        mBrightnessMirror.setLayoutParams(lp);

        int padding = mBrightnessMirror.getResources().getDimensionPixelSize(
                R.dimen.notification_side_padding);
        mBrightnessMirror.setPadding(padding, mBrightnessMirror.getPaddingTop(),
                padding, mBrightnessMirror.getPaddingBottom());
    }
}
