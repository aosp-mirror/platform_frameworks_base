/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.server.am.BatteryStatsService;
import com.android.systemui.R;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  It goes through the normal API for icons, even though it probably
 * strictly doesn't need to.
 */
public class PhoneStatusBarPolicy {
    private static final String TAG = "PhoneStatusBarPolicy";

    // message codes for the handler
    private static final int EVENT_BATTERY_CLOSE = 4;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private static final int AM_PM_STYLE = AM_PM_STYLE_GONE;

    private static final int INET_CONDITION_THRESHOLD = 50;

    private final Context mContext;
    private final StatusBarManager mService;
    private final Handler mHandler = new Handler();
    private final IBatteryStats mBatteryStats;

    // storage
    private StorageManager mStorageManager;

    // phone
    private TelephonyManager mPhone;
    private int mPhoneSignalIconId;

    //***** Signal strength icons
    //GSM/UMTS
    private static final int[][] sSignalImages = {
        { R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4 },
        { R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully }
    };
    private static final int[][] sSignalImages_r = {
        { R.drawable.stat_sys_r_signal_0,
          R.drawable.stat_sys_r_signal_1,
          R.drawable.stat_sys_r_signal_2,
          R.drawable.stat_sys_r_signal_3,
          R.drawable.stat_sys_r_signal_4 },
        { R.drawable.stat_sys_r_signal_0_fully,
          R.drawable.stat_sys_r_signal_1_fully,
          R.drawable.stat_sys_r_signal_2_fully,
          R.drawable.stat_sys_r_signal_3_fully,
          R.drawable.stat_sys_r_signal_4_fully }
    };
    private static final int[] sRoamingIndicatorImages_cdma = new int[] {
        R.drawable.stat_sys_roaming_cdma_0, //Standard Roaming Indicator
        // 1 is Standard Roaming Indicator OFF
        // TODO T: image never used, remove and put 0 instead?
        R.drawable.stat_sys_roaming_cdma_0,

        // 2 is Standard Roaming Indicator FLASHING
        // TODO T: image never used, remove and put 0 instead?
        R.drawable.stat_sys_roaming_cdma_0,

        // 3-12 Standard ERI
        R.drawable.stat_sys_roaming_cdma_0, //3
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,

        // 13-63 Reserved for Standard ERI
        R.drawable.stat_sys_roaming_cdma_0, //13
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,

        // 64-127 Reserved for Non Standard (Operator Specific) ERI
        R.drawable.stat_sys_roaming_cdma_0, //64
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0, //83
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0,
        R.drawable.stat_sys_roaming_cdma_0 //239

        // 240-255 Reserved
    };

    //***** Data connection icons
    private int[] mDataIconList = sDataNetType_g[0];
    //GSM/UMTS
    private static final int[][] sDataNetType_g = {
            { R.drawable.stat_sys_data_connected_g,
              R.drawable.stat_sys_data_in_g,
              R.drawable.stat_sys_data_out_g,
              R.drawable.stat_sys_data_inandout_g },
            { R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_in_g,
              R.drawable.stat_sys_data_fully_out_g,
              R.drawable.stat_sys_data_fully_inandout_g }
        };
    private static final int[][] sDataNetType_3g = {
            { R.drawable.stat_sys_data_connected_3g,
              R.drawable.stat_sys_data_in_3g,
              R.drawable.stat_sys_data_out_3g,
              R.drawable.stat_sys_data_inandout_3g },
            { R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_in_3g,
              R.drawable.stat_sys_data_fully_out_3g,
              R.drawable.stat_sys_data_fully_inandout_3g }
        };
    private static final int[][] sDataNetType_4g = {
            { R.drawable.stat_sys_data_connected_4g,
              R.drawable.stat_sys_data_in_4g,
              R.drawable.stat_sys_data_out_4g,
              R.drawable.stat_sys_data_inandout_4g },
            { R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_in_4g,
              R.drawable.stat_sys_data_fully_out_4g,
              R.drawable.stat_sys_data_fully_inandout_4g }
        };
    private static final int[][] sDataNetType_e = {
            { R.drawable.stat_sys_data_connected_e,
              R.drawable.stat_sys_data_in_e,
              R.drawable.stat_sys_data_out_e,
              R.drawable.stat_sys_data_inandout_e },
            { R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_in_e,
              R.drawable.stat_sys_data_fully_out_e,
              R.drawable.stat_sys_data_fully_inandout_e }
        };
    //3.5G
    private static final int[][] sDataNetType_h = {
            { R.drawable.stat_sys_data_connected_h,
              R.drawable.stat_sys_data_in_h,
              R.drawable.stat_sys_data_out_h,
              R.drawable.stat_sys_data_inandout_h },
            { R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_in_h,
              R.drawable.stat_sys_data_fully_out_h,
              R.drawable.stat_sys_data_fully_inandout_h }
    };

