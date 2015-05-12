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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.util.Collection;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;

/**
 * Properties of {@code AndroidKeyStore} keys.
 */
public abstract class KeyStoreKeyProperties {
    private KeyStoreKeyProperties() {}

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {Purpose.ENCRYPT, Purpose.DECRYPT, Purpose.SIGN, Purpose.VERIFY})
    public @interface PurposeEnum {}

    /**
     * Purposes of key.
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
        @NonNull
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
        public static @PurposeEnum int allFromKeymaster(@NonNull Collection<Integer> purposes) {
            @PurposeEnum int result = 0;
            for (int keymasterPurpose : purposes) {
                result |= fromKeymaster(keymasterPurpose);
            }
            return result;
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        Algorithm.RSA,
        Algorithm.EC,
        Algorithm.AES,
        Algorithm.HMAC_SHA1,
        Algorithm.HMAC_SHA224,
        Algorithm.HMAC_SHA256,
        Algorithm.HMAC_SHA384,
        Algorithm.HMAC_SHA512,
        })
    public @interface AlgorithmEnum {}

    /**
     * Key algorithms.
     *
     * <p>These are standard names which can be used to obtain instances of {@link KeyGenerator},
     * {@link KeyPairGenerator}, {@link Cipher} (as part of the transformation string), {@link Mac},
     * {@link KeyFactory}, {@link SecretKeyFactory}. These are also the names used by
     * {@link Key#getAlgorithm()}.
     */
    public static abstract class Algorithm {
        private Algorithm() {}

        /** Rivest Shamir Adleman (RSA) key. */
        public static final String RSA = "RSA";

        /** Elliptic Curve (EC) key. */
        public static final String EC = "EC";

        /** Advanced Encryption Standard (AES) key. */
        public static final String AES = "AES";

        /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-1 as the hash. */
        public static final String HMAC_SHA1 = "HmacSHA1";

        /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-224 as the hash. */
        public static final String HMAC_SHA224 = "HmacSHA224";

        /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-256 as the hash. */
        public static final String HMAC_SHA256 = "HmacSHA256";

        /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-384 as the hash. */
        public static final String HMAC_SHA384 = "HmacSHA384";

        /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-512 as the hash. */
        public static final String HMAC_SHA512 = "HmacSHA512";

        /**
         * @hide
         */
        static int toKeymasterSecretKeyAlgorithm(@NonNull @AlgorithmEnum String algorithm) {
            if (AES.equalsIgnoreCase(algorithm)) {
                return KeymasterDefs.KM_ALGORITHM_AES;
            } else if (algorithm.toUpperCase(Locale.US).startsWith("HMAC")) {
                return KeymasterDefs.KM_ALGORITHM_HMAC;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported secret key algorithm: " + algorithm);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @AlgorithmEnum String fromKeymasterSecretKeyAlgorithm(
                int keymasterAlgorithm, int keymasterDigest) {
            switch (keymasterAlgorithm) {
                case KeymasterDefs.KM_ALGORITHM_AES:
                    if (keymasterDigest != -1) {
                        throw new IllegalArgumentException("Digest not supported for AES key: "
                                + Digest.fromKeymaster(keymasterDigest));
                    }
                    return AES;
                case KeymasterDefs.KM_ALGORITHM_HMAC:
                    switch (keymasterDigest) {
                        case KeymasterDefs.KM_DIGEST_SHA1:
                            return HMAC_SHA1;
                        case KeymasterDefs.KM_DIGEST_SHA_2_224:
                            return HMAC_SHA224;
                        case KeymasterDefs.KM_DIGEST_SHA_2_256:
                            return HMAC_SHA256;
                        case KeymasterDefs.KM_DIGEST_SHA_2_384:
                            return HMAC_SHA384;
                        case KeymasterDefs.KM_DIGEST_SHA_2_512:
                            return HMAC_SHA512;
                        default:
                            throw new IllegalArgumentException("Unsupported HMAC digest: "
                                    + Digest.fromKeymaster(keymasterDigest));
                    }
                default:
                    throw new IllegalArgumentException(
                            "Unsupported algorithm: " + keymasterAlgorithm);
            }
        }

        /**
         * @hide
         *
         * @return keymaster digest or {@code -1} if the algorithm does not involve a digest.
         */
        static int toKeymasterDigest(@NonNull @AlgorithmEnum String algorithm) {
            String algorithmUpper = algorithm.toUpperCase(Locale.US);
            if (algorithmUpper.startsWith("HMAC")) {
                String digestUpper = algorithmUpper.substring("HMAC".length());
                switch (digestUpper) {
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
                        throw new IllegalArgumentException(
                                "Unsupported HMAC digest: " + digestUpper);
                }
            } else {
                return -1;
            }
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        BlockMode.ECB,
        BlockMode.CBC,
        BlockMode.CTR,
        BlockMode.GCM,
        })
    public @interface BlockModeEnum {}

    /**
     * Block modes that can be used when encrypting/decrypting using a key.
     */
    public static abstract class BlockMode {
        private BlockMode() {}

        /** Electronic Codebook (ECB) block mode. */
        public static final String ECB = "ECB";

        /** Cipher Block Chaining (CBC) block mode. */
        public static final String CBC = "CBC";

        /** Counter (CTR) block mode. */
        public static final String CTR = "CTR";

        /** Galois/Counter Mode (GCM) block mode. */
        public static final String GCM = "GCM";

        /**
         * @hide
         */
        static int toKeymaster(@NonNull @BlockModeEnum String blockMode) {
            if (ECB.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_ECB;
            } else if (CBC.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_CBC;
            } else if (CTR.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_CTR;
            } else if (GCM.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_GCM;
            } else {
                throw new IllegalArgumentException("Unsupported block mode: " + blockMode);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @BlockModeEnum String fromKeymaster(int blockMode) {
            switch (blockMode) {
                case KeymasterDefs.KM_MODE_ECB:
                    return ECB;
                case KeymasterDefs.KM_MODE_CBC:
                    return CBC;
                case KeymasterDefs.KM_MODE_CTR:
                    return CTR;
                case KeymasterDefs.KM_MODE_GCM:
                    return GCM;
                default:
                    throw new IllegalArgumentException("Unsupported block mode: " + blockMode);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @BlockModeEnum String[] allFromKeymaster(@NonNull Collection<Integer> blockModes) {
            if ((blockModes == null) || (blockModes.isEmpty())) {
                return EmptyArray.STRING;
            }
            @BlockModeEnum String[] result = new String[blockModes.size()];
            int offset = 0;
            for (int blockMode : blockModes) {
                result[offset] = fromKeymaster(blockMode);
                offset++;
            }
            return result;
        }

        /**
         * @hide
         */
        static int[] allToKeymaster(@Nullable @BlockModeEnum String[] blockModes) {
            if ((blockModes == null) || (blockModes.length == 0)) {
                return EmptyArray.INT;
            }
            int[] result = new int[blockModes.length];
            for (int i = 0; i < blockModes.length; i++) {
                result[i] = toKeymaster(blockModes[i]);
            }
            return result;
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        EncryptionPadding.NONE,
        EncryptionPadding.PKCS7,
        EncryptionPadding.RSA_PKCS1,
        EncryptionPadding.RSA_OAEP,
        })
    public @interface EncryptionPaddingEnum {}

    /**
     * Padding schemes for encryption/decryption.
     */
    public static abstract class EncryptionPadding {
        private EncryptionPadding() {}

        /**
         * No padding.
         */
        public static final String NONE = "NoPadding";

        /**
         * PKCS#7 padding.
         */
        public static final String PKCS7 = "PKCS7Padding";

        /**
         * RSA PKCS#1 v1.5 padding for encryption/decryption.
         */
        public static final String RSA_PKCS1 = "PKCS1Padding";

        /**
         * RSA Optimal Asymmetric Encryption Padding (OAEP).
         */
        public static final String RSA_OAEP = "OAEPPadding";

        /**
         * @hide
         */
        static int toKeymaster(@NonNull @EncryptionPaddingEnum String padding) {
            if (NONE.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_NONE;
            } else if (PKCS7.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_PKCS7;
            } else if (RSA_PKCS1.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT;
            } else if (RSA_OAEP.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_RSA_OAEP;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported encryption padding scheme: " + padding);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @EncryptionPaddingEnum String fromKeymaster(int padding) {
            switch (padding) {
                case KeymasterDefs.KM_PAD_NONE:
                    return NONE;
                case KeymasterDefs.KM_PAD_PKCS7:
                    return PKCS7;
                case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT:
                    return RSA_PKCS1;
                case KeymasterDefs.KM_PAD_RSA_OAEP:
                    return RSA_OAEP;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported encryption padding: " + padding);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static int[] allToKeymaster(@Nullable @EncryptionPaddingEnum String[] paddings) {
            if ((paddings == null) || (paddings.length == 0)) {
                return EmptyArray.INT;
            }
            int[] result = new int[paddings.length];
            for (int i = 0; i < paddings.length; i++) {
                result[i] = toKeymaster(paddings[i]);
            }
            return result;
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        SignaturePadding.RSA_PKCS1,
        SignaturePadding.RSA_PSS,
        })
    public @interface SignaturePaddingEnum {}

    /**
     * Padding schemes for signing/verification.
     */
    public static abstract class SignaturePadding {
        private SignaturePadding() {}

        /**
         * RSA PKCS#1 v1.5 padding for signatures.
         */
        public static final String RSA_PKCS1 = "PKCS1";

        /**
         * RSA PKCS#1 v2.1 Probabilistic Signature Scheme (PSS) padding.
         */
        public static final String RSA_PSS = "PSS";

        /**
         * @hide
         */
        static int toKeymaster(@NonNull @SignaturePaddingEnum String padding) {
            switch (padding.toUpperCase(Locale.US)) {
                case RSA_PKCS1:
                    return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN;
                case RSA_PSS:
                    return KeymasterDefs.KM_PAD_RSA_PSS;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported signature padding scheme: " + padding);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @SignaturePaddingEnum String fromKeymaster(int padding) {
            switch (padding) {
                case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN:
                    return RSA_PKCS1;
                case KeymasterDefs.KM_PAD_RSA_PSS:
                    return RSA_PSS;
                default:
                    throw new IllegalArgumentException("Unsupported signature padding: " + padding);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static int[] allToKeymaster(@Nullable @SignaturePaddingEnum String[] paddings) {
            if ((paddings == null) || (paddings.length == 0)) {
                return EmptyArray.INT;
            }
            int[] result = new int[paddings.length];
            for (int i = 0; i < paddings.length; i++) {
                result[i] = toKeymaster(paddings[i]);
            }
            return result;
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
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
        public static final String NONE = "NONE";

        /**
         * MD5 digest.
         */
        public static final String MD5 = "MD5";

        /**
         * SHA-1 digest.
         */
        public static final String SHA1 = "SHA-1";

        /**
         * SHA-2 224 (aka SHA-224) digest.
         */
        public static final String SHA224 = "SHA-224";

        /**
         * SHA-2 256 (aka SHA-256) digest.
         */
        public static final String SHA256 = "SHA-256";

        /**
         * SHA-2 384 (aka SHA-384) digest.
         */
        public static final String SHA384 = "SHA-384";

        /**
         * SHA-2 512 (aka SHA-512) digest.
         */
        public static final String SHA512 = "SHA-512";

        /**
         * @hide
         */
        static int toKeymaster(@NonNull @DigestEnum String digest) {
            switch (digest.toUpperCase(Locale.US)) {
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
                case NONE:
                    return KeymasterDefs.KM_DIGEST_NONE;
                case MD5:
                    return KeymasterDefs.KM_DIGEST_MD5;
                default:
                    throw new IllegalArgumentException("Unsupported digest algorithm: " + digest);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @DigestEnum String fromKeymaster(int digest) {
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
                    throw new IllegalArgumentException("Unsupported digest algorithm: " + digest);
            }
        }

        /**
         * @hide
         */
        @NonNull
        static @DigestEnum String[] allFromKeymaster(@NonNull Collection<Integer> digests) {
            if (digests.isEmpty()) {
                return EmptyArray.STRING;
            }
            String[] result = new String[digests.size()];
            int offset = 0;
            for (int digest : digests) {
                result[offset] = fromKeymaster(digest);
                offset++;
            }
            return result;
        }

        /**
         * @hide
         */
        @NonNull
        static int[] allToKeymaster(@Nullable @DigestEnum String[] digests) {
            if ((digests == null) || (digests.length == 0)) {
                return EmptyArray.INT;
            }
            int[] result = new int[digests.length];
            int offset = 0;
            for (@DigestEnum String digest : digests) {
                result[offset] = toKeymaster(digest);
                offset++;
            }
            return result;
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Origin.GENERATED, Origin.IMPORTED, Origin.UNKNOWN})
    public @interface OriginEnum {}

    /**
     * Origin of the key.
     */
    public static abstract class Origin {
        private Origin() {}

        /** Key was generated inside AndroidKeyStore. */
        public static final int GENERATED = 1 << 0;

        /** Key was imported into AndroidKeyStore. */
        public static final int IMPORTED = 1 << 1;

        /**
         * Origin of the key is unknown. This can occur only for keys backed by an old TEE-backed
         * implementation which does not record origin information.
         */
        public static final int UNKNOWN = 1 << 2;

        /**
         * @hide
         */
        public static @OriginEnum int fromKeymaster(int origin) {
            switch (origin) {
                case KeymasterDefs.KM_ORIGIN_GENERATED:
                    return GENERATED;
                case KeymasterDefs.KM_ORIGIN_IMPORTED:
                    return IMPORTED;
                case KeymasterDefs.KM_ORIGIN_UNKNOWN:
                    return UNKNOWN;
                default:
                    throw new IllegalArgumentException("Unknown origin: " + origin);
            }
        }
    }

    private static int[] getSetFlags(int flags) {
        if (flags == 0) {
            return EmptyArray.INT;
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
