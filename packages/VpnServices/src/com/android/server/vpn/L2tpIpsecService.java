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

import android.net.vpn.L2tpIpsecProfile;
import android.security.CertTool;

import java.io.IOException;

/**
 * The service that manages the certificate based L2TP-over-IPSec VPN connection.
 */
class L2tpIpsecService extends VpnService<L2tpIpsecProfile> {
    private static final String IPSEC_DAEMON = "racoon";

    @Override
    protected void connect(String serverIp, String username, String password)
            throws IOException {
        String hostIp = getHostIp();

        // IPSEC
        AndroidServiceProxy ipsecService = startService(IPSEC_DAEMON);
        ipsecService.sendCommand(hostIp, serverIp, L2tpService.L2TP_PORT,
                getUserkeyPath(), getUserCertPath(), getCaCertPath());

        sleep(2000); // 2 seconds

        // L2TP
        L2tpIpsecProfile p = getProfile();
        MtpdHelper.sendCommand(this, L2tpService.L2TP_DAEMON, serverIp,
                L2tpService.L2TP_PORT,
                (p.isSecretEnabled() ? p.getSecretString() : null),
                username, password);
    }

    private String getCaCertPath() {
        return CertTool.getInstance().getCaCertificate(
                getProfile().getCaCertificate());
    }

    private String getUserCertPath() {
        return CertTool.getInstance().getUserCertificate(
                getProfile().getUserCertificate());
    }

    private String getUserkeyPath() {
        return CertTool.getInstance().getUserPrivateKey(
                getProfile().getUserCertificate());
    }
}
