/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.util.SparseBooleanArray;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Tracks the Carrier Config values.
 */
@SysUISingleton
public class CarrierConfigTracker extends BroadcastReceiver {
    private final SparseBooleanArray mCallStrengthConfigs = new SparseBooleanArray();
    private final SparseBooleanArray mNoCallingConfigs = new SparseBooleanArray();
    private final SparseBooleanArray mCarrierProvisionsWifiMergedNetworks =
            new SparseBooleanArray();
    private final CarrierConfigManager mCarrierConfigManager;
    private boolean mDefaultCallStrengthConfigLoaded;
    private boolean mDefaultCallStrengthConfig;
    private boolean mDefaultNoCallingConfigLoaded;
    private boolean mDefaultNoCallingConfig;
    private boolean mDefaultCarrierProvisionsWifiMergedNetworksLoaded;
    private boolean mDefaultCarrierProvisionsWifiMergedNetworks;

    @Inject
    public CarrierConfigTracker(Context context, BroadcastDispatcher broadcastDispatcher) {
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        broadcastDispatcher.registerReceiver(
                this, new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(intent.getAction())) {
            return;
        }

        final int subId = intent.getIntExtra(
                CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return;
        }

        final PersistableBundle config = mCarrierConfigManager.getConfigForSubId(subId);
        if (config == null) {
            return;
        }

        synchronized (mCallStrengthConfigs) {
            mCallStrengthConfigs.put(subId, config.getBoolean(
                    CarrierConfigManager.KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL));
        }
        synchronized (mNoCallingConfigs) {
            mNoCallingConfigs.put(subId, config.getBoolean(
                    CarrierConfigManager.KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL));
        }
        synchronized (mCarrierProvisionsWifiMergedNetworks) {
            mCarrierProvisionsWifiMergedNetworks.put(subId, config.getBoolean(
                    CarrierConfigManager.KEY_CARRIER_PROVISIONS_WIFI_MERGED_NETWORKS_BOOL));
        }
    }

    /**
     * Returns the KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL value for the given subId.
     */
    public boolean getCallStrengthConfig(int subId) {
        synchronized (mCallStrengthConfigs) {
            if (mCallStrengthConfigs.indexOfKey(subId) >= 0) {
                return mCallStrengthConfigs.get(subId);
            }
        }
        if (!mDefaultCallStrengthConfigLoaded) {
            mDefaultCallStrengthConfig =
                    CarrierConfigManager.getDefaultConfig().getBoolean(
                            CarrierConfigManager.KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL);
            mDefaultCallStrengthConfigLoaded = true;
        }
        return mDefaultCallStrengthConfig;
    }

    /**
     * Returns the KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL value for the given subId.
     */
    public boolean getNoCallingConfig(int subId) {
        synchronized (mNoCallingConfigs) {
            if (mNoCallingConfigs.indexOfKey(subId) >= 0) {
                return mNoCallingConfigs.get(subId);
            }
        }
        if (!mDefaultNoCallingConfigLoaded) {
            mDefaultNoCallingConfig =
                    CarrierConfigManager.getDefaultConfig().getBoolean(
                            CarrierConfigManager.KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL);
            mDefaultNoCallingConfigLoaded = true;
        }
        return mDefaultNoCallingConfig;
    }

    /**
     * Returns the KEY_CARRIER_PROVISIONS_WIFI_MERGED_NETWORKS_BOOL value for the given subId.
     */
    public boolean getCarrierProvisionsWifiMergedNetworksBool(int subId) {
        synchronized (mCarrierProvisionsWifiMergedNetworks) {
            if (mCarrierProvisionsWifiMergedNetworks.indexOfKey(subId) >= 0) {
                return mCarrierProvisionsWifiMergedNetworks.get(subId);
            }
        }
        if (!mDefaultCarrierProvisionsWifiMergedNetworksLoaded) {
            mDefaultCarrierProvisionsWifiMergedNetworks =
                    CarrierConfigManager.getDefaultConfig().getBoolean(
                            CarrierConfigManager.KEY_CARRIER_PROVISIONS_WIFI_MERGED_NETWORKS_BOOL);
            mDefaultCarrierProvisionsWifiMergedNetworksLoaded = true;
        }
        return mDefaultCarrierProvisionsWifiMergedNetworks;
    }
}
