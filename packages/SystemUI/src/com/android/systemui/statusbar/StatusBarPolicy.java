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

package com.android.systemui.statusbar.policy;

import android.app.StatusBarManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothPbap;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.server.am.BatteryStatsService;

import com.android.systemui.R;
import android.net.wimax.WimaxManagerConstants;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  It goes through the normal API for icons, even though it probably
 * strictly doesn't need to.
 */
public class StatusBarPolicy {
    private static final String TAG = "StatusBarPolicy";

    // message codes for the handler
    private static final int EVENT_BATTERY_CLOSE = 4;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private static final int AM_PM_STYLE = AM_PM_STYLE_GONE;

    private static final int INET_CONDITION_THRESHOLD = 50;

    private final Context mContext;
    private final StatusBarManager mService;
    private final Handler mHandler = new StatusBarHandler();
    private final IBatteryStats mBatteryStats;

    // storage
    private StorageManager mStorageManager;

    // battery
    private boolean mBatteryFirst = true;
    private boolean mBatteryPlugged;
    private int mBatteryLevel;
    private AlertDialog mLowBatteryDialog;
    private TextView mBatteryLevelTextView;
    private View mBatteryView;
    private int mBatteryViewSequence;
    private boolean mBatteryShowLowOnEndCall = false;
    private static final boolean SHOW_LOW_BATTERY_WARNING = true;
    private static final boolean SHOW_BATTERY_WARNINGS_IN_CALL = true;

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

    // Assume it's all good unless we hear otherwise.  We don't always seem
    // to get broadcasts that it *is* there.
    IccCard.State mSimState = IccCard.State.READY;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int mDataState = TelephonyManager.DATA_DISCONNECTED;
    int mDataActivity = TelephonyManager.DATA_ACTIVITY_NONE;
    ServiceState mServiceState;
    SignalStrength mSignalStrength;

    // flag for signal strength behavior
    private boolean mAlwaysUseCdmaRssi;

    // data connection
    private boolean mDataIconVisible;
    private boolean mHspaDataDistinguishable;

    // ringer volume
    private boolean mVolumeVisible;

    // bluetooth device status
    private int mBluetoothHeadsetState;
    private boolean mBluetoothA2dpConnected;
    private int mBluetoothPbapState;
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

