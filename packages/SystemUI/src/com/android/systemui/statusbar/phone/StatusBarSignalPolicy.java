/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Log;

import com.android.settingslib.mobile.TelephonyIcons;
import com.android.systemui.res.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.CarrierConfigTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

/** Controls the signal policies for icons shown in the statusbar. **/
@SysUISingleton
public class StatusBarSignalPolicy implements SignalCallback,
        SecurityController.SecurityControllerCallback, Tunable {
    private static final String TAG = "StatusBarSignalPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final String mSlotAirplane;
    private final String mSlotMobile;
    private final String mSlotEthernet;
    private final String mSlotVpn;
    private final String mSlotNoCalling;
    private final String mSlotCallStrength;

    private final Context mContext;
    private final StatusBarIconController mIconController;
    private final NetworkController mNetworkController;
    private final SecurityController mSecurityController;
    private final Handler mHandler = Handler.getMain();
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final TunerService mTunerService;

    private boolean mHideAirplane;
    private boolean mHideMobile;
    private boolean mHideEthernet;
    private boolean mActivityEnabled;

    // Track as little state as possible, and only for padding purposes
    private boolean mIsAirplaneMode = false;

    private ArrayList<CallIndicatorIconState> mCallIndicatorStates = new ArrayList<>();
    private boolean mInitialized;

    @Inject
    public StatusBarSignalPolicy(
            Context context,
            StatusBarIconController iconController,
            CarrierConfigTracker carrierConfigTracker,
            NetworkController networkController,
            SecurityController securityController,
            TunerService tunerService
    ) {
        mContext = context;

        mIconController = iconController;
        mCarrierConfigTracker = carrierConfigTracker;
        mNetworkController = networkController;
        mSecurityController = securityController;
        mTunerService = tunerService;

        mSlotAirplane = mContext.getString(com.android.internal.R.string.status_bar_airplane);
        mSlotMobile   = mContext.getString(com.android.internal.R.string.status_bar_mobile);
        mSlotEthernet = mContext.getString(com.android.internal.R.string.status_bar_ethernet);
        mSlotVpn      = mContext.getString(com.android.internal.R.string.status_bar_vpn);
        mSlotNoCalling = mContext.getString(com.android.internal.R.string.status_bar_no_calling);
        mSlotCallStrength =
                mContext.getString(com.android.internal.R.string.status_bar_call_strength);
        mActivityEnabled = mContext.getResources().getBoolean(R.bool.config_showActivity);
    }

    /** Call to initialize and register this class with the system. */
    public void init() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mTunerService.addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
        mNetworkController.addCallback(this);
        mSecurityController.addCallback(this);
    }

    public void destroy() {
        mTunerService.removeTunable(this);
        mNetworkController.removeCallback(this);
        mSecurityController.removeCallback(this);
    }

    private void updateVpn() {
        boolean vpnVisible = mSecurityController.isVpnEnabled();
        int vpnIconId = currentVpnIconId(
                mSecurityController.isVpnBranded(),
                mSecurityController.isVpnValidated());

        mIconController.setIcon(mSlotVpn, vpnIconId,
                mContext.getResources().getString(R.string.accessibility_vpn_on));
        mIconController.setIconVisibility(mSlotVpn, vpnVisible);
    }

    private int currentVpnIconId(boolean isBranded, boolean isValidated) {
        if (isBranded) {
            return isValidated
                    ? R.drawable.stat_sys_branded_vpn
                    : R.drawable.stat_sys_no_internet_branded_vpn;
        } else {
            return isValidated
                    ? R.drawable.stat_sys_vpn_ic
                    : R.drawable.stat_sys_no_internet_vpn_ic;
        }
    }

    /**
     * From SecurityController
     */
    @Override
    public void onStateChanged() {
        mHandler.post(this::updateVpn);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            return;
        }
        ArraySet<String> hideList = StatusBarIconController.getIconHideList(mContext, newValue);
        boolean hideAirplane = hideList.contains(mSlotAirplane);
        boolean hideMobile = hideList.contains(mSlotMobile);
        boolean hideEthernet = hideList.contains(mSlotEthernet);

        if (hideAirplane != mHideAirplane || hideMobile != mHideMobile
                || hideEthernet != mHideEthernet) {
            mHideAirplane = hideAirplane;
            mHideMobile = hideMobile;
            mHideEthernet = hideEthernet;
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
    }

    @Override
    public void setCallIndicator(@NonNull IconState statusIcon, int subId) {
        if (DEBUG) {
            Log.d(TAG, "setCallIndicator: "
                    + "statusIcon = " + statusIcon + ","
                    + "subId = " + subId);
        }
        CallIndicatorIconState state = getNoCallingState(subId);
        if (state == null) {
            return;
        }
        if (statusIcon.icon == R.drawable.ic_shade_no_calling_sms) {
            state.isNoCalling = statusIcon.visible;
            state.noCallingDescription = statusIcon.contentDescription;
        } else {
            state.callStrengthResId = statusIcon.icon;
            state.callStrengthDescription = statusIcon.contentDescription;
        }
        if (mCarrierConfigTracker.getCallStrengthConfig(subId)) {
            mIconController.setCallStrengthIcons(mSlotCallStrength,
                    CallIndicatorIconState.copyStates(mCallIndicatorStates));
        } else {
            mIconController.removeIcon(mSlotCallStrength, subId);
        }
        mIconController.setNoCallingIcons(mSlotNoCalling,
                CallIndicatorIconState.copyStates(mCallIndicatorStates));
    }

    private CallIndicatorIconState getNoCallingState(int subId) {
        for (CallIndicatorIconState state : mCallIndicatorStates) {
            if (state.subId == subId) {
                return state;
            }
        }
        Log.e(TAG, "Unexpected subscription " + subId);
        return null;
    }

    @Override
    public void setEthernetIndicators(IconState state) {
        int resId = state.icon;
        String description = state.contentDescription;

        if (resId > 0) {
            mIconController.setIcon(mSlotEthernet, resId, description);
            mIconController.setIconVisibility(mSlotEthernet, true);
        } else {
            mIconController.setIconVisibility(mSlotEthernet, false);
        }
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        if (DEBUG) {
            Log.d(TAG, "setIsAirplaneMode: "
                    + "icon = " + (icon == null ? "" : icon.toString()));
        }
        mIsAirplaneMode = icon.visible && !mHideAirplane;
        int resId = icon.icon;
        String description = icon.contentDescription;

        if (mIsAirplaneMode && resId > 0) {
            mIconController.setIcon(mSlotAirplane, resId, description);
            mIconController.setIconVisibility(mSlotAirplane, true);
        } else {
            mIconController.setIconVisibility(mSlotAirplane, false);
        }
    }

    /**
     * Stores the statusbar state for no Calling & SMS.
     */
    public static class CallIndicatorIconState {
        public boolean isNoCalling;
        public int noCallingResId;
        public int callStrengthResId;
        public int subId;
        public String noCallingDescription;
        public String callStrengthDescription;

        private CallIndicatorIconState(int subId) {
            this.subId = subId;
            this.noCallingResId = R.drawable.ic_shade_no_calling_sms;
            this.callStrengthResId = TelephonyIcons.MOBILE_CALL_STRENGTH_ICONS[0];
        }

        @Override
        public boolean equals(Object o) {
            // Skipping reference equality bc this should be more of a value type
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CallIndicatorIconState that = (CallIndicatorIconState) o;
            return  isNoCalling == that.isNoCalling
                    && noCallingResId == that.noCallingResId
                    && callStrengthResId == that.callStrengthResId
                    && subId == that.subId
                    && noCallingDescription == that.noCallingDescription
                    && callStrengthDescription == that.callStrengthDescription;

        }

        @Override
        public int hashCode() {
            return Objects.hash(isNoCalling, noCallingResId,
                    callStrengthResId, subId, noCallingDescription, callStrengthDescription);
        }

        private void copyTo(CallIndicatorIconState other) {
            other.isNoCalling = isNoCalling;
            other.noCallingResId = noCallingResId;
            other.callStrengthResId = callStrengthResId;
            other.subId = subId;
            other.noCallingDescription = noCallingDescription;
            other.callStrengthDescription = callStrengthDescription;
        }

        private static List<CallIndicatorIconState> copyStates(
                List<CallIndicatorIconState> inStates) {
            ArrayList<CallIndicatorIconState> outStates = new ArrayList<>();
            for (CallIndicatorIconState state : inStates) {
                CallIndicatorIconState copy = new CallIndicatorIconState(state.subId);
                state.copyTo(copy);
                outStates.add(copy);
            }
            return outStates;
        }
    }
}
