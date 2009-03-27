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

package com.android.server.status;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsLocationProvider;
import com.android.internal.telephony.SimCard;
import com.android.internal.telephony.TelephonyIntents;
import com.android.server.am.BatteryStatsService;

import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  In reality, it should go into the android.policy package, but
 * putting it here is the first step from extracting it.
 */
public class StatusBarPolicy {
    private static final String TAG = "StatusBarPolicy";

    private static StatusBarPolicy sInstance;

    // message codes for the handler
    private static final int EVENT_DATA_CONN_STATE_CHANGED = 2;
    private static final int EVENT_DATA_ACTIVITY = 3;
    private static final int EVENT_BATTERY_CLOSE = 4;

    // indices into mBatteryThresholds
    private static final int BATTERY_THRESHOLD_CLOSE_WARNING = 0;
    private static final int BATTERY_THRESHOLD_WARNING = 1;
    private static final int BATTERY_THRESHOLD_EMPTY = 2;

    private final Context mContext;
    private final StatusBarService mService;
    private final Handler mHandler = new StatusBarHandler();
    private final IBatteryStats mBatteryStats;

    // clock
    private Calendar mCalendar;
    private IBinder mClockIcon;
    private IconData mClockData;

    // battery
    private IBinder mBatteryIcon;
    private IconData mBatteryData;
    private boolean mBatteryFirst = true;
    private boolean mBatteryPlugged;
    private int mBatteryLevel;
    private int mBatteryThreshold = 0; // index into mBatteryThresholds
    private int[] mBatteryThresholds = new int[] { 20, 15, -1 };
    private AlertDialog mLowBatteryDialog;
    private TextView mBatteryLevelTextView;
    private View mBatteryView;
    private int mBatteryViewSequence;
    private boolean mBatteryShowLowOnEndCall = false;
    private static final boolean SHOW_LOW_BATTERY_WARNING = true;

    // phone
    private TelephonyManager mPhone;
    private IBinder mPhoneIcon;
    private IconData mPhoneData;
    private static final int[] sSignalImages = new int[] {
            com.android.internal.R.drawable.stat_sys_signal_0,
            com.android.internal.R.drawable.stat_sys_signal_1,
            com.android.internal.R.drawable.stat_sys_signal_2,
            com.android.internal.R.drawable.stat_sys_signal_3,
            com.android.internal.R.drawable.stat_sys_signal_4
        };
    private static final int[] sSignalImages_r = new int[] {
            com.android.internal.R.drawable.stat_sys_r_signal_0,
            com.android.internal.R.drawable.stat_sys_r_signal_1,
            com.android.internal.R.drawable.stat_sys_r_signal_2,
            com.android.internal.R.drawable.stat_sys_r_signal_3,
            com.android.internal.R.drawable.stat_sys_r_signal_4
        };
    private int[] mDataIconList = sDataNetType_g;
    private static final int[] sDataNetType_g = new int[] {
            com.android.internal.R.drawable.stat_sys_data_connected_g,
            com.android.internal.R.drawable.stat_sys_data_in_g,
            com.android.internal.R.drawable.stat_sys_data_out_g,
            com.android.internal.R.drawable.stat_sys_data_inandout_g,
        };
    private static final int[] sDataNetType_3g = new int[] {
            com.android.internal.R.drawable.stat_sys_data_connected_3g,
            com.android.internal.R.drawable.stat_sys_data_in_3g,
            com.android.internal.R.drawable.stat_sys_data_out_3g,
            com.android.internal.R.drawable.stat_sys_data_inandout_3g,
        };
    private static final int[] sDataNetType_e = new int[] {
            com.android.internal.R.drawable.stat_sys_data_connected_e,
            com.android.internal.R.drawable.stat_sys_data_in_e,
            com.android.internal.R.drawable.stat_sys_data_out_e,
            com.android.internal.R.drawable.stat_sys_data_inandout_e,
        };
    // Assume it's all good unless we hear otherwise.  We don't always seem
    // to get broadcasts that it *is* there.
    SimCard.State mSimState = SimCard.State.READY;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int mDataState = TelephonyManager.DATA_DISCONNECTED;
    int mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    int mDataActivity = TelephonyManager.DATA_ACTIVITY_NONE;
    ServiceState mServiceState;
    int mSignalAsu = -1;