    //CDMA
    // Use 3G icons for EVDO data and 1x icons for 1XRTT data
    private static final int[][] sDataNetType_1x = {
            { R.drawable.stat_sys_data_connected_1x,
              R.drawable.stat_sys_data_in_1x,
              R.drawable.stat_sys_data_out_1x,
              R.drawable.stat_sys_data_inandout_1x },
            { R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_in_1x,
              R.drawable.stat_sys_data_fully_out_1x,
              R.drawable.stat_sys_data_fully_inandout_1x }
            };

    // Accessibility;

    private static final int[] sPhoneSignalStrength = {
        R.string.accessibility_no_phone,
        R.string.accessibility_phone_one_bar,
        R.string.accessibility_phone_two_bars,
        R.string.accessibility_phone_three_bars,
        R.string.accessibility_phone_signal_full
    };

    private static final int[] sDataConnectionStrength = {
        R.string.accessibility_no_data,
        R.string.accessibility_data_one_bar,
        R.string.accessibility_data_two_bars,
        R.string.accessibility_data_three_bars,
        R.string.accessibility_data_signal_full
    };

    private static final int[] sWifiConnectionStrength = {
        R.string.accessibility_no_wifi,
        R.string.accessibility_wifi_one_bar,
        R.string.accessibility_wifi_two_bars,
        R.string.accessibility_wifi_three_bars,
        R.string.accessibility_wifi_signal_full
    };

    // Assume it's all good unless we hear otherwise.  We don't always seem
    // to get broadcasts that it *is* there.
    IccCard.State mSimState = IccCard.State.READY;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int mDataState = TelephonyManager.DATA_DISCONNECTED;
    int mDataActivity = TelephonyManager.DATA_ACTIVITY_NONE;
    ServiceState mServiceState;
    SignalStrength mSignalStrength;

    // data connection
    private boolean mDataIconVisible;
    private boolean mHspaDataDistinguishable;

    // ringer volume
    private boolean mVolumeVisible;

    // bluetooth device status
    private boolean mBluetoothEnabled;

    // wifi
    private static final int[][] sWifiSignalImages = {
            { R.drawable.stat_sys_wifi_signal_1,
              R.drawable.stat_sys_wifi_signal_2,
              R.drawable.stat_sys_wifi_signal_3,
              R.drawable.stat_sys_wifi_signal_4 },
            { R.drawable.stat_sys_wifi_signal_1_fully,
              R.drawable.stat_sys_wifi_signal_2_fully,
              R.drawable.stat_sys_wifi_signal_3_fully,
              R.drawable.stat_sys_wifi_signal_4_fully }
        };
    private static final int sWifiTemporarilyNotConnectedImage =
            R.drawable.stat_sys_wifi_signal_0;

    private int mLastWifiSignalLevel = -1;
    private boolean mIsWifiConnected = false;

    // state of inet connection - 0 not connected, 100 connected
    private int mInetCondition = 0;

