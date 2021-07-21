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
import android.util.SparseArray;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Tracks the Carrier Config values.
 */
@SysUISingleton
public class CarrierConfigTracker extends BroadcastReceiver {
    private final SparseArray<Boolean> mCallStrengthConfigs = new SparseArray<>();
    private final SparseArray<Boolean> mNoCallingConfigs = new SparseArray<>();
    private final CarrierConfigManager mCarrierConfigManager;
    private boolean mDefaultCallStrengthConfigLoaded;
    private boolean mDefaultCallStrengthConfig;
    private boolean mDefaultNoCallingConfigLoaded;
    private boolean mDefaultNoCallingConfig;

    @Inject
    public CarrierConfigTracker(Context context) {
        mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        context.registerReceiver(
                this, new IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED) {
            int subId = intent.getIntExtra(
                    CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                return;
            }
            PersistableBundle b = mCarrierConfigManager.getConfigForSubId(subId);
            if (b != null) {
                boolean hideNoCallingConfig = b.getBoolean(
                        CarrierConfigManager.KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL);
                boolean displayCallStrengthIcon = b.getBoolean(
                        CarrierConfigManager.KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL);
                mCallStrengthConfigs.put(subId, displayCallStrengthIcon);
                mNoCallingConfigs.put(subId, hideNoCallingConfig);
            }
        }
    }

    /**
     * Returns the KEY_DISPLAY_CALL_STRENGTH_INDICATOR_BOOL value for the given subId.
     */
    public boolean getCallStrengthConfig(int subId) {
        if (mCallStrengthConfigs.indexOfKey(subId) >= 0) {
            return mCallStrengthConfigs.get(subId);
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
        if (mNoCallingConfigs.indexOfKey(subId) >= 0) {
            return mNoCallingConfigs.get(subId);
        }
        if (!mDefaultNoCallingConfigLoaded) {
            mDefaultNoCallingConfig =
                    CarrierConfigManager.getDefaultConfig().getBoolean(
                            CarrierConfigManager.KEY_USE_IP_FOR_CALLING_INDICATOR_BOOL);
            mDefaultNoCallingConfigLoaded = true;
        }
        return mDefaultNoCallingConfig;
    }
}
