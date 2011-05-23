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

package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothPan;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;
/**
 * @hide
 *
 * Timeout
 *
 * TODO - look for parent classes and code sharing
 */

public class Tethering extends INetworkManagementEventObserver.Stub {

    private Context mContext;
    private final static String TAG = "Tethering";
    private final static boolean DEBUG = true;

    private boolean mBooted = false;
    //used to remember if we got connected before boot finished
    private boolean mDeferedUsbConnection = false;

    // TODO - remove both of these - should be part of interface inspection/selection stuff
    private String[] mTetherableUsbRegexs;
    private String[] mTetherableWifiRegexs;
    private String[] mTetherableBluetoothRegexs;
    private String[] mUpstreamIfaceRegexs;

    private INetworkManagementService mNMService;
    private Looper mLooper;
    private HandlerThread mThread;

    private HashMap<String, TetherInterfaceSM> mIfaces; // all tethered/tetherable ifaces

    private BroadcastReceiver mStateReceiver;

    private static final String USB_NEAR_IFACE_ADDR      = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH        = 24;

    // USB is  192.168.42.1 and 255.255.255.0
    // Wifi is 192.168.43.1 and 255.255.255.0
    // BT is limited to max default of 5 connections. 192.168.44.1 to 192.168.48.1
    // with 255.255.255.0

    private String[] mDhcpRange;
    private static final String[] DHCP_DEFAULT_RANGE = {
        "192.168.42.2", "192.168.42.254", "192.168.43.2", "192.168.43.254",
        "192.168.44.2", "192.168.44.254", "192.168.45.2", "192.168.45.254",
        "192.168.46.2", "192.168.46.254", "192.168.47.2", "192.168.47.254",
        "192.168.48.2", "192.168.48.254",
    };

    private String[] mDnsServers;
    private static final String DNS_DEFAULT_SERVER1 = "8.8.8.8";
    private static final String DNS_DEFAULT_SERVER2 = "8.8.4.4";

    // resampled each time we turn on tethering - used as cache for settings/config-val
    private boolean mDunRequired;  // configuration info - must use DUN apn on 3g

    private StateMachine mTetherMasterSM;

    private Notification mTetheredNotification;

    // whether we can tether is the && of these two - they come in as separate
    // broadcasts so track them so we can decide what to do when either changes
    private boolean mUsbMassStorageOff;  // track the status of USB Mass Storage
    private boolean mUsbConnected;       // track the status of USB connection

    public Tethering(Context context, INetworkManagementService nmService, Looper looper) {
        mContext = context;
        mNMService = nmService;
        mLooper = looper;

        mIfaces = new HashMap<String, TetherInterfaceSM>();

        // make our own thread so we don't anr the system
        mThread = new HandlerThread("Tethering");
        mThread.start();
        mLooper = mThread.getLooper();
        mTetherMasterSM = new TetherMasterSM("TetherMaster", mLooper);
        mTetherMasterSM.start();

        mStateReceiver = new StateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_STATE);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiver(mStateReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        filter.addDataScheme("file");
        mContext.registerReceiver(mStateReceiver, filter);

        mUsbMassStorageOff = !Environment.MEDIA_SHARED.equals(
                Environment.getExternalStorageState());

        mDhcpRange = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_dhcp_range);
        if ((mDhcpRange.length == 0) || (mDhcpRange.length % 2 ==1)) {
            mDhcpRange = DHCP_DEFAULT_RANGE;
        }
        mDunRequired = false; // resample when we turn on

        mTetherableUsbRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_usb_regexs);
        mTetherableWifiRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_wifi_regexs);
        mTetherableBluetoothRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_bluetooth_regexs);
        mUpstreamIfaceRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_upstream_regexs);

        // TODO - remove and rely on real notifications of the current iface
        mDnsServers = new String[2];
        mDnsServers[0] = DNS_DEFAULT_SERVER1;
        mDnsServers[1] = DNS_DEFAULT_SERVER2;
    }

    public void interfaceLinkStatusChanged(String iface, boolean link) {
        if (DEBUG) Log.d(TAG, "interfaceLinkStatusChanged " + iface + ", " + link);
        boolean found = false;
        boolean usb = false;
        if (isWifi(iface)) {
            found = true;
        } else if (isUsb(iface)) {
            found = true;
            usb = true;
        } else if (isBluetooth(iface)) {
            found = true;
        }
        if (found == false) return;

        synchronized (mIfaces) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (link) {
                if (sm == null) {
                    sm = new TetherInterfaceSM(iface, mLooper, usb);
                    mIfaces.put(iface, sm);
                    sm.start();
                }
            } else {
                if (sm != null) {
                    sm.sendMessage(TetherInterfaceSM.CMD_INTERFACE_DOWN);
                    mIfaces.remove(iface);
                }
            }
        }
    }

    private boolean isUsb(String iface) {
        for (String regex : mTetherableUsbRegexs) {
            if (iface.matches(regex)) return true;
        }
        return false;
    }

    public boolean isWifi(String iface) {
        for (String regex : mTetherableWifiRegexs) {
            if (iface.matches(regex)) return true;
        }
        return false;
    }

    public boolean isBluetooth(String iface) {
        for (String regex : mTetherableBluetoothRegexs) {
            if (iface.matches(regex)) return true;
        }
        return false;
    }
    public void interfaceAdded(String iface) {
        boolean found = false;
        boolean usb = false;
        if (isWifi(iface)) {
            found = true;
        }
        if (isUsb(iface)) {
            found = true;
            usb = true;
        }
        if (isBluetooth(iface)) {
            found = true;
        }
        if (found == false) {
            if (DEBUG) Log.d(TAG, iface + " is not a tetherable iface, ignoring");
            return;
        }

        synchronized (mIfaces) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (sm != null) {
                if (DEBUG) Log.d(TAG, "active iface (" + iface + ") reported as added, ignoring");
                return;
            }
            sm = new TetherInterfaceSM(iface, mLooper, usb);
            mIfaces.put(iface, sm);
            sm.start();
        }
        if (DEBUG) Log.d(TAG, "interfaceAdded :" + iface);
    }

    public void interfaceRemoved(String iface) {
        synchronized (mIfaces) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (sm == null) {
                if (DEBUG) {
                    Log.e(TAG, "attempting to remove unknown iface (" + iface + "), ignoring");
                }
                return;
            }
            sm.sendMessage(TetherInterfaceSM.CMD_INTERFACE_DOWN);
            mIfaces.remove(iface);
        }
    }

    public int tether(String iface) {
        Log.d(TAG, "Tethering " + iface);
        TetherInterfaceSM sm = null;
        synchronized (mIfaces) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to Tether an unknown iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
        }
        if (!sm.isAvailable() && !sm.isErrored()) {
            Log.e(TAG, "Tried to Tether an unavailable iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNAVAIL_IFACE;
        }
        sm.sendMessage(TetherInterfaceSM.CMD_TETHER_REQUESTED);
        return ConnectivityManager.TETHER_ERROR_NO_ERROR;
    }

    public int untether(String iface) {
        Log.d(TAG, "Untethering " + iface);
        TetherInterfaceSM sm = null;
        synchronized (mIfaces) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to Untether an unknown iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
        }
        if (sm.isErrored()) {
            Log.e(TAG, "Tried to Untethered an errored iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNAVAIL_IFACE;
        }
        sm.sendMessage(TetherInterfaceSM.CMD_TETHER_UNREQUESTED);
        return ConnectivityManager.TETHER_ERROR_NO_ERROR;
    }

    public int getLastTetherError(String iface) {
        TetherInterfaceSM sm = null;
        synchronized (mIfaces) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to getLastTetherError on an unknown iface :" + iface + ", ignoring");
            return ConnectivityManager.TETHER_ERROR_UNKNOWN_IFACE;
        }
        return sm.getLastError();
    }

    private void sendTetherStateChangedBroadcast() {
        IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);
        try {
            if (!cm.isTetheringSupported()) return;
        } catch (RemoteException e) {
            return;
        }

        ArrayList<String> availableList = new ArrayList<String>();
        ArrayList<String> activeList = new ArrayList<String>();
        ArrayList<String> erroredList = new ArrayList<String>();

        boolean wifiTethered = false;
        boolean usbTethered = false;
        boolean bluetoothTethered = false;

        synchronized (mIfaces) {
            Set ifaces = mIfaces.keySet();
            for (Object iface : ifaces) {
                TetherInterfaceSM sm = mIfaces.get(iface);
                if (sm != null) {
                    if (sm.isErrored()) {
                        erroredList.add((String)iface);
                    } else if (sm.isAvailable()) {
                        availableList.add((String)iface);
                    } else if (sm.isTethered()) {
                        if (isUsb((String)iface)) {
                            usbTethered = true;
                        } else if (isWifi((String)iface)) {
                            wifiTethered = true;
                      } else if (isBluetooth((String)iface)) {
                            bluetoothTethered = true;
                        }
                        activeList.add((String)iface);
                    }
                }
            }
        }
        Intent broadcast = new Intent(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        broadcast.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        broadcast.putStringArrayListExtra(ConnectivityManager.EXTRA_AVAILABLE_TETHER,
                availableList);
        broadcast.putStringArrayListExtra(ConnectivityManager.EXTRA_ACTIVE_TETHER, activeList);
        broadcast.putStringArrayListExtra(ConnectivityManager.EXTRA_ERRORED_TETHER,
                erroredList);
        mContext.sendStickyBroadcast(broadcast);
        if (DEBUG) {
            Log.d(TAG, "sendTetherStateChangedBroadcast " + availableList.size() + ", " +
                    activeList.size() + ", " + erroredList.size());
        }

        if (usbTethered) {
            if (wifiTethered || bluetoothTethered) {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_general);
            } else {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_usb);
            }
        } else if (wifiTethered) {
            if (bluetoothTethered) {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_general);
            } else {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_wifi);
            }
        } else if (bluetoothTethered) {
            showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_bluetooth);
        } else {
            clearTetheredNotification();
        }
    }

    private void showTetheredNotification(int icon) {
        NotificationManager notificationManager =
                (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        if (mTetheredNotification != null) {
            if (mTetheredNotification.icon == icon) {
                return;
            }
            notificationManager.cancel(mTetheredNotification.icon);
        }

        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        Resources r = Resources.getSystem();
        CharSequence title = r.getText(com.android.internal.R.string.tethered_notification_title);
        CharSequence message = r.getText(com.android.internal.R.string.
                tethered_notification_message);

        if (mTetheredNotification == null) {
            mTetheredNotification = new Notification();
            mTetheredNotification.when = 0;
        }
        mTetheredNotification.icon = icon;
        mTetheredNotification.defaults &= ~Notification.DEFAULT_SOUND;
        mTetheredNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mTetheredNotification.tickerText = title;
        mTetheredNotification.setLatestEventInfo(mContext, title, message, pi);

        notificationManager.notify(mTetheredNotification.icon, mTetheredNotification);
    }

    private void clearTetheredNotification() {
        NotificationManager notificationManager =
            (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && mTetheredNotification != null) {
            notificationManager.cancel(mTetheredNotification.icon);
            mTetheredNotification = null;
        }
    }

    private void updateUsbStatus() {
        boolean enable = mUsbConnected && mUsbMassStorageOff;

        if (mBooted) {
            enableUsbIfaces(enable);
        }
    }

    private class StateReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getExtras().getBoolean(UsbManager.USB_CONNECTED);
                updateUsbStatus();
            } else if (action.equals(Intent.ACTION_MEDIA_SHARED)) {
                mUsbMassStorageOff = false;
                updateUsbStatus();
            }
            else if (action.equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mUsbMassStorageOff = true;
                updateUsbStatus();
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                if (DEBUG) Log.d(TAG, "Tethering got CONNECTIVITY_ACTION");
                mTetherMasterSM.sendMessage(TetherMasterSM.CMD_UPSTREAM_CHANGED);
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBooted = true;
                updateUsbStatus();
            }
        }
    }

    // used on cable insert/remove
    private void enableUsbIfaces(boolean enable) {
        String[] ifaces = new String[0];
        try {
            ifaces = mNMService.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces :" + e);
            return;
        }
        for (String iface : ifaces) {
            if (isUsb(iface)) {
                if (enable) {
                    interfaceAdded(iface);
                } else {
                    interfaceRemoved(iface);
                }
            }
        }
    }

    // toggled when we enter/leave the fully tethered state
    private boolean enableUsbRndis(boolean enabled) {
        if (DEBUG) Log.d(TAG, "enableUsbRndis(" + enabled + ")");

        try {
            if (enabled) {
                synchronized (this) {
                    if (!mNMService.isUsbRNDISStarted()) {
                        mNMService.startUsbRNDIS();
                    }
                }
            } else {
                if (mNMService.isUsbRNDISStarted()) {
                    mNMService.stopUsbRNDIS();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling usb RNDIS :" + e);
            return false;
        }
        return true;
    }

    // configured when we start tethering and unconfig'd on error or conclusion
    private boolean configureUsbIface(boolean enabled) {
        if (DEBUG) Log.d(TAG, "configureUsbIface(" + enabled + ")");

        // bring toggle the interfaces
        String[] ifaces = new String[0];
        try {
            ifaces = mNMService.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces :" + e);
            return false;
        }
        for (String iface : ifaces) {
            if (isUsb(iface)) {
                InterfaceConfiguration ifcg = null;
                try {
                    ifcg = mNMService.getInterfaceConfig(iface);
                    if (ifcg != null) {
                        InetAddress addr = NetworkUtils.numericToInetAddress(USB_NEAR_IFACE_ADDR);
                        ifcg.addr = new LinkAddress(addr, USB_PREFIX_LENGTH);
                        if (enabled) {
                            ifcg.interfaceFlags = ifcg.interfaceFlags.replace("down", "up");
                        } else {
                            ifcg.interfaceFlags = ifcg.interfaceFlags.replace("up", "down");
                        }
                        ifcg.interfaceFlags = ifcg.interfaceFlags.replace("running", "");
                        ifcg.interfaceFlags = ifcg.interfaceFlags.replace("  "," ");
                        mNMService.setInterfaceConfig(iface, ifcg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error configuring interface " + iface + ", :" + e);
                    return false;
                }
            }
        }

        return true;
    }

    public String[] getTetherableUsbRegexs() {
        return mTetherableUsbRegexs;
    }

    public String[] getTetherableWifiRegexs() {
        return mTetherableWifiRegexs;
    }

    public String[] getTetherableBluetoothRegexs() {
        return mTetherableBluetoothRegexs;
    }

    public String[] getUpstreamIfaceRegexs() {
        return mUpstreamIfaceRegexs;
    }

    public boolean isDunRequired() {
        boolean defaultVal = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_tether_dun_required);
        boolean result = (Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.TETHER_DUN_REQUIRED, (defaultVal ? 1 : 0)) == 1);
        return result;
    }

    public String[] getTetheredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mIfaces) {
            Set keys = mIfaces.keySet();
            for (Object key : keys) {
                TetherInterfaceSM sm = mIfaces.get(key);
                if (sm.isTethered()) {
                    list.add((String)key);
                }
            }
        }
        String[] retVal = new String[list.size()];
        for (int i=0; i < list.size(); i++) {
            retVal[i] = list.get(i);
        }
        return retVal;
    }

    public String[] getTetherableIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mIfaces) {
            Set keys = mIfaces.keySet();
            for (Object key : keys) {
                TetherInterfaceSM sm = mIfaces.get(key);
                if (sm.isAvailable()) {
                    list.add((String)key);
                }
            }
        }
        String[] retVal = new String[list.size()];
        for (int i=0; i < list.size(); i++) {
            retVal[i] = list.get(i);
        }
        return retVal;
    }

    public String[] getErroredIfaces() {
        ArrayList<String> list = new ArrayList<String>();
        synchronized (mIfaces) {
            Set keys = mIfaces.keySet();
            for (Object key : keys) {
                TetherInterfaceSM sm = mIfaces.get(key);
                if (sm.isErrored()) {
                    list.add((String)key);
                }
            }
        }
        String[] retVal = new String[list.size()];
        for (int i= 0; i< list.size(); i++) {
            retVal[i] = list.get(i);
        }
        return retVal;
    }

    public void handleTetherIfaceChange(String iface) {
        // check if iface is white listed
        for (String regex : mUpstreamIfaceRegexs) {
            if (iface.matches(regex)) {
                if (DEBUG) Log.d(TAG, "Tethering got Interface Change");
                mTetherMasterSM.sendMessage(TetherMasterSM.CMD_IFACE_CHANGED, iface);
                break;
            }
        }
    }

    class TetherInterfaceSM extends StateMachine {
        // notification from the master SM that it's not in tether mode
        static final int CMD_TETHER_MODE_DEAD            =  1;
        // request from the user that it wants to tether
        static final int CMD_TETHER_REQUESTED            =  2;
        // request from the user that it wants to untether
        static final int CMD_TETHER_UNREQUESTED          =  3;
        // notification that this interface is down
        static final int CMD_INTERFACE_DOWN              =  4;
        // notification that this interface is up
        static final int CMD_INTERFACE_UP                =  5;
        // notification from the master SM that it had an error turning on cellular dun
        static final int CMD_CELL_DUN_ERROR              =  6;
        // notification from the master SM that it had trouble enabling IP Forwarding
        static final int CMD_IP_FORWARDING_ENABLE_ERROR  =  7;
        // notification from the master SM that it had trouble disabling IP Forwarding
        static final int CMD_IP_FORWARDING_DISABLE_ERROR =  8;
        // notification from the master SM that it had trouble staring tethering
        static final int CMD_START_TETHERING_ERROR       =  9;
        // notification from the master SM that it had trouble stopping tethering
        static final int CMD_STOP_TETHERING_ERROR        = 10;
        // notification from the master SM that it had trouble setting the DNS forwarders
        static final int CMD_SET_DNS_FORWARDERS_ERROR    = 11;
        // the upstream connection has changed
        static final int CMD_TETHER_CONNECTION_CHANGED   = 12;

        private State mDefaultState;

        private State mInitialState;
        private State mStartingState;
        private State mTetheredState;

        private State mUnavailableState;

        private boolean mAvailable;
        private boolean mTethered;
        int mLastError;

        String mIfaceName;
        String mMyUpstreamIfaceName;  // may change over time

        boolean mUsb;

        TetherInterfaceSM(String name, Looper looper, boolean usb) {
            super(name, looper);
            mIfaceName = name;
            mUsb = usb;
            setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);

            mInitialState = new InitialState();
            addState(mInitialState);
            mStartingState = new StartingState();
            addState(mStartingState);
            mTetheredState = new TetheredState();
            addState(mTetheredState);
            mUnavailableState = new UnavailableState();
            addState(mUnavailableState);

            setInitialState(mInitialState);
        }

        public String toString() {
            String res = new String();
            res += mIfaceName + " - ";
            IState current = getCurrentState();
            if (current == mInitialState) res += "InitialState";
            if (current == mStartingState) res += "StartingState";
            if (current == mTetheredState) res += "TetheredState";
            if (current == mUnavailableState) res += "UnavailableState";
            if (mAvailable) res += " - Available";
            if (mTethered) res += " - Tethered";
            res += " - lastError =" + mLastError;
            return res;
        }

        public synchronized int getLastError() {
            return mLastError;
        }

        private synchronized void setLastError(int error) {
            mLastError = error;

            if (isErrored()) {
                if (mUsb) {
                    // note everything's been unwound by this point so nothing to do on
                    // further error..
                    Tethering.this.configureUsbIface(false);
                }
            }
        }

        // synchronized between this getter and the following setter
        public synchronized boolean isAvailable() {
            return mAvailable;
        }

        private synchronized void setAvailable(boolean available) {
            mAvailable = available;
        }

        // synchronized between this getter and the following setter
        public synchronized boolean isTethered() {
            return mTethered;
        }

        private synchronized void setTethered(boolean tethered) {
            mTethered = tethered;
        }

        // synchronized between this getter and the following setter
        public synchronized boolean isErrored() {
            return (mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR);
        }

        class InitialState extends State {
            @Override
            public void enter() {
                setAvailable(true);
                setTethered(false);
                sendTetherStateChangedBroadcast();
            }

            @Override
            public boolean processMessage(Message message) {
                if (DEBUG) Log.d(TAG, "InitialState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_REQUESTED:
                        setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_REQUESTED,
                                TetherInterfaceSM.this);
                        transitionTo(mStartingState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        transitionTo(mUnavailableState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class StartingState extends State {
            @Override
            public void enter() {
                setAvailable(false);
                if (mUsb) {
                    if (!Tethering.this.configureUsbIface(true)) {
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);

                        transitionTo(mInitialState);
                        return;
                    }
                }
                sendTetherStateChangedBroadcast();

                // Skipping StartingState
                transitionTo(mTetheredState);
            }
            @Override
            public boolean processMessage(Message message) {
                if (DEBUG) Log.d(TAG, "StartingState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    // maybe a parent class?
                    case CMD_TETHER_UNREQUESTED:
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                                break;
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    case CMD_CELL_DUN_ERROR:
                    case CMD_IP_FORWARDING_ENABLE_ERROR:
                    case CMD_IP_FORWARDING_DISABLE_ERROR:
                    case CMD_START_TETHERING_ERROR:
                    case CMD_STOP_TETHERING_ERROR:
                    case CMD_SET_DNS_FORWARDERS_ERROR:
                        setLastErrorAndTransitionToInitialState(
                                ConnectivityManager.TETHER_ERROR_MASTER_ERROR);
                        break;
                    case CMD_INTERFACE_DOWN:
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        transitionTo(mUnavailableState);
                        break;
                    default:
                        retValue = false;
                }
                return retValue;
            }
        }

        class TetheredState extends State {
            @Override
            public void enter() {
                try {
                    mNMService.tetherInterface(mIfaceName);
                } catch (Exception e) {
                    setLastError(ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR);

                    transitionTo(mInitialState);
                    return;
                }
                if (mUsb) Tethering.this.enableUsbRndis(true);
                if (DEBUG) Log.d(TAG, "Tethered " + mIfaceName);
                setAvailable(false);
                setTethered(true);
                sendTetherStateChangedBroadcast();
            }
            @Override
            public void exit() {
                if (mUsb) Tethering.this.enableUsbRndis(false);
            }
            @Override
            public boolean processMessage(Message message) {
                if (DEBUG) Log.d(TAG, "TetheredState.processMessage what=" + message.what);
                boolean retValue = true;
                boolean error = false;
                switch (message.what) {
                    case CMD_TETHER_UNREQUESTED:
                    case CMD_INTERFACE_DOWN:
                        if (mMyUpstreamIfaceName != null) {
                            try {
                                mNMService.disableNat(mIfaceName, mMyUpstreamIfaceName);
                                mMyUpstreamIfaceName = null;
                            } catch (Exception e) {
                                try {
                                    mNMService.untetherInterface(mIfaceName);
                                } catch (Exception ee) {}

                                setLastErrorAndTransitionToInitialState(
                                        ConnectivityManager.TETHER_ERROR_DISABLE_NAT_ERROR);
                                break;
                            }
                        }
                        try {
                            mNMService.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                            break;
                        }
                        mTetherMasterSM.sendMessage(TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED,
                                TetherInterfaceSM.this);
                        if (message.what == CMD_TETHER_UNREQUESTED) {
                            if (mUsb) {
                                if (!Tethering.this.configureUsbIface(false)) {
                                    setLastError(
                                            ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                                }
                            }
                            transitionTo(mInitialState);
                        } else if (message.what == CMD_INTERFACE_DOWN) {
                            transitionTo(mUnavailableState);
                        }
                        if (DEBUG) Log.d(TAG, "Untethered " + mIfaceName);
                        break;
                    case CMD_TETHER_CONNECTION_CHANGED:
                        String newUpstreamIfaceName = (String)(message.obj);
                        if ((mMyUpstreamIfaceName == null && newUpstreamIfaceName == null) ||
                                (mMyUpstreamIfaceName != null &&
                                mMyUpstreamIfaceName.equals(newUpstreamIfaceName))) {
                            if (DEBUG) Log.d(TAG, "Connection changed noop - dropping");
                            break;
                        }
                        if (mMyUpstreamIfaceName != null) {
                            try {
                                mNMService.disableNat(mIfaceName, mMyUpstreamIfaceName);
                                mMyUpstreamIfaceName = null;
                            } catch (Exception e) {
                                try {
                                    mNMService.untetherInterface(mIfaceName);
                                } catch (Exception ee) {}

                                setLastErrorAndTransitionToInitialState(
                                        ConnectivityManager.TETHER_ERROR_DISABLE_NAT_ERROR);
                                break;
                            }
                        }
                        if (newUpstreamIfaceName != null) {
                            try {
                                mNMService.enableNat(mIfaceName, newUpstreamIfaceName);
                            } catch (Exception e) {
                                try {
                                    mNMService.untetherInterface(mIfaceName);
                                } catch (Exception ee) {}

                                setLastError(ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR);
                                transitionTo(mInitialState);
                                return true;
                            }
                        }
                        mMyUpstreamIfaceName = newUpstreamIfaceName;
                        break;
                    case CMD_CELL_DUN_ERROR:
                    case CMD_IP_FORWARDING_ENABLE_ERROR:
                    case CMD_IP_FORWARDING_DISABLE_ERROR:
                    case CMD_START_TETHERING_ERROR:
                    case CMD_STOP_TETHERING_ERROR:
                    case CMD_SET_DNS_FORWARDERS_ERROR:
                        error = true;
                        // fall through
                    case CMD_TETHER_MODE_DEAD:
                        if (mMyUpstreamIfaceName != null) {
                            try {
                                mNMService.disableNat(mIfaceName, mMyUpstreamIfaceName);
                                mMyUpstreamIfaceName = null;
                            } catch (Exception e) {
                                try {
                                    mNMService.untetherInterface(mIfaceName);
                                } catch (Exception ee) {}

                                setLastErrorAndTransitionToInitialState(
                                        ConnectivityManager.TETHER_ERROR_DISABLE_NAT_ERROR);
                                break;
                            }
                        }
                        try {
                            mNMService.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                            break;
                        }
                        if (error) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_MASTER_ERROR);
                            break;
                        }
                        if (DEBUG) Log.d(TAG, "Tether lost upstream connection " + mIfaceName);
                        sendTetherStateChangedBroadcast();
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class UnavailableState extends State {
            @Override
            public void enter() {
                setAvailable(false);
                setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
                setTethered(false);
                sendTetherStateChangedBroadcast();
            }
            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case CMD_INTERFACE_UP:
                        transitionTo(mInitialState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        void setLastErrorAndTransitionToInitialState(int error) {
            setLastError(error);
            transitionTo(mInitialState);
        }

    }

    class TetherMasterSM extends StateMachine {
        // an interface SM has requested Tethering
        static final int CMD_TETHER_MODE_REQUESTED   = 1;
        // an interface SM has unrequested Tethering
        static final int CMD_TETHER_MODE_UNREQUESTED = 2;
        // upstream connection change - do the right thing
        static final int CMD_UPSTREAM_CHANGED        = 3;
        // we received notice that the cellular DUN connection is up
        static final int CMD_CELL_CONNECTION_RENEW   = 4;
        // we don't have a valid upstream conn, check again after a delay
        static final int CMD_RETRY_UPSTREAM          = 5;
        // received an indication that upstream interface has changed
        static final int CMD_IFACE_CHANGED           = 6;

        // This indicates what a timeout event relates to.  A state that
        // sends itself a delayed timeout event and handles incoming timeout events
        // should inc this when it is entered and whenever it sends a new timeout event.
        // We do not flush the old ones.
        private int mSequenceNumber;

        private State mInitialState;
        private State mTetherModeAliveState;

        private State mSetIpForwardingEnabledErrorState;
        private State mSetIpForwardingDisabledErrorState;
        private State mStartTetheringErrorState;
        private State mStopTetheringErrorState;
        private State mSetDnsForwardersErrorState;

        private ArrayList mNotifyList;

        private int mCurrentConnectionSequence;
        private boolean mMobileReserved = false;

        private String mUpstreamIfaceName = null;

        private static final int UPSTREAM_SETTLE_TIME_MS     = 10000;
        private static final int CELL_CONNECTION_RENEW_MS    = 40000;

        TetherMasterSM(String name, Looper looper) {
            super(name, looper);

            //Add states
            mInitialState = new InitialState();
            addState(mInitialState);
            mTetherModeAliveState = new TetherModeAliveState();
            addState(mTetherModeAliveState);

            mSetIpForwardingEnabledErrorState = new SetIpForwardingEnabledErrorState();
            addState(mSetIpForwardingEnabledErrorState);
            mSetIpForwardingDisabledErrorState = new SetIpForwardingDisabledErrorState();
            addState(mSetIpForwardingDisabledErrorState);
            mStartTetheringErrorState = new StartTetheringErrorState();
            addState(mStartTetheringErrorState);
            mStopTetheringErrorState = new StopTetheringErrorState();
            addState(mStopTetheringErrorState);
            mSetDnsForwardersErrorState = new SetDnsForwardersErrorState();
            addState(mSetDnsForwardersErrorState);

            mNotifyList = new ArrayList();
            setInitialState(mInitialState);
        }

        class TetherMasterUtilState extends State {
            protected final static boolean TRY_TO_SETUP_MOBILE_CONNECTION = true;
            protected final static boolean WAIT_FOR_NETWORK_TO_SETTLE     = false;

            @Override
            public boolean processMessage(Message m) {
                return false;
            }
            protected boolean turnOnMobileConnection() {
                boolean retValue = true;
                if (mMobileReserved) return retValue;
                IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);
                int result = Phone.APN_REQUEST_FAILED;
                try {
                    result = cm.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            (mDunRequired ? Phone.FEATURE_ENABLE_DUN_ALWAYS :
                            Phone.FEATURE_ENABLE_HIPRI), new Binder());
                } catch (Exception e) {
                }
                switch (result) {
                case Phone.APN_ALREADY_ACTIVE:
                case Phone.APN_REQUEST_STARTED:
                    mMobileReserved = true;
                    Message m = obtainMessage(CMD_CELL_CONNECTION_RENEW);
                    m.arg1 = ++mCurrentConnectionSequence;
                    sendMessageDelayed(m, CELL_CONNECTION_RENEW_MS);
                    break;
                case Phone.APN_REQUEST_FAILED:
                default:
                    retValue = false;
                    break;
                }

                return retValue;
            }
            protected boolean turnOffMobileConnection() {
                if (mMobileReserved) {
                    IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                    IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);
                    try {
                        cm.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                                (mDunRequired? Phone.FEATURE_ENABLE_DUN_ALWAYS :
                                             Phone.FEATURE_ENABLE_HIPRI));
                    } catch (Exception e) {
                        return false;
                    }
                    mMobileReserved = false;
                }
                return true;
            }
            protected boolean turnOnMasterTetherSettings() {
                try {
                    mNMService.setIpForwardingEnabled(true);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingEnabledErrorState);
                    return false;
                }
                try {
                    mNMService.startTethering(mDhcpRange);
                } catch (Exception e) {
                    try {
                        mNMService.stopTethering();
                        mNMService.startTethering(mDhcpRange);
                    } catch (Exception ee) {
                        transitionTo(mStartTetheringErrorState);
                        return false;
                    }
                }
                try {
                    mNMService.setDnsForwarders(mDnsServers);
                } catch (Exception e) {
                    transitionTo(mSetDnsForwardersErrorState);
                    return false;
                }
                return true;
            }
            protected boolean turnOffMasterTetherSettings() {
                try {
                    mNMService.stopTethering();
                } catch (Exception e) {
                    transitionTo(mStopTetheringErrorState);
                    return false;
                }
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingDisabledErrorState);
                    return false;
                }
                transitionTo(mInitialState);
                return true;
            }
            protected String findActiveUpstreamIface() {
                // check for what iface we can use - if none found switch to error.
                IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);

                try {
                    LinkProperties defaultProp = cm.getActiveLinkProperties();
                    if (defaultProp != null) {
                        String iface = defaultProp.getInterfaceName();
                        for(String regex : mUpstreamIfaceRegexs) {
                            if (iface.matches(regex)) return iface;
                        }
                    }
                } catch (RemoteException e) { }

                String[] ifaces = new String[0];
                try {
                    ifaces = mNMService.listInterfaces();
                } catch (Exception e) {
                    Log.e(TAG, "Error listing Interfaces :" + e);
                    return null;
                }

                for (String iface : ifaces) {
                    for (String regex : mUpstreamIfaceRegexs) {
                        if (iface.matches(regex)) {
                            // verify it is active
                            InterfaceConfiguration ifcg = null;
                            try {
                                ifcg = mNMService.getInterfaceConfig(iface);
                                if (ifcg.isActive()) {
                                    return iface;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error getting iface config :" + e);
                                // ignore - try next
                                continue;
                            }
                        }
                    }
                }
                return null;
            }

            protected void chooseUpstreamType(boolean tryCell) {
                // decide if the current upstream is good or not and if not
                // do something about it (start up DUN if required or HiPri if not)
                String iface = findActiveUpstreamIface();
                IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager cm = IConnectivityManager.Stub.asInterface(b);
                mMobileReserved = false;
                if (DEBUG) {
                    Log.d(TAG, "chooseUpstreamType(" + tryCell + "),  dunRequired ="
                            + mDunRequired + ", iface=" + iface);
                }
                if (iface != null) {
                    try {
                        if (mDunRequired) {
                            // check if Dun is on - we can use that
                            NetworkInfo info = cm.getNetworkInfo(
                                    ConnectivityManager.TYPE_MOBILE_DUN);
                            if (info.isConnected()) {
                                if (DEBUG) Log.d(TAG, "setting dun ifacename =" + iface);
                                // even if we're already connected - it may be somebody else's
                                // refcount, so add our own
                                turnOnMobileConnection();
                            } else {
                                // verify the iface is not the default mobile - can't use that!
                                info = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
                                if (info.isConnected()) {
                                    iface = null; // can't accept this one
                                }
                            }
                        } else {
                            if (DEBUG) Log.d(TAG, "checking if hipri brought us this connection");
                            NetworkInfo info = cm.getNetworkInfo(
                                    ConnectivityManager.TYPE_MOBILE_HIPRI);
                            if (info.isConnected()) {
                                if (DEBUG) Log.d(TAG, "yes - hipri in use");
                                // even if we're already connected - it may be sombody else's
                                // refcount, so add our own
                                turnOnMobileConnection();
                            }
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "RemoteException calling ConnectivityManager " + e);
                        iface = null;
                    }
                }
                // may have been set to null in the if above
                if (iface == null ) {
                    boolean success = false;
                    if (tryCell == TRY_TO_SETUP_MOBILE_CONNECTION) {
                        success = turnOnMobileConnection();
                    }
                    if (!success) {
                        // wait for things to settle and retry
                        sendMessageDelayed(CMD_RETRY_UPSTREAM, UPSTREAM_SETTLE_TIME_MS);
                    }
                }
                notifyTetheredOfNewUpstreamIface(iface);
            }
            protected void notifyTetheredOfNewUpstreamIface(String ifaceName) {
                if (DEBUG) Log.d(TAG, "notifying tethered with iface =" + ifaceName);
                mUpstreamIfaceName = ifaceName;
                for (Object o : mNotifyList) {
                    TetherInterfaceSM sm = (TetherInterfaceSM)o;
                    sm.sendMessage(TetherInterfaceSM.CMD_TETHER_CONNECTION_CHANGED,
                            ifaceName);
                }
            }
        }

        class InitialState extends TetherMasterUtilState {
            @Override
            public void enter() {
                mMobileReserved = false;
            }
            @Override
            public boolean processMessage(Message message) {
                if (DEBUG) Log.d(TAG, "MasterInitialState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        mDunRequired = isDunRequired();
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        if (DEBUG) Log.d(TAG, "Tether Mode requested by " + who.toString());
                        mNotifyList.add(who);
                        transitionTo(mTetherModeAliveState);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        if (DEBUG) Log.d(TAG, "Tether Mode unrequested by " + who.toString());
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(who);
                        }
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class TetherModeAliveState extends TetherMasterUtilState {
            boolean mTryCell = !WAIT_FOR_NETWORK_TO_SETTLE;
            @Override
            public void enter() {
                mTryCell = !WAIT_FOR_NETWORK_TO_SETTLE; // better try something first pass
                                                        // or crazy tests cases will fail
                chooseUpstreamType(mTryCell);
                mTryCell = !mTryCell;
                turnOnMasterTetherSettings(); // may transition us out
            }
            @Override
            public void exit() {
                turnOffMobileConnection();
                notifyTetheredOfNewUpstreamIface(null);
            }
            @Override
            public boolean processMessage(Message message) {
                if (DEBUG) Log.d(TAG, "TetherModeAliveState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        mNotifyList.add(who);
                        who.sendMessage(TetherInterfaceSM.CMD_TETHER_CONNECTION_CHANGED,
                                mUpstreamIfaceName);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(index);
                            if (mNotifyList.isEmpty()) {
                                turnOffMasterTetherSettings(); // transitions appropriately
                            }
                        }
                        break;
                    case CMD_UPSTREAM_CHANGED:
                        // need to try DUN immediately if Wifi goes down
                        mTryCell = !WAIT_FOR_NETWORK_TO_SETTLE;
                        chooseUpstreamType(mTryCell);
                        mTryCell = !mTryCell;
                        break;
                    case CMD_CELL_CONNECTION_RENEW:
                        // make sure we're still using a requested connection - may have found
                        // wifi or something since then.
                        if (mCurrentConnectionSequence == message.arg1) {
                            if (DEBUG) {
                                Log.d(TAG, "renewing mobile connection - requeuing for another " +
                                        CELL_CONNECTION_RENEW_MS + "ms");
                            }
                            mMobileReserved = false; // need to renew it
                            turnOnMobileConnection();
                        }
                        break;
                    case CMD_RETRY_UPSTREAM:
                        chooseUpstreamType(mTryCell);
                        mTryCell = !mTryCell;
                        break;
                    case CMD_IFACE_CHANGED:
                        String iface = (String)message.obj;
                        if (DEBUG) Log.d(TAG, "Activie upstream interface changed: " + iface);
                        notifyTetheredOfNewUpstreamIface(iface);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class ErrorState extends State {
            int mErrorNotification;
            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        who.sendMessage(mErrorNotification);
                        break;
                    default:
                       retValue = false;
                }
                return retValue;
            }
            void notify(int msgType) {
                mErrorNotification = msgType;
                for (Object o : mNotifyList) {
                    TetherInterfaceSM sm = (TetherInterfaceSM)o;
                    sm.sendMessage(msgType);
                }
            }

        }
        class SetIpForwardingEnabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingEnabled");
                notify(TetherInterfaceSM.CMD_IP_FORWARDING_ENABLE_ERROR);
            }
        }

        class SetIpForwardingDisabledErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setIpForwardingDisabled");
                notify(TetherInterfaceSM.CMD_IP_FORWARDING_DISABLE_ERROR);
            }
        }

        class StartTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in startTethering");
                notify(TetherInterfaceSM.CMD_START_TETHERING_ERROR);
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class StopTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in stopTethering");
                notify(TetherInterfaceSM.CMD_STOP_TETHERING_ERROR);
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class SetDnsForwardersErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setDnsForwarders");
                notify(TetherInterfaceSM.CMD_SET_DNS_FORWARDERS_ERROR);
                try {
                    mNMService.stopTethering();
                } catch (Exception e) {}
                try {
                    mNMService.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService.Tether " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
                    return;
        }

        pw.println();
        pw.println("Tether state:");
        synchronized (mIfaces) {
            for (Object o : mIfaces.values()) {
                pw.println(" "+o.toString());
            }
        }
        pw.println();
        return;
    }
}
