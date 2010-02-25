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
import android.os.IBinder;
import android.os.INetworkManagementService;
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
 * TODO - review error states - they currently are dead-ends with no recovery possible
 *
 * TODO - look for parent classes and code sharing
 */

public class Tethering extends INetworkManagementEventObserver.Stub {

    private Notification mTetheringNotification;
    private Context mContext;
    private final String TAG = "Tethering";

    private boolean mPlaySounds = false;
    private boolean mBooted = false;
    //used to remember if we got connected before boot finished
    private boolean mDeferedUsbConnection = false;

    // TODO - remove both of these - should be part of interface inspection/selection stuff
    private String[] mTetherableUsbRegexs;
    private String[] mTetherableWifiRegexs;
    private String[] mUpstreamIfaceRegexs;

    private HashMap<String, TetherInterfaceSM> mIfaces;

    private ArrayList<String> mActiveTtys;

    private BroadcastReceiver mStateReceiver;

    private static final String USB_NEAR_IFACE_ADDR      = "169.254.2.1";

    private String[] mDhcpRange;
    private static final String DHCP_DEFAULT_RANGE_START = "169.254.2.10";
    private static final String DHCP_DEFAULT_RANGE_STOP  = "169.254.2.64";

    private String[] mDnsServers;
    private static final String DNS_DEFAULT_SERVER1 = "8.8.8.8";
    private static final String DNS_DEFAULT_SERVER2 = "4.2.2.2";

    private boolean mDunRequired;
    private boolean mUseHiPri;
    private String mUpstreamIfaceName;

    HierarchicalStateMachine mTetherMasterSM;

