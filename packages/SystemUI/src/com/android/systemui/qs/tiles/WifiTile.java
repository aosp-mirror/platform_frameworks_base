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
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.wifi.AccessPoint;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.qs.AlphaControlledSignalTileView;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

import java.util.List;

/** Quick settings tile: Wifi **/
public class WifiTile extends QSTileImpl<SignalState> {
    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);

    protected final NetworkController mController;
    private final AccessPointController mWifiController;
    private final WifiDetailAdapter mDetailAdapter;
    private final QSTile.SignalState mStateBeforeClick = newTileState();

    protected final WifiSignalCallback mSignalCallback = new WifiSignalCallback();
    private final ActivityStarter mActivityStarter;

    public WifiTile(QSHost host) {
        super(host);
        mController = Dependency.get(NetworkController.class);
        mWifiController = mController.getAccessPointController();
        mDetailAdapter = (WifiDetailAdapter) createDetailAdapter();
        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    @Override
    public SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            mController.addCallback(mSignalCallback);
        } else {
            mController.removeCallback(mSignalCallback);
        }
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
    protected void handleClick() {
        // Secondary clicks are header clicks, just toggle.
        mState.copyTo(mStateBeforeClick);
        mController.setWifiEnabled(!mState.value);
    }

    @Override
    protected void handleSecondaryClick() {
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
        CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) {
            cb = mSignalCallback.mInfo;
        }

        boolean wifiConnected = cb.enabled && (cb.wifiSignalIconId > 0) && (cb.enabledDesc != null);
        boolean wifiNotConnected = (cb.wifiSignalIconId > 0) && (cb.enabledDesc == null);
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
        state.state = Tile.STATE_ACTIVE;
        state.dualTarget = true;
        state.value = cb.enabled;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;
        final StringBuffer minimalContentDescription = new StringBuffer();
        final Resources r = mContext.getResources();
        if (cb.isTransient) {
            state.icon = ResourceIcon.get(R.drawable.ic_signal_wifi_transient_animation);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else if (!state.value) {
            state.slash.isSlashed = true;
            state.state = Tile.STATE_INACTIVE;
            state.icon = ResourceIcon.get(R.drawable.ic_qs_wifi_disabled);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else if (wifiConnected) {
            state.icon = ResourceIcon.get(cb.wifiSignalIconId);
            state.label = removeDoubleQuotes(cb.enabledDesc);
        } else if (wifiNotConnected) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_wifi_disconnected);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_wifi_no_network);
            state.label = r.getString(R.string.quick_settings_wifi_label);
        }
        minimalContentDescription.append(
                mContext.getString(R.string.quick_settings_wifi_label)).append(",");
        if (state.value) {
            if (wifiConnected) {
                minimalContentDescription.append(cb.wifiSignalContentDescription).append(",");
                minimalContentDescription.append(removeDoubleQuotes(cb.enabledDesc));
            }
        }
        state.contentDescription = minimalContentDescription.toString();
        state.dualLabelContentDescription = r.getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel());
        state.expandedAccessibilityClassName = Switch.class.getName();
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
        String enabledDesc;
        boolean activityIn;
        boolean activityOut;
        String wifiSignalContentDescription;
        boolean isTransient;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                    .append("enabled=").append(enabled)
                    .append(",connected=").append(connected)
                    .append(",wifiSignalIconId=").append(wifiSignalIconId)
                    .append(",enabledDesc=").append(enabledDesc)
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
        public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
                boolean activityIn, boolean activityOut, String description, boolean isTransient) {
            if (DEBUG) Log.d(TAG, "onWifiSignalChanged enabled=" + enabled);
            mInfo.enabled = enabled;
            mInfo.connected = qsIcon.visible;
            mInfo.wifiSignalIconId = qsIcon.icon;
            mInfo.enabledDesc = description;
            mInfo.activityIn = activityIn;
            mInfo.activityOut = activityOut;
            mInfo.wifiSignalContentDescription = qsIcon.contentDescription;
            mInfo.isTransient = isTransient;
            if (isShowingDetail()) {
                mDetailAdapter.updateItems();
            }
            refreshState(mInfo);
        }
    }

    ;

    protected class WifiDetailAdapter implements DetailAdapter,
            NetworkController.AccessPointController.AccessPointCallback, QSDetailItems.Callback {

        private QSDetailItems mItems;
        private AccessPoint[] mAccessPoints;

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
        public void onAccessPointsChanged(final List<AccessPoint> accessPoints) {
            mAccessPoints = accessPoints.toArray(new AccessPoint[accessPoints.size()]);
            filterUnreachableAPs();

            updateItems();
        }

        /** Filter unreachable APs from mAccessPoints */
        private void filterUnreachableAPs() {
            int numReachable = 0;
            for (AccessPoint ap : mAccessPoints) {
                if (ap.isReachable()) numReachable++;
            }
            if (numReachable != mAccessPoints.length) {
                AccessPoint[] unfiltered = mAccessPoints;
                mAccessPoints = new AccessPoint[numReachable];
                int i = 0;
                for (AccessPoint ap : unfiltered) {
                    if (ap.isReachable()) mAccessPoints[i++] = ap;
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
            final AccessPoint ap = (AccessPoint) item.tag;
            if (!ap.isActive()) {
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
                mItems.setEmptyState(R.drawable.ic_qs_wifi_detail_empty,
                        R.string.wifi_is_off);
                mItems.setItems(null);
                return;
            }

            // No available access points
            mItems.setEmptyState(R.drawable.ic_qs_wifi_detail_empty,
                    R.string.quick_settings_wifi_detail_empty_text);

            // Build the list
            Item[] items = null;
            if (mAccessPoints != null) {
                items = new Item[mAccessPoints.length];
                for (int i = 0; i < mAccessPoints.length; i++) {
                    final AccessPoint ap = mAccessPoints[i];
                    final Item item = new Item();
                    item.tag = ap;
                    item.icon = mWifiController.getIcon(ap);
                    item.line1 = ap.getSsid();
                    item.line2 = ap.isActive() ? ap.getSummary() : null;
                    item.icon2 = ap.getSecurity() != AccessPoint.SECURITY_NONE
                            ? R.drawable.qs_ic_wifi_lock
                            : -1;
                    items[i] = item;
                }
            }
            mItems.setItems(items);
        }
    }
}