    // sync state
    // If sync is active the SyncActive icon is displayed. If sync is not active but
    // sync is failing the SyncFailing icon is displayed. Otherwise neither are displayed.

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                updateBattery(intent);
            }
            else if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                updateAlarm(intent);
            }
            else if (action.equals(Intent.ACTION_SYNC_STATE_CHANGED)) {
                updateSyncState(intent);
            }
            else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED) ||
                    action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                updateBluetooth(intent);
            }
            else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION) ||
                    action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION) ||
                    action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                updateWifi(intent);
            }
            else if (action.equals(LocationManager.GPS_ENABLED_CHANGE_ACTION) ||
                    action.equals(LocationManager.GPS_FIX_CHANGE_ACTION)) {
                updateGps(intent);
            }
            else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION) ||
                    action.equals(AudioManager.VIBRATE_SETTING_CHANGED_ACTION)) {
                updateVolume();
            }
            else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                updateSimState(intent);
            }
            else if (action.equals(TtyIntent.TTY_ENABLED_CHANGE_ACTION)) {
                updateTTY(intent);
            }
            else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                     action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
                // TODO - stop using other means to get wifi/mobile info
                updateConnectivity(intent);
            }
        }
    };

    public PhoneStatusBarPolicy(Context context) {
        mContext = context;
        mService = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        mSignalStrength = new SignalStrength();
        mBatteryStats = BatteryStatsService.getService();

        // storage
        mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        mStorageManager.registerListener(
                new com.android.systemui.usb.StorageNotification(context));

        // battery
        mService.setIcon("battery", com.android.internal.R.drawable.stat_sys_battery_unknown, 0,
                null);

        // phone_signal
        mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneSignalIconId = R.drawable.stat_sys_signal_null;
        mService.setIcon("phone_signal", mPhoneSignalIconId, 0, null);

        // register for phone state notifications.
        ((TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE))
                .listen(mPhoneStateListener,
                          PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY);

        // data_connection
        mService.setIcon("data_connection", R.drawable.stat_sys_data_connected_g, 0, null);
        mService.setIconVisibility("data_connection", false);

        // wifi
        mService.setIcon("wifi", sWifiSignalImages[0][0], 0, null);
        mService.setIconVisibility("wifi", false);
        // wifi will get updated by the sticky intents

        // TTY status
        mService.setIcon("tty",  R.drawable.stat_sys_tty_mode, 0, null);
        mService.setIconVisibility("tty", false);

        // Cdma Roaming Indicator, ERI
        mService.setIcon("cdma_eri", R.drawable.stat_sys_roaming_cdma_0, 0, null);
        mService.setIconVisibility("cdma_eri", false);

        // bluetooth status
        mService.setIcon("bluetooth", R.drawable.stat_sys_data_bluetooth, 0, null);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            mBluetoothEnabled = adapter.isEnabled();
        } else {
            mBluetoothEnabled = false;
        }
        mService.setIconVisibility("bluetooth", mBluetoothEnabled);

        // Gps status
        mService.setIcon("gps", R.drawable.stat_sys_gps_acquiring_anim, 0, null);
        mService.setIconVisibility("gps", false);

        // Alarm clock
        mService.setIcon("alarm_clock", R.drawable.stat_notify_alarm, 0, null);
        mService.setIconVisibility("alarm_clock", false);

        // Sync state
        mService.setIcon("sync_active", com.android.internal.R.drawable.stat_notify_sync_anim0,
                0, null);
        mService.setIcon("sync_failing", com.android.internal.R.drawable.stat_notify_sync_error,
                0, null);
        mService.setIconVisibility("sync_active", false);
        mService.setIconVisibility("sync_failing", false);

        // volume
        mService.setIcon("volume", R.drawable.stat_sys_ringer_silent, 0, null);
        mService.setIconVisibility("volume", false);
        updateVolume();

        IntentFilter filter = new IntentFilter();

        // Register for Intent broadcasts for...
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_ALARM_CHANGED);
        filter.addAction(Intent.ACTION_SYNC_STATE_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.VIBRATE_SETTING_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(LocationManager.GPS_ENABLED_CHANGE_ACTION);
        filter.addAction(LocationManager.GPS_FIX_CHANGE_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TtyIntent.TTY_ENABLED_CHANGE_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);

        // load config to determine if to distinguish Hspa data icon
        try {
            mHspaDataDistinguishable = mContext.getResources().getBoolean(
                    R.bool.config_hspa_data_distinguishable);
        } catch (Exception e) {
            mHspaDataDistinguishable = false;
        }
    }

    private final void updateAlarm(Intent intent) {
        boolean alarmSet = intent.getBooleanExtra("alarmSet", false);
        mService.setIconVisibility("alarm_clock", alarmSet);
    }

    private final void updateSyncState(Intent intent) {
        boolean isActive = intent.getBooleanExtra("active", false);
        boolean isFailing = intent.getBooleanExtra("failing", false);
        mService.setIconVisibility("sync_active", isActive);
        // Don't display sync failing icon: BUG 1297963 Set sync error timeout to "never"
        //mService.setIconVisibility("sync_failing", isFailing && !isActive);
    }

    private final void updateBattery(Intent intent) {
        final int id = intent.getIntExtra("icon-small", 0);
        int level = intent.getIntExtra("level", 0);
        String contentDescription = mContext.getString(R.string.accessibility_battery_level, level);
        mService.setIcon("battery", id, level, contentDescription);
    }

    private void updateConnectivity(Intent intent) {
        NetworkInfo info = (NetworkInfo)(intent.getParcelableExtra(
                ConnectivityManager.EXTRA_NETWORK_INFO));
        int connectionStatus = intent.getIntExtra(ConnectivityManager.EXTRA_INET_CONDITION, 0);

        int inetCondition = (connectionStatus > INET_CONDITION_THRESHOLD ? 1 : 0);

        switch (info.getType()) {
        case ConnectivityManager.TYPE_MOBILE:
            mInetCondition = inetCondition;
            updateDataNetType(info.getSubtype());
            updateDataIcon();
            updateSignalStrength(); // apply any change in connectionStatus
            break;
        case ConnectivityManager.TYPE_WIFI:
            mInetCondition = inetCondition;
            if (info.isConnected()) {
                mIsWifiConnected = true;
                int iconId;
                String contentDescription = null;
                if (mLastWifiSignalLevel == -1) {
                    iconId = sWifiSignalImages[mInetCondition][0];
                    contentDescription = mContext.getString(sWifiConnectionStrength[0]);
                } else {
                    iconId = sWifiSignalImages[mInetCondition][mLastWifiSignalLevel];
                    contentDescription = mContext.getString(
                            sWifiConnectionStrength[mLastWifiSignalLevel]);
                } 
                mService.setIcon("wifi", iconId, 0, contentDescription);
                // Show the icon since wi-fi is connected
                mService.setIconVisibility("wifi", true);
            } else {
                mLastWifiSignalLevel = -1;
                mIsWifiConnected = false;
                int iconId = sWifiSignalImages[0][0];

                String contentDescription = mContext.getString(R.string.accessibility_no_wifi);
                mService.setIcon("wifi", iconId, 0, contentDescription);
                // Hide the icon since we're not connected
                mService.setIconVisibility("wifi", false);
            }
            updateSignalStrength(); // apply any change in mInetCondition
            break;
        }
    }

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mSignalStrength = signalStrength;
            updateSignalStrength();
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            mServiceState = state;
            updateSignalStrength();
            updateCdmaRoamingIcon(state);
            updateDataIcon();
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            // In cdma, if a voice call is made, RSSI should switch to 1x.
            if (isCdma()) {
                updateSignalStrength();
            }
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            mDataState = state;
            updateDataNetType(networkType);
            updateDataIcon();
        }

        @Override
        public void onDataActivity(int direction) {
            mDataActivity = direction;
            updateDataIcon();
        }
    };

    private final void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(IccCard.INTENT_KEY_ICC_STATE);
        if (IccCard.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            mSimState = IccCard.State.ABSENT;
        }
        else if (IccCard.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            mSimState = IccCard.State.READY;
        }
        else if (IccCard.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason = intent.getStringExtra(IccCard.INTENT_KEY_LOCKED_REASON);
            if (IccCard.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = IccCard.State.PIN_REQUIRED;
            }
            else if (IccCard.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = IccCard.State.PUK_REQUIRED;
            }
            else {
                mSimState = IccCard.State.NETWORK_LOCKED;
            }
        } else {
            mSimState = IccCard.State.UNKNOWN;
        }
        updateDataIcon();
    }

    private boolean isCdma() {
        return (mSignalStrength != null) && !mSignalStrength.isGsm();
    }

    private boolean hasService() {
        if (mServiceState != null) {
            switch (mServiceState.getState()) {
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_POWER_OFF:
                    return false;
                default:
                    return true;
            }
        } else {
            return false;
        }
    }

    private final void updateSignalStrength() {
        int[] iconList;
        String contentDescription = null;

        // Display signal strength while in "emergency calls only" mode
        if (mServiceState == null || (!hasService() && !mServiceState.isEmergencyOnly())) {
            //Slog.d(TAG, "updateSignalStrength: no service");
            if (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0) == 1) {
                mPhoneSignalIconId = R.drawable.stat_sys_signal_flightmode;
                contentDescription = mContext.getString(R.string.accessibility_airplane_mode);
            } else {
                mPhoneSignalIconId = R.drawable.stat_sys_signal_null;
                contentDescription = mContext.getString(R.string.accessibility_no_phone);
            }
            mService.setIcon("phone_signal", mPhoneSignalIconId, 0, contentDescription);
            return;
        }

        if (!isCdma()) {
            // Though mPhone is a Manager, this call is not an IPC
            if (mPhone.isNetworkRoaming()) {
                iconList = sSignalImages_r[mInetCondition];
            } else {
                iconList = sSignalImages[mInetCondition];
            }
        } else {
            iconList = sSignalImages[mInetCondition];
        }

        final int signalLevel = mSignalStrength.getLevel();
        mPhoneSignalIconId = iconList[signalLevel];
        contentDescription = mContext.getString(sPhoneSignalStrength[signalLevel]);
        mService.setIcon("phone_signal", mPhoneSignalIconId, 0, contentDescription);
    }

    private final void updateDataNetType(int net) {
        switch (net) {
        case TelephonyManager.NETWORK_TYPE_EDGE:
            mDataIconList = sDataNetType_e[mInetCondition];
            break;
        case TelephonyManager.NETWORK_TYPE_UMTS:
            mDataIconList = sDataNetType_3g[mInetCondition];
            break;
        case TelephonyManager.NETWORK_TYPE_HSDPA:
        case TelephonyManager.NETWORK_TYPE_HSUPA:
        case TelephonyManager.NETWORK_TYPE_HSPA:
            if (mHspaDataDistinguishable) {
                mDataIconList = sDataNetType_h[mInetCondition];
            } else {
                mDataIconList = sDataNetType_3g[mInetCondition];
            }
            break;
        case TelephonyManager.NETWORK_TYPE_CDMA:
            // display 1xRTT for IS95A/B
            mDataIconList = sDataNetType_1x[mInetCondition];
            break;
        case TelephonyManager.NETWORK_TYPE_1xRTT:
            mDataIconList = sDataNetType_1x[mInetCondition];
            break;
        case TelephonyManager.NETWORK_TYPE_EVDO_0: //fall through
        case TelephonyManager.NETWORK_TYPE_EVDO_A:
        case TelephonyManager.NETWORK_TYPE_EVDO_B:
        case TelephonyManager.NETWORK_TYPE_EHRPD:
            mDataIconList = sDataNetType_3g[mInetCondition];
            break;
        case TelephonyManager.NETWORK_TYPE_LTE:
            mDataIconList = sDataNetType_4g[mInetCondition];
            break;
        default:
            mDataIconList = sDataNetType_g[mInetCondition];
        break;
        }
    }

    private final void updateDataIcon() {
        int iconId;
        String contentDescription = null;
        boolean visible = true;

        if (!isCdma()) {
            // GSM case, we have to check also the sim state
            if (mSimState == IccCard.State.READY || mSimState == IccCard.State.UNKNOWN) {
                if (hasService() && mDataState == TelephonyManager.DATA_CONNECTED) {
                    switch (mDataActivity) {
                        case TelephonyManager.DATA_ACTIVITY_IN:
                            iconId = mDataIconList[1];
                            break;
                        case TelephonyManager.DATA_ACTIVITY_OUT:
                            iconId = mDataIconList[2];
                            break;
                        case TelephonyManager.DATA_ACTIVITY_INOUT:
                            iconId = mDataIconList[3];
                            break;
                        default:
                            iconId = mDataIconList[0];
                            break;
                    }
                    contentDescription = mContext.getString(sDataConnectionStrength[mDataActivity]);
                    mService.setIcon("data_connection", iconId, 0, contentDescription);
                } else {
                    visible = false;
                }
            } else {
                iconId = R.drawable.stat_sys_no_sim;
                contentDescription = mContext.getString(R.string.accessibility_no_sim);
                mService.setIcon("data_connection", iconId, 0, contentDescription);
            }
        } else {
            // CDMA case, mDataActivity can be also DATA_ACTIVITY_DORMANT
            if (hasService() && mDataState == TelephonyManager.DATA_CONNECTED) {
                switch (mDataActivity) {
                    case TelephonyManager.DATA_ACTIVITY_IN:
                        iconId = mDataIconList[1];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_OUT:
                        iconId = mDataIconList[2];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_INOUT:
                        iconId = mDataIconList[3];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_DORMANT:
                    default:
                        iconId = mDataIconList[0];
                        break;
                }
                mService.setIcon("data_connection", iconId, 0, null);
            } else {
                visible = false;
            }
        }

        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneDataConnectionState(mPhone.getNetworkType(), visible);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (mDataIconVisible != visible) {
            mService.setIconVisibility("data_connection", visible);
            mDataIconVisible = visible;
        }
    }

    private final void updateVolume() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        final int ringerMode = audioManager.getRingerMode();
        final boolean visible = ringerMode == AudioManager.RINGER_MODE_SILENT ||
                ringerMode == AudioManager.RINGER_MODE_VIBRATE;

        final int iconId;
        String contentDescription = null;
        if (audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)) {
            iconId = R.drawable.stat_sys_ringer_vibrate;
            contentDescription = mContext.getString(R.string.accessibility_ringer_vibrate);
        } else {
            iconId =  R.drawable.stat_sys_ringer_silent;
            contentDescription = mContext.getString(R.string.accessibility_ringer_silent);
        }

        if (visible) {
            mService.setIcon("volume", iconId, 0, contentDescription);
        }
        if (visible != mVolumeVisible) {
            mService.setIconVisibility("volume", visible);
            mVolumeVisible = visible;
        }
    }

    private final void updateBluetooth(Intent intent) {
        int iconId = R.drawable.stat_sys_data_bluetooth;
        String contentDescription = null;
        String action = intent.getAction();
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            mBluetoothEnabled = state == BluetoothAdapter.STATE_ON;
        } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                BluetoothAdapter.STATE_DISCONNECTED);
            if (state == BluetoothAdapter.STATE_CONNECTED) {
                iconId = R.drawable.stat_sys_data_bluetooth_connected;
                contentDescription = mContext.getString(R.string.accessibility_bluetooth_connected);
            } else {
                contentDescription = mContext.getString(
                        R.string.accessibility_bluetooth_disconnected);
            }
        } else {
            return;
        }

        mService.setIcon("bluetooth", iconId, 0, contentDescription);
        mService.setIconVisibility("bluetooth", mBluetoothEnabled);
    }

    private final void updateWifi(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {

            final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

            if (!enabled) {
                // If disabled, hide the icon. (We show icon when connected.)
                mService.setIconVisibility("wifi", false);
            }

        } else if (action.equals(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)) {
            final boolean enabled = intent.getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED,
                                                           false);
            if (!enabled) {
                mService.setIconVisibility("wifi", false);
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            int iconId;
            String contentDescription = null;
            final int newRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi,
                                                                  sWifiSignalImages[0].length);
            if (newSignalLevel != mLastWifiSignalLevel) {
                mLastWifiSignalLevel = newSignalLevel;
                if (mIsWifiConnected) {
                    iconId = sWifiSignalImages[mInetCondition][newSignalLevel];
                    contentDescription = mContext.getString(
                            sWifiConnectionStrength[newSignalLevel]);
                } else {
                    iconId = sWifiTemporarilyNotConnectedImage;
                    contentDescription = mContext.getString(R.string.accessibility_no_wifi);
                }
                mService.setIcon("wifi", iconId, 0, contentDescription);
            }
        }
    }

    private final void updateGps(Intent intent) {
        final String action = intent.getAction();
        final boolean enabled = intent.getBooleanExtra(LocationManager.EXTRA_GPS_ENABLED, false);

        if (action.equals(LocationManager.GPS_FIX_CHANGE_ACTION) && enabled) {
            // GPS is getting fixes
            mService.setIcon("gps", com.android.internal.R.drawable.stat_sys_gps_on, 0,
                    mContext.getString(R.string.accessibility_gps_enabled));
            mService.setIconVisibility("gps", true);
        } else if (action.equals(LocationManager.GPS_ENABLED_CHANGE_ACTION) && !enabled) {
            // GPS is off
            mService.setIconVisibility("gps", false);
        } else {
            // GPS is on, but not receiving fixes
            mService.setIcon("gps", R.drawable.stat_sys_gps_acquiring_anim, 0,
                    mContext.getString(R.string.accessibility_gps_acquiring));
            mService.setIconVisibility("gps", true);
        }
    }

    private final void updateTTY(Intent intent) {
        final String action = intent.getAction();
        final boolean enabled = intent.getBooleanExtra(TtyIntent.TTY_ENABLED, false);

        if (false) Slog.v(TAG, "updateTTY: enabled: " + enabled);

        if (enabled) {
            // TTY is on
            if (false) Slog.v(TAG, "updateTTY: set TTY on");
            mService.setIcon("tty", R.drawable.stat_sys_tty_mode, 0,
                    mContext.getString(R.string.accessibility_tty_enabled));
            mService.setIconVisibility("tty", true);
        } else {
            // TTY is off
            if (false) Slog.v(TAG, "updateTTY: set TTY off");
            mService.setIconVisibility("tty", false);
        }
    }

    private final void updateCdmaRoamingIcon(ServiceState state) {
        if (!hasService()) {
            mService.setIconVisibility("cdma_eri", false);
            return;
        }

        if (!isCdma()) {
            mService.setIconVisibility("cdma_eri", false);
            return;
        }

        int[] iconList = sRoamingIndicatorImages_cdma;
        int iconIndex = state.getCdmaEriIconIndex();
        int iconMode = state.getCdmaEriIconMode();

        if (iconIndex == -1) {
            Slog.e(TAG, "getCdmaEriIconIndex returned null, skipping ERI icon update");
            return;
        }

        if (iconMode == -1) {
            Slog.e(TAG, "getCdmeEriIconMode returned null, skipping ERI icon update");
            return;
        }

        if (iconIndex == EriInfo.ROAMING_INDICATOR_OFF) {
            if (false) Slog.v(TAG, "Cdma ROAMING_INDICATOR_OFF, removing ERI icon");
            mService.setIconVisibility("cdma_eri", false);
            return;
        }

        switch (iconMode) {
            case EriInfo.ROAMING_ICON_MODE_NORMAL:
                mService.setIcon("cdma_eri", iconList[iconIndex], 0, null);
                mService.setIconVisibility("cdma_eri", true);
                break;
            case EriInfo.ROAMING_ICON_MODE_FLASH:
                mService.setIcon("cdma_eri", R.drawable.stat_sys_roaming_cdma_flash, 0, null);
                mService.setIconVisibility("cdma_eri", true);
                break;

        }
        mService.setIcon("phone_signal", mPhoneSignalIconId, 0, null);
    }
}
