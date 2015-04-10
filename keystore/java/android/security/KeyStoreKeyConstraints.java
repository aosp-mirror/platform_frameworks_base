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

import android.annotation.IntDef;
import android.security.keymaster.KeymasterDefs;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Locale;

/**
 * Constraints for {@code AndroidKeyStore} keys.
 *
 * @hide
 */
public abstract class KeyStoreKeyConstraints {
    private KeyStoreKeyConstraints() {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {Purpose.ENCRYPT, Purpose.DECRYPT, Purpose.SIGN, Purpose.VERIFY})
    public @interface PurposeEnum {}

    /**
     * Purpose of key.
     */
    public static abstract class Purpose {
        private Purpose() {}

        /**
         * Purpose: encryption.
         */
        public static final int ENCRYPT = 1 << 0;

        /**
         * Purpose: decryption.
         */
        public static final int DECRYPT = 1 << 1;

        /**
         * Purpose: signing.
         */
        public static final int SIGN = 1 << 2;

        /**
         * Purpose: signature verification.
         */
        public static final int VERIFY = 1 << 3;

        /**
         * @hide
         */
        public static int toKeymaster(@PurposeEnum int purpose) {
            switch (purpose) {
                case ENCRYPT:
                    return KeymasterDefs.KM_PURPOSE_ENCRYPT;
                case DECRYPT:
                    return KeymasterDefs.KM_PURPOSE_DECRYPT;
                case SIGN:
                    return KeymasterDefs.KM_PURPOSE_SIGN;
                case VERIFY:
                    return KeymasterDefs.KM_PURPOSE_VERIFY;
                default:
                    throw new IllegalArgumentException("Unknown purpose: " + purpose);
            }
        }

        /**
         * @hide
         */
        public static @PurposeEnum int fromKeymaster(int purpose) {
            switch (purpose) {
                case KeymasterDefs.KM_PURPOSE_ENCRYPT:
                    return ENCRYPT;
                case KeymasterDefs.KM_PURPOSE_DECRYPT:
                    return DECRYPT;
                case KeymasterDefs.KM_PURPOSE_SIGN:
                    return SIGN;
                case KeymasterDefs.KM_PURPOSE_VERIFY:
                    return VERIFY;
                default:
                    throw new IllegalArgumentException("Unknown purpose: " + purpose);
            }
        }

        /**
         * @hide
         */
        public static int[] allToKeymaster(@PurposeEnum int purposes) {
            int[] result = getSetFlags(purposes);
            for (int i = 0; i < result.length; i++) {
                result[i] = toKeymaster(result[i]);
            }
            return result;
        }

        /**
         * @hide
         */
        public static @PurposeEnum int allFromKeymaster(Collection<Integer> purposes) {
            @PurposeEnum int result = 0;
            for (int keymasterPurpose : purposes) {
                result |= fromKeymaster(keymasterPurpose);
            }
            return result;
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Algorithm.AES, Algorithm.HMAC, Algorithm.RSA, Algorithm.EC})
    public @interface AlgorithmEnum {}

    /**
     * Key algorithm.
     */
    public static abstract class Algorithm {
        private Algorithm() {}

        /**
         * Key algorithm: AES.
         */
        public static final int AES = 1 << 0;

        /**
         * Key algorithm: HMAC.
         */
        public static final int HMAC = 1 << 1;

        /**
         * Key algorithm: RSA.
         */
        public static final int RSA = 1 << 2;

        /**
         * Key algorithm: EC.
         */
        public static final int EC = 1 << 3;

        /**
         * @hide
         */
        public static int toKeymaster(@AlgorithmEnum int algorithm) {
            switch (algorithm) {
                case AES:
                    return KeymasterDefs.KM_ALGORITHM_AES;
                case HMAC:
                    return KeymasterDefs.KM_ALGORITHM_HMAC;
                case RSA:
                    return KeymasterDefs.KM_ALGORITHM_RSA;
                case EC:
                    return KeymasterDefs.KM_ALGORITHM_EC;
                default:
                    throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
            }
        }

