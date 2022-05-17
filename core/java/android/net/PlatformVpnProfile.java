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
    protected final boolean mExcludeLocalRoutes;
    /** @hide */
    protected final boolean mRequiresInternetValidation;

    /** @hide */
    PlatformVpnProfile(@PlatformVpnType int type, boolean excludeLocalRoutes,
            boolean requiresValidation) {
        mType = type;
        mExcludeLocalRoutes = excludeLocalRoutes;
        mRequiresInternetValidation = requiresValidation;
    }

    /** Returns the profile integer type. */
    @PlatformVpnType
    public final int getType() {
        return mType;
    }

    /**
     * Returns whether the local traffic is exempted from the VPN.
     */
    public final boolean areLocalRoutesExcluded() {
        return mExcludeLocalRoutes;
    }

    /**
     * Returns whether this VPN should undergo Internet validation.
     *
     * If this is true, the platform will perform basic validation checks for Internet
     * connectivity over this VPN. If and when they succeed, the VPN network capabilities will
     * reflect this by gaining the {@link NetworkCapabilities#NET_CAPABILITY_VALIDATED}
     * capability.
     *
     * If this is false, the platform assumes the VPN either is always capable of reaching the
     * Internet or intends not to. In this case, the VPN network capabilities will
     * always gain the {@link NetworkCapabilities#NET_CAPABILITY_VALIDATED} capability
     * immediately after it connects, whether it can reach public Internet destinations or not.
     */
    public final boolean isInternetValidationRequired() {
        return mRequiresInternetValidation;
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
