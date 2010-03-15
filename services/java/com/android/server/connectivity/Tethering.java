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
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.util.HierarchicalState;
import com.android.internal.util.HierarchicalStateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final String TAG = "Tethering";

    private boolean mBooted = false;
    //used to remember if we got connected before boot finished
    private boolean mDeferedUsbConnection = false;

    // TODO - remove both of these - should be part of interface inspection/selection stuff
    private String[] mTetherableUsbRegexs;
    private String[] mTetherableWifiRegexs;
    private String[] mUpstreamIfaceRegexs;

    private Looper mLooper; // given to us at construction time..

    private HashMap<String, TetherInterfaceSM> mIfaces; // all tethered/tetherable ifaces

    private BroadcastReceiver mStateReceiver;

    private static final String USB_NEAR_IFACE_ADDR      = "169.254.2.1";

    private String[] mDhcpRange;
    private static final String DHCP_DEFAULT_RANGE_START = "169.254.2.10";
    private static final String DHCP_DEFAULT_RANGE_STOP  = "169.254.2.64";

    private String[] mDnsServers;
    private static final String DNS_DEFAULT_SERVER1 = "8.8.8.8";
    private static final String DNS_DEFAULT_SERVER2 = "4.2.2.2";

    private boolean mDunRequired;  // configuration info - must use DUN apn on 3g
    private boolean mUseHiPri;

    private String mUpstreamIfaceName;

    private HierarchicalStateMachine mTetherMasterSM;

    private Notification mTetheredNotification;

    // whether we can tether is the && of these two - they come in as separate
    // broadcasts so track them so we can decide what to do when either changes
    private boolean mUsbMassStorageOff;  // track the status of USB Mass Storage
    private boolean mUsbConnected;       // track the status of USB connection

    public Tethering(Context context, Looper looper) {
        Log.d(TAG, "Tethering starting");
        mContext = context;
        mLooper = looper;

        // register for notifications from NetworkManagement Service
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        try {
            service.registerObserver(this);
        } catch (RemoteException e) {
            Log.e(TAG, "Error registering observer :" + e);
        }

        mIfaces = new HashMap<String, TetherInterfaceSM>();

        mTetherMasterSM = new TetherMasterSM("TetherMaster", mLooper);
        mTetherMasterSM.start();

        mStateReceiver = new StateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
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
        if (mDhcpRange.length == 0) {
            mDhcpRange = new String[2];
            mDhcpRange[0] = DHCP_DEFAULT_RANGE_START;
            mDhcpRange[1] = DHCP_DEFAULT_RANGE_STOP;
        } else if(mDhcpRange.length == 1) {
            String[] tmp = new String[2];
            tmp[0] = mDhcpRange[0];
            tmp[1] = new String("");
            mDhcpRange = tmp;
        }
        mDunRequired = context.getResources().getBoolean(
                com.android.internal.R.bool.config_tether_dun_required);

        mTetherableUsbRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_usb_regexs);
        mTetherableWifiRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_wifi_regexs);
        mUpstreamIfaceRegexs = context.getResources().getStringArray(
                com.android.internal.R.array.config_tether_upstream_regexs);

        // TODO - remove and rely on real notifications of the current iface
        mDnsServers = new String[2];
        mDnsServers[0] = DNS_DEFAULT_SERVER1;
        mDnsServers[1] = DNS_DEFAULT_SERVER2;
    }

    public void interfaceLinkStatusChanged(String iface, boolean link) {
        Log.d(TAG, "interfaceLinkStatusChanged " + iface + ", " + link);
        boolean found = false;
        boolean usb = false;
        if (isWifi(iface)) {
            found = true;
        } else if (isUsb(iface)) {
            found = true;
            usb = true;
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
                    sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_INTERFACE_DOWN));
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

    public void interfaceAdded(String iface) {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        boolean found = false;
        boolean usb = false;
        if (isWifi(iface)) {
            found = true;
        }
        if (isUsb(iface)) {
            found = true;
            usb = true;
        }
        if (found == false) {
            Log.d(TAG, iface + " is not a tetherable iface, ignoring");
            return;
        }

        synchronized (mIfaces) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (sm != null) {
                Log.e(TAG, "active iface (" + iface + ") reported as added, ignoring");
                return;
            }
            sm = new TetherInterfaceSM(iface, mLooper, usb);
            mIfaces.put(iface, sm);
            sm.start();
        }
        Log.d(TAG, "interfaceAdded :" + iface);
    }

    public void interfaceRemoved(String iface) {
        synchronized (mIfaces) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (sm == null) {
                Log.e(TAG, "attempting to remove unknown iface (" + iface + "), ignoring");
                return;
            }
            sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_INTERFACE_DOWN));
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
        sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_TETHER_REQUESTED));
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
        sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_TETHER_UNREQUESTED));
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
        IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);
        try {
            if (!service.isTetheringSupported()) return;
        } catch (RemoteException e) {
            return;
        }

        ArrayList<String> availableList = new ArrayList<String>();
        ArrayList<String> activeList = new ArrayList<String>();
        ArrayList<String> erroredList = new ArrayList<String>();

        boolean wifiTethered = false;
        boolean usbTethered = false;

        synchronized (mIfaces) {
            Set ifaces = mIfaces.keySet();
            for (Object iface : ifaces) {
                TetherInterfaceSM sm = mIfaces.get(iface);
                if (sm != null) {
                    if(sm.isErrored()) {
                        erroredList.add((String)iface);
                    } else if (sm.isAvailable()) {
                        availableList.add((String)iface);
                    } else if (sm.isTethered()) {
                        if (isUsb((String)iface)) {
                            usbTethered = true;
                        } else if (isWifi((String)iface)) {
                            wifiTethered = true;
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
        Log.d(TAG, "sendTetherStateChangedBroadcast " + availableList.size() + ", " +
                activeList.size() + ", " + erroredList.size());

        if (usbTethered) {
            if (wifiTethered) {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_general);
            } else {
                showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_usb);
            }
        } else if (wifiTethered) {
            showTetheredNotification(com.android.internal.R.drawable.stat_sys_tether_wifi);
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

        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        Resources r = Resources.getSystem();
        CharSequence title = r.getText(com.android.internal.R.string.tethered_notification_title);
        CharSequence message = r.getText(com.android.internal.R.string.
                tethered_notification_message);

        if(mTetheredNotification == null) {
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
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mUsbConnected = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                        == BatteryManager.BATTERY_PLUGGED_USB);
                Tethering.this.updateUsbStatus();
            } else if (action.equals(Intent.ACTION_MEDIA_SHARED)) {
                mUsbMassStorageOff = false;
                updateUsbStatus();
            }
            else if (action.equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mUsbMassStorageOff = true;
                updateUsbStatus();
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);
                try {
                    int netType = (mUseHiPri ? ConnectivityManager.TYPE_MOBILE_HIPRI:
                                               ConnectivityManager.TYPE_MOBILE_DUN);
                    NetworkInfo info = service.getNetworkInfo(netType);
                    int msg;
                    if (info != null && info.isConnected() == true) {
                        msg = TetherMasterSM.CMD_CELL_DUN_ENABLED;
                    } else {
                        msg = TetherMasterSM.CMD_CELL_DUN_DISABLED;
                    }
                    mTetherMasterSM.sendMessage(mTetherMasterSM.obtainMessage(msg));
                } catch (RemoteException e) {}
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBooted = true;
                updateUsbStatus();
            }
        }
    }

    // used on cable insert/remove
    private void enableUsbIfaces(boolean enable) {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        String[] ifaces = new String[0];
        try {
            ifaces = service.listInterfaces();
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

    // toggled when we enter/leave the fully teathered state
    private boolean enableUsbRndis(boolean enabled) {
        Log.d(TAG, "enableUsbRndis(" + enabled + ")");
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        try {
            if (enabled) {
                synchronized (this) {
                    if (!service.isUsbRNDISStarted()) {
                        service.startUsbRNDIS();
                    }
                }
            } else {
                if (service.isUsbRNDISStarted()) {
                    service.stopUsbRNDIS();
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
        Log.d(TAG, "configureUsbIface(" + enabled + ")");

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        // bring toggle the interfaces
        String[] ifaces = new String[0];
        try {
            ifaces = service.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces :" + e);
            return false;
        }
        for (String iface : ifaces) {
            if (isUsb(iface)) {
                InterfaceConfiguration ifcg = null;
                try {
                    ifcg = service.getInterfaceConfig(iface);
                    if (ifcg != null) {
                        ifcg.ipAddr = (169 << 24) + (254 << 16) + (2 << 8) + 1;
                        ifcg.netmask = (255 << 24) + (255 << 16) + (255 << 8) + 0;
                        if (enabled) {
                            ifcg.interfaceFlags = ifcg.interfaceFlags.replace("down", "up");
                        } else {
                            ifcg.interfaceFlags = ifcg.interfaceFlags.replace("up", "down");
                        }
                        ifcg.interfaceFlags = ifcg.interfaceFlags.replace("running", "");
                        ifcg.interfaceFlags = ifcg.interfaceFlags.replace("  "," ");
                        service.setInterfaceConfig(iface, ifcg);
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

    public String[] getUpstreamIfaceRegexs() {
        return mUpstreamIfaceRegexs;
    }

    public boolean isDunRequired() {
        return mDunRequired;
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


    class TetherInterfaceSM extends HierarchicalStateMachine {
        // notification from the master SM that it's in tether mode
        static final int CMD_TETHER_MODE_ALIVE           =  1;
        // notification from the master SM that it's not in tether mode
        static final int CMD_TETHER_MODE_DEAD            =  2;
        // request from the user that it wants to tether
        static final int CMD_TETHER_REQUESTED            =  3;
        // request from the user that it wants to untether
        static final int CMD_TETHER_UNREQUESTED          =  4;
        // notification that this interface is down
        static final int CMD_INTERFACE_DOWN              =  5;
        // notification that this interface is up
        static final int CMD_INTERFACE_UP                =  6;
        // notification from the master SM that it had an error turning on cellular dun
        static final int CMD_CELL_DUN_ERROR              = 10;
        // notification from the master SM that it had trouble enabling IP Forwarding
        static final int CMD_IP_FORWARDING_ENABLE_ERROR  = 11;
        // notification from the master SM that it had trouble disabling IP Forwarding
        static final int CMD_IP_FORWARDING_DISABLE_ERROR = 12;
        // notification from the master SM that it had trouble staring tethering
        static final int CMD_START_TETHERING_ERROR       = 13;
        // notification from the master SM that it had trouble stopping tethering
        static final int CMD_STOP_TETHERING_ERROR        = 14;
        // notification from the master SM that it had trouble setting the DNS forwarders
        static final int CMD_SET_DNS_FORWARDERS_ERROR    = 15;
        // a mechanism to transition self to another state from an enter function
        static final int CMD_TRANSITION_TO_STATE         = 16;

        private HierarchicalState mDefaultState;

        private HierarchicalState mInitialState;
        private HierarchicalState mStartingState;
        private HierarchicalState mTetheredState;

        private HierarchicalState mUnavailableState;

        private boolean mAvailable;
        private boolean mTethered;
        int mLastError;

        String mIfaceName;
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
            HierarchicalState current = getCurrentState();
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

        class InitialState extends HierarchicalState {
            @Override
            public void enter() {
                setAvailable(true);
                setTethered(false);
                sendTetherStateChangedBroadcast();
            }

            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "InitialState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_REQUESTED:
                        setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
                        Message m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_REQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);
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

        class StartingState extends HierarchicalState {
            @Override
            public void enter() {
                setAvailable(false);
                if (mUsb) {
                    if (!Tethering.this.configureUsbIface(true)) {
                        Message m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);

                        setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);

                        m = obtainMessage(CMD_TRANSITION_TO_STATE);
                        m.obj = mInitialState;
                        sendMessageAtFrontOfQueue(m);
                        return;
                    }
                }
                sendTetherStateChangedBroadcast();
            }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "StartingState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    // maybe a parent class?
                    case CMD_TETHER_UNREQUESTED:
                        Message m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                                break;
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    case CMD_TETHER_MODE_ALIVE:
                        transitionTo(mTetheredState);
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
                        m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);
                        transitionTo(mUnavailableState);
                        break;
                   case CMD_TRANSITION_TO_STATE:
                       HierarchicalState s = (HierarchicalState)(message.obj);
                       transitionTo(s);
                       break;
                    default:
                        retValue = false;
                }
                return retValue;
            }
        }

        class TetheredState extends HierarchicalState {
            @Override
            public void enter() {
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service =
                        INetworkManagementService.Stub.asInterface(b);
                try {
                    service.tetherInterface(mIfaceName);
                } catch (Exception e) {
                    setLastError(ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR);

                    Message m = obtainMessage(CMD_TRANSITION_TO_STATE);
                    m.obj = mInitialState;
                    sendMessageAtFrontOfQueue(m);
                    return;
                }
                try {
                    service.enableNat(mIfaceName, mUpstreamIfaceName);
                } catch (Exception e) {
                    try {
                        service.untetherInterface(mIfaceName);
                    } catch (Exception ee) {}

                    setLastError(ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR);

                    Message m = obtainMessage(CMD_TRANSITION_TO_STATE);
                    m.obj = mInitialState;
                    sendMessageAtFrontOfQueue(m);
                    return;
                }
                if (mUsb) Tethering.this.enableUsbRndis(true);
                Log.d(TAG, "Tethered " + mIfaceName);
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
                Log.d(TAG, "TetheredState.processMessage what=" + message.what);
                boolean retValue = true;
                boolean error = false;
                switch (message.what) {
                    case CMD_TETHER_UNREQUESTED:
                    case CMD_INTERFACE_DOWN:
                        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                        INetworkManagementService service =
                                INetworkManagementService.Stub.asInterface(b);
                        try {
                            service.disableNat(mIfaceName, mUpstreamIfaceName);
                        } catch (Exception e) {
                            try {
                                service.untetherInterface(mIfaceName);
                            } catch (Exception ee) {}

                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_DISABLE_NAT_ERROR);
                            break;
                        }
                        try {
                            service.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                            break;
                        }
                        Message m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);
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
                        Log.d(TAG, "Untethered " + mIfaceName);
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
                        b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                        service = INetworkManagementService.Stub.asInterface(b);
                        try {
                            service.disableNat(mIfaceName, mUpstreamIfaceName);
                        } catch (Exception e) {
                            try {
                                service.untetherInterface(mIfaceName);
                            } catch (Exception ee) {}

                            setLastErrorAndTransitionToInitialState(
                                    ConnectivityManager.TETHER_ERROR_DISABLE_NAT_ERROR);
                            break;
                        }
                        try {
                            service.untetherInterface(mIfaceName);
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
                        Log.d(TAG, "Tether lost upstream connection " + mIfaceName);
                        sendTetherStateChangedBroadcast();
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    case CMD_TRANSITION_TO_STATE:
                        HierarchicalState s = (HierarchicalState)(message.obj);
                        transitionTo(s);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class UnavailableState extends HierarchicalState {
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

    class TetherMasterSM extends HierarchicalStateMachine {
        // an interface SM has requested Tethering
        static final int CMD_TETHER_MODE_REQUESTED   = 1;
        // an interface SM has unrequested Tethering
        static final int CMD_TETHER_MODE_UNREQUESTED = 2;
        // we received notice that the cellular DUN connection is up
        static final int CMD_CELL_DUN_ENABLED        = 3;
        // we received notice that the cellular DUN connection is down
        static final int CMD_CELL_DUN_DISABLED       = 4;
        // we timed out on a cellular DUN toggle
        static final int CMD_CELL_DUN_TIMEOUT        = 5;
        // it's time to renew our cellular DUN reservation
        static final int CMD_CELL_DUN_RENEW          = 6;

        // This indicates what a timeout event relates to.  A state that
        // sends itself a delayed timeout event and handles incoming timeout events
        // should inc this when it is entered and whenever it sends a new timeout event.
        // We do not flush the old ones.
        private int mSequenceNumber;

        private HierarchicalState mInitialState;
        private HierarchicalState mCellDunRequestedState;
        private HierarchicalState mCellDunAliveState;
        private HierarchicalState mTetherModeAliveState;

        private HierarchicalState mCellDunErrorState;
        private HierarchicalState mSetIpForwardingEnabledErrorState;
        private HierarchicalState mSetIpForwardingDisabledErrorState;
        private HierarchicalState mStartTetheringErrorState;
        private HierarchicalState mStopTetheringErrorState;
        private HierarchicalState mSetDnsForwardersErrorState;

        private ArrayList mNotifyList;


        private static final int CELL_DUN_TIMEOUT_MS         = 45000;
        private static final int CELL_DISABLE_DUN_TIMEOUT_MS = 3000;
        private static final int CELL_DUN_RENEW_MS           = 40000;

        TetherMasterSM(String name, Looper looper) {
            super(name, looper);

            //Add states
            mInitialState = new InitialState();
            addState(mInitialState);
            mCellDunRequestedState = new CellDunRequestedState();
            addState(mCellDunRequestedState);
            mCellDunAliveState = new CellDunAliveState();
            addState(mCellDunAliveState);
            mTetherModeAliveState = new TetherModeAliveState();
            addState(mTetherModeAliveState);

            mCellDunErrorState = new CellDunErrorState();
            addState(mCellDunErrorState);
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

        class TetherMasterUtilState extends HierarchicalState {
            @Override
            public boolean processMessage(Message m) {
                return false;
            }
            public int turnOnMobileDun() {
                IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);
                int retValue = Phone.APN_REQUEST_FAILED;
                try {
                    retValue = service.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            (mUseHiPri ? Phone.FEATURE_ENABLE_HIPRI : Phone.FEATURE_ENABLE_DUN),
                            new Binder());
                } catch (Exception e) {
                }
                return retValue;
            }
            public boolean turnOffMobileDun() {
                IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
                IConnectivityManager service =
                        IConnectivityManager.Stub.asInterface(b);
                try {
                    service.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            (mUseHiPri ? Phone.FEATURE_ENABLE_HIPRI : Phone.FEATURE_ENABLE_DUN));
                } catch (Exception e) {
                    return false;
                }
                return true;
            }
            public boolean turnOnMasterTetherSettings() {
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service =
                        INetworkManagementService.Stub.asInterface(b);
                try {
                    service.setIpForwardingEnabled(true);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingEnabledErrorState);
                    return false;
                }
                try {
                    service.startTethering(mDhcpRange[0], mDhcpRange[1]);
                } catch (Exception e) {
                    transitionTo(mStartTetheringErrorState);
                    return false;
                }
                try {
                    service.setDnsForwarders(mDnsServers);
                } catch (Exception e) {
                    transitionTo(mSetDnsForwardersErrorState);
                    return false;
                }
                transitionTo(mTetherModeAliveState);
                return true;
            }
            public boolean turnOffMasterTetherSettings() {
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service =
                        INetworkManagementService.Stub.asInterface(b);
                try {
                    service.stopTethering();
                } catch (Exception e) {
                    transitionTo(mStopTetheringErrorState);
                    return false;
                }
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception e) {
                    transitionTo(mSetIpForwardingDisabledErrorState);
                    return false;
                }
                transitionTo(mInitialState);
                return true;
            }
            public String findActiveUpstreamIface() {
                // check for what iface we can use - if none found switch to error.
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

                String[] ifaces = new String[0];
                try {
                    ifaces = service.listInterfaces();
                } catch (Exception e) {
                    Log.e(TAG, "Error listing Interfaces :" + e);
                    return null;
                }
                for (String iface : ifaces) {
                    for (String regex : mUpstreamIfaceRegexs) {
                        if (iface.matches(regex)) {
                            // verify it is up!
                            InterfaceConfiguration ifcg = null;
                            try {
                                ifcg = service.getInterfaceConfig(iface);
                            } catch (Exception e) {
                                Log.e(TAG, "Error getting iface config :" + e);
                                // ignore - try next
                                continue;
                            }
                            if (ifcg.interfaceFlags.contains("up")) {
                                return iface;
                            }
                        }
                    }
                }
                return null;
            }
        }

        class InitialState extends TetherMasterUtilState {
            @Override
            public void enter() {
                mUseHiPri = false;
            }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "MasterInitialState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        Log.d(TAG, "Tether Mode requested by " + who.toString());
                        mNotifyList.add(who);
                        transitionTo(mCellDunRequestedState);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        Log.d(TAG, "Tether Mode unrequested by " + who.toString());
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(who);
                        }
                        break;
                    case CMD_CELL_DUN_ENABLED:
                        transitionTo(mCellDunAliveState);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }
        class CellDunRequestedState extends TetherMasterUtilState {
            @Override
            public void enter() {
                mUseHiPri = (findActiveUpstreamIface() == null && !mDunRequired);
                if (mDunRequired || mUseHiPri) {
                    ++mSequenceNumber;
                    int result = turnOnMobileDun();
                    switch (result) {
                        case Phone.APN_ALREADY_ACTIVE:
                            Log.d(TAG, "Dun already active");
                            sendMessage(obtainMessage(CMD_CELL_DUN_ENABLED));
                            break;
                        case Phone.APN_REQUEST_FAILED:
                        case Phone.APN_TYPE_NOT_AVAILABLE:
                            Log.d(TAG, "Error bringing up Dun connection");
                            Message m = obtainMessage(CMD_CELL_DUN_TIMEOUT);
                            m.arg1 = mSequenceNumber;
                            sendMessage(m);
                            break;
                        case Phone.APN_REQUEST_STARTED:
                            Log.d(TAG, "Started bringing up Dun connection");
                            m = obtainMessage(CMD_CELL_DUN_TIMEOUT);
                            m.arg1 = mSequenceNumber;
                            sendMessageDelayed(m, CELL_DUN_TIMEOUT_MS);
                            break;
                        default:
                            Log.e(TAG, "Unknown return value from startUsingNetworkFeature " +
                                    result);
                    }
                } else {
                    Log.d(TAG, "no Dun Required.  Skipping to Active");
                    sendMessage(obtainMessage(CMD_CELL_DUN_ENABLED));
                }
            }

            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "CellDunRequestedState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        mNotifyList.add(who);
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(index);
                            if (mNotifyList.isEmpty()) {
                                if (mDunRequired || mUseHiPri) {
                                    turnOffMobileDun();
                                }
                                transitionTo(mInitialState);
                            }
                        }
                        break;
                    case CMD_CELL_DUN_ENABLED:
                        turnOnMasterTetherSettings();
                        break;
                    case CMD_CELL_DUN_DISABLED:
                        break;
                    case CMD_CELL_DUN_TIMEOUT:
                        if (message.arg1 == mSequenceNumber) {
                            transitionTo(mCellDunErrorState);
                        }
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class CellDunAliveState extends TetherMasterUtilState {
            @Override
            public void enter() {
                Log.d(TAG, "renewing Dun in " + CELL_DUN_RENEW_MS + "ms");
                sendMessageDelayed(obtainMessage(CMD_CELL_DUN_RENEW), CELL_DUN_RENEW_MS);
            }

            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "CellDunAliveState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        mNotifyList.add(who);
                        turnOnMasterTetherSettings();
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(index);
                            if (mNotifyList.isEmpty()) {
                                if (mDunRequired || mUseHiPri) {
                                    turnOffMobileDun();
                                }
                                transitionTo(mInitialState);
                            }
                        }
                        break;
                    case CMD_CELL_DUN_DISABLED:
                        transitionTo(mInitialState);
                        break;
                    case CMD_CELL_DUN_RENEW:
                        Log.d(TAG, "renewing dun connection - requeuing for another " +
                                CELL_DUN_RENEW_MS + "ms");
                        turnOnMobileDun();
                        sendMessageDelayed(obtainMessage(CMD_CELL_DUN_RENEW), CELL_DUN_RENEW_MS);
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class TetherModeAliveState extends TetherMasterUtilState {
            @Override
            public void enter() {
                if (mDunRequired || mUseHiPri) {
                    Log.d(TAG, "renewing Dun in " + CELL_DUN_RENEW_MS + "ms");
                    sendMessageDelayed(obtainMessage(CMD_CELL_DUN_RENEW), CELL_DUN_RENEW_MS);
                }
                mUpstreamIfaceName = findActiveUpstreamIface();
                if (mUpstreamIfaceName == null) {
                    Log.d(TAG, "Erroring our of tether - no upstream ifaces available");
                    sendMessage(obtainMessage(CMD_CELL_DUN_DISABLED));
                } else {
                    for (Object o : mNotifyList) {
                        TetherInterfaceSM sm = (TetherInterfaceSM)o;
                        sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_TETHER_MODE_ALIVE));
                    }
                }
            }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "TetherModeAliveState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        mNotifyList.add(who);
                        who.sendMessage(who.obtainMessage(TetherInterfaceSM.CMD_TETHER_MODE_ALIVE));
                        break;
                    case CMD_TETHER_MODE_UNREQUESTED:
                        who = (TetherInterfaceSM)message.obj;
                        int index = mNotifyList.indexOf(who);
                        if (index != -1) {
                            mNotifyList.remove(index);
                            if (mNotifyList.isEmpty()) {
                                if (mDunRequired || mUseHiPri) {
                                    turnOffMobileDun();
                                }
                                turnOffMasterTetherSettings(); // transitions appropriately
                                mUpstreamIfaceName = null;
                            }
                        }
                        break;
                    case CMD_CELL_DUN_DISABLED:
                        int size = mNotifyList.size();
                        for (int i = 0; i < size; i++) {
                            TetherInterfaceSM sm = (TetherInterfaceSM)mNotifyList.get(i);
                            mNotifyList.remove(i);
                            sm.sendMessage(sm.obtainMessage(
                                    TetherInterfaceSM.CMD_TETHER_MODE_DEAD));
                        }
                        turnOffMasterTetherSettings(); // transitions appropriately
                        mUpstreamIfaceName = null;
                        break;
                    case CMD_CELL_DUN_RENEW:
                        Log.d(TAG, "renewing dun connection - requeuing for another " +
                                CELL_DUN_RENEW_MS + "ms");
                        turnOnMobileDun();
                        sendMessageDelayed(obtainMessage(CMD_CELL_DUN_RENEW), CELL_DUN_RENEW_MS);
                        break;
                    default:
                       retValue = false;
                       break;
                }
                return retValue;
            }
        }

        class ErrorState extends HierarchicalState {
            int mErrorNotification;
            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_MODE_REQUESTED:
                        TetherInterfaceSM who = (TetherInterfaceSM)message.obj;
                        who.sendMessage(who.obtainMessage(mErrorNotification));
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
                    sm.sendMessage(sm.obtainMessage(msgType));
                }
            }

        }
        class CellDunErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error trying to enable Cell DUN");
                notify(TetherInterfaceSM.CMD_CELL_DUN_ERROR);
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
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service =
                        INetworkManagementService.Stub.asInterface(b);
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class StopTetheringErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in stopTethering");
                notify(TetherInterfaceSM.CMD_STOP_TETHERING_ERROR);
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service =
                         INetworkManagementService.Stub.asInterface(b);
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception e) {}
            }
        }

        class SetDnsForwardersErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in setDnsForwarders");
                notify(TetherInterfaceSM.CMD_SET_DNS_FORWARDERS_ERROR);
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service =
                        INetworkManagementService.Stub.asInterface(b);
                try {
                    service.stopTethering();
                } catch (Exception e) {}
                try {
                    service.setIpForwardingEnabled(false);
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