        /**
         * @hide
         */
        public static @AlgorithmEnum int fromKeymaster(int algorithm) {
            switch (algorithm) {
                case KeymasterDefs.KM_ALGORITHM_AES:
                    return AES;
                case KeymasterDefs.KM_ALGORITHM_HMAC:
                    return HMAC;
                case KeymasterDefs.KM_ALGORITHM_RSA:
                    return RSA;
                case KeymasterDefs.KM_ALGORITHM_EC:
                    return EC;
                default:
                    throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
            }
        }

        /**
         * @hide
         */
        public static String toString(@AlgorithmEnum int algorithm) {
            switch (algorithm) {
                case AES:
                    return "AES";
                case HMAC:
                    return "HMAC";
                case RSA:
                    return "RSA";
                case EC:
                    return "EC";
                default:
                    throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
            }
        }

        /**
         * @hide
         */
        public static @AlgorithmEnum int fromJCASecretKeyAlgorithm(String algorithm) {
            if (algorithm == null) {
                throw new NullPointerException("algorithm == null");
            } else  if ("AES".equalsIgnoreCase(algorithm)) {
                return AES;
            } else if (algorithm.toLowerCase(Locale.US).startsWith("hmac")) {
                return HMAC;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported secret key algorithm: " + algorithm);
            }
        }

        /**
         * @hide
         */
        public static String toJCASecretKeyAlgorithm(@AlgorithmEnum int algorithm,
                @DigestEnum Integer digest) {
            switch (algorithm) {
                case AES:
                    return "AES";
                case HMAC:
                    if (digest == null) {
                        throw new IllegalArgumentException("HMAC digest not specified");
                    }
                    switch (digest) {
                        case Digest.MD5:
                            return "HmacMD5";
                        case Digest.SHA1:
                            return "HmacSHA1";
                        case Digest.SHA224:
                            return "HmacSHA224";
                        case Digest.SHA256:
                            return "HmacSHA256";
                        case Digest.SHA384:
                            return "HmacSHA384";
                        case Digest.SHA512:
                            return "HmacSHA512";
                        default:
                            throw new IllegalArgumentException(
                                    "Unsupported HMAC digest: " + digest);
                    }
                default:
                    throw new IllegalArgumentException("Unsupported key algorithm: " + algorithm);
            }
        }

        /**
         * @hide
         */
        public static String toJCAKeyPairAlgorithm(@AlgorithmEnum int algorithm) {
            switch (algorithm) {
                case RSA:
                    return "RSA";
                case EC:
                    return "EC";
                default:
                    throw new IllegalArgumentException("Unsupported key alorithm: " + algorithm);
            }
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {
                Padding.NONE,
                Padding.PKCS7,
                Padding.RSA_PKCS1_ENCRYPTION,
                Padding.RSA_PKCS1_SIGNATURE,
                Padding.RSA_OAEP,
                Padding.RSA_PSS,
                })
    public @interface PaddingEnum {}

    /**
     * Padding for signing and encryption.
     */
    public static abstract class Padding {
        private Padding() {}

        /**
         * No padding.
         */
        public static final int NONE = 1 << 0;

        /**
         * PKCS#7 padding.
         */
        public static final int PKCS7 = 1 << 1;

        /**
         * RSA PKCS#1 v1.5 padding for encryption/decryption.
         */
        public static final int RSA_PKCS1_ENCRYPTION = 1 << 2;

        /**
         * RSA PKCS#1 v1.5 padding for signatures.
         */
        public static final int RSA_PKCS1_SIGNATURE = 1 << 3;

        /**
         * RSA Optimal Asymmetric Encryption Padding (OAEP).
         */
        public static final int RSA_OAEP = 1 << 4;

        /**
         * RSA PKCS#1 v2.1 Probabilistic Signature Scheme (PSS) padding.
         */
        public static final int RSA_PSS = 1 << 5;

