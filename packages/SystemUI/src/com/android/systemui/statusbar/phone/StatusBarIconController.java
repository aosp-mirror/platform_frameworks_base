/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_NONE;

import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_ICON;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_MOBILE;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_NETWORK_TRAFFIC;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_WIFI;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;

import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusBarMobileView;
import com.android.systemui.statusbar.StatusBarWifiView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.WifiIconState;
import com.android.systemui.statusbar.policy.NetworkTrafficSB;
import com.android.systemui.util.Utils.DisableStateTracker;

import java.util.List;

public interface StatusBarIconController {

    /**
     * When an icon is added with TAG_PRIMARY, it will be treated as the primary icon
     * in that slot and not added as a sub slot.
     */
    public static final int TAG_PRIMARY = 0;

    public void addIconGroup(IconManager iconManager);
    public void removeIconGroup(IconManager iconManager);
    public void setExternalIcon(String slot);
    public void setIcon(String slot, int resourceId, CharSequence contentDescription);
    public void setIcon(String slot, StatusBarIcon icon);
    public void setSignalIcon(String slot, WifiIconState state);
    public void setMobileIcons(String slot, List<MobileIconState> states);
    public void setIconVisibility(String slot, boolean b);
    /**
     * If you don't know what to pass for `tag`, either remove all icons for slot, or use
     * TAG_PRIMARY to refer to the first icon at a given slot.
     */
    public void removeIcon(String slot, int tag);
    public void removeAllIconsForSlot(String slot);

    public static final String ICON_BLACKLIST = "icon_blacklist";

    public static ArraySet<String> getIconBlacklist(String blackListStr) {
        ArraySet<String> ret = new ArraySet<>();
        if (blackListStr == null) {
            blackListStr = "rotate,headset";
        }
        String[] blacklist = blackListStr.split(",");
        for (String slot : blacklist) {
            if (!TextUtils.isEmpty(slot)) {
                ret.add(slot);
            }
        }
        return ret;
    }

    /**
     * Version of ViewGroup that observes state from the DarkIconDispatcher.
     */
    public static class DarkIconManager extends IconManager {
        private final DarkIconDispatcher mDarkIconDispatcher;
        private int mIconHPadding;

        public DarkIconManager(LinearLayout linearLayout) {
            super(linearLayout);
            mIconHPadding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.status_bar_icon_padding);
            mDarkIconDispatcher = Dependency.get(DarkIconDispatcher.class);
        }

        @Override
        protected void onIconAdded(int index, String slot, boolean blocked,
                StatusBarIconHolder holder) {
            StatusIconDisplayable view = addHolder(index, slot, blocked, holder);
            mDarkIconDispatcher.addDarkReceiver((DarkReceiver) view);
        }

