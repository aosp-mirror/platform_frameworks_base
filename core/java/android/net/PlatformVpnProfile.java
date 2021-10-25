/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import static android.net.PlatformVpnProfile.TYPE_IKEV2_IPSEC_PSK;
import static android.net.PlatformVpnProfile.TYPE_IKEV2_IPSEC_RSA;
import static android.net.PlatformVpnProfile.TYPE_IKEV2_IPSEC_USER_PASS;

import android.annotation.IntDef;
import android.annotation.NonNull;

import com.android.internal.net.VpnProfile;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.GeneralSecurityException;

/**
 * PlatformVpnProfile represents a configuration for a platform-based VPN implementation.
 *
 * <p>Platform-based VPNs allow VPN applications to provide configuration and authentication options
 * to leverage the Android OS' implementations of well-defined control plane (authentication, key
 * negotiation) and data plane (per-packet encryption) protocols to simplify the creation of VPN
 * tunnels. In contrast, {@link VpnService} based VPNs must implement both the control and data
 * planes on a per-app basis.
 *
 * @see Ikev2VpnProfile
 */
public abstract class PlatformVpnProfile {
    /**
     * Alias to platform VPN related types from VpnProfile, for API use.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        TYPE_IKEV2_IPSEC_USER_PASS,
        TYPE_IKEV2_IPSEC_PSK,
        TYPE_IKEV2_IPSEC_RSA,
    })
    public static @interface PlatformVpnType {}

    public static final int TYPE_IKEV2_IPSEC_USER_PASS = VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS;
    public static final int TYPE_IKEV2_IPSEC_PSK = VpnProfile.TYPE_IKEV2_IPSEC_PSK;
    public static final int TYPE_IKEV2_IPSEC_RSA = VpnProfile.TYPE_IKEV2_IPSEC_RSA;

    /** @hide */
    public static final int MAX_MTU_DEFAULT = 1360;

    /** @hide */
    @PlatformVpnType protected final int mType;

    /** @hide */
    PlatformVpnProfile(@PlatformVpnType int type) {
        mType = type;
    }
    /** Returns the profile integer type. */
    @PlatformVpnType
    public final int getType() {
        return mType;
    }

    /** Returns a type string describing the VPN profile type */
    @NonNull
    public final String getTypeString() {
        switch (mType) {
            case TYPE_IKEV2_IPSEC_USER_PASS:
                return "IKEv2/IPsec Username/Password";
            case TYPE_IKEV2_IPSEC_PSK:
                return "IKEv2/IPsec Preshared key";
            case TYPE_IKEV2_IPSEC_RSA:
                return "IKEv2/IPsec RSA Digital Signature";
            default:
                return "Unknown VPN profile type";
        }
    }

    /** @hide */
    @NonNull
    public abstract VpnProfile toVpnProfile() throws IOException, GeneralSecurityException;

    /** @hide */
    @NonNull
    public static PlatformVpnProfile fromVpnProfile(@NonNull VpnProfile profile)
            throws IOException, GeneralSecurityException {
        switch (profile.type) {
            case TYPE_IKEV2_IPSEC_USER_PASS: // fallthrough
            case TYPE_IKEV2_IPSEC_PSK: // fallthrough
            case TYPE_IKEV2_IPSEC_RSA:
                return Ikev2VpnProfile.fromVpnProfile(profile);
            default:
                throw new IllegalArgumentException("Unknown VPN Profile type");
        }
    }
}