        /**
         * @hide
         */
        public static int toKeymaster(int padding) {
            switch (padding) {
                case NONE:
                    return KeymasterDefs.KM_PAD_NONE;
                case PKCS7:
                    return KeymasterDefs.KM_PAD_PKCS7;
                case RSA_PKCS1_ENCRYPTION:
                    return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT;
                case RSA_PKCS1_SIGNATURE:
                    return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN;
                case RSA_OAEP:
                    return KeymasterDefs.KM_PAD_RSA_OAEP;
                case RSA_PSS:
                    return KeymasterDefs.KM_PAD_RSA_PSS;
                default:
                    throw new IllegalArgumentException("Unknown padding: " + padding);
            }
        }

        /**
         * @hide
         */
        public static @PaddingEnum int fromKeymaster(int padding) {
            switch (padding) {
                case KeymasterDefs.KM_PAD_NONE:
                    return NONE;
                case KeymasterDefs.KM_PAD_PKCS7:
                    return PKCS7;
                case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT:
                    return RSA_PKCS1_ENCRYPTION;
                case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN:
                    return RSA_PKCS1_SIGNATURE;
                case KeymasterDefs.KM_PAD_RSA_OAEP:
                    return RSA_OAEP;
                case KeymasterDefs.KM_PAD_RSA_PSS:
                    return RSA_PSS;
                default:
                    throw new IllegalArgumentException("Unknown padding: " + padding);
            }
        }

        /**
         * @hide
         */
        public static String toString(@PaddingEnum int padding) {
            switch (padding) {
                case NONE:
                    return "NONE";
                case PKCS7:
                    return "PKCS#7";
                case RSA_PKCS1_ENCRYPTION:
                    return "RSA PKCS#1 (encryption)";
                case RSA_PKCS1_SIGNATURE:
                    return "RSA PKCS#1 (signature)";
                case RSA_OAEP:
                    return "RSA OAEP";
                case RSA_PSS:
                    return "RSA PSS";
                default:
                    throw new IllegalArgumentException("Unknown padding: " + padding);
            }
        }

        /**
         * @hide
         */
        public static @PaddingEnum int fromJCACipherPadding(String padding) {
            String paddingLower = padding.toLowerCase(Locale.US);
            if ("nopadding".equals(paddingLower)) {
                return NONE;
            } else if ("pkcs7padding".equals(paddingLower)) {
                return PKCS7;
            } else if ("pkcs1padding".equals(paddingLower)) {
                return RSA_PKCS1_ENCRYPTION;
            } else if (("oaeppadding".equals(paddingLower))
                    || ((paddingLower.startsWith("oaepwith"))
                            && (paddingLower.endsWith("padding")))) {
                return RSA_OAEP;
            } else {
                throw new IllegalArgumentException("Unknown padding: " + padding);
            }
        }

        /**
         * @hide
         */
        public static int[] allToKeymaster(@PaddingEnum int paddings) {
            int[] result = getSetFlags(paddings);
            for (int i = 0; i < result.length; i++) {
                result[i] = toKeymaster(result[i]);
            }
            return result;
        }

        /**
         * @hide
         */
        public static @PaddingEnum int allFromKeymaster(Collection<Integer> paddings) {
            @PaddingEnum int result = 0;
            for (int keymasterPadding : paddings) {
                result |= fromKeymaster(keymasterPadding);
            }
            return result;
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {
                Digest.NONE,
                Digest.MD5,
                Digest.SHA1,
                Digest.SHA224,
                Digest.SHA256,
                Digest.SHA384,
                Digest.SHA512,
                })
    public @interface DigestEnum {}

    /**
     * Digests that can be used with a key when signing or generating Message Authentication
     * Codes (MACs).
     */
    public static abstract class Digest {
        private Digest() {}

        /**
         * No digest: sign/authenticate the raw message.
         */
        public static final int NONE = 1 << 0;

        /**
         * MD5 digest.
         */
        public static final int MD5 = 1 << 1;

        /**
         * SHA-1 digest.
         */
        public static final int SHA1 = 1 << 2;

        /**
         * SHA-2 224 (aka SHA-224) digest.
         */
        public static final int SHA224 = 1 << 3;

        /**
         * SHA-2 256 (aka SHA-256) digest.
         */
        public static final int SHA256 = 1 << 4;

        /**
         * SHA-2 384 (aka SHA-384) digest.
         */
        public static final int SHA384 = 1 << 5;

