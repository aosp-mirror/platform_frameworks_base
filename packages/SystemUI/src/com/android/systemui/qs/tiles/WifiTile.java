/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.wifi.AccessPoint;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.AlphaControlledSignalTileView;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointController;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkController.WifiIndicators;
import com.android.systemui.statusbar.policy.WifiIcons;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;

import javax.inject.Inject;

/** Quick settings tile: Wifi **/
public class WifiTile extends QSTileImpl<SignalState> {
    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);

    protected final NetworkController mController;
    private final AccessPointController mWifiController;
    private final WifiDetailAdapter mDetailAdapter;
    private final QSTile.SignalState mStateBeforeClick = newTileState();

    protected final WifiSignalCallback mSignalCallback = new WifiSignalCallback();
    private boolean mExpectDisabled;

    @Inject
    public WifiTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            NetworkController networkController,
            AccessPointController accessPointController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = networkController;
        mWifiController = accessPointController;
        mDetailAdapter = (WifiDetailAdapter) createDetailAdapter();
        mController.observe(getLifecycle(), mSignalCallback);
        mStateBeforeClick.spec = "wifi";
    }

    @Override
    public SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public void setDetailListening(boolean listening) {
        if (listening) {
            mWifiController.addAccessPointCallback(mDetailAdapter);
        } else {
            mWifiController.removeAccessPointCallback(mDetailAdapter);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected DetailAdapter createDetailAdapter() {
        return new WifiDetailAdapter();
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
        // Secondary clicks are header clicks, just toggle.
        mState.copyTo(mStateBeforeClick);
        boolean wifiEnabled = mState.value;
        // Immediately enter transient state when turning on wifi.
        refreshState(wifiEnabled ? null : ARG_SHOW_TRANSIENT_ENABLING);
        mController.setWifiEnabled(!wifiEnabled);
        mExpectDisabled = wifiEnabled;
        if (mExpectDisabled) {
            mHandler.postDelayed(() -> {
                if (mExpectDisabled) {
                    mExpectDisabled = false;
                    refreshState();
                }
            }, QSIconViewImpl.QS_ANIM_LENGTH);
        }
    }

    @Override
    protected void handleSecondaryClick(@Nullable View view) {
        if (!mWifiController.canConfigWifi()) {
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_WIFI_SETTINGS), 0);
            return;
        }
        showDetail(true);
        if (!mState.value) {
            mController.setWifiEnabled(true);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_wifi_label);
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        if (DEBUG) Log.d(TAG, "handleUpdateState arg=" + arg);
        final CallbackInfo cb = mSignalCallback.mInfo;
        if (mExpectDisabled) {
            if (cb.enabled) {
                return; // Ignore updates until disabled event occurs.
            } else {
                mExpectDisabled = false;
            }
        }
        boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;
        boolean wifiConnected = cb.enabled && (cb.wifiSignalIconId > 0)
                && (cb.ssid != null || cb.wifiSignalIconId != WifiIcons.QS_WIFI_NO_NETWORK);
        boolean wifiNotConnected = (cb.ssid == null)
                && (cb.wifiSignalIconId == WifiIcons.QS_WIFI_NO_NETWORK);
        boolean enabledChanging = state.value != cb.enabled;
        if (enabledChanging) {
            mDetailAdapter.setItemsVisible(cb.enabled);
            fireToggleStateChanged(cb.enabled);
        }
        if (state.slash == null) {
            state.slash = new SlashState();
            state.slash.rotation = 6;
        }
        state.slash.isSlashed = false;
        boolean isTransient = transientEnabling || cb.isTransient;
        state.secondaryLabel = getSecondaryLabel(isTransient, cb.statusLabel);
        state.state = Tile.STATE_ACTIVE;
        state.dualTarget = true;
        state.value = transientEnabling || cb.enabled;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;
        final StringBuffer minimalContentDescription = new StringBuffer();
        final StringBuffer minimalStateDescription = new StringBuffer();
        final Resources r = mContext.getResources();
        if (isTransient) {
            state.icon = ResourceIcon.get(
                    com.android.internal.R.drawable.ic_signal_wifi_transient_animation);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else if (!state.value) {
            state.slash.isSlashed = true;
            state.state = Tile.STATE_INACTIVE;
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_DISABLED);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else if (wifiConnected) {
            state.icon = ResourceIcon.get(cb.wifiSignalIconId);
            state.label = cb.ssid != null ? removeDoubleQuotes(cb.ssid) : getTileLabel();
        } else if (wifiNotConnected) {
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_NO_NETWORK);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else {
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_NO_NETWORK);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        }
        minimalContentDescription.append(
                mContext.getString(R.string.quick_settings_wifi_label)).append(",");
        if (state.value) {
            if (wifiConnected) {
                minimalStateDescription.append(cb.wifiSignalContentDescription);
                minimalContentDescription.append(removeDoubleQuotes(cb.ssid));
                if (!TextUtils.isEmpty(state.secondaryLabel)) {
                    minimalContentDescription.append(",").append(state.secondaryLabel);
                }
            }
        }
        state.stateDescription = minimalStateDescription.toString();
        state.contentDescription = minimalContentDescription.toString();
        state.dualLabelContentDescription = r.getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel());
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    private CharSequence getSecondaryLabel(boolean isTransient, String statusLabel) {
        return isTransient
                ? mContext.getString(R.string.quick_settings_wifi_secondary_label_transient)
                : statusLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_WIFI;
    }

    @Override
    protected boolean shouldAnnouncementBeDelayed() {
        return mStateBeforeClick.value == mState.value;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_wifi_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_wifi_changed_off);
        }
    }

    @Override
    public boolean isAvailable() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
    }

    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    protected static final class CallbackInfo {
        boolean enabled;
        boolean connected;
        int wifiSignalIconId;
        String ssid;
        boolean activityIn;
        boolean activityOut;
        String wifiSignalContentDescription;
        boolean isTransient;
        public String statusLabel;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                    .append("enabled=").append(enabled)
                    .append(",connected=").append(connected)
                    .append(",wifiSignalIconId=").append(wifiSignalIconId)
                    .append(",ssid=").append(ssid)
                    .append(",activityIn=").append(activityIn)
                    .append(",activityOut=").append(activityOut)
                    .append(",wifiSignalContentDescription=").append(wifiSignalContentDescription)
                    .append(",isTransient=").append(isTransient)
                    .append(']').toString();
        }
    }

    protected final class WifiSignalCallback implements SignalCallback {
        final CallbackInfo mInfo = new CallbackInfo();

        @Override
        public void setWifiIndicators(WifiIndicators indicators) {
            if (DEBUG) Log.d(TAG, "onWifiSignalChanged enabled=" + indicators.enabled);
            if (indicators.qsIcon == null) {
                return;
            }
            mInfo.enabled = indicators.enabled;
            mInfo.connected = indicators.qsIcon.visible;
            mInfo.wifiSignalIconId = indicators.qsIcon.icon;
            mInfo.ssid = indicators.description;
            mInfo.activityIn = indicators.activityIn;
            mInfo.activityOut = indicators.activityOut;
            mInfo.wifiSignalContentDescription = indicators.qsIcon.contentDescription;
            mInfo.isTransient = indicators.isTransient;
            mInfo.statusLabel = indicators.statusLabel;
            if (isShowingDetail()) {
                mDetailAdapter.updateItems();
            }
            refreshState();
        }
    }

    protected class WifiDetailAdapter implements DetailAdapter,
            NetworkController.AccessPointController.AccessPointCallback, QSDetailItems.Callback {

        private QSDetailItems mItems;
        private WifiEntry[] mAccessPoints;

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_wifi_label);
        }

        public Intent getSettingsIntent() {
            return WIFI_SETTINGS;
        }

        @Override
        public Boolean getToggleState() {
            return mState.value;
        }

        @Override
        public void setToggleState(boolean state) {
            if (DEBUG) Log.d(TAG, "setToggleState " + state);
            MetricsLogger.action(mContext, MetricsEvent.QS_WIFI_TOGGLE, state);
            mController.setWifiEnabled(state);
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_WIFI_DETAILS;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            if (DEBUG) Log.d(TAG, "createDetailView convertView=" + (convertView != null));
            mAccessPoints = null;
            mItems = QSDetailItems.convertOrInflate(context, convertView, parent);
            mItems.setTagSuffix("Wifi");
            mItems.setCallback(this);
            mWifiController.scanForAccessPoints(); // updates APs and items
            setItemsVisible(mState.value);
            return mItems;
        }

        @Override
        public void onAccessPointsChanged(final List<WifiEntry> accessPoints) {
            mAccessPoints = accessPoints.toArray(new WifiEntry[accessPoints.size()]);
            filterUnreachableAPs();

            updateItems();
        }

        /** Filter unreachable APs from mAccessPoints */
        private void filterUnreachableAPs() {
            int numReachable = 0;
            for (WifiEntry ap : mAccessPoints) {
                if (isWifiEntryReachable(ap)) numReachable++;
            }
            if (numReachable != mAccessPoints.length) {
                WifiEntry[] unfiltered = mAccessPoints;
                mAccessPoints = new WifiEntry[numReachable];
                int i = 0;
                for (WifiEntry ap : unfiltered) {
                    if (isWifiEntryReachable(ap)) mAccessPoints[i++] = ap;
                }
            }
        }

        @Override
        public void onSettingsActivityTriggered(Intent settingsIntent) {
            mActivityStarter.postStartActivityDismissingKeyguard(settingsIntent, 0);
        }

        @Override
        public void onDetailItemClick(Item item) {
            if (item == null || item.tag == null) return;
            final WifiEntry ap = (WifiEntry) item.tag;
            if (ap.getConnectedState() == WifiEntry.CONNECTED_STATE_DISCONNECTED) {
                if (mWifiController.connect(ap)) {
                    mHost.collapsePanels();
                }
            }
            showDetail(false);
        }

        @Override
        public void onDetailItemDisconnect(Item item) {
            // noop
        }

        public void setItemsVisible(boolean visible) {
            if (mItems == null) return;
            mItems.setItemsVisible(visible);
        }

        private void updateItems() {
            if (mItems == null) return;
            if ((mAccessPoints != null && mAccessPoints.length > 0)
                    || !mSignalCallback.mInfo.enabled) {
                fireScanStateChanged(false);
            } else {
                fireScanStateChanged(true);
            }

            // Wi-Fi is off
            if (!mSignalCallback.mInfo.enabled) {
                mItems.setEmptyState(WifiIcons.QS_WIFI_NO_NETWORK,
                        R.string.wifi_is_off);
                mItems.setItems(null);
                return;
            }

            // No available access points
            mItems.setEmptyState(WifiIcons.QS_WIFI_NO_NETWORK,
                    R.string.quick_settings_wifi_detail_empty_text);

            // Build the list
            Item[] items = null;
            if (mAccessPoints != null) {
                items = new Item[mAccessPoints.length];
                for (int i = 0; i < mAccessPoints.length; i++) {
                    final WifiEntry ap = mAccessPoints[i];
                    final Item item = new Item();
                    item.tag = ap;
                    item.iconResId = mWifiController.getIcon(ap);
                    item.line1 = ap.getSsid();
                    item.line2 = ap.getSummary();
                    item.icon2 = ap.getSecurity() != AccessPoint.SECURITY_NONE
                            ? R.drawable.qs_ic_wifi_lock
                            : -1;
                    items[i] = item;
                }
            }
            mItems.setItems(items);
        }
    }

    private static boolean isWifiEntryReachable(WifiEntry ap) {
        return ap.getLevel() != WifiEntry.WIFI_LEVEL_UNREACHABLE;
    }
}
