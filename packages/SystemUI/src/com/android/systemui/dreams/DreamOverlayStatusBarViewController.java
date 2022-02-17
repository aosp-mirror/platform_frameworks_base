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

package com.android.systemui.dreams;

import android.annotation.IntDef;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.android.systemui.battery.BatteryMeterViewController;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.dagger.DreamOverlayModule;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.ViewController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View controller for {@link DreamOverlayStatusBarView}.
 */
@DreamOverlayComponent.DreamOverlayScope
public class DreamOverlayStatusBarViewController extends ViewController<DreamOverlayStatusBarView> {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "WIFI_STATUS_" }, value = {
            WIFI_STATUS_UNKNOWN,
            WIFI_STATUS_UNAVAILABLE,
            WIFI_STATUS_AVAILABLE
    })
    private @interface WifiStatus {}
    private static final int WIFI_STATUS_UNKNOWN = 0;
    private static final int WIFI_STATUS_UNAVAILABLE = 1;
    private static final int WIFI_STATUS_AVAILABLE = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "BATTERY_STATUS_" }, value = {
            BATTERY_STATUS_UNKNOWN,
            BATTERY_STATUS_NOT_CHARGING,
            BATTERY_STATUS_CHARGING
    })
    private @interface BatteryStatus {}
    private static final int BATTERY_STATUS_UNKNOWN = 0;
    private static final int BATTERY_STATUS_NOT_CHARGING = 1;
    private static final int BATTERY_STATUS_CHARGING = 2;

    private final BatteryController mBatteryController;
    private final BatteryMeterViewController mBatteryMeterViewController;
    private final ConnectivityManager mConnectivityManager;
    private final boolean mShowPercentAvailable;

    private final NetworkRequest mNetworkRequest = new NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(
                Network network, NetworkCapabilities networkCapabilities) {
            onWifiAvailabilityChanged(
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        }

        @Override
        public void onAvailable(Network network) {
            onWifiAvailabilityChanged(true);
        }

        @Override
        public void onLost(Network network) {
            onWifiAvailabilityChanged(false);
        }
    };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                    DreamOverlayStatusBarViewController.this.onBatteryLevelChanged(charging);
                }
            };

    private @WifiStatus int mWifiStatus = WIFI_STATUS_UNKNOWN;
    private @BatteryStatus int mBatteryStatus = BATTERY_STATUS_UNKNOWN;

    @Inject
    public DreamOverlayStatusBarViewController(
            Context context,
            DreamOverlayStatusBarView view,
            BatteryController batteryController,
            @Named(DreamOverlayModule.DREAM_OVERLAY_BATTERY_CONTROLLER)
                    BatteryMeterViewController batteryMeterViewController,
            ConnectivityManager connectivityManager) {
        super(view);
        mBatteryController = batteryController;
        mBatteryMeterViewController = batteryMeterViewController;
        mConnectivityManager = connectivityManager;

        mShowPercentAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_battery_percentage_setting_available);
    }

    @Override
    protected void onInit() {
        super.onInit();
        mBatteryMeterViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mBatteryController.addCallback(mBatteryStateChangeCallback);
        mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);

        NetworkCapabilities capabilities =
                mConnectivityManager.getNetworkCapabilities(
                        mConnectivityManager.getActiveNetwork());
        onWifiAvailabilityChanged(
                capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    @Override
    protected void onViewDetached() {
        mBatteryController.removeCallback(mBatteryStateChangeCallback);
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }

    /**
     * Wifi availability has changed. Update the wifi status icon as appropriate.
     * @param available Whether wifi is available.
     */
    private void onWifiAvailabilityChanged(boolean available) {
        final int newWifiStatus = available ? WIFI_STATUS_AVAILABLE : WIFI_STATUS_UNAVAILABLE;
        if (mWifiStatus != newWifiStatus) {
            mWifiStatus = newWifiStatus;
            mView.showWifiStatus(mWifiStatus == WIFI_STATUS_UNAVAILABLE);
        }
    }

    /**
     * The battery level has changed. Update the battery status icon as appropriate.
     * @param charging Whether the battery is currently charging.
     */
    private void onBatteryLevelChanged(boolean charging) {
        final int newBatteryStatus =
                charging ? BATTERY_STATUS_CHARGING : BATTERY_STATUS_NOT_CHARGING;
        if (mBatteryStatus != newBatteryStatus) {
            mBatteryStatus = newBatteryStatus;
            mView.showBatteryPercentText(
                    mBatteryStatus == BATTERY_STATUS_CHARGING && mShowPercentAvailable);
        }
    }
}