        /**
         * SHA-2 512 (aka SHA-512) digest.
         */
        public static final int SHA512 = 1 << 6;

        /**
         * @hide
         */
        public static String toString(@DigestEnum int digest) {
            switch (digest) {
                case NONE:
                    return "NONE";
                case MD5:
                    return "MD5";
                case SHA1:
                    return "SHA-1";
                case SHA224:
                    return "SHA-224";
                case SHA256:
                    return "SHA-256";
                case SHA384:
                    return "SHA-384";
                case SHA512:
                    return "SHA-512";
                default:
                    throw new IllegalArgumentException("Unknown digest: " + digest);
            }
        }

        /**
         * @hide
         */
        public static String allToString(@DigestEnum int digests) {
            StringBuilder result = new StringBuilder("[");
            boolean firstValue = true;
            for (@DigestEnum int digest : getSetFlags(digests)) {
                if (firstValue) {
                    firstValue = false;
                } else {
                    result.append(", ");
                }
                result.append(toString(digest));
            }
            result.append(']');
            return result.toString();
        }

        /**
         * @hide
         */
        public static int toKeymaster(@DigestEnum int digest) {
            switch (digest) {
                case NONE:
                    return KeymasterDefs.KM_DIGEST_NONE;
                case MD5:
                    return KeymasterDefs.KM_DIGEST_MD5;
                case SHA1:
                    return KeymasterDefs.KM_DIGEST_SHA1;
                case SHA224:
                    return KeymasterDefs.KM_DIGEST_SHA_2_224;
                case SHA256:
                    return KeymasterDefs.KM_DIGEST_SHA_2_256;
                case SHA384:
                    return KeymasterDefs.KM_DIGEST_SHA_2_384;
                case SHA512:
                    return KeymasterDefs.KM_DIGEST_SHA_2_512;
                default:
                    throw new IllegalArgumentException("Unknown digest: " + digest);
            }
        }

        /**
         * @hide
         */
        public static @DigestEnum int fromKeymaster(int digest) {
            switch (digest) {
                case KeymasterDefs.KM_DIGEST_NONE:
                    return NONE;
                case KeymasterDefs.KM_DIGEST_MD5:
                    return MD5;
                case KeymasterDefs.KM_DIGEST_SHA1:
                    return SHA1;
                case KeymasterDefs.KM_DIGEST_SHA_2_224:
                    return SHA224;
                case KeymasterDefs.KM_DIGEST_SHA_2_256:
                    return SHA256;
                case KeymasterDefs.KM_DIGEST_SHA_2_384:
                    return SHA384;
                case KeymasterDefs.KM_DIGEST_SHA_2_512:
                    return SHA512;
                default:
                    throw new IllegalArgumentException("Unknown digest: " + digest);
            }
        }

        /**
         * @hide
         */
        public static int[] allToKeymaster(@DigestEnum int digests) {
            int[] result = getSetFlags(digests);
            for (int i = 0; i < result.length; i++) {
                result[i] = toKeymaster(result[i]);
            }
            return result;
        }

        /**
         * @hide
         */
        public static @DigestEnum int allFromKeymaster(Collection<Integer> digests) {
            @DigestEnum int result = 0;
            for (int keymasterDigest : digests) {
                result |= fromKeymaster(keymasterDigest);
            }
            return result;
        }

        /**
         * @hide
         */
        public static @DigestEnum Integer fromJCASecretKeyAlgorithm(String algorithm) {
            String algorithmLower = algorithm.toLowerCase(Locale.US);
            if (algorithmLower.startsWith("hmac")) {
                String digestLower = algorithmLower.substring("hmac".length());
                if ("md5".equals(digestLower)) {
                    return MD5;
                } else if ("sha1".equals(digestLower)) {
                    return SHA1;
                } else if ("sha224".equals(digestLower)) {
                    return SHA224;
                } else if ("sha256".equals(digestLower)) {
                    return SHA256;
                } else if ("sha384".equals(digestLower)) {
                    return SHA384;
                } else if ("sha512".equals(digestLower)) {
                    return SHA512;
                } else {
                    throw new IllegalArgumentException("Unsupported digest: " + digestLower);
                }
            } else {
                return null;
            }
        }

