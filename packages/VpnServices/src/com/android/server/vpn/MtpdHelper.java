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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A helper class for sending commands to the MTP daemon (mtpd).
 */
class MtpdHelper {
    static final String MTPD = "mtpd";
    private static final String VPN_LINKNAME = "vpn";
    private static final String PPP_ARGS_SEPARATOR = "";

    static void sendCommand(VpnService<?> vpnService, String protocol,
            String serverIp, String port, String secret, String username,
            String password) throws IOException {
        sendCommand(vpnService, protocol, serverIp, port, secret, username,
                password, false);
    }

    static void sendCommand(VpnService<?> vpnService, String protocol,
            String serverIp, String port, String secret, String username,
            String password, boolean encryption) throws IOException {
        ArrayList<String> args = new ArrayList<String>();
        args.addAll(Arrays.asList(protocol, serverIp, port));
        if (secret != null) args.add(secret);
        args.add(PPP_ARGS_SEPARATOR);
        addPppArguments(args, serverIp, username, password, encryption);

        DaemonProxy mtpd = vpnService.startDaemon(MTPD);
        mtpd.sendCommand(args.toArray(new String[args.size()]));
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

    private MtpdHelper() {
    }
}
