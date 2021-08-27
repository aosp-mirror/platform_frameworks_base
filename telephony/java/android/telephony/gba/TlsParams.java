/*
 * Copyright 2020 The Android Open Source Project
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
package android.telephony.gba;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Defines the TLS parameters for GBA as per IANA and TS 33.210, which are used
 * by some UA security protocol identifiers defined in 3GPP TS 33.220 Annex H,
 * and 3GPP TS 33.222.
 *
 * @hide
 */
@SystemApi
public class TlsParams {

    private TlsParams() {}

    /**
     * TLS protocol version supported by GBA
     */
    public static final int PROTOCOL_VERSION_TLS_1_2 = 0x0303;
    public static final int PROTOCOL_VERSION_TLS_1_3 = 0x0304;

    /**
     * TLS cipher suites are used to create {@link UaSecurityProtocolIdentifier}
     * by {@link UaSecurityProtocolIdentifier#create3GppUaSpId}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        prefix = {"TLS_"},
        value = {
            TLS_NULL_WITH_NULL_NULL,
            TLS_RSA_WITH_NULL_MD5,
            TLS_RSA_WITH_NULL_SHA,
            TLS_RSA_WITH_RC4_128_MD5,
            TLS_RSA_WITH_RC4_128_SHA,
            TLS_RSA_WITH_3DES_EDE_CBC_SHA,
            TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA,
            TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA,
            TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA,
            TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA,
            TLS_DH_ANON_WITH_RC4_128_MD5,
            TLS_DH_ANON_WITH_3DES_EDE_CBC_SHA,
            TLS_RSA_WITH_AES_128_CBC_SHA,
            TLS_DH_DSS_WITH_AES_128_CBC_SHA,
            TLS_DH_RSA_WITH_AES_128_CBC_SHA,
            TLS_DHE_DSS_WITH_AES_128_CBC_SHA,
            TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
            TLS_DH_ANON_WITH_AES_128_CBC_SHA,
            TLS_RSA_WITH_AES_256_CBC_SHA,
            TLS_DH_DSS_WITH_AES_256_CBC_SHA,
            TLS_DH_RSA_WITH_AES_256_CBC_SHA,
            TLS_DHE_DSS_WITH_AES_256_CBC_SHA,
            TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
            TLS_DH_ANON_WITH_AES_256_CBC_SHA,
            TLS_RSA_WITH_NULL_SHA256,
            TLS_RSA_WITH_AES_128_CBC_SHA256,
            TLS_RSA_WITH_AES_256_CBC_SHA256,
            TLS_DH_DSS_WITH_AES_128_CBC_SHA256,
            TLS_DH_RSA_WITH_AES_128_CBC_SHA256,
            TLS_DHE_DSS_WITH_AES_128_CBC_SHA256,
            TLS_DHE_RSA_WITH_AES_128_CBC_SHA256,
            TLS_DH_DSS_WITH_AES_256_CBC_SHA256,
            TLS_DH_RSA_WITH_AES_256_CBC_SHA256,
            TLS_DHE_DSS_WITH_AES_256_CBC_SHA256,
            TLS_DHE_RSA_WITH_AES_256_CBC_SHA256,
            TLS_DH_ANON_WITH_AES_128_CBC_SHA256,
            TLS_DH_ANON_WITH_AES_256_CBC_SHA256,
            TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
            TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,
            TLS_DHE_PSK_WITH_AES_128_GCM_SHA256,
            TLS_DHE_PSK_WITH_AES_256_GCM_SHA384,
            TLS_AES_128_GCM_SHA256,
            TLS_AES_256_GCM_SHA384,
            TLS_CHACHA20_POLY1305_SHA256,
            TLS_AES_128_CCM_SHA256,
            TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            TLS_DHE_RSA_WITH_AES_128_CCM,
            TLS_DHE_RSA_WITH_AES_256_CCM,
            TLS_DHE_PSK_WITH_AES_128_CCM,
            TLS_DHE_PSK_WITH_AES_256_CCM,
            TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256,
            TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256,
            TLS_ECDHE_PSK_WITH_AES_128_GCM_SHA256,
            TLS_ECDHE_PSK_WITH_AES_256_GCM_SHA384,
            TLS_ECDHE_PSK_WITH_AES_128_CCM_SHA256
        })
    public @interface TlsCipherSuite {}

    // Cipher suites for TLS v1.2 per RFC5246
    public static final int TLS_NULL_WITH_NULL_NULL = 0x0000;
    public static final int TLS_RSA_WITH_NULL_MD5 = 0x0001;
    public static final int TLS_RSA_WITH_NULL_SHA = 0x0002;
    public static final int TLS_RSA_WITH_RC4_128_MD5 = 0x0004;
    public static final int TLS_RSA_WITH_RC4_128_SHA = 0x0005;
    public static final int TLS_RSA_WITH_3DES_EDE_CBC_SHA = 0x000A;
    public static final int TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA = 0x000D;
    public static final int TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA = 0x0010;
    public static final int TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA = 0x0013;
    public static final int TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA = 0x0016;
    public static final int TLS_DH_ANON_WITH_RC4_128_MD5 = 0x0018;
    public static final int TLS_DH_ANON_WITH_3DES_EDE_CBC_SHA = 0x001B;
    public static final int TLS_RSA_WITH_AES_128_CBC_SHA = 0x002F;
    public static final int TLS_DH_DSS_WITH_AES_128_CBC_SHA = 0x0030;
    public static final int TLS_DH_RSA_WITH_AES_128_CBC_SHA = 0x0031;
    public static final int TLS_DHE_DSS_WITH_AES_128_CBC_SHA = 0x0032;
    public static final int TLS_DHE_RSA_WITH_AES_128_CBC_SHA = 0x0033;
    public static final int TLS_DH_ANON_WITH_AES_128_CBC_SHA = 0x0034;
    public static final int TLS_RSA_WITH_AES_256_CBC_SHA = 0x0035;
    public static final int TLS_DH_DSS_WITH_AES_256_CBC_SHA = 0x0036;
    public static final int TLS_DH_RSA_WITH_AES_256_CBC_SHA = 0x0037;
    public static final int TLS_DHE_DSS_WITH_AES_256_CBC_SHA = 0x0038;
    public static final int TLS_DHE_RSA_WITH_AES_256_CBC_SHA = 0x0039;
    public static final int TLS_DH_ANON_WITH_AES_256_CBC_SHA = 0x003A;
    public static final int TLS_RSA_WITH_NULL_SHA256 = 0x003B;
    public static final int TLS_RSA_WITH_AES_128_CBC_SHA256 = 0x003C;
    public static final int TLS_RSA_WITH_AES_256_CBC_SHA256 = 0x003D;
    public static final int TLS_DH_DSS_WITH_AES_128_CBC_SHA256 = 0x003E;
    public static final int TLS_DH_RSA_WITH_AES_128_CBC_SHA256 = 0x003F;
    public static final int TLS_DHE_DSS_WITH_AES_128_CBC_SHA256 = 0x0040;
    public static final int TLS_DHE_RSA_WITH_AES_128_CBC_SHA256 = 0x0067;
    public static final int TLS_DH_DSS_WITH_AES_256_CBC_SHA256 = 0x0068;
    public static final int TLS_DH_RSA_WITH_AES_256_CBC_SHA256 = 0x0069;
    public static final int TLS_DHE_DSS_WITH_AES_256_CBC_SHA256 = 0x006A;
    public static final int TLS_DHE_RSA_WITH_AES_256_CBC_SHA256 = 0x006B;
    public static final int TLS_DH_ANON_WITH_AES_128_CBC_SHA256 = 0x006C;
    public static final int TLS_DH_ANON_WITH_AES_256_CBC_SHA256 = 0x006D;

    // Cipher suites for TLS v1.3 per RFC8446 and recommended by IANA
    public static final int TLS_DHE_RSA_WITH_AES_128_GCM_SHA256 = 0x009E;
    public static final int TLS_DHE_RSA_WITH_AES_256_GCM_SHA384 = 0x009F;
    public static final int TLS_DHE_PSK_WITH_AES_128_GCM_SHA256 = 0x00AA;
    public static final int TLS_DHE_PSK_WITH_AES_256_GCM_SHA384 = 0x00AB;
    public static final int TLS_AES_128_GCM_SHA256 = 0x1301;
    public static final int TLS_AES_256_GCM_SHA384 = 0x1302;
    public static final int TLS_CHACHA20_POLY1305_SHA256 = 0x1303;
    public static final int TLS_AES_128_CCM_SHA256 = 0x1304;
    public static final int TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 = 0xC02B;
    public static final int TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 = 0xC02C;
    public static final int TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 = 0xC02F;
    public static final int TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 = 0xC030;
    public static final int TLS_DHE_RSA_WITH_AES_128_CCM = 0xC09E;
    public static final int TLS_DHE_RSA_WITH_AES_256_CCM = 0xC09F;
    public static final int TLS_DHE_PSK_WITH_AES_128_CCM = 0xC0A6;
    public static final int TLS_DHE_PSK_WITH_AES_256_CCM = 0xC0A7;
    public static final int TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 = 0xCCA8;
    public static final int TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 = 0xCCA9;
    public static final int TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256 = 0xCCAA;
    public static final int TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256 = 0xCCAC;
    public static final int TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256 = 0xCCAD;
    public static final int TLS_ECDHE_PSK_WITH_AES_128_GCM_SHA256 = 0xD001;
    public static final int TLS_ECDHE_PSK_WITH_AES_256_GCM_SHA384 = 0xD002;
    public static final int TLS_ECDHE_PSK_WITH_AES_128_CCM_SHA256 = 0xD005;

    private static final int[] CS_EXPECTED = {
        TLS_NULL_WITH_NULL_NULL,
        TLS_RSA_WITH_NULL_MD5,
        TLS_RSA_WITH_NULL_SHA,
        TLS_RSA_WITH_RC4_128_MD5,
        TLS_RSA_WITH_RC4_128_SHA,
        TLS_RSA_WITH_3DES_EDE_CBC_SHA,
        TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA,
        TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA,
        TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA,
        TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA,
        TLS_DH_ANON_WITH_RC4_128_MD5,
        TLS_DH_ANON_WITH_3DES_EDE_CBC_SHA,
        TLS_RSA_WITH_AES_128_CBC_SHA,
        TLS_DH_DSS_WITH_AES_128_CBC_SHA,
        TLS_DH_RSA_WITH_AES_128_CBC_SHA,
        TLS_DHE_DSS_WITH_AES_128_CBC_SHA,
        TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
        TLS_DH_ANON_WITH_AES_128_CBC_SHA,
        TLS_RSA_WITH_AES_256_CBC_SHA,
        TLS_DH_DSS_WITH_AES_256_CBC_SHA,
        TLS_DH_RSA_WITH_AES_256_CBC_SHA,
        TLS_DHE_DSS_WITH_AES_256_CBC_SHA,
        TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
        TLS_DH_ANON_WITH_AES_256_CBC_SHA,
        TLS_RSA_WITH_NULL_SHA256,
        TLS_RSA_WITH_AES_128_CBC_SHA256,
        TLS_RSA_WITH_AES_256_CBC_SHA256,
        TLS_DH_DSS_WITH_AES_128_CBC_SHA256,
        TLS_DH_RSA_WITH_AES_128_CBC_SHA256,
        TLS_DHE_DSS_WITH_AES_128_CBC_SHA256,
        TLS_DHE_RSA_WITH_AES_128_CBC_SHA256,
        TLS_DH_DSS_WITH_AES_256_CBC_SHA256,
        TLS_DH_RSA_WITH_AES_256_CBC_SHA256,
        TLS_DHE_DSS_WITH_AES_256_CBC_SHA256,
        TLS_DHE_RSA_WITH_AES_256_CBC_SHA256,
        TLS_DH_ANON_WITH_AES_128_CBC_SHA256,
        TLS_DH_ANON_WITH_AES_256_CBC_SHA256,
        TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
        TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,
        TLS_DHE_PSK_WITH_AES_128_GCM_SHA256,
        TLS_DHE_PSK_WITH_AES_256_GCM_SHA384,
        TLS_AES_128_GCM_SHA256,
        TLS_AES_256_GCM_SHA384,
        TLS_CHACHA20_POLY1305_SHA256,
        TLS_AES_128_CCM_SHA256,
        TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        TLS_DHE_RSA_WITH_AES_128_CCM,
        TLS_DHE_RSA_WITH_AES_256_CCM,
        TLS_DHE_PSK_WITH_AES_128_CCM,
        TLS_DHE_PSK_WITH_AES_256_CCM,
        TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256,
        TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256,
        TLS_ECDHE_PSK_WITH_AES_128_GCM_SHA256,
        TLS_ECDHE_PSK_WITH_AES_256_GCM_SHA384,
        TLS_ECDHE_PSK_WITH_AES_128_CCM_SHA256
    };

    /**
     * TLS supported groups required by TS 33.210
     */
    public static final int GROUP_SECP256R1 = 23;
    public static final int GROUP_SECP384R1 = 24;
    public static final int GROUP_X25519 = 29;
    public static final int GROUP_X448 = 30;

