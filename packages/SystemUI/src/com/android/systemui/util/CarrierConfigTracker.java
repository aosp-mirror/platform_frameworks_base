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
import android.util.ArraySet;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;

import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.Set;

import javax.inject.Inject;

/**
 * Tracks CarrierConfigs for each subId, as well as the default configuration. CarrierConfigurations
 * do not trigger a device configuration event, so any UI that relies on carrier configurations must
 * register with the tracker to get proper updates.
 *
 * The tracker also listens for `TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED`
 *
 * @see CarrierConfigChangedListener to listen for updates
 */
@SysUISingleton
public class CarrierConfigTracker
        extends BroadcastReceiver
        implements CallbackController<CarrierConfigTracker.CarrierConfigChangedListener> {
    private final SparseBooleanArray mCallStrengthConfigs = new SparseBooleanArray();
    private final SparseBooleanArray mNoCallingConfigs = new SparseBooleanArray();
    private final SparseBooleanArray mCarrierProvisionsWifiMergedNetworks =
            new SparseBooleanArray();
    private final SparseBooleanArray mShowOperatorNameConfigs = new SparseBooleanArray();
    private final CarrierConfigManager mCarrierConfigManager;
    private final Set<CarrierConfigChangedListener> mListeners = new ArraySet<>();
    private final Set<DefaultDataSubscriptionChangedListener> mDataListeners =
            new ArraySet<>();
    private boolean mDefaultCallStrengthConfigLoaded;
    private boolean mDefaultCallStrengthConfig;
    private boolean mDefaultNoCallingConfigLoaded;
    private boolean mDefaultNoCallingConfig;
    private boolean mDefaultCarrierProvisionsWifiMergedNetworksLoaded;
    private boolean mDefaultCarrierProvisionsWifiMergedNetworks;
    private boolean mDefaultShowOperatorNameConfigLoaded;
    private boolean mDefaultShowOperatorNameConfig;
    private boolean mDefaultAlwaysShowPrimarySignalBarInOpportunisticNetworkConfigLoaded;
    private boolean mDefaultAlwaysShowPrimarySignalBarInOpportunisticNetworkConfig;

    @Inject
    public CarrierConfigTracker(
            CarrierConfigManager carrierConfigManager,
            BroadcastDispatcher broadcastDispatcher) {
        mCarrierConfigManager = carrierConfigManager;
        IntentFilter filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        broadcastDispatcher.registerReceiver(this, filter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(action)) {
            updateFromNewCarrierConfig(intent);
        } else if (TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
            updateDefaultDataSubscription(intent);
        }
    }

    private void updateFromNewCarrierConfig(Intent intent) {
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
        synchronized (mShowOperatorNameConfigs) {
            mShowOperatorNameConfigs.put(subId, config.getBoolean(
                    CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL));
        }

        notifyCarrierConfigChanged();
    }

    private void updateDefaultDataSubscription(Intent intent) {
        int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1);
        notifyDefaultDataSubscriptionChanged(subId);
    }

    private void notifyCarrierConfigChanged() {
        for (CarrierConfigChangedListener l : mListeners) {
            l.onCarrierConfigChanged();
        }
    }

    private void notifyDefaultDataSubscriptionChanged(int subId) {
        for (DefaultDataSubscriptionChangedListener l : mDataListeners) {
            l.onDefaultSubscriptionChanged(subId);
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

    /**
     * Returns the KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL value for the default config
     */
    public boolean getShowOperatorNameInStatusBarConfigDefault() {
        if (!mDefaultShowOperatorNameConfigLoaded) {
            mDefaultShowOperatorNameConfig = CarrierConfigManager.getDefaultConfig().getBoolean(
                    CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL);
            mDefaultShowOperatorNameConfigLoaded = true;
        }

        return mDefaultShowOperatorNameConfig;
    }

    /**
     * Returns KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN value for
     * the default carrier config.
     */
    public boolean getAlwaysShowPrimarySignalBarInOpportunisticNetworkDefault() {
        if (!mDefaultAlwaysShowPrimarySignalBarInOpportunisticNetworkConfigLoaded) {
            mDefaultAlwaysShowPrimarySignalBarInOpportunisticNetworkConfig = CarrierConfigManager
                    .getDefaultConfig().getBoolean(CarrierConfigManager
                            .KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN
                    );
            mDefaultAlwaysShowPrimarySignalBarInOpportunisticNetworkConfigLoaded = true;
        }

        return mDefaultAlwaysShowPrimarySignalBarInOpportunisticNetworkConfig;
    }

    /**
     * Returns the KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL value for the given subId, or the
     * default value if no override exists
     *
     * @param subId the subscription id for which to query the config
     */
    public boolean getShowOperatorNameInStatusBarConfig(int subId) {
        if (mShowOperatorNameConfigs.indexOfKey(subId) >= 0) {
            return mShowOperatorNameConfigs.get(subId);
        } else {
            return getShowOperatorNameInStatusBarConfigDefault();
        }
    }

    @Override
    public void addCallback(@NonNull CarrierConfigChangedListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeCallback(@NonNull CarrierConfigChangedListener listener) {
        mListeners.remove(listener);
    }

    /** */
    public void addDefaultDataSubscriptionChangedListener(
            @NonNull DefaultDataSubscriptionChangedListener listener) {
        mDataListeners.add(listener);
    }

    /** */
    public void removeDataSubscriptionChangedListener(
            DefaultDataSubscriptionChangedListener listener) {
        mDataListeners.remove(listener);
    }

    /**
     * Called when carrier config changes
     */
    public interface CarrierConfigChangedListener {
        /** */
        void onCarrierConfigChanged();
    }

    /**
     * Called when the default data subscription changes. Listeners may want to query
     * subId-dependent configuration values when this event happens
     */
    public interface DefaultDataSubscriptionChangedListener {
        /**
         * @param subId the new default data subscription id per
         * {@link SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX}
         */
        void onDefaultSubscriptionChanged(int subId);
    }
}
