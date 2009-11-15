/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vpn;

import android.util.Log;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A helper class for managing native VPN daemons.
 */
class VpnDaemons implements Serializable {
    static final long serialVersionUID = 1L;
    private final String TAG = VpnDaemons.class.getSimpleName();

    private static final String MTPD = "mtpd";
    private static final String IPSEC = "racoon";

    private static final String L2TP = "l2tp";
    private static final String L2TP_PORT = "1701";

    private static final String PPTP = "pptp";
    private static final String PPTP_PORT = "1723";

    private static final String VPN_LINKNAME = "vpn";
    private static final String PPP_ARGS_SEPARATOR = "";

    private List<DaemonProxy> mDaemonList = new ArrayList<DaemonProxy>();

    public DaemonProxy startL2tp(String serverIp, String secret,
            String username, String password) throws IOException {
        return startMtpd(L2TP, serverIp, L2TP_PORT, secret, username, password,
                false);
    }

    public DaemonProxy startPptp(String serverIp, String username,
            String password, boolean encryption) throws IOException {
        return startMtpd(PPTP, serverIp, PPTP_PORT, null, username, password,
                encryption);
    }

    public DaemonProxy startIpsecForL2tp(String serverIp, String pskKey)
            throws IOException {
        DaemonProxy ipsec = startDaemon(IPSEC);
        ipsec.sendCommand(serverIp, L2TP_PORT, pskKey);
        return ipsec;
    }

    public DaemonProxy startIpsecForL2tp(String serverIp, String userKeyKey,
            String userCertKey, String caCertKey) throws IOException {
        DaemonProxy ipsec = startDaemon(IPSEC);
        ipsec.sendCommand(serverIp, L2TP_PORT, userKeyKey, userCertKey,
                caCertKey);
        return ipsec;
    }

    public synchronized void stopAll() {
        new DaemonProxy(MTPD).stop();
        new DaemonProxy(IPSEC).stop();
    }

    public synchronized void closeSockets() {
        for (DaemonProxy s : mDaemonList) s.closeControlSocket();
    }

    public synchronized boolean anyDaemonStopped() {
        for (DaemonProxy s : mDaemonList) {
            if (s.isStopped()) {
                Log.w(TAG, "    VPN daemon gone: " + s.getName());
                return true;
            }
        }
        return false;
    }

    public synchronized int getSocketError() {
        for (DaemonProxy s : mDaemonList) {
            int errCode = getResultFromSocket(s);
            if (errCode != 0) return errCode;
        }
        return 0;
    }

    private synchronized DaemonProxy startDaemon(String daemonName)
            throws IOException {
        DaemonProxy daemon = new DaemonProxy(daemonName);
        mDaemonList.add(daemon);
        daemon.start();
        return daemon;
    }

    private int getResultFromSocket(DaemonProxy s) {
        try {
            return s.getResultFromSocket();
        } catch (IOException e) {
            return -1;
        }
    }

    private DaemonProxy startMtpd(String protocol,
            String serverIp, String port, String secret, String username,
            String password, boolean encryption) throws IOException {
        ArrayList<String> args = new ArrayList<String>();
        args.addAll(Arrays.asList(protocol, serverIp, port));
        if (secret != null) args.add(secret);
        args.add(PPP_ARGS_SEPARATOR);
        addPppArguments(args, serverIp, username, password, encryption);

        DaemonProxy mtpd = startDaemon(MTPD);
        mtpd.sendCommand(args.toArray(new String[args.size()]));
        return mtpd;
    }

    private static void addPppArguments(ArrayList<String> args, String serverIp,
            String username, String password, boolean encryption)
            throws IOException {
        args.addAll(Arrays.asList(
                "linkname", VPN_LINKNAME,
                "name", username,
                "password", password,
                "refuse-eap", "nodefaultroute", "usepeerdns",
                "idle", "1800",
                "mtu", "1400",
                "mru", "1400"));
        if (encryption) {
            args.add("+mppe");
        }
    }
}
