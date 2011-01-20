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

import android.net.vpn.L2tpProfile;

import java.io.IOException;

/**
 * The service that manages the L2TP VPN connection.
 */
class L2tpService extends VpnService<L2tpProfile> {
    @Override
    protected void connect(String serverIp, String username, String password)
            throws IOException {
        L2tpProfile p = getProfile();
        getDaemons().startL2tp(serverIp,
                (p.isSecretEnabled() ? p.getSecretString() : null),
                username, password);
    }
}
