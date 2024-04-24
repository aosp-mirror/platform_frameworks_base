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

import android.annotation.NonNull;
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
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.dialog.InternetDialogManager;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.connectivity.AccessPointController;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiIcons;
import com.android.systemui.statusbar.connectivity.WifiIndicators;

import java.io.PrintWriter;

import javax.inject.Inject;

/** Quick settings tile: Internet **/
public class InternetTile extends QSTileImpl<QSTile.BooleanState> {

    public static final String TILE_SPEC = "internet";

    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);
    private static final int LAST_STATE_UNKNOWN = -1;
    private static final int LAST_STATE_CELLULAR = 0;
    private static final int LAST_STATE_WIFI = 1;
    private static final int LAST_STATE_ETHERNET = 2;

    protected final NetworkController mController;
    private final AccessPointController mAccessPointController;
    private final DataUsageController mDataController;
    // The last updated tile state, 0: mobile, 1: wifi, 2: ethernet.
    private int mLastTileState = LAST_STATE_UNKNOWN;

    protected final InternetSignalCallback mSignalCallback = new InternetSignalCallback();
    private final InternetDialogManager mInternetDialogManager;
    final Handler mHandler;

    @Inject
    public InternetTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            NetworkController networkController,
            AccessPointController accessPointController,
            InternetDialogManager internetDialogManager
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mInternetDialogManager = internetDialogManager;
        mHandler = mainHandler;
        mController = networkController;
        mAccessPointController = accessPointController;
        mDataController = mController.getMobileDataController();
        mController.observe(getLifecycle(), mSignalCallback);
    }

    @Override
    public BooleanState newTileState() {
        BooleanState s = new BooleanState();
        s.forceExpandIcon = true;
        return s;
    }

    @Override
    public Intent getLongClickIntent() {
        return WIFI_SETTINGS;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        mHandler.post(() -> mInternetDialogManager.create(true,
                mAccessPointController.canConfigMobileData(),
                mAccessPointController.canConfigWifi(), expandable));
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

    @Nullable
    private CharSequence getSecondaryLabel(boolean isTransient, @Nullable String statusLabel) {
        return isTransient
                ? mContext.getString(R.string.quick_settings_wifi_secondary_label_transient)
                : statusLabel;
    }

    @Nullable
    private static String removeDoubleQuotes(@Nullable String string) {
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
        @Nullable
        String mEthernetContentDescription;

        public void copyTo(EthernetCallbackInfo ethernetCallbackInfo) {
            if (ethernetCallbackInfo == null) {
                throw new IllegalArgumentException();
            }
            ethernetCallbackInfo.mConnected = this.mConnected;
            ethernetCallbackInfo.mEthernetSignalIconId = this.mEthernetSignalIconId;
            ethernetCallbackInfo.mEthernetContentDescription = this.mEthernetContentDescription;
        }

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
        @Nullable
        String mSsid;
        @Nullable
        String mWifiSignalContentDescription;
        boolean mIsTransient;
        @Nullable
        public String mStatusLabel;
        boolean mNoDefaultNetwork;
        boolean mNoValidatedNetwork;
        boolean mNoNetworksAvailable;

        public void copyTo(WifiCallbackInfo wifiCallbackInfo) {
            if (wifiCallbackInfo == null) {
                throw new IllegalArgumentException();
            }
            wifiCallbackInfo.mAirplaneModeEnabled = this.mAirplaneModeEnabled;
            wifiCallbackInfo.mEnabled = this.mEnabled;
            wifiCallbackInfo.mConnected = this.mConnected;
            wifiCallbackInfo.mWifiSignalIconId = this.mWifiSignalIconId;
            wifiCallbackInfo.mSsid = this.mSsid;
            wifiCallbackInfo.mWifiSignalContentDescription = this.mWifiSignalContentDescription;
            wifiCallbackInfo.mIsTransient = this.mIsTransient;
            wifiCallbackInfo.mStatusLabel = this.mStatusLabel;
            wifiCallbackInfo.mNoDefaultNetwork = this.mNoDefaultNetwork;
            wifiCallbackInfo.mNoValidatedNetwork = this.mNoValidatedNetwork;
            wifiCallbackInfo.mNoNetworksAvailable = this.mNoNetworksAvailable;
        }

        @Override
        public String toString() {
            return new StringBuilder("WifiCallbackInfo[")
                    .append("mAirplaneModeEnabled=").append(mAirplaneModeEnabled)
                    .append(",mEnabled=").append(mEnabled)
                    .append(",mConnected=").append(mConnected)
                    .append(",mWifiSignalIconId=").append(mWifiSignalIconId)
                    .append(",mSsid=").append(mSsid)
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
        @Nullable
        CharSequence mDataSubscriptionName;
        @Nullable
        CharSequence mDataContentDescription;
        int mMobileSignalIconId;
        int mQsTypeIcon;
        boolean mNoSim;
        boolean mRoaming;
        boolean mMultipleSubs;
        boolean mNoDefaultNetwork;
        boolean mNoValidatedNetwork;
        boolean mNoNetworksAvailable;

        public void copyTo(CellularCallbackInfo cellularCallbackInfo) {
            if (cellularCallbackInfo == null) {
                throw new IllegalArgumentException();
            }
            cellularCallbackInfo.mAirplaneModeEnabled = this.mAirplaneModeEnabled;
            cellularCallbackInfo.mDataSubscriptionName = this.mDataSubscriptionName;
            cellularCallbackInfo.mDataContentDescription = this.mDataContentDescription;
            cellularCallbackInfo.mMobileSignalIconId = this.mMobileSignalIconId;
            cellularCallbackInfo.mQsTypeIcon = this.mQsTypeIcon;
            cellularCallbackInfo.mNoSim = this.mNoSim;
            cellularCallbackInfo.mRoaming = this.mRoaming;
            cellularCallbackInfo.mMultipleSubs = this.mMultipleSubs;
            cellularCallbackInfo.mNoDefaultNetwork = this.mNoDefaultNetwork;
            cellularCallbackInfo.mNoValidatedNetwork = this.mNoValidatedNetwork;
            cellularCallbackInfo.mNoNetworksAvailable = this.mNoNetworksAvailable;
        }

        @Override
        public String toString() {
            return new StringBuilder("CellularCallbackInfo[")
                .append("mAirplaneModeEnabled=").append(mAirplaneModeEnabled)
                .append(",mDataSubscriptionName=").append(mDataSubscriptionName)
                .append(",mDataContentDescription=").append(mDataContentDescription)
                .append(",mMobileSignalIconId=").append(mMobileSignalIconId)
                .append(",mQsTypeIcon=").append(mQsTypeIcon)
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
        @GuardedBy("mWifiInfo")
        final WifiCallbackInfo mWifiInfo = new WifiCallbackInfo();
        @GuardedBy("mCellularInfo")
        final CellularCallbackInfo mCellularInfo = new CellularCallbackInfo();
        @GuardedBy("mEthernetInfo")
        final EthernetCallbackInfo mEthernetInfo = new EthernetCallbackInfo();


        @Override
        public void setWifiIndicators(@NonNull WifiIndicators indicators) {
            if (DEBUG) {
                Log.d(TAG, "setWifiIndicators: " + indicators);
            }
            synchronized (mWifiInfo) {
                mWifiInfo.mEnabled = indicators.enabled;
                mWifiInfo.mSsid = indicators.description;
                mWifiInfo.mIsTransient = indicators.isTransient;
                mWifiInfo.mStatusLabel = indicators.statusLabel;
                if (indicators.qsIcon != null) {
                    mWifiInfo.mConnected = indicators.qsIcon.visible;
                    mWifiInfo.mWifiSignalIconId = indicators.qsIcon.icon;
                    mWifiInfo.mWifiSignalContentDescription = indicators.qsIcon.contentDescription;
                } else {
                    mWifiInfo.mConnected = false;
                    mWifiInfo.mWifiSignalIconId = 0;
                    mWifiInfo.mWifiSignalContentDescription = null;
                }
            }
            if (indicators.qsIcon != null) {
                refreshState(mWifiInfo);
            }
        }

        @Override
        public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
            if (DEBUG) {
                Log.d(TAG, "setMobileDataIndicators: " + indicators);
            }
            if (indicators.qsIcon == null) {
                // Not data sim, don't display.
                return;
            }
            synchronized (mCellularInfo) {
                mCellularInfo.mDataSubscriptionName = indicators.qsDescription == null
                    ? mController.getMobileDataNetworkName() : indicators.qsDescription;
                mCellularInfo.mDataContentDescription = indicators.qsDescription != null
                    ? indicators.typeContentDescriptionHtml : null;
                mCellularInfo.mMobileSignalIconId = indicators.qsIcon.icon;
                mCellularInfo.mQsTypeIcon = indicators.qsType;
                mCellularInfo.mRoaming = indicators.roaming;
                mCellularInfo.mMultipleSubs = mController.getNumberSubscriptions() > 1;
            }
            refreshState(mCellularInfo);
        }

        @Override
        public void setEthernetIndicators(@NonNull IconState icon) {
            if (DEBUG) {
                Log.d(TAG, "setEthernetIndicators: "
                        + "icon = " + (icon == null ? "" :  icon.toString()));
            }
            synchronized (mEthernetInfo) {
                mEthernetInfo.mConnected = icon.visible;
                mEthernetInfo.mEthernetSignalIconId = icon.icon;
                mEthernetInfo.mEthernetContentDescription = icon.contentDescription;
            }
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
            synchronized (mCellularInfo) {
                mCellularInfo.mNoSim = show;
                if (mCellularInfo.mNoSim) {
                    // Make sure signal gets cleared out when no sims.
                    mCellularInfo.mMobileSignalIconId = 0;
                    mCellularInfo.mQsTypeIcon = 0;
                }
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
            synchronized (mCellularInfo) {
                mCellularInfo.mAirplaneModeEnabled = icon.visible;
            }
            synchronized (mWifiInfo) {
                mWifiInfo.mAirplaneModeEnabled = icon.visible;
            }
            if (!mSignalCallback.mEthernetInfo.mConnected) {
                // Always use mWifiInfo to refresh the Internet Tile if airplane mode is enabled,
                // because Internet Tile will show different information depending on whether WiFi
                // is enabled or not.
                if (mWifiInfo.mAirplaneModeEnabled) {
                    refreshState(mWifiInfo);
                // If airplane mode is disabled, we will use mWifiInfo to refresh the Internet Tile
                // if WiFi is currently connected to avoid any icon flickering.
                } else if (mWifiInfo.mEnabled && (mWifiInfo.mWifiSignalIconId > 0)
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
            synchronized (mCellularInfo) {
                mCellularInfo.mNoDefaultNetwork = noDefaultNetwork;
                mCellularInfo.mNoValidatedNetwork = noValidatedNetwork;
                mCellularInfo.mNoNetworksAvailable = noNetworksAvailable;
            }
            synchronized (mWifiInfo) {
                mWifiInfo.mNoDefaultNetwork = noDefaultNetwork;
                mWifiInfo.mNoValidatedNetwork = noValidatedNetwork;
                mWifiInfo.mNoNetworksAvailable = noNetworksAvailable;
            }
            if (!noDefaultNetwork) {
                return;
            }
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
    protected void handleUpdateState(BooleanState state, Object arg) {
        mQSLogger.logInternetTileUpdate(
                getTileSpec(), mLastTileState, arg == null ? "null" : arg.toString());
        if (arg instanceof CellularCallbackInfo) {
            mLastTileState = LAST_STATE_CELLULAR;
            CellularCallbackInfo cb = (CellularCallbackInfo) arg;
            CellularCallbackInfo cellularInfo = new CellularCallbackInfo();
            synchronized (cb) {
                cb.copyTo(cellularInfo);
            }
            handleUpdateCellularState(state, cellularInfo);
        } else if (arg instanceof WifiCallbackInfo) {
            mLastTileState = LAST_STATE_WIFI;
            WifiCallbackInfo cb = (WifiCallbackInfo) arg;
            WifiCallbackInfo wifiInfo = new WifiCallbackInfo();
            synchronized (cb) {
                cb.copyTo(wifiInfo);
            }
            handleUpdateWifiState(state, wifiInfo);
        } else if (arg instanceof EthernetCallbackInfo) {
            mLastTileState = LAST_STATE_ETHERNET;
            EthernetCallbackInfo cb = (EthernetCallbackInfo) arg;
            EthernetCallbackInfo ethernetInfo = new EthernetCallbackInfo();
            synchronized (cb) {
                cb.copyTo(ethernetInfo);
            }
            handleUpdateEthernetState(state, ethernetInfo);
        } else {
            // handleUpdateState will be triggered when user expands the QuickSetting panel with
            // arg = null, in this case the last updated CellularCallbackInfo or WifiCallbackInfo
            // should be used to refresh the tile.
            if (mLastTileState == LAST_STATE_CELLULAR) {
                CellularCallbackInfo cellularInfo = new CellularCallbackInfo();
                synchronized (mSignalCallback.mCellularInfo) {
                    mSignalCallback.mCellularInfo.copyTo(cellularInfo);
                }
                handleUpdateCellularState(state, cellularInfo);
            } else if (mLastTileState == LAST_STATE_WIFI) {
                WifiCallbackInfo wifiInfo = new WifiCallbackInfo();
                synchronized (mSignalCallback.mWifiInfo) {
                    mSignalCallback.mWifiInfo.copyTo(wifiInfo);
                }
                handleUpdateWifiState(state, wifiInfo);
            } else if (mLastTileState == LAST_STATE_ETHERNET) {
                EthernetCallbackInfo ethernetInfo = new EthernetCallbackInfo();
                synchronized (mSignalCallback.mEthernetInfo) {
                    mSignalCallback.mEthernetInfo.copyTo(ethernetInfo);
                }
                handleUpdateEthernetState(state, ethernetInfo);
            }
        }
    }

    private void handleUpdateWifiState(BooleanState state, Object arg) {
        WifiCallbackInfo cb = (WifiCallbackInfo) arg;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateWifiState: " + "WifiCallbackInfo = " + cb.toString());
        }
        boolean wifiConnected = cb.mEnabled && (cb.mWifiSignalIconId > 0) && (cb.mSsid != null);
        boolean wifiNotConnected = (cb.mWifiSignalIconId > 0) && (cb.mSsid == null);
        state.secondaryLabel = getSecondaryLabel(cb.mIsTransient, removeDoubleQuotes(cb.mSsid));
        state.state = Tile.STATE_ACTIVE;
        state.dualTarget = true;
        state.value = cb.mEnabled;
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
            Log.d(TAG, "handleUpdateWifiState: " + "BooleanState = " + state.toString());
        }
    }

    private void handleUpdateCellularState(BooleanState state, Object arg) {
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
            Log.d(TAG, "handleUpdateCellularState: " + "BooleanState = " + state.toString());
        }
    }

    private void handleUpdateEthernetState(BooleanState state, Object arg) {
        EthernetCallbackInfo cb = (EthernetCallbackInfo) arg;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateEthernetState: " + "EthernetCallbackInfo = " + cb.toString());
        }
        if (!cb.mConnected) {
            return;
        }
        final Resources r = mContext.getResources();
        state.label = r.getString(R.string.quick_settings_internet_label);
        state.state = Tile.STATE_ACTIVE;
        state.icon = ResourceIcon.get(cb.mEthernetSignalIconId);
        state.secondaryLabel = cb.mEthernetContentDescription;
        if (DEBUG) {
            Log.d(TAG, "handleUpdateEthernetState: " + "BooleanState = " + state.toString());
        }
    }

    private CharSequence appendMobileDataType(
            @Nullable CharSequence current, @Nullable CharSequence dataType) {
        if (TextUtils.isEmpty(dataType)) {
            return Html.fromHtml((current == null ? "" : current.toString()), 0);
        }
        if (TextUtils.isEmpty(current)) {
            return Html.fromHtml((dataType == null ? "" : dataType.toString()), 0);
        }
        String concat = mContext.getString(R.string.mobile_carrier_text_format, current, dataType);
        return Html.fromHtml(concat, 0);
    }

    @Nullable
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
        @NonNull
        public Drawable getDrawable(Context context) {
            SignalDrawable d = new SignalDrawable(context);
            d.setLevel(getState());
            return d;
        }
        @Override
        public String toString() {
            return String.format("SignalIcon[mState=0x%08x]", mState);
        }
    }

    /**
     * Dumps the state of this tile along with its name.
     */
    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(this.getClass().getSimpleName() + ":");
        pw.print("    "); pw.println(getState().toString());
        pw.print("    "); pw.println("mLastTileState=" + mLastTileState);
        pw.print("    "); pw.println("mSignalCallback=" + mSignalCallback.toString());
    }

    // For testing usage only.
    protected int getLastTileState() {
        return mLastTileState;
    }
}
