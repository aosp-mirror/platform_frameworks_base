/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.AlphaControlledSignalTileView;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.dialog.InternetDialogFactory;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataIndicators;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkController.WifiIndicators;
import com.android.systemui.statusbar.policy.WifiIcons;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/** Quick settings tile: Internet **/
public class InternetTile extends QSTileImpl<SignalState> {
    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);

    protected final NetworkController mController;
    private final AccessPointController mAccessPointController;
    private final DataUsageController mDataController;
    // The last updated tile state, 0: mobile, 1: wifi, 2: ethernet.
    private int mLastTileState = -1;

    protected final InternetSignalCallback mSignalCallback = new InternetSignalCallback();
    private final InternetDialogFactory mInternetDialogFactory;
    final Handler mHandler;

    @Inject
    public InternetTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            NetworkController networkController,
            AccessPointController accessPointController,
            InternetDialogFactory internetDialogFactory
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mInternetDialogFactory = internetDialogFactory;
        mHandler = mainHandler;
        mController = networkController;
        mAccessPointController = accessPointController;
        mDataController = mController.getMobileDataController();
        mController.observe(getLifecycle(), mSignalCallback);
    }

    @Override
    public SignalState newTileState() {
        SignalState s = new SignalState();
        s.forceExpandIcon = true;
        return s;
    }

    @Override
    public QSIconView createTileView(Context context) {
        return new AlphaControlledSignalTileView(context);
    }

    @Override
    public Intent getLongClickIntent() {
        return WIFI_SETTINGS;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        mHandler.post(() -> mInternetDialogFactory.create(true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi()));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_internet_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_WIFI;
    }

    @Override
    public boolean isAvailable() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)
                || (mController.hasMobileDataFeature()
                        && mHost.getUserContext().getUserId() == UserHandle.USER_SYSTEM);
    }

    private CharSequence getSecondaryLabel(boolean isTransient, String statusLabel) {
        return isTransient
                ? mContext.getString(R.string.quick_settings_wifi_secondary_label_transient)
                : statusLabel;
    }

    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private static final class EthernetCallbackInfo {
        boolean mConnected;
        int mEthernetSignalIconId;
        String mEthernetContentDescription;

        @Override
        public String toString() {
            return new StringBuilder("EthernetCallbackInfo[")
                    .append("mConnected=").append(mConnected)
                    .append(",mEthernetSignalIconId=").append(mEthernetSignalIconId)
                    .append(",mEthernetContentDescription=").append(mEthernetContentDescription)
                    .append(']').toString();
        }
    }

    private static final class WifiCallbackInfo {
        boolean mAirplaneModeEnabled;
        boolean mEnabled;
        boolean mConnected;
        int mWifiSignalIconId;
        String mSsid;
        boolean mActivityIn;
        boolean mActivityOut;
        String mWifiSignalContentDescription;
        boolean mIsTransient;
        public String mStatusLabel;
        boolean mNoDefaultNetwork;
        boolean mNoValidatedNetwork;
        boolean mNoNetworksAvailable;

        @Override
        public String toString() {
            return new StringBuilder("WifiCallbackInfo[")
                    .append("mAirplaneModeEnabled=").append(mAirplaneModeEnabled)
                    .append(",mEnabled=").append(mEnabled)
                    .append(",mConnected=").append(mConnected)
                    .append(",mWifiSignalIconId=").append(mWifiSignalIconId)
                    .append(",mSsid=").append(mSsid)
                    .append(",mActivityIn=").append(mActivityIn)
                    .append(",mActivityOut=").append(mActivityOut)
                    .append(",mWifiSignalContentDescription=").append(mWifiSignalContentDescription)
                    .append(",mIsTransient=").append(mIsTransient)
                    .append(",mNoDefaultNetwork=").append(mNoDefaultNetwork)
                    .append(",mNoValidatedNetwork=").append(mNoValidatedNetwork)
                    .append(",mNoNetworksAvailable=").append(mNoNetworksAvailable)
                    .append(']').toString();
        }
    }

    private static final class CellularCallbackInfo {
        boolean mAirplaneModeEnabled;
        CharSequence mDataSubscriptionName;
        CharSequence mDataContentDescription;
        int mMobileSignalIconId;
        int mQsTypeIcon;
        boolean mActivityIn;
        boolean mActivityOut;
        boolean mNoSim;
        boolean mRoaming;
        boolean mMultipleSubs;
        boolean mNoDefaultNetwork;
        boolean mNoValidatedNetwork;
        boolean mNoNetworksAvailable;

        @Override
        public String toString() {
            return new StringBuilder("CellularCallbackInfo[")
                .append("mAirplaneModeEnabled=").append(mAirplaneModeEnabled)
                .append(",mDataSubscriptionName=").append(mDataSubscriptionName)
                .append(",mDataContentDescription=").append(mDataContentDescription)
                .append(",mMobileSignalIconId=").append(mMobileSignalIconId)
                .append(",mQsTypeIcon=").append(mQsTypeIcon)
                .append(",mActivityIn=").append(mActivityIn)
                .append(",mActivityOut=").append(mActivityOut)
                .append(",mNoSim=").append(mNoSim)
                .append(",mRoaming=").append(mRoaming)
                .append(",mMultipleSubs=").append(mMultipleSubs)
                .append(",mNoDefaultNetwork=").append(mNoDefaultNetwork)
                .append(",mNoValidatedNetwork=").append(mNoValidatedNetwork)
                .append(",mNoNetworksAvailable=").append(mNoNetworksAvailable)
                .append(']').toString();
        }
    }

    protected final class InternetSignalCallback implements SignalCallback {
        final WifiCallbackInfo mWifiInfo = new WifiCallbackInfo();
        final CellularCallbackInfo mCellularInfo = new CellularCallbackInfo();
        final EthernetCallbackInfo mEthernetInfo = new EthernetCallbackInfo();


        @Override
        public void setWifiIndicators(WifiIndicators indicators) {
            if (DEBUG) {
                Log.d(TAG, "setWifiIndicators: " + indicators);
            }
            mWifiInfo.mEnabled = indicators.enabled;
            if (indicators.qsIcon == null) {
                return;
            }
            mWifiInfo.mConnected = indicators.qsIcon.visible;
            mWifiInfo.mWifiSignalIconId = indicators.qsIcon.icon;
            mWifiInfo.mWifiSignalContentDescription = indicators.qsIcon.contentDescription;
            mWifiInfo.mEnabled = indicators.enabled;
            mWifiInfo.mSsid = indicators.description;
            mWifiInfo.mActivityIn = indicators.activityIn;
            mWifiInfo.mActivityOut = indicators.activityOut;
            mWifiInfo.mIsTransient = indicators.isTransient;
            mWifiInfo.mStatusLabel = indicators.statusLabel;
            refreshState(mWifiInfo);
        }

        @Override
        public void setMobileDataIndicators(MobileDataIndicators indicators) {
            if (DEBUG) {
                Log.d(TAG, "setMobileDataIndicators: " + indicators);
            }
            if (indicators.qsIcon == null) {
                // Not data sim, don't display.
                return;
            }
            mCellularInfo.mDataSubscriptionName = indicators.qsDescription == null
                    ? mController.getMobileDataNetworkName() : indicators.qsDescription;
            mCellularInfo.mDataContentDescription = indicators.qsDescription != null
                    ? indicators.typeContentDescriptionHtml : null;
            mCellularInfo.mMobileSignalIconId = indicators.qsIcon.icon;
            mCellularInfo.mQsTypeIcon = indicators.qsType;
            mCellularInfo.mActivityIn = indicators.activityIn;
            mCellularInfo.mActivityOut = indicators.activityOut;
            mCellularInfo.mRoaming = indicators.roaming;
            mCellularInfo.mMultipleSubs = mController.getNumberSubscriptions() > 1;
            refreshState(mCellularInfo);
        }

        @Override
        public void setEthernetIndicators(IconState icon) {
            if (DEBUG) {
                Log.d(TAG, "setEthernetIndicators: "
                        + "icon = " + (icon == null ? "" :  icon.toString()));
            }
            mEthernetInfo.mConnected = icon.visible;
            mEthernetInfo.mEthernetSignalIconId = icon.icon;
            mEthernetInfo.mEthernetContentDescription = icon.contentDescription;
            if (icon.visible) {
                refreshState(mEthernetInfo);
            }
        }

        @Override
        public void setNoSims(boolean show, boolean simDetected) {
            if (DEBUG) {
                Log.d(TAG, "setNoSims: "
                        + "show = " + show + ","
                        + "simDetected = " + simDetected);
            }
            mCellularInfo.mNoSim = show;
            if (mCellularInfo.mNoSim) {
                // Make sure signal gets cleared out when no sims.
                mCellularInfo.mMobileSignalIconId = 0;
                mCellularInfo.mQsTypeIcon = 0;
            }
        }

        @Override
        public void setIsAirplaneMode(IconState icon) {
            if (DEBUG) {
                Log.d(TAG, "setIsAirplaneMode: "
                        + "icon = " + (icon == null ? "" : icon.toString()));
            }
            if (mCellularInfo.mAirplaneModeEnabled == icon.visible) {
                return;
            }
            mCellularInfo.mAirplaneModeEnabled = icon.visible;
            mWifiInfo.mAirplaneModeEnabled = icon.visible;
            if (!mSignalCallback.mEthernetInfo.mConnected) {
                if (mWifiInfo.mEnabled && (mWifiInfo.mWifiSignalIconId > 0)
                        && (mWifiInfo.mSsid != null)) {
                    refreshState(mWifiInfo);
                } else {
                    refreshState(mCellularInfo);
                }
            }
        }

        @Override
        public void setConnectivityStatus(boolean noDefaultNetwork, boolean noValidatedNetwork,
                boolean noNetworksAvailable) {
            if (DEBUG) {
                Log.d(TAG, "setConnectivityStatus: "
                        + "noDefaultNetwork = " + noDefaultNetwork + ","
                        + "noValidatedNetwork = " + noValidatedNetwork + ","
                        + "noNetworksAvailable = " + noNetworksAvailable);
            }
            mCellularInfo.mNoDefaultNetwork = noDefaultNetwork;
            mCellularInfo.mNoValidatedNetwork = noValidatedNetwork;
            mCellularInfo.mNoNetworksAvailable = noNetworksAvailable;
            mWifiInfo.mNoDefaultNetwork = noDefaultNetwork;
            mWifiInfo.mNoValidatedNetwork = noValidatedNetwork;
            mWifiInfo.mNoNetworksAvailable = noNetworksAvailable;
            refreshState(mWifiInfo);
        }

        @Override
        public String toString() {
            return new StringBuilder("InternetSignalCallback[")
                .append("mWifiInfo=").append(mWifiInfo)
                .append(",mCellularInfo=").append(mCellularInfo)
                .append(",mEthernetInfo=").append(mEthernetInfo)
                .append(']').toString();
        }
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        if (arg instanceof CellularCallbackInfo) {
            mLastTileState = 0;
            handleUpdateCellularState(state, arg);
        } else if (arg instanceof WifiCallbackInfo) {
            mLastTileState = 1;
            handleUpdateWifiState(state, arg);
        } else if (arg instanceof EthernetCallbackInfo) {
            mLastTileState = 2;
            handleUpdateEthernetState(state, arg);
        } else {
            // handleUpdateState will be triggered when user expands the QuickSetting panel with
            // arg = null, in this case the last updated CellularCallbackInfo or WifiCallbackInfo
            // should be used to refresh the tile.
            if (mLastTileState == 0) {
                handleUpdateCellularState(state, mSignalCallback.mCellularInfo);
            } else if (mLastTileState == 1) {
                handleUpdateWifiState(state, mSignalCallback.mWifiInfo);
            } else if (mLastTileState == 2) {
                handleUpdateEthernetState(state, mSignalCallback.mEthernetInfo);
            }
        }
    }

    private void handleUpdateWifiState(SignalState state, Object arg) {
        WifiCallbackInfo cb = (WifiCallbackInfo) arg;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateWifiState: " + "WifiCallbackInfo = " + cb.toString());
        }
        boolean wifiConnected = cb.mEnabled && (cb.mWifiSignalIconId > 0) && (cb.mSsid != null);
        boolean wifiNotConnected = (cb.mWifiSignalIconId > 0) && (cb.mSsid == null);
        boolean enabledChanging = state.value != cb.mEnabled;
        if (enabledChanging) {
            fireToggleStateChanged(cb.mEnabled);
        }
        if (state.slash == null) {
            state.slash = new SlashState();
            state.slash.rotation = 6;
        }
        state.slash.isSlashed = false;
        state.secondaryLabel = getSecondaryLabel(cb.mIsTransient, removeDoubleQuotes(cb.mSsid));
        state.state = Tile.STATE_ACTIVE;
        state.dualTarget = true;
        state.value = cb.mEnabled;
        state.activityIn = cb.mEnabled && cb.mActivityIn;
        state.activityOut = cb.mEnabled && cb.mActivityOut;
        final StringBuffer minimalContentDescription = new StringBuffer();
        final StringBuffer minimalStateDescription = new StringBuffer();
        final Resources r = mContext.getResources();
        state.label = r.getString(R.string.quick_settings_internet_label);
        if (cb.mAirplaneModeEnabled) {
            if (!state.value) {
                state.state = Tile.STATE_INACTIVE;
                state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable);
                state.secondaryLabel = r.getString(R.string.status_bar_airplane);
            } else if (!wifiConnected) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable);
                if (cb.mNoNetworksAvailable) {
                    state.secondaryLabel =
                            r.getString(R.string.quick_settings_networks_unavailable);
                } else {
                    state.secondaryLabel =
                            r.getString(R.string.quick_settings_networks_available);
                }
            } else {
                state.icon = ResourceIcon.get(cb.mWifiSignalIconId);
            }
        } else if (cb.mNoDefaultNetwork) {
            if (cb.mNoNetworksAvailable || !cb.mEnabled) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable);
                state.secondaryLabel = r.getString(R.string.quick_settings_networks_unavailable);
            } else {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_available);
                state.secondaryLabel = r.getString(R.string.quick_settings_networks_available);
            }
        } else if (cb.mIsTransient) {
            state.icon = ResourceIcon.get(
                com.android.internal.R.drawable.ic_signal_wifi_transient_animation);
        } else if (!state.value) {
            state.slash.isSlashed = true;
            state.state = Tile.STATE_INACTIVE;
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_DISABLED);
        } else if (wifiConnected) {
            state.icon = ResourceIcon.get(cb.mWifiSignalIconId);
        } else if (wifiNotConnected) {
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_NO_NETWORK);
        } else {
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_NO_NETWORK);
        }
        minimalContentDescription.append(
            mContext.getString(R.string.quick_settings_internet_label)).append(",");
        if (state.value && wifiConnected) {
            minimalStateDescription.append(cb.mWifiSignalContentDescription);
            minimalContentDescription.append(removeDoubleQuotes(cb.mSsid));
        } else if (!TextUtils.isEmpty(state.secondaryLabel)) {
            minimalContentDescription.append(",").append(state.secondaryLabel);
        }

        state.stateDescription = minimalStateDescription.toString();
        state.contentDescription = minimalContentDescription.toString();
        state.dualLabelContentDescription = r.getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel());
        state.expandedAccessibilityClassName = Switch.class.getName();
        if (DEBUG) {
            Log.d(TAG, "handleUpdateWifiState: " + "SignalState = " + state.toString());
        }
    }

    private void handleUpdateCellularState(SignalState state, Object arg) {
        CellularCallbackInfo cb = (CellularCallbackInfo) arg;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateCellularState: " + "CellularCallbackInfo = " + cb.toString());
        }
        final Resources r = mContext.getResources();
        state.label = r.getString(R.string.quick_settings_internet_label);
        state.state = Tile.STATE_ACTIVE;
        boolean mobileDataEnabled = mDataController.isMobileDataSupported()
                && mDataController.isMobileDataEnabled();
        state.value = mobileDataEnabled;
        state.activityIn = mobileDataEnabled && cb.mActivityIn;
        state.activityOut = mobileDataEnabled && cb.mActivityOut;
        state.expandedAccessibilityClassName = Switch.class.getName();

        if (cb.mAirplaneModeEnabled && cb.mQsTypeIcon != TelephonyIcons.ICON_CWF) {
            state.state = Tile.STATE_INACTIVE;
            state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable);
            state.secondaryLabel = r.getString(R.string.status_bar_airplane);
        } else if (cb.mNoDefaultNetwork) {
            if (cb.mNoNetworksAvailable || !mSignalCallback.mWifiInfo.mEnabled) {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_unavailable);
                state.secondaryLabel = r.getString(R.string.quick_settings_networks_unavailable);
            } else {
                state.icon = ResourceIcon.get(R.drawable.ic_qs_no_internet_available);
                state.secondaryLabel = r.getString(R.string.quick_settings_networks_available);
            }
        } else {
            state.icon = new SignalIcon(cb.mMobileSignalIconId);
            state.secondaryLabel = appendMobileDataType(cb.mDataSubscriptionName,
                    getMobileDataContentName(cb));
        }

        state.contentDescription = state.label;
        if (state.state == Tile.STATE_INACTIVE) {
            // This information is appended later by converting the Tile.STATE_INACTIVE state.
            state.stateDescription = "";
        } else {
            state.stateDescription = state.secondaryLabel;
        }
        if (DEBUG) {
            Log.d(TAG, "handleUpdateCellularState: " + "SignalState = " + state.toString());
        }
    }

    private void handleUpdateEthernetState(SignalState state, Object arg) {
        EthernetCallbackInfo cb = (EthernetCallbackInfo) arg;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateEthernetState: " + "EthernetCallbackInfo = " + cb.toString());
        }
        final Resources r = mContext.getResources();
        state.label = r.getString(R.string.quick_settings_internet_label);
        state.state = Tile.STATE_ACTIVE;
        state.icon = ResourceIcon.get(cb.mEthernetSignalIconId);
        state.secondaryLabel = cb.mEthernetContentDescription;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateEthernetState: " + "SignalState = " + state.toString());
        }
    }

    private CharSequence appendMobileDataType(CharSequence current, CharSequence dataType) {
        if (TextUtils.isEmpty(dataType)) {
            return Html.fromHtml((current == null ? "" : current.toString()), 0);
        }
        if (TextUtils.isEmpty(current)) {
            return Html.fromHtml((dataType == null ? "" : dataType.toString()), 0);
        }
        String concat = mContext.getString(R.string.mobile_carrier_text_format, current, dataType);
        return Html.fromHtml(concat, 0);
    }

    private CharSequence getMobileDataContentName(CellularCallbackInfo cb) {
        if (cb.mRoaming && !TextUtils.isEmpty(cb.mDataContentDescription)) {
            String roaming = mContext.getString(R.string.data_connection_roaming);
            String dataDescription =
                    cb.mDataContentDescription == null ? ""
                            : cb.mDataContentDescription.toString();
            return mContext.getString(R.string.mobile_data_text_format, roaming, dataDescription);
        }
        if (cb.mRoaming) {
            return mContext.getString(R.string.data_connection_roaming);
        }
        return cb.mDataContentDescription;
    }

    private static class SignalIcon extends Icon {
        private final int mState;
        SignalIcon(int state) {
            mState = state;
        }
        public int getState() {
            return mState;
        }

        @Override
        public Drawable getDrawable(Context context) {
            SignalDrawable d = new SignalDrawable(context);
            d.setLevel(getState());
            return d;
        }
    }

    /**
     * Dumps the state of this tile along with its name.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(this.getClass().getSimpleName() + ":");
        pw.print("    "); pw.println(getState().toString());
        pw.print("    "); pw.println("mLastTileState=" + mLastTileState);
        pw.print("    "); pw.println("mSignalCallback=" + mSignalCallback.toString());
    }
}
