/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger;
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel;
import com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView;
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel;

import java.util.ArrayList;
import java.util.List;

//TODO: This should be a controller, not its own view
public class DemoStatusIcons extends StatusIconContainer implements DemoMode, DarkReceiver {
    private static final String TAG = "DemoStatusIcons";

    private final LinearLayout mStatusIcons;
    private final ArrayList<ModernStatusBarMobileView> mModernMobileViews = new ArrayList<>();
    private final int mIconSize;

    private ModernStatusBarWifiView mModernWifiView;
    private boolean mDemoMode;
    private int mColor;
    private int mContrastColor;

    private final MobileIconsViewModel mMobileIconsViewModel;
    private final StatusBarLocation mLocation;

    public DemoStatusIcons(
            LinearLayout statusIcons,
            MobileIconsViewModel mobileIconsViewModel,
            StatusBarLocation location,
            int iconSize
    ) {
        super(statusIcons.getContext());
        mStatusIcons = statusIcons;
        mIconSize = iconSize;
        mColor = DarkIconDispatcher.DEFAULT_ICON_TINT;
        mContrastColor = DarkIconDispatcher.DEFAULT_INVERSE_ICON_TINT;
        mMobileIconsViewModel = mobileIconsViewModel;
        mLocation = location;

        if (statusIcons instanceof StatusIconContainer) {
            setShouldRestrictIcons(((StatusIconContainer) statusIcons).isRestrictingIcons());
        } else {
            setShouldRestrictIcons(false);
        }
        setLayoutParams(mStatusIcons.getLayoutParams());
        setPadding(mStatusIcons.getPaddingLeft(), mStatusIcons.getPaddingTop(),
                mStatusIcons.getPaddingRight(), mStatusIcons.getPaddingBottom());
        setOrientation(mStatusIcons.getOrientation());
        setGravity(Gravity.CENTER_VERTICAL); // no LL.getGravity()
        ViewGroup p = (ViewGroup) mStatusIcons.getParent();
        p.addView(this, p.indexOfChild(mStatusIcons));
    }

    public void remove() {
        ((ViewGroup) getParent()).removeView(this);
    }

    /** Set the tint colors */
    public void setColor(int color, int contrastColor) {
        mColor = color;
        mContrastColor = contrastColor;
        updateColors();
    }

    private void updateColors() {
        for (int i = 0; i < getChildCount(); i++) {
            StatusIconDisplayable child = (StatusIconDisplayable) getChildAt(i);
            child.setStaticDrawableColor(mColor, mContrastColor);
            child.setDecorColor(mColor);
        }
    }

    @Override
    public List<String> demoCommands() {
        List<String> commands = new ArrayList<>();
        commands.add(COMMAND_STATUS);
        return commands;
    }

    @Override
    public void onDemoModeStarted() {
        mDemoMode = true;
        mStatusIcons.setVisibility(View.GONE);
        setVisibility(View.VISIBLE);
    }

    @Override
    public void onDemoModeFinished() {
        mDemoMode = false;
        mStatusIcons.setVisibility(View.VISIBLE);
        mModernMobileViews.clear();
        setVisibility(View.GONE);
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        String volume = args.getString("volume");
        if (volume != null) {
            int iconId = volume.equals("vibrate") ? R.drawable.stat_sys_ringer_vibrate
                    : 0;
            updateSlot("volume", null, iconId);
        }
        String zen = args.getString("zen");
        if (zen != null) {
            int iconId = zen.equals("dnd") ? R.drawable.stat_sys_dnd : 0;
            updateSlot("zen", null, iconId);
        }
        String bt = args.getString("bluetooth");
        if (bt != null) {
            int iconId = bt.equals("connected")
                    ? R.drawable.stat_sys_data_bluetooth_connected : 0;
            updateSlot("bluetooth", null, iconId);
        }
        String location = args.getString("location");
        if (location != null) {
            int iconId = location.equals("show") ? PhoneStatusBarPolicy.LOCATION_STATUS_ICON_ID
                    : 0;
            updateSlot("location", null, iconId);
        }
        String alarm = args.getString("alarm");
        if (alarm != null) {
            int iconId = alarm.equals("show") ? R.drawable.stat_sys_alarm
                    : 0;
            updateSlot("alarm_clock", null, iconId);
        }
        String tty = args.getString("tty");
        if (tty != null) {
            int iconId = tty.equals("show") ? R.drawable.stat_sys_tty_mode
                    : 0;
            updateSlot("tty", null, iconId);
        }
        String mute = args.getString("mute");
        if (mute != null) {
            int iconId = mute.equals("show") ? android.R.drawable.stat_notify_call_mute
                    : 0;
            updateSlot("mute", null, iconId);
        }
        String speakerphone = args.getString("speakerphone");
        if (speakerphone != null) {
            int iconId = speakerphone.equals("show") ? android.R.drawable.stat_sys_speakerphone
                    : 0;
            updateSlot("speakerphone", null, iconId);
        }
        String cast = args.getString("cast");
        if (cast != null) {
            int iconId = cast.equals("show") ? R.drawable.stat_sys_cast : 0;
            updateSlot("cast", null, iconId);
        }
        String hotspot = args.getString("hotspot");
        if (hotspot != null) {
            int iconId = hotspot.equals("show") ? R.drawable.stat_sys_hotspot : 0;
            updateSlot("hotspot", null, iconId);
        }
    }

