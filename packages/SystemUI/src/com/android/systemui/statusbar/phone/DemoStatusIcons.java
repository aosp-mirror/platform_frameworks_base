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
import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusBarMobileView;
import com.android.systemui.statusbar.StatusBarWifiView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.WifiIconState;

import java.util.ArrayList;

public class DemoStatusIcons extends StatusIconContainer implements DemoMode, DarkReceiver {
    private static final String TAG = "DemoStatusIcons";

    private final LinearLayout mStatusIcons;
    private final ArrayList<StatusBarMobileView> mMobileViews = new ArrayList<>();
    private final int mIconSize;

    private StatusBarWifiView mWifiView;
    private boolean mDemoMode;
    private int mColor;

    public DemoStatusIcons(LinearLayout statusIcons, int iconSize) {
        super(statusIcons.getContext());
        mStatusIcons = statusIcons;
        mIconSize = iconSize;
        mColor = DarkIconDispatcher.DEFAULT_ICON_TINT;

        if (statusIcons instanceof StatusIconContainer) {
            setShouldRestrictIcons(((StatusIconContainer) statusIcons).isRestrictingIcons());
        } else {
            setShouldRestrictIcons(false);
        }
        setLayoutParams(mStatusIcons.getLayoutParams());
        setPadding(mStatusIcons.getPaddingLeft(),mStatusIcons.getPaddingTop(),
                mStatusIcons.getPaddingRight(), mStatusIcons.getPaddingBottom());
        setOrientation(mStatusIcons.getOrientation());
        setGravity(Gravity.CENTER_VERTICAL); // no LL.getGravity()
        ViewGroup p = (ViewGroup) mStatusIcons.getParent();
        p.addView(this, p.indexOfChild(mStatusIcons));
    }

    public void remove() {
        mMobileViews.clear();
        ((ViewGroup) getParent()).removeView(this);
    }

    public void setColor(int color) {
        mColor = color;
        updateColors();
    }

    private void updateColors() {
        for (int i = 0; i < getChildCount(); i++) {
            StatusIconDisplayable child = (StatusIconDisplayable) getChildAt(i);
            child.setStaticDrawableColor(mColor);
            child.setDecorColor(mColor);
        }
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
            mStatusIcons.setVisibility(View.GONE);
            setVisibility(View.VISIBLE);
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            mStatusIcons.setVisibility(View.VISIBLE);
            setVisibility(View.GONE);
        } else if (mDemoMode && command.equals(COMMAND_STATUS)) {
            String volume = args.getString("volume");
            if (volume != null) {
                int iconId = volume.equals("vibrate") ? R.drawable.stat_sys_ringer_vibrate
                        : 0;
                updateSlot("volume", null, iconId);
            }
            String zen = args.getString("zen");
            if (zen != null) {
                int iconId = zen.equals("important") ? R.drawable.stat_sys_zen_important
                        : zen.equals("none") ? R.drawable.stat_sys_zen_none
                        : 0;
                updateSlot("zen", null, iconId);
            }
            String bt = args.getString("bluetooth");
            if (bt != null) {
                int iconId = bt.equals("disconnected") ? R.drawable.stat_sys_data_bluetooth
                        : bt.equals("connected") ? R.drawable.stat_sys_data_bluetooth_connected
                        : 0;
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
        v.setStaticDrawableColor(mColor);
        v.setDecorColor(mColor);
        addView(v, 0, createLayoutParams());
    }

    public void addDemoWifiView(WifiIconState state) {
        Log.d(TAG, "addDemoWifiView: ");
        StatusBarWifiView view = StatusBarWifiView.fromContext(mContext, state.slot);

        int viewIndex = getChildCount();
        // If we have mobile views, put wifi before them
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof StatusBarMobileView) {
                viewIndex = i;
                break;
            }
        }

        mWifiView = view;
        mWifiView.applyWifiState(state);
        mWifiView.setStaticDrawableColor(mColor);
        addView(view, viewIndex, createLayoutParams());
    }

    public void updateWifiState(WifiIconState state) {
        Log.d(TAG, "updateWifiState: ");
        if (mWifiView == null) {
            addDemoWifiView(state);
        } else {
            mWifiView.applyWifiState(state);
        }
    }

    public void addMobileView(MobileIconState state) {
        Log.d(TAG, "addMobileView: ");
        StatusBarMobileView view = StatusBarMobileView.fromContext(mContext, state.slot);

        view.applyMobileState(state);
        view.setStaticDrawableColor(mColor);

        // mobile always goes at the end
        mMobileViews.add(view);
        addView(view, getChildCount(), createLayoutParams());
    }

    public void updateMobileState(MobileIconState state) {
        Log.d(TAG, "updateMobileState: ");
        // If the view for this subId exists already, use it
        for (int i = 0; i < mMobileViews.size(); i++) {
            StatusBarMobileView view = mMobileViews.get(i);
            if (view.getState().subId == state.subId) {
                view.applyMobileState(state);
                return;
            }
        }

        // Else we have to add it
        addMobileView(state);
    }

    public void onRemoveIcon(StatusIconDisplayable view) {
        if (view.getSlot().equals("wifi")) {
            removeView(mWifiView);
            mWifiView = null;
        } else {
            StatusBarMobileView mobileView = matchingMobileView(view);
            if (mobileView != null) {
                removeView(mobileView);
                mMobileViews.remove(mobileView);
            }
        }
    }

    private StatusBarMobileView matchingMobileView(StatusIconDisplayable otherView) {
        if (!(otherView instanceof StatusBarMobileView)) {
            return null;
        }

        StatusBarMobileView v = (StatusBarMobileView) otherView;
        for (StatusBarMobileView view : mMobileViews) {
            if (view.getState().subId == v.getState().subId) {
                return view;
            }
        }

        return null;
    }

    private LayoutParams createLayoutParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        setColor(DarkIconDispatcher.getTint(area, mStatusIcons, tint));

        if (mWifiView != null) {
            mWifiView.onDarkChanged(area, darkIntensity, tint);
        }
        for (StatusBarMobileView view : mMobileViews) {
            view.onDarkChanged(area, darkIntensity, tint);
        }
    }
}
