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

import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.DemoStatusIcons;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;

import javax.inject.Inject;

/**
 * Version of {@link IconManager} that observes state from the DarkIconDispatcher.
 */
public class DarkIconManager extends IconManager {
    private final DarkIconDispatcher mDarkIconDispatcher;
    private final int mIconHorizontalMargin;

    public DarkIconManager(
            LinearLayout linearLayout,
            StatusBarLocation location,
            WifiUiAdapter wifiUiAdapter,
            MobileUiAdapter mobileUiAdapter,
            MobileContextProvider mobileContextProvider,
            DarkIconDispatcher darkIconDispatcher) {
        super(linearLayout,
                location,
                wifiUiAdapter,
                mobileUiAdapter,
                mobileContextProvider);
        mIconHorizontalMargin = mContext.getResources().getDimensionPixelSize(
                com.android.systemui.res.R.dimen.status_bar_icon_horizontal_margin);
        mDarkIconDispatcher = darkIconDispatcher;
    }

    @Override
    protected void onIconAdded(int index, String slot, boolean blocked,
            StatusBarIconHolder holder) {
        StatusIconDisplayable view = addHolder(index, slot, blocked, holder);
        mDarkIconDispatcher.addDarkReceiver(view);
    }

    @Override
    protected LinearLayout.LayoutParams onCreateLayoutParams(StatusBarIcon.Shape shape) {
        LinearLayout.LayoutParams lp = super.onCreateLayoutParams(shape);
        lp.setMargins(mIconHorizontalMargin, 0, mIconHorizontalMargin, 0);
        return lp;
    }

    @Override
    protected void destroy() {
        for (int i = 0; i < mGroup.getChildCount(); i++) {
            mDarkIconDispatcher.removeDarkReceiver(
                    (DarkIconDispatcher.DarkReceiver) mGroup.getChildAt(i));
        }
        mGroup.removeAllViews();
    }

    @Override
    protected void onRemoveIcon(int viewIndex) {
        mDarkIconDispatcher.removeDarkReceiver(
                (DarkIconDispatcher.DarkReceiver) mGroup.getChildAt(viewIndex));
        super.onRemoveIcon(viewIndex);
    }

    @Override
    public void onSetIcon(int viewIndex, StatusBarIcon icon) {
        super.onSetIcon(viewIndex, icon);
        mDarkIconDispatcher.applyDark(
                (DarkIconDispatcher.DarkReceiver) mGroup.getChildAt(viewIndex));
    }

    @Override
    protected DemoStatusIcons createDemoStatusIcons() {
        DemoStatusIcons icons = super.createDemoStatusIcons();
        mDarkIconDispatcher.addDarkReceiver(icons);

        return icons;
    }

    @Override
    protected void exitDemoMode() {
        mDarkIconDispatcher.removeDarkReceiver(mDemoStatusIcons);
        super.exitDemoMode();
    }

    @SysUISingleton
    public static class Factory {
        private final WifiUiAdapter mWifiUiAdapter;
        private final MobileContextProvider mMobileContextProvider;
        private final MobileUiAdapter mMobileUiAdapter;
        private final DarkIconDispatcher mDarkIconDispatcher;

        @Inject
        public Factory(
                WifiUiAdapter wifiUiAdapter,
                MobileContextProvider mobileContextProvider,
                MobileUiAdapter mobileUiAdapter,
                DarkIconDispatcher darkIconDispatcher) {
            mWifiUiAdapter = wifiUiAdapter;
            mMobileContextProvider = mobileContextProvider;
            mMobileUiAdapter = mobileUiAdapter;
            mDarkIconDispatcher = darkIconDispatcher;
        }

        /** Creates a new {@link DarkIconManager} for the given view group and location. */
        public DarkIconManager create(LinearLayout group, StatusBarLocation location) {
            return new DarkIconManager(
                    group,
                    location,
                    mWifiUiAdapter,
                    mMobileUiAdapter,
                    mMobileContextProvider,
                    mDarkIconDispatcher);
        }
    }
}
