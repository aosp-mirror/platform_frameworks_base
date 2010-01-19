/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import java.util.ArrayList;

import android.provider.Settings;
import android.content.ContentResolver;
import android.database.ContentObserver;

import java.io.File;
import java.io.FileReader;
import java.lang.IllegalStateException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @hide
 */
class NetworkManagementService extends INetworkManagementService.Stub {

    private static final String TAG = "NetworkManagmentService";

    class NetdResponseCode {
        public static final int InterfaceListResult       = 110;
        public static final int TetherInterfaceListResult = 111;
        public static final int TetherDnsFwdTgtListResult = 112;

        public static final int TetherStatusResult        = 210;
        public static final int IpFwdStatusResult         = 211;
    }

    /**
     * Binder context for this service
     */
    private Context mContext;

    /**
     * connector object for communicating with netd
     */
    private NativeDaemonConnector mConnector;

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    private NetworkManagementService(Context context) {
        mContext = context;

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), "netd", 10, "NetdConnector");
        Thread thread = new Thread(mConnector, NativeDaemonConnector.class.getName());
        thread.start();
    }

    //
    // Netd Callback handling
    //

    class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        public void onDaemonConnected() {
            new Thread() {
                public void run() {
                    // XXX: Run some tests
                }
            }.start();
        }
        public boolean onEvent(int code, String raw, String[] cooked) {
           return false;
        }
    }

    //
    // INetworkManagementService members
    //

    public String[] listInterfaces() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("list_interfaces");

        String[] rdata = new String[rsp.size()];
        int idx = 0;

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.InterfaceListResult) {
                if (tok.length !=2) {
                    throw new IllegalStateException(
                            String.format("Malformatted list entry '%s'", line));
                }
                rdata[idx++] = tok[1];
            } else if (code == NativeDaemonConnector.ResponseCode.CommandOkay) {
                return rdata;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void shutdown() {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires SHUTDOWN permission");
        }

        Log.d(TAG, "Shutting down");
    }

    public boolean getIpForwardingEnabled() throws IllegalStateException{
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("ipfwd status");

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.IpFwdStatusResult) {
                // 211 Forwarding <enabled/disabled>
                if (tok.length !=2) {
                    throw new IllegalStateException(
                            String.format("Malformatted list entry '%s'", line));
                }
                if (tok[2].equals("enabled"))
                    return true;
                return false;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void setIpForwardingEnabled(boolean enable) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(String.format("ipfwd %sable", (enable ? "en" : "dis")));
    }

    public void startTethering(String dhcpRangeStart, String dhcpRangeEnd)
             throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(String.format("tether start %s %s", dhcpRangeStart, dhcpRangeEnd));
    }

    public void stopTethering() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand("tether stop");
    }

    public boolean isTetheringStarted() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("tether status");

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.TetherStatusResult) {
                // XXX: Tethering services <started/stopped> <TBD>...
                if (tok[2].equals("started"))
                    return true;
                return false;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void tetherInterface(String iface) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand("tether interface add " + iface);
    }

    public void untetherInterface(String iface) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand("tether interface remove " + iface);
    }

    public String[] listTetheredInterfaces() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("tether interface list");

        String[] rdata = new String[rsp.size()];
        int idx = 0;

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.TetherInterfaceListResult) {
                if (tok.length !=2) {
                    throw new IllegalStateException(
                            String.format("Malformatted list entry '%s'", line));
                }
                rdata[idx++] = tok[1];
            } else if (code == NativeDaemonConnector.ResponseCode.CommandOkay) {
                return rdata;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void setDnsForwarders(String[] dns) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            String cmd = "tether dns set ";
            for (String s : dns) {
                cmd += InetAddress.getByName(s).toString() + " ";
            }
            mConnector.doCommand(cmd);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Error resolving dns name", e);
        }
    }

    public String[] getDnsForwarders() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("tether dns list");

        String[] rdata = new String[rsp.size()];
        int idx = 0;

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.TetherDnsFwdTgtListResult) {
                if (tok.length !=2) {
                    throw new IllegalStateException(
                            String.format("Malformatted list entry '%s'", line));
                }
                rdata[idx++] = tok[1];
            } else if (code == NativeDaemonConnector.ResponseCode.CommandOkay) {
                return rdata;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void enableNat(String internalInterface, String externalInterface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(
                String.format("nat enable %s %s", internalInterface, externalInterface));
    }

    public void disableNat(String internalInterface, String externalInterface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(
                String.format("nat disable %s %s", internalInterface, externalInterface));
    }
}

