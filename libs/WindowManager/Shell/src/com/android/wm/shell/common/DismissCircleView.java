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

package com.android.wm.shell.common;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.wm.shell.R;

/**
 * Circular view with a semitransparent, circular background with an 'X' inside it.
 *
 * This is used by both Bubbles and PIP as the dismiss target.
 */
public class DismissCircleView extends FrameLayout {

    private final ImageView mIconView = new ImageView(getContext());

    public DismissCircleView(Context context) {
        super(context);
        final Resources res = getResources();

        setBackground(res.getDrawable(R.drawable.dismiss_circle_background));

        mIconView.setImageDrawable(res.getDrawable(R.drawable.pip_ic_close_white));
        addView(mIconView);

        setViewSizes();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setViewSizes();
    }

    /** Retrieves the current dimensions for the icon and circle and applies them. */
    private void setViewSizes() {
        final Resources res = getResources();
        final int iconSize = res.getDimensionPixelSize(R.dimen.dismiss_target_x_size);
        mIconView.setLayoutParams(
                new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
    }
}
