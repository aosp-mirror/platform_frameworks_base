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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.UserHandle;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.CallIndicatorIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.WifiIconState;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Wraps {@link com.android.internal.statusbar.StatusBarIcon} so we can still have a uniform list
 */
public class StatusBarIconHolder {
    public static final int TYPE_ICON = 0;
    public static final int TYPE_WIFI = 1;
    public static final int TYPE_MOBILE = 2;
    /**
     * TODO (b/249790733): address this once the new pipeline is in place
     * This type exists so that the new pipeline (see {@link MobileIconViewModel}) can be used
     * to inform the old view system about changes to the data set (the list of mobile icons). The
     * design of the new pipeline should allow for removal of this icon holder type, and obsolete
     * the need for this entire class.
     *
     * @deprecated This field only exists so the new status bar pipeline can interface with the
     * view holder system.
     */
    @Deprecated
    public static final int TYPE_MOBILE_NEW = 3;

    /**
     * TODO (b/238425913): address this once the new pipeline is in place
     * This type exists so that the new wifi pipeline can be used to inform the old view system
     * about the existence of the wifi icon. The design of the new pipeline should allow for removal
     * of this icon holder type, and obsolete the need for this entire class.
     *
     * @deprecated This field only exists so the new status bar pipeline can interface with the
     * view holder system.
     */
    @Deprecated
    public static final int TYPE_WIFI_NEW = 4;

    @IntDef({
            TYPE_ICON,
            TYPE_WIFI,
            TYPE_MOBILE,
            TYPE_MOBILE_NEW,
            TYPE_WIFI_NEW
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface IconType {}

    private StatusBarIcon mIcon;
    private WifiIconState mWifiState;
    private MobileIconState mMobileState;
    private @IconType int mType = TYPE_ICON;
    private int mTag = 0;

    private StatusBarIconHolder() {
    }

    public static StatusBarIconHolder fromIcon(StatusBarIcon icon) {
        StatusBarIconHolder wrapper = new StatusBarIconHolder();
        wrapper.mIcon = icon;

        return wrapper;
    }

    /** */
    public static StatusBarIconHolder fromResId(
            Context context,
            int resId,
            CharSequence contentDescription) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mIcon = new StatusBarIcon(UserHandle.SYSTEM, context.getPackageName(),
                Icon.createWithResource( context, resId), 0, 0, contentDescription);
        return holder;
    }

    /** */
    public static StatusBarIconHolder fromWifiIconState(WifiIconState state) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mWifiState = state;
        holder.mType = TYPE_WIFI;
        return holder;
    }

    /** Creates a new holder with for the new wifi icon. */
    public static StatusBarIconHolder forNewWifiIcon() {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mType = TYPE_WIFI_NEW;
        return holder;
    }

    /** */
    public static StatusBarIconHolder fromMobileIconState(MobileIconState state) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mMobileState = state;
        holder.mType = TYPE_MOBILE;
        holder.mTag = state.subId;
        return holder;
    }

    /**
     * ONLY for use with the new connectivity pipeline, where we only need a subscriptionID to
     * determine icon ordering and building the correct view model
     */
    public static StatusBarIconHolder fromSubIdForModernMobileIcon(int subId) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        holder.mType = TYPE_MOBILE_NEW;
        holder.mTag = subId;

        return holder;
    }

    /**
     * Creates a new StatusBarIconHolder from a CallIndicatorIconState.
     */
    public static StatusBarIconHolder fromCallIndicatorState(
            Context context,
            CallIndicatorIconState state) {
        StatusBarIconHolder holder = new StatusBarIconHolder();
        int resId = state.isNoCalling ? state.noCallingResId : state.callStrengthResId;
        String contentDescription = state.isNoCalling
                ? state.noCallingDescription : state.callStrengthDescription;
        holder.mIcon = new StatusBarIcon(UserHandle.SYSTEM, context.getPackageName(),
                Icon.createWithResource(context, resId), 0, 0, contentDescription);
        holder.mTag = state.subId;
        return holder;
    }

    public @IconType int getType() {
        return mType;
    }

    @Nullable
    public StatusBarIcon getIcon() {
        return mIcon;
    }

    public void setIcon(StatusBarIcon icon) {
        mIcon = icon;
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
            case TYPE_MOBILE_NEW:
            case TYPE_WIFI_NEW:
                // The new pipeline controls visibilities via the view model and view binder, so
                // this is effectively an unused return value.
                return true;
            default:
                return true;
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

            case TYPE_MOBILE_NEW:
            case TYPE_WIFI_NEW:
                // The new pipeline controls visibilities via the view model and view binder, so
                // ignore setVisible.
                break;
        }
    }

    public int getTag() {
        return mTag;
    }
}
