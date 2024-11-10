/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server;

import static com.android.tradeinmode.flags.Flags.enableTradeInMode;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.annotation.RequiresPermission;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.ITradeInMode;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Slog;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;


public final class TradeInModeService extends SystemService {
    private static final String TAG = "TradeInModeService";

    private static final String TIM_PROP = "persist.adb.tradeinmode";

    private static final int TIM_STATE_UNSET = 0;

    // adbd_tradeinmode was stopped.
    private static final int TIM_STATE_DISABLED = -1;

    // adbd_tradeinmode has started.
    private static final int TIM_STATE_FOYER = 1;

    // Full non-root adb granted; factory reset is guaranteed.
    private static final int TIM_STATE_EVALUATION_MODE = 2;

    // This file contains a single integer counter of how many boot attempts
    // have been made since entering evaluation mode.
    private static final String WIPE_INDICATOR_FILE = "/metadata/tradeinmode/wipe";

    private final Context mContext;
    private TradeInMode mTradeInMode;

    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback = null;

    private AccountManager mAccountManager;
    private OnAccountsUpdateListener mAccountsListener = null;

    public TradeInModeService(Context context) {
        super(context);

        mContext = context;
    }

    @Override
    public void onStart() {
        if (!enableTradeInMode()) {
            return;
        }

        mTradeInMode = new TradeInMode();
        publishBinderService("tradeinmode", mTradeInMode);
    }

