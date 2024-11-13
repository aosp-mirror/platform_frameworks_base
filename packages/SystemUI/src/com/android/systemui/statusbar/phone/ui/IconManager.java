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

import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_BINDABLE;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_ICON;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_MOBILE_NEW;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_WIFI_NEW;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIcon.Shape;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.modes.shared.ModesUiIcons;
import com.android.systemui.statusbar.BaseStatusBarFrameLayout;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.DemoStatusIcons;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarIconHolder.BindableIconHolder;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconsBinder;
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel;
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;
import com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView;
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel;
import com.android.systemui.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns info from StatusBarIconController into ImageViews in a ViewGroup.
 */
public class IconManager implements DemoModeCommandReceiver {
    protected final ViewGroup mGroup;
    private final MobileContextProvider mMobileContextProvider;
    private final LocationBasedWifiViewModel mWifiViewModel;
    private final MobileIconsViewModel mMobileIconsViewModel;

    /**
     * Stores the list of bindable icons that have been added, keyed on slot name. This ensures
     * we don't accidentally add the same bindable icon twice.
     */
    private final Map<String, BindableIconHolder> mBindableIcons = new HashMap<>();
    protected final Context mContext;
    protected int mIconSize;
    // Whether or not these icons show up in dumpsys
    protected boolean mShouldLog = false;
    private StatusBarIconController mController;
    private final StatusBarLocation mLocation;

    // Enables SystemUI demo mode to take effect in this group
    protected boolean mDemoable = true;
    private boolean mIsInDemoMode;
    protected DemoStatusIcons mDemoStatusIcons;

    protected ArrayList<String> mBlockList = new ArrayList<>();

    public IconManager(
            ViewGroup group,
            StatusBarLocation location,
            WifiUiAdapter wifiUiAdapter,
            MobileUiAdapter mobileUiAdapter,
            MobileContextProvider mobileContextProvider
    ) {
        mGroup = group;
        mMobileContextProvider = mobileContextProvider;
        mContext = group.getContext();
        mLocation = location;

        reloadDimens();

        // This starts the flow for the new pipeline, and will notify us of changes via
        // {@link #setNewMobileIconIds}
        mMobileIconsViewModel = mobileUiAdapter.getMobileIconsViewModel();
        MobileIconsBinder.bind(mGroup, mMobileIconsViewModel);

        mWifiViewModel = wifiUiAdapter.bindGroup(mGroup, mLocation);
    }

    public boolean isDemoable() {
        return mDemoable;
    }

    void setController(StatusBarIconController controller) {
        mController = controller;
    }

    /** Sets the list of slots that should be blocked from showing in the status bar. */
    public void setBlockList(@Nullable List<String> blockList) {
        Assert.isMainThread();
        mBlockList.clear();
        mBlockList.addAll(blockList);
        if (mController != null) {
            mController.refreshIconGroup(this);
        }
    }

    /** Sets whether this manager's changes should be dumped in a bug report. */
    public void setShouldLog(boolean should) {
        mShouldLog = should;
    }

    /** Returns true if this manager's changes should be dumped in a bug report. */
    public boolean shouldLog() {
        return mShouldLog;
    }

    protected void onIconAdded(int index, String slot, boolean blocked,
            StatusBarIconHolder holder) {
        addHolder(index, slot, blocked, holder);
    }

    protected StatusIconDisplayable addHolder(int index, String slot, boolean blocked,
            StatusBarIconHolder holder) {
        // This is a little hacky, and probably regrettable, but just set `blocked` on any icon
        // that is in our blocked list, then we'll never see it
        if (mBlockList.contains(slot)) {
            blocked = true;
        }
        return switch (holder.getType()) {
            case TYPE_ICON -> addIcon(index, slot, blocked, holder.getIcon());
            case TYPE_WIFI_NEW -> addNewWifiIcon(index, slot);
            case TYPE_MOBILE_NEW -> addNewMobileIcon(index, slot, holder.getTag());
            case TYPE_BINDABLE ->
                // Safe cast, since only BindableIconHolders can set this tag on themselves
                addBindableIcon((BindableIconHolder) holder, index);
            default -> null;
        };
    }

    protected StatusBarIconView addIcon(int index, String slot, boolean blocked,
            StatusBarIcon icon) {
        StatusBarIconView view = onCreateStatusBarIconView(slot, blocked);
        view.set(icon);
        mGroup.addView(view, index, onCreateLayoutParams(icon.shape));
        return view;
    }

    /**
     * ModernStatusBarViews can be created and bound, and thus do not need to update their
     * drawable by sending multiple calls to setIcon. Instead, by using a bindable
     * icon view, we can simply create the icon when requested and allow the
     * ViewBinder to control its visual state.
     */
    protected StatusIconDisplayable addBindableIcon(BindableIconHolder holder,
            int index) {
        mBindableIcons.put(holder.getSlot(), holder);
        ModernStatusBarView view = holder.getInitializer().createAndBind(mContext);
        mGroup.addView(view, index, onCreateLayoutParams(Shape.WRAP_CONTENT));
        if (mIsInDemoMode) {
            mDemoStatusIcons.addBindableIcon(holder);
        }
        return view;
    }

