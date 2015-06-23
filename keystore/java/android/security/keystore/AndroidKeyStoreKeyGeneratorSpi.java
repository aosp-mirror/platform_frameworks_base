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

import android.security.Credentials;
import android.security.KeyStore;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import libcore.util.EmptyArray;

import java.security.InvalidAlgorithmParameterException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

/**
 * {@link KeyGeneratorSpi} backed by Android KeyStore.
 *
 * @hide
 */
public abstract class AndroidKeyStoreKeyGeneratorSpi extends KeyGeneratorSpi {

    public static class AES extends AndroidKeyStoreKeyGeneratorSpi {
        public AES() {
            super(KeymasterDefs.KM_ALGORITHM_AES, 128);
        }

        @Override
        protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidAlgorithmParameterException {
            super.engineInit(params, random);
            if ((mKeySizeBits != 128) && (mKeySizeBits != 192) && (mKeySizeBits != 256)) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported key size: " + mKeySizeBits
                        + ". Supported: 128, 192, 256.");
            }
        }
    }

    protected static abstract class HmacBase extends AndroidKeyStoreKeyGeneratorSpi {
        protected HmacBase(int keymasterDigest) {
            super(KeymasterDefs.KM_ALGORITHM_HMAC,
                    keymasterDigest,
                    KeymasterUtils.getDigestOutputSizeBits(keymasterDigest));
        }
    }

    public static class HmacSHA1 extends HmacBase {
        public HmacSHA1() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
    }

    public static class HmacSHA224 extends HmacBase {
        public HmacSHA224() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
    }

    public static class HmacSHA256 extends HmacBase {
        public HmacSHA256() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
    }

    public static class HmacSHA384 extends HmacBase {
        public HmacSHA384() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
    }

    public static class HmacSHA512 extends HmacBase {
        public HmacSHA512() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
    }

    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final int mKeymasterAlgorithm;
    private final int mKeymasterDigest;
    private final int mDefaultKeySizeBits;

    private KeyGenParameterSpec mSpec;
    private SecureRandom mRng;

    protected int mKeySizeBits;
    private int[] mKeymasterPurposes;
    private int[] mKeymasterBlockModes;
    private int[] mKeymasterPaddings;
    private int[] mKeymasterDigests;

    protected AndroidKeyStoreKeyGeneratorSpi(
            int keymasterAlgorithm,
            int defaultKeySizeBits) {
        this(keymasterAlgorithm, -1, defaultKeySizeBits);
    }

    protected AndroidKeyStoreKeyGeneratorSpi(
            int keymasterAlgorithm,
            int keymasterDigest,
            int defaultKeySizeBits) {
        mKeymasterAlgorithm = keymasterAlgorithm;
        mKeymasterDigest = keymasterDigest;
        mDefaultKeySizeBits = defaultKeySizeBits;
        if (mDefaultKeySizeBits <= 0) {
            throw new IllegalArgumentException("Default key size must be positive");
        }

        if ((mKeymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC) && (mKeymasterDigest == -1)) {
            throw new IllegalArgumentException(
                    "Digest algorithm must be specified for HMAC key");
        }
    }

    @Override
    protected void engineInit(SecureRandom random) {
        throw new UnsupportedOperationException("Cannot initialize without a "
                + KeyGenParameterSpec.class.getName() + " parameter");
    }

    @Override
    protected void engineInit(int keySize, SecureRandom random) {
        throw new UnsupportedOperationException("Cannot initialize without a "
                + KeyGenParameterSpec.class.getName() + " parameter");
    }

    @Override
    protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            if ((params == null) || (!(params instanceof KeyGenParameterSpec))) {
                throw new InvalidAlgorithmParameterException("Cannot initialize without a "
                        + KeyGenParameterSpec.class.getName() + " parameter");
            }
            KeyGenParameterSpec spec = (KeyGenParameterSpec) params;
            if (spec.getKeystoreAlias() == null) {
                throw new InvalidAlgorithmParameterException("KeyStore entry alias not provided");
            }

            mRng = random;
            mSpec = spec;

            mKeySizeBits = (spec.getKeySize() != -1) ? spec.getKeySize() : mDefaultKeySizeBits;
            if (mKeySizeBits <= 0) {
                throw new InvalidAlgorithmParameterException(
                        "Key size must be positive: " + mKeySizeBits);
            } else if ((mKeySizeBits % 8) != 0) {
                throw new InvalidAlgorithmParameterException(
                        "Key size in must be a multiple of 8: " + mKeySizeBits);
            }

            try {
                mKeymasterPurposes = KeyProperties.Purpose.allToKeymaster(spec.getPurposes());
                mKeymasterPaddings = KeyProperties.EncryptionPadding.allToKeymaster(
                        spec.getEncryptionPaddings());
                if (spec.getSignaturePaddings().length > 0) {
                    throw new InvalidAlgorithmParameterException(
                            "Signature paddings not supported for symmetric key algorithms");
                }
                mKeymasterBlockModes = KeyProperties.BlockMode.allToKeymaster(spec.getBlockModes());
                if (((spec.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0)
                        && (spec.isRandomizedEncryptionRequired())) {
                    for (int keymasterBlockMode : mKeymasterBlockModes) {
                        if (!KeymasterUtils.isKeymasterBlockModeIndCpaCompatibleWithSymmetricCrypto(
                                keymasterBlockMode)) {
                            throw new InvalidAlgorithmParameterException(
                                    "Randomized encryption (IND-CPA) required but may be violated"
                                    + " by block mode: "
                                    + KeyProperties.BlockMode.fromKeymaster(keymasterBlockMode)
                                    + ". See " + KeyGenParameterSpec.class.getName()
                                    + " documentation.");
                        }
                    }
                }
                if (spec.isDigestsSpecified()) {
                    // Digest(s) explicitly specified in the spec
                    mKeymasterDigests = KeyProperties.Digest.allToKeymaster(spec.getDigests());
                    if (mKeymasterDigest != -1) {
                        // Key algorithm implies a digest -- ensure it's specified in the spec as
                        // first digest.
                        if (!com.android.internal.util.ArrayUtils.contains(
                                mKeymasterDigests, mKeymasterDigest)) {
                            throw new InvalidAlgorithmParameterException(
                                    "Digests specified in algorithm parameters ("
                                    + Arrays.asList(spec.getDigests()) + ") must include "
                                    + " the digest "
                                    + KeyProperties.Digest.fromKeymaster(mKeymasterDigest)
                                    + " implied by key algorithm");
                        }
                        if (mKeymasterDigests[0] != mKeymasterDigest) {
                            // The first digest is not the one implied by the key algorithm.
                            // Swap the implied digest with the first one.
                            for (int i = 0; i < mKeymasterDigests.length; i++) {
                                if (mKeymasterDigests[i] == mKeymasterDigest) {
                                    mKeymasterDigests[i] = mKeymasterDigests[0];
                                    mKeymasterDigests[0] = mKeymasterDigest;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    // No digest specified in the spec
                    if (mKeymasterDigest != -1) {
                        // Key algorithm implies a digest -- use that digest
                        mKeymasterDigests = new int[] {mKeymasterDigest};
                    } else {
                        mKeymasterDigests = EmptyArray.INT;
                    }
                }
                if (mKeymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC) {
                    if (mKeymasterDigests.length == 0) {
                        throw new InvalidAlgorithmParameterException(
                                "At least one digest algorithm must be specified");
                    }
                }
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new InvalidAlgorithmParameterException(e);
            }

            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void resetAll() {
        mSpec = null;
        mRng = null;
        mKeySizeBits = -1;
        mKeymasterPurposes = null;
        mKeymasterPaddings = null;
        mKeymasterBlockModes = null;
    }

    @Override
    protected SecretKey engineGenerateKey() {
        KeyGenParameterSpec spec = mSpec;
        if (spec == null) {
            throw new IllegalStateException("Not initialized");
        }

        KeymasterArguments args = new KeymasterArguments();
        args.addInt(KeymasterDefs.KM_TAG_KEY_SIZE, mKeySizeBits);
        args.addInt(KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm);
        args.addInts(KeymasterDefs.KM_TAG_PURPOSE, mKeymasterPurposes);
        args.addInts(KeymasterDefs.KM_TAG_BLOCK_MODE, mKeymasterBlockModes);
        args.addInts(KeymasterDefs.KM_TAG_PADDING, mKeymasterPaddings);
        args.addInts(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigests);
        KeymasterUtils.addUserAuthArgs(args,
                spec.isUserAuthenticationRequired(),
                spec.getUserAuthenticationValidityDurationSeconds());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, spec.getKeyValidityStart());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                spec.getKeyValidityForOriginationEnd());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                spec.getKeyValidityForConsumptionEnd());

        if (((spec.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0)
                && (!spec.isRandomizedEncryptionRequired())) {
            // Permit caller-provided IV when encrypting with this key
            args.addBoolean(KeymasterDefs.KM_TAG_CALLER_NONCE);
        }

        byte[] additionalEntropy =
                KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                        mRng, (mKeySizeBits + 7) / 8);
        int flags = 0;
        String keyAliasInKeystore = Credentials.USER_SECRET_KEY + spec.getKeystoreAlias();
        KeyCharacteristics resultingKeyCharacteristics = new KeyCharacteristics();
        boolean success = false;
        try {
            Credentials.deleteAllTypesForAlias(mKeyStore, spec.getKeystoreAlias());
            int errorCode = mKeyStore.generateKey(
                    keyAliasInKeystore,
                    args,
                    additionalEntropy,
                    flags,
                    resultingKeyCharacteristics);
            if (errorCode != KeyStore.NO_ERROR) {
                throw new ProviderException(
                        "Keystore operation failed", KeyStore.getKeyStoreException(errorCode));
            }
            @KeyProperties.KeyAlgorithmEnum String keyAlgorithmJCA;
            try {
                keyAlgorithmJCA = KeyProperties.KeyAlgorithm.fromKeymasterSecretKeyAlgorithm(
                        mKeymasterAlgorithm, mKeymasterDigest);
            } catch (IllegalArgumentException e) {
                throw new ProviderException("Failed to obtain JCA secret key algorithm name", e);
            }
            SecretKey result = new AndroidKeyStoreSecretKey(keyAliasInKeystore, keyAlgorithmJCA);
            success = true;
            return result;
        } finally {
            if (!success) {
                Credentials.deleteAllTypesForAlias(mKeyStore, spec.getKeystoreAlias());
            }
        }
    }
}