    @Override
    public void onBootPhase(@BootPhase int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            final int state = getTradeInModeState();

            if (isAdbEnabled() && !isDebuggable() && !isDeviceSetup()
                    && state == TIM_STATE_DISABLED) {
                // If we fail to start trade-in mode, the persist property may linger
                // past reboot. If we detect this, disable ADB and clear TIM state.
                Slog.i(TAG, "Resetting trade-in mode state.");
                SystemProperties.set(TIM_PROP, "");

                final ContentResolver cr = mContext.getContentResolver();
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0);
            } else if (state == TIM_STATE_FOYER) {
                // If zygote crashed or we rebooted, and TIM is still enabled, make
                // sure it's allowed to be enabled. If it is, we need to re-add our
                // setup completion observer.
                if (isDeviceSetup()) {
                    stopTradeInMode();
                } else {
                    watchForSetupCompletion();
                    watchForNetworkChange();
                    watchForAccountsCreated();
                }
            }
        }
    }

    private final class TradeInMode extends ITradeInMode.Stub {
        @Override
        @RequiresPermission(android.Manifest.permission.ENTER_TRADE_IN_MODE)
        public boolean start() {
            mContext.enforceCallingOrSelfPermission("android.permission.ENTER_TRADE_IN_MODE",
                                                    "Cannot enter trade-in mode foyer");
            final int state = getTradeInModeState();
            if (state == TIM_STATE_FOYER) {
                return true;
            }

            if (state != TIM_STATE_UNSET) {
                Slog.e(TAG, "Cannot enter trade-in mode in state: " + state);
                return false;
            }

            if (isDeviceSetup()) {
                Slog.i(TAG, "Not starting trade-in mode, device is setup.");
                return false;
            }
            if (SystemProperties.getInt("ro.debuggable", 0) == 1) {
                // We don't want to force adbd into TIM on debug builds.
                Slog.e(TAG, "Not starting trade-in mode, device is debuggable.");
                return false;
            }
            if (isAdbEnabled()) {
                Slog.e(TAG, "Not starting trade-in mode, adb is already enabled.");
                return false;
            }

            final long callingId = Binder.clearCallingIdentity();
            try {
                startTradeInMode();
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
            return true;
        }

        @Override
        @RequiresPermission(android.Manifest.permission.ENTER_TRADE_IN_MODE)
        public boolean enterEvaluationMode() {
            mContext.enforceCallingOrSelfPermission("android.permission.ENTER_TRADE_IN_MODE",
                                                    "Cannot enter trade-in evaluation mode");
            final int state = getTradeInModeState();
            if (state != TIM_STATE_FOYER) {
                Slog.e(TAG, "Cannot enter evaluation mode in state: " + state);
                return false;
            }
            if (isFrpActive()) {
                Slog.e(TAG, "Cannot enter evaluation mode, FRP lock is present.");
                return false;
            }

            try (FileWriter fw = new FileWriter(WIPE_INDICATOR_FILE,
                                                StandardCharsets.US_ASCII)) {
                fw.write("0");
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write " + WIPE_INDICATOR_FILE, e);
                return false;
            }

            final long callingId = Binder.clearCallingIdentity();
            try {
                removeNetworkWatch();
                removeAccountsWatch();
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }

            SystemProperties.set(TIM_PROP, Integer.toString(TIM_STATE_EVALUATION_MODE));
            SystemProperties.set("ctl.restart", "adbd");
            return true;
        }

        @Override
        @RequiresPermission(android.Manifest.permission.ENTER_TRADE_IN_MODE)
        public boolean isEvaluationModeAllowed() {
            mContext.enforceCallingOrSelfPermission("android.permission.ENTER_TRADE_IN_MODE",
                                        "Cannot test for trade-in evaluation mode allowed");
            return !isFrpActive();
        }
    }

    private void startTradeInMode() {
        Slog.i(TAG, "Enabling trade-in mode.");

        SystemProperties.set(TIM_PROP, Integer.toString(TIM_STATE_FOYER));

        final ContentResolver cr = mContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);

        watchForSetupCompletion();
        watchForNetworkChange();
        watchForAccountsCreated();
    }

    private void stopTradeInMode() {
        Slog.i(TAG, "Stopping trade-in mode.");

        SystemProperties.set(TIM_PROP, Integer.toString(TIM_STATE_DISABLED));

        removeNetworkWatch();
        removeAccountsWatch();

        final ContentResolver cr = mContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 0);
    }

    private int getTradeInModeState() {
        return SystemProperties.getInt(TIM_PROP, TIM_STATE_UNSET);
    }

    private boolean isDebuggable() {
        return SystemProperties.getInt("ro.debuggable", 0) == 1;
    }

    private boolean isAdbEnabled() {
        final ContentResolver cr = mContext.getContentResolver();
        return Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1;
    }

    private boolean isFrpActive() {
        try {
            PersistentDataBlockManager pdb =
                    mContext.getSystemService(PersistentDataBlockManager.class);
            if (pdb == null) {
                return false;
            }
            return pdb.isFactoryResetProtectionActive();
        } catch (Exception e) {
            Slog.e(TAG, "Could not read PDB", e);
            return false;
        }
    }

    // This returns true if the device has progressed far enough into Setup Wizard that it no
    // longer makes sense to enable trade-in mode. As a last stop, we check the SUW completion
    // bits.
    private boolean isDeviceSetup() {
        final ContentResolver cr = mContext.getContentResolver();
        try {
            if (Settings.Secure.getIntForUser(cr, Settings.Secure.USER_SETUP_COMPLETE, 0) != 0) {
                return true;
            }
        } catch (SettingNotFoundException e) {
            Slog.e(TAG, "Could not find USER_SETUP_COMPLETE setting", e);
        }

        if (Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            return true;
        }
        return false;
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            if (getTradeInModeState() == TIM_STATE_FOYER && isDeviceSetup()) {
                stopTradeInMode();
            }
        }
    }

    private void watchForSetupCompletion() {
        final Uri userSetupComplete = Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE);
        final Uri deviceProvisioned = Settings.Global.getUriFor(
                Settings.Global.DEVICE_PROVISIONED);
        final ContentResolver cr = mContext.getContentResolver();
        final SettingsObserver observer = new SettingsObserver();

        cr.registerContentObserver(userSetupComplete, false, observer);
        cr.registerContentObserver(deviceProvisioned, false, observer);
    }


    private void watchForNetworkChange() {
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build();

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                stopTradeInMode();
            }
        };

        mConnectivityManager.registerNetworkCallback(networkRequest, mNetworkCallback);
    }

    private void removeNetworkWatch() {
        if (mNetworkCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
    }

    private void watchForAccountsCreated() {
        mAccountManager = mContext.getSystemService(AccountManager.class);
        mAccountsListener = new OnAccountsUpdateListener() {
            @Override
            public void onAccountsUpdated(Account[] accounts) {
                stopTradeInMode();
            }
        };
        mAccountManager.addOnAccountsUpdatedListener(mAccountsListener, null, false);
    }

    private void removeAccountsWatch() {
        if (mAccountsListener != null) {
            mAccountManager.removeOnAccountsUpdatedListener(mAccountsListener);
            mAccountsListener = null;
        }
    }
}
