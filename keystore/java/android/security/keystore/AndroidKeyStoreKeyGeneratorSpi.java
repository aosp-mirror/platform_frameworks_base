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
import android.security.GateKeeper;
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
                        "Key size must be a multiple of 8: " + mKeySizeBits);
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

                if (mKeymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC) {
                    if (mKeySizeBits < 64) {
                        throw new InvalidAlgorithmParameterException(
                            "HMAC key size must be at least 64 bits.");
                    }

                    // JCA HMAC key algorithm implies a digest (e.g., HmacSHA256 key algorithm
                    // implies SHA-256 digest). Because keymaster HMAC key is authorized only for
                    // one digest, we don't let algorithm parameter spec override the digest implied
                    // by the key. If the spec specifies digests at all, it must specify only one
                    // digest, the only implied by key algorithm.
                    mKeymasterDigests = new int[] {mKeymasterDigest};
                    if (spec.isDigestsSpecified()) {
                        // Digest(s) explicitly specified in the spec. Check that the list
                        // consists of exactly one digest, the one implied by key algorithm.
                        int[] keymasterDigestsFromSpec =
                                KeyProperties.Digest.allToKeymaster(spec.getDigests());
                        if ((keymasterDigestsFromSpec.length != 1)
                                || (keymasterDigestsFromSpec[0] != mKeymasterDigest)) {
                            throw new InvalidAlgorithmParameterException(
                                    "Unsupported digests specification: "
                                    + Arrays.asList(spec.getDigests()) + ". Only "
                                    + KeyProperties.Digest.fromKeymaster(mKeymasterDigest)
                                    + " supported for this HMAC key algorithm");
                        }
                    }
                } else {
                    // Key algorithm does not imply a digest.
                    if (spec.isDigestsSpecified()) {
                        mKeymasterDigests = KeyProperties.Digest.allToKeymaster(spec.getDigests());
                    } else {
                        mKeymasterDigests = EmptyArray.INT;
                    }
                }

                // Check that user authentication related parameters are acceptable. This method
                // will throw an IllegalStateException if there are issues (e.g., secure lock screen
                // not set up).
                KeymasterUtils.addUserAuthArgs(new KeymasterArguments(),
                        spec.isUserAuthenticationRequired(),
                        spec.getUserAuthenticationValidityDurationSeconds(),
                        spec.isUserAuthenticationValidWhileOnBody(),
                        spec.isInvalidatedByBiometricEnrollment(),
                        GateKeeper.INVALID_SECURE_USER_ID /* boundToSpecificSecureUserId */);
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
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, mKeySizeBits);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm);
        args.addEnums(KeymasterDefs.KM_TAG_PURPOSE, mKeymasterPurposes);
        args.addEnums(KeymasterDefs.KM_TAG_BLOCK_MODE, mKeymasterBlockModes);
        args.addEnums(KeymasterDefs.KM_TAG_PADDING, mKeymasterPaddings);
        args.addEnums(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigests);
        KeymasterUtils.addUserAuthArgs(args,
                spec.isUserAuthenticationRequired(),
                spec.getUserAuthenticationValidityDurationSeconds(),
                spec.isUserAuthenticationValidWhileOnBody(),
                spec.isInvalidatedByBiometricEnrollment(),
                GateKeeper.INVALID_SECURE_USER_ID /* boundToSpecificSecureUserId */);
        KeymasterUtils.addMinMacLengthAuthorizationIfNecessary(
                args,
                mKeymasterAlgorithm,
                mKeymasterBlockModes,
                mKeymasterDigests);
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
            Credentials.deleteAllTypesForAlias(mKeyStore, spec.getKeystoreAlias(), spec.getUid());
            int errorCode = mKeyStore.generateKey(
                    keyAliasInKeystore,
                    args,
                    additionalEntropy,
                    spec.getUid(),
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
            SecretKey result = new AndroidKeyStoreSecretKey(
                    keyAliasInKeystore, spec.getUid(), keyAlgorithmJCA);
            success = true;
            return result;
        } finally {
            if (!success) {
                Credentials.deleteAllTypesForAlias(
                        mKeyStore, spec.getKeystoreAlias(), spec.getUid());
            }
        }
    }
}
