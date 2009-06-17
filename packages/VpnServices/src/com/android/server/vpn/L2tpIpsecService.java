/*
 * Copyright (C) 2007, The Android Open Source Project
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

import android.net.vpn.L2tpIpsecProfile;
import android.security.Keystore;

import java.io.IOException;

/**
 * The service that manages the L2TP-over-IPSec VPN connection.
 */
class L2tpIpsecService extends VpnService<L2tpIpsecProfile> {
    private static final String IPSEC_SERVICE = "racoon";
    private static final String L2TP_SERVICE = "mtpd";

    @Override
    protected void connect(String serverIp, String username, String password)
            throws IOException {
        String hostIp = getHostIp();

        // IPSEC
        AndroidServiceProxy ipsecService = startService(IPSEC_SERVICE);
        ipsecService.sendCommand(
                String.format("SETKEY %s %s", hostIp, serverIp));
        ipsecService.sendCommand(String.format("SET_CERTS %s %s %s %s",
                serverIp, getCaCertPath(), getUserCertPath(),
                getUserkeyPath()));

        // L2TP
        AndroidServiceProxy l2tpService = startService(L2TP_SERVICE);
        l2tpService.sendCommand2("l2tp", serverIp, "1701", "",
                    "file", getPppOptionFilePath(),
                    "name", username,
                    "password", password);
    }

    private String getCaCertPath() {
        return Keystore.getInstance().getCaCertificate(
                getProfile().getCaCertificate());
    }

    private String getUserCertPath() {
        return Keystore.getInstance().getUserCertificate(
                getProfile().getUserCertificate());
    }

    private String getUserkeyPath() {
        return Keystore.getInstance().getUserPrivateKey(
                getProfile().getUserCertificate());
    }
}
