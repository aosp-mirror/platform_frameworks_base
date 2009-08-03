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

import android.net.vpn.L2tpIpsecPskProfile;

import java.io.IOException;

/**
 * The service that manages the preshared key based L2TP-over-IPSec VPN
 * connection.
 */
class L2tpIpsecPskService extends VpnService<L2tpIpsecPskProfile> {
    private static final String IPSEC = "racoon";

    @Override
    protected void connect(String serverIp, String username, String password)
            throws IOException {
        L2tpIpsecPskProfile p = getProfile();

        // IPSEC
        DaemonProxy ipsec = startDaemon(IPSEC);
        ipsec.sendCommand(serverIp, L2tpService.L2TP_PORT, p.getPresharedKey());
        ipsec.closeControlSocket();

        sleep(2000); // 2 seconds

        // L2TP
        MtpdHelper.sendCommand(this, L2tpService.L2TP_DAEMON, serverIp,
                L2tpService.L2TP_PORT,
                (p.isSecretEnabled() ? p.getSecretString() : null),
                username, password);
    }

    @Override
    protected void stopPreviouslyRunDaemons() {
        stopDaemon(IPSEC);
        stopDaemon(MtpdHelper.MTPD);
    }
}