    /**
     * Signature algorithms shall be supported as per TS 33.210
     */
    public static final int SIG_RSA_PKCS1_SHA1 = 0X0201;
    public static final int SIG_ECDSA_SHA1 = 0X0203;
    public static final int SIG_RSA_PKCS1_SHA256 = 0X0401;
    public static final int SIG_ECDSA_SECP256R1_SHA256 = 0X0403;
    public static final int SIG_RSA_PKCS1_SHA256_LEGACY = 0X0420;
    public static final int SIG_RSA_PKCS1_SHA384 = 0X0501;
    public static final int SIG_ECDSA_SECP384R1_SHA384 = 0X0503;
    public static final int SIG_RSA_PKCS1_SHA384_LEGACY = 0X0520;
    public static final int SIG_RSA_PKCS1_SHA512 = 0X0601;
    public static final int SIG_ECDSA_SECP521R1_SHA512 = 0X0603;
    public static final int SIG_RSA_PKCS1_SHA512_LEGACY = 0X0620;
    public static final int SIG_RSA_PSS_RSAE_SHA256 = 0X0804;
    public static final int SIG_RSA_PSS_RSAE_SHA384 = 0X0805;
    public static final int SIG_RSA_PSS_RSAE_SHA512 = 0X0806;
    public static final int SIG_ECDSA_BRAINPOOLP256R1TLS13_SHA256 = 0X081A;
    public static final int SIG_ECDSA_BRAINPOOLP384R1TLS13_SHA384 = 0X081B;
    public static final int SIG_ECDSA_BRAINPOOLP512R1TLS13_SHA512 = 0X081C;

    /**
     * Returns whether the TLS cipher suite id is supported
     */
    public static boolean isTlsCipherSuiteSupported(int csId) {
        return Arrays.binarySearch(CS_EXPECTED, csId) >= 0;
    }
}
