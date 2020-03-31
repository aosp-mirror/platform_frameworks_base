/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.net;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.Ikev2VpnProfile;
import android.net.PlatformVpnProfile;
import android.net.ProxyInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Profile storage class for a platform VPN.
 *
 * <p>This class supports both the Legacy VPN, as well as application-configurable platform VPNs
 * (such as IKEv2/IPsec).
 *
 * <p>This class is serialized and deserialized via the {@link #encode()} and {@link #decode()}
 * functions for persistent storage in the Android Keystore. The encoding is entirely custom, but
 * must be kept for backward compatibility for devices upgrading between Android versions.
 *
 * @hide
 */
public final class VpnProfile implements Cloneable, Parcelable {
    private static final String TAG = "VpnProfile";

    @VisibleForTesting static final String VALUE_DELIMITER = "\0";
    @VisibleForTesting static final String LIST_DELIMITER = ",";

    // Match these constants with R.array.vpn_types.
    public static final int TYPE_PPTP = 0;
    public static final int TYPE_L2TP_IPSEC_PSK = 1;
    public static final int TYPE_L2TP_IPSEC_RSA = 2;
    public static final int TYPE_IPSEC_XAUTH_PSK = 3;
    public static final int TYPE_IPSEC_XAUTH_RSA = 4;
    public static final int TYPE_IPSEC_HYBRID_RSA = 5;
    public static final int TYPE_IKEV2_IPSEC_USER_PASS = 6;
    public static final int TYPE_IKEV2_IPSEC_PSK = 7;
    public static final int TYPE_IKEV2_IPSEC_RSA = 8;
    public static final int TYPE_MAX = 8;

    // Match these constants with R.array.vpn_proxy_settings.
    public static final int PROXY_NONE = 0;
    public static final int PROXY_MANUAL = 1;

    private static final String ENCODED_NULL_PROXY_INFO = "\0\0\0\0";

    // Entity fields.
    @UnsupportedAppUsage
    public final String key;                                   // -1

    @UnsupportedAppUsage
    public String name = "";                                   // 0

    @UnsupportedAppUsage
    public int type = TYPE_PPTP;                               // 1

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public String server = "";                                 // 2

    @UnsupportedAppUsage
    public String username = "";                               // 3
    public String password = "";                               // 4
    public String dnsServers = "";                             // 5
    public String searchDomains = "";                          // 6
    public String routes = "";                                 // 7
    public boolean mppe = true;                                // 8
    public String l2tpSecret = "";                             // 9
    public String ipsecIdentifier = "";                        // 10

    /**
     * The RSA private key or pre-shared key used for authentication.
     *
     * <p>If areAuthParamsInline is {@code true}, this String will be either:
     *
     * <ul>
     *   <li>If this is an IKEv2 RSA profile: a PKCS#8 encoded {@link java.security.PrivateKey}
     *   <li>If this is an IKEv2 PSK profile: a string value representing the PSK.
     * </ul>
     */
    public String ipsecSecret = "";                            // 11

    /**
     * The RSA certificate to be used for digital signature authentication.
     *
     * <p>If areAuthParamsInline is {@code true}, this String will be a pem-encoded {@link
     * java.security.X509Certificate}
     */
    public String ipsecUserCert = "";                          // 12

    /**
     * The RSA certificate that should be used to verify the server's end/target certificate.
     *
     * <p>If areAuthParamsInline is {@code true}, this String will be a pem-encoded {@link
     * java.security.X509Certificate}
     */
    public String ipsecCaCert = "";                            // 13
    public String ipsecServerCert = "";                        // 14
    public ProxyInfo proxy = null;                             // 15~18

    /**
     * The list of allowable algorithms.
     *
     * <p>This list is validated in the setter to ensure that encoding characters (list, value
     * delimiters) are not present in the algorithm names. See {@link #validateAllowedAlgorithms()}
     */
    private List<String> mAllowedAlgorithms = new ArrayList<>(); // 19
    public boolean isBypassable = false;                         // 20
    public boolean isMetered = false;                            // 21
    public int maxMtu = PlatformVpnProfile.MAX_MTU_DEFAULT;      // 22
    public boolean areAuthParamsInline = false;                  // 23

    // Helper fields.
    @UnsupportedAppUsage
    public transient boolean saveLogin = false;

    public VpnProfile(String key) {
        this.key = key;
    }

    @UnsupportedAppUsage
    public VpnProfile(Parcel in) {
        key = in.readString();
        name = in.readString();
        type = in.readInt();
        server = in.readString();
        username = in.readString();
        password = in.readString();
        dnsServers = in.readString();
        searchDomains = in.readString();
        routes = in.readString();
        mppe = in.readInt() != 0;
        l2tpSecret = in.readString();
        ipsecIdentifier = in.readString();
        ipsecSecret = in.readString();
        ipsecUserCert = in.readString();
        ipsecCaCert = in.readString();
        ipsecServerCert = in.readString();
        saveLogin = in.readInt() != 0;
        proxy = in.readParcelable(null);
        mAllowedAlgorithms = new ArrayList<>();
        in.readList(mAllowedAlgorithms, null);
        isBypassable = in.readBoolean();
        isMetered = in.readBoolean();
        maxMtu = in.readInt();
        areAuthParamsInline = in.readBoolean();
    }

    /**
     * Retrieves the list of allowed algorithms.
     *
     * <p>The contained elements are as listed in {@link IpSecAlgorithm}
     */
    public List<String> getAllowedAlgorithms() {
        return Collections.unmodifiableList(mAllowedAlgorithms);
    }

    /**
     * Validates and sets the list of algorithms that can be used for the IPsec transforms.
     *
     * @param allowedAlgorithms the list of allowable algorithms, as listed in {@link
     *     IpSecAlgorithm}.
     * @throws IllegalArgumentException if any delimiters are used in algorithm names. See {@link
     *     #VALUE_DELIMITER} and {@link LIST_DELIMITER}.
     */
    public void setAllowedAlgorithms(List<String> allowedAlgorithms) {
        validateAllowedAlgorithms(allowedAlgorithms);
        mAllowedAlgorithms = allowedAlgorithms;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(key);
        out.writeString(name);
        out.writeInt(type);
        out.writeString(server);
        out.writeString(username);
        out.writeString(password);
        out.writeString(dnsServers);
        out.writeString(searchDomains);
        out.writeString(routes);
        out.writeInt(mppe ? 1 : 0);
        out.writeString(l2tpSecret);
        out.writeString(ipsecIdentifier);
        out.writeString(ipsecSecret);
        out.writeString(ipsecUserCert);
        out.writeString(ipsecCaCert);
        out.writeString(ipsecServerCert);
        out.writeInt(saveLogin ? 1 : 0);
        out.writeParcelable(proxy, flags);
        out.writeList(mAllowedAlgorithms);
        out.writeBoolean(isBypassable);
        out.writeBoolean(isMetered);
        out.writeInt(maxMtu);
        out.writeBoolean(areAuthParamsInline);
    }

    /**
     * Decodes a VpnProfile instance from the encoded byte array.
     *
     * <p>See {@link #encode()}
     */
    @UnsupportedAppUsage
    public static VpnProfile decode(String key, byte[] value) {
        try {
            if (key == null) {
                return null;
            }

            String[] values = new String(value, StandardCharsets.UTF_8).split(VALUE_DELIMITER, -1);
            // Acceptable numbers of values are:
            // 14-19: Standard profile, with option for serverCert, proxy
            // 24: Standard profile with serverCert, proxy and platform-VPN parameters.
            if ((values.length < 14 || values.length > 19) && values.length != 24) {
                return null;
            }

            VpnProfile profile = new VpnProfile(key);
            profile.name = values[0];
            profile.type = Integer.parseInt(values[1]);
            if (profile.type < 0 || profile.type > TYPE_MAX) {
                return null;
            }
            profile.server = values[2];
            profile.username = values[3];
            profile.password = values[4];
            profile.dnsServers = values[5];
            profile.searchDomains = values[6];
            profile.routes = values[7];
            profile.mppe = Boolean.parseBoolean(values[8]);
            profile.l2tpSecret = values[9];
            profile.ipsecIdentifier = values[10];
            profile.ipsecSecret = values[11];
            profile.ipsecUserCert = values[12];
            profile.ipsecCaCert = values[13];
            profile.ipsecServerCert = (values.length > 14) ? values[14] : "";
            if (values.length > 15) {
                String host = (values.length > 15) ? values[15] : "";
                String port = (values.length > 16) ? values[16] : "";
                String exclList = (values.length > 17) ? values[17] : "";
                String pacFileUrl = (values.length > 18) ? values[18] : "";
                if (!host.isEmpty() || !port.isEmpty() || !exclList.isEmpty()) {
                    profile.proxy = new ProxyInfo(host, port.isEmpty() ?
                            0 : Integer.parseInt(port), exclList);
                } else if (!pacFileUrl.isEmpty()) {
                    profile.proxy = new ProxyInfo(pacFileUrl);
                }
            } // else profile.proxy = null

            // Either all must be present, or none must be.
            if (values.length >= 24) {
                profile.mAllowedAlgorithms = Arrays.asList(values[19].split(LIST_DELIMITER));
                profile.isBypassable = Boolean.parseBoolean(values[20]);
                profile.isMetered = Boolean.parseBoolean(values[21]);
                profile.maxMtu = Integer.parseInt(values[22]);
                profile.areAuthParamsInline = Boolean.parseBoolean(values[23]);
            }

            profile.saveLogin = !profile.username.isEmpty() || !profile.password.isEmpty();
            return profile;
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Encodes a VpnProfile instance to a byte array for storage.
     *
     * <p>See {@link #decode(String, byte[])}
     */
    public byte[] encode() {
        StringBuilder builder = new StringBuilder(name);
        builder.append(VALUE_DELIMITER).append(type);
        builder.append(VALUE_DELIMITER).append(server);
        builder.append(VALUE_DELIMITER).append(saveLogin ? username : "");
        builder.append(VALUE_DELIMITER).append(saveLogin ? password : "");
        builder.append(VALUE_DELIMITER).append(dnsServers);
        builder.append(VALUE_DELIMITER).append(searchDomains);
        builder.append(VALUE_DELIMITER).append(routes);
        builder.append(VALUE_DELIMITER).append(mppe);
        builder.append(VALUE_DELIMITER).append(l2tpSecret);
        builder.append(VALUE_DELIMITER).append(ipsecIdentifier);
        builder.append(VALUE_DELIMITER).append(ipsecSecret);
        builder.append(VALUE_DELIMITER).append(ipsecUserCert);
        builder.append(VALUE_DELIMITER).append(ipsecCaCert);
        builder.append(VALUE_DELIMITER).append(ipsecServerCert);
        if (proxy != null) {
            builder.append(VALUE_DELIMITER).append(proxy.getHost() != null ? proxy.getHost() : "");
            builder.append(VALUE_DELIMITER).append(proxy.getPort());
            builder.append(VALUE_DELIMITER)
                    .append(
                            proxy.getExclusionListAsString() != null
                                    ? proxy.getExclusionListAsString()
                                    : "");
            builder.append(VALUE_DELIMITER).append(proxy.getPacFileUrl().toString());
        } else {
            builder.append(ENCODED_NULL_PROXY_INFO);
        }

        builder.append(VALUE_DELIMITER).append(String.join(LIST_DELIMITER, mAllowedAlgorithms));
        builder.append(VALUE_DELIMITER).append(isBypassable);
        builder.append(VALUE_DELIMITER).append(isMetered);
        builder.append(VALUE_DELIMITER).append(maxMtu);
        builder.append(VALUE_DELIMITER).append(areAuthParamsInline);

        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Checks if this profile specifies a LegacyVpn type. */
    public static boolean isLegacyType(int type) {
        switch (type) {
            case VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS: // fall through
            case VpnProfile.TYPE_IKEV2_IPSEC_RSA: // fall through
            case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
                return false;
            default:
                return true;
        }
    }

    private boolean isValidLockdownLegacyVpnProfile() {
        return isLegacyType(type) && isServerAddressNumeric() && hasDns()
                && areDnsAddressesNumeric();
    }

    private boolean isValidLockdownPlatformVpnProfile() {
        return Ikev2VpnProfile.isValidVpnProfile(this);
    }

    /**
     * Tests if profile is valid for lockdown.
     *
     * <p>For LegacyVpn profiles, this requires an IPv4 address for both the server and DNS.
     *
     * <p>For PlatformVpn profiles, this requires a server, an identifier and the relevant fields to
     * be non-null.
     */
    public boolean isValidLockdownProfile() {
        return isTypeValidForLockdown()
                && (isValidLockdownLegacyVpnProfile() || isValidLockdownPlatformVpnProfile());
    }

    /** Returns {@code true} if the VPN type is valid for lockdown. */
    public boolean isTypeValidForLockdown() {
        // b/7064069: lockdown firewall blocks ports used for PPTP
        return type != TYPE_PPTP;
    }

    /** Returns {@code true} if the server address is numeric, e.g. 8.8.8.8 */
    public boolean isServerAddressNumeric() {
        try {
            InetAddress.parseNumericAddress(server);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    /** Returns {@code true} if one or more DNS servers are specified. */
    public boolean hasDns() {
        return !TextUtils.isEmpty(dnsServers);
    }

    /** Returns {@code true} if all DNS servers have numeric addresses, e.g. 8.8.8.8 */
    public boolean areDnsAddressesNumeric() {
        try {
            for (String dnsServer : dnsServers.split(" +")) {
                InetAddress.parseNumericAddress(dnsServer);
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    /**
     * Validates that the provided list of algorithms does not contain illegal characters.
     *
     * @param allowedAlgorithms The list to be validated
     */
    public static void validateAllowedAlgorithms(List<String> allowedAlgorithms) {
        for (final String alg : allowedAlgorithms) {
            if (alg.contains(VALUE_DELIMITER) || alg.contains(LIST_DELIMITER)) {
                throw new IllegalArgumentException(
                        "Algorithm contained illegal ('\0' or ',') character");
            }
        }
    }

    /** Generates a hashcode over the VpnProfile. */
    @Override
    public int hashCode() {
        return Objects.hash(
            key, type, server, username, password, dnsServers, searchDomains, routes, mppe,
            l2tpSecret, ipsecIdentifier, ipsecSecret, ipsecUserCert, ipsecCaCert, ipsecServerCert,
            proxy, mAllowedAlgorithms, isBypassable, isMetered, maxMtu, areAuthParamsInline);
    }

    /** Checks VPN profiles for interior equality. */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof VpnProfile)) {
            return false;
        }

        final VpnProfile other = (VpnProfile) obj;
        return Objects.equals(key, other.key)
                && Objects.equals(name, other.name)
                && type == other.type
                && Objects.equals(server, other.server)
                && Objects.equals(username, other.username)
                && Objects.equals(password, other.password)
                && Objects.equals(dnsServers, other.dnsServers)
                && Objects.equals(searchDomains, other.searchDomains)
                && Objects.equals(routes, other.routes)
                && mppe == other.mppe
                && Objects.equals(l2tpSecret, other.l2tpSecret)
                && Objects.equals(ipsecIdentifier, other.ipsecIdentifier)
                && Objects.equals(ipsecSecret, other.ipsecSecret)
                && Objects.equals(ipsecUserCert, other.ipsecUserCert)
                && Objects.equals(ipsecCaCert, other.ipsecCaCert)
                && Objects.equals(ipsecServerCert, other.ipsecServerCert)
                && Objects.equals(proxy, other.proxy)
                && Objects.equals(mAllowedAlgorithms, other.mAllowedAlgorithms)
                && isBypassable == other.isBypassable
                && isMetered == other.isMetered
                && maxMtu == other.maxMtu
                && areAuthParamsInline == other.areAuthParamsInline;
    }

    @NonNull
    public static final Creator<VpnProfile> CREATOR = new Creator<VpnProfile>() {
        @Override
        public VpnProfile createFromParcel(Parcel in) {
            return new VpnProfile(in);
        }

        @Override
        public VpnProfile[] newArray(int size) {
            return new VpnProfile[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }
}
