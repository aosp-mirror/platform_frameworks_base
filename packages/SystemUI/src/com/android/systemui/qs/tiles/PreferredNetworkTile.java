/*
 * Copyright (C) 2022 Nameless-AOSP Project
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

import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.CDMA;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.EVDO;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.GSM;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.LTE;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.NR;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.RAF_TD_SCDMA;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.RAF_UNKNOWN;
import static com.android.systemui.qs.tiles.PreferredNetworkTile.RadioConstants.WCDMA;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class PreferredNetworkTile extends SecureQSTile<State> {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_preferred_network);

    private final TelephonyManager mTelephonyManager;

    @Inject
    public PreferredNetworkTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            KeyguardStateController keyguardStateController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger, keyguardStateController);
        mTelephonyManager = TelephonyManager.from(host.getContext());
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick(@Nullable View view, boolean keyguardShowing) {
        if (checkKeyguard(view, keyguardShowing)) {
            return;
        }
        final int mode = getPreferredNetworkMode();
        final int newMode = TelephonyManagerConstants.getTargetMode(mode);
        if (newMode == -1) return;
        final int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        mTelephonyManager.createForSubscriptionId(subId).setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                getRafFromNetworkType(newMode));
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        Intent intent = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
        int dataSub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.putExtra(Settings.EXTRA_SUB_ID,
                    SubscriptionManager.getDefaultDataSubscriptionId());
        }
        return intent;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = mIcon;
        state.label = mContext.getResources().getString(R.string.quick_settings_preferred_network_label);
        final int mode = getPreferredNetworkMode();
        final int newMode = TelephonyManagerConstants.getTargetMode(mode);
        state.state = newMode == -1 ? Tile.STATE_UNAVAILABLE : Tile.STATE_ACTIVE;
        state.secondaryLabel = newMode == -1 ? mContext.getResources().getString(R.string.quick_settings_preferred_network_unsupported)
                : (TelephonyManagerConstants.is5gMode(mode) ?
                mContext.getResources().getString(R.string.quick_settings_preferred_network_nr)
                : mContext.getResources().getString(R.string.quick_settings_preferred_network_lte));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_preferred_network_label);
    }

    @Override
    public int getMetricsCategory() {
       return MetricsEvent.XTENDED;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    private int getPreferredNetworkMode() {
        final int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        return getNetworkTypeFromRaf(
                (int) mTelephonyManager.createForSubscriptionId(subId).getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER));
    }

    private static int getNetworkTypeFromRaf(int raf) {
        raf = getAdjustedRaf(raf);

        switch (raf) {
            case (GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_WCDMA_PREF;
            case GSM:
                return TelephonyManagerConstants.NETWORK_MODE_GSM_ONLY;
            case WCDMA:
                return TelephonyManagerConstants.NETWORK_MODE_WCDMA_ONLY;
            case (CDMA | EVDO):
                return TelephonyManagerConstants.NETWORK_MODE_CDMA_EVDO;
            case (LTE | CDMA | EVDO):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_CDMA_EVDO;
            case (LTE | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_GSM_WCDMA;
            case (LTE | CDMA | EVDO | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA;
            case LTE:
                return TelephonyManagerConstants.NETWORK_MODE_LTE_ONLY;
            case (LTE | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_WCDMA;
            case CDMA:
                return TelephonyManagerConstants.NETWORK_MODE_CDMA_NO_EVDO;
            case EVDO:
                return TelephonyManagerConstants.NETWORK_MODE_EVDO_NO_CDMA;
            case (GSM | WCDMA | CDMA | EVDO):
                return TelephonyManagerConstants.NETWORK_MODE_GLOBAL;
            case RAF_TD_SCDMA:
                return TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_ONLY;
            case (RAF_TD_SCDMA | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_WCDMA;
            case (LTE | RAF_TD_SCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA;
            case (RAF_TD_SCDMA | GSM):
                return TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_GSM;
            case (LTE | RAF_TD_SCDMA | GSM):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_GSM;
            case (RAF_TD_SCDMA | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA;
            case (LTE | RAF_TD_SCDMA | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA;
            case (LTE | RAF_TD_SCDMA | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA;
            case (RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
            case (LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
            case (NR):
                return TelephonyManagerConstants.NETWORK_MODE_NR_ONLY;
            case (NR | LTE):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE;
            case (NR | LTE | CDMA | EVDO):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO;
            case (NR | LTE | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_GSM_WCDMA;
            case (NR | LTE | CDMA | EVDO | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA;
            case (NR | LTE | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_WCDMA;
            case (NR | LTE | RAF_TD_SCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA;
            case (NR | LTE | RAF_TD_SCDMA | GSM):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM;
            case (NR | LTE | RAF_TD_SCDMA | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA;
            case (NR | LTE | RAF_TD_SCDMA | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA;
            case (NR | LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                return TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
            default:
                return TelephonyManagerConstants.NETWORK_MODE_UNKNOWN;
        }
    }

    private static long getRafFromNetworkType(int type) {
        switch (type) {
            case TelephonyManagerConstants.NETWORK_MODE_WCDMA_PREF:
                return GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_GSM_ONLY:
                return GSM;
            case TelephonyManagerConstants.NETWORK_MODE_WCDMA_ONLY:
                return WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_GSM_UMTS:
                return GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_CDMA_EVDO:
                return CDMA | EVDO;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_CDMA_EVDO:
                return LTE | CDMA | EVDO;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_GSM_WCDMA:
                return LTE | GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                return LTE | CDMA | EVDO | GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_ONLY:
                return LTE;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_WCDMA:
                return LTE | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_CDMA_NO_EVDO:
                return CDMA;
            case TelephonyManagerConstants.NETWORK_MODE_EVDO_NO_CDMA:
                return EVDO;
            case TelephonyManagerConstants.NETWORK_MODE_GLOBAL:
                return GSM | WCDMA | CDMA | EVDO;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_ONLY:
                return RAF_TD_SCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_WCDMA:
                return RAF_TD_SCDMA | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA:
                return LTE | RAF_TD_SCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_GSM:
                return RAF_TD_SCDMA | GSM;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_GSM:
                return LTE | RAF_TD_SCDMA | GSM;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA:
                return RAF_TD_SCDMA | GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA:
                return LTE | RAF_TD_SCDMA | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
                return LTE | RAF_TD_SCDMA | GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
            case TelephonyManagerConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_ONLY):
                return NR;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE):
                return NR | LTE;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO):
                return NR | LTE | CDMA | EVDO;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_GSM_WCDMA):
                return NR | LTE | GSM | WCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA):
                return NR | LTE | CDMA | EVDO | GSM | WCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_WCDMA):
                return NR | LTE | WCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA):
                return NR | LTE | RAF_TD_SCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM):
                return NR | LTE | RAF_TD_SCDMA | GSM;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA):
                return NR | LTE | RAF_TD_SCDMA | WCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA):
                return NR | LTE | RAF_TD_SCDMA | GSM | WCDMA;
            case (TelephonyManagerConstants.NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA):
                return NR | LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
            default:
                return RAF_UNKNOWN;
        }
    }

    private static int getAdjustedRaf(int raf) {
        raf = ((GSM & raf) > 0) ? (GSM | raf) : raf;
        raf = ((WCDMA & raf) > 0) ? (WCDMA | raf) : raf;
        raf = ((CDMA & raf) > 0) ? (CDMA | raf) : raf;
        raf = ((EVDO & raf) > 0) ? (EVDO | raf) : raf;
        raf = ((LTE & raf) > 0) ? (LTE | raf) : raf;
        raf = ((NR & raf) > 0) ? (NR | raf) : raf;
        return raf;
    }

    static class TelephonyManagerConstants {

        // Network modes are in turn copied from RILConstants
        // with one difference: NETWORK_MODE_CDMA is named NETWORK_MODE_CDMA_EVDO

        public static final int NETWORK_MODE_UNKNOWN = -1;

        /**
         * GSM, WCDMA (WCDMA preferred)
         */
        public static final int NETWORK_MODE_WCDMA_PREF = 0;

        /**
         * GSM only
         */
        public static final int NETWORK_MODE_GSM_ONLY = 1;

        /**
         * WCDMA only
         */
        public static final int NETWORK_MODE_WCDMA_ONLY = 2;

        /**
         * GSM, WCDMA (auto mode, according to PRL)
         */
        public static final int NETWORK_MODE_GSM_UMTS = 3;

        /**
         * CDMA and EvDo (auto mode, according to PRL)
         * this is NETWORK_MODE_CDMA in RILConstants.java
         */
        public static final int NETWORK_MODE_CDMA_EVDO = 4;

        /**
         * CDMA only
         */
        public static final int NETWORK_MODE_CDMA_NO_EVDO = 5;

        /**
         * EvDo only
         */
        public static final int NETWORK_MODE_EVDO_NO_CDMA = 6;

        /**
         * GSM, WCDMA, CDMA, and EvDo (auto mode, according to PRL)
         */
        public static final int NETWORK_MODE_GLOBAL = 7;

        /**
         * LTE, CDMA and EvDo
         */
        public static final int NETWORK_MODE_LTE_CDMA_EVDO = 8;

        /**
         * LTE, GSM and WCDMA
         */
        public static final int NETWORK_MODE_LTE_GSM_WCDMA = 9;

        /**
         * LTE, CDMA, EvDo, GSM, and WCDMA
         */
        public static final int NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA = 10;

        /**
         * LTE only mode.
         */
        public static final int NETWORK_MODE_LTE_ONLY = 11;

        /**
         * LTE and WCDMA
         */
        public static final int NETWORK_MODE_LTE_WCDMA = 12;

        /**
         * TD-SCDMA only
         */
        public static final int NETWORK_MODE_TDSCDMA_ONLY = 13;

        /**
         * TD-SCDMA and WCDMA
         */
        public static final int NETWORK_MODE_TDSCDMA_WCDMA = 14;

        /**
         * LTE and TD-SCDMA
         */
        public static final int NETWORK_MODE_LTE_TDSCDMA = 15;

        /**
         * TD-SCDMA and GSM
         */
        public static final int NETWORK_MODE_TDSCDMA_GSM = 16;

        /**
         * TD-SCDMA, GSM and LTE
         */
        public static final int NETWORK_MODE_LTE_TDSCDMA_GSM = 17;

        /**
         * TD-SCDMA, GSM and WCDMA
         */
        public static final int NETWORK_MODE_TDSCDMA_GSM_WCDMA = 18;

        /**
         * LTE, TD-SCDMA and WCDMA
         */
        public static final int NETWORK_MODE_LTE_TDSCDMA_WCDMA = 19;

        /**
         * LTE, TD-SCDMA, GSM, and WCDMA
         */
        public static final int NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA = 20;

        /**
         * TD-SCDMA, CDMA, EVDO, GSM and WCDMA
         */
        public static final int NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 21;

        /**
         * LTE, TDCSDMA, CDMA, EVDO, GSM and WCDMA
         */
        public static final int NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 22;

        /**
         * NR 5G only mode
         */
        public static final int NETWORK_MODE_NR_ONLY = 23;

        /**
         * NR 5G, LTE
         */
        public static final int NETWORK_MODE_NR_LTE = 24;

        /**
         * NR 5G, LTE, CDMA and EvDo
         */
        public static final int NETWORK_MODE_NR_LTE_CDMA_EVDO = 25;

        /**
         * NR 5G, LTE, GSM and WCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_GSM_WCDMA = 26;

        /**
         * NR 5G, LTE, CDMA, EvDo, GSM and WCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA = 27;

        /**
         * NR 5G, LTE and WCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_WCDMA = 28;

        /**
         * NR 5G, LTE and TDSCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_TDSCDMA = 29;

        /**
         * NR 5G, LTE, TD-SCDMA and GSM
         */
        public static final int NETWORK_MODE_NR_LTE_TDSCDMA_GSM = 30;

        /**
         * NR 5G, LTE, TD-SCDMA, WCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA = 31;

        /**
         * NR 5G, LTE, TD-SCDMA, GSM and WCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA = 32;

        /**
         * NR 5G, LTE, TD-SCDMA, CDMA, EVDO, GSM and WCDMA
         */
        public static final int NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA = 33;

        public static int getTargetMode(int mode) {
            switch (mode) {
                case NETWORK_MODE_LTE_CDMA_EVDO:
                    return NETWORK_MODE_NR_LTE_CDMA_EVDO;
                case NETWORK_MODE_LTE_GSM_WCDMA:
                    return NETWORK_MODE_NR_LTE_GSM_WCDMA;
                case NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                    return NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA;
                case NETWORK_MODE_LTE_ONLY:
                    return NETWORK_MODE_NR_LTE;
                case NETWORK_MODE_LTE_WCDMA:
                    return NETWORK_MODE_NR_LTE_WCDMA;
                case NETWORK_MODE_LTE_TDSCDMA:
                    return NETWORK_MODE_NR_LTE_TDSCDMA;
                case NETWORK_MODE_LTE_TDSCDMA_GSM:
                    return NETWORK_MODE_NR_LTE_TDSCDMA_GSM;
                case NETWORK_MODE_LTE_TDSCDMA_WCDMA:
                    return NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA;
                case NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
                    return NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA;
                case NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                    return NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
                case NETWORK_MODE_NR_ONLY:
                    return NETWORK_MODE_LTE_ONLY;
                case NETWORK_MODE_NR_LTE:
                    return NETWORK_MODE_LTE_ONLY;
                case NETWORK_MODE_NR_LTE_CDMA_EVDO:
                    return NETWORK_MODE_LTE_CDMA_EVDO;
                case NETWORK_MODE_NR_LTE_GSM_WCDMA:
                    return NETWORK_MODE_LTE_GSM_WCDMA;
                case NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA:
                    return NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA;
                case NETWORK_MODE_NR_LTE_WCDMA:
                    return NETWORK_MODE_LTE_WCDMA;
                case NETWORK_MODE_NR_LTE_TDSCDMA:
                    return NETWORK_MODE_LTE_TDSCDMA;
                case NETWORK_MODE_NR_LTE_TDSCDMA_GSM:
                    return NETWORK_MODE_LTE_TDSCDMA_GSM;
                case NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA:
                    return NETWORK_MODE_LTE_TDSCDMA_WCDMA;
                case NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA:
                    return NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA;
                case NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                    return NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
                default:
                    return -1;
            }
        }

        public static boolean is5gMode(int mode) {
            return mode == NETWORK_MODE_NR_ONLY ||
                    mode == NETWORK_MODE_NR_LTE ||
                    mode == NETWORK_MODE_NR_LTE_CDMA_EVDO ||
                    mode == NETWORK_MODE_NR_LTE_GSM_WCDMA ||
                    mode == NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA ||
                    mode == NETWORK_MODE_NR_LTE_WCDMA ||
                    mode == NETWORK_MODE_NR_LTE_TDSCDMA ||
                    mode == NETWORK_MODE_NR_LTE_TDSCDMA_GSM ||
                    mode == NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA ||
                    mode == NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA ||
                    mode == NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
        }
    }

    static class RadioConstants {
        // 2G
        public static final int RAF_UNKNOWN = (int) TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN;
        public static final int RAF_GSM = (int) TelephonyManager.NETWORK_TYPE_BITMASK_GSM;
        public static final int RAF_GPRS = (int) TelephonyManager.NETWORK_TYPE_BITMASK_GPRS;
        public static final int RAF_EDGE = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EDGE;
        public static final int RAF_IS95A = (int) TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
        public static final int RAF_IS95B = (int) TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
        public static final int RAF_1xRTT = (int) TelephonyManager.NETWORK_TYPE_BITMASK_1xRTT;
        // 3G
        public static final int RAF_EVDO_0 = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_0;
        public static final int RAF_EVDO_A = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_A;
        public static final int RAF_EVDO_B = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_B;
        public static final int RAF_EHRPD = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EHRPD;
        public static final int RAF_HSUPA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA;
        public static final int RAF_HSDPA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA;
        public static final int RAF_HSPA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSPA;
        public static final int RAF_HSPAP = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP;
        public static final int RAF_UMTS = (int) TelephonyManager.NETWORK_TYPE_BITMASK_UMTS;
        public static final int RAF_TD_SCDMA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA;
        // 4G
        public static final int RAF_LTE = (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE;
        public static final int RAF_LTE_CA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA;
        // 5G
        public static final int RAF_NR = (int) TelephonyManager.NETWORK_TYPE_BITMASK_NR;

        // Grouping of RAFs
        // 2G
        public static final int GSM = RAF_GSM | RAF_GPRS | RAF_EDGE;
        public static final int CDMA = RAF_IS95A | RAF_IS95B | RAF_1xRTT;
        // 3G
        public static final int EVDO = RAF_EVDO_0 | RAF_EVDO_A | RAF_EVDO_B | RAF_EHRPD;
        public static final int HS = RAF_HSUPA | RAF_HSDPA | RAF_HSPA | RAF_HSPAP;
        public static final int WCDMA = HS | RAF_UMTS;
        // 4G
        public static final int LTE = RAF_LTE | RAF_LTE_CA;
        // 5G
        public static final int NR = RAF_NR;
    }
}
