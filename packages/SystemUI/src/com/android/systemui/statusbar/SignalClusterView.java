/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.telephony.SubscriptionInfo;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.List;

// Intimately tied to the design of res/layout/signal_cluster_view.xml
public class SignalClusterView
        extends LinearLayout
        implements NetworkControllerImpl.SignalCallback,
        SecurityController.SecurityControllerCallback, Tunable {

    static final String TAG = "SignalClusterView";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SLOT_AIRPLANE = "airplane";
    private static final String SLOT_MOBILE = "mobile";
    private static final String SLOT_WIFI = "wifi";
    private static final String SLOT_ETHERNET = "ethernet";

    NetworkControllerImpl mNC;
    SecurityController mSC;

    private boolean mNoSimsVisible = false;
    private boolean mVpnVisible = false;
    private boolean mEthernetVisible = false;
    private int mEthernetIconId = 0;
    private int mLastEthernetIconId = -1;
    private boolean mWifiVisible = false;
    private int mWifiStrengthId = 0;
    private int mLastWifiStrengthId = -1;
    private boolean mIsAirplaneMode = false;
    private int mAirplaneIconId = 0;
    private int mLastAirplaneIconId = -1;
    private String mAirplaneContentDescription;
    private String mWifiDescription;
    private String mEthernetDescription;
    private ArrayList<PhoneState> mPhoneStates = new ArrayList<PhoneState>();
    private int mIconTint = Color.WHITE;
    private float mDarkIntensity;

    ViewGroup mEthernetGroup, mWifiGroup;
    View mNoSimsCombo;
    ImageView mVpn, mEthernet, mWifi, mAirplane, mNoSims, mEthernetDark, mWifiDark, mNoSimsDark;
    View mWifiAirplaneSpacer;
    View mWifiSignalSpacer;
    LinearLayout mMobileSignalGroup;

    private int mWideTypeIconStartPadding;
    private int mSecondaryTelephonyPadding;
    private int mEndPadding;
    private int mEndPaddingNothingVisible;

    private boolean mBlockAirplane;
    private boolean mBlockMobile;
    private boolean mBlockWifi;
    private boolean mBlockEthernet;

    public SignalClusterView(Context context) {
        this(context, null);
    }

    public SignalClusterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalClusterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            return;
        }
        ArraySet<String> blockList = StatusBarIconController.getIconBlacklist(newValue);
        boolean blockAirplane = blockList.contains(SLOT_AIRPLANE);
        boolean blockMobile = blockList.contains(SLOT_MOBILE);
        boolean blockWifi = blockList.contains(SLOT_WIFI);
        boolean blockEthernet = blockList.contains(SLOT_ETHERNET);

        if (blockAirplane != mBlockAirplane || blockMobile != mBlockMobile
                || blockEthernet != mBlockEthernet || blockWifi != mBlockWifi) {
            mBlockAirplane = blockAirplane;
            mBlockMobile = blockMobile;
            mBlockEthernet = blockEthernet;
            mBlockWifi = blockWifi;
            // Re-register to get new callbacks.
            mNC.removeSignalCallback(this);
            mNC.addSignalCallback(this);
        }
    }

    public void setNetworkController(NetworkControllerImpl nc) {
        if (DEBUG) Log.d(TAG, "NetworkController=" + nc);
        mNC = nc;
    }

    public void setSecurityController(SecurityController sc) {
        if (DEBUG) Log.d(TAG, "SecurityController=" + sc);
        mSC = sc;
        mSC.addCallback(this);
        mVpnVisible = mSC.isVpnEnabled();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mWideTypeIconStartPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.wide_type_icon_start_padding);
        mSecondaryTelephonyPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.secondary_telephony_padding);
        mEndPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.signal_cluster_battery_padding);
        mEndPaddingNothingVisible = getContext().getResources().getDimensionPixelSize(
                R.dimen.no_signal_cluster_battery_padding);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mVpn            = (ImageView) findViewById(R.id.vpn);
        mEthernetGroup  = (ViewGroup) findViewById(R.id.ethernet_combo);
        mEthernet       = (ImageView) findViewById(R.id.ethernet);
        mEthernetDark   = (ImageView) findViewById(R.id.ethernet_dark);
        mWifiGroup      = (ViewGroup) findViewById(R.id.wifi_combo);
        mWifi           = (ImageView) findViewById(R.id.wifi_signal);
        mWifiDark       = (ImageView) findViewById(R.id.wifi_signal_dark);
        mAirplane       = (ImageView) findViewById(R.id.airplane);
        mNoSims         = (ImageView) findViewById(R.id.no_sims);
        mNoSimsDark     = (ImageView) findViewById(R.id.no_sims_dark);
        mNoSimsCombo    =             findViewById(R.id.no_sims_combo);
        mWifiAirplaneSpacer =         findViewById(R.id.wifi_airplane_spacer);
        mWifiSignalSpacer =           findViewById(R.id.wifi_signal_spacer);
        mMobileSignalGroup = (LinearLayout) findViewById(R.id.mobile_signal_group);
        for (PhoneState state : mPhoneStates) {
            mMobileSignalGroup.addView(state.mMobileGroup);
        }
        TunerService.get(mContext).addTunable(this, StatusBarIconController.ICON_BLACKLIST);

        apply();
        applyIconTint();
    }

    @Override
    protected void onDetachedFromWindow() {
        mVpn            = null;
        mEthernetGroup  = null;
        mEthernet       = null;
        mWifiGroup      = null;
        mWifi           = null;
        mAirplane       = null;
        mMobileSignalGroup.removeAllViews();
        mMobileSignalGroup = null;
        TunerService.get(mContext).removeTunable(this);

        super.onDetachedFromWindow();
    }

    // From SecurityController.
    @Override
    public void onStateChanged() {
        post(new Runnable() {
            @Override
            public void run() {
                mVpnVisible = mSC.isVpnEnabled();
                apply();
            }
        });
    }

    @Override
    public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
            boolean activityIn, boolean activityOut, String description) {
        mWifiVisible = statusIcon.visible && !mBlockWifi;
        mWifiStrengthId = statusIcon.icon;
        mWifiDescription = statusIcon.contentDescription;

        apply();
    }

    @Override
    public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut, String typeContentDescription,
            String description, boolean isWide, int subId) {
        PhoneState state = getState(subId);
        if (state == null) {
            return;
        }
        state.mMobileVisible = statusIcon.visible && !mBlockMobile;
        state.mMobileStrengthId = statusIcon.icon;
        state.mMobileTypeId = statusType;
        state.mMobileDescription = statusIcon.contentDescription;
        state.mMobileTypeDescription = typeContentDescription;
        state.mIsMobileTypeIconWide = statusType != 0 && isWide;

        apply();
    }

    @Override
    public void setEthernetIndicators(IconState state) {
        mEthernetVisible = state.visible && !mBlockEthernet;
        mEthernetIconId = state.icon;
        mEthernetDescription = state.contentDescription;

        apply();
    }

    @Override
    public void setNoSims(boolean show) {
        mNoSimsVisible = show && !mBlockMobile;
    }

    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        if (hasCorrectSubs(subs)) {
            return;
        }
        // Clear out all old subIds.
        mPhoneStates.clear();
        if (mMobileSignalGroup != null) {
            mMobileSignalGroup.removeAllViews();
        }
        final int n = subs.size();
        for (int i = 0; i < n; i++) {
            inflatePhoneState(subs.get(i).getSubscriptionId());
        }
        if (isAttachedToWindow()) {
            applyIconTint();
        }
    }

    private boolean hasCorrectSubs(List<SubscriptionInfo> subs) {
        final int N = subs.size();
        if (N != mPhoneStates.size()) {
            return false;
        }
        for (int i = 0; i < N; i++) {
            if (mPhoneStates.get(i).mSubId != subs.get(i).getSubscriptionId()) {
                return false;
            }
        }
        return true;
    }

    private PhoneState getState(int subId) {
        for (PhoneState state : mPhoneStates) {
            if (state.mSubId == subId) {
                return state;
            }
        }
        Log.e(TAG, "Unexpected subscription " + subId);
        return null;
    }

    private PhoneState inflatePhoneState(int subId) {
        PhoneState state = new PhoneState(subId, mContext);
        if (mMobileSignalGroup != null) {
            mMobileSignalGroup.addView(state.mMobileGroup);
        }
        mPhoneStates.add(state);
        return state;
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        mIsAirplaneMode = icon.visible && !mBlockAirplane;
        mAirplaneIconId = icon.icon;
        mAirplaneContentDescription = icon.contentDescription;

        apply();
    }

    @Override
    public void setMobileDataEnabled(boolean enabled) {
        // Don't care.
    }

    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        // Standard group layout onPopulateAccessibilityEvent() implementations
        // ignore content description, so populate manually
        if (mEthernetVisible && mEthernetGroup != null &&
                mEthernetGroup.getContentDescription() != null)
            event.getText().add(mEthernetGroup.getContentDescription());
        if (mWifiVisible && mWifiGroup != null && mWifiGroup.getContentDescription() != null)
            event.getText().add(mWifiGroup.getContentDescription());
        for (PhoneState state : mPhoneStates) {
            state.populateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEventInternal(event);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        if (mEthernet != null) {
            mEthernet.setImageDrawable(null);
            mEthernetDark.setImageDrawable(null);
            mLastEthernetIconId = -1;
        }

        if (mWifi != null) {
            mWifi.setImageDrawable(null);
            mWifiDark.setImageDrawable(null);
            mLastWifiStrengthId = -1;
        }

        for (PhoneState state : mPhoneStates) {
            if (state.mMobile != null) {
                state.mMobile.setImageDrawable(null);
            }
            if (state.mMobileType != null) {
                state.mMobileType.setImageDrawable(null);
            }
        }

        if (mAirplane != null) {
            mAirplane.setImageDrawable(null);
            mLastAirplaneIconId = -1;
        }

        apply();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // Run after each indicator change.
    private void apply() {
        if (mWifiGroup == null) return;

        mVpn.setVisibility(mVpnVisible ? View.VISIBLE : View.GONE);
        if (DEBUG) Log.d(TAG, String.format("vpn: %s", mVpnVisible ? "VISIBLE" : "GONE"));

        if (mEthernetVisible) {
            if (mLastEthernetIconId != mEthernetIconId) {
                mEthernet.setImageResource(mEthernetIconId);
                mEthernetDark.setImageResource(mEthernetIconId);
                mLastEthernetIconId = mEthernetIconId;
            }
            mEthernetGroup.setContentDescription(mEthernetDescription);
            mEthernetGroup.setVisibility(View.VISIBLE);
        } else {
            mEthernetGroup.setVisibility(View.GONE);
        }

        if (DEBUG) Log.d(TAG,
                String.format("ethernet: %s",
                    (mEthernetVisible ? "VISIBLE" : "GONE")));


        if (mWifiVisible) {
            if (mWifiStrengthId != mLastWifiStrengthId) {
                mWifi.setImageResource(mWifiStrengthId);
                mWifiDark.setImageResource(mWifiStrengthId);
                mLastWifiStrengthId = mWifiStrengthId;
            }
            mWifiGroup.setContentDescription(mWifiDescription);
            mWifiGroup.setVisibility(View.VISIBLE);
        } else {
            mWifiGroup.setVisibility(View.GONE);
        }

        if (DEBUG) Log.d(TAG,
                String.format("wifi: %s sig=%d",
                    (mWifiVisible ? "VISIBLE" : "GONE"),
                    mWifiStrengthId));

        boolean anyMobileVisible = false;
        int firstMobileTypeId = 0;
        for (PhoneState state : mPhoneStates) {
            if (state.apply(anyMobileVisible)) {
                if (!anyMobileVisible) {
                    firstMobileTypeId = state.mMobileTypeId;
                    anyMobileVisible = true;
                }
            }
        }

        if (mIsAirplaneMode) {
            if (mLastAirplaneIconId != mAirplaneIconId) {
                mAirplane.setImageResource(mAirplaneIconId);
                mLastAirplaneIconId = mAirplaneIconId;
            }
            mAirplane.setContentDescription(mAirplaneContentDescription);
            mAirplane.setVisibility(View.VISIBLE);
        } else {
            mAirplane.setVisibility(View.GONE);
        }

        if (mIsAirplaneMode && mWifiVisible) {
            mWifiAirplaneSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiAirplaneSpacer.setVisibility(View.GONE);
        }

        if (((anyMobileVisible && firstMobileTypeId != 0) || mNoSimsVisible) && mWifiVisible) {
            mWifiSignalSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiSignalSpacer.setVisibility(View.GONE);
        }

        mNoSimsCombo.setVisibility(mNoSimsVisible ? View.VISIBLE : View.GONE);

        boolean anythingVisible = mNoSimsVisible || mWifiVisible || mIsAirplaneMode
                || anyMobileVisible || mVpnVisible || mEthernetVisible;
        setPaddingRelative(0, 0, anythingVisible ? mEndPadding : mEndPaddingNothingVisible, 0);
    }

    public void setIconTint(int tint, float darkIntensity) {
        boolean changed = tint != mIconTint || darkIntensity != mDarkIntensity;
        mIconTint = tint;
        mDarkIntensity = darkIntensity;
        if (changed && isAttachedToWindow()) {
            applyIconTint();
        }
    }

    private void applyIconTint() {
        setTint(mVpn, mIconTint);
        setTint(mAirplane, mIconTint);
        applyDarkIntensity(mDarkIntensity, mNoSims, mNoSimsDark);
        applyDarkIntensity(mDarkIntensity, mWifi, mWifiDark);
        applyDarkIntensity(mDarkIntensity, mEthernet, mEthernetDark);
        for (int i = 0; i < mPhoneStates.size(); i++) {
            mPhoneStates.get(i).setIconTint(mIconTint, mDarkIntensity);
        }
    }

    private void applyDarkIntensity(float darkIntensity, View lightIcon, View darkIcon) {
        lightIcon.setAlpha(1 - darkIntensity);
        darkIcon.setAlpha(darkIntensity);
    }

    private void setTint(ImageView v, int tint) {
        v.setImageTintList(ColorStateList.valueOf(tint));
    }

    private class PhoneState {
        private final int mSubId;
        private boolean mMobileVisible = false;
        private int mMobileStrengthId = 0, mMobileTypeId = 0;
        private boolean mIsMobileTypeIconWide;
        private String mMobileDescription, mMobileTypeDescription;

        private ViewGroup mMobileGroup;
        private ImageView mMobile, mMobileDark, mMobileType;

        public PhoneState(int subId, Context context) {
            ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.mobile_signal_group, null);
            setViews(root);
            mSubId = subId;
        }

        public void setViews(ViewGroup root) {
            mMobileGroup    = root;
            mMobile         = (ImageView) root.findViewById(R.id.mobile_signal);
            mMobileDark     = (ImageView) root.findViewById(R.id.mobile_signal_dark);
            mMobileType     = (ImageView) root.findViewById(R.id.mobile_type);
        }

        public boolean apply(boolean isSecondaryIcon) {
            if (mMobileVisible && !mIsAirplaneMode) {
                mMobile.setImageResource(mMobileStrengthId);
                Drawable mobileDrawable = mMobile.getDrawable();
                if (mobileDrawable instanceof Animatable) {
                    Animatable ad = (Animatable) mobileDrawable;
                    if (!ad.isRunning()) {
                        ad.start();
                    }
                }

                mMobileDark.setImageResource(mMobileStrengthId);
                Drawable mobileDarkDrawable = mMobileDark.getDrawable();
                if (mobileDarkDrawable instanceof Animatable) {
                    Animatable ad = (Animatable) mobileDarkDrawable;
                    if (!ad.isRunning()) {
                        ad.start();
                    }
                }

                mMobileType.setImageResource(mMobileTypeId);
                mMobileGroup.setContentDescription(mMobileTypeDescription
                        + " " + mMobileDescription);
                mMobileGroup.setVisibility(View.VISIBLE);
            } else {
                mMobileGroup.setVisibility(View.GONE);
            }

            // When this isn't next to wifi, give it some extra padding between the signals.
            mMobileGroup.setPaddingRelative(isSecondaryIcon ? mSecondaryTelephonyPadding : 0,
                    0, 0, 0);
            mMobile.setPaddingRelative(mIsMobileTypeIconWide ? mWideTypeIconStartPadding : 0,
                    0, 0, 0);
            mMobileDark.setPaddingRelative(mIsMobileTypeIconWide ? mWideTypeIconStartPadding : 0,
                    0, 0, 0);

            if (DEBUG) Log.d(TAG, String.format("mobile: %s sig=%d typ=%d",
                        (mMobileVisible ? "VISIBLE" : "GONE"), mMobileStrengthId, mMobileTypeId));

            mMobileType.setVisibility(mMobileTypeId != 0 ? View.VISIBLE : View.GONE);

            return mMobileVisible;
        }

        public void populateAccessibilityEvent(AccessibilityEvent event) {
            if (mMobileVisible && mMobileGroup != null
                    && mMobileGroup.getContentDescription() != null) {
                event.getText().add(mMobileGroup.getContentDescription());
            }
        }

        public void setIconTint(int tint, float darkIntensity) {
            applyDarkIntensity(darkIntensity, mMobile, mMobileDark);
            setTint(mMobileType, tint);
        }
    }
}

