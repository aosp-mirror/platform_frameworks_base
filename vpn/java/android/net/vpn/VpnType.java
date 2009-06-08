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

package android.net.vpn;

/**
 * Enumeration of all supported VPN types.
 * {@hide}
 */
public enum VpnType {
    L2TP_IPSEC("L2TP/IPSec", L2tpIpsecProfile.class),
    L2TP("L2TP", L2tpProfile.class);

    private String mDisplayName;
    private Class<? extends VpnProfile> mClass;

    VpnType(String displayName, Class<? extends VpnProfile> klass) {
        mDisplayName = displayName;
        mClass = klass;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public Class<? extends VpnProfile> getProfileClass() {
        return mClass;
    }
}
