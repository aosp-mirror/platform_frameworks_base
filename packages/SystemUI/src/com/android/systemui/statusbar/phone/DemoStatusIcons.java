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

import android.os.Bundle;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.policy.LocationControllerImpl;

public class DemoStatusIcons extends LinearLayout implements DemoMode {
    private final LinearLayout mStatusIcons;
    private final int mIconSize;

    private boolean mDemoMode;

    public DemoStatusIcons(LinearLayout statusIcons, int iconSize) {
        super(statusIcons.getContext());
        mStatusIcons = statusIcons;
        mIconSize = iconSize;

        setLayoutParams(mStatusIcons.getLayoutParams());
        setOrientation(mStatusIcons.getOrientation());
        setGravity(Gravity.CENTER_VERTICAL); // no LL.getGravity()
        ViewGroup p = (ViewGroup) mStatusIcons.getParent();
        p.addView(this, p.indexOfChild(mStatusIcons));
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
                int iconId = location.equals("show") ? LocationControllerImpl.LOCATION_STATUS_ICON_ID
                        : 0;
                updateSlot(LocationControllerImpl.LOCATION_STATUS_ICON_PLACEHOLDER, null, iconId);
            }
            String alarm = args.getString("alarm");
            if (alarm != null) {
                int iconId = alarm.equals("show") ? R.drawable.stat_sys_alarm
                        : 0;
                updateSlot("alarm_clock", null, iconId);
            }
            String sync = args.getString("sync");
            if (sync != null) {
                int iconId = sync.equals("show") ? R.drawable.stat_sys_sync
                        : 0;
                updateSlot("sync_active", null, iconId);
            }
            String tty = args.getString("tty");
            if (tty != null) {
                int iconId = tty.equals("show") ? R.drawable.stat_sys_tty_mode
                        : 0;
                updateSlot("tty", null, iconId);
            }
            String eri = args.getString("eri");
            if (eri != null) {
                int iconId = eri.equals("show") ? R.drawable.stat_sys_roaming_cdma_0
                        : 0;
                updateSlot("cdma_eri", null, iconId);
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
                int iconId = cast.equals("cast") ? R.drawable.stat_sys_cast : 0;
                updateSlot("cast", null, iconId);
            }
        }
    }

    private void updateSlot(String slot, String iconPkg, int iconId) {
        if (!mDemoMode) return;
        int removeIndex = -1;
        for (int i = 0; i < getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) getChildAt(i);
            if (slot.equals(v.getTag())) {
                if (iconId == 0) {
                    removeIndex = i;
                    break;
                } else {
                    StatusBarIcon icon = v.getStatusBarIcon();
                    icon.iconPackage = iconPkg;
                    icon.iconId = iconId;
                    v.set(icon);
                    v.updateDrawable();
                    return;
                }
            }
        }
        if (iconId == 0) {
            if (removeIndex != -1) {
                removeViewAt(removeIndex);
                return;
            }
        }
        StatusBarIcon icon = new StatusBarIcon(iconPkg, UserHandle.CURRENT, iconId, 0, 0, "Demo");
        StatusBarIconView v = new StatusBarIconView(getContext(), null);
        v.setTag(slot);
        v.set(icon);
        addView(v, 0, new LinearLayout.LayoutParams(mIconSize, mIconSize));
    }
}