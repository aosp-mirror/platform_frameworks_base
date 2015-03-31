package android.security;

import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import java.security.InvalidAlgorithmParameterException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

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
            super(KeyStoreKeyConstraints.Algorithm.AES, 128);
        }
    }

    public static class HmacSHA256 extends KeyStoreKeyGeneratorSpi {
        public HmacSHA256() {
            super(KeyStoreKeyConstraints.Algorithm.HMAC,
                    KeyStoreKeyConstraints.Digest.SHA256,
                    KeyStoreKeyConstraints.Digest.getOutputSizeBytes(
                            KeyStoreKeyConstraints.Digest.SHA256) * 8);
        }
    }

    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final @KeyStoreKeyConstraints.AlgorithmEnum int mAlgorithm;
    private final @KeyStoreKeyConstraints.DigestEnum Integer mDigest;
    private final int mDefaultKeySizeBits;

    private KeyGeneratorSpec mSpec;
    private SecureRandom mRng;

    protected KeyStoreKeyGeneratorSpi(
            @KeyStoreKeyConstraints.AlgorithmEnum int algorithm,
            int defaultKeySizeBits) {
        this(algorithm, null, defaultKeySizeBits);
    }

    protected KeyStoreKeyGeneratorSpi(
            @KeyStoreKeyConstraints.AlgorithmEnum int algorithm,
            @KeyStoreKeyConstraints.DigestEnum Integer digest,
            int defaultKeySizeBits) {
        mAlgorithm = algorithm;
        mDigest = digest;
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
        args.addInt(KeymasterDefs.KM_TAG_ALGORITHM,
                KeyStoreKeyConstraints.Algorithm.toKeymaster(mAlgorithm));
        if (mDigest != null) {
            args.addInt(KeymasterDefs.KM_TAG_DIGEST,
                    KeyStoreKeyConstraints.Digest.toKeymaster(mDigest));
            Integer digestOutputSizeBytes =
                    KeyStoreKeyConstraints.Digest.getOutputSizeBytes(mDigest);
            if (digestOutputSizeBytes != null) {
                // TODO: Remove MAC length constraint once Keymaster API no longer requires it.
                // TODO: Switch to bits instead of bytes, once this is fixed in Keymaster
                args.addInt(KeymasterDefs.KM_TAG_MAC_LENGTH, digestOutputSizeBytes);
            }
        }
        if (mAlgorithm == KeyStoreKeyConstraints.Algorithm.HMAC) {
            if (mDigest == null) {
                throw new IllegalStateException("Digest algorithm must be specified for key"
                        + " algorithm " + KeyStoreKeyConstraints.Algorithm.toString(mAlgorithm));
            }
        }
        int keySizeBits = (spec.getKeySize() != null) ? spec.getKeySize() : mDefaultKeySizeBits;
        args.addInt(KeymasterDefs.KM_TAG_KEY_SIZE, keySizeBits);
        @KeyStoreKeyConstraints.PurposeEnum int purposes = (spec.getPurposes() != null)
                ? spec.getPurposes()
                : (KeyStoreKeyConstraints.Purpose.ENCRYPT
                        | KeyStoreKeyConstraints.Purpose.DECRYPT
                        | KeyStoreKeyConstraints.Purpose.SIGN
                        | KeyStoreKeyConstraints.Purpose.VERIFY);
        for (int keymasterPurpose :
            KeyStoreKeyConstraints.Purpose.allToKeymaster(purposes)) {
            args.addInt(KeymasterDefs.KM_TAG_PURPOSE, keymasterPurpose);
        }
        if (spec.getBlockMode() != null) {
            args.addInt(KeymasterDefs.KM_TAG_BLOCK_MODE,
                    KeyStoreKeyConstraints.BlockMode.toKeymaster(spec.getBlockMode()));
        }
        if (spec.getPadding() != null) {
            args.addInt(KeymasterDefs.KM_TAG_PADDING,
                    KeyStoreKeyConstraints.Padding.toKeymaster(spec.getPadding()));
        }
        if (spec.getMaxUsesPerBoot() != null) {
            args.addInt(KeymasterDefs.KM_TAG_MAX_USES_PER_BOOT, spec.getMaxUsesPerBoot());
        }
        if (spec.getMinSecondsBetweenOperations() != null) {
            args.addInt(KeymasterDefs.KM_TAG_MIN_SECONDS_BETWEEN_OPS,
                    spec.getMinSecondsBetweenOperations());
        }
        if (spec.getUserAuthenticators().isEmpty()) {
            args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        } else {
        // TODO: Pass-in user authenticator IDs once the Keymaster API has stabilized
//            for (int userAuthenticatorId : spec.getUserAuthenticators()) {
//                args.addInt(KeymasterDefs.KM_TAG_USER_AUTH_ID, userAuthenticatorId);
//            }
        }
        if (spec.getUserAuthenticationValidityDurationSeconds() != null) {
            args.addInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT,
                    spec.getUserAuthenticationValidityDurationSeconds());
        }
        if (spec.getKeyValidityStart() != null) {
            args.addDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, spec.getKeyValidityStart());
        }
        if (spec.getKeyValidityForOriginationEnd() != null) {
            args.addDate(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                    spec.getKeyValidityForOriginationEnd());
        }
        if (spec.getKeyValidityForConsumptionEnd() != null) {
            args.addDate(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                    spec.getKeyValidityForConsumptionEnd());
        }

        if (((purposes & KeyStoreKeyConstraints.Purpose.ENCRYPT) != 0)
            || ((purposes & KeyStoreKeyConstraints.Purpose.DECRYPT) != 0)) {
            // Permit caller-specified IV. This is needed due to the Cipher abstraction.
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
            throw new CryptoOperationException("Failed to generate key",
                    KeymasterUtils.getExceptionForKeymasterError(errorCode));
        }
        String keyAlgorithmJCA =
                KeyStoreKeyConstraints.Algorithm.toJCASecretKeyAlgorithm(mAlgorithm, mDigest);
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