        @Override
        protected LayoutParams onCreateLayoutParams() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
            lp.setMargins(mIconHPadding, 0, mIconHPadding, 0);
            return lp;
        }

        @Override
        protected void destroy() {
            for (int i = 0; i < mGroup.getChildCount(); i++) {
                mDarkIconDispatcher.removeDarkReceiver((DarkReceiver) mGroup.getChildAt(i));
            }
            mGroup.removeAllViews();
        }

        @Override
        protected void onRemoveIcon(int viewIndex) {
            mDarkIconDispatcher.removeDarkReceiver((DarkReceiver) mGroup.getChildAt(viewIndex));
            super.onRemoveIcon(viewIndex);
        }

        @Override
        public void onSetIcon(int viewIndex, StatusBarIcon icon) {
            super.onSetIcon(viewIndex, icon);
            mDarkIconDispatcher.applyDark((DarkReceiver) mGroup.getChildAt(viewIndex));
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
    }

    public static class TintedIconManager extends IconManager {
        private int mColor;

        public TintedIconManager(ViewGroup group) {
            super(group);
        }

        @Override
        protected void onIconAdded(int index, String slot, boolean blocked,
                StatusBarIconHolder holder) {
            StatusIconDisplayable view = addHolder(index, slot, blocked, holder);
            view.setStaticDrawableColor(mColor);
            view.setDecorColor(mColor);
        }

        public void setTint(int color) {
            mColor = color;
            for (int i = 0; i < mGroup.getChildCount(); i++) {
                View child = mGroup.getChildAt(i);
                if (child instanceof StatusIconDisplayable) {
                    StatusIconDisplayable icon = (StatusIconDisplayable) child;
                    icon.setStaticDrawableColor(mColor);
                    icon.setDecorColor(mColor);
                }
            }
        }

        @Override
        protected DemoStatusIcons createDemoStatusIcons() {
            DemoStatusIcons icons = super.createDemoStatusIcons();
            icons.setColor(mColor);
            return icons;
        }
    }

    /**
     * Turns info from StatusBarIconController into ImageViews in a ViewGroup.
     */
    public static class IconManager implements DemoMode {
        protected final ViewGroup mGroup;
        protected final Context mContext;
        protected final int mIconSize;
        // Whether or not these icons show up in dumpsys
        protected boolean mShouldLog = false;

        // Enables SystemUI demo mode to take effect in this group
        protected boolean mDemoable = true;
        private boolean mIsInDemoMode;
        protected DemoStatusIcons mDemoStatusIcons;

        public IconManager(ViewGroup group) {
            mGroup = group;
            mContext = group.getContext();
            mIconSize = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_icon_size);

            DisableStateTracker tracker =
                    new DisableStateTracker(DISABLE_NONE, DISABLE2_SYSTEM_ICONS);
            mGroup.addOnAttachStateChangeListener(tracker);
            if (mGroup.isAttachedToWindow()) {
                // In case we miss the first onAttachedToWindow event
                tracker.onViewAttachedToWindow(mGroup);
            }
        }

        public boolean isDemoable() {
            return mDemoable;
        }

        public void setIsDemoable(boolean demoable) {
            mDemoable = demoable;
        }

        public void setShouldLog(boolean should) {
            mShouldLog = should;
        }

        public boolean shouldLog() {
            return mShouldLog;
        }

        protected void onIconAdded(int index, String slot, boolean blocked,
                StatusBarIconHolder holder) {
            addHolder(index, slot, blocked, holder);
        }

        protected StatusIconDisplayable addHolder(int index, String slot, boolean blocked,
                StatusBarIconHolder holder) {
            switch (holder.getType()) {
                case TYPE_ICON:
                    return addIcon(index, slot, blocked, holder.getIcon());

                case TYPE_WIFI:
                    return addSignalIcon(index, slot, holder.getWifiState());

                case TYPE_MOBILE:
                    return addMobileIcon(index, slot, holder.getMobileState());

                case TYPE_NETWORK_TRAFFIC:
                    return addNetworkTraffic(index, slot);
            }

            return null;
        }

        @VisibleForTesting
        protected StatusBarIconView addIcon(int index, String slot, boolean blocked,
                StatusBarIcon icon) {
            StatusBarIconView view = onCreateStatusBarIconView(slot, blocked);
            view.set(icon);
            mGroup.addView(view, index, onCreateLayoutParams());
            return view;
        }

        @VisibleForTesting
        protected StatusBarWifiView addSignalIcon(int index, String slot, WifiIconState state) {
            StatusBarWifiView view = onCreateStatusBarWifiView(slot);
            view.applyWifiState(state);
            mGroup.addView(view, index, onCreateLayoutParams());

            if (mIsInDemoMode) {
                mDemoStatusIcons.addDemoWifiView(state);
            }
            return view;
        }

        protected NetworkTrafficSB addNetworkTraffic(int index, String slot) {
            NetworkTrafficSB view = onCreateNetworkTraffic(slot);
            mGroup.addView(view, index, onCreateLayoutParams());
            return view;
        }

        @VisibleForTesting
        protected StatusBarMobileView addMobileIcon(int index, String slot, MobileIconState state) {
            StatusBarMobileView view = onCreateStatusBarMobileView(slot);
            view.applyMobileState(state);
            mGroup.addView(view, index, onCreateLayoutParams());

            if (mIsInDemoMode) {
                mDemoStatusIcons.addMobileView(state);
            }
            return view;
        }

        private StatusBarIconView onCreateStatusBarIconView(String slot, boolean blocked) {
            return new StatusBarIconView(mContext, slot, null, blocked);
        }

        private StatusBarWifiView onCreateStatusBarWifiView(String slot) {
            StatusBarWifiView view = StatusBarWifiView.fromContext(mContext, slot);
            return view;
        }

        private StatusBarMobileView onCreateStatusBarMobileView(String slot) {
            StatusBarMobileView view = StatusBarMobileView.fromContext(mContext, slot);
            return view;
        }

        private NetworkTrafficSB onCreateNetworkTraffic(String slot) {
            NetworkTrafficSB view = new NetworkTrafficSB(mContext);
            view.setPadding(2, 0, 2, 0);
            view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            return view;
        }

        protected LinearLayout.LayoutParams onCreateLayoutParams() {
            return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
        }

        protected void destroy() {
            mGroup.removeAllViews();
        }

        protected void onIconExternal(int viewIndex, int height) {
            ImageView imageView = (ImageView) mGroup.getChildAt(viewIndex);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setAdjustViewBounds(true);
            setHeightAndCenter(imageView, height);
        }

        protected void onDensityOrFontScaleChanged() {
            for (int i = 0; i < mGroup.getChildCount(); i++) {
                View child = mGroup.getChildAt(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
                child.setLayoutParams(lp);
            }
        }

        private void setHeightAndCenter(ImageView imageView, int height) {
            ViewGroup.LayoutParams params = imageView.getLayoutParams();
            params.height = height;
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).gravity = Gravity.CENTER_VERTICAL;
            }
            imageView.setLayoutParams(params);
        }

        protected void onRemoveIcon(int viewIndex) {
            if (mIsInDemoMode) {
                mDemoStatusIcons.onRemoveIcon((StatusIconDisplayable) mGroup.getChildAt(viewIndex));
            }
            mGroup.removeViewAt(viewIndex);
        }

        public void onSetIcon(int viewIndex, StatusBarIcon icon) {
            StatusBarIconView view = (StatusBarIconView) mGroup.getChildAt(viewIndex);
            view.set(icon);
        }

        public void onSetIconHolder(int viewIndex, StatusBarIconHolder holder) {
            switch (holder.getType()) {
                case TYPE_ICON:
                    onSetIcon(viewIndex, holder.getIcon());
                    return;
                case TYPE_WIFI:
                    onSetSignalIcon(viewIndex, holder.getWifiState());
                    return;

                case TYPE_MOBILE:
                    onSetMobileIcon(viewIndex, holder.getMobileState());
                default:
                    break;
            }
        }

        public void onSetSignalIcon(int viewIndex, WifiIconState state) {
            StatusBarWifiView wifiView = (StatusBarWifiView) mGroup.getChildAt(viewIndex);
            if (wifiView != null) {
                wifiView.applyWifiState(state);
            }

            if (mIsInDemoMode) {
                mDemoStatusIcons.updateWifiState(state);
            }
        }

        public void onSetMobileIcon(int viewIndex, MobileIconState state) {
            StatusBarMobileView view = (StatusBarMobileView) mGroup.getChildAt(viewIndex);
            if (view != null) {
                view.applyMobileState(state);
            }

            if (mIsInDemoMode) {
                mDemoStatusIcons.updateMobileState(state);
            }
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) {
            if (!mDemoable) {
                return;
            }

            if (command.equals(COMMAND_EXIT)) {
                if (mDemoStatusIcons != null) {
                    mDemoStatusIcons.dispatchDemoCommand(command, args);
                    exitDemoMode();
                }
                mIsInDemoMode = false;
            } else {
                if (mDemoStatusIcons == null) {
                    mIsInDemoMode = true;
                    mDemoStatusIcons = createDemoStatusIcons();
                }
                mDemoStatusIcons.dispatchDemoCommand(command, args);
            }
        }

        protected void exitDemoMode() {
            mDemoStatusIcons.remove();
            mDemoStatusIcons = null;
        }

        protected DemoStatusIcons createDemoStatusIcons() {
            return new DemoStatusIcons((LinearLayout) mGroup, mIconSize);
        }
    }
}
