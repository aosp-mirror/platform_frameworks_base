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

import static com.android.systemui.Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA;

import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.telephony.SubscriptionManager;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile.SignalState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/** Quick settings tile: Cellular **/
public class CellularTile extends QSTileImpl<SignalState> {
    private static final String ENABLE_SETTINGS_DATA_PLAN = "enable.settings.data.plan";

    private final NetworkController mController;
    private final DataUsageController mDataController;
    private final KeyguardStateController mKeyguard;
    private final CellSignalCallback mSignalCallback = new CellSignalCallback();

    @Inject
    public CellularTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            NetworkController networkController,
            KeyguardStateController keyguardStateController

    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = networkController;
        mKeyguard = keyguardStateController;
        mDataController = mController.getMobileDataController();
        mController.observe(getLifecycle(), mSignalCallback);
    }

    @Override
    public SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public QSIconView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    public Intent getLongClickIntent() {
        if (getState().state == Tile.STATE_UNAVAILABLE) {
            return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        }
        return getCellularSettingIntent();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (getState().state == Tile.STATE_UNAVAILABLE) {
            return;
        }
        if (mDataController.isMobileDataEnabled()) {
            maybeShowDisableDialog();
        } else {
            mDataController.setMobileDataEnabled(true);
        }
    }

    private void maybeShowDisableDialog() {
        if (Prefs.getBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA, false)) {
            // Directly turn off mobile data if the user has seen the dialog before.
            mDataController.setMobileDataEnabled(false);
            return;
        }
        String carrierName = mController.getMobileDataNetworkName();
        boolean isInService = mController.isMobileDataNetworkInService();
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = mContext.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        AlertDialog dialog = new Builder(mContext)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(mContext.getString(R.string.mobile_data_disable_message, carrierName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        com.android.internal.R.string.alert_windows_notification_turn_off_action,
                        (d, w) -> {
                            mDataController.setMobileDataEnabled(false);
                            Prefs.putBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA, true);
                        })
                .create();
        dialog.getWindow().setType(LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.registerDismissListener(dialog);
        SystemUIDialog.setWindowOnTop(dialog, mKeyguard.isShowing());
        dialog.show();
    }

    @Override
    protected void handleSecondaryClick(@Nullable View view) {
        handleLongClick(view);
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
        state.label = r.getString(R.string.mobile_data);
        boolean mobileDataEnabled = mDataController.isMobileDataSupported()
                && mDataController.isMobileDataEnabled();
        state.value = mobileDataEnabled;
        state.activityIn = mobileDataEnabled && cb.activityIn;
        state.activityOut = mobileDataEnabled && cb.activityOut;
        state.expandedAccessibilityClassName = Switch.class.getName();
        if (cb.noSim) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_no_sim);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_swap_vert);
        }

        if (cb.noSim) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = r.getString(R.string.keyguard_missing_sim_message_short);
        } else if (cb.airplaneModeEnabled) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = r.getString(R.string.status_bar_airplane);
        } else if (mobileDataEnabled) {
            state.state = Tile.STATE_ACTIVE;
            state.secondaryLabel = appendMobileDataType(
                    // Only show carrier name if there are more than 1 subscription
                    cb.multipleSubs ? cb.dataSubscriptionName : "",
                    getMobileDataContentName(cb));
        } else {
            state.state = Tile.STATE_INACTIVE;
            state.secondaryLabel = r.getString(R.string.cell_data_off);
        }

        state.contentDescription = state.label;
        if (state.state == Tile.STATE_INACTIVE) {
            // This information is appended later by converting the Tile.STATE_INACTIVE state.
            state.stateDescription = "";
        } else {
            state.stateDescription = state.secondaryLabel;
        }
    }

    private CharSequence appendMobileDataType(CharSequence current, CharSequence dataType) {
        if (TextUtils.isEmpty(dataType)) {
            return Html.fromHtml(current.toString(), 0);
        }
        if (TextUtils.isEmpty(current)) {
            return Html.fromHtml(dataType.toString(), 0);
        }
        String concat = mContext.getString(R.string.mobile_carrier_text_format, current, dataType);
        return Html.fromHtml(concat, 0);
    }

    private CharSequence getMobileDataContentName(CallbackInfo cb) {
        if (cb.roaming && !TextUtils.isEmpty(cb.dataContentDescription)) {
            String roaming = mContext.getString(R.string.data_connection_roaming);
            String dataDescription = cb.dataContentDescription.toString();
            return mContext.getString(R.string.mobile_data_text_format, roaming, dataDescription);
        }
        if (cb.roaming) {
            return mContext.getString(R.string.data_connection_roaming);
        }
        return cb.dataContentDescription;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CELLULAR;
    }

    @Override
    public boolean isAvailable() {
        return mController.hasMobileDataFeature()
            && mHost.getUserContext().getUserId() == UserHandle.USER_SYSTEM;
    }

    private static final class CallbackInfo {
        boolean airplaneModeEnabled;
        @Nullable
        CharSequence dataSubscriptionName;
        @Nullable
        CharSequence dataContentDescription;
        boolean activityIn;
        boolean activityOut;
        boolean noSim;
        boolean roaming;
        boolean multipleSubs;
    }

    private final class CellSignalCallback implements SignalCallback {
        private final CallbackInfo mInfo = new CallbackInfo();

        @Override
        public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
            if (indicators.qsIcon == null) {
                // Not data sim, don't display.
                return;
            }
            mInfo.dataSubscriptionName = mController.getMobileDataNetworkName();
            mInfo.dataContentDescription = indicators.qsDescription != null
                    ? indicators.typeContentDescriptionHtml : null;
            mInfo.activityIn = indicators.activityIn;
            mInfo.activityOut = indicators.activityOut;
            mInfo.roaming = indicators.roaming;
            mInfo.multipleSubs = mController.getNumberSubscriptions() > 1;
            refreshState(mInfo);
        }

        @Override
        public void setNoSims(boolean show, boolean simDetected) {
            mInfo.noSim = show;
            refreshState(mInfo);
        }

        @Override
        public void setIsAirplaneMode(@NonNull IconState icon) {
            mInfo.airplaneModeEnabled = icon.visible;
            refreshState(mInfo);
        }
    }

    static Intent getCellularSettingIntent() {
        Intent intent = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
        int dataSub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.putExtra(Settings.EXTRA_SUB_ID,
                    SubscriptionManager.getDefaultDataSubscriptionId());
        }
        return intent;
    }
}
