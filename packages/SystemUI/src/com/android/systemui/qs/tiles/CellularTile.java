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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.qs.DataUsageGraph;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.DataUsageInfo;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;

import java.text.DecimalFormat;

/** Quick settings tile: Cellular **/
public class CellularTile extends QSTile<QSTile.SignalState> {
    private static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    private final NetworkController mController;
    private final CellularDetailAdapter mDetailAdapter;

    public CellularTile(Host host) {
        super(host);
        mController = host.getNetworkController();
        mDetailAdapter = new CellularDetailAdapter();
    }

    @Override
    protected SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addNetworkSignalChangedCallback(mCallback);
        } else {
            mController.removeNetworkSignalChangedCallback(mCallback);
        }
    }

    @Override
    public QSTileView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    protected void handleClick() {
        if (mController.isMobileDataSupported()) {
            showDetail(true);
        } else {
            mHost.startSettingsActivity(CELLULAR_SETTINGS);
        }
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        state.visible = mController.hasMobileDataFeature();
        if (!state.visible) return;
        final CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) return;

        final Resources r = mContext.getResources();
        state.iconId = cb.noSim ? R.drawable.stat_sys_no_sim
                : !cb.enabled || cb.airplaneModeEnabled ? R.drawable.ic_qs_signal_disabled
                : cb.mobileSignalIconId > 0 ? cb.mobileSignalIconId
                : R.drawable.ic_qs_signal_no_signal;
        state.autoMirrorDrawable = !cb.noSim;
        state.overlayIconId = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiConnected
                ? cb.dataTypeIconId
                : 0;
        state.filter = state.iconId != R.drawable.stat_sys_no_sim;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;

        state.label = cb.enabled
                ? removeTrailingPeriod(cb.enabledDesc)
                : r.getString(R.string.quick_settings_rssi_emergency_only);

        final String signalContentDesc = cb.enabled && (cb.mobileSignalIconId > 0)
                ? cb.signalContentDescription
                : r.getString(R.string.accessibility_no_signal);
        final String dataContentDesc = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiEnabled
                ? cb.dataContentDescription
                : r.getString(R.string.accessibility_no_data);
        state.contentDescription = r.getString(
                R.string.accessibility_quick_settings_mobile,
                signalContentDesc, dataContentDesc,
                state.label);
    }

    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }

    private static final class CallbackInfo {
        boolean enabled;
        boolean wifiEnabled;
        boolean wifiConnected;
        boolean airplaneModeEnabled;
        int mobileSignalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
        boolean activityIn;
        boolean activityOut;
        String enabledDesc;
        boolean noSim;
    }

    private final NetworkSignalChangedCallback mCallback = new NetworkSignalChangedCallback() {
        private boolean mWifiEnabled;
        private boolean mWifiConnected;
        private boolean mAirplaneModeEnabled;

        @Override
        public void onWifiSignalChanged(boolean enabled, boolean connected, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description) {
            mWifiEnabled = enabled;
            mWifiConnected = connected;
        }

        @Override
        public void onMobileDataSignalChanged(boolean enabled,
                int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description, boolean noSim) {
            final CallbackInfo info = new CallbackInfo();  // TODO pool?
            info.enabled = enabled;
            info.wifiEnabled = mWifiEnabled;
            info.wifiConnected = mWifiConnected;
            info.airplaneModeEnabled = mAirplaneModeEnabled;
            info.mobileSignalIconId = mobileSignalIconId;
            info.signalContentDescription = mobileSignalContentDescriptionId;
            info.dataTypeIconId = dataTypeIconId;
            info.dataContentDescription = dataTypeContentDescriptionId;
            info.activityIn = activityIn;
            info.activityOut = activityOut;
            info.enabledDesc = description;
            info.noSim = noSim;
            refreshState(info);
        }

        @Override
        public void onAirplaneModeChanged(boolean enabled) {
            mAirplaneModeEnabled = enabled;
        }

        public void onMobileDataEnabled(boolean enabled) {
            mDetailAdapter.setMobileDataEnabled(enabled);
        }
    };

    private final class CellularDetailAdapter implements DetailAdapter {
        private static final double KB = 1024;
        private static final double MB = 1024 * KB;
        private static final double GB = 1024 * MB;

        private final DecimalFormat FORMAT = new DecimalFormat("#.##");

        @Override
        public int getTitle() {
            return R.string.quick_settings_cellular_detail_title;
        }

        @Override
        public Boolean getToggleState() {
            return mController.isMobileDataSupported() ? mController.isMobileDataEnabled() : null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CELLULAR_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            mController.setMobileDataEnabled(state);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(mContext)
                    .inflate(R.layout.data_usage, parent, false);
            final DataUsageInfo info = mController.getDataUsageInfo();
            if (info == null) return v;
            final Resources res = mContext.getResources();
            final int titleId;
            final long bytes;
            int usageColor = R.color.system_accent_color;
            final String top;
            String bottom = null;
            if (info.usageLevel < info.warningLevel || info.limitLevel <= 0) {
                // under warning, or no limit
                titleId = R.string.quick_settings_cellular_detail_data_usage;
                bytes = info.usageLevel;
                top = res.getString(R.string.quick_settings_cellular_detail_data_warning,
                        formatBytes(info.warningLevel));
            } else if (info.usageLevel <= info.limitLevel) {
                // over warning, under limit
                titleId = R.string.quick_settings_cellular_detail_remaining_data;
                bytes = info.limitLevel - info.usageLevel;
                top = res.getString(R.string.quick_settings_cellular_detail_data_used,
                        formatBytes(info.usageLevel));
                bottom = res.getString(R.string.quick_settings_cellular_detail_data_limit,
                        formatBytes(info.limitLevel));
            } else {
                // over limit
                titleId = R.string.quick_settings_cellular_detail_over_limit;
                bytes = info.usageLevel - info.limitLevel;
                top = res.getString(R.string.quick_settings_cellular_detail_data_used,
                        formatBytes(info.usageLevel));
                bottom = res.getString(R.string.quick_settings_cellular_detail_data_limit,
                        formatBytes(info.limitLevel));
                usageColor = R.color.system_warning_color;
            }

            final TextView title = (TextView) v.findViewById(android.R.id.title);
            title.setText(titleId);
            final TextView usage = (TextView) v.findViewById(R.id.usage_text);
            usage.setText(formatBytes(bytes));
            usage.setTextColor(res.getColor(usageColor));
            final DataUsageGraph graph = (DataUsageGraph) v.findViewById(R.id.usage_graph);
            graph.setLevels(info.limitLevel, info.warningLevel, info.usageLevel);
            final TextView carrier = (TextView) v.findViewById(R.id.usage_carrier_text);
            carrier.setText(info.carrier);
            final TextView period = (TextView) v.findViewById(R.id.usage_period_text);
            period.setText(info.period);
            final TextView infoTop = (TextView) v.findViewById(R.id.usage_info_top_text);
            infoTop.setVisibility(top != null ? View.VISIBLE : View.GONE);
            infoTop.setText(top);
            final TextView infoBottom = (TextView) v.findViewById(R.id.usage_info_bottom_text);
            infoBottom.setVisibility(bottom != null ? View.VISIBLE : View.GONE);
            infoBottom.setText(bottom);
            return v;
        }

        public void setMobileDataEnabled(boolean enabled) {
            fireToggleStateChanged(enabled);
        }

        private String formatBytes(long bytes) {
            final long b = Math.abs(bytes);
            double val;
            String suffix;
            if (b > 100 * MB) {
                val = b / GB;
                suffix = "GB";
            } else if (b > 100 * KB) {
                val = b / MB;
                suffix = "MB";
            } else {
                val = b / KB;
                suffix = "KB";
            }
            return FORMAT.format(val * (bytes < 0 ? -1 : 1)) + " " + suffix;
        }
    }
}
