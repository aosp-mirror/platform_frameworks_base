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

package com.android.systemui.lowlightclock;

import android.content.Context;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.RemoteException;
import android.text.format.Formatter;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Preconditions;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;

import java.text.NumberFormat;

import javax.inject.Inject;

/**
 * Provides charging status as a string to a registered callback such that it can be displayed to
 * the user (e.g. on the low-light clock).
 * TODO(b/223681352): Make this code shareable with {@link KeyguardIndicationController}.
 */
public class ChargingStatusProvider {
    private static final String TAG = "ChargingStatusProvider";

    private final Resources mResources;
    private final Context mContext;
    private final IBatteryStats mBatteryInfo;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final BatteryState mBatteryState = new BatteryState();
    // This callback is registered with KeyguardUpdateMonitor, which only keeps weak references to
    // its callbacks. Therefore, an explicit reference needs to be kept here to avoid the
    // callback being GC'd.
    private ChargingStatusCallback mChargingStatusCallback;

    private Callback mCallback;

    @Inject
    public ChargingStatusProvider(
            Context context,
            @Main Resources resources,
            IBatteryStats iBatteryStats,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        mContext = context;
        mResources = resources;
        mBatteryInfo = iBatteryStats;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
    }

    /**
     * Start using the {@link ChargingStatusProvider}.
     * @param callback A callback to be called when the charging status changes.
     */
    public void startUsing(Callback callback) {
        Preconditions.checkState(
                mCallback == null, "ChargingStatusProvider already started!");
        mCallback = callback;
        mChargingStatusCallback = new ChargingStatusCallback();
        mKeyguardUpdateMonitor.registerCallback(mChargingStatusCallback);
        reportStatusToCallback();
    }

    /**
     * Stop using the {@link ChargingStatusProvider}.
     */
    public void stopUsing() {
        mCallback = null;

        if (mChargingStatusCallback != null) {
            mKeyguardUpdateMonitor.removeCallback(mChargingStatusCallback);
            mChargingStatusCallback = null;
        }
    }

    private String computeChargingString() {
        if (!mBatteryState.isValid()) {
            return null;
        }

        int chargingId;

        if (mBatteryState.isBatteryDefender()) {
            return mResources.getString(
                    R.string.keyguard_plugged_in_charging_limited,
                    mBatteryState.getBatteryLevelAsPercentage());
        } else if (mBatteryState.isPowerCharged()) {
            return mResources.getString(R.string.keyguard_charged);
        }

        final long chargingTimeRemaining = mBatteryState.getChargingTimeRemaining(mBatteryInfo);
        final boolean hasChargingTime = chargingTimeRemaining > 0;
        if (mBatteryState.isPowerPluggedInWired()) {
            switch (mBatteryState.getChargingSpeed(mContext)) {
                case BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        } else if (mBatteryState.isPowerPluggedInWireless()) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_wireless
                    : R.string.keyguard_plugged_in_wireless;
        } else if (mBatteryState.isPowerPluggedInDocked()) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_dock
                    : R.string.keyguard_plugged_in_dock;
        } else {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time
                    : R.string.keyguard_plugged_in;
        }

        final String percentage = mBatteryState.getBatteryLevelAsPercentage();
        if (hasChargingTime) {
            final String chargingTimeFormatted =
                    Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                            mContext, chargingTimeRemaining);
            return mResources.getString(chargingId, chargingTimeFormatted,
                    percentage);
        } else {
            return mResources.getString(chargingId, percentage);
        }
    }

    private void reportStatusToCallback() {
        if (mCallback != null) {
            final boolean shouldShowStatus =
                    mBatteryState.isPowerPluggedIn() || mBatteryState.isBatteryDefenderEnabled();
            mCallback.onChargingStatusChanged(shouldShowStatus, computeChargingString());
        }
    }

    private class ChargingStatusCallback extends KeyguardUpdateMonitorCallback {
        @Override
        public void onRefreshBatteryInfo(BatteryStatus status) {
            mBatteryState.setBatteryStatus(status);
            reportStatusToCallback();
        }
    }

    /***
     * A callback to be called when the charging status changes.
     */
    public interface Callback {
        /***
         * Called when the charging status changes.
         * @param shouldShowStatus Whether or not to show a charging status message.
         * @param statusMessage A charging status message.
         */
        void onChargingStatusChanged(boolean shouldShowStatus, String statusMessage);
    }

    /***
     * A wrapper around {@link BatteryStatus} for fetching various properties of the current
     * battery and charging state.
     */
    private static class BatteryState {
        private BatteryStatus mBatteryStatus;

        public void setBatteryStatus(BatteryStatus batteryStatus) {
            mBatteryStatus = batteryStatus;
        }

        public boolean isValid() {
            return mBatteryStatus != null;
        }

        public long getChargingTimeRemaining(IBatteryStats batteryInfo) {
            try {
                return isPowerPluggedIn() ? batteryInfo.computeChargeTimeRemaining() : -1;
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling IBatteryStats: ", e);
                return -1;
            }
        }

        public boolean isBatteryDefenderEnabled() {
            return isValid() && mBatteryStatus.isPluggedIn() && isBatteryDefender();
        }

        public boolean isBatteryDefender() {
            return isValid() && mBatteryStatus.isBatteryDefender();
        }

        public int getBatteryLevel() {
            return isValid() ? mBatteryStatus.level : 0;
        }

        public int getChargingSpeed(Context context) {
            return isValid() ? mBatteryStatus.getChargingSpeed(context) : 0;
        }

        public boolean isPowerCharged() {
            return isValid() && mBatteryStatus.isCharged();
        }

        public boolean isPowerPluggedIn() {
            return isValid() && mBatteryStatus.isPluggedIn() && isChargingOrFull();
        }

        public boolean isPowerPluggedInWired() {
            return isValid()
                    && mBatteryStatus.isPluggedInWired()
                    && isChargingOrFull();
        }

        public boolean isPowerPluggedInWireless() {
            return isValid()
                    && mBatteryStatus.isPluggedInWireless()
                    && isChargingOrFull();
        }

        public boolean isPowerPluggedInDocked() {
            return isValid() && mBatteryStatus.isPluggedInDock() && isChargingOrFull();
        }

        private boolean isChargingOrFull() {
            return isValid()
                    && (mBatteryStatus.status == BatteryManager.BATTERY_STATUS_CHARGING
                        || mBatteryStatus.isCharged());
        }

        private String getBatteryLevelAsPercentage() {
            return NumberFormat.getPercentInstance().format(getBatteryLevel() / 100f);
        }
    }
}
