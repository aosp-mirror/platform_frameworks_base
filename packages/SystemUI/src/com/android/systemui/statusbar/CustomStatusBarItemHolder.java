/**
 * Copyright (C) 2018 The LineageOS project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.widget.RelativeLayout;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.R;

import java.util.ArrayList;

public class CustomStatusBarItemHolder extends RelativeLayout
        implements CustomStatusBarItem.Manager {
    private static final String TAG = "CustomStatusBarItemHolder";

    private ArrayList<CustomStatusBarItem.DarkReceiver> mDarkReceivers =
            new ArrayList<CustomStatusBarItem.DarkReceiver>();
    private ArrayList<CustomStatusBarItem.VisibilityReceiver> mVisibilityReceivers =
            new ArrayList<CustomStatusBarItem.VisibilityReceiver>();

    private Rect mLastArea;
    private float mLastDarkIntensity;
    private int mLastTint;

    private boolean mItemHolderIsVisible;

    private Context mContext;

    public CustomStatusBarItemHolder(Context context) {
        this(context, null);
    }

    public CustomStatusBarItemHolder(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomStatusBarItemHolder(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        mItemHolderIsVisible = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        setOnSystemUiVisibilityChangeListener(mSystemUiVisibilityChangeListener);
        updateStatusBarVisibility(getSystemUiVisibility());

        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mDarkReceiver);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setOnSystemUiVisibilityChangeListener(null);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mDarkReceiver);
    }

    // Propagate systemui tint updates to registered receivers.

    private DarkReceiver mDarkReceiver = new DarkReceiver() {
        @Override
        public void onDarkChanged(Rect area, float darkIntensity, int tint) {
            mLastArea = area;
            mLastDarkIntensity = darkIntensity;
            mLastTint = tint;
            for (CustomStatusBarItem.DarkReceiver r : mDarkReceivers) {
                r.onDarkChanged(area, darkIntensity, tint);
            }
        }
    };

    // Collect and propagate item holder visibility to
    // registered receivers.
    //
    // We watch both our own view visibility and systemui visibility.
    // Latest change in either direction wins (and has been observed
    // thus far to always be correct).

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateVisibilityReceivers(isVisible);
    }

    private View.OnSystemUiVisibilityChangeListener mSystemUiVisibilityChangeListener =
            new View.OnSystemUiVisibilityChangeListener() {
        @Override
        public void onSystemUiVisibilityChange(int visibility) {
            updateStatusBarVisibility(visibility);
        }
    };

    private void updateStatusBarVisibility(int visibility) {
        final boolean isVisible =
                (visibility & SYSTEM_UI_FLAG_FULLSCREEN) == 0
                || (visibility & SYSTEM_UI_FLAG_LOW_PROFILE) != 0;
        updateVisibilityReceivers(isVisible);
    }

    private void updateVisibilityReceivers(boolean isVisible) {
        if (isVisible == mItemHolderIsVisible) {
            return;
        }
        mItemHolderIsVisible = isVisible;
        for (CustomStatusBarItem.VisibilityReceiver r : mVisibilityReceivers) {
            r.onVisibilityChanged(mItemHolderIsVisible);
        }
    }

    // CustomStatusBarItem.Manager methods

    public void addDarkReceiver(CustomStatusBarItem.DarkReceiver darkReceiver) {
        darkReceiver.setFillColors(
                mContext.getColor(R.color.dark_mode_icon_color_dual_tone_fill),
                mContext.getColor(R.color.light_mode_icon_color_dual_tone_fill));
        mDarkReceivers.add(darkReceiver);
        darkReceiver.onDarkChanged(mLastArea, mLastDarkIntensity, mLastTint);
    }

    public void addVisibilityReceiver(CustomStatusBarItem.VisibilityReceiver visibilityReceiver) {
        mVisibilityReceivers.add(visibilityReceiver);
        visibilityReceiver.onVisibilityChanged(mItemHolderIsVisible);
    }
}