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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.server.am.BatteryStatsService;
import com.android.internal.util.AsyncChannel;

import com.android.systemui.R;

public class NetworkController extends BroadcastReceiver {
    // debug
    static final String TAG = "StatusBar.NetworkController";
    static final boolean DEBUG = false;

    // telephony
    boolean mHspaDataDistinguishable;
    final TelephonyManager mPhone;
    boolean mDataConnected;
    IccCard.State mSimState = IccCard.State.READY;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    int mDataState = TelephonyManager.DATA_DISCONNECTED;
    int mDataActivity = TelephonyManager.DATA_ACTIVITY_NONE;
    ServiceState mServiceState;
    SignalStrength mSignalStrength;
    int[] mDataIconList = TelephonyIcons.DATA_G[0];
    String mNetworkName;
    String mNetworkNameDefault;
    String mNetworkNameSeparator;
    int mPhoneSignalIconId;
    int mDataDirectionIconId;
    int mDataDirectionOverlayIconId;
    int mDataSignalIconId;
    int mDataTypeIconId;
    boolean mDataActive;

    String mContentDescriptionPhoneSignal;
    String mContentDescriptionWifi;
    String mContentDescriptionCombinedSignal;
    String mContentDescriptionDataType;

    // wifi
    final WifiManager mWifiManager;
    AsyncChannel mWifiChannel;
    boolean mWifiEnabled, mWifiConnected;
    int mWifiLevel;
    String mWifiSsid;
    int mWifiIconId = 0;
    int mWifiActivity = WifiManager.DATA_ACTIVITY_NONE;

    // bluetooth
    private boolean mBluetoothTethered = false;
    private int mBluetoothTetherIconId =
        com.android.internal.R.drawable.stat_sys_tether_bluetooth;

    // data connectivity (regardless of state, can we access the internet?)
    // state of inet connection - 0 not connected, 100 connected
    private int mInetCondition = 0;
    private static final int INET_CONDITION_THRESHOLD = 50;

    private boolean mAirplaneMode = false;

    // our ui
    Context mContext;
    ArrayList<ImageView> mPhoneSignalIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataDirectionIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataDirectionOverlayIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mWifiIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mCombinedSignalIconViews = new ArrayList<ImageView>();
    ArrayList<ImageView> mDataTypeIconViews = new ArrayList<ImageView>();
    ArrayList<TextView> mLabelViews = new ArrayList<TextView>();
    int mLastPhoneSignalIconId = -1;
    int mLastDataDirectionIconId = -1;
    int mLastDataDirectionOverlayIconId = -1;
    int mLastWifiIconId = -1;
    int mLastCombinedSignalIconId = -1;
    int mLastDataTypeIconId = -1;
    String mLastLabel = "";

    boolean mDataAndWifiStacked = false;

    // yuck -- stop doing this here and put it in the framework
    IBatteryStats mBatteryStats;

    /**
     * Construct this controller object and register for updates.
     */
    public NetworkController(Context context) {
        mContext = context;

        // set up the default wifi icon, used when no radios have ever appeared
        updateWifiIcons();

        // telephony
        mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mPhone.listen(mPhoneStateListener,
                          PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY);
        mHspaDataDistinguishable = mContext.getResources().getBoolean(
                R.bool.config_hspa_data_distinguishable);
        mNetworkNameSeparator = mContext.getString(R.string.status_bar_network_name_separator);
        mNetworkNameDefault = mContext.getString(
                com.android.internal.R.string.lockscreen_carrier_default);
        mNetworkName = mNetworkNameDefault;

        // wifi
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        Handler handler = new WifiHandler();
        mWifiChannel = new AsyncChannel();
        Messenger wifiMessenger = mWifiManager.getMessenger();
        if (wifiMessenger != null) {
            mWifiChannel.connect(mContext, handler, wifiMessenger);
        }

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        context.registerReceiver(this, filter);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode();

        // yuck
        mBatteryStats = BatteryStatsService.getService();
    }

    public void addPhoneSignalIconView(ImageView v) {
        mPhoneSignalIconViews.add(v);
    }

    public void addDataDirectionIconView(ImageView v) {
        mDataDirectionIconViews.add(v);
    }

    public void addDataDirectionOverlayIconView(ImageView v) {
        mDataDirectionOverlayIconViews.add(v);
    }

