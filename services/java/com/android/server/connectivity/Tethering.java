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
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
/**
 * @hide
 */
public class Tethering extends INetworkManagementEventObserver.Stub {

    private Notification mTetheringNotification;
    private Context mContext;
    private final String TAG = "Tethering";

    private boolean mPlaySounds = false;

    private ArrayList<String> mAvailableIfaces;
    private ArrayList<String> mActiveIfaces;

    private ArrayList<String> mActiveTtys;

    private BroadcastReceiver mStateReceiver;

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

        mAvailableIfaces = new ArrayList<String>();
        mActiveIfaces = new ArrayList<String>();
        mActiveTtys = new ArrayList<String>();

        // TODO - remove this hack after real USB connections are detected.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_UMS_DISCONNECTED);
        filter.addAction(Intent.ACTION_UMS_CONNECTED);
        mStateReceiver = new UMSStateReceiver();
        mContext.registerReceiver(mStateReceiver, filter);
    }

    public synchronized void interfaceLinkStatusChanged(String iface, boolean link) {
        Log.d(TAG, "interfaceLinkStatusChanged " + iface + ", " + link);
    }

    public synchronized void interfaceAdded(String iface) {
        if (mActiveIfaces.contains(iface)) {
            Log.e(TAG, "active iface (" + iface + ") reported as added, ignoring");
            return;
        }
        if (mAvailableIfaces.contains(iface)) {
            Log.e(TAG, "available iface (" + iface + ") readded, ignoring");
            return;
        }
        mAvailableIfaces.add(iface);
        Log.d(TAG, "interfaceAdded :" + iface);
        sendTetherStateChangedBroadcast();
    }

    public synchronized void interfaceRemoved(String iface) {
        if (mActiveIfaces.contains(iface)) {
            Log.d(TAG, "removed an active iface (" + iface + ")");
            untether(iface);
        }
        if (mAvailableIfaces.contains(iface)) {
            mAvailableIfaces.remove(iface);
            Log.d(TAG, "interfaceRemoved " + iface);
            sendTetherStateChangedBroadcast();
        }
    }

    public synchronized boolean tether(String iface) {
        Log.d(TAG, "Tethering " + iface);

        if (!mAvailableIfaces.contains(iface)) {
            Log.e(TAG, "Tried to Tether an unavailable iface :" + iface + ", ignoring");
            return false;
        }
        if (mActiveIfaces.contains(iface)) {
            Log.e(TAG, "Tried to Tether an already Tethered iface :" + iface + ", ignoring");
            return false;
        }

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        if (mActiveIfaces.size() == 0) {
            try {
                service.setIpForwardingEnabled(true);
            } catch (Exception e) {
                Log.e(TAG, "Error in setIpForwardingEnabled(true) :" + e);
                return false;
            }

            try {
                // TODO - don't hardcode this - though with non-conf values (un-routable)
                // maybe it's not a big deal
                service.startTethering("169.254.2.1", "169.254.2.64");
            } catch (Exception e) {
                Log.e(TAG, "Error in startTethering :" + e);
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception ee) {}
                return false;
            }

            try {
                // TODO - maybe use the current connection's dns servers for this
                String[] dns = new String[2];
                dns[0] = new String("8.8.8.8");
                dns[1] = new String("4.2.2.2");
                service.setDnsForwarders(dns);
            } catch (Exception e) {
                Log.e(TAG, "Error in setDnsForwarders :" + e);
                try {
                    service.stopTethering();
                } catch (Exception ee) {}
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception ee) {}
            }
        }

        try {
            service.tetherInterface(iface);
        } catch (Exception e) {
            Log.e(TAG, "Error in tetherInterface :" + e);
            if (mActiveIfaces.size() == 0) {
                try {
                    service.stopTethering();
                } catch (Exception ee) {}
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception ee) {}
            }
            return false;
        }

        try {
            // TODO - use the currently active external iface
            service.enableNat (iface, "rmnet0");
        } catch (Exception e) {
            Log.e(TAG, "Error in enableNat :" + e);
            try {
                service.untetherInterface(iface);
            } catch (Exception ee) {}
            if (mActiveIfaces.size() == 0) {
                try {
                    service.stopTethering();
                } catch (Exception ee) {}
                try {
                    service.setIpForwardingEnabled(false);
                } catch (Exception ee) {}
            }
            return false;
        }
        mAvailableIfaces.remove(iface);
        mActiveIfaces.add(iface);
        Log.d(TAG, "Tethered " + iface);
        sendTetherStateChangedBroadcast();
        return true;
    }

    public synchronized boolean untether(String iface) {
        Log.d(TAG, "Untethering " + iface);

        if (mAvailableIfaces.contains(iface)) {
            Log.e(TAG, "Tried to Untether an available iface :" + iface);
            return false;
        }
        if (!mActiveIfaces.contains(iface)) {
            Log.e(TAG, "Tried to Untether an inactive iface :" + iface);
            return false;
        }

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        // none of these errors are recoverable - ie, multiple calls won't help
        // and the user can't do anything.  Basically a reboot is required and probably
        // the device is misconfigured or something bad has happend.
        // Because of this, we should try to unroll as much state as we can.
        try {
            service.disableNat(iface, "rmnet0");
        } catch (Exception e) {
            Log.e(TAG, "Error in disableNat :" + e);
        }
        try {
            service.untetherInterface(iface);
        } catch (Exception e) {
            Log.e(TAG, "Error untethering " + iface + ", :" + e);
        }
        mActiveIfaces.remove(iface);
        mAvailableIfaces.add(iface);

        if (mActiveIfaces.size() == 0) {
            Log.d(TAG, "no active tethers - turning down dhcp/ipforward");
            try {
                service.stopTethering();
            } catch (Exception e) {
                Log.e(TAG, "Error in stopTethering :" + e);
            }
            try {
                service.setIpForwardingEnabled(false);
            } catch (Exception e) {
                Log.e(TAG, "Error in setIpForwardingEnabled(false) :" + e);
            }
        }
        sendTetherStateChangedBroadcast();
        Log.d(TAG, "Untethered " + iface);
        return true;
    }

    private void sendTetherStateChangedBroadcast() {
        Intent broadcast = new Intent(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        broadcast.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        broadcast.putExtra(ConnectivityManager.EXTRA_AVAILABLE_TETHER_COUNT,
                mAvailableIfaces.size());
        broadcast.putExtra(ConnectivityManager.EXTRA_ACTIVE_TETHER_COUNT, mActiveIfaces.size());
        mContext.sendBroadcast(broadcast);

        // for USB we only have the one, so don't have to deal with additional
        if (mAvailableIfaces.size() > 0) {
            // Check if the user wants to be bothered
            boolean tellUser = (Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.TETHER_NOTIFY, 0) == 1);

            if (tellUser) {
                showTetherAvailableNotification();
            }
        } else if (mActiveIfaces.size() > 0) {
            showTetheredNotification();
        } else {
            clearNotification();
        }
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




// TODO - remove this hack after we get proper USB detection
    private class UMSStateReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_UMS_CONNECTED)) {
                Tethering.this.handleTtyConnect();
            } else if (intent.getAction().equals(Intent.ACTION_UMS_DISCONNECTED)) {
                Tethering.this.handleTtyDisconnect();
            }
        }
    }

    private synchronized void handleTtyConnect() {
        Log.d(TAG, "handleTtyConnect");
        // for each of the available Tty not already supported by a ppp session,
        // create a ppp session
        // TODO - this should be data-driven rather than hard coded.
        String[] allowedTtys = new String[1];
        allowedTtys[0] = new String("ttyGS0");

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        String[] availableTtys;
        try {
            availableTtys = service.listTtys();
        } catch (RemoteException e) {
            Log.e(TAG, "error listing Ttys :" + e);
            return;
        }

        for (String tty : availableTtys) {
            for (String pattern : allowedTtys) {
                if (tty.matches(pattern)) {
                    synchronized (this) {
                        if (!mActiveTtys.contains(tty)) {
                            // TODO - don't hardcode this
                            try {
                                // local, remote, dns
                                service.attachPppd(tty, "169.254.1.128", "169.254.1.1",
                                        "169.254.1.128", "0.0.0.0");
                            } catch (Exception e) {
                                Log.e(TAG, "error calling attachPppd: " + e);
                                return;
                            }
                            Log.d(TAG, "started Pppd on tty " + tty);
                            mActiveTtys.add(tty);
                            // TODO - remove this after we detect the new iface
                            interfaceAdded("ppp0");
                        }
                    }
                }
            }
        }
    }

    private synchronized void handleTtyDisconnect() {
        Log.d(TAG, "handleTtyDisconnect");

        // TODO - this should be data-driven rather than hard coded.
        String[] allowedTtys = new String[1];
        allowedTtys[0] = new String("ttyGS0");

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);

        String[] availableTtys;
        try {
            availableTtys = service.listTtys();
        } catch (RemoteException e) {
            Log.e(TAG, "error listing Ttys :" + e);
            return;
        }

        for (String tty : availableTtys) {
            for (String pattern : allowedTtys) {
                if (tty.matches(pattern)) {
                    synchronized (this) {
                        if (mActiveTtys.contains(tty)) {
                            try {
                                service.detachPppd(tty);
                            } catch (Exception e) {
                                Log.e(TAG, "error calling detachPppd on " + tty + " :" + e);
                            }
                            mActiveTtys.remove(tty);
                            // TODO - remove this after we detect the new iface
                            interfaceRemoved("ppp0");
                            return;
                        }
                    }
                }
            }
        }
    }

    public synchronized String[] getTetheredIfaces() {
        int size = mActiveIfaces.size();
        String[] result = new String[size];
        size -= 1;
        for (int i=0; i< size; i++) {
            result[i] = mActiveIfaces.get(i);
        }
        return result;
    }

    public synchronized String[] getTetherableIfaces() {
        int size = mAvailableIfaces.size();
        String[] result = new String[size];
        size -= 1;
        for (int i=0; i< size; i++) {
            result[i] = mActiveIfaces.get(i);
        }
        return result;
    }
}