    // data connection
    private IBinder mDataIcon;
    private IconData mDataData;
    private boolean mDataIconVisible;

    // ringer volume
    private IBinder mVolumeIcon;
    private IconData mVolumeData;
    private boolean mVolumeVisible;
    
    // bluetooth device status
    private IBinder mBluetoothIcon;
    private IconData mBluetoothData;
    private int mBluetoothHeadsetState;
    private int mBluetoothA2dpState;
    private boolean mBluetoothEnabled;

    // wifi
    private static final int[] sWifiSignalImages = new int[] {
            com.android.internal.R.drawable.stat_sys_wifi_signal_1,
            com.android.internal.R.drawable.stat_sys_wifi_signal_2,
            com.android.internal.R.drawable.stat_sys_wifi_signal_3,
            com.android.internal.R.drawable.stat_sys_wifi_signal_4,
        };
    private static final int sWifiTemporarilyNotConnectedImage =
            com.android.internal.R.drawable.stat_sys_wifi_signal_0;

    private int mLastWifiSignalLevel = -1;
    private boolean mIsWifiConnected = false;
    private IBinder mWifiIcon;
    private IconData mWifiData;

    // gps
    private IBinder mGpsIcon;
    private IconData mGpsEnabledIconData;
    private IconData mGpsFixIconData;

    // alarm clock
    // Icon lit when clock is set
    private IBinder mAlarmClockIcon;
    private IconData mAlarmClockIconData;