    /// Can only be used to update non-signal related slots
    private void updateSlot(String slot, String iconPkg, int iconId) {
        if (!mDemoMode) return;
        if (iconPkg == null) {
            iconPkg = mContext.getPackageName();
        }
        int removeIndex = -1;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof StatusBarIconView)) {
                continue;
            }
            StatusBarIconView v = (StatusBarIconView) child;
            if (slot.equals(v.getTag())) {
                if (iconId == 0) {
                    removeIndex = i;
                    break;
                } else {
                    StatusBarIcon icon = v.getStatusBarIcon();
                    icon.visible = true;
                    icon.icon = Icon.createWithResource(icon.icon.getResPackage(), iconId);
                    v.set(icon);
                    v.updateDrawable();
                    return;
                }
            }
        }
        if (iconId == 0) {
            if (removeIndex != -1) {
                removeViewAt(removeIndex);
            }
            return;
        }
        StatusBarIcon icon = new StatusBarIcon(iconPkg, UserHandle.SYSTEM, iconId, 0, 0, "Demo");
        icon.visible = true;
        StatusBarIconView v = new StatusBarIconView(getContext(), slot, null, false);
        v.setTag(slot);
        v.set(icon);
        v.setStaticDrawableColor(mColor, mContrastColor);
        v.setDecorColor(mColor);
        addView(v, 0, createLayoutParams());
    }

    /**
     * Add a {@link ModernStatusBarMobileView}
     * @param mobileContext possibly mcc/mnc overridden mobile context
     * @param subId the subscriptionId for this mobile view
     */
    public void addModernMobileView(
            Context mobileContext,
            MobileViewLogger mobileViewLogger,
            int subId) {
        Log.d(TAG, "addModernMobileView (subId=" + subId + ")");
        ModernStatusBarMobileView view = ModernStatusBarMobileView.constructAndBind(
                mobileContext,
                mobileViewLogger,
                "mobile",
                mMobileIconsViewModel.viewModelForSub(subId, mLocation)
        );

        // mobile always goes at the end
        mModernMobileViews.add(view);
        addView(view, getChildCount(), createLayoutParams());
    }

    /**
     * Add a {@link ModernStatusBarWifiView}
     */
    public void addModernWifiView(LocationBasedWifiViewModel viewModel) {
        Log.d(TAG, "addModernDemoWifiView: ");
        ModernStatusBarWifiView view = ModernStatusBarWifiView
                .constructAndBind(mContext, "wifi", viewModel);

        int viewIndex = getChildCount();
        // If we have mobile views, put wifi before them
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof ModernStatusBarMobileView) {
                viewIndex = i;
                break;
            }
        }

        mModernWifiView = view;
        mModernWifiView.setStaticDrawableColor(mColor, mContrastColor);
        addView(view, viewIndex, createLayoutParams());
    }

    public void onRemoveIcon(StatusIconDisplayable view) {
        if (view.getSlot().equals("wifi")) {
            if (view instanceof ModernStatusBarWifiView) {
                Log.d(TAG, "onRemoveIcon: removing modern wifi view");
                removeView(mModernWifiView);
                mModernWifiView = null;
            }
        } else if (view instanceof ModernStatusBarMobileView) {
            ModernStatusBarMobileView mobileView = matchingModernMobileView(
                    (ModernStatusBarMobileView) view);
            if (mobileView != null) {
                removeView(mobileView);
                mModernMobileViews.remove(mobileView);
            }
        }
    }

    private ModernStatusBarMobileView matchingModernMobileView(ModernStatusBarMobileView other) {
        for (ModernStatusBarMobileView v : mModernMobileViews) {
            if (v.getSubId() == other.getSubId()) {
                return v;
            }
        }

        return null;
    }

    private LayoutParams createLayoutParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
    }

    @Override
    public void onDarkChangedWithContrast(ArrayList<Rect> areas, int tint, int contrastTint) {
        setColor(tint, contrastTint);

        if (mModernWifiView != null) {
            mModernWifiView.onDarkChangedWithContrast(areas, tint, contrastTint);
        }

        for (ModernStatusBarMobileView view : mModernMobileViews) {
            view.onDarkChangedWithContrast(areas, tint, contrastTint);
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        // not needed
    }
}
