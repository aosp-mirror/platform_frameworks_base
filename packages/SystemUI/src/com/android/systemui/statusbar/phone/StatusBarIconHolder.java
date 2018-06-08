/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.WifiIconState;

/**
 * Wraps {@link com.android.internal.statusbar.StatusBarIcon} so we can still have a uniform list
 */
public class StatusBarIconHolder {
    public static final int TYPE_ICON = 0;
    public static final int TYPE_WIFI = 1;
    public static final int TYPE_MOBILE = 2;

    private StatusBarIcon mIcon;
    private WifiIconState mWifiState;
    private MobileIconState mMobileState;
    private int mType = TYPE_ICON;
    private int mTag = 0;
    private boolean mVisible = true;

    public static StatusBarIconHolder fromIcon(StatusBarIcon icon) {
        StatusBarIconHolder wrapper = new StatusBarIconHolder();
        wrapper.mIcon = icon;

        return wrapper;
    }

    public static StatusBarIconHolder fromResId(Context context, int resId,
            CharSequence contentDescription) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mIcon = new StatusBarIcon(UserHandle.SYSTEM, context.getPackageName(),
                Icon.createWithResource( context, resId), 0, 0, contentDescription);
        return holder;
    }

    public static StatusBarIconHolder fromWifiIconState(WifiIconState state) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mWifiState = state;
        holder.mType = TYPE_WIFI;
        return holder;
    }

    public static StatusBarIconHolder fromMobileIconState(MobileIconState state) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mMobileState = state;
        holder.mType = TYPE_MOBILE;
        holder.mTag = state.subId;
        return holder;
    }

    public int getType() {
        return mType;
    }

    @Nullable
    public StatusBarIcon getIcon() {
        return mIcon;
    }

    @Nullable
    public WifiIconState getWifiState() {
        return mWifiState;
    }

    public void setWifiState(WifiIconState state) {
        mWifiState = state;
    }

    @Nullable
    public MobileIconState getMobileState() {
        return mMobileState;
    }

    public void setMobileState(MobileIconState state) {
        mMobileState = state;
    }

    public boolean isVisible() {
        switch (mType) {
            case TYPE_ICON:
                return mIcon.visible;
            case TYPE_WIFI:
                return mWifiState.visible;
            case TYPE_MOBILE:
                return mMobileState.visible;

            default: return true;
        }
    }

    public void setVisible(boolean visible) {
        if (isVisible() == visible) {
            return;
        }

        switch (mType) {
            case TYPE_ICON:
                mIcon.visible = visible;
                break;

            case TYPE_WIFI:
                mWifiState.visible = visible;
                break;

            case TYPE_MOBILE:
                mMobileState.visible = visible;
                break;
        }
    }

    public int getTag() {
        return mTag;
    }
}
