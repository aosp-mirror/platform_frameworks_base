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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.systemui.R;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout {

    private boolean mExpanded;
    private View mBackground;
    private View mFlipper;

    private int mCollapsedHeight;
    private int mExpandedHeight;

    public StatusBarHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = findViewById(R.id.background);
        mFlipper = findViewById(R.id.header_flipper);
        loadDimens();
    }

    private void loadDimens() {
        mCollapsedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height);
        mExpandedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_expanded);
    }

    public int getCollapsedHeight() {
        return mCollapsedHeight;
    }

    public int getExpandedHeight() {
        return mExpandedHeight;
    }

    public void setExpanded(boolean expanded) {
        if (expanded != mExpanded) {
            ViewGroup.LayoutParams lp = getLayoutParams();
            lp.height = expanded ? mExpandedHeight : mCollapsedHeight;
            setLayoutParams(lp);
            mExpanded = expanded;
        }
    }

    public void setExpansionEnabled(boolean enabled) {
        mFlipper.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    public void setExpansion(float height) {
        if (height < mCollapsedHeight) {
            height = mCollapsedHeight;
        }
        if (height > mExpandedHeight) {
            height = mExpandedHeight;
        }
        if (mExpanded) {
            mBackground.setTranslationY(-(mExpandedHeight - height));
        } else {
            mBackground.setTranslationY(0);
        }
    }

    public View getBackgroundView() {
        return mBackground;
    }
}