        /**
         * @hide
         */
        public static String toJCASignatureAlgorithmDigest(@DigestEnum int digest) {
            switch (digest) {
                case NONE:
                    return "NONE";
                case MD5:
                    return "MD5";
                case SHA1:
                    return "SHA1";
                case SHA224:
                    return "SHA224";
                case SHA256:
                    return "SHA256";
                case SHA384:
                    return "SHA384";
                case SHA512:
                    return "SHA512";
                default:
                    throw new IllegalArgumentException("Unknown digest: " + digest);
            }
        }

        /**
         * @hide
         */
        public static Integer getOutputSizeBytes(@DigestEnum int digest) {
            switch (digest) {
                case NONE:
                    return null;
                case MD5:
                    return 128 / 8;
                case SHA1:
                    return 160 / 8;
                case SHA224:
                    return 224 / 8;
                case SHA256:
                    return 256 / 8;
                case SHA384:
                    return 384 / 8;
                case SHA512:
                    return 512 / 8;
                default:
                    throw new IllegalArgumentException("Unknown digest: " + digest);
            }
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {BlockMode.ECB, BlockMode.CBC, BlockMode.CTR, BlockMode.GCM})
    public @interface BlockModeEnum {}

    /**
     * Block modes that can be used when encrypting/decrypting using a key.
     */
    public static abstract class BlockMode {
        private BlockMode() {}

        /** Electronic Codebook (ECB) block mode. */
        public static final int ECB = 1 << 0;

        /** Cipher Block Chaining (CBC) block mode. */
        public static final int CBC = 1 << 1;

        /** Counter (CTR) block mode. */
        public static final int CTR = 1 << 2;

        /** Galois/Counter Mode (GCM) block mode. */
        public static final int GCM = 1 << 3;

        /**
         * Set of block modes compatible with IND-CPA if used correctly.
         *
         * @hide
         */
        public static final @BlockModeEnum int IND_CPA_COMPATIBLE_MODES =
                CBC | CTR | GCM;

        /**
         * @hide
         */
        public static int toKeymaster(@BlockModeEnum int mode) {
            switch (mode) {
                case ECB:
                    return KeymasterDefs.KM_MODE_ECB;
                case CBC:
                    return KeymasterDefs.KM_MODE_CBC;
                case CTR:
                    return KeymasterDefs.KM_MODE_CTR;
                case GCM:
                    return KeymasterDefs.KM_MODE_GCM;
                default:
                    throw new IllegalArgumentException("Unknown block mode: " + mode);
            }
        }

        /**
         * @hide
         */
        public static @BlockModeEnum int fromKeymaster(int mode) {
            switch (mode) {
                case KeymasterDefs.KM_MODE_ECB:
                    return ECB;
                case KeymasterDefs.KM_MODE_CBC:
                    return CBC;
                case KeymasterDefs.KM_MODE_CTR:
                    return CTR;
                case KeymasterDefs.KM_MODE_GCM:
                    return GCM;
                default:
                    throw new IllegalArgumentException("Unknown block mode: " + mode);
            }
        }

        /**
         * @hide
         */
        public static int[] allToKeymaster(@BlockModeEnum int modes) {
            int[] result = getSetFlags(modes);
            for (int i = 0; i < result.length; i++) {
                result[i] = toKeymaster(result[i]);
            }
            return result;
        }

        /**
         * @hide
         */
        public static @BlockModeEnum int allFromKeymaster(Collection<Integer> modes) {
            @BlockModeEnum int result = 0;
            for (int keymasterMode : modes) {
                result |= fromKeymaster(keymasterMode);
            }
            return result;
        }

        /**
         * @hide
         */
        public static String toString(@BlockModeEnum int mode) {
            switch (mode) {
                case ECB:
                    return "ECB";
                case CBC:
                    return "CBC";
                case CTR:
                    return "CTR";
                case GCM:
                    return "GCM";
                default:
                    throw new IllegalArgumentException("Unknown block mode: " + mode);
            }
        }

