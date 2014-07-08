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
import android.content.res.Resources;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPoint;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;

/** Quick settings tile: Wifi **/
public class WifiTile extends QSTile<QSTile.SignalState> {
    private static final Intent WIFI_SETTINGS = new Intent(Settings.ACTION_WIFI_SETTINGS);
    private static final int MAX_ITEMS = 4; // TODO temporary visual restriction

    private final NetworkController mController;
    private final WifiDetailAdapter mDetailAdapter;

    public WifiTile(Host host) {
        super(host);
        mController = host.getNetworkController();
        mDetailAdapter = new WifiDetailAdapter();
    }

    @Override
    public boolean supportsDualTargets() {
        return true;
    }

    @Override
    protected SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addNetworkSignalChangedCallback(mCallback);
            mController.addAccessPointCallback(mDetailAdapter);
            mController.scanForAccessPoints();
        } else {
            mController.removeNetworkSignalChangedCallback(mCallback);
            mController.removeAccessPointCallback(mDetailAdapter);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public QSTileView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    protected void handleClick() {
        mController.setWifiEnabled(!mState.enabled);
    }

    @Override
    protected void handleSecondaryClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        state.visible = true;
        if (DEBUG) Log.d(TAG, "handleUpdateState arg=" + arg);
        if (arg == null) return;
        CallbackInfo cb = (CallbackInfo) arg;

        boolean wifiConnected = cb.enabled && (cb.wifiSignalIconId > 0) && (cb.enabledDesc != null);
        boolean wifiNotConnected = (cb.wifiSignalIconId > 0) && (cb.enabledDesc == null);
        boolean enabledChanging = state.enabled != cb.enabled;
        if (enabledChanging) {
            mDetailAdapter.postUpdateItems();
            fireToggleStateChanged(cb.enabled);
        }
        state.enabled = cb.enabled;
        state.connected = wifiConnected;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;
        state.filter = true;
        final String signalContentDescription;
        final Resources r = mContext.getResources();
        if (wifiConnected) {
            state.iconId = cb.wifiSignalIconId;
            state.label = removeDoubleQuotes(cb.enabledDesc);
            signalContentDescription = cb.wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            state.iconId = R.drawable.ic_qs_wifi_0;
            state.label = r.getString(R.string.quick_settings_wifi_label);
            signalContentDescription = r.getString(R.string.accessibility_no_wifi);
        } else {
            state.iconId = R.drawable.ic_qs_wifi_no_network;
            state.label = r.getString(R.string.quick_settings_wifi_label);
            signalContentDescription = r.getString(R.string.accessibility_wifi_off);
        }
        state.contentDescription = mContext.getString(
                R.string.accessibility_quick_settings_wifi,
                signalContentDescription,
                state.connected ? state.label : "");
    }

    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private static final class CallbackInfo {
        boolean enabled;
        int wifiSignalIconId;
        String enabledDesc;
        boolean activityIn;
        boolean activityOut;
        String wifiSignalContentDescription;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                .append("enabled=").append(enabled)
                .append(",wifiSignalIconId=").append(wifiSignalIconId)
                .append(",enabledDesc=").append(enabledDesc)
                .append(",activityIn=").append(activityIn)
                .append(",activityOut=").append(activityOut)
                .append(",wifiSignalContentDescription=").append(wifiSignalContentDescription)
                .append(']').toString();
        }
    }

    private final NetworkSignalChangedCallback mCallback = new NetworkSignalChangedCallback() {
        @Override
        public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description) {
            if (DEBUG) Log.d(TAG, "onWifiSignalChanged enabled=" + enabled);
            final CallbackInfo info = new CallbackInfo();
            info.enabled = enabled;
            info.wifiSignalIconId = wifiSignalIconId;
            info.enabledDesc = description;
            info.activityIn = activityIn;
            info.activityOut = activityOut;
            info.wifiSignalContentDescription = wifiSignalContentDescriptionId;
            refreshState(info);
        }

        @Override
        public void onMobileDataSignalChanged(boolean enabled,
                int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description, boolean noSim) {
            // noop
        }

        @Override
        public void onAirplaneModeChanged(boolean enabled) {
            // noop
        }

        @Override
        public void onMobileDataEnabled(boolean enabled) {
            // noop
        }
    };

    private final class WifiDetailAdapter implements DetailAdapter,
            NetworkController.AccessPointCallback {

        private LinearLayout mItems;
        private AccessPoint[] mAccessPoints;

        @Override
        public int getTitle() {
            return R.string.quick_settings_wifi_label;
        }

        public Intent getSettingsIntent() {
            return WIFI_SETTINGS;
        }

        @Override
        public Boolean getToggleState() {
            return mState.enabled;
        }

        @Override
        public void setToggleState(boolean state) {
            if (DEBUG) Log.d(TAG, "setToggleState " + state);
            mController.setWifiEnabled(state);
            showDetail(false);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            if (convertView != null) return convertView;
            mItems = new LinearLayout(context);
            mItems.setOrientation(LinearLayout.VERTICAL);
            updateItems();
            return mItems;
        }

        @Override
        public void onAccessPointsChanged(final AccessPoint[] accessPoints) {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAccessPoints = accessPoints;
                    updateItems();
                }
            });
        }

        public void postUpdateItems() {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateItems();
                }
            });
        }

        private void updateItems() {
            if (mItems == null) return;
            mItems.removeAllViews();
            if (mAccessPoints == null || mAccessPoints.length == 0 || !mState.enabled) return;
            for (int i = 0; i < mAccessPoints.length; i++) {
                final AccessPoint ap = mAccessPoints[i];
                if (ap == null) continue;
                final View item = LayoutInflater.from(mContext).inflate(R.layout.qs_detail_item,
                        mItems, false);
                final ImageView iv = (ImageView) item.findViewById(android.R.id.icon);
                iv.setImageResource(ap.iconId);
                final TextView title = (TextView) item.findViewById(android.R.id.title);
                title.setText(ap.ssid);
                final TextView summary = (TextView) item.findViewById(android.R.id.summary);
                if (ap.isConnected) {
                    item.setMinimumHeight(mContext.getResources()
                            .getDimensionPixelSize(R.dimen.qs_detail_item_height_twoline));
                    summary.setText(R.string.quick_settings_connected);
                } else {
                    summary.setVisibility(View.GONE);
                }
                item.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!ap.isConnected) {
                            mController.connect(ap);
                        }
                        showDetail(false);
                    }
                });
                mItems.addView(item);
                if (mItems.getChildCount() == MAX_ITEMS) break;
            }
        }
    };
}