    public void addWifiIconView(ImageView v) {
        mWifiIconViews.add(v);
    }

    public void addCombinedSignalIconView(ImageView v) {
        mCombinedSignalIconViews.add(v);
    }

    public void addDataTypeIconView(ImageView v) {
        mDataTypeIconViews.add(v);
    }

    public void addLabelView(TextView v) {
        mLabelViews.add(v);
    }

    public void setStackedMode(boolean stacked) {
        mDataAndWifiStacked = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.RSSI_CHANGED_ACTION)
                || action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)
                || action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            updateWifiState(intent);
            refreshViews();
        } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
            updateSimState(intent);
            updateDataIcon();
            refreshViews();
        } else if (action.equals(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION)) {
            updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN));
            refreshViews();
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ||
                 action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
            updateConnectivity(intent);
            refreshViews();
        } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
            refreshViews();
        } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            updateAirplaneMode();
            refreshViews();
        }
    }


    // ===== Telephony ==============================================================

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (DEBUG) {
                Slog.d(TAG, "onSignalStrengthsChanged signalStrength=" + signalStrength);
            }
            mSignalStrength = signalStrength;
            updateTelephonySignalStrength();
            refreshViews();
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceStateChanged state=" + state.getState());
            }
            mServiceState = state;
            updateTelephonySignalStrength();
            updateDataIcon();
            refreshViews();
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DEBUG) {
                Slog.d(TAG, "onCallStateChanged state=" + state);
            }
            // In cdma, if a voice call is made, RSSI should switch to 1x.
            if (isCdma()) {
                updateTelephonySignalStrength();
                refreshViews();
            }
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (DEBUG) {
                Slog.d(TAG, "onDataConnectionStateChanged: state=" + state
                        + " type=" + networkType);
            }
            mDataState = state;
            mDataNetType = networkType;
            if (state < 0) {
                // device without a data connection
                mSignalStrength = null;
            }
            updateDataNetType();
            updateDataIcon();
            refreshViews();
        }

        @Override
        public void onDataActivity(int direction) {
            if (DEBUG) {
                Slog.d(TAG, "onDataActivity: direction=" + direction);
            }
            mDataActivity = direction;
            updateDataIcon();
            refreshViews();
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

    private boolean hasMobileDataFeature() {
        // XXX: HAX: replace when a more reliable method is available
        return (! "wifi-only".equals(SystemProperties.get("ro.carrier")));
    }


    private void updateAirplaneMode() {
        mAirplaneMode = (Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.AIRPLANE_MODE_ON, 0) == 1);
    }

    private final void updateTelephonySignalStrength() {
        if (!hasService()) {
            //Slog.d(TAG, "updateTelephonySignalStrength: no service");
            mPhoneSignalIconId = R.drawable.stat_sys_signal_null;
            mDataSignalIconId = R.drawable.stat_sys_signal_0; // note we use 0 instead of null
        } else {
            if (mSignalStrength == null) {
                mPhoneSignalIconId = R.drawable.stat_sys_signal_null;
                mDataSignalIconId = R.drawable.stat_sys_signal_0; // note we use 0 instead of null
                mContentDescriptionPhoneSignal = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0]);
            } else {
                int iconLevel;
                int[] iconList;
                iconLevel = mSignalStrength.getLevel();
                if (isCdma()) {
                    if (isCdmaEri()) {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                    } else {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                    }
                } else {
                    // Though mPhone is a Manager, this call is not an IPC
                    if (mPhone.isNetworkRoaming()) {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                    } else {
                        iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                    }
                }
                mPhoneSignalIconId = iconList[iconLevel];
                mContentDescriptionPhoneSignal = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[iconLevel]);

                mDataSignalIconId = TelephonyIcons.DATA_SIGNAL_STRENGTH[mInetCondition][iconLevel];
            }
        }
    }

    private final void updateDataNetType() {
        switch (mDataNetType) {
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                mDataIconList = TelephonyIcons.DATA_G[mInetCondition];
                mDataTypeIconId = 0;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_gprs);
                break;
            case TelephonyManager.NETWORK_TYPE_EDGE:
                mDataIconList = TelephonyIcons.DATA_E[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_edge;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_edge);
                break;
            case TelephonyManager.NETWORK_TYPE_UMTS:
                mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_3g;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_3g);
                break;
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                if (mHspaDataDistinguishable) {
                    mDataIconList = TelephonyIcons.DATA_H[mInetCondition];
                    mDataTypeIconId = R.drawable.stat_sys_signal_hsdpa;
                    mContentDescriptionDataType = mContext.getString(
                            R.string.accessibility_data_connection_3_5g);
                } else {
                    mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                    mDataTypeIconId = R.drawable.stat_sys_signal_3g;
                    mContentDescriptionDataType = mContext.getString(
                            R.string.accessibility_data_connection_3g);
                }
                break;
            case TelephonyManager.NETWORK_TYPE_CDMA:
                // display 1xRTT for IS95A/B
                mDataIconList = TelephonyIcons.DATA_1X[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_1x;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_cdma);
                break;
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                mDataIconList = TelephonyIcons.DATA_1X[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_1x;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_cdma);
                break;
            case TelephonyManager.NETWORK_TYPE_EVDO_0: //fall through
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                mDataIconList = TelephonyIcons.DATA_3G[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_3g;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_3g);
                break;
            case TelephonyManager.NETWORK_TYPE_LTE:
                mDataIconList = TelephonyIcons.DATA_4G[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_4g;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_4g);
                break;
            default:
                mDataIconList = TelephonyIcons.DATA_G[mInetCondition];
                mDataTypeIconId = R.drawable.stat_sys_signal_gprs;
                mContentDescriptionDataType = mContext.getString(
                        R.string.accessibility_data_connection_gprs);
                break;
        }
        if ((isCdma() && isCdmaEri()) || mPhone.isNetworkRoaming()) {
            mDataTypeIconId = R.drawable.stat_sys_signal_roam;
        }
    }

    boolean isCdmaEri() {
        if (mServiceState != null) {
            final int iconIndex = mServiceState.getCdmaEriIconIndex();
            if (iconIndex != EriInfo.ROAMING_INDICATOR_OFF) {
                final int iconMode = mServiceState.getCdmaEriIconMode();
                if (iconMode == EriInfo.ROAMING_ICON_MODE_NORMAL
                        || iconMode == EriInfo.ROAMING_ICON_MODE_FLASH) {
                    return true;
                }
            }
        }
        return false;
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
                    mDataDirectionIconId = iconId;
                } else {
                    iconId = 0;
                    visible = false;
                }
            } else {
                iconId = R.drawable.stat_sys_no_sim;
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
            } else {
                iconId = 0;
                visible = false;
            }
        }

        // yuck - this should NOT be done by the status bar
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.notePhoneDataConnectionState(mPhone.getNetworkType(), visible);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        mDataDirectionIconId = iconId;
        mDataConnected = visible;
    }

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Slog.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        boolean something = false;
        if (showPlmn && plmn != null) {
            str.append(plmn);
            something = true;
        }
        if (showSpn && spn != null) {
            if (something) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
            something = true;
        }
        if (something) {
            mNetworkName = str.toString();
        } else {
            mNetworkName = mNetworkNameDefault;
        }
    }

    // ===== Wifi ===================================================================

    class WifiHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiChannel.sendMessage(Message.obtain(this,
                                AsyncChannel.CMD_CHANNEL_FULL_CONNECTION));
                    } else {
                        Slog.e(TAG, "Failed to connect to wifi");
                    }
                    break;
                case WifiManager.DATA_ACTIVITY_NOTIFICATION:
                    if (msg.arg1 != mWifiActivity) {
                        mWifiActivity = msg.arg1;
                        refreshViews();
                    }
                    break;
                default:
                    //Ignore
                    break;
            }
        }
    }

    private void updateWifiState(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            mWifiEnabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            boolean wasConnected = mWifiConnected;
            mWifiConnected = networkInfo != null && networkInfo.isConnected();
            // If we just connected, grab the inintial signal strength and ssid
            if (mWifiConnected && !wasConnected) {
                WifiInfo info = mWifiManager.getConnectionInfo();
                if (info != null) {
                    mWifiLevel = WifiManager.calculateSignalLevel(info.getRssi(),
                            WifiIcons.WIFI_LEVEL_COUNT);
                    mWifiSsid = huntForSsid(info);
                } else {
                    mWifiLevel = 0;
                    mWifiSsid = null;
                }
            } else if (!mWifiConnected) {
                mWifiLevel = 0;
                mWifiSsid = null;
            }

        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            if (mWifiConnected) {
                final int newRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
                mWifiLevel = WifiManager.calculateSignalLevel(newRssi, WifiIcons.WIFI_LEVEL_COUNT);
            }
        }

        updateWifiIcons();
    }

    private void updateWifiIcons() {
        if (mWifiConnected) {
            mWifiIconId = WifiIcons.WIFI_SIGNAL_STRENGTH[mInetCondition][mWifiLevel];
            mContentDescriptionWifi = mContext.getString(
                    AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH[mWifiLevel]);
        } else {
            if (mDataAndWifiStacked) {
                mWifiIconId = 0;
            } else {
                mWifiIconId = WifiIcons.WIFI_SIGNAL_STRENGTH[0][0];
            }
            mContentDescriptionWifi = mContext.getString(R.string.accessibility_no_wifi);
        }
    }

    private String huntForSsid(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid != null) {
            return ssid;
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration net : networks) {
            if (net.networkId == info.getNetworkId()) {
                return net.SSID;
            }
        }
        return null;
    }


    // ===== Full or limited Internet connectivity ==================================

    private void updateConnectivity(Intent intent) {
        NetworkInfo info = (NetworkInfo)(intent.getParcelableExtra(
                ConnectivityManager.EXTRA_NETWORK_INFO));
        int connectionStatus = intent.getIntExtra(ConnectivityManager.EXTRA_INET_CONDITION, 0);

        int inetCondition = (connectionStatus > INET_CONDITION_THRESHOLD ? 1 : 0);

        switch (info.getType()) {
            case ConnectivityManager.TYPE_MOBILE:
                mInetCondition = inetCondition;
                updateDataNetType();
                updateDataIcon();
                updateTelephonySignalStrength(); // apply any change in connectionStatus
                break;
            case ConnectivityManager.TYPE_WIFI:
                mInetCondition = inetCondition;
                updateWifiIcons();
                break;
            case ConnectivityManager.TYPE_BLUETOOTH:
                mInetCondition = inetCondition;
                if (info != null) {
                    mBluetoothTethered = info.isConnected() ? true: false;
                } else {
                    mBluetoothTethered = false;
                }
                break;
        }
    }


    // ===== Update the views =======================================================

    // figure out what to show- there should be one connected network or nothing
    // General order of preference is: wifi, 3G than bluetooth. This might vary by product.
    void refreshViews() {
        Context context = mContext;

        int combinedSignalIconId;
        int dataDirectionOverlayIconId = 0;
        int dataTypeIconId;
        String label;
        int N;

        if (mWifiConnected) {
            if (mWifiSsid == null) {
                label = context.getString(R.string.status_bar_settings_signal_meter_wifi_nossid);
            } else {
                label = mWifiSsid;
                switch (mWifiActivity) {
                    case WifiManager.DATA_ACTIVITY_IN:
                        dataDirectionOverlayIconId = R.drawable.stat_sys_wifi_in;
                        break;
                    case WifiManager.DATA_ACTIVITY_OUT:
                        dataDirectionOverlayIconId = R.drawable.stat_sys_wifi_out;
                        break;
                    case WifiManager.DATA_ACTIVITY_INOUT:
                        dataDirectionOverlayIconId = R.drawable.stat_sys_wifi_inout;
                        break;
                    case WifiManager.DATA_ACTIVITY_NONE:
                        break;
                }
            }
            combinedSignalIconId = mWifiIconId;
            mContentDescriptionCombinedSignal = mContentDescriptionWifi;
            dataTypeIconId = 0;
        } else if (mDataConnected) {
            label = mNetworkName;
            combinedSignalIconId = mDataSignalIconId;
            switch (mDataActivity) {
                case TelephonyManager.DATA_ACTIVITY_IN:
                    dataDirectionOverlayIconId = R.drawable.stat_sys_signal_in;
                    break;
                case TelephonyManager.DATA_ACTIVITY_OUT:
                    dataDirectionOverlayIconId = R.drawable.stat_sys_signal_out;
                    break;
                case TelephonyManager.DATA_ACTIVITY_INOUT:
                    dataDirectionOverlayIconId = R.drawable.stat_sys_signal_inout;
                    break;
                default:
                    dataDirectionOverlayIconId = 0;
                    break;
            }
            combinedSignalIconId = mDataSignalIconId;
            mContentDescriptionCombinedSignal = mContentDescriptionDataType;
            dataTypeIconId = mDataTypeIconId;
        } else if (mBluetoothTethered) {
            label = mContext.getString(R.string.bluetooth_tethered);
            combinedSignalIconId = mBluetoothTetherIconId;
            mContentDescriptionCombinedSignal = mContext.getString(
                    R.string.accessibility_bluetooth_tether);
            dataTypeIconId = 0;
        } else if (mAirplaneMode &&
                (mServiceState == null || (!hasService() && !mServiceState.isEmergencyOnly()))) {
            // Only display the flight-mode icon if not in "emergency calls only" mode.
            label = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            combinedSignalIconId = R.drawable.stat_sys_signal_flightmode;
            mContentDescriptionCombinedSignal = mContext.getString(
                    R.string.accessibility_airplane_mode);
            dataTypeIconId = 0;
        } else {
            label = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            // On devices without mobile radios, we want to show the wifi icon
            combinedSignalIconId =
                hasMobileDataFeature() ? mDataSignalIconId : mWifiIconId;
            mContentDescriptionCombinedSignal = hasMobileDataFeature()
                ? mContentDescriptionDataType : mContentDescriptionWifi;
            dataTypeIconId = 0;
        }

        if (DEBUG) {
            Slog.d(TAG, "refreshViews combinedSignalIconId=0x"
                    + Integer.toHexString(combinedSignalIconId)
                    + "/" + getResourceName(combinedSignalIconId)
                    + " dataDirectionOverlayIconId=0x" + Integer.toHexString(dataDirectionOverlayIconId)
                    + " mAirplaneMode=" + mAirplaneMode
                    + " mDataActivity=" + mDataActivity
                    + " mPhoneSignalIconId=0x" + Integer.toHexString(mPhoneSignalIconId)
                    + " mDataDirectionIconId=0x" + Integer.toHexString(mDataDirectionIconId)
                    + " mDataSignalIconId=0x" + Integer.toHexString(mDataSignalIconId)
                    + " mDataTypeIconId=0x" + Integer.toHexString(mDataTypeIconId)
                    + " mWifiIconId=0x" + Integer.toHexString(mWifiIconId)
                    + " mBluetoothTetherIconId=0x" + Integer.toHexString(mBluetoothTetherIconId));
        }

        // the phone icon on phones
        if (mLastPhoneSignalIconId != mPhoneSignalIconId) {
            mLastPhoneSignalIconId = mPhoneSignalIconId;
            N = mPhoneSignalIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mPhoneSignalIconViews.get(i);
                v.setImageResource(mPhoneSignalIconId);
                v.setContentDescription(mContentDescriptionPhoneSignal);
            }
        }

        // the data icon on phones
        if (mLastDataDirectionIconId != mDataDirectionIconId) {
            mLastDataDirectionIconId = mDataDirectionIconId;
            N = mDataDirectionIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataDirectionIconViews.get(i);
                v.setImageResource(mDataDirectionIconId);
                v.setContentDescription(mContentDescriptionDataType);
            }
        }

        // the wifi icon on phones
        if (mLastWifiIconId != mWifiIconId) {
            mLastWifiIconId = mWifiIconId;
            N = mWifiIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mWifiIconViews.get(i);
                if (mWifiIconId == 0) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(mWifiIconId);
                    v.setContentDescription(mContentDescriptionWifi);
                }
            }
        }

        // the combined data signal icon
        if (mLastCombinedSignalIconId != combinedSignalIconId) {
            mLastCombinedSignalIconId = combinedSignalIconId;
            N = mCombinedSignalIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mCombinedSignalIconViews.get(i);
                v.setImageResource(combinedSignalIconId);
                v.setContentDescription(mContentDescriptionCombinedSignal);
            }
        }

        // the data network type overlay
        if (mLastDataTypeIconId != dataTypeIconId) {
            mLastDataTypeIconId = dataTypeIconId;
            N = mDataTypeIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataTypeIconViews.get(i);
                if (dataTypeIconId == 0) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(dataTypeIconId);
                    v.setContentDescription(mContentDescriptionDataType);
                }
            }
        }

        // the data direction overlay
        if (mLastDataDirectionOverlayIconId != dataDirectionOverlayIconId) {
            if (DEBUG) {
                Slog.d(TAG, "changing data overlay icon id to " + dataDirectionOverlayIconId);
            }
            mLastDataDirectionOverlayIconId = dataDirectionOverlayIconId;
            N = mDataDirectionOverlayIconViews.size();
            for (int i=0; i<N; i++) {
                final ImageView v = mDataDirectionOverlayIconViews.get(i);
                if (dataDirectionOverlayIconId == 0) {
                    v.setVisibility(View.INVISIBLE);
                } else {
                    v.setVisibility(View.VISIBLE);
                    v.setImageResource(dataDirectionOverlayIconId);
                    v.setContentDescription(mContentDescriptionDataType);
                }
            }
        }

        // the label in the notification panel
        if (!mLastLabel.equals(label)) {
            mLastLabel = label;
            N = mLabelViews.size();
            for (int i=0; i<N; i++) {
                TextView v = mLabelViews.get(i);
                v.setText(label);
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("  - telephony ------");
        pw.print("  mHspaDataDistinguishable=");
        pw.println(mHspaDataDistinguishable);
        pw.print("  mDataConnected=");
        pw.println(mDataConnected);
        pw.print("  mSimState=");
        pw.println(mSimState);
        pw.print("  mPhoneState=");
        pw.println(mPhoneState);
        pw.print("  mDataState=");
        pw.println(mDataState);
        pw.print("  mDataActivity=");
        pw.println(mDataActivity);
        pw.print("  mServiceState=");
        pw.println(mServiceState);
        pw.print("  mNetworkName=");
        pw.println(mNetworkName);
        pw.print("  mNetworkNameDefault=");
        pw.println(mNetworkNameDefault);
        pw.print("  mNetworkNameSeparator=");
        pw.println(mNetworkNameSeparator);
        pw.print("  mPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mPhoneSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mPhoneSignalIconId));
        pw.print("  mDataDirectionIconId=");
        pw.print(Integer.toHexString(mDataDirectionIconId));
        pw.print("/");
        pw.println(getResourceName(mDataDirectionIconId));
        pw.print("  mDataSignalIconId=");
        pw.print(Integer.toHexString(mDataSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mDataSignalIconId));
        pw.print("  mDataTypeIconId=");
        pw.print(Integer.toHexString(mDataTypeIconId));
        pw.print("/");
        pw.println(getResourceName(mDataTypeIconId));

        pw.println("  - wifi ------");
        pw.print("  mWifiEnabled=");
        pw.println(mWifiEnabled);
        pw.print("  mWifiConnected=");
        pw.println(mWifiConnected);
        pw.print("  mWifiLevel=");
        pw.println(mWifiLevel);
        pw.print("  mWifiSsid=");
        pw.println(mWifiSsid);
        pw.print("  mWifiIconId=");
        pw.println(mWifiIconId);
        pw.print("  mWifiActivity=");
        pw.println(mWifiActivity);


        pw.println("  - Bluetooth ----");
        pw.print(" mBtReverseTethered=");
        pw.println(mBluetoothTethered);

        pw.println("  - connectivity ------");
        pw.print("  mInetCondition=");
        pw.println(mInetCondition);

        pw.println("  - icons ------");
        pw.print("  mLastPhoneSignalIconId=0x");
        pw.print(Integer.toHexString(mLastPhoneSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mLastPhoneSignalIconId));
        pw.print("  mLastDataDirectionIconId=0x");
        pw.print(Integer.toHexString(mLastDataDirectionIconId));
        pw.print("/");
        pw.println(getResourceName(mLastDataDirectionIconId));
        pw.print("  mLastDataDirectionOverlayIconId=0x");
        pw.print(Integer.toHexString(mLastDataDirectionOverlayIconId));
        pw.print("/");
        pw.println(getResourceName(mLastDataDirectionOverlayIconId));
        pw.print("  mLastWifiIconId=0x");
        pw.print(Integer.toHexString(mLastWifiIconId));
        pw.print("/");
        pw.println(getResourceName(mLastWifiIconId));
        pw.print("  mLastCombinedSignalIconId=0x");
        pw.print(Integer.toHexString(mLastCombinedSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mLastCombinedSignalIconId));
        pw.print("  mLastDataTypeIconId=0x");
        pw.print(Integer.toHexString(mLastDataTypeIconId));
        pw.print("/");
        pw.println(getResourceName(mLastCombinedSignalIconId));
        pw.print("  mLastLabel=");
        pw.print(mLastLabel);
    }

    private String getResourceName(int resId) {
        if (resId == 0) {
            final Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

}