    public Tethering(Context context) {
        Log.d(TAG, "Tethering starting");
        mContext = context;

        // register for notifications from NetworkManagement Service
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        try {
            service.registerObserver(this);
        } catch (RemoteException e) {
            Log.e(TAG, "Error registering observer :" + e);
        }

        mIfaces = new HashMap<String, TetherInterfaceSM>();
        mActiveTtys = new ArrayList<String>();

        mTetherMasterSM = new TetherMasterSM("TetherMaster");
        mTetherMasterSM.start();

        // TODO - remove this hack after real USB connections are detected.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        mStateReceiver = new StateReceiver();
        mContext.registerReceiver(mStateReceiver, filter);

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
        for (String regex : mTetherableWifiRegexs) {
            if (iface.matches(regex)) {
                found = true;
                break;
            }
        }
        for (String regex: mTetherableUsbRegexs) {
            if (iface.matches(regex)) {
                found = true;
                usb = true;
                break;
            }
        }
        if (found == false) return;

        synchronized (mIfaces) {
            TetherInterfaceSM sm = mIfaces.get(iface);
            if (link) {
                if (sm == null) {
                    sm = new TetherInterfaceSM(iface, usb);
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

    public void interfaceAdded(String iface) {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        boolean found = false;
        boolean usb = false;
        for (String regex : mTetherableWifiRegexs) {
            if (iface.matches(regex)) {
                found = true;
                break;
            }
        }
        for (String regex : mTetherableUsbRegexs) {
            if (iface.matches(regex)) {
                found = true;
                usb = true;
                break;
            }
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
            sm = new TetherInterfaceSM(iface, usb);
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

    public boolean tether(String iface) {
        Log.d(TAG, "Tethering " + iface);
        TetherInterfaceSM sm = null;
        synchronized (mIfaces) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to Tether an unknown iface :" + iface + ", ignoring");
            return false;
        }
        if (sm.isErrored()) {
            Log.e(TAG, "Tried to Tether to an errored iface :" + iface + ", ignoring");
            return false;
        }
        if (!sm.isAvailable()) {
            Log.e(TAG, "Tried to Tether an unavailable iface :" + iface + ", ignoring");
            return false;
        }
        sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_TETHER_REQUESTED));
        return true;
    }

    public boolean untether(String iface) {
        Log.d(TAG, "Untethering " + iface);
        TetherInterfaceSM sm = null;
        synchronized (mIfaces) {
            sm = mIfaces.get(iface);
        }
        if (sm == null) {
            Log.e(TAG, "Tried to Untether an unknown iface :" + iface + ", ignoring");
            return false;
        }
        if (sm.isErrored()) {
            Log.e(TAG, "Tried to Untethered an errored iface :" + iface + ", ignoring");
            return false;
        }
        sm.sendMessage(sm.obtainMessage(TetherInterfaceSM.CMD_TETHER_UNREQUESTED));
        return true;
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
        // check if we need to send a USB notification
        // Check if the user wants to be bothered
        boolean tellUser = (Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.TETHER_NOTIFY, 0) == 1);
        for (Object o : activeList) {
            String s = (String)o;
            for (Object regexObject : mTetherableUsbRegexs) {
                if (s.matches((String)regexObject)) {
                    showTetheredNotification();
                    return;
                }
            }
        }
        if (tellUser) {
            for (Object o : availableList) {
                String s = (String)o;
                for (String match : mTetherableUsbRegexs) {
                    if (s.matches(match)) {
                        showTetherAvailableNotification();
                        return;
                    }
                }
            }
        }
        clearNotification();
    }

    private void showTetherAvailableNotification() {
        NotificationManager notificationManager = (NotificationManager)mContext.
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        Intent intent = new Intent();
        intent.setClass(mContext, com.android.internal.app.TetherActivity.class);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        Resources r = Resources.getSystem();
        CharSequence title = r.getText(com.android.internal.R.string.
                tether_available_notification_title);
        CharSequence message = r.getText(com.android.internal.R.string.
                tether_available_notification_message);

        if(mTetheringNotification == null) {
            mTetheringNotification = new Notification();
            mTetheringNotification.when = 0;
        }
        mTetheringNotification.icon = com.android.internal.R.drawable.stat_sys_tether_usb;

        boolean playSounds = false;
        //playSounds = SystemProperties.get("persist.service.mount.playsnd", "1").equals("1");
        if (playSounds) {
            mTetheringNotification.defaults |= Notification.DEFAULT_SOUND;
        } else {
            mTetheringNotification.defaults &= ~Notification.DEFAULT_SOUND;
        }

        mTetheringNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mTetheringNotification.tickerText = title;
        mTetheringNotification.setLatestEventInfo(mContext, title, message, pi);

        notificationManager.notify(mTetheringNotification.icon, mTetheringNotification);

    }

    private void showTetheredNotification() {
        NotificationManager notificationManager = (NotificationManager)mContext.
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        Intent intent = new Intent();
        intent.setClass(mContext, com.android.internal.app.TetherActivity.class);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        Resources r = Resources.getSystem();
        CharSequence title = r.getText(com.android.internal.R.string.
                tether_stop_notification_title);
        CharSequence message = r.getText(com.android.internal.R.string.
                tether_stop_notification_message);

        if(mTetheringNotification == null) {
            mTetheringNotification = new Notification();
            mTetheringNotification.when = 0;
        }
        mTetheringNotification.icon = com.android.internal.R.drawable.stat_sys_tether_usb;

        boolean playSounds = false;
        //playSounds = SystemProperties.get("persist.service.mount.playsnd", "1").equals("1");
        if (playSounds) {
            mTetheringNotification.defaults |= Notification.DEFAULT_SOUND;
        } else {
            mTetheringNotification.defaults &= ~Notification.DEFAULT_SOUND;
        }

        mTetheringNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mTetheringNotification.tickerText = title;
        mTetheringNotification.setLatestEventInfo(mContext, title, message, pi);

        notificationManager.notify(mTetheringNotification.icon, mTetheringNotification);
    }

    private void clearNotification() {
        NotificationManager notificationManager = (NotificationManager)mContext.
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && mTetheringNotification != null) {
            notificationManager.cancel(mTetheringNotification.icon);
            mTetheringNotification = null;
        }
    }

    private class StateReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                boolean usbConnected = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                        == BatteryManager.BATTERY_PLUGGED_USB);
                if (mBooted) {
                    Tethering.this.enableUsbIfaces(usbConnected); // add or remove them
                } else {
                    mDeferedUsbConnection = usbConnected;
                }
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
                if (mDeferedUsbConnection) {
                    Tethering.this.enableUsbIfaces(true);
                }
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
            for (String regex : mTetherableUsbRegexs) {
                if (iface.matches(regex)) {
                    if (enable) {
                        interfaceAdded(iface);
                    } else {
                        interfaceRemoved(iface);
                    }
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
            for (String regex : mTetherableUsbRegexs) {
                if (iface.matches(regex)) {
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
                                // TODO - clean this up - maybe a better regex?
                                ifcg.interfaceFlags = ifcg.interfaceFlags.replace("running", "");
                                ifcg.interfaceFlags = ifcg.interfaceFlags.replace("  "," ");
                            }
                            service.setInterfaceConfig(iface, ifcg);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error configuring interface " + iface + ", :" + e);
                        return false;
                    }
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
        // a mechanism to transition self to error state from an enter function
        static final int CMD_TRANSITION_TO_ERROR         = 16;

        private HierarchicalState mDefaultState;

        private HierarchicalState mInitialState;
        private HierarchicalState mStartingState;
        private HierarchicalState mTetheredState;

        private HierarchicalState mMasterTetherErrorState;
        private HierarchicalState mTetherInterfaceErrorState;
        private HierarchicalState mUntetherInterfaceErrorState;
        private HierarchicalState mEnableNatErrorState;
        private HierarchicalState mDisableNatErrorState;
        private HierarchicalState mUsbConfigurationErrorState;

        private HierarchicalState mUnavailableState;

        private boolean mAvailable;
        private boolean mErrored;
        private boolean mTethered;

        String mIfaceName;
        boolean mUsb;

        TetherInterfaceSM(String name, boolean usb) {
            super(name);
            mIfaceName = name;
            mUsb = usb;

            mInitialState = new InitialState();
            addState(mInitialState);
            mStartingState = new StartingState();
            addState(mStartingState);
            mTetheredState = new TetheredState();
            addState(mTetheredState);
            mMasterTetherErrorState = new MasterTetherErrorState();
            addState(mMasterTetherErrorState);
            mTetherInterfaceErrorState = new TetherInterfaceErrorState();
            addState(mTetherInterfaceErrorState);
            mUntetherInterfaceErrorState = new UntetherInterfaceErrorState();
            addState(mUntetherInterfaceErrorState);
            mEnableNatErrorState = new EnableNatErrorState();
            addState(mEnableNatErrorState);
            mDisableNatErrorState = new DisableNatErrorState();
            addState(mDisableNatErrorState);
            mUsbConfigurationErrorState = new UsbConfigurationErrorState();
            addState(mUsbConfigurationErrorState);
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
            if (current == mMasterTetherErrorState) res += "MasterTetherErrorState";
            if (current == mTetherInterfaceErrorState) res += "TetherInterfaceErrorState";
            if (current == mUntetherInterfaceErrorState) res += "UntetherInterfaceErrorState";
            if (current == mEnableNatErrorState) res += "EnableNatErrorState";
            if (current == mDisableNatErrorState) res += "DisableNatErrorState";
            if (current == mUsbConfigurationErrorState) res += "UsbConfigurationErrorState";
            if (current == mUnavailableState) res += "UnavailableState";
            if (mAvailable) res += " - Available";
            if (mTethered) res += " - Tethered";
            if (mErrored) res += " - ERRORED";
            return res;
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
            return mErrored;
        }

        private void setErrored(boolean errored) {
            synchronized (this) {
                mErrored = errored;
            }
            if (errored && mUsb) {
                // note everything's been unwound by this point so nothing to do on
                // further error..
                Tethering.this.configureUsbIface(false);
            }
        }

        class InitialState extends HierarchicalState {
            @Override
            public void enter() {
                setAvailable(true);
                setTethered(false);
                setErrored(false);
                sendTetherStateChangedBroadcast();
            }

            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "InitialState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_REQUESTED:
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

                        m = obtainMessage(CMD_TRANSITION_TO_ERROR);
                        m.obj = mUsbConfigurationErrorState;
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
                                transitionTo(mUsbConfigurationErrorState);
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
                        transitionTo(mMasterTetherErrorState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);
                        transitionTo(mUnavailableState);
                        break;
                   case CMD_TRANSITION_TO_ERROR:
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
                    Message m = obtainMessage(CMD_TRANSITION_TO_ERROR);
                    m.obj = mTetherInterfaceErrorState;
                    sendMessageAtFrontOfQueue(m);
                    return;
                }
                try {
                    service.enableNat(mIfaceName, mUpstreamIfaceName);
                } catch (Exception e) {
                    Message m = obtainMessage(CMD_TRANSITION_TO_ERROR);
                    m.obj = mEnableNatErrorState;
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
                            transitionTo(mDisableNatErrorState);
                            break;
                        }
                        try {
                            service.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            transitionTo(mUntetherInterfaceErrorState);
                            break;
                        }
                        Message m = mTetherMasterSM.obtainMessage(
                                TetherMasterSM.CMD_TETHER_MODE_UNREQUESTED);
                        m.obj = TetherInterfaceSM.this;
                        mTetherMasterSM.sendMessage(m);
                        if (message.what == CMD_TETHER_UNREQUESTED) {
                            if (mUsb) {
                                if (!Tethering.this.configureUsbIface(false)) {
                                    transitionTo(mUsbConfigurationErrorState);
                                } else {
                                    transitionTo(mInitialState);
                                }
                            } else {
                                transitionTo(mInitialState);
                            }
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
                            transitionTo(mDisableNatErrorState);
                            break;
                        }
                        try {
                            service.untetherInterface(mIfaceName);
                        } catch (Exception e) {
                            transitionTo(mUntetherInterfaceErrorState);
                            break;
                        }
                        if (error) {
                            transitionTo(mMasterTetherErrorState);
                            break;
                        }
                        Log.d(TAG, "Tether lost upstream connection " + mIfaceName);
                        sendTetherStateChangedBroadcast();
                        if (mUsb) {
                            if (!Tethering.this.configureUsbIface(false)) {
                                transitionTo(mUsbConfigurationErrorState);
                                break;
                            }
                        }
                        transitionTo(mInitialState);
                        break;
                    case CMD_TRANSITION_TO_ERROR:
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
                setErrored(false);
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


        class ErrorState extends HierarchicalState {
            int mErrorNotification;
            @Override
            public boolean processMessage(Message message) {
                boolean retValue = true;
                switch (message.what) {
                    case CMD_TETHER_REQUESTED:
                        sendTetherStateChangedBroadcast();
                        break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
            }
        }

        class MasterTetherErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error in Master Tether state " + mIfaceName);
                setAvailable(false);
                setErrored(true);
                sendTetherStateChangedBroadcast();
            }
        }

        class TetherInterfaceErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error trying to tether " + mIfaceName);
                setAvailable(false);
                setErrored(true);
                sendTetherStateChangedBroadcast();
            }
        }

        class UntetherInterfaceErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error trying to untether " + mIfaceName);
                setAvailable(false);
                setErrored(true);
                sendTetherStateChangedBroadcast();
            }
        }

        class EnableNatErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error trying to enable NAT " + mIfaceName);
                setAvailable(false);
                setErrored(true);

                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
                try {
                    service.untetherInterface(mIfaceName);
                } catch (Exception e) {}
                sendTetherStateChangedBroadcast();
            }
        }


        class DisableNatErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error trying to disable NAT " + mIfaceName);
                setAvailable(false);
                setErrored(true);

                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
                try {
                    service.untetherInterface(mIfaceName);
                } catch (Exception e) {}
                sendTetherStateChangedBroadcast();
            }
        }

        class UsbConfigurationErrorState extends ErrorState {
            @Override
            public void enter() {
                Log.e(TAG, "Error trying to configure USB " + mIfaceName);
                setAvailable(false);
                setErrored(true);
            }
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

        TetherMasterSM(String name) {
            super(name);

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
