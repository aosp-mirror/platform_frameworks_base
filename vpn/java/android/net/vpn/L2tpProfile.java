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
 * The profile for L2TP type of VPN.
 * {@hide}
 */
public class L2tpProfile extends VpnProfile {
    private static final long serialVersionUID = 1L;

    private boolean mSecret;
    private String mSecretString;

    @Override
    public VpnType getType() {
        return VpnType.L2TP;
    }

    /**
     * Enables/disables the secret for authenticating tunnel connection.
     */
    public void setSecretEnabled(boolean enabled) {
        mSecret = enabled;
    }

    public boolean isSecretEnabled() {
        return mSecret;
    }

    public void setSecretString(String secret) {
        mSecretString = secret;
    }

    public String getSecretString() {
        return mSecretString;
    }

    @Override
    protected void readFromParcel(Parcel in) {
        super.readFromParcel(in);
        mSecret = in.readInt() > 0;
        mSecretString = in.readString();
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeInt(mSecret ? 1 : 0);
        parcel.writeString(mSecretString);
    }
}
