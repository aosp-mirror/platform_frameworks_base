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
import android.telephony.SubscriptionInfo;
import android.util.ArraySet;
import android.util.Log;

import com.android.settingslib.mobile.TelephonyIcons;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
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
    private final String mSlotWifi;
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
    private boolean mHideWifi;
    private boolean mHideEthernet;
    private boolean mActivityEnabled;

    // Track as little state as possible, and only for padding purposes
    private boolean mIsAirplaneMode = false;
    private boolean mIsWifiEnabled = false;

    private ArrayList<MobileIconState> mMobileStates = new ArrayList<>();
    private ArrayList<CallIndicatorIconState> mCallIndicatorStates = new ArrayList<>();
    private WifiIconState mWifiIconState = new WifiIconState();
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
        mSlotWifi     = mContext.getString(com.android.internal.R.string.status_bar_wifi);
        mSlotEthernet = mContext.getString(com.android.internal.R.string.status_bar_ethernet);
        mSlotVpn      = mContext.getString(com.android.internal.R.string.status_bar_vpn);
        mSlotNoCalling = mContext.getString(com.android.internal.R.string.status_bar_no_calling);
        mSlotCallStrength =
                mContext.getString(com.android.internal.R.string.status_bar_call_strength);
        mActivityEnabled = mContext.getResources().getBoolean(R.bool.config_showActivity);
    }

    /** Call to initilaize and register this classw with the system. */
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
        int vpnIconId = currentVpnIconId(mSecurityController.isVpnBranded());

        mIconController.setIcon(mSlotVpn, vpnIconId,
                mContext.getResources().getString(R.string.accessibility_vpn_on));
        mIconController.setIconVisibility(mSlotVpn, vpnVisible);
    }

    private int currentVpnIconId(boolean isBranded) {
        return isBranded ? R.drawable.stat_sys_branded_vpn : R.drawable.stat_sys_vpn_ic;
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
        boolean hideWifi = hideList.contains(mSlotWifi);
        boolean hideEthernet = hideList.contains(mSlotEthernet);

        if (hideAirplane != mHideAirplane || hideMobile != mHideMobile
                || hideEthernet != mHideEthernet || hideWifi != mHideWifi) {
            mHideAirplane = hideAirplane;
            mHideMobile = hideMobile;
            mHideEthernet = hideEthernet;
            mHideWifi = hideWifi;
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
    }

    @Override
    public void setWifiIndicators(@NonNull WifiIndicators indicators) {
        if (DEBUG) {
            Log.d(TAG, "setWifiIndicators: " + indicators);
        }
        boolean visible = indicators.statusIcon.visible && !mHideWifi;
        boolean in = indicators.activityIn && mActivityEnabled && visible;
        boolean out = indicators.activityOut && mActivityEnabled && visible;
        mIsWifiEnabled = indicators.enabled;

        WifiIconState newState = mWifiIconState.copy();

        if (mWifiIconState.noDefaultNetwork && mWifiIconState.noNetworksAvailable
                && !mIsAirplaneMode) {
            newState.visible = true;
            newState.resId = R.drawable.ic_qs_no_internet_unavailable;
        } else if (mWifiIconState.noDefaultNetwork && !mWifiIconState.noNetworksAvailable
                && (!mIsAirplaneMode || (mIsAirplaneMode && mIsWifiEnabled))) {
            newState.visible = true;
            newState.resId = R.drawable.ic_qs_no_internet_available;
        } else {
            newState.visible = visible;
            newState.resId = indicators.statusIcon.icon;
            newState.activityIn = in;
            newState.activityOut = out;
            newState.contentDescription = indicators.statusIcon.contentDescription;
            MobileIconState first = getFirstMobileState();
            newState.signalSpacerVisible = first != null && first.typeId != 0;
        }
        newState.slot = mSlotWifi;
        newState.airplaneSpacerVisible = mIsAirplaneMode;
        updateWifiIconWithState(newState);
        mWifiIconState = newState;
    }

    private void updateShowWifiSignalSpacer(WifiIconState state) {
        MobileIconState first = getFirstMobileState();
        state.signalSpacerVisible = first != null && first.typeId != 0;
    }

    private void updateWifiIconWithState(WifiIconState state) {
        if (DEBUG) Log.d(TAG, "WifiIconState: " + state == null ? "" : state.toString());
        if (state.visible && state.resId > 0) {
            mIconController.setSignalIcon(mSlotWifi, state);
            mIconController.setIconVisibility(mSlotWifi, true);
        } else {
            mIconController.setIconVisibility(mSlotWifi, false);
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
        if (statusIcon.icon == R.drawable.ic_qs_no_calling_sms) {
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

    @Override
    public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
        if (DEBUG) {
            Log.d(TAG, "setMobileDataIndicators: " + indicators);
        }
        MobileIconState state = getState(indicators.subId);
        if (state == null) {
            return;
        }

        // Visibility of the data type indicator changed
        boolean typeChanged = indicators.statusType != state.typeId
                && (indicators.statusType == 0 || state.typeId == 0);

        state.visible = indicators.statusIcon.visible && !mHideMobile;
        state.strengthId = indicators.statusIcon.icon;
        state.typeId = indicators.statusType;
        state.contentDescription = indicators.statusIcon.contentDescription;
        state.typeContentDescription = indicators.typeContentDescription;
        state.showTriangle = indicators.showTriangle;
        state.roaming = indicators.roaming;
        state.activityIn = indicators.activityIn && mActivityEnabled;
        state.activityOut = indicators.activityOut && mActivityEnabled;

        if (DEBUG) {
            Log.d(TAG, "MobileIconStates: "
                    + (mMobileStates == null ? "" : mMobileStates.toString()));
        }
        // Always send a copy to maintain value type semantics
        mIconController.setMobileIcons(mSlotMobile, MobileIconState.copyStates(mMobileStates));

        if (typeChanged) {
            WifiIconState wifiCopy = mWifiIconState.copy();
            updateShowWifiSignalSpacer(wifiCopy);
            if (!Objects.equals(wifiCopy, mWifiIconState)) {
                updateWifiIconWithState(wifiCopy);
                mWifiIconState = wifiCopy;
            }
        }
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

    private MobileIconState getState(int subId) {
        for (MobileIconState state : mMobileStates) {
            if (state.subId == subId) {
                return state;
            }
        }
        Log.e(TAG, "Unexpected subscription " + subId);
        return null;
    }

    private MobileIconState getFirstMobileState() {
        if (mMobileStates.size() > 0) {
            return mMobileStates.get(0);
        }

        return null;
    }


    /**
     * It is expected that a call to setSubs will be immediately followed by setMobileDataIndicators
     * so we don't have to update the icon manager at this point, just remove the old ones
     * @param subs list of mobile subscriptions, displayed as mobile data indicators (max 8)
     */
    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        if (DEBUG) Log.d(TAG, "setSubs: " + (subs == null ? "" : subs.toString()));
        if (hasCorrectSubs(subs)) {
            return;
        }

        mIconController.removeAllIconsForSlot(mSlotMobile);
        mIconController.removeAllIconsForSlot(mSlotNoCalling);
        mIconController.removeAllIconsForSlot(mSlotCallStrength);
        mMobileStates.clear();
        List<CallIndicatorIconState> noCallingStates = new ArrayList<CallIndicatorIconState>();
        noCallingStates.addAll(mCallIndicatorStates);
        mCallIndicatorStates.clear();
        final int n = subs.size();
        for (int i = 0; i < n; i++) {
            mMobileStates.add(new MobileIconState(subs.get(i).getSubscriptionId()));
            boolean isNewSub = true;
            for (CallIndicatorIconState state : noCallingStates) {
                if (state.subId == subs.get(i).getSubscriptionId()) {
                    mCallIndicatorStates.add(state);
                    isNewSub = false;
                    break;
                }
            }
            if (isNewSub) {
                mCallIndicatorStates.add(
                        new CallIndicatorIconState(subs.get(i).getSubscriptionId()));
            }
        }
    }

    private boolean hasCorrectSubs(List<SubscriptionInfo> subs) {
        final int N = subs.size();
        if (N != mMobileStates.size()) {
            return false;
        }
        for (int i = 0; i < N; i++) {
            if (mMobileStates.get(i).subId != subs.get(i).getSubscriptionId()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setNoSims(boolean show, boolean simDetected) {
        // Noop yay!
    }

    @Override
    public void setEthernetIndicators(IconState state) {
        boolean visible = state.visible && !mHideEthernet;
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

    @Override
    public void setMobileDataEnabled(boolean enabled) {
        // Don't care.
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
            this.noCallingResId = R.drawable.ic_qs_no_calling_sms;
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

    private static abstract class SignalIconState {
        public boolean visible;
        public boolean activityOut;
        public boolean activityIn;
        public String slot;
        public String contentDescription;

        @Override
        public boolean equals(Object o) {
            // Skipping reference equality bc this should be more of a value type
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SignalIconState that = (SignalIconState) o;
            return visible == that.visible &&
                    activityOut == that.activityOut &&
                    activityIn == that.activityIn &&
                    Objects.equals(contentDescription, that.contentDescription) &&
                    Objects.equals(slot, that.slot);
        }

        @Override
        public int hashCode() {
            return Objects.hash(visible, activityOut, slot);
        }

        protected void copyTo(SignalIconState other) {
            other.visible = visible;
            other.activityIn = activityIn;
            other.activityOut = activityOut;
            other.slot = slot;
            other.contentDescription = contentDescription;
        }
    }

    public static class WifiIconState extends SignalIconState{
        public int resId;
        public boolean airplaneSpacerVisible;
        public boolean signalSpacerVisible;
        public boolean noDefaultNetwork;
        public boolean noValidatedNetwork;
        public boolean noNetworksAvailable;

        @Override
        public boolean equals(Object o) {
            // Skipping reference equality bc this should be more of a value type
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            WifiIconState that = (WifiIconState) o;
            return resId == that.resId
                    && airplaneSpacerVisible == that.airplaneSpacerVisible
                    && signalSpacerVisible == that.signalSpacerVisible
                    && noDefaultNetwork == that.noDefaultNetwork
                    && noValidatedNetwork == that.noValidatedNetwork
                    && noNetworksAvailable == that.noNetworksAvailable;
        }

        public void copyTo(WifiIconState other) {
            super.copyTo(other);
            other.resId = resId;
            other.airplaneSpacerVisible = airplaneSpacerVisible;
            other.signalSpacerVisible = signalSpacerVisible;
            other.noDefaultNetwork = noDefaultNetwork;
            other.noValidatedNetwork = noValidatedNetwork;
            other.noNetworksAvailable = noNetworksAvailable;
        }

        public WifiIconState copy() {
            WifiIconState newState = new WifiIconState();
            copyTo(newState);
            return newState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(),
                    resId, airplaneSpacerVisible, signalSpacerVisible, noDefaultNetwork,
                    noValidatedNetwork, noNetworksAvailable);
        }

        @Override public String toString() {
            return "WifiIconState(resId=" + resId + ", visible=" + visible + ")";
        }
    }

    /**
     * A little different. This one delegates to SignalDrawable instead of a specific resId
     */
    public static class MobileIconState extends SignalIconState {
        public int subId;
        public int strengthId;
        public int typeId;
        public boolean showTriangle;
        public boolean roaming;
        public boolean needsLeadingPadding;
        public CharSequence typeContentDescription;

        private MobileIconState(int subId) {
            super();
            this.subId = subId;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            MobileIconState that = (MobileIconState) o;
            return subId == that.subId
                    && strengthId == that.strengthId
                    && typeId == that.typeId
                    && showTriangle == that.showTriangle
                    && roaming == that.roaming
                    && needsLeadingPadding == that.needsLeadingPadding
                    && Objects.equals(typeContentDescription, that.typeContentDescription);
        }

        @Override
        public int hashCode() {

            return Objects
                    .hash(super.hashCode(), subId, strengthId, typeId, showTriangle, roaming,
                            needsLeadingPadding, typeContentDescription);
        }

        public MobileIconState copy() {
            MobileIconState copy = new MobileIconState(this.subId);
            copyTo(copy);
            return copy;
        }

        public void copyTo(MobileIconState other) {
            super.copyTo(other);
            other.subId = subId;
            other.strengthId = strengthId;
            other.typeId = typeId;
            other.showTriangle = showTriangle;
            other.roaming = roaming;
            other.needsLeadingPadding = needsLeadingPadding;
            other.typeContentDescription = typeContentDescription;
        }

        private static List<MobileIconState> copyStates(List<MobileIconState> inStates) {
            ArrayList<MobileIconState> outStates = new ArrayList<>();
            for (MobileIconState state : inStates) {
                MobileIconState copy = new MobileIconState(state.subId);
                state.copyTo(copy);
                outStates.add(copy);
            }

            return outStates;
        }

        @Override public String toString() {
            return "MobileIconState(subId=" + subId + ", strengthId=" + strengthId
                    + ", showTriangle=" + showTriangle + ", roaming=" + roaming
                    + ", typeId=" + typeId + ", visible=" + visible + ")";
        }
    }
}