    //4G
    private static final int[][] sWimaxSignalImages = {
            { R.drawable.stat_sys_data_wimax_signal_0,
              R.drawable.stat_sys_data_wimax_signal_1,
              R.drawable.stat_sys_data_wimax_signal_2,
              R.drawable.stat_sys_data_wimax_signal_3 },
            { R.drawable.stat_sys_data_wimax_signal_0_fully,
              R.drawable.stat_sys_data_wimax_signal_1_fully,
              R.drawable.stat_sys_data_wimax_signal_2_fully,
              R.drawable.stat_sys_data_wimax_signal_3_fully }
        };
    private static final int sWimaxDisconnectedImg =
            R.drawable.stat_sys_data_wimax_signal_disconnected;
    private static final int sWimaxIdleImg = R.drawable.stat_sys_data_wimax_signal_idle;
    private boolean mIsWimaxEnabled = false;
    private int mWimaxSignal = 0;
    private int mWimaxState = 0;
    private int mWimaxExtraState = 0;

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
            else if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                onBatteryLow(intent);
            }
            else if (action.equals(Intent.ACTION_BATTERY_OKAY)
                    || action.equals(Intent.ACTION_POWER_CONNECTED)) {
                onBatteryOkay(intent);
            }
            else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED) ||
                    action.equals(BluetoothHeadset.ACTION_STATE_CHANGED) ||
                    action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED) ||
                    action.equals(BluetoothPbap.PBAP_STATE_CHANGED_ACTION)) {
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
            else if (action.equals(WimaxManagerConstants.WIMAX_ENABLED_STATUS_CHANGED) ||
                     action.equals(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION) ||
                     action.equals(WimaxManagerConstants.WIMAX_STATE_CHANGED_ACTION)) {
                updateWiMAX(intent);
            }
        }
    };

    public StatusBarPolicy(Context context) {
        mContext = context;
        mService = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        mSignalStrength = new SignalStrength();
        mBatteryStats = BatteryStatsService.getService();

        // storage
        mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        mStorageManager.registerListener(
                new com.android.systemui.usb.StorageNotification(context));

        // battery
        mService.setIcon("battery", com.android.internal.R.drawable.stat_sys_battery_unknown, 0);

        // phone_signal
        mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneSignalIconId = R.drawable.stat_sys_signal_null;
        mService.setIcon("phone_signal", mPhoneSignalIconId, 0);
        mAlwaysUseCdmaRssi = mContext.getResources().getBoolean(
            com.android.internal.R.bool.config_alwaysUseCdmaRssi);

        // register for phone state notifications.
        ((TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE))
                .listen(mPhoneStateListener,
                          PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY);

        // data_connection
        mService.setIcon("data_connection", R.drawable.stat_sys_data_connected_g, 0);
        mService.setIconVisibility("data_connection", false);

        // wifi
        mService.setIcon("wifi", sWifiSignalImages[0][0], 0);
        mService.setIconVisibility("wifi", false);
        // wifi will get updated by the sticky intents

        // wimax
        //enable/disable wimax depending on the value in config.xml
        boolean isWimaxEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wimaxEnabled);
        if (isWimaxEnabled) {
            mService.setIcon("wimax", sWimaxDisconnectedImg, 0);
            mService.setIconVisibility("wimax", false);
        }

        // TTY status
        mService.setIcon("tty",  R.drawable.stat_sys_tty_mode, 0);
        mService.setIconVisibility("tty", false);

        // Cdma Roaming Indicator, ERI
        mService.setIcon("cdma_eri", R.drawable.stat_sys_roaming_cdma_0, 0);
        mService.setIconVisibility("cdma_eri", false);

        // bluetooth status
        mService.setIcon("bluetooth", R.drawable.stat_sys_data_bluetooth, 0);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            mBluetoothEnabled = adapter.isEnabled();
        } else {
            mBluetoothEnabled = false;
        }
        mBluetoothA2dpConnected = false;
        mBluetoothHeadsetState = BluetoothHeadset.STATE_DISCONNECTED;
        mBluetoothPbapState = BluetoothPbap.STATE_DISCONNECTED;
        mService.setIconVisibility("bluetooth", mBluetoothEnabled);

        // Gps status
        mService.setIcon("gps", R.drawable.stat_sys_gps_acquiring_anim, 0);
        mService.setIconVisibility("gps", false);

        // Alarm clock
        mService.setIcon("alarm_clock", R.drawable.stat_notify_alarm, 0);
        mService.setIconVisibility("alarm_clock", false);

        // Sync state
        mService.setIcon("sync_active", com.android.internal.R.drawable.stat_notify_sync_anim0, 0);
        mService.setIcon("sync_failing", com.android.internal.R.drawable.stat_notify_sync_error, 0);
        mService.setIconVisibility("sync_active", false);
        mService.setIconVisibility("sync_failing", false);

        // volume
        mService.setIcon("volume", R.drawable.stat_sys_ringer_silent, 0);
        mService.setIconVisibility("volume", false);
        updateVolume();

        IntentFilter filter = new IntentFilter();

        // Register for Intent broadcasts for...
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_ALARM_CHANGED);
        filter.addAction(Intent.ACTION_SYNC_STATE_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.VIBRATE_SETTING_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_SINK_STATE_CHANGED);
        filter.addAction(BluetoothPbap.PBAP_STATE_CHANGED_ACTION);
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
        filter.addAction(WimaxManagerConstants.WIMAX_STATE_CHANGED_ACTION);
        filter.addAction(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION);
        filter.addAction(WimaxManagerConstants.WIMAX_ENABLED_STATUS_CHANGED);

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
        mService.setIcon("battery", id, level);

        boolean plugged = intent.getIntExtra("plugged", 0) != 0;
        level = intent.getIntExtra("level", -1);
        if (false) {
            Slog.d(TAG, "updateBattery level=" + level
                    + " plugged=" + plugged
                    + " mBatteryPlugged=" + mBatteryPlugged
                    + " mBatteryLevel=" + mBatteryLevel
                    + " mBatteryFirst=" + mBatteryFirst);
        }

        boolean oldPlugged = mBatteryPlugged;

        mBatteryPlugged = plugged;
        mBatteryLevel = level;

        if (mBatteryFirst) {
            mBatteryFirst = false;
        }
        /*
         * No longer showing the battery view because it draws attention away
         * from the USB storage notification. We could still show it when
         * connected to a brick, but that could lead to the user into thinking
         * the device does not charge when plugged into USB (since he/she would
         * not see the same battery screen on USB as he sees on brick).
         */
        if (false) {
            Slog.d(TAG, "plugged=" + plugged + " oldPlugged=" + oldPlugged + " level=" + level);
        }
    }

    private void onBatteryLow(Intent intent) {
        if (SHOW_LOW_BATTERY_WARNING) {
            if (false) {
                Slog.d(TAG, "mPhoneState=" + mPhoneState
                      + " mLowBatteryDialog=" + mLowBatteryDialog
                      + " mBatteryShowLowOnEndCall=" + mBatteryShowLowOnEndCall);
            }

            if (SHOW_BATTERY_WARNINGS_IN_CALL || mPhoneState == TelephonyManager.CALL_STATE_IDLE) {
                showLowBatteryWarning();
            } else {
                mBatteryShowLowOnEndCall = true;
            }
        }
    }

    private void onBatteryOkay(Intent intent) {
        if (mLowBatteryDialog != null
                && SHOW_LOW_BATTERY_WARNING) {
            mLowBatteryDialog.dismiss();
            mBatteryShowLowOnEndCall = false;
        }
    }

    private void setBatteryLevel(View parent, int id, int height, int background, int level) {
        ImageView v = (ImageView)parent.findViewById(id);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)v.getLayoutParams();
        lp.weight = height;
        if (background != 0) {
            v.setBackgroundResource(background);
            Drawable bkg = v.getBackground();
            bkg.setLevel(level);
        }
    }

    private void showLowBatteryWarning() {
        closeLastBatteryView();

        // Show exact battery level.
        CharSequence levelText = mContext.getString(
                    R.string.battery_low_percent_format, mBatteryLevel);

        if (mBatteryLevelTextView != null) {
            mBatteryLevelTextView.setText(levelText);
        } else {
            View v = View.inflate(mContext, R.layout.battery_low, null);
            mBatteryLevelTextView=(TextView)v.findViewById(R.id.level_percent);

            mBatteryLevelTextView.setText(levelText);

            AlertDialog.Builder b = new AlertDialog.Builder(mContext);
                b.setCancelable(true);
                b.setTitle(R.string.battery_low_title);
                b.setView(v);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                b.setPositiveButton(android.R.string.ok, null);

                final Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY);
                if (intent.resolveActivity(mContext.getPackageManager()) != null) {
                    b.setNegativeButton(R.string.battery_low_why,
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mContext.startActivity(intent);
                            if (mLowBatteryDialog != null) {
                                mLowBatteryDialog.dismiss();
                            }
                        }
                    });
                }

            AlertDialog d = b.create();
            d.setOnDismissListener(mLowBatteryListener);
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            d.show();
            mLowBatteryDialog = d;
        }

        final ContentResolver cr = mContext.getContentResolver();
        if (Settings.System.getInt(cr,
                Settings.System.POWER_SOUNDS_ENABLED, 1) == 1)
        {
            final String soundPath = Settings.System.getString(cr,
                Settings.System.LOW_BATTERY_SOUND);
            if (soundPath != null) {
                final Uri soundUri = Uri.parse("file://" + soundPath);
                if (soundUri != null) {
                    final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                    if (sfx != null) {
                        sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                        sfx.play();
                    }
                }
            }
        }
    }

    private final void updateCallState(int state) {
        mPhoneState = state;
        if (false) {
            Slog.d(TAG, "mPhoneState=" + mPhoneState
                    + " mLowBatteryDialog=" + mLowBatteryDialog
                    + " mBatteryShowLowOnEndCall=" + mBatteryShowLowOnEndCall);
        }
        if (mPhoneState == TelephonyManager.CALL_STATE_IDLE) {
            if (mBatteryShowLowOnEndCall) {
                if (!mBatteryPlugged) {
                    showLowBatteryWarning();
                }
                mBatteryShowLowOnEndCall = false;
            }
        } else {
            if (mLowBatteryDialog != null) {
                mLowBatteryDialog.dismiss();
                mBatteryShowLowOnEndCall = true;
            }
        }
    }

    private DialogInterface.OnDismissListener mLowBatteryListener
            = new DialogInterface.OnDismissListener() {
        public void onDismiss(DialogInterface dialog) {
            mLowBatteryDialog = null;
            mBatteryLevelTextView = null;
        }
    };

    private void scheduleCloseBatteryView() {
        Message m = mHandler.obtainMessage(EVENT_BATTERY_CLOSE);
        m.arg1 = (++mBatteryViewSequence);
        mHandler.sendMessageDelayed(m, 3000);
    }

    private void closeLastBatteryView() {
        if (mBatteryView != null) {
            //mBatteryView.debug();
            WindowManagerImpl.getDefault().removeView(mBatteryView);
            mBatteryView = null;
        }
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
                if (mLastWifiSignalLevel == -1) {
                    iconId = sWifiSignalImages[mInetCondition][0];
                } else {
                    iconId = sWifiSignalImages[mInetCondition][mLastWifiSignalLevel];
                }
                mService.setIcon("wifi", iconId, 0);
                // Show the icon since wi-fi is connected
                mService.setIconVisibility("wifi", true);
            } else {
                mLastWifiSignalLevel = -1;
                mIsWifiConnected = false;
                int iconId = sWifiSignalImages[0][0];

                mService.setIcon("wifi", iconId, 0);
                // Hide the icon since we're not connected
                mService.setIconVisibility("wifi", false);
            }
            updateSignalStrength(); // apply any change in mInetCondition
            break;
        case ConnectivityManager.TYPE_WIMAX:
            mInetCondition = inetCondition;
            updateWiMAX(intent);
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
            updateCallState(state);
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

    private boolean isEvdo() {
        return ( (mServiceState != null)
                 && ((mServiceState.getRadioTechnology()
                        == ServiceState.RADIO_TECHNOLOGY_EVDO_0)
                     || (mServiceState.getRadioTechnology()
                        == ServiceState.RADIO_TECHNOLOGY_EVDO_A)
                     || (mServiceState.getRadioTechnology()
                        == ServiceState.RADIO_TECHNOLOGY_EVDO_B)));
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
        int iconLevel = -1;
        int[] iconList;

        // Display signal strength while in "emergency calls only" mode
        if (mServiceState == null || (!hasService() && !mServiceState.isEmergencyOnly())) {
            //Slog.d(TAG, "updateSignalStrength: no service");
            if (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0) == 1) {
                mPhoneSignalIconId = R.drawable.stat_sys_signal_flightmode;
            } else {
                mPhoneSignalIconId = R.drawable.stat_sys_signal_null;
            }
            mService.setIcon("phone_signal", mPhoneSignalIconId, 0);
            return;
        }

        if (!isCdma()) {
            int asu = mSignalStrength.getGsmSignalStrength();

            // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
            // asu = 0 (-113dB or less) is very weak
            // signal, its better to show 0 bars to the user in such cases.
            // asu = 99 is a special case, where the signal strength is unknown.
            if (asu <= 2 || asu == 99) iconLevel = 0;
            else if (asu >= 12) iconLevel = 4;
            else if (asu >= 8)  iconLevel = 3;
            else if (asu >= 5)  iconLevel = 2;
            else iconLevel = 1;

            // Though mPhone is a Manager, this call is not an IPC
            if (mPhone.isNetworkRoaming()) {
                iconList = sSignalImages_r[mInetCondition];
            } else {
                iconList = sSignalImages[mInetCondition];
            }
        } else {
            iconList = sSignalImages[mInetCondition];

            // If 3G(EV) and 1x network are available than 3G should be
            // displayed, displayed RSSI should be from the EV side.
            // If a voice call is made then RSSI should switch to 1x.
            if ((mPhoneState == TelephonyManager.CALL_STATE_IDLE) && isEvdo()
                && !mAlwaysUseCdmaRssi) {
                iconLevel = getEvdoLevel();
                if (false) {
                    Slog.d(TAG, "use Evdo level=" + iconLevel + " to replace Cdma Level=" + getCdmaLevel());
                }
            } else {
                iconLevel = getCdmaLevel();
            }
        }
        mPhoneSignalIconId = iconList[iconLevel];
        mService.setIcon("phone_signal", mPhoneSignalIconId, 0);
    }

    private int getCdmaLevel() {
        final int cdmaDbm = mSignalStrength.getCdmaDbm();
        final int cdmaEcio = mSignalStrength.getCdmaEcio();
        int levelDbm = 0;
        int levelEcio = 0;

        if (cdmaDbm >= -75) levelDbm = 4;
        else if (cdmaDbm >= -85) levelDbm = 3;
        else if (cdmaDbm >= -95) levelDbm = 2;
        else if (cdmaDbm >= -100) levelDbm = 1;
        else levelDbm = 0;

        // Ec/Io are in dB*10
        if (cdmaEcio >= -90) levelEcio = 4;
        else if (cdmaEcio >= -110) levelEcio = 3;
        else if (cdmaEcio >= -130) levelEcio = 2;
        else if (cdmaEcio >= -150) levelEcio = 1;
        else levelEcio = 0;

        return (levelDbm < levelEcio) ? levelDbm : levelEcio;
    }

    private int getEvdoLevel() {
        int evdoDbm = mSignalStrength.getEvdoDbm();
        int evdoSnr = mSignalStrength.getEvdoSnr();
        int levelEvdoDbm = 0;
        int levelEvdoSnr = 0;

        if (evdoDbm >= -65) levelEvdoDbm = 4;
        else if (evdoDbm >= -75) levelEvdoDbm = 3;
        else if (evdoDbm >= -90) levelEvdoDbm = 2;
        else if (evdoDbm >= -105) levelEvdoDbm = 1;
        else levelEvdoDbm = 0;

        if (evdoSnr >= 7) levelEvdoSnr = 4;
        else if (evdoSnr >= 5) levelEvdoSnr = 3;
        else if (evdoSnr >= 3) levelEvdoSnr = 2;
        else if (evdoSnr >= 1) levelEvdoSnr = 1;
        else levelEvdoSnr = 0;

        return (levelEvdoDbm < levelEvdoSnr) ? levelEvdoDbm : levelEvdoSnr;
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
            mDataIconList = sDataNetType_3g[mInetCondition];
            break;
        default:
            mDataIconList = sDataNetType_g[mInetCondition];
        break;
        }
    }

    private final void updateDataIcon() {
        int iconId;
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
                    mService.setIcon("data_connection", iconId, 0);
                } else {
                    visible = false;
                }
            } else {
                iconId = R.drawable.stat_sys_no_sim;
                mService.setIcon("data_connection", iconId, 0);
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
                mService.setIcon("data_connection", iconId, 0);
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
        final int iconId = audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)
                ? R.drawable.stat_sys_ringer_vibrate
                : R.drawable.stat_sys_ringer_silent;

        if (visible) {
            mService.setIcon("volume", iconId, 0);
        }
        if (visible != mVolumeVisible) {
            mService.setIconVisibility("volume", visible);
            mVolumeVisible = visible;
        }
    }

    private final void updateBluetooth(Intent intent) {
        int iconId = R.drawable.stat_sys_data_bluetooth;
        String action = intent.getAction();
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            mBluetoothEnabled = state == BluetoothAdapter.STATE_ON;
        } else if (action.equals(BluetoothHeadset.ACTION_STATE_CHANGED)) {
            mBluetoothHeadsetState = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE,
                    BluetoothHeadset.STATE_ERROR);
        } else if (action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED)) {
            BluetoothA2dp a2dp = new BluetoothA2dp(mContext);
            if (a2dp.getConnectedSinks().size() != 0) {
                mBluetoothA2dpConnected = true;
            } else {
                mBluetoothA2dpConnected = false;
            }
        } else if (action.equals(BluetoothPbap.PBAP_STATE_CHANGED_ACTION)) {
            mBluetoothPbapState = intent.getIntExtra(BluetoothPbap.PBAP_STATE,
                    BluetoothPbap.STATE_DISCONNECTED);
        } else {
            return;
        }

        if (mBluetoothHeadsetState == BluetoothHeadset.STATE_CONNECTED || mBluetoothA2dpConnected ||
                mBluetoothPbapState == BluetoothPbap.STATE_CONNECTED) {
            iconId = R.drawable.stat_sys_data_bluetooth_connected;
        }

        mService.setIcon("bluetooth", iconId, 0);
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
            final int newRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi,
                                                                  sWifiSignalImages[0].length);
            if (newSignalLevel != mLastWifiSignalLevel) {
                mLastWifiSignalLevel = newSignalLevel;
                if (mIsWifiConnected) {
                    iconId = sWifiSignalImages[mInetCondition][newSignalLevel];
                } else {
                    iconId = sWifiTemporarilyNotConnectedImage;
                }
                mService.setIcon("wifi", iconId, 0);
            }
        }
    }

    private final void updateWiMAX(Intent intent) {
        final String action = intent.getAction();
        int iconId = sWimaxDisconnectedImg;

        if (action.equals(WimaxManagerConstants.WIMAX_ENABLED_STATUS_CHANGED)) {
            int wimaxStatus = intent.getIntExtra(WimaxManagerConstants.EXTRA_WIMAX_STATUS,
                    WimaxManagerConstants.WIMAX_STATUS_DISABLED);
            switch(wimaxStatus) {
                case WimaxManagerConstants.WIMAX_STATUS_ENABLED:
                    mIsWimaxEnabled = true;
                    break;
                case WimaxManagerConstants.WIMAX_STATUS_DISABLED:
                    mIsWimaxEnabled = false;
                    break;
            }
            mService.setIconVisibility("wimax", mIsWimaxEnabled);
        } else if (action.equals(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION)) {
            mWimaxSignal = intent.getIntExtra(WimaxManagerConstants.EXTRA_NEW_SIGNAL_LEVEL, 0);
        } else if (action.equals(WimaxManagerConstants.WIMAX_STATE_CHANGED_ACTION)) {
            mWimaxState = intent.getIntExtra(WimaxManagerConstants.EXTRA_WIMAX_STATE,
                    WimaxManagerConstants.WIMAX_STATE_UNKNOWN);
            mWimaxExtraState = intent.getIntExtra(
                    WimaxManagerConstants.EXTRA_WIMAX_STATE_DETAIL,
                    WimaxManagerConstants.WIMAX_DEREGISTRATION);
        }
        switch(mWimaxState) {
            case WimaxManagerConstants.WIMAX_STATE_DISCONNECTED:
                iconId = sWimaxDisconnectedImg;
                break;
            case WimaxManagerConstants.WIMAX_STATE_CONNECTED:
                if(mWimaxExtraState == WimaxManagerConstants.WIMAX_IDLE) {
                    iconId = sWimaxIdleImg;
                } else {
                    iconId = sWimaxSignalImages[mInetCondition][mWimaxSignal];
                }
                break;
        }
        if (mIsWimaxEnabled) mService.setIcon("wimax", iconId, 0);
    }

    private final void updateGps(Intent intent) {
        final String action = intent.getAction();
        final boolean enabled = intent.getBooleanExtra(LocationManager.EXTRA_GPS_ENABLED, false);

        if (action.equals(LocationManager.GPS_FIX_CHANGE_ACTION) && enabled) {
            // GPS is getting fixes
            mService.setIcon("gps", com.android.internal.R.drawable.stat_sys_gps_on, 0);
            mService.setIconVisibility("gps", true);
        } else if (action.equals(LocationManager.GPS_ENABLED_CHANGE_ACTION) && !enabled) {
            // GPS is off
            mService.setIconVisibility("gps", false);
        } else {
            // GPS is on, but not receiving fixes
            mService.setIcon("gps", R.drawable.stat_sys_gps_acquiring_anim, 0);
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
            mService.setIcon("tty", R.drawable.stat_sys_tty_mode, 0);
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
                if (iconIndex >= iconList.length) {
                    Slog.e(TAG, "unknown iconIndex " + iconIndex + ", skipping ERI icon update");
                    return;
                }
                mService.setIcon("cdma_eri", iconList[iconIndex], 0);
                mService.setIconVisibility("cdma_eri", true);
                break;
            case EriInfo.ROAMING_ICON_MODE_FLASH:
                mService.setIcon("cdma_eri", R.drawable.stat_sys_roaming_cdma_flash, 0);
                mService.setIconVisibility("cdma_eri", true);
                break;

        }
        mService.setIcon("phone_signal", mPhoneSignalIconId, 0);
    }

    private class StatusBarHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_BATTERY_CLOSE:
                if (msg.arg1 == mBatteryViewSequence) {
                    closeLastBatteryView();
                }
                break;
            }
        }
    }
}
