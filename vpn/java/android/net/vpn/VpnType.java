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

package android.net.vpn;

/**
 * Enumeration of all supported VPN types.
 * {@hide}
 */
public enum VpnType {
    PPTP("PPTP", "", PptpProfile.class),
    L2TP("L2TP", "", L2tpProfile.class),
    L2TP_IPSEC_PSK("L2TP/IPSec PSK", "Pre-shared key based L2TP/IPSec VPN",
            L2tpIpsecPskProfile.class),
    L2TP_IPSEC("L2TP/IPSec CRT", "Certificate based L2TP/IPSec VPN",
            L2tpIpsecProfile.class);

    private String mDisplayName;
    private String mDescription;
    private Class<? extends VpnProfile> mClass;

    VpnType(String displayName, String description,
            Class<? extends VpnProfile> klass) {
        mDisplayName = displayName;
        mDescription = description;
        mClass = klass;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public String getDescription() {
        return mDescription;
    }

    public Class<? extends VpnProfile> getProfileClass() {
        return mClass;
    }
}
