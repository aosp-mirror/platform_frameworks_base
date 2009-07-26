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

import android.os.Parcel;

/**
 * The profile for certificate-based L2TP-over-IPSec type of VPN.
 * {@hide}
 */
public class L2tpIpsecProfile extends L2tpProfile {
    private static final long serialVersionUID = 1L;

    private String mUserCertificate;
    private String mCaCertificate;

    @Override
    public VpnType getType() {
        return VpnType.L2TP_IPSEC;
    }

    public void setCaCertificate(String name) {
        mCaCertificate = name;
    }

    public String getCaCertificate() {
        return mCaCertificate;
    }

    public void setUserCertificate(String name) {
        mUserCertificate = name;
    }

    public String getUserCertificate() {
        return mUserCertificate;
    }

    @Override
    protected void readFromParcel(Parcel in) {
        super.readFromParcel(in);
        mCaCertificate = in.readString();
        mUserCertificate = in.readString();
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeString(mCaCertificate);
        parcel.writeString(mUserCertificate);
    }
}
