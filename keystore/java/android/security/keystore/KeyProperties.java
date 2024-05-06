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

package android.security.keystore;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.os.Process;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.Collection;
import java.util.Locale;

/**
 * Properties of <a href="{@docRoot}training/articles/keystore.html">Android Keystore</a> keys.
 */
public abstract class KeyProperties {
    private KeyProperties() {}

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "AUTH_" }, value = {
            AUTH_BIOMETRIC_STRONG,
            AUTH_DEVICE_CREDENTIAL,
    })
    public @interface AuthEnum {}

    /**
     * The non-biometric credential used to secure the device (i.e., PIN, pattern, or password)
     */
    public static final int AUTH_DEVICE_CREDENTIAL = 1 << 0;

    /**
     * Any biometric (e.g. fingerprint, iris, or face) on the device that meets or exceeds the
     * requirements for <strong>Strong</strong>, as defined by the Android CDD.
     */
    public static final int AUTH_BIOMETRIC_STRONG = 1 << 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "PURPOSE_" }, value = {
            PURPOSE_ENCRYPT,
            PURPOSE_DECRYPT,
            PURPOSE_SIGN,
            PURPOSE_VERIFY,
            PURPOSE_WRAP_KEY,
            PURPOSE_AGREE_KEY,
            PURPOSE_ATTEST_KEY,
    })
    public @interface PurposeEnum {}

    /**
     * Purpose of key: encryption.
     */
    public static final int PURPOSE_ENCRYPT = 1 << 0;

    /**
     * Purpose of key: decryption.
     */
    public static final int PURPOSE_DECRYPT = 1 << 1;

    /**
     * Purpose of key: signing or generating a Message Authentication Code (MAC).
     */
    public static final int PURPOSE_SIGN = 1 << 2;

    /**
     * Purpose of key: signature or Message Authentication Code (MAC) verification.
     */
    public static final int PURPOSE_VERIFY = 1 << 3;

    /**
     * Purpose of key: wrapping and unwrapping wrapped keys for secure import.
     */
    public static final int PURPOSE_WRAP_KEY = 1 << 5;

    /**
     * Purpose of key: creating a shared ECDH secret through key agreement.
     *
     * <p>A key having this purpose can be combined with the elliptic curve public key of another
     * party to establish a shared secret over an insecure channel. It should be used  as a
     * parameter to {@link javax.crypto.KeyAgreement#init(java.security.Key)} (a complete example is
     * available <a
     * href="{@docRoot}reference/android/security/keystore/KeyGenParameterSpec#example:ecdh"
     * >here</a>).
     * See <a href="https://en.wikipedia.org/wiki/Elliptic-curve_Diffie%E2%80%93Hellman">this
     * article</a> for a more detailed explanation.
     */
    public static final int PURPOSE_AGREE_KEY = 1 << 6;

    /**
     * Purpose of key: Signing attestaions. This purpose is incompatible with all others, meaning
     * that when generating a key with PURPOSE_ATTEST_KEY, no other purposes may be specified. In
     * addition, PURPOSE_ATTEST_KEY may not be specified for imported keys.
     */
    public static final int PURPOSE_ATTEST_KEY = 1 << 7;

    /**
     * @hide
     */
    public static abstract class Purpose {
        private Purpose() {}

        public static int toKeymaster(@PurposeEnum int purpose) {
            switch (purpose) {
                case PURPOSE_ENCRYPT:
                    return KeymasterDefs.KM_PURPOSE_ENCRYPT;
                case PURPOSE_DECRYPT:
                    return KeymasterDefs.KM_PURPOSE_DECRYPT;
                case PURPOSE_SIGN:
                    return KeymasterDefs.KM_PURPOSE_SIGN;
                case PURPOSE_VERIFY:
                    return KeymasterDefs.KM_PURPOSE_VERIFY;
                case PURPOSE_WRAP_KEY:
                    return KeymasterDefs.KM_PURPOSE_WRAP;
                case PURPOSE_AGREE_KEY:
                    return KeymasterDefs.KM_PURPOSE_AGREE_KEY;
                case PURPOSE_ATTEST_KEY:
                    return KeymasterDefs.KM_PURPOSE_ATTEST_KEY;
                default:
                    throw new IllegalArgumentException("Unknown purpose: " + purpose);
            }
        }

        public static @PurposeEnum int fromKeymaster(int purpose) {
            switch (purpose) {
                case KeymasterDefs.KM_PURPOSE_ENCRYPT:
                    return PURPOSE_ENCRYPT;
                case KeymasterDefs.KM_PURPOSE_DECRYPT:
                    return PURPOSE_DECRYPT;
                case KeymasterDefs.KM_PURPOSE_SIGN:
                    return PURPOSE_SIGN;
                case KeymasterDefs.KM_PURPOSE_VERIFY:
                    return PURPOSE_VERIFY;
                case KeymasterDefs.KM_PURPOSE_WRAP:
                    return PURPOSE_WRAP_KEY;
                case KeymasterDefs.KM_PURPOSE_AGREE_KEY:
                    return PURPOSE_AGREE_KEY;
                case KeymasterDefs.KM_PURPOSE_ATTEST_KEY:
                    return PURPOSE_ATTEST_KEY;
                default:
                    throw new IllegalArgumentException("Unknown purpose: " + purpose);
            }
        }

        @NonNull
        public static int[] allToKeymaster(@PurposeEnum int purposes) {
            int[] result = getSetFlags(purposes);
            for (int i = 0; i < result.length; i++) {
                result[i] = toKeymaster(result[i]);
            }
            return result;
        }

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
    @StringDef(prefix = { "KEY_" }, value = {
        KEY_ALGORITHM_RSA,
        KEY_ALGORITHM_EC,
        KEY_ALGORITHM_XDH,
        KEY_ALGORITHM_AES,
        KEY_ALGORITHM_HMAC_SHA1,
        KEY_ALGORITHM_HMAC_SHA224,
        KEY_ALGORITHM_HMAC_SHA256,
        KEY_ALGORITHM_HMAC_SHA384,
        KEY_ALGORITHM_HMAC_SHA512,
        })
    public @interface KeyAlgorithmEnum {}

    /** Rivest Shamir Adleman (RSA) key. */
    public static final String KEY_ALGORITHM_RSA = "RSA";

    /** Elliptic Curve (EC) Cryptography key. */
    public static final String KEY_ALGORITHM_EC = "EC";

    /** Curve 25519 based Agreement key.
     * @hide
     */
    public static final String KEY_ALGORITHM_XDH = "XDH";

    /** Advanced Encryption Standard (AES) key. */
    public static final String KEY_ALGORITHM_AES = "AES";

    /**
     * Triple Data Encryption Algorithm (3DES) key.
     *
     * @deprecated Included for interoperability with legacy systems. Prefer {@link
     * KeyProperties#KEY_ALGORITHM_AES} for new development.
     */
    @Deprecated
    public static final String KEY_ALGORITHM_3DES = "DESede";

    /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-1 as the hash. */
    public static final String KEY_ALGORITHM_HMAC_SHA1 = "HmacSHA1";

    /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-224 as the hash. */
    public static final String KEY_ALGORITHM_HMAC_SHA224 = "HmacSHA224";

    /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-256 as the hash. */
    public static final String KEY_ALGORITHM_HMAC_SHA256 = "HmacSHA256";

    /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-384 as the hash. */
    public static final String KEY_ALGORITHM_HMAC_SHA384 = "HmacSHA384";

    /** Keyed-Hash Message Authentication Code (HMAC) key using SHA-512 as the hash. */
    public static final String KEY_ALGORITHM_HMAC_SHA512 = "HmacSHA512";

    /**
     * @hide
     */
    public static abstract class KeyAlgorithm {
        private KeyAlgorithm() {}

        public static int toKeymasterAsymmetricKeyAlgorithm(
                @NonNull @KeyAlgorithmEnum String algorithm) {
            if (KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm)
                    || KEY_ALGORITHM_XDH.equalsIgnoreCase(algorithm)) {
                return KeymasterDefs.KM_ALGORITHM_EC;
            } else if (KEY_ALGORITHM_RSA.equalsIgnoreCase(algorithm)) {
                return KeymasterDefs.KM_ALGORITHM_RSA;
            } else {
                throw new IllegalArgumentException("Unsupported key algorithm: " + algorithm);
            }
        }

        @NonNull
        public static @KeyAlgorithmEnum String fromKeymasterAsymmetricKeyAlgorithm(
                int keymasterAlgorithm) {
            switch (keymasterAlgorithm) {
                case KeymasterDefs.KM_ALGORITHM_EC:
                    return KEY_ALGORITHM_EC;
                case KeymasterDefs.KM_ALGORITHM_RSA:
                    return KEY_ALGORITHM_RSA;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported key algorithm: " + keymasterAlgorithm);
            }
        }

        public static int toKeymasterSecretKeyAlgorithm(
                @NonNull @KeyAlgorithmEnum String algorithm) {
            if (KEY_ALGORITHM_AES.equalsIgnoreCase(algorithm)) {
                return KeymasterDefs.KM_ALGORITHM_AES;
            } else if (KEY_ALGORITHM_3DES.equalsIgnoreCase(algorithm)) {
                return KeymasterDefs.KM_ALGORITHM_3DES;
            } else if (algorithm.toUpperCase(Locale.US).startsWith("HMAC")) {
                return KeymasterDefs.KM_ALGORITHM_HMAC;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported secret key algorithm: " + algorithm);
            }
        }

        @NonNull
        public static @KeyAlgorithmEnum String fromKeymasterSecretKeyAlgorithm(
                int keymasterAlgorithm, int keymasterDigest) {
            switch (keymasterAlgorithm) {
                case KeymasterDefs.KM_ALGORITHM_AES:
                    return KEY_ALGORITHM_AES;
                case KeymasterDefs.KM_ALGORITHM_3DES:
                    return KEY_ALGORITHM_3DES;
                case KeymasterDefs.KM_ALGORITHM_HMAC:
                    switch (keymasterDigest) {
                        case KeymasterDefs.KM_DIGEST_SHA1:
                            return KEY_ALGORITHM_HMAC_SHA1;
                        case KeymasterDefs.KM_DIGEST_SHA_2_224:
                            return KEY_ALGORITHM_HMAC_SHA224;
                        case KeymasterDefs.KM_DIGEST_SHA_2_256:
                            return KEY_ALGORITHM_HMAC_SHA256;
                        case KeymasterDefs.KM_DIGEST_SHA_2_384:
                            return KEY_ALGORITHM_HMAC_SHA384;
                        case KeymasterDefs.KM_DIGEST_SHA_2_512:
                            return KEY_ALGORITHM_HMAC_SHA512;
                        default:
                            throw new IllegalArgumentException("Unsupported HMAC digest: "
                                    + Digest.fromKeymaster(keymasterDigest));
                    }
                default:
                    throw new IllegalArgumentException(
                            "Unsupported key algorithm: " + keymasterAlgorithm);
            }
        }

        /**
         * @hide
         *
         * @return keymaster digest or {@code -1} if the algorithm does not involve a digest.
         */
        public static int toKeymasterDigest(@NonNull @KeyAlgorithmEnum String algorithm) {
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
    @StringDef(prefix = { "BLOCK_MODE_" }, value = {
        BLOCK_MODE_ECB,
        BLOCK_MODE_CBC,
        BLOCK_MODE_CTR,
        BLOCK_MODE_GCM,
        })
    public @interface BlockModeEnum {}

    /** Electronic Codebook (ECB) block mode. */
    public static final String BLOCK_MODE_ECB = "ECB";

    /** Cipher Block Chaining (CBC) block mode. */
    public static final String BLOCK_MODE_CBC = "CBC";

    /** Counter (CTR) block mode. */
    public static final String BLOCK_MODE_CTR = "CTR";

    /** Galois/Counter Mode (GCM) block mode. */
    public static final String BLOCK_MODE_GCM = "GCM";

    /**
     * @hide
     */
    public static abstract class BlockMode {
        private BlockMode() {}

        public static int toKeymaster(@NonNull @BlockModeEnum String blockMode) {
            if (BLOCK_MODE_ECB.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_ECB;
            } else if (BLOCK_MODE_CBC.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_CBC;
            } else if (BLOCK_MODE_CTR.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_CTR;
            } else if (BLOCK_MODE_GCM.equalsIgnoreCase(blockMode)) {
                return KeymasterDefs.KM_MODE_GCM;
            } else {
                throw new IllegalArgumentException("Unsupported block mode: " + blockMode);
            }
        }

        @NonNull
        public static @BlockModeEnum String fromKeymaster(int blockMode) {
            switch (blockMode) {
                case KeymasterDefs.KM_MODE_ECB:
                    return BLOCK_MODE_ECB;
                case KeymasterDefs.KM_MODE_CBC:
                    return BLOCK_MODE_CBC;
                case KeymasterDefs.KM_MODE_CTR:
                    return BLOCK_MODE_CTR;
                case KeymasterDefs.KM_MODE_GCM:
                    return BLOCK_MODE_GCM;
                default:
                    throw new IllegalArgumentException("Unsupported block mode: " + blockMode);
            }
        }

        @NonNull
        public static @BlockModeEnum String[] allFromKeymaster(
                @NonNull Collection<Integer> blockModes) {
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

        public static int[] allToKeymaster(@Nullable @BlockModeEnum String[] blockModes) {
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
    @StringDef(prefix = { "ENCRYPTION_PADDING_" }, value = {
        ENCRYPTION_PADDING_NONE,
        ENCRYPTION_PADDING_PKCS7,
        ENCRYPTION_PADDING_RSA_PKCS1,
        ENCRYPTION_PADDING_RSA_OAEP,
        })
    public @interface EncryptionPaddingEnum {}

    /**
     * No encryption padding.
     */
    public static final String ENCRYPTION_PADDING_NONE = "NoPadding";

    /**
     * PKCS#7 encryption padding scheme.
     */
    public static final String ENCRYPTION_PADDING_PKCS7 = "PKCS7Padding";

    /**
     * RSA PKCS#1 v1.5 padding scheme for encryption.
     */
    public static final String ENCRYPTION_PADDING_RSA_PKCS1 = "PKCS1Padding";

    /**
     * RSA Optimal Asymmetric Encryption Padding (OAEP) scheme.
     */
    public static final String ENCRYPTION_PADDING_RSA_OAEP = "OAEPPadding";

    /**
     * @hide
     */
    public static abstract class EncryptionPadding {
        private EncryptionPadding() {}

        public static int toKeymaster(@NonNull @EncryptionPaddingEnum String padding) {
            if (ENCRYPTION_PADDING_NONE.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_NONE;
            } else if (ENCRYPTION_PADDING_PKCS7.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_PKCS7;
            } else if (ENCRYPTION_PADDING_RSA_PKCS1.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT;
            } else if (ENCRYPTION_PADDING_RSA_OAEP.equalsIgnoreCase(padding)) {
                return KeymasterDefs.KM_PAD_RSA_OAEP;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported encryption padding scheme: " + padding);
            }
        }

        @NonNull
        public static @EncryptionPaddingEnum String fromKeymaster(int padding) {
            switch (padding) {
                case KeymasterDefs.KM_PAD_NONE:
                    return ENCRYPTION_PADDING_NONE;
                case KeymasterDefs.KM_PAD_PKCS7:
                    return ENCRYPTION_PADDING_PKCS7;
                case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT:
                    return ENCRYPTION_PADDING_RSA_PKCS1;
                case KeymasterDefs.KM_PAD_RSA_OAEP:
                    return ENCRYPTION_PADDING_RSA_OAEP;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported encryption padding: " + padding);
            }
        }

        @NonNull
        public static int[] allToKeymaster(@Nullable @EncryptionPaddingEnum String[] paddings) {
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
    @StringDef(prefix = { "SIGNATURE_PADDING_" }, value = {
        SIGNATURE_PADDING_RSA_PKCS1,
        SIGNATURE_PADDING_RSA_PSS,
        })
    public @interface SignaturePaddingEnum {}

    /**
     * RSA PKCS#1 v1.5 padding for signatures.
     */
    public static final String SIGNATURE_PADDING_RSA_PKCS1 = "PKCS1";

    /**
     * RSA PKCS#1 v2.1 Probabilistic Signature Scheme (PSS) padding.
     */
    public static final String SIGNATURE_PADDING_RSA_PSS = "PSS";

    /**
     * @hide
     */
    public abstract static class SignaturePadding {
        private SignaturePadding() {}

        /**
         * @hide
         */
        public static int toKeymaster(@NonNull @SignaturePaddingEnum String padding) {
            switch (padding.toUpperCase(Locale.US)) {
                case SIGNATURE_PADDING_RSA_PKCS1:
                    return KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN;
                case SIGNATURE_PADDING_RSA_PSS:
                    return KeymasterDefs.KM_PAD_RSA_PSS;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported signature padding scheme: " + padding);
            }
        }

        @NonNull
        public static @SignaturePaddingEnum String fromKeymaster(int padding) {
            switch (padding) {
                case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN:
                    return SIGNATURE_PADDING_RSA_PKCS1;
                case KeymasterDefs.KM_PAD_RSA_PSS:
                    return SIGNATURE_PADDING_RSA_PSS;
                default:
                    throw new IllegalArgumentException("Unsupported signature padding: " + padding);
            }
        }

        @NonNull
        public static int[] allToKeymaster(@Nullable @SignaturePaddingEnum String[] paddings) {
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
    @StringDef(prefix = { "DIGEST_" }, value = {
        DIGEST_NONE,
        DIGEST_MD5,
        DIGEST_SHA1,
        DIGEST_SHA224,
        DIGEST_SHA256,
        DIGEST_SHA384,
        DIGEST_SHA512,
        })
    public @interface DigestEnum {}

    /**
     * No digest: sign/authenticate the raw message.
     */
    public static final String DIGEST_NONE = "NONE";

    /**
     * MD5 digest.
     */
    public static final String DIGEST_MD5 = "MD5";

    /**
     * SHA-1 digest.
     */
    public static final String DIGEST_SHA1 = "SHA-1";

    /**
     * SHA-2 224 (aka SHA-224) digest.
     */
    public static final String DIGEST_SHA224 = "SHA-224";

    /**
     * SHA-2 256 (aka SHA-256) digest.
     */
    public static final String DIGEST_SHA256 = "SHA-256";

    /**
     * SHA-2 384 (aka SHA-384) digest.
     */
    public static final String DIGEST_SHA384 = "SHA-384";

    /**
     * SHA-2 512 (aka SHA-512) digest.
     */
    public static final String DIGEST_SHA512 = "SHA-512";

    /**
     * @hide
     */
    public static abstract class Digest {
        private Digest() {}

        public static int toKeymaster(@NonNull @DigestEnum String digest) {
            switch (digest.toUpperCase(Locale.US)) {
                case DIGEST_SHA1:
                    return KeymasterDefs.KM_DIGEST_SHA1;
                case DIGEST_SHA224:
                    return KeymasterDefs.KM_DIGEST_SHA_2_224;
                case DIGEST_SHA256:
                    return KeymasterDefs.KM_DIGEST_SHA_2_256;
                case DIGEST_SHA384:
                    return KeymasterDefs.KM_DIGEST_SHA_2_384;
                case DIGEST_SHA512:
                    return KeymasterDefs.KM_DIGEST_SHA_2_512;
                case DIGEST_NONE:
                    return KeymasterDefs.KM_DIGEST_NONE;
                case DIGEST_MD5:
                    return KeymasterDefs.KM_DIGEST_MD5;
                default:
                    throw new IllegalArgumentException("Unsupported digest algorithm: " + digest);
            }
        }

        @NonNull
        public static @DigestEnum String fromKeymaster(int digest) {
            switch (digest) {
                case KeymasterDefs.KM_DIGEST_NONE:
                    return DIGEST_NONE;
                case KeymasterDefs.KM_DIGEST_MD5:
                    return DIGEST_MD5;
                case KeymasterDefs.KM_DIGEST_SHA1:
                    return DIGEST_SHA1;
                case KeymasterDefs.KM_DIGEST_SHA_2_224:
                    return DIGEST_SHA224;
                case KeymasterDefs.KM_DIGEST_SHA_2_256:
                    return DIGEST_SHA256;
                case KeymasterDefs.KM_DIGEST_SHA_2_384:
                    return DIGEST_SHA384;
                case KeymasterDefs.KM_DIGEST_SHA_2_512:
                    return DIGEST_SHA512;
                default:
                    throw new IllegalArgumentException("Unsupported digest algorithm: " + digest);
            }
        }

        /**
         * @hide
         */
        @NonNull public static @DigestEnum
                AlgorithmParameterSpec fromKeymasterToMGF1ParameterSpec(int digest) {
            switch (digest) {
                default:
                case KeymasterDefs.KM_DIGEST_SHA1:
                    return MGF1ParameterSpec.SHA1;
                case KeymasterDefs.KM_DIGEST_SHA_2_224:
                    return MGF1ParameterSpec.SHA224;
                case KeymasterDefs.KM_DIGEST_SHA_2_256:
                    return MGF1ParameterSpec.SHA256;
                case KeymasterDefs.KM_DIGEST_SHA_2_384:
                    return MGF1ParameterSpec.SHA384;
                case KeymasterDefs.KM_DIGEST_SHA_2_512:
                    return MGF1ParameterSpec.SHA512;
            }
        }

        @NonNull
        public static @DigestEnum String fromKeymasterToSignatureAlgorithmDigest(int digest) {
            switch (digest) {
                case KeymasterDefs.KM_DIGEST_NONE:
                    return "NONE";
                case KeymasterDefs.KM_DIGEST_MD5:
                    return "MD5";
                case KeymasterDefs.KM_DIGEST_SHA1:
                    return "SHA1";
                case KeymasterDefs.KM_DIGEST_SHA_2_224:
                    return "SHA224";
                case KeymasterDefs.KM_DIGEST_SHA_2_256:
                    return "SHA256";
                case KeymasterDefs.KM_DIGEST_SHA_2_384:
                    return "SHA384";
                case KeymasterDefs.KM_DIGEST_SHA_2_512:
                    return "SHA512";
                default:
                    throw new IllegalArgumentException("Unsupported digest algorithm: " + digest);
            }
        }

        @NonNull
        public static @DigestEnum String[] allFromKeymaster(@NonNull Collection<Integer> digests) {
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

        @NonNull
        public static int[] allToKeymaster(@Nullable @DigestEnum String[] digests) {
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
    @IntDef(prefix = { "ORIGIN_" }, value = {
            ORIGIN_GENERATED,
            ORIGIN_IMPORTED,
            ORIGIN_UNKNOWN,
    })

    public @interface OriginEnum {}

    /** Key was generated inside AndroidKeyStore. */
    public static final int ORIGIN_GENERATED = 1 << 0;

    /** Key was imported into AndroidKeyStore. */
    public static final int ORIGIN_IMPORTED = 1 << 1;

    /**
     * Origin of the key is unknown. This can occur only for keys backed by an old TEE-backed
     * implementation which does not record origin information.
     */
    public static final int ORIGIN_UNKNOWN = 1 << 2;

    /**
     * Key was imported into the AndroidKeyStore in an encrypted wrapper. Unlike imported keys,
     * securely imported keys can be imported without appearing as plaintext in the device's host
     * memory.
     */
    public static final int ORIGIN_SECURELY_IMPORTED = 1 << 3;


    /**
     * @hide
     */
    public static abstract class Origin {
        private Origin() {}

        public static @OriginEnum int fromKeymaster(int origin) {
            switch (origin) {
                case KeymasterDefs.KM_ORIGIN_GENERATED:
                    return ORIGIN_GENERATED;
                case KeymasterDefs.KM_ORIGIN_IMPORTED:
                    return ORIGIN_IMPORTED;
                case KeymasterDefs.KM_ORIGIN_UNKNOWN:
                    return ORIGIN_UNKNOWN;
                case KeymasterDefs.KM_ORIGIN_SECURELY_IMPORTED:
                    return ORIGIN_SECURELY_IMPORTED;
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

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SECURITY_LEVEL_" }, value = {
            SECURITY_LEVEL_UNKNOWN,
            SECURITY_LEVEL_UNKNOWN_SECURE,
            SECURITY_LEVEL_SOFTWARE,
            SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            SECURITY_LEVEL_STRONGBOX,
    })
    public @interface SecurityLevelEnum {}

    /**
     * This security level indicates that no assumptions can be made about the security level of the
     * respective key.
     */
    public static final int SECURITY_LEVEL_UNKNOWN = -2;
    /**
     * This security level indicates that due to the target API level of the caller no exact
     * statement can be made about the security level of the key, however, the security level
     * can be considered is at least equivalent to {@link #SECURITY_LEVEL_TRUSTED_ENVIRONMENT}.
     */
    public static final int SECURITY_LEVEL_UNKNOWN_SECURE = -1;

    /** Indicates enforcement by system software. */
    public static final int SECURITY_LEVEL_SOFTWARE = 0;

    /** Indicates enforcement by a trusted execution environment. */
    public static final int SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1;

    /**
     * Indicates enforcement by environment meeting the Strongbox security profile,
     * such as a secure element.
     */
    public static final int SECURITY_LEVEL_STRONGBOX = 2;

    /**
     * @hide
     */
    public abstract static class SecurityLevel {
        private SecurityLevel() {}

        /**
         * @hide
         */
        public static int toKeymaster(int securityLevel) {
            switch (securityLevel) {
                case SECURITY_LEVEL_SOFTWARE:
                    return KeymasterDefs.KM_SECURITY_LEVEL_SOFTWARE;
                case SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                    return KeymasterDefs.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT;
                case SECURITY_LEVEL_STRONGBOX:
                    return KeymasterDefs.KM_SECURITY_LEVEL_STRONGBOX;
                default:
                    throw new IllegalArgumentException("Unsupported security level: "
                            + securityLevel);
            }
        }

        /**
         * @hide
         */
        @NonNull
        public static int fromKeymaster(int securityLevel) {
            switch (securityLevel) {
                case KeymasterDefs.KM_SECURITY_LEVEL_SOFTWARE:
                    return SECURITY_LEVEL_SOFTWARE;
                case KeymasterDefs.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT:
                    return SECURITY_LEVEL_TRUSTED_ENVIRONMENT;
                case KeymasterDefs.KM_SECURITY_LEVEL_STRONGBOX:
                    return SECURITY_LEVEL_STRONGBOX;
                default:
                    throw new IllegalArgumentException("Unsupported security level: "
                            + securityLevel);
            }
        }
    }

    /**
     * @hide
     */
    public abstract static class EcCurve {
        private EcCurve() {}

        /**
         * @hide
         */
        public static int toKeymasterCurve(ECParameterSpec spec) {
            int keySize = spec.getCurve().getField().getFieldSize();
            switch (keySize) {
                case 224:
                    return android.hardware.security.keymint.EcCurve.P_224;
                case 256:
                    return android.hardware.security.keymint.EcCurve.P_256;
                case 384:
                    return android.hardware.security.keymint.EcCurve.P_384;
                case 521:
                    return android.hardware.security.keymint.EcCurve.P_521;
                default:
                    return -1;
            }
        }

        /**
         * @hide
         */
        public static int fromKeymasterCurve(int ecCurve) {
            switch (ecCurve) {
                case android.hardware.security.keymint.EcCurve.P_224:
                    return 224;
                case android.hardware.security.keymint.EcCurve.P_256:
                case android.hardware.security.keymint.EcCurve.CURVE_25519:
                    return 256;
                case android.hardware.security.keymint.EcCurve.P_384:
                    return 384;
                case android.hardware.security.keymint.EcCurve.P_521:
                    return 521;
                default:
                    return -1;
            }
        }
    }

    /**
     * Namespaces provide system developers and vendors with a way to use keystore without
     * requiring an applications uid. Namespaces can be configured using SEPolicy.
     * See <a href="https://source.android.com/security/keystore#access-control">
     *     Keystore 2.0 access-control</a>
     * {@See KeyGenParameterSpec.Builder#setNamespace}
     * {@See android.security.keystore2.AndroidKeyStoreLoadStoreParameter}
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "NAMESPACE_" }, value = {
            NAMESPACE_APPLICATION,
            NAMESPACE_WIFI,
            NAMESPACE_LOCKSETTINGS,
    })
    public @interface Namespace {}

    /**
     * This value indicates the implicit keystore namespace of the calling application.
     * It is used by default. Only select system components can choose a different namespace
     * which it must be configured in SEPolicy.
     * @hide
     */
    @SystemApi
    public static final int NAMESPACE_APPLICATION = -1;

    /**
     * The namespace identifier for the WIFI Keystore namespace.
     * This must be kept in sync with system/sepolicy/private/keystore2_key_contexts
     * @hide
     */
    @SystemApi
    public static final int NAMESPACE_WIFI = 102;

    /**
     * The namespace identifier for the LOCKSETTINGS Keystore namespace.
     * This must be kept in sync with system/sepolicy/private/keystore2_key_contexts
     * @hide
     */
    public static final int NAMESPACE_LOCKSETTINGS = 103;

    /**
     * The legacy UID that corresponds to {@link #NAMESPACE_APPLICATION}.
     * In new code, prefer to work with Keystore namespaces directly.
     * @hide
     */
    public static final int UID_SELF = -1;

    /**
     * For legacy support, translate namespaces into known UIDs.
     * @hide
     */
    public static int namespaceToLegacyUid(@Namespace int namespace) {
        switch (namespace) {
            case NAMESPACE_APPLICATION:
                return UID_SELF;
            case NAMESPACE_WIFI:
                return Process.WIFI_UID;
            default:
                throw new IllegalArgumentException("No UID corresponding to namespace "
                        + namespace);
        }
    }

    /**
     * For legacy support, translate namespaces into known UIDs.
     * @hide
     */
    public static @Namespace int legacyUidToNamespace(int uid) {
        switch (uid) {
            case UID_SELF:
                return NAMESPACE_APPLICATION;
            case Process.WIFI_UID:
                return NAMESPACE_WIFI;
            default:
                throw new IllegalArgumentException("No namespace corresponding to uid "
                        + uid);
        }
    }

    /**
     * This value indicates that there is no restriction on the number of times the key can be used.
     */
    public static final int UNRESTRICTED_USAGE_COUNT = -1;
}