    protected StatusIconDisplayable addNewWifiIcon(int index, String slot) {
        ModernStatusBarWifiView view = onCreateModernStatusBarWifiView(slot);
        mGroup.addView(view, index, onCreateLayoutParams(Shape.WRAP_CONTENT));

        if (mIsInDemoMode) {
            mDemoStatusIcons.addModernWifiView(mWifiViewModel);
        }

        return view;
    }


    protected StatusIconDisplayable addNewMobileIcon(
            int index,
            String slot,
            int subId
    ) {
        BaseStatusBarFrameLayout view = onCreateModernStatusBarMobileView(slot, subId);
        mGroup.addView(view, index, onCreateLayoutParams(Shape.WRAP_CONTENT));

        if (mIsInDemoMode) {
            Context mobileContext = mMobileContextProvider
                    .getMobileContextForSub(subId, mContext);
            mDemoStatusIcons.addModernMobileView(
                    mobileContext,
                    mMobileIconsViewModel.getLogger(),
                    subId);
        }

        return view;
    }

    private StatusBarIconView onCreateStatusBarIconView(String slot, boolean blocked) {
        return new StatusBarIconView(mContext, slot, null, blocked);
    }

    private ModernStatusBarWifiView onCreateModernStatusBarWifiView(String slot) {
        return ModernStatusBarWifiView.constructAndBind(mContext, slot, mWifiViewModel);
    }

    private ModernStatusBarMobileView onCreateModernStatusBarMobileView(
            String slot, int subId) {
        Context mobileContext = mMobileContextProvider.getMobileContextForSub(subId, mContext);
        return ModernStatusBarMobileView
                .constructAndBind(
                        mobileContext,
                        mMobileIconsViewModel.getLogger(),
                        slot,
                        mMobileIconsViewModel.viewModelForSub(subId, mLocation)
                );
    }

    protected LinearLayout.LayoutParams onCreateLayoutParams(Shape shape) {
        int width = ModesUiIcons.isEnabled() && shape == StatusBarIcon.Shape.FIXED_SPACE
                ? mIconSize
                : ViewGroup.LayoutParams.WRAP_CONTENT;

        return new LinearLayout.LayoutParams(width, mIconSize);
    }

    protected void destroy() {
        mGroup.removeAllViews();
    }

    protected void reloadDimens() {
        mIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size_sp);
    }

    protected void onRemoveIcon(int viewIndex) {
        if (mIsInDemoMode) {
            mDemoStatusIcons.onRemoveIcon((StatusIconDisplayable) mGroup.getChildAt(viewIndex));
        }
        mGroup.removeViewAt(viewIndex);
    }

    /** Called once an icon has been set. */
    public void onSetIcon(int viewIndex, StatusBarIcon icon) {
        StatusBarIconView view = (StatusBarIconView) mGroup.getChildAt(viewIndex);
        if (ModesUiIcons.isEnabled()) {
            ViewGroup.LayoutParams current = view.getLayoutParams();
            ViewGroup.LayoutParams desired = onCreateLayoutParams(icon.shape);
            if (desired.width != current.width || desired.height != current.height) {
                view.setLayoutParams(desired);
            }
        }
        view.set(icon);
    }

    /** Called once an icon holder has been set. */
    public void onSetIconHolder(int viewIndex, StatusBarIconHolder holder) {
        switch (holder.getType()) {
            case TYPE_ICON:
                onSetIcon(viewIndex, holder.getIcon());
                return;
            case TYPE_MOBILE_NEW:
            case TYPE_WIFI_NEW:
            case TYPE_BINDABLE:
                // Nothing, the new icons update themselves
                return;
            default:
                break;
        }
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoable) {
            return;
        }

        mDemoStatusIcons.dispatchDemoCommand(command, args);
    }

    @Override
    public void onDemoModeStarted() {
        mIsInDemoMode = true;
        if (mDemoStatusIcons == null) {
            mDemoStatusIcons = createDemoStatusIcons();
            mDemoStatusIcons.addModernWifiView(mWifiViewModel);
            for (BindableIconHolder holder : mBindableIcons.values()) {
                mDemoStatusIcons.addBindableIcon(holder);
            }
        }
        mDemoStatusIcons.onDemoModeStarted();
    }

    @Override
    public void onDemoModeFinished() {
        if (mDemoStatusIcons != null) {
            mDemoStatusIcons.onDemoModeFinished();
            exitDemoMode();
            mIsInDemoMode = false;
        }
    }

    protected void exitDemoMode() {
        mDemoStatusIcons.remove();
        mDemoStatusIcons = null;
    }

    protected DemoStatusIcons createDemoStatusIcons() {
        return new DemoStatusIcons(
                (LinearLayout) mGroup,
                mMobileIconsViewModel,
                mLocation,
                mIconSize
        );
    }
}
