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
import android.service.quicksettings.Tile;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.qs.CellTileView;
import com.android.systemui.qs.CellTileView.SignalIcon;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;

/** Quick settings tile: Cellular **/
public class CellularTile extends QSTileImpl<SignalState> {
    static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    private final NetworkController mController;
    private final DataUsageController mDataController;
    private final CellularDetailAdapter mDetailAdapter;

    private final CellSignalCallback mSignalCallback = new CellSignalCallback();
    private final ActivityStarter mActivityStarter;

    public CellularTile(QSHost host) {
        super(host);
        mController = Dependency.get(NetworkController.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mDataController = mController.getMobileDataController();
        mDetailAdapter = new CellularDetailAdapter();
    }

    @Override
    public SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addCallback(mSignalCallback);
        } else {
            mController.removeCallback(mSignalCallback);
        }
    }

    @Override
    public QSIconView createTileView(Context context) {
        return new CellTileView(context);
    }

    @Override
    public Intent getLongClickIntent() {
        return CELLULAR_SETTINGS;
    }

    @Override
    protected void handleClick() {
        mDataController.setMobileDataEnabled(!mDataController.isMobileDataEnabled());
    }

    @Override
    protected void handleSecondaryClick() {
        if (mDataController.isMobileDataSupported()) {
            showDetail(true);
        } else {
            mActivityStarter.postStartActivityDismissingKeyguard(CELLULAR_SETTINGS, 0);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_cellular_detail_title);
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) {
            cb = mSignalCallback.mInfo;
        }

        final Resources r = mContext.getResources();
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;
        state.isOverlayIconWide = cb.isDataTypeIconWide;
        state.overlayIconId = cb.dataTypeIconId;

        state.label = r.getString(R.string.mobile_data);

        final String signalContentDesc = cb.enabled && (cb.mobileSignalIconId > 0)
                ? cb.signalContentDescription
                : r.getString(R.string.accessibility_no_signal);
        if (cb.noSim) {
            state.contentDescription = state.label;
        } else {
            state.contentDescription = signalContentDesc + ", " + state.label;
        }

        state.expandedAccessibilityClassName = Switch.class.getName();
        state.value = mDataController.isMobileDataSupported()
                && mDataController.isMobileDataEnabled();

        state.icon = new SignalIcon(cb.mobileSignalIconId);
        state.state = Tile.STATE_ACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CELLULAR;
    }

    @Override
    public boolean isAvailable() {
        return mController.hasMobileDataFeature();
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
        boolean airplaneModeEnabled;
        int mobileSignalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
        boolean activityIn;
        boolean activityOut;
        String enabledDesc;
        boolean noSim;
        boolean isDataTypeIconWide;
        boolean roaming;
    }

    private final class CellSignalCallback implements SignalCallback {
        private final CallbackInfo mInfo = new CallbackInfo();
        @Override
        public void setWifiIndicators(boolean enabled, IconState statusIcon, IconState qsIcon,
                boolean activityIn, boolean activityOut, String description, boolean isTransient) {
            mInfo.wifiEnabled = enabled;
            refreshState(mInfo);
        }

        @Override
        public void setMobileDataIndicators(IconState statusIcon, IconState qsIcon, int statusType,
                int qsType, boolean activityIn, boolean activityOut, String typeContentDescription,
                String description, boolean isWide, int subId, boolean roaming) {
            if (qsIcon == null) {
                // Not data sim, don't display.
                return;
            }
            mInfo.enabled = qsIcon.visible;
            mInfo.mobileSignalIconId = qsIcon.icon;
            mInfo.signalContentDescription = qsIcon.contentDescription;
            mInfo.dataTypeIconId = qsType;
            mInfo.dataContentDescription = typeContentDescription;
            mInfo.activityIn = activityIn;
            mInfo.activityOut = activityOut;
            mInfo.enabledDesc = description;
            mInfo.isDataTypeIconWide = qsType != 0 && isWide;
            mInfo.roaming = roaming;
            refreshState(mInfo);
        }

        @Override
        public void setNoSims(boolean show) {
            mInfo.noSim = show;
            if (mInfo.noSim) {
                // Make sure signal gets cleared out when no sims.
                mInfo.mobileSignalIconId = 0;
                mInfo.dataTypeIconId = 0;
                // Show a No SIMs description to avoid emergency calls message.
                mInfo.enabled = true;
                mInfo.enabledDesc = mContext.getString(
                        R.string.keyguard_missing_sim_message_short);
                mInfo.signalContentDescription = mInfo.enabledDesc;
            }
            refreshState(mInfo);
        }

        @Override
        public void setIsAirplaneMode(IconState icon) {
            mInfo.airplaneModeEnabled = icon.visible;
            refreshState(mInfo);
        }

        @Override
        public void setMobileDataEnabled(boolean enabled) {
            mDetailAdapter.setMobileDataEnabled(enabled);
        }
    };

    private final class CellularDetailAdapter implements DetailAdapter {

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_cellular_detail_title);
        }

        @Override
        public Boolean getToggleState() {
            return mDataController.isMobileDataSupported()
                    ? mDataController.isMobileDataEnabled()
                    : null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CELLULAR_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            MetricsLogger.action(mContext, MetricsEvent.QS_CELLULAR_TOGGLE, state);
            mDataController.setMobileDataEnabled(state);
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_DATAUSAGEDETAIL;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            final DataUsageDetailView v = (DataUsageDetailView) (convertView != null
                    ? convertView
                    : LayoutInflater.from(mContext).inflate(R.layout.data_usage, parent, false));
            final DataUsageController.DataUsageInfo info = mDataController.getDataUsageInfo();
            if (info == null) return v;
            v.bind(info);
            v.findViewById(R.id.roaming_text).setVisibility(mSignalCallback.mInfo.roaming
                    ? View.VISIBLE : View.INVISIBLE);
            return v;
        }

        public void setMobileDataEnabled(boolean enabled) {
            fireToggleStateChanged(enabled);
        }
    }
}