    // sync state
    // If sync is active the SyncActive icon is displayed. If sync is not active but
    // sync is failing the SyncFailing icon is displayed. Otherwise neither are displayed.
    private IBinder mSyncActiveIcon;
    private IBinder mSyncFailingIcon;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIME_TICK)) {
                updateClock();
            }
            else if (action.equals(Intent.ACTION_TIME_CHANGED)) {
                updateClock();
            }
            else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                updateClock();
            }
            else if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                updateClock();
            }
            else if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                updateAlarm(intent);
            }
            else if (action.equals(Intent.ACTION_SYNC_STATE_CHANGED)) {
                updateSyncState(intent);
            }
            else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                updateBattery(intent);
            }
            else if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION) ||
                    action.equals(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION) ||
                    action.equals(BluetoothA2dp.SINK_STATE_CHANGED_ACTION)) {
                updateBluetooth(intent);
            }
            else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION) ||
                    action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION) ||
                    action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                updateWifi(intent);
            }
            else if (action.equals(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION) ||
                    action.equals(GpsLocationProvider.GPS_FIX_CHANGE_ACTION)) {
                updateGps(intent);
            }
            else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION) ||
                    action.equals(AudioManager.VIBRATE_SETTING_CHANGED_ACTION)) {
                updateVolume();
            }
            else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                updateSimState(intent);
            }
        }
    };

    private StatusBarPolicy(Context context, StatusBarService service) {
        mContext = context;
        mService = service;
        mBatteryStats = BatteryStatsService.getService();

        // clock
        mCalendar = Calendar.getInstance(TimeZone.getDefault());
        mClockData = IconData.makeText("clock", "");
        mClockIcon = service.addIcon(mClockData, null);
        updateClock();

        // battery
        mBatteryData = IconData.makeIcon("battery",
                null, com.android.internal.R.drawable.stat_sys_battery_unknown, 0, 0);
        mBatteryIcon = service.addIcon(mBatteryData, null);

        // phone_signal
        mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mPhoneData = IconData.makeIcon("phone_signal", 
                null, com.android.internal.R.drawable.stat_sys_signal_null, 0, 0);
        mPhoneIcon = service.addIcon(mPhoneData, null);
        // register for phone state notifications.
        ((TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE))
                .listen(mPhoneStateListener,
                          PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTH
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY);

        // data_connection
        mDataData = IconData.makeIcon("data_connection",
                null, com.android.internal.R.drawable.stat_sys_data_connected_g, 0, 0);
        mDataIcon = service.addIcon(mDataData, null);
        service.setIconVisibility(mDataIcon, false);

        // wifi
        mWifiData = IconData.makeIcon("wifi", null, sWifiSignalImages[0], 0, 0);
        mWifiIcon = service.addIcon(mWifiData, null);
        service.setIconVisibility(mWifiIcon, false);
        // wifi will get updated by the sticky intents
        
        // bluetooth status
        mBluetoothData = IconData.makeIcon("bluetooth",
                null, com.android.internal.R.drawable.stat_sys_data_bluetooth, 0, 0);
        mBluetoothIcon = service.addIcon(mBluetoothData, null);
        BluetoothDevice bluetooth =
                (BluetoothDevice) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetooth != null) {
            mBluetoothEnabled = bluetooth.isEnabled();
        } else {
            mBluetoothEnabled = false;
        }
        mBluetoothA2dpState = BluetoothA2dp.STATE_DISCONNECTED;
        mBluetoothHeadsetState = BluetoothHeadset.STATE_DISCONNECTED;
        mService.setIconVisibility(mBluetoothIcon, mBluetoothEnabled);

        // Gps status
        mGpsEnabledIconData = IconData.makeIcon("gps",
                null, com.android.internal.R.drawable.stat_sys_gps_acquiring_anim, 0, 0);
        mGpsFixIconData = IconData.makeIcon("gps",
                null, com.android.internal.R.drawable.stat_sys_gps_on, 0, 0);
        mGpsIcon = service.addIcon(mGpsEnabledIconData, null);
        service.setIconVisibility(mGpsIcon, false);           

        // Alarm clock
        mAlarmClockIconData = IconData.makeIcon(
                "alarm_clock",
                null, com.android.internal.R.drawable.stat_notify_alarm, 0, 0);
        mAlarmClockIcon = service.addIcon(mAlarmClockIconData, null);
        service.setIconVisibility(mAlarmClockIcon, false);

        // Sync state
        mSyncActiveIcon = service.addIcon(IconData.makeIcon("sync_active",
                null, R.drawable.stat_notify_sync_anim0, 0, 0), null);
        mSyncFailingIcon = service.addIcon(IconData.makeIcon("sync_failing",
                null, R.drawable.stat_notify_sync_error, 0, 0), null);
        service.setIconVisibility(mSyncActiveIcon, false);
        service.setIconVisibility(mSyncFailingIcon, false);

        // volume
        mVolumeData = IconData.makeIcon("volume",
                null, com.android.internal.R.drawable.stat_sys_ringer_silent, 0, 0);
        mVolumeIcon = service.addIcon(mVolumeData, null);
        service.setIconVisibility(mVolumeIcon, false);
        updateVolume();
        
        IntentFilter filter = new IntentFilter();

        // Register for Intent broadcasts for...
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_ALARM_CHANGED);
        filter.addAction(Intent.ACTION_SYNC_STATE_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.VIBRATE_SETTING_CHANGED_ACTION);
        filter.addAction(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothA2dp.SINK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION);
        filter.addAction(GpsLocationProvider.GPS_FIX_CHANGE_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);
    }

    public static void installIcons(Context context, StatusBarService service) {
        sInstance = new StatusBarPolicy(context, service);
    }

    private final void updateClock() {
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        mClockData.text = DateFormat.getTimeFormat(mContext)
                .format(mCalendar.getTime());
        mService.updateIcon(mClockIcon, mClockData, null);
    }

    private final void updateAlarm(Intent intent) {
        boolean alarmSet = intent.getBooleanExtra("alarmSet", false);
        mService.setIconVisibility(mAlarmClockIcon, alarmSet);
    }

    private final void updateSyncState(Intent intent) {
        boolean isActive = intent.getBooleanExtra("active", false);
        boolean isFailing = intent.getBooleanExtra("failing", false);
        mService.setIconVisibility(mSyncActiveIcon, isActive);
        // Don't display sync failing icon: BUG 1297963 Set sync error timeout to "never"
        //mService.setIconVisibility(mSyncFailingIcon, isFailing && !isActive);
    }

    private void pickNextBatteryLevel(int level) {
        final int N = mBatteryThresholds.length;
        for (int i=0; i<N; i++) {
            if (level >= mBatteryThresholds[i]) {
                mBatteryThreshold = i;
                break;
            }
        }
        if (mBatteryThreshold >= N) {
            mBatteryThreshold = N-1;
        }
    }

    private final void updateBattery(Intent intent) {
        mBatteryData.iconId = intent.getIntExtra("icon-small", 0);
        mBatteryData.iconLevel = intent.getIntExtra("level", 0);
        mService.updateIcon(mBatteryIcon, mBatteryData, null);

        boolean plugged = intent.getIntExtra("plugged", 0) != 0;
        int level = intent.getIntExtra("level", -1);
        if (false) {
            Log.d(TAG, "updateBattery level=" + level
                    + " plugged=" + plugged
                    + " mBatteryPlugged=" + mBatteryPlugged
                    + " mBatteryLevel=" + mBatteryLevel
                    + " mBatteryThreshold=" + mBatteryThreshold
                    + " mBatteryFirst=" + mBatteryFirst);
        }

        boolean oldPlugged = mBatteryPlugged;
        int oldThreshold = mBatteryThreshold;
        pickNextBatteryLevel(level);

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
        /* else {
            if (plugged && !oldPlugged) {
                showBatteryView();
            }
        }
        */
        if (false) {
            Log.d(TAG, "plugged=" + plugged + " oldPlugged=" + oldPlugged + " level=" + level
                    + " mBatteryThreshold=" + mBatteryThreshold + " oldThreshold=" + oldThreshold);
        }
        if (!plugged
                && ((oldPlugged && level < mBatteryThresholds[BATTERY_THRESHOLD_WARNING])
                    || (mBatteryThreshold > oldThreshold
                        && mBatteryThreshold > BATTERY_THRESHOLD_WARNING))) {
            // Broadcast the low battery warning
            mContext.sendBroadcast(new Intent(Intent.ACTION_BATTERY_LOW));

            if (SHOW_LOW_BATTERY_WARNING) {
                if (false) {
                    Log.d(TAG, "mPhoneState=" + mPhoneState
                            + " mLowBatteryDialog=" + mLowBatteryDialog
                            + " mBatteryShowLowOnEndCall=" + mBatteryShowLowOnEndCall);
                }

                if (mPhoneState == TelephonyManager.CALL_STATE_IDLE) {
                    showLowBatteryWarning();
                } else {
                    mBatteryShowLowOnEndCall = true;
                }
            }
        } else if (mBatteryThreshold == BATTERY_THRESHOLD_CLOSE_WARNING) {
            if (SHOW_LOW_BATTERY_WARNING) {
                if (mLowBatteryDialog != null) {
                    mLowBatteryDialog.dismiss();
                    mBatteryShowLowOnEndCall = false;
                }
            }
        }
    }

    private void showBatteryView() {    
        closeLastBatteryView();
        if (mLowBatteryDialog != null) {
            mLowBatteryDialog.dismiss();
        }

        int level = mBatteryLevel;

        View v = View.inflate(mContext, com.android.internal.R.layout.battery_status, null);
        mBatteryView = v;
        int pixelFormat = PixelFormat.TRANSLUCENT;
        Drawable bg = v.getBackground();
        if (bg != null) {
            pixelFormat = bg.getOpacity();
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                pixelFormat);

        // Get the dim amount from the theme
        TypedArray a = mContext.obtainStyledAttributes(
                com.android.internal.R.styleable.Theme);
        lp.dimAmount = a.getFloat(android.R.styleable.Theme_backgroundDimAmount, 0.5f);
        a.recycle();
        
        lp.setTitle("Battery");

        TextView levelTextView = (TextView)v.findViewById(com.android.internal.R.id.level_percent);
        levelTextView.setText(mContext.getString(
                    com.android.internal.R.string.battery_status_text_percent_format, level));

        setBatteryLevel(v, com.android.internal.R.id.spacer, 100-level, 0, 0);
        setBatteryLevel(v, com.android.internal.R.id.level, level,
                com.android.internal.R.drawable.battery_charge_fill, level);

        WindowManagerImpl.getDefault().addView(v, lp);

        scheduleCloseBatteryView();
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

        int level = mBatteryThresholds[mBatteryThreshold > 1 ? mBatteryThreshold - 1 : 0];
        CharSequence levelText = mContext.getString(
                    com.android.internal.R.string.battery_low_percent_format, level);

        if (mBatteryLevelTextView != null) {
            mBatteryLevelTextView.setText(levelText);
        } else {
            View v = View.inflate(mContext, com.android.internal.R.layout.battery_low, null);
            mBatteryLevelTextView=(TextView)v.findViewById(com.android.internal.R.id.level_percent);

            mBatteryLevelTextView.setText(levelText);

            AlertDialog.Builder b = new AlertDialog.Builder(mContext);
                b.setCancelable(true);
                b.setTitle(com.android.internal.R.string.battery_low_title);
                b.setView(v);
                b.setIcon(android.R.drawable.ic_dialog_alert);
                b.setPositiveButton(android.R.string.ok, null);

            AlertDialog d = b.create();
            d.setOnDismissListener(mLowBatteryListener);
            d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            d.show();
            mLowBatteryDialog = d;
        }
    }

    private final void updateCallState(int state) {
        mPhoneState = state;
        if (false) {
            Log.d(TAG, "mPhoneState=" + mPhoneState
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

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthChanged(int asu) {
            mSignalAsu = asu;
            updateSignalStrength();
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            mServiceState = state;
            updateSignalStrength();
            updateDataIcon();
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            updateCallState(state);
        }

        @Override
        public void onDataConnectionStateChanged(int state) {
            mDataState = state;
            updateDataNetType();
            updateDataIcon();
        }

        @Override
        public void onDataActivity(int direction) {
            mDataActivity = direction;
            updateDataIcon();
        }
    };
    

    private final void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(SimCard.INTENT_KEY_SIM_STATE);
        if (SimCard.INTENT_VALUE_SIM_ABSENT.equals(stateExtra)) {
            mSimState = SimCard.State.ABSENT;
        }
        else if (SimCard.INTENT_VALUE_SIM_READY.equals(stateExtra)) {
            mSimState = SimCard.State.READY;
        }
        else if (SimCard.INTENT_VALUE_SIM_LOCKED.equals(stateExtra)) {
            final String lockedReason = intent.getStringExtra(SimCard.INTENT_KEY_LOCKED_REASON);
            if (SimCard.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = SimCard.State.PIN_REQUIRED;
            } 
            else if (SimCard.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = SimCard.State.PUK_REQUIRED;
            }
            else {
                mSimState = SimCard.State.NETWORK_LOCKED;
            }
        } else {
            mSimState = SimCard.State.UNKNOWN;
        }
        updateDataIcon();
    }

    private final void updateSignalStrength() {
        int asu = mSignalAsu;
        ServiceState ss = mServiceState;

        boolean hasService = true;
        
        if (ss != null) {
            int state = ss.getState();
            switch (state) {
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_POWER_OFF:
                    hasService = false;
                    break;
            }
        } else {
            hasService = false;
        }

        if (!hasService) {
            //Log.d(TAG, "updateSignalStrength: no service");
            if (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0) == 1) {
                mPhoneData.iconId = com.android.internal.R.drawable.stat_sys_signal_flightmode;
            } else {
                mPhoneData.iconId = com.android.internal.R.drawable.stat_sys_signal_null;
            }
            mService.updateIcon(mPhoneIcon, mPhoneData, null);
            return;
        }

        // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
        // asu = 0 (-113dB or less) is very weak
        // signal, its better to show 0 bars to the user in such cases.
        // asu = 99 is a special case, where the signal strength is unknown.
        if (asu <= 0 || asu == 99) asu = 0;
        else if (asu >= 16) asu = 4;
        else if (asu >= 8)  asu = 3;
        else if (asu >= 4)  asu = 2;
        else asu = 1;

        int[] iconList;
        if (mPhone.isNetworkRoaming()) {
            iconList = sSignalImages_r;
        } else {
            iconList = sSignalImages;
        }
        
        mPhoneData.iconId = iconList[asu];
        mService.updateIcon(mPhoneIcon, mPhoneData, null);
    }

    private final void updateDataNetType() {
        mDataNetType = mPhone.getNetworkType();
        switch (mDataNetType) {
            case TelephonyManager.NETWORK_TYPE_EDGE:
                mDataIconList = sDataNetType_e;
                break;
            case TelephonyManager.NETWORK_TYPE_UMTS:
                mDataIconList = sDataNetType_3g;
                break;
            default:
                mDataIconList = sDataNetType_g;
                break;
        }
    }

    private final void updateDataIcon() {
        int iconId;
        boolean visible = true;

        if (mSimState == SimCard.State.READY || mSimState == SimCard.State.UNKNOWN) {
            int data = mDataState;
            
            int[] list = mDataIconList;

            ServiceState ss = mServiceState;

            boolean hasService = false;

            if (ss != null) {
                hasService = (ss.getState() == ServiceState.STATE_IN_SERVICE);
            }

            if (hasService && data == TelephonyManager.DATA_CONNECTED) {
                switch (mDataActivity) {
                    case TelephonyManager.DATA_ACTIVITY_IN:
                        iconId = list[1];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_OUT:
                        iconId = list[2];
                        break;
                    case TelephonyManager.DATA_ACTIVITY_INOUT:
                        iconId = list[3];
                        break;
                    default:
                        iconId = list[0];
                        break;
                }
                mDataData.iconId = iconId;
                mService.updateIcon(mDataIcon, mDataData, null);
            } else {
                visible = false;
            }
        } else {
            mDataData.iconId = com.android.internal.R.drawable.stat_sys_no_sim;
            mService.updateIcon(mDataIcon, mDataData, null);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneDataConnectionState(mDataNetType, visible);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (mDataIconVisible != visible) {
            mService.setIconVisibility(mDataIcon, visible);
            mDataIconVisible = visible;
        }
    }

    private final void updateVolume() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        final int ringerMode = audioManager.getRingerMode();
        final boolean visible = ringerMode == AudioManager.RINGER_MODE_SILENT ||
                ringerMode == AudioManager.RINGER_MODE_VIBRATE; 
        final int iconId = audioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)
                ? com.android.internal.R.drawable.stat_sys_ringer_vibrate
                : com.android.internal.R.drawable.stat_sys_ringer_silent;

        if (visible) {
            mVolumeData.iconId = iconId;
            mService.updateIcon(mVolumeIcon, mVolumeData, null);
        }
        if (visible != mVolumeVisible) {
            mService.setIconVisibility(mVolumeIcon, visible);
            mVolumeVisible = visible;
        }
    }

    private final void updateBluetooth(Intent intent) {
        int iconId = com.android.internal.R.drawable.stat_sys_data_bluetooth;

        String action = intent.getAction();
        if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)) {
            int state = intent.getIntExtra(BluetoothIntent.BLUETOOTH_STATE,
                                           BluetoothError.ERROR);
            mBluetoothEnabled = state == BluetoothDevice.BLUETOOTH_STATE_ON;
        } else if (action.equals(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION)) {
            mBluetoothHeadsetState = intent.getIntExtra(BluetoothIntent.HEADSET_STATE,
                    BluetoothHeadset.STATE_ERROR);
        } else if (action.equals(BluetoothA2dp.SINK_STATE_CHANGED_ACTION)) {
            mBluetoothA2dpState = intent.getIntExtra(BluetoothA2dp.SINK_STATE,
                    BluetoothA2dp.STATE_DISCONNECTED);
        } else {
            return;
        }
        
        if (mBluetoothHeadsetState == BluetoothHeadset.STATE_CONNECTED ||
                mBluetoothA2dpState == BluetoothA2dp.STATE_CONNECTED ||
                mBluetoothA2dpState == BluetoothA2dp.STATE_PLAYING) {
            iconId = com.android.internal.R.drawable.stat_sys_data_bluetooth_connected;
        }

        mBluetoothData.iconId = iconId;
        mService.updateIcon(mBluetoothIcon, mBluetoothData, null);
        mService.setIconVisibility(mBluetoothIcon, mBluetoothEnabled);
    }

    private final void updateWifi(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            
            final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
            
            if (!enabled) {
                // If disabled, hide the icon. (We show icon when connected.)
                mService.setIconVisibility(mWifiIcon, false);
            }
            
        } else if (action.equals(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)) {
            final boolean enabled = intent.getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED,
                                                           false);
            if (!enabled) {
                mService.setIconVisibility(mWifiIcon, false);
            }
        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {

            final NetworkInfo networkInfo = (NetworkInfo) 
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            
            int iconId;
            if (networkInfo != null && networkInfo.isConnected()) {
                mIsWifiConnected = true;
                if (mLastWifiSignalLevel == -1) {
                    iconId = sWifiSignalImages[0];
                } else {
                    iconId = sWifiSignalImages[mLastWifiSignalLevel];
                }
                
                // Show the icon since wi-fi is connected
                mService.setIconVisibility(mWifiIcon, true);
                
            } else {
                mLastWifiSignalLevel = -1;
                mIsWifiConnected = false;
                iconId = sWifiSignalImages[0];

                // Hide the icon since we're not connected
                mService.setIconVisibility(mWifiIcon, false);
            }

            mWifiData.iconId = iconId;
            mService.updateIcon(mWifiIcon, mWifiData, null);
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            final int newRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi,
                                                                  sWifiSignalImages.length);
            if (newSignalLevel != mLastWifiSignalLevel) {
                mLastWifiSignalLevel = newSignalLevel;
                if (mIsWifiConnected) {
                    mWifiData.iconId = sWifiSignalImages[newSignalLevel];
                } else {
                    mWifiData.iconId = sWifiTemporarilyNotConnectedImage;
                }
                mService.updateIcon(mWifiIcon, mWifiData, null);
            }
        }
    }

    private final void updateGps(Intent intent) {
        final String action = intent.getAction();
        final boolean enabled = intent.getBooleanExtra(GpsLocationProvider.EXTRA_ENABLED, false);

        if (action.equals(GpsLocationProvider.GPS_FIX_CHANGE_ACTION) && enabled) {
            // GPS is getting fixes
            mService.updateIcon(mGpsIcon, mGpsFixIconData, null);
            mService.setIconVisibility(mGpsIcon, true);           
        } else if (action.equals(GpsLocationProvider.GPS_ENABLED_CHANGE_ACTION) && !enabled) {
            // GPS is off
            mService.setIconVisibility(mGpsIcon, false);           
        } else {
            // GPS is on, but not receiving fixes
            mService.updateIcon(mGpsIcon, mGpsEnabledIconData, null);
            mService.setIconVisibility(mGpsIcon, true);           
        }
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
