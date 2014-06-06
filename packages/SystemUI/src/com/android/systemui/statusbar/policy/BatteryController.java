/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.internal.app.IBatteryStats;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarHeaderView;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.format.Formatter;
import android.util.Log;

import java.util.ArrayList;

public class BatteryController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.BatteryController";

    private ArrayList<BatteryStateChangeCallback> mChangeCallbacks =
            new ArrayList<BatteryStateChangeCallback>();

    private Context mContext;
    private StatusBarHeaderView mStatusBarHeaderView;
    private IBatteryStats mBatteryInfo;

    private int mLevel;
    private boolean mPluggedIn;
    private boolean mCharging;
    private boolean mCharged;


    public interface BatteryStateChangeCallback {
        public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging);
    }

    public BatteryController(Context context) {
        mContext = context;

        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(this, filter);
    }

    public void addStateChangedCallback(BatteryStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    public void setStatusBarHeaderView(StatusBarHeaderView statusBarHeaderView) {
        mStatusBarHeaderView = statusBarHeaderView;
        updateStatusBarHeaderView();
    }

    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
            mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            mPluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;

            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            mCharged = status == BatteryManager.BATTERY_STATUS_FULL;
            mCharging = mCharged || status == BatteryManager.BATTERY_STATUS_CHARGING;

            updateStatusBarHeaderView();
            for (BatteryStateChangeCallback cb : mChangeCallbacks) {
                cb.onBatteryLevelChanged(mLevel, mPluggedIn, mCharging);
            }
        }
    }

    private void updateStatusBarHeaderView() {
        if (mStatusBarHeaderView != null) {
            mStatusBarHeaderView.setShowChargingInfo(mPluggedIn);
            mStatusBarHeaderView.setChargingInfo(computeChargingInfo());
        }
    }

    private String computeChargingInfo() {
        if (!mPluggedIn || !mCharged && !mCharging) {
            return mContext.getResources().getString(R.string.expanded_header_battery_not_charging);
        }

        if (mCharged) {
            return mContext.getResources().getString(R.string.expanded_header_battery_charged);
        }

        // Try fetching charging time from battery stats.
        try {
            long chargingTimeRemaining = mBatteryInfo.computeChargeTimeRemaining();
            if (chargingTimeRemaining > 0) {
                String chargingTimeFormatted =
                        Formatter.formatShortElapsedTime(mContext, chargingTimeRemaining);
                return mContext.getResources().getString(
                        R.string.expanded_header_battery_charging_with_time, chargingTimeFormatted);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IBatteryStats: ", e);
        }

        // Fall back to simple charging label.
        return mContext.getResources().getString(R.string.expanded_header_battery_charging);
    }
}
