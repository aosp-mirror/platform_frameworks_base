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

package com.android.systemui.statusbar.phone.ui;

import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.DemoStatusIcons;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;

import javax.inject.Inject;

/**
 * Version of {@link IconManager} that can tint the icons to a particular color.
 */
public class TintedIconManager extends IconManager {
    // The main tint, used as the foreground in non layer drawables
    private int mColor;
    // To be used as the main tint in drawables that wish to have a layer
    private int mForegroundColor;

    public TintedIconManager(
            ViewGroup group,
            StatusBarLocation location,
            WifiUiAdapter wifiUiAdapter,
            MobileUiAdapter mobileUiAdapter,
            MobileContextProvider mobileContextProvider
    ) {
        super(group,
                location,
                wifiUiAdapter,
                mobileUiAdapter,
                mobileContextProvider);
    }

    @Override
    protected void onIconAdded(int index, String slot, boolean blocked,
            StatusBarIconHolder holder) {
        StatusIconDisplayable view = addHolder(index, slot, blocked, holder);
        view.setStaticDrawableColor(mColor, mForegroundColor);
        view.setDecorColor(mColor);
    }

    /**
     * Most icons are a single layer, and tintColor will be used as the tint in those cases.
     * For icons that have a background, foregroundColor becomes the contrasting tint used
     * for the foreground.
     *
     * @param tintColor       the main tint to use for the icons in the group
     * @param foregroundColor used as the main tint for layer-ish drawables where tintColor is
     *                        being used as the background
     */
    public void setTint(int tintColor, int foregroundColor) {
        mColor = tintColor;
        mForegroundColor = foregroundColor;

        for (int i = 0; i < mGroup.getChildCount(); i++) {
            View child = mGroup.getChildAt(i);
            if (child instanceof StatusIconDisplayable icon) {
                icon.setStaticDrawableColor(mColor, mForegroundColor);
                icon.setDecorColor(mColor);
            }
        }

        if (mDemoStatusIcons != null) {
            mDemoStatusIcons.setColor(tintColor, foregroundColor);
        }
    }

    @Override
    protected DemoStatusIcons createDemoStatusIcons() {
        DemoStatusIcons icons = super.createDemoStatusIcons();
        icons.setColor(mColor, mForegroundColor);
        return icons;
    }

    @SysUISingleton
    public static class Factory {
        private final WifiUiAdapter mWifiUiAdapter;
        private final MobileContextProvider mMobileContextProvider;
        private final MobileUiAdapter mMobileUiAdapter;

        @Inject
        public Factory(
                WifiUiAdapter wifiUiAdapter,
                MobileUiAdapter mobileUiAdapter,
                MobileContextProvider mobileContextProvider
        ) {
            mWifiUiAdapter = wifiUiAdapter;
            mMobileUiAdapter = mobileUiAdapter;
            mMobileContextProvider = mobileContextProvider;
        }

        /** Creates a new {@link TintedIconManager} for the given view group and location. */
        public TintedIconManager create(ViewGroup group, StatusBarLocation location) {
            return new TintedIconManager(
                    group,
                    location,
                    mWifiUiAdapter,
                    mMobileUiAdapter,
                    mMobileContextProvider);
        }
    }
}
