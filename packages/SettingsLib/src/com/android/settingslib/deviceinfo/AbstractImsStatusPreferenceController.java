/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.RegistrationManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Preference controller for IMS status
 */
public abstract class AbstractImsStatusPreferenceController
        extends AbstractConnectivityPreferenceController {

    private static final String LOG_TAG = "AbstractImsPrefController";

    @VisibleForTesting
    static final String KEY_IMS_REGISTRATION_STATE = "ims_reg_state";

    private static final long MAX_THREAD_BLOCKING_TIME_MS = 2000;

    private static final String[] CONNECTIVITY_INTENTS = {
            BluetoothAdapter.ACTION_STATE_CHANGED,
            ConnectivityManager.CONNECTIVITY_ACTION,
            WifiManager.ACTION_LINK_CONFIGURATION_CHANGED,
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
    };

    private Preference mImsStatus;

    public AbstractImsStatusPreferenceController(Context context,
            Lifecycle lifecycle) {
        super(context, lifecycle);
    }

    @Override
    public boolean isAvailable() {
        final CarrierConfigManager configManager =
                mContext.getSystemService(CarrierConfigManager.class);
        final int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        PersistableBundle config = null;
        if (configManager != null) {
            config = configManager.getConfigForSubId(subId);
        }
        return config != null && config.getBoolean(
                CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_IMS_REGISTRATION_STATE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mImsStatus = screen.findPreference(KEY_IMS_REGISTRATION_STATE);
        updateConnectivity();
    }

    @Override
    protected String[] getConnectivityIntents() {
        return CONNECTIVITY_INTENTS;
    }

    @Override
    protected void updateConnectivity() {
        if (mImsStatus == null) {
            return;
        }
        final int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            mImsStatus.setSummary(R.string.ims_reg_status_not_registered);
            return;
        }
        final ExecutorService executors = Executors.newSingleThreadExecutor();
        final StateCallback stateCallback = new StateCallback();

        final ImsMmTelManager imsMmTelManager = ImsMmTelManager.createForSubscriptionId(subId);
        try {
            imsMmTelManager.getRegistrationState(executors, stateCallback);
        } catch (Exception ex) {
        }

        mImsStatus.setSummary(stateCallback.waitUntilResult()
                ? R.string.ims_reg_status_registered : R.string.ims_reg_status_not_registered);

        try {
            executors.shutdownNow();
        } catch (Exception exception) {
        }
    }

    private final class StateCallback extends AtomicBoolean implements Consumer<Integer> {
        private StateCallback() {
            super(false);
            mSemaphore = new Semaphore(0);
        }

        private final Semaphore mSemaphore;

        public void accept(Integer state) {
            set(state == RegistrationManager.REGISTRATION_STATE_REGISTERED);
            try {
                mSemaphore.release();
            } catch (Exception ex) {
            }
        }

        public boolean waitUntilResult() {
            try {
                if (!mSemaphore.tryAcquire(MAX_THREAD_BLOCKING_TIME_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(LOG_TAG, "IMS registration state query timeout");
                    return false;
                }
            } catch (Exception ex) {
            }
            return get();
        }
    }
}
