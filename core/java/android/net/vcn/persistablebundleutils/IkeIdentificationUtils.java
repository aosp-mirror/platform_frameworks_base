/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn.persistablebundleutils;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.net.InetAddresses;
import android.net.ipsec.ike.IkeDerAsn1DnIdentification;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeIdentification;
import android.net.ipsec.ike.IkeIpv4AddrIdentification;
import android.net.ipsec.ike.IkeIpv6AddrIdentification;
import android.net.ipsec.ike.IkeKeyIdIdentification;
import android.net.ipsec.ike.IkeRfc822AddrIdentification;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Objects;

import javax.security.auth.x500.X500Principal;

/**
 * Abstract utility class to convert IkeIdentification to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class IkeIdentificationUtils {
    private static final String ID_TYPE_KEY = "ID_TYPE_KEY";

    private static final String DER_ASN1_DN_KEY = "DER_ASN1_DN_KEY";
    private static final String FQDN_KEY = "FQDN_KEY";
    private static final String KEY_ID_KEY = "KEY_ID_KEY";
    private static final String IP4_ADDRESS_KEY = "IP4_ADDRESS_KEY";
    private static final String IP6_ADDRESS_KEY = "IP6_ADDRESS_KEY";
    private static final String RFC822_ADDRESS_KEY = "RFC822_ADDRESS_KEY";

    private static final int ID_TYPE_DER_ASN1_DN = 1;
    private static final int ID_TYPE_FQDN = 2;
    private static final int ID_TYPE_IPV4_ADDR = 3;
    private static final int ID_TYPE_IPV6_ADDR = 4;
    private static final int ID_TYPE_KEY_ID = 5;
    private static final int ID_TYPE_RFC822_ADDR = 6;

    /** Serializes an IkeIdentification to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(@NonNull IkeIdentification ikeId) {
        if (ikeId instanceof IkeDerAsn1DnIdentification) {
            final PersistableBundle result = createPersistableBundle(ID_TYPE_DER_ASN1_DN);
            IkeDerAsn1DnIdentification id = (IkeDerAsn1DnIdentification) ikeId;
            result.putPersistableBundle(
                    DER_ASN1_DN_KEY,
                    PersistableBundleUtils.fromByteArray(id.derAsn1Dn.getEncoded()));
            return result;
        } else if (ikeId instanceof IkeFqdnIdentification) {
            final PersistableBundle result = createPersistableBundle(ID_TYPE_FQDN);
            IkeFqdnIdentification id = (IkeFqdnIdentification) ikeId;
            result.putString(FQDN_KEY, id.fqdn);
            return result;
        } else if (ikeId instanceof IkeIpv4AddrIdentification) {
            final PersistableBundle result = createPersistableBundle(ID_TYPE_IPV4_ADDR);
            IkeIpv4AddrIdentification id = (IkeIpv4AddrIdentification) ikeId;
            result.putString(IP4_ADDRESS_KEY, id.ipv4Address.getHostAddress());
            return result;
        } else if (ikeId instanceof IkeIpv6AddrIdentification) {
            final PersistableBundle result = createPersistableBundle(ID_TYPE_IPV6_ADDR);
            IkeIpv6AddrIdentification id = (IkeIpv6AddrIdentification) ikeId;
            result.putString(IP6_ADDRESS_KEY, id.ipv6Address.getHostAddress());
            return result;
        } else if (ikeId instanceof IkeKeyIdIdentification) {
            final PersistableBundle result = createPersistableBundle(ID_TYPE_KEY_ID);
            IkeKeyIdIdentification id = (IkeKeyIdIdentification) ikeId;
            result.putPersistableBundle(KEY_ID_KEY, PersistableBundleUtils.fromByteArray(id.keyId));
            return result;
        } else if (ikeId instanceof IkeRfc822AddrIdentification) {
            final PersistableBundle result = createPersistableBundle(ID_TYPE_RFC822_ADDR);
            IkeRfc822AddrIdentification id = (IkeRfc822AddrIdentification) ikeId;
            result.putString(RFC822_ADDRESS_KEY, id.rfc822Name);
            return result;
        } else {
            throw new IllegalStateException("Unrecognized IkeIdentification subclass");
        }
    }

    private static PersistableBundle createPersistableBundle(int idType) {
        final PersistableBundle result = new PersistableBundle();
        result.putInt(ID_TYPE_KEY, idType);
        return result;
    }

    /** Constructs an IkeIdentification by deserializing a PersistableBundle. */
    @NonNull
    public static IkeIdentification fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");
        int idType = in.getInt(ID_TYPE_KEY);
        switch (idType) {
            case ID_TYPE_DER_ASN1_DN:
                final PersistableBundle dnBundle = in.getPersistableBundle(DER_ASN1_DN_KEY);
                Objects.requireNonNull(dnBundle, "ASN1 DN was null");
                return new IkeDerAsn1DnIdentification(
                        new X500Principal(PersistableBundleUtils.toByteArray(dnBundle)));
            case ID_TYPE_FQDN:
                return new IkeFqdnIdentification(in.getString(FQDN_KEY));
            case ID_TYPE_IPV4_ADDR:
                final String v4AddressStr = in.getString(IP4_ADDRESS_KEY);
                Objects.requireNonNull(v4AddressStr, "IPv4 address was null");
                return new IkeIpv4AddrIdentification(
                        (Inet4Address) InetAddresses.parseNumericAddress(v4AddressStr));
            case ID_TYPE_IPV6_ADDR:
                final String v6AddressStr = in.getString(IP6_ADDRESS_KEY);
                Objects.requireNonNull(v6AddressStr, "IPv6 address was null");
                return new IkeIpv6AddrIdentification(
                        (Inet6Address) InetAddresses.parseNumericAddress(v6AddressStr));
            case ID_TYPE_KEY_ID:
                final PersistableBundle keyIdBundle = in.getPersistableBundle(KEY_ID_KEY);
                Objects.requireNonNull(in, "Key ID was null");
                return new IkeKeyIdIdentification(PersistableBundleUtils.toByteArray(keyIdBundle));
            case ID_TYPE_RFC822_ADDR:
                return new IkeRfc822AddrIdentification(in.getString(RFC822_ADDRESS_KEY));
            default:
                throw new IllegalStateException("Unrecognized IKE ID type: " + idType);
        }
    }
}
