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

import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import java.security.InvalidAlgorithmParameterException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Date;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

/**
 * {@link KeyGeneratorSpi} backed by Android KeyStore.
 *
 * @hide
 */
public abstract class KeyStoreKeyGeneratorSpi extends KeyGeneratorSpi {

    public static class AES extends KeyStoreKeyGeneratorSpi {
        public AES() {
            super(KeymasterDefs.KM_ALGORITHM_AES, 128);
        }
    }

    protected static abstract class HmacBase extends KeyStoreKeyGeneratorSpi {
        protected HmacBase(int keymasterDigest) {
            super(KeymasterDefs.KM_ALGORITHM_HMAC,
                    keymasterDigest,
                    KeymasterUtils.getDigestOutputSizeBytes(keymasterDigest) * 8);
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

    private KeyGeneratorSpec mSpec;
    private SecureRandom mRng;

    protected KeyStoreKeyGeneratorSpi(
            int keymasterAlgorithm,
            int defaultKeySizeBits) {
        this(keymasterAlgorithm, -1, defaultKeySizeBits);
    }

    protected KeyStoreKeyGeneratorSpi(
            int keymasterAlgorithm,
            int keymasterDigest,
            int defaultKeySizeBits) {
        mKeymasterAlgorithm = keymasterAlgorithm;
        mKeymasterDigest = keymasterDigest;
        mDefaultKeySizeBits = defaultKeySizeBits;
    }

    @Override
    protected SecretKey engineGenerateKey() {
        KeyGeneratorSpec spec = mSpec;
        if (spec == null) {
            throw new IllegalStateException("Not initialized");
        }

        if ((spec.isEncryptionRequired())
                && (mKeyStore.state() != KeyStore.State.UNLOCKED)) {
            throw new IllegalStateException(
                    "Android KeyStore must be in initialized and unlocked state if encryption is"
                    + " required");
        }

        KeymasterArguments args = new KeymasterArguments();
        args.addInt(KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm);
        if (mKeymasterDigest != -1) {
            args.addInt(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest);
            int digestOutputSizeBytes =
                    KeymasterUtils.getDigestOutputSizeBytes(mKeymasterDigest);
            if (digestOutputSizeBytes != -1) {
                // TODO: Remove MAC length constraint once Keymaster API no longer requires it.
                // TODO: Switch to bits instead of bytes, once this is fixed in Keymaster
                args.addInt(KeymasterDefs.KM_TAG_MAC_LENGTH, digestOutputSizeBytes);
            }
        }
        if (mKeymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC) {
            if (mKeymasterDigest == -1) {
                throw new IllegalStateException("Digest algorithm must be specified for HMAC key");
            }
        }
        int keySizeBits = (spec.getKeySize() != null) ? spec.getKeySize() : mDefaultKeySizeBits;
        args.addInt(KeymasterDefs.KM_TAG_KEY_SIZE, keySizeBits);
        @KeyStoreKeyProperties.PurposeEnum int purposes = spec.getPurposes();
        int[] keymasterBlockModes = KeymasterUtils.getKeymasterBlockModesFromJcaBlockModes(
                spec.getBlockModes());
        if (((purposes & KeyStoreKeyProperties.Purpose.ENCRYPT) != 0)
                && (spec.isRandomizedEncryptionRequired())) {
            for (int keymasterBlockMode : keymasterBlockModes) {
                if (!KeymasterUtils.isKeymasterBlockModeIndCpaCompatible(keymasterBlockMode)) {
                    throw new IllegalStateException(
                            "Randomized encryption (IND-CPA) required but may be violated by block"
                            + " mode: "
                            + KeymasterUtils.getJcaBlockModeFromKeymasterBlockMode(
                                    keymasterBlockMode)
                            + ". See KeyGeneratorSpec documentation.");
                }
            }
        }

        for (int keymasterPurpose :
            KeyStoreKeyProperties.Purpose.allToKeymaster(purposes)) {
            args.addInt(KeymasterDefs.KM_TAG_PURPOSE, keymasterPurpose);
        }
        args.addInts(KeymasterDefs.KM_TAG_BLOCK_MODE, keymasterBlockModes);
        args.addInts(
                KeymasterDefs.KM_TAG_PADDING,
                KeymasterUtils.getKeymasterPaddingsFromJcaEncryptionPaddings(
                        spec.getEncryptionPaddings()));
        if (spec.getUserAuthenticators() == 0) {
            args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        } else {
            args.addInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE,
                    KeyStoreKeyProperties.UserAuthenticator.allToKeymaster(
                            spec.getUserAuthenticators()));
        }
        if (spec.getUserAuthenticationValidityDurationSeconds() != -1) {
            args.addInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT,
                    spec.getUserAuthenticationValidityDurationSeconds());
        }
        args.addDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME,
                (spec.getKeyValidityStart() != null)
                ? spec.getKeyValidityStart() : new Date(0));
        args.addDate(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                (spec.getKeyValidityForOriginationEnd() != null)
                ? spec.getKeyValidityForOriginationEnd() : new Date(Long.MAX_VALUE));
        args.addDate(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                (spec.getKeyValidityForConsumptionEnd() != null)
                ? spec.getKeyValidityForConsumptionEnd() : new Date(Long.MAX_VALUE));

        if (((purposes & KeyStoreKeyProperties.Purpose.ENCRYPT) != 0)
                && (!spec.isRandomizedEncryptionRequired())) {
            // Permit caller-provided IV when encrypting with this key
            args.addBoolean(KeymasterDefs.KM_TAG_CALLER_NONCE);
        }

        byte[] additionalEntropy = null;
        SecureRandom rng = mRng;
        if (rng != null) {
            additionalEntropy = new byte[(keySizeBits + 7) / 8];
            rng.nextBytes(additionalEntropy);
        }

        int flags = spec.getFlags();
        String keyAliasInKeystore = Credentials.USER_SECRET_KEY + spec.getKeystoreAlias();
        int errorCode = mKeyStore.generateKey(
                keyAliasInKeystore, args, additionalEntropy, flags, new KeyCharacteristics());
        if (errorCode != KeyStore.NO_ERROR) {
            throw KeyStore.getCryptoOperationException(errorCode);
        }
        String keyAlgorithmJCA =
                KeymasterUtils.getJcaSecretKeyAlgorithm(mKeymasterAlgorithm, mKeymasterDigest);
        return new KeyStoreSecretKey(keyAliasInKeystore, keyAlgorithmJCA);
    }

    @Override
    protected void engineInit(SecureRandom random) {
        throw new UnsupportedOperationException("Cannot initialize without an "
                + KeyGeneratorSpec.class.getName() + " parameter");
    }

    @Override
    protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        if ((params == null) || (!(params instanceof KeyGeneratorSpec))) {
            throw new InvalidAlgorithmParameterException("Cannot initialize without an "
                    + KeyGeneratorSpec.class.getName() + " parameter");
        }
        KeyGeneratorSpec spec = (KeyGeneratorSpec) params;
        if (spec.getKeystoreAlias() == null) {
            throw new InvalidAlgorithmParameterException("KeyStore entry alias not provided");
        }

        mSpec = spec;
        mRng = random;
    }

    @Override
    protected void engineInit(int keySize, SecureRandom random) {
        throw new UnsupportedOperationException("Cannot initialize without a "
                + KeyGeneratorSpec.class.getName() + " parameter");
    }
}
