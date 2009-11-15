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

import com.android.internal.R;

/**
 * Enumeration of all supported VPN types.
 * {@hide}
 */
public enum VpnType {
    PPTP("PPTP", R.string.pptp_vpn_description, PptpProfile.class),
    L2TP("L2TP", R.string.l2tp_vpn_description, L2tpProfile.class),
    L2TP_IPSEC_PSK("L2TP/IPSec PSK", R.string.l2tp_ipsec_psk_vpn_description,
            L2tpIpsecPskProfile.class),
    L2TP_IPSEC("L2TP/IPSec CRT", R.string.l2tp_ipsec_crt_vpn_description,
            L2tpIpsecProfile.class);

    private String mDisplayName;
    private int mDescriptionId;
    private Class<? extends VpnProfile> mClass;

    VpnType(String displayName, int descriptionId,
            Class<? extends VpnProfile> klass) {
        mDisplayName = displayName;
        mDescriptionId = descriptionId;
        mClass = klass;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public int getDescriptionId() {
        return mDescriptionId;
    }

    public Class<? extends VpnProfile> getProfileClass() {
        return mClass;
    }
}
