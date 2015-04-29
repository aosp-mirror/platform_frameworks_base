/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security;

import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.util.Collection;
import java.util.Locale;

/**
 * @hide
 */
public abstract class KeymasterUtils {

    private KeymasterUtils() {}

    public static int getKeymasterAlgorithmFromJcaSecretKeyAlgorithm(String jcaKeyAlgorithm) {
        if ("AES".equalsIgnoreCase(jcaKeyAlgorithm)) {
            return KeymasterDefs.KM_ALGORITHM_AES;
        } else if (jcaKeyAlgorithm.toUpperCase(Locale.US).startsWith("HMAC")) {
            return KeymasterDefs.KM_ALGORITHM_HMAC;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported secret key algorithm: " + jcaKeyAlgorithm);
        }
    }

    public static String getJcaSecretKeyAlgorithm(int keymasterAlgorithm, int keymasterDigest) {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_AES:
                if (keymasterDigest != -1) {
                    throw new IllegalArgumentException(
                            "Digest not supported for AES key: " + keymasterDigest);
                }
                return "AES";
            case KeymasterDefs.KM_ALGORITHM_HMAC:
                switch (keymasterDigest) {
                    case KeymasterDefs.KM_DIGEST_SHA1:
                        return "HmacSHA1";
                    case KeymasterDefs.KM_DIGEST_SHA_2_224:
                        return "HmacSHA224";
                    case KeymasterDefs.KM_DIGEST_SHA_2_256:
                        return "HmacSHA256";
                    case KeymasterDefs.KM_DIGEST_SHA_2_384:
                        return "HmacSHA384";
                    case KeymasterDefs.KM_DIGEST_SHA_2_512:
                        return "HmacSHA512";
                    default:
                        throw new IllegalArgumentException(
                                "Unsupported HMAC digest: " + keymasterDigest);
                }
            default:
                throw new IllegalArgumentException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    public static String getJcaKeyPairAlgorithmFromKeymasterAlgorithm(int keymasterAlgorithm) {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
                return "RSA";
            case KeymasterDefs.KM_ALGORITHM_EC:
                return "EC";
            default:
                throw new IllegalArgumentException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    public static int getKeymasterDigestfromJcaSecretKeyAlgorithm(String jcaKeyAlgorithm) {
        String algorithmUpper = jcaKeyAlgorithm.toUpperCase(Locale.US);
        if (algorithmUpper.startsWith("HMAC")) {
            String digestUpper = algorithmUpper.substring("HMAC".length());
            switch (digestUpper) {
                case "MD5":
                    return KeymasterDefs.KM_DIGEST_MD5;
                case "SHA1":
                    return KeymasterDefs.KM_DIGEST_SHA1;
                case "SHA224":
                    return KeymasterDefs.KM_DIGEST_SHA_2_224;
                case "SHA256":
                    return KeymasterDefs.KM_DIGEST_SHA_2_256;
                case "SHA384":
                    return KeymasterDefs.KM_DIGEST_SHA_2_384;
                case "SHA512":
                    return KeymasterDefs.KM_DIGEST_SHA_2_512;
                default:
                    throw new IllegalArgumentException("Unsupported HMAC digest: " + digestUpper);
            }
        } else {
            return -1;
        }
    }

    public static int getKeymasterDigestFromJcaDigestAlgorithm(String jcaDigestAlgorithm) {
        if (jcaDigestAlgorithm.equalsIgnoreCase("SHA-1")) {
            return KeymasterDefs.KM_DIGEST_SHA1;
        } else if (jcaDigestAlgorithm.equalsIgnoreCase("SHA-224")) {
            return KeymasterDefs.KM_DIGEST_SHA_2_224;
        } else if (jcaDigestAlgorithm.equalsIgnoreCase("SHA-256")) {
            return KeymasterDefs.KM_DIGEST_SHA_2_256;
        } else if (jcaDigestAlgorithm.equalsIgnoreCase("SHA-384")) {
            return KeymasterDefs.KM_DIGEST_SHA_2_384;
        } else if (jcaDigestAlgorithm.equalsIgnoreCase("SHA-512")) {
            return KeymasterDefs.KM_DIGEST_SHA_2_512;
        } else if (jcaDigestAlgorithm.equalsIgnoreCase("NONE")) {
            return KeymasterDefs.KM_DIGEST_NONE;
        } else if (jcaDigestAlgorithm.equalsIgnoreCase("MD5")) {
            return KeymasterDefs.KM_DIGEST_MD5;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported digest algorithm: " + jcaDigestAlgorithm);
        }
    }

    public static String getJcaDigestAlgorithmFromKeymasterDigest(int keymasterDigest) {
        switch (keymasterDigest) {
            case KeymasterDefs.KM_DIGEST_NONE:
                return "NONE";
            case KeymasterDefs.KM_DIGEST_MD5:
                return "MD5";
            case KeymasterDefs.KM_DIGEST_SHA1:
                return "SHA-1";
            case KeymasterDefs.KM_DIGEST_SHA_2_224:
                return "SHA-224";
            case KeymasterDefs.KM_DIGEST_SHA_2_256:
                return "SHA-256";
            case KeymasterDefs.KM_DIGEST_SHA_2_384:
                return "SHA-384";
            case KeymasterDefs.KM_DIGEST_SHA_2_512:
                return "SHA-512";
            default:
                throw new IllegalArgumentException(
                        "Unsupported digest algorithm: " + keymasterDigest);
        }
    }

    public static String[] getJcaDigestAlgorithmsFromKeymasterDigests(
            Collection<Integer> keymasterDigests) {
        if (keymasterDigests.isEmpty()) {
            return EmptyArray.STRING;
        }
        String[] result = new String[keymasterDigests.size()];
        int offset = 0;
        for (int keymasterDigest : keymasterDigests) {
            result[offset] = getJcaDigestAlgorithmFromKeymasterDigest(keymasterDigest);
            offset++;
        }
        return result;
    }

    public static int[] getKeymasterDigestsFromJcaDigestAlgorithms(String[] jcaDigestAlgorithms) {
        if ((jcaDigestAlgorithms == null) || (jcaDigestAlgorithms.length == 0)) {
            return EmptyArray.INT;
        }
        int[] result = new int[jcaDigestAlgorithms.length];
        int offset = 0;
        for (String jcaDigestAlgorithm : jcaDigestAlgorithms) {
            result[offset] = getKeymasterDigestFromJcaDigestAlgorithm(jcaDigestAlgorithm);
            offset++;
        }
        return result;
    }

    public static int getDigestOutputSizeBytes(int keymasterDigest) {
        switch (keymasterDigest) {
            case KeymasterDefs.KM_DIGEST_NONE:
                return -1;
            case KeymasterDefs.KM_DIGEST_MD5:
                return 128 / 8;
            case KeymasterDefs.KM_DIGEST_SHA1:
                return 160 / 8;
            case KeymasterDefs.KM_DIGEST_SHA_2_224:
                return 224 / 8;
            case KeymasterDefs.KM_DIGEST_SHA_2_256:
                return 256 / 8;
            case KeymasterDefs.KM_DIGEST_SHA_2_384:
                return 384 / 8;
            case KeymasterDefs.KM_DIGEST_SHA_2_512:
                return 512 / 8;
            default:
                throw new IllegalArgumentException("Unknown digest: " + keymasterDigest);
        }
    }

    public static int getKeymasterBlockModeFromJcaBlockMode(String jcaBlockMode) {
        if ("ECB".equalsIgnoreCase(jcaBlockMode)) {
            return KeymasterDefs.KM_MODE_ECB;
        } else if ("CBC".equalsIgnoreCase(jcaBlockMode)) {
            return KeymasterDefs.KM_MODE_CBC;
        } else if ("CTR".equalsIgnoreCase(jcaBlockMode)) {
            return KeymasterDefs.KM_MODE_CTR;
        } else if ("GCM".equalsIgnoreCase(jcaBlockMode)) {
            return KeymasterDefs.KM_MODE_GCM;
        } else {
            throw new IllegalArgumentException("Unsupported block mode: " + jcaBlockMode);
        }
    }

    public static String getJcaBlockModeFromKeymasterBlockMode(int keymasterBlockMode) {
        switch (keymasterBlockMode) {
            case KeymasterDefs.KM_MODE_ECB:
                return "ECB";
            case KeymasterDefs.KM_MODE_CBC:
                return "CBC";
            case KeymasterDefs.KM_MODE_CTR:
                return "CTR";
            case KeymasterDefs.KM_MODE_GCM:
                return "GCM";
            default:
                throw new IllegalArgumentException("Unsupported block mode: " + keymasterBlockMode);
        }
    }

    public static String[] getJcaBlockModesFromKeymasterBlockModes(
            Collection<Integer> keymasterBlockModes) {
        if ((keymasterBlockModes == null) || (keymasterBlockModes.isEmpty())) {
            return EmptyArray.STRING;
        }
        String[] result = new String[keymasterBlockModes.size()];
        int offset = 0;
        for (int keymasterBlockMode : keymasterBlockModes) {
            result[offset] = getJcaBlockModeFromKeymasterBlockMode(keymasterBlockMode);
            offset++;
        }
        return result;
    }

    public static int[] getKeymasterBlockModesFromJcaBlockModes(String[] jcaBlockModes) {
        if ((jcaBlockModes == null) || (jcaBlockModes.length == 0)) {
            return EmptyArray.INT;
        }
        int[] result = new int[jcaBlockModes.length];
        for (int i = 0; i < jcaBlockModes.length; i++) {
            result[i] = getKeymasterBlockModeFromJcaBlockMode(jcaBlockModes[i]);
        }
        return result;
    }

    public static boolean isKeymasterBlockModeIndCpaCompatible(int keymasterBlockMode) {
        switch (keymasterBlockMode) {
            case KeymasterDefs.KM_MODE_ECB:
                return false;
            case KeymasterDefs.KM_MODE_CBC:
            case KeymasterDefs.KM_MODE_CTR:
            case KeymasterDefs.KM_MODE_GCM:
                return true;
            default:
                throw new IllegalArgumentException("Unsupported block mode: " + keymasterBlockMode);
        }
    }

    public static int getKeymasterPaddingFromJcaEncryptionPadding(String jcaPadding) {
        if ("NoPadding".equalsIgnoreCase(jcaPadding)) {
            return KeymasterDefs.KM_PAD_NONE;
        } else if ("PKCS7Padding".equalsIgnoreCase(jcaPadding)) {
            return KeymasterDefs.KM_PAD_PKCS7;
        } else if ("PKCS1Padding".equalsIgnoreCase(jcaPadding)) {
            return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT;
        } else if ("OEAPPadding".equalsIgnoreCase(jcaPadding)) {
            return KeymasterDefs.KM_PAD_RSA_OAEP;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported encryption padding scheme: " + jcaPadding);
        }
    }

    public static String getJcaEncryptionPaddingFromKeymasterPadding(int keymasterPadding) {
        switch (keymasterPadding) {
            case KeymasterDefs.KM_PAD_NONE:
                return "NoPadding";
            case KeymasterDefs.KM_PAD_PKCS7:
                return "PKCS7Padding";
            case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT:
                return "PKCS1Padding";
            case KeymasterDefs.KM_PAD_RSA_OAEP:
                return "OEAPPadding";
            default:
                throw new IllegalArgumentException(
                        "Unsupported encryption padding: " + keymasterPadding);
        }
    }

    public static int getKeymasterPaddingFromJcaSignaturePadding(String jcaPadding) {
        if ("PKCS#1".equalsIgnoreCase(jcaPadding)) {
            return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN;
        } if ("PSS".equalsIgnoreCase(jcaPadding)) {
            return KeymasterDefs.KM_PAD_RSA_PSS;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported signature padding scheme: " + jcaPadding);
        }
    }

    public static String getJcaSignaturePaddingFromKeymasterPadding(int keymasterPadding) {
        switch (keymasterPadding) {
            case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN:
                return "PKCS#1";
            case KeymasterDefs.KM_PAD_RSA_PSS:
                return "PSS";
            default:
                throw new IllegalArgumentException(
                        "Unsupported signature padding: " + keymasterPadding);
        }
    }

    public static int[] getKeymasterPaddingsFromJcaEncryptionPaddings(String[] jcaPaddings) {
        if ((jcaPaddings == null) || (jcaPaddings.length == 0)) {
            return EmptyArray.INT;
        }
        int[] result = new int[jcaPaddings.length];
        for (int i = 0; i < jcaPaddings.length; i++) {
            result[i] = getKeymasterPaddingFromJcaEncryptionPadding(jcaPaddings[i]);
        }
        return result;
    }

    public static int[] getKeymasterPaddingsFromJcaSignaturePaddings(String[] jcaPaddings) {
        if ((jcaPaddings == null) || (jcaPaddings.length == 0)) {
            return EmptyArray.INT;
        }
        int[] result = new int[jcaPaddings.length];
        for (int i = 0; i < jcaPaddings.length; i++) {
            result[i] = getKeymasterPaddingFromJcaSignaturePadding(jcaPaddings[i]);
        }
        return result;
    }
}
