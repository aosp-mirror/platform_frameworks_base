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

import static android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS;
import static android.app.StatusBarManager.DISABLE_NONE;

import android.annotation.DrawableRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.telephony.SubscriptionInfo;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.Utils.DisableStateTracker;

import java.util.ArrayList;
import java.util.List;

// Intimately tied to the design of res/layout/signal_cluster_view.xml
public class SignalClusterView extends LinearLayout implements NetworkControllerImpl.SignalCallback,
        SecurityController.SecurityControllerCallback, Tunable, DarkReceiver {

    static final String TAG = "SignalClusterView";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SLOT_AIRPLANE = "airplane";
    private static final String SLOT_MOBILE = "mobile";
    private static final String SLOT_WIFI = "wifi";
    private static final String SLOT_ETHERNET = "ethernet";
    private static final String SLOT_VPN = "vpn";

    private final NetworkController mNetworkController;
    private final SecurityController mSecurityController;

    private boolean mVpnVisible = false;
    private int mVpnIconId = 0;
    private int mLastVpnIconId = -1;
    private boolean mEthernetVisible = false;
    private int mEthernetIconId = 0;
    private int mLastEthernetIconId = -1;
    private boolean mWifiVisible = false;
    private int mWifiStrengthId = 0;
    private int mLastWifiStrengthId = -1;
    private boolean mWifiIn;
    private boolean mWifiOut;
    private boolean mIsAirplaneMode = false;
    private int mAirplaneIconId = 0;
    private int mLastAirplaneIconId = -1;
    private String mAirplaneContentDescription;
    private String mWifiDescription;
    private String mEthernetDescription;
    private ArrayList<PhoneState> mPhoneStates = new ArrayList<PhoneState>();
    private int mIconTint = Color.WHITE;
    private float mDarkIntensity;
    private final Rect mTintArea = new Rect();

    ViewGroup mEthernetGroup, mWifiGroup;
    ImageView mVpn, mEthernet, mWifi, mAirplane, mEthernetDark, mWifiDark;
    ImageView mWifiActivityIn;
    ImageView mWifiActivityOut;
    View mWifiAirplaneSpacer;
    View mWifiSignalSpacer;
    LinearLayout mMobileSignalGroup;

    private final int mMobileSignalGroupEndPadding;
    private final int mMobileDataIconStartPadding;
    private final int mSecondaryTelephonyPadding;
    private final int mEndPadding;
    private final int mEndPaddingNothingVisible;
    private final float mIconScaleFactor;

    private boolean mBlockAirplane;
    private boolean mBlockMobile;
    private boolean mBlockWifi;
    private boolean mBlockEthernet;
    private boolean mActivityEnabled;
    private boolean mForceBlockWifi;

    private final IconLogger mIconLogger = Dependency.get(IconLogger.class);

    public SignalClusterView(Context context) {
        this(context, null);
    }

    public SignalClusterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalClusterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Resources res = getResources();
        mMobileSignalGroupEndPadding =
                res.getDimensionPixelSize(R.dimen.mobile_signal_group_end_padding);
        mMobileDataIconStartPadding =
                res.getDimensionPixelSize(R.dimen.mobile_data_icon_start_padding);
        mSecondaryTelephonyPadding = res.getDimensionPixelSize(R.dimen.secondary_telephony_padding);
        mEndPadding = res.getDimensionPixelSize(R.dimen.signal_cluster_battery_padding);
        mEndPaddingNothingVisible = res.getDimensionPixelSize(
                R.dimen.no_signal_cluster_battery_padding);

        TypedValue typedValue = new TypedValue();
        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        mIconScaleFactor = typedValue.getFloat();
        mNetworkController = Dependency.get(NetworkController.class);
        mSecurityController = Dependency.get(SecurityController.class);
        addOnAttachStateChangeListener(
                new DisableStateTracker(DISABLE_NONE, DISABLE2_SYSTEM_ICONS));
        updateActivityEnabled();
    }

    public void setForceBlockWifi() {
        mForceBlockWifi = true;
        mBlockWifi = true;
        if (isAttachedToWindow()) {
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
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
            mBlockWifi = blockWifi || mForceBlockWifi;
            // Re-register to get new callbacks.
            mNetworkController.removeCallback(this);
            mNetworkController.addCallback(this);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mVpn            = findViewById(R.id.vpn);
        mEthernetGroup  = findViewById(R.id.ethernet_combo);
        mEthernet       = findViewById(R.id.ethernet);
        mEthernetDark   = findViewById(R.id.ethernet_dark);
        mWifiGroup      = findViewById(R.id.wifi_combo);
        mWifi           = findViewById(R.id.wifi_signal);
        mWifiDark       = findViewById(R.id.wifi_signal_dark);
        mWifiActivityIn = findViewById(R.id.wifi_in);
        mWifiActivityOut= findViewById(R.id.wifi_out);
        mAirplane       = findViewById(R.id.airplane);
        mWifiAirplaneSpacer =         findViewById(R.id.wifi_airplane_spacer);
        mWifiSignalSpacer =           findViewById(R.id.wifi_signal_spacer);
        mMobileSignalGroup =          findViewById(R.id.mobile_signal_group);

        maybeScaleVpnAndNoSimsIcons();
    }

    /**
     * Extracts the icon off of the VPN and no sims views and maybe scale them by
     * {@link #mIconScaleFactor}. Note that the other icons are not scaled here because they are
     * dynamic. As such, they need to be scaled each time the icon changes in {@link #apply()}.
     */
    private void maybeScaleVpnAndNoSimsIcons() {
        if (mIconScaleFactor == 1.f) {
            return;
        }

        mVpn.setImageDrawable(new ScalingDrawableWrapper(mVpn.getDrawable(), mIconScaleFactor));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mVpnVisible = mSecurityController.isVpnEnabled();
        mVpnIconId = currentVpnIconId(mSecurityController.isVpnBranded());

        for (PhoneState state : mPhoneStates) {
            if (state.mMobileGroup.getParent() == null) {
                mMobileSignalGroup.addView(state.mMobileGroup);
            }
        }

        int endPadding = mMobileSignalGroup.getChildCount() > 0 ? mMobileSignalGroupEndPadding : 0;
        mMobileSignalGroup.setPaddingRelative(0, 0, endPadding, 0);

        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_BLACKLIST);

        apply();
        applyIconTint();
        mNetworkController.addCallback(this);
        mSecurityController.addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        mMobileSignalGroup.removeAllViews();
        Dependency.get(TunerService.class).removeTunable(this);
        mSecurityController.removeCallback(this);
        mNetworkController.removeCallback(this);

        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        // Re-run all checks against the tint area for all icons
        applyIconTint();
    }

    // From SecurityController.
    @Override
    public void onStateChanged() {
        post(new Runnable() {
            @Override
            public void run() {
                mVpnVisible = mSecurityController.isVpnEnabled();
                mVpnIconId = currentVpnIconId(mSecurityController.isVpnBranded());
                apply();
            }
        });
    }

    private void updateActivityEnabled() {
        mActivityEnabled = mContext.getResources().getBoolean(R.bool.config_showActivity);
    }

    @Override
    public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
            boolean activityIn, boolean activityOut, String description, boolean isTransient,
            String secondaryLabel) {
        mWifiVisible = statusIcon.visible && !mBlockWifi;
        mWifiStrengthId = statusIcon.icon;
        mWifiDescription = statusIcon.contentDescription;
        mWifiIn = activityIn && mActivityEnabled && mWifiVisible;
        mWifiOut = activityOut && mActivityEnabled && mWifiVisible;

        apply();
    }

    @Override
    public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut, String typeContentDescription,
            String description, boolean isWide, int subId, boolean roaming) {
        PhoneState state = getState(subId);
        if (state == null) {
            return;
        }
        state.mMobileVisible = statusIcon.visible && !mBlockMobile;
        state.mMobileStrengthId = statusIcon.icon;
        state.mMobileTypeId = statusType;
        state.mMobileDescription = statusIcon.contentDescription;
        state.mMobileTypeDescription = typeContentDescription;
        state.mRoaming = roaming;
        state.mActivityIn = activityIn && mActivityEnabled;
        state.mActivityOut = activityOut && mActivityEnabled;

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
    public void setNoSims(boolean show, boolean simDetected) {
        // Noop. Status bar no longer shows no sim icon.
    }

    @Override
    public void setSubs(List<SubscriptionInfo> subs) {
        if (hasCorrectSubs(subs)) {
            return;
        }
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
            if (state.mMobileType != null) {
                state.mMobileType.setImageDrawable(null);
                state.mLastMobileTypeId = -1;
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

        if (mVpnVisible) {
            if (mLastVpnIconId != mVpnIconId) {
                setIconForView(mVpn, mVpnIconId);
                mLastVpnIconId = mVpnIconId;
            }
            mIconLogger.onIconShown(SLOT_VPN);
            mVpn.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_VPN);
            mVpn.setVisibility(View.GONE);
        }
        if (DEBUG) Log.d(TAG, String.format("vpn: %s", mVpnVisible ? "VISIBLE" : "GONE"));

        if (mEthernetVisible) {
            if (mLastEthernetIconId != mEthernetIconId) {
                setIconForView(mEthernet, mEthernetIconId);
                setIconForView(mEthernetDark, mEthernetIconId);
                mLastEthernetIconId = mEthernetIconId;
            }
            mEthernetGroup.setContentDescription(mEthernetDescription);
            mIconLogger.onIconShown(SLOT_ETHERNET);
            mEthernetGroup.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_ETHERNET);
            mEthernetGroup.setVisibility(View.GONE);
        }

        if (DEBUG) Log.d(TAG,
                String.format("ethernet: %s",
                    (mEthernetVisible ? "VISIBLE" : "GONE")));

        if (mWifiVisible) {
            if (mWifiStrengthId != mLastWifiStrengthId) {
                setIconForView(mWifi, mWifiStrengthId);
                setIconForView(mWifiDark, mWifiStrengthId);
                mLastWifiStrengthId = mWifiStrengthId;
            }
            mIconLogger.onIconShown(SLOT_WIFI);
            mWifiGroup.setContentDescription(mWifiDescription);
            mWifiGroup.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_WIFI);
            mWifiGroup.setVisibility(View.GONE);
        }

        if (DEBUG) Log.d(TAG,
                String.format("wifi: %s sig=%d",
                    (mWifiVisible ? "VISIBLE" : "GONE"),
                    mWifiStrengthId));

        mWifiActivityIn.setVisibility(mWifiIn ? View.VISIBLE : View.GONE);
        mWifiActivityOut.setVisibility(mWifiOut ? View.VISIBLE : View.GONE);

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
        if (anyMobileVisible) {
            mIconLogger.onIconShown(SLOT_MOBILE);
        } else {
            mIconLogger.onIconHidden(SLOT_MOBILE);
        }

        if (mIsAirplaneMode) {
            if (mLastAirplaneIconId != mAirplaneIconId) {
                setIconForView(mAirplane, mAirplaneIconId);
                mLastAirplaneIconId = mAirplaneIconId;
            }
            mAirplane.setContentDescription(mAirplaneContentDescription);
            mIconLogger.onIconShown(SLOT_AIRPLANE);
            mAirplane.setVisibility(View.VISIBLE);
        } else {
            mIconLogger.onIconHidden(SLOT_AIRPLANE);
            mAirplane.setVisibility(View.GONE);
        }

        if (mIsAirplaneMode && mWifiVisible) {
            mWifiAirplaneSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiAirplaneSpacer.setVisibility(View.GONE);
        }

        if ((anyMobileVisible && firstMobileTypeId != 0) && mWifiVisible) {
            mWifiSignalSpacer.setVisibility(View.VISIBLE);
        } else {
            mWifiSignalSpacer.setVisibility(View.GONE);
        }

        boolean anythingVisible = mWifiVisible || mIsAirplaneMode
                || anyMobileVisible || mVpnVisible || mEthernetVisible;
        setPaddingRelative(0, 0, anythingVisible ? mEndPadding : mEndPaddingNothingVisible, 0);
    }

    /**
     * Sets the given drawable id on the view. This method will also scale the icon by
     * {@link #mIconScaleFactor} if appropriate.
     */
    private void setIconForView(ImageView imageView, @DrawableRes int iconId) {
        // Using the imageView's context to retrieve the Drawable so that theme is preserved.
        Drawable icon = imageView.getContext().getDrawable(iconId);

        if (mIconScaleFactor == 1.f) {
            imageView.setImageDrawable(icon);
        } else {
            imageView.setImageDrawable(new ScalingDrawableWrapper(icon, mIconScaleFactor));
        }
    }


    @Override
    public void onDarkChanged(Rect tintArea, float darkIntensity, int tint) {
        boolean changed = tint != mIconTint || darkIntensity != mDarkIntensity
                || !mTintArea.equals(tintArea);
        mIconTint = tint;
        mDarkIntensity = darkIntensity;
        mTintArea.set(tintArea);
        if (changed && isAttachedToWindow()) {
            applyIconTint();
        }
    }

    private void applyIconTint() {
        setTint(mVpn, DarkIconDispatcher.getTint(mTintArea, mVpn, mIconTint));
        setTint(mAirplane, DarkIconDispatcher.getTint(mTintArea, mAirplane, mIconTint));
        applyDarkIntensity(
                DarkIconDispatcher.getDarkIntensity(mTintArea, mWifi, mDarkIntensity),
                mWifi, mWifiDark);
        setTint(mWifiActivityIn,
                DarkIconDispatcher.getTint(mTintArea, mWifiActivityIn, mIconTint));
        setTint(mWifiActivityOut,
                DarkIconDispatcher.getTint(mTintArea, mWifiActivityOut, mIconTint));
        applyDarkIntensity(
                DarkIconDispatcher.getDarkIntensity(mTintArea, mEthernet, mDarkIntensity),
                mEthernet, mEthernetDark);
        for (int i = 0; i < mPhoneStates.size(); i++) {
            mPhoneStates.get(i).setIconTint(mIconTint, mDarkIntensity, mTintArea);
        }
    }

    private void applyDarkIntensity(float darkIntensity, View lightIcon, View darkIcon) {
        lightIcon.setAlpha(1 - darkIntensity);
        darkIcon.setAlpha(darkIntensity);
    }

    private void setTint(ImageView v, int tint) {
        v.setImageTintList(ColorStateList.valueOf(tint));
    }

    private int currentVpnIconId(boolean isBranded) {
        return isBranded ? R.drawable.stat_sys_branded_vpn : R.drawable.stat_sys_vpn_ic;
    }

    private class PhoneState {
        private final int mSubId;
        private boolean mMobileVisible = false;
        private int mMobileStrengthId = 0, mMobileTypeId = 0;
        private int mLastMobileStrengthId = -1;
        private int mLastMobileTypeId = -1;
        private String mMobileDescription, mMobileTypeDescription;

        private ViewGroup mMobileGroup;
        private ImageView mMobile, mMobileType, mMobileRoaming;
        private View mMobileRoamingSpace;
        public boolean mRoaming;
        private ImageView mMobileActivityIn;
        private ImageView mMobileActivityOut;
        public boolean mActivityIn;
        public boolean mActivityOut;
        private SignalDrawable mMobileSignalDrawable;

        public PhoneState(int subId, Context context) {
            ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.mobile_signal_group, null);
            setViews(root);
            mSubId = subId;
        }

        public void setViews(ViewGroup root) {
            mMobileGroup    = root;
            mMobile         = root.findViewById(R.id.mobile_signal);
            mMobileType     = root.findViewById(R.id.mobile_type);
            mMobileRoaming  = root.findViewById(R.id.mobile_roaming);
            mMobileRoamingSpace  = root.findViewById(R.id.mobile_roaming_space);
            mMobileActivityIn = root.findViewById(R.id.mobile_in);
            mMobileActivityOut = root.findViewById(R.id.mobile_out);
            mMobileSignalDrawable = new SignalDrawable(mMobile.getContext());
            mMobile.setImageDrawable(mMobileSignalDrawable);
        }

        public boolean apply(boolean isSecondaryIcon) {
            if (mMobileVisible && !mIsAirplaneMode) {
                if (mLastMobileStrengthId != mMobileStrengthId) {
                    mMobile.getDrawable().setLevel(mMobileStrengthId);
                    mLastMobileStrengthId = mMobileStrengthId;
                }

                if (mLastMobileTypeId != mMobileTypeId) {
                    mMobileType.setImageResource(mMobileTypeId);
                    mLastMobileTypeId = mMobileTypeId;
                }

                mMobileGroup.setContentDescription(mMobileTypeDescription
                        + " " + mMobileDescription);
                mMobileGroup.setVisibility(View.VISIBLE);
            } else {
                mMobileGroup.setVisibility(View.GONE);
            }

            // When this isn't next to wifi, give it some extra padding between the signals.
            mMobileGroup.setPaddingRelative(isSecondaryIcon ? mSecondaryTelephonyPadding : 0,
                    0, 0, 0);
            mMobile.setPaddingRelative(mMobileDataIconStartPadding, 0, 0, 0);

            if (DEBUG) Log.d(TAG, String.format("mobile: %s sig=%d typ=%d",
                        (mMobileVisible ? "VISIBLE" : "GONE"), mMobileStrengthId, mMobileTypeId));

            mMobileType.setVisibility(mMobileTypeId != 0 ? View.VISIBLE : View.GONE);
            mMobileRoaming.setVisibility(mRoaming ? View.VISIBLE : View.GONE);
            mMobileRoamingSpace.setVisibility(mRoaming ? View.VISIBLE : View.GONE);
            mMobileActivityIn.setVisibility(mActivityIn ? View.VISIBLE : View.GONE);
            mMobileActivityOut.setVisibility(mActivityOut ? View.VISIBLE : View.GONE);

            return mMobileVisible;
        }

        public void populateAccessibilityEvent(AccessibilityEvent event) {
            if (mMobileVisible && mMobileGroup != null
                    && mMobileGroup.getContentDescription() != null) {
                event.getText().add(mMobileGroup.getContentDescription());
            }
        }

        public void setIconTint(int tint, float darkIntensity, Rect tintArea) {
            mMobileSignalDrawable.setDarkIntensity(darkIntensity);
            setTint(mMobileType, DarkIconDispatcher.getTint(tintArea, mMobileType, tint));
            setTint(mMobileRoaming, DarkIconDispatcher.getTint(tintArea, mMobileRoaming,
                    tint));
            setTint(mMobileActivityIn,
                    DarkIconDispatcher.getTint(tintArea, mMobileActivityIn, tint));
            setTint(mMobileActivityOut,
                    DarkIconDispatcher.getTint(tintArea, mMobileActivityOut, tint));
        }
    }
}