        /**
         * @hide
         */
        public static String allToString(@BlockModeEnum int modes) {
            StringBuilder result = new StringBuilder("[");
            boolean firstValue = true;
            for (@BlockModeEnum int mode : getSetFlags(modes)) {
                if (firstValue) {
                    firstValue = false;
                } else {
                    result.append(", ");
                }
                result.append(toString(mode));
            }
            result.append(']');
            return result.toString();
        }

        /**
         * @hide
         */
        public static @BlockModeEnum int fromJCAMode(String mode) {
            String modeLower = mode.toLowerCase(Locale.US);
            if ("ecb".equals(modeLower)) {
                return ECB;
            } else if ("cbc".equals(modeLower)) {
                return CBC;
            } else if ("ctr".equals(modeLower)) {
                return CTR;
            } else if ("gcm".equals(modeLower)) {
                return GCM;
            } else {
                throw new IllegalArgumentException("Unknown block mode: " + mode);
            }
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {UserAuthenticator.LOCK_SCREEN})
    public @interface UserAuthenticatorEnum {}

    /**
     * User authenticators which can be used to restrict/protect access to keys.
     */
    public static abstract class UserAuthenticator {
        private UserAuthenticator() {}

        /** Lock screen. */
        public static final int LOCK_SCREEN = 1 << 0;

        /**
         * @hide
         */
        public static int toKeymaster(@UserAuthenticatorEnum int userAuthenticator) {
            switch (userAuthenticator) {
                case LOCK_SCREEN:
                    return KeymasterDefs.HW_AUTH_PASSWORD;
                default:
                    throw new IllegalArgumentException(
                            "Unknown user authenticator: " + userAuthenticator);
            }
        }

        /**
         * @hide
         */
        public static @UserAuthenticatorEnum int fromKeymaster(int userAuthenticator) {
            switch (userAuthenticator) {
                case KeymasterDefs.HW_AUTH_PASSWORD:
                    return LOCK_SCREEN;
                default:
                    throw new IllegalArgumentException(
                            "Unknown user authenticator: " + userAuthenticator);
            }
        }

        /**
         * @hide
         */
        public static int allToKeymaster(@UserAuthenticatorEnum int userAuthenticators) {
            int result = 0;
            int userAuthenticator = 1;
            while (userAuthenticators != 0) {
                if ((userAuthenticators & 1) != 0) {
                    result |= toKeymaster(userAuthenticator);
                }
                userAuthenticators >>>= 1;
                userAuthenticator <<= 1;
            }
            return result;
        }

        /**
         * @hide
         */
        public static @UserAuthenticatorEnum int allFromKeymaster(int userAuthenticators) {
            @UserAuthenticatorEnum int result = 0;
            int userAuthenticator = 1;
            while (userAuthenticators != 0) {
                if ((userAuthenticators & 1) != 0) {
                    result |= fromKeymaster(userAuthenticator);
                }
                userAuthenticators >>>= 1;
                userAuthenticator <<= 1;
            }
            return result;
        }

        /**
         * @hide
         */
        public static String toString(@UserAuthenticatorEnum int userAuthenticator) {
            switch (userAuthenticator) {
                case LOCK_SCREEN:
                    return "LOCK_SCREEN";
                default:
                    throw new IllegalArgumentException(
                            "Unknown user authenticator: " + userAuthenticator);
            }
        }
    }

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static int[] getSetFlags(int flags) {
        if (flags == 0) {
            return EMPTY_INT_ARRAY;
        }
        int result[] = new int[getSetBitCount(flags)];
        int resultOffset = 0;
        int flag = 1;
        while (flags != 0) {
            if ((flags & 1) != 0) {
                result[resultOffset] = flag;
                resultOffset++;
            }
            flags >>>= 1;
            flag <<= 1;
        }
        return result;
    }

    private static int getSetBitCount(int value) {
        if (value == 0) {
            return 0;
        }
        int result = 0;
        while (value != 0) {
            if ((value & 1) != 0) {
                result++;
            }
            value >>>= 1;
        }
        return result;
    }
}
