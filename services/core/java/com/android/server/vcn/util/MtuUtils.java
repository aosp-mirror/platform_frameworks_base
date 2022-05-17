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

package com.android.server.vcn.util;

import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_3DES;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CBC;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_CTR;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8;
import static android.net.ipsec.ike.SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_AES_CMAC_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256;
import static android.net.ipsec.ike.SaProposal.INTEGRITY_ALGORITHM_NONE;

import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;

import static java.lang.Math.max;
import static java.util.Collections.unmodifiableMap;

import android.annotation.NonNull;
import android.net.ipsec.ike.ChildSaProposal;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import java.util.List;
import java.util.Map;

/** @hide */
public class MtuUtils {
    private static final String TAG = MtuUtils.class.getSimpleName();
    /**
     * Max ESP overhead possible
     *
     * <p>60 (Outer IPv4 + options) + 8 (UDP encap) + 4 (SPI) + 4 (Seq) + 2 (Pad Length + Next
     * Header). Note: Payload data, Pad Length and Next Header will need to be padded to be multiple
     * of the block size of a cipher, and at the same time be aligned on a 4-byte boundary.
     */
    private static final int GENERIC_ESP_OVERHEAD_MAX_V4 = 78;

    /**
     * Max ESP overhead possible
     *
     * <p>40 (Outer IPv6) + 4 (SPI) + 4 (Seq) + 2 (Pad Length + Next Header). Note: Payload data,
     * Pad Length and Next Header will need to be padded to be multiple of the block size of a
     * cipher, and at the same time be aligned on a 4-byte boundary.
     */
    private static final int GENERIC_ESP_OVERHEAD_MAX_V6 = 50;

    /** Maximum overheads of authentication algorithms, keyed on IANA-defined constants */
    private static final Map<Integer, Integer> AUTH_ALGORITHM_OVERHEAD;

    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(INTEGRITY_ALGORITHM_NONE, 0);
        map.put(INTEGRITY_ALGORITHM_HMAC_SHA1_96, 12);
        map.put(INTEGRITY_ALGORITHM_AES_XCBC_96, 12);
        map.put(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128, 32);
        map.put(INTEGRITY_ALGORITHM_HMAC_SHA2_384_192, 48);
        map.put(INTEGRITY_ALGORITHM_HMAC_SHA2_512_256, 64);
        map.put(INTEGRITY_ALGORITHM_AES_CMAC_96, 12);

        AUTH_ALGORITHM_OVERHEAD = unmodifiableMap(map);
    }

    /** Maximum overheads of encryption algorithms, keyed on IANA-defined constants */
    private static final Map<Integer, Integer> CRYPT_ALGORITHM_OVERHEAD;

    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(ENCRYPTION_ALGORITHM_3DES, 15); // 8 (IV) + 7 (Max pad)
        map.put(ENCRYPTION_ALGORITHM_AES_CBC, 31); // 16 (IV) + 15 (Max pad)
        map.put(ENCRYPTION_ALGORITHM_AES_CTR, 11); // 8 (IV) + 3 (Max pad)

        CRYPT_ALGORITHM_OVERHEAD = unmodifiableMap(map);
    }

    /** Maximum overheads of combined mode algorithms, keyed on IANA-defined constants */
    private static final Map<Integer, Integer> AUTHCRYPT_ALGORITHM_OVERHEAD;

    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(ENCRYPTION_ALGORITHM_AES_GCM_8, 19); // 8 (IV) + 3 (Max pad) + 8 (ICV)
        map.put(ENCRYPTION_ALGORITHM_AES_GCM_12, 23); // 8 (IV) + 3 (Max pad) + 12 (ICV)
        map.put(ENCRYPTION_ALGORITHM_AES_GCM_16, 27); // 8 (IV) + 3 (Max pad) + 16 (ICV)
        map.put(ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, 27); // 8 (IV) + 3 (Max pad) + 16 (ICV)

        AUTHCRYPT_ALGORITHM_OVERHEAD = unmodifiableMap(map);
    }

    /**
     * Calculates the MTU of the inner interface based on the parameters provided
     *
     * <p>The MTU of the inner interface will be the minimum of the following:
     *
     * <ul>
     *   <li>The MTU of the outer interface, minus the greatest ESP overhead (based on proposed
     *       algorithms).
     *   <li>The maximum MTU as provided in the arguments.
     * </ul>
     */
    public static int getMtu(
            @NonNull List<ChildSaProposal> childProposals,
            int maxMtu,
            int underlyingMtu,
            boolean isIpv4) {
        if (underlyingMtu <= 0) {
            return IPV6_MIN_MTU;
        }

        int maxAuthOverhead = 0;
        int maxCryptOverhead = 0;
        int maxAuthCryptOverhead = 0;

        for (ChildSaProposal proposal : childProposals) {
            for (Pair<Integer, Integer> encryptionAlgoPair : proposal.getEncryptionAlgorithms()) {
                final int algo = encryptionAlgoPair.first;

                if (AUTHCRYPT_ALGORITHM_OVERHEAD.containsKey(algo)) {
                    maxAuthCryptOverhead =
                            max(maxAuthCryptOverhead, AUTHCRYPT_ALGORITHM_OVERHEAD.get(algo));
                    continue;
                } else if (CRYPT_ALGORITHM_OVERHEAD.containsKey(algo)) {
                    maxCryptOverhead = max(maxCryptOverhead, CRYPT_ALGORITHM_OVERHEAD.get(algo));
                    continue;
                }

                Slog.wtf(TAG, "Unknown encryption algorithm requested: " + algo);
                return IPV6_MIN_MTU;
            }

            for (int algo : proposal.getIntegrityAlgorithms()) {
                if (AUTH_ALGORITHM_OVERHEAD.containsKey(algo)) {
                    maxAuthOverhead = max(maxAuthOverhead, AUTH_ALGORITHM_OVERHEAD.get(algo));
                    continue;
                }

                Slog.wtf(TAG, "Unknown integrity algorithm requested: " + algo);
                return IPV6_MIN_MTU;
            }
        }

        final int genericEspOverheadMax =
                isIpv4 ? GENERIC_ESP_OVERHEAD_MAX_V4 : GENERIC_ESP_OVERHEAD_MAX_V6;

        // Return minimum of maxMtu, and the adjusted MTUs based on algorithms.
        final int combinedModeMtu = underlyingMtu - maxAuthCryptOverhead - genericEspOverheadMax;
        final int normalModeMtu =
                underlyingMtu - maxCryptOverhead - maxAuthOverhead - genericEspOverheadMax;
        return Math.min(Math.min(maxMtu, combinedModeMtu), normalModeMtu);
    }
}
