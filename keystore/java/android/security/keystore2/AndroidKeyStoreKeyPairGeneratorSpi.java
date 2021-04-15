/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.security.keystore2;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.SecurityLevel;
import android.hardware.security.keymint.Tag;
import android.os.Build;
import android.os.RemoteException;
import android.security.GenerateRkpKey;
import android.security.GenerateRkpKeyException;
import android.security.KeyPairGeneratorSpec;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.security.KeyStoreSecurityLevel;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.ArrayUtils;
import android.security.keystore.AttestationUtils;
import android.security.keystore.DeviceIdAttestationException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.SecureKeyImportUnavailableException;
import android.security.keystore.StrongBoxUnavailableException;
import android.system.keystore2.Authorization;
import android.system.keystore2.Domain;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.system.keystore2.ResponseCode;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;

import libcore.util.EmptyArray;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Provides a way to create instances of a KeyPair which will be placed in the
 * Android keystore service usable only by the application that called it. This
 * can be used in conjunction with
 * {@link java.security.KeyStore#getInstance(String)} using the
 * {@code "AndroidKeyStore"} type.
 * <p>
 * This class can not be directly instantiated and must instead be used via the
 * {@link KeyPairGenerator#getInstance(String)
 * KeyPairGenerator.getInstance("AndroidKeyStore")} API.
 *
 * @hide
 */
public abstract class AndroidKeyStoreKeyPairGeneratorSpi extends KeyPairGeneratorSpi {
    private static final String TAG = "AndroidKeyStoreKeyPairGeneratorSpi";

    public static class RSA extends AndroidKeyStoreKeyPairGeneratorSpi {
        public RSA() {
            super(KeymasterDefs.KM_ALGORITHM_RSA);
        }
    }

    public static class EC extends AndroidKeyStoreKeyPairGeneratorSpi {
        public EC() {
            super(KeymasterDefs.KM_ALGORITHM_EC);
        }
    }

    /*
     * These must be kept in sync with system/security/keystore/defaults.h
     */

    /* EC */
    private static final int EC_DEFAULT_KEY_SIZE = 256;

    /* RSA */
    private static final int RSA_DEFAULT_KEY_SIZE = 2048;
    private static final int RSA_MIN_KEY_SIZE = 512;
    private static final int RSA_MAX_KEY_SIZE = 8192;

    private static final Map<String, Integer> SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE =
            new HashMap<String, Integer>();
    private static final List<String> SUPPORTED_EC_NIST_CURVE_NAMES = new ArrayList<String>();
    private static final List<Integer> SUPPORTED_EC_NIST_CURVE_SIZES = new ArrayList<Integer>();
    static {
        // Aliases for NIST P-224
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-224", 224);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp224r1", 224);


        // Aliases for NIST P-256
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-256", 256);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp256r1", 256);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("prime256v1", 256);

        // Aliases for NIST P-384
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-384", 384);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp384r1", 384);

        // Aliases for NIST P-521
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("p-521", 521);
        SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.put("secp521r1", 521);

        SUPPORTED_EC_NIST_CURVE_NAMES.addAll(SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.keySet());
        Collections.sort(SUPPORTED_EC_NIST_CURVE_NAMES);

        SUPPORTED_EC_NIST_CURVE_SIZES.addAll(
                new HashSet<Integer>(SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.values()));
        Collections.sort(SUPPORTED_EC_NIST_CURVE_SIZES);
    }

    private final int mOriginalKeymasterAlgorithm;

    private KeyStore2 mKeyStore;

    private KeyGenParameterSpec mSpec;

    private String mEntryAlias;
    private int mEntryNamespace;
    private @KeyProperties.KeyAlgorithmEnum String mJcaKeyAlgorithm;
    private int mKeymasterAlgorithm = -1;
    private int mKeySizeBits;
    private SecureRandom mRng;
    private KeyDescriptor mAttestKeyDescriptor;

    private int[] mKeymasterPurposes;
    private int[] mKeymasterBlockModes;
    private int[] mKeymasterEncryptionPaddings;
    private int[] mKeymasterSignaturePaddings;
    private int[] mKeymasterDigests;

    private Long mRSAPublicExponent;

    protected AndroidKeyStoreKeyPairGeneratorSpi(int keymasterAlgorithm) {
        mOriginalKeymasterAlgorithm = keymasterAlgorithm;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void initialize(int keysize, SecureRandom random) {
        throw new IllegalArgumentException(
                KeyGenParameterSpec.class.getName() + " or " + KeyPairGeneratorSpec.class.getName()
                + " required to initialize this KeyPairGenerator");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            if (params == null) {
                throw new InvalidAlgorithmParameterException(
                        "Must supply params of type " + KeyGenParameterSpec.class.getName()
                        + " or " + KeyPairGeneratorSpec.class.getName());
            }

            KeyGenParameterSpec spec;
            boolean encryptionAtRestRequired = false;
            int keymasterAlgorithm = mOriginalKeymasterAlgorithm;
            if (params instanceof KeyGenParameterSpec) {
                spec = (KeyGenParameterSpec) params;
            } else if (params instanceof KeyPairGeneratorSpec) {
                // Legacy/deprecated spec
                KeyPairGeneratorSpec legacySpec = (KeyPairGeneratorSpec) params;
                try {
                    keymasterAlgorithm = getKeymasterAlgorithmFromLegacy(keymasterAlgorithm,
                            legacySpec);
                    spec = buildKeyGenParameterSpecFromLegacy(legacySpec, keymasterAlgorithm);
                } catch (NullPointerException | IllegalArgumentException e) {
                    throw new InvalidAlgorithmParameterException(e);
                }
            } else {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported params class: " + params.getClass().getName()
                        + ". Supported: " + KeyGenParameterSpec.class.getName()
                        + ", " + KeyPairGeneratorSpec.class.getName());
            }

            mEntryAlias = spec.getKeystoreAlias();
            mEntryNamespace = spec.getNamespace();
            mSpec = spec;
            mKeymasterAlgorithm = keymasterAlgorithm;
            mKeySizeBits = spec.getKeySize();
            initAlgorithmSpecificParameters();
            if (mKeySizeBits == -1) {
                mKeySizeBits = getDefaultKeySize(keymasterAlgorithm);
            }
            checkValidKeySize(keymasterAlgorithm, mKeySizeBits, mSpec.isStrongBoxBacked());

            if (spec.getKeystoreAlias() == null) {
                throw new InvalidAlgorithmParameterException("KeyStore entry alias not provided");
            }

            String jcaKeyAlgorithm;
            try {
                jcaKeyAlgorithm = KeyProperties.KeyAlgorithm.fromKeymasterAsymmetricKeyAlgorithm(
                        keymasterAlgorithm);
                mKeymasterPurposes = KeyProperties.Purpose.allToKeymaster(spec.getPurposes());
                mKeymasterBlockModes = KeyProperties.BlockMode.allToKeymaster(spec.getBlockModes());
                mKeymasterEncryptionPaddings = KeyProperties.EncryptionPadding.allToKeymaster(
                        spec.getEncryptionPaddings());
                if (((spec.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0)
                        && (spec.isRandomizedEncryptionRequired())) {
                    for (int keymasterPadding : mKeymasterEncryptionPaddings) {
                        if (!KeymasterUtils
                                .isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto(
                                        keymasterPadding)) {
                            throw new InvalidAlgorithmParameterException(
                                    "Randomized encryption (IND-CPA) required but may be violated"
                                    + " by padding scheme: "
                                    + KeyProperties.EncryptionPadding.fromKeymaster(
                                            keymasterPadding)
                                    + ". See " + KeyGenParameterSpec.class.getName()
                                    + " documentation.");
                        }
                    }
                }
                mKeymasterSignaturePaddings = KeyProperties.SignaturePadding.allToKeymaster(
                        spec.getSignaturePaddings());
                if (spec.isDigestsSpecified()) {
                    mKeymasterDigests = KeyProperties.Digest.allToKeymaster(spec.getDigests());
                } else {
                    mKeymasterDigests = EmptyArray.INT;
                }

                // Check that user authentication related parameters are acceptable. This method
                // will throw an IllegalStateException if there are issues (e.g., secure lock screen
                // not set up).
                KeyStore2ParameterUtils.addUserAuthArgs(new ArrayList<>(), mSpec);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new InvalidAlgorithmParameterException(e);
            }

            mJcaKeyAlgorithm = jcaKeyAlgorithm;
            mRng = random;
            mKeyStore = KeyStore2.getInstance();

            mAttestKeyDescriptor = buildAndCheckAttestKeyDescriptor(spec);
            checkAttestKeyPurpose(spec);

            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void checkAttestKeyPurpose(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        if ((spec.getPurposes() & KeyProperties.PURPOSE_ATTEST_KEY) != 0
                && spec.getPurposes() != KeyProperties.PURPOSE_ATTEST_KEY) {
            throw new InvalidAlgorithmParameterException(
                    "PURPOSE_ATTEST_KEY may not be specified with any other purposes");
        }
    }

    private KeyDescriptor buildAndCheckAttestKeyDescriptor(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        if (spec.getAttestKeyAlias() != null) {
            KeyDescriptor attestKeyDescriptor = new KeyDescriptor();
            attestKeyDescriptor.domain = Domain.APP;
            attestKeyDescriptor.alias = spec.getAttestKeyAlias();
            try {
                KeyEntryResponse attestKey = mKeyStore.getKeyEntry(attestKeyDescriptor);
                checkAttestKeyChallenge(spec);
                checkAttestKeyPurpose(attestKey.metadata.authorizations);
                checkAttestKeySecurityLevel(spec, attestKey);
            } catch (KeyStoreException e) {
                throw new InvalidAlgorithmParameterException("Invalid attestKeyAlias", e);
            }
            return attestKeyDescriptor;
        }
        return null;
    }

    private void checkAttestKeyChallenge(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        if (spec.getAttestationChallenge() == null) {
            throw new InvalidAlgorithmParameterException(
                    "AttestKey specified but no attestation challenge provided");
        }
    }

    private void checkAttestKeyPurpose(Authorization[] keyAuths)
            throws InvalidAlgorithmParameterException {
        Predicate<Authorization> isAttestKeyPurpose = x -> x.keyParameter.tag == Tag.PURPOSE
                && x.keyParameter.value.getKeyPurpose() == KeyPurpose.ATTEST_KEY;

        if (Arrays.stream(keyAuths).noneMatch(isAttestKeyPurpose)) {
            throw new InvalidAlgorithmParameterException(
                    ("Invalid attestKey, does not have PURPOSE_ATTEST_KEY"));
        }
    }

    private void checkAttestKeySecurityLevel(KeyGenParameterSpec spec, KeyEntryResponse key)
            throws InvalidAlgorithmParameterException {
        boolean attestKeyInStrongBox = key.metadata.keySecurityLevel == SecurityLevel.STRONGBOX;
        if (spec.isStrongBoxBacked() != attestKeyInStrongBox) {
            if (attestKeyInStrongBox) {
                throw new InvalidAlgorithmParameterException(
                        "Invalid security level: Cannot sign non-StrongBox key with "
                                + "StrongBox attestKey");

            } else {
                throw new InvalidAlgorithmParameterException(
                        "Invalid security level: Cannot sign StrongBox key with "
                                + "non-StrongBox attestKey");
            }
        }
    }

    private int getKeymasterAlgorithmFromLegacy(int keymasterAlgorithm,
            KeyPairGeneratorSpec legacySpec) throws InvalidAlgorithmParameterException {
        String specKeyAlgorithm = legacySpec.getKeyType();
        if (specKeyAlgorithm != null) {
            // Spec overrides the generator's default key algorithm
            try {
                keymasterAlgorithm =
                        KeyProperties.KeyAlgorithm.toKeymasterAsymmetricKeyAlgorithm(
                                specKeyAlgorithm);
            } catch (IllegalArgumentException e) {
                throw new InvalidAlgorithmParameterException(
                        "Invalid key type in parameters", e);
            }
        }
        return keymasterAlgorithm;
    }

    private KeyGenParameterSpec buildKeyGenParameterSpecFromLegacy(KeyPairGeneratorSpec legacySpec,
            int keymasterAlgorithm) {
        KeyGenParameterSpec.Builder specBuilder;
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                specBuilder = new KeyGenParameterSpec.Builder(
                        legacySpec.getKeystoreAlias(),
                        KeyProperties.PURPOSE_SIGN
                        | KeyProperties.PURPOSE_VERIFY);
                // Authorized to be used with any digest (including no digest).
                // MD5 was never offered for Android Keystore for ECDSA.
                specBuilder.setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA1,
                        KeyProperties.DIGEST_SHA224,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512);
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                specBuilder = new KeyGenParameterSpec.Builder(
                        legacySpec.getKeystoreAlias(),
                        KeyProperties.PURPOSE_ENCRYPT
                        | KeyProperties.PURPOSE_DECRYPT
                        | KeyProperties.PURPOSE_SIGN
                        | KeyProperties.PURPOSE_VERIFY);
                // Authorized to be used with any digest (including no digest).
                specBuilder.setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_MD5,
                        KeyProperties.DIGEST_SHA1,
                        KeyProperties.DIGEST_SHA224,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512);
                // Authorized to be used with any encryption and signature padding
                // schemes (including no padding).
                specBuilder.setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE,
                        KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                        KeyProperties.ENCRYPTION_PADDING_RSA_OAEP);
                specBuilder.setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS);
                // Disable randomized encryption requirement to support encryption
                // padding NONE above.
                specBuilder.setRandomizedEncryptionRequired(false);
                break;
            default:
                throw new ProviderException(
                        "Unsupported algorithm: " + mKeymasterAlgorithm);
        }

        if (legacySpec.getKeySize() != -1) {
            specBuilder.setKeySize(legacySpec.getKeySize());
        }
        if (legacySpec.getAlgorithmParameterSpec() != null) {
            specBuilder.setAlgorithmParameterSpec(
                    legacySpec.getAlgorithmParameterSpec());
        }
        specBuilder.setCertificateSubject(legacySpec.getSubjectDN());
        specBuilder.setCertificateSerialNumber(legacySpec.getSerialNumber());
        specBuilder.setCertificateNotBefore(legacySpec.getStartDate());
        specBuilder.setCertificateNotAfter(legacySpec.getEndDate());
        specBuilder.setUserAuthenticationRequired(false);

        return specBuilder.build();
    }

    private void resetAll() {
        mEntryAlias = null;
        mEntryNamespace = KeyProperties.NAMESPACE_APPLICATION;
        mJcaKeyAlgorithm = null;
        mKeymasterAlgorithm = -1;
        mKeymasterPurposes = null;
        mKeymasterBlockModes = null;
        mKeymasterEncryptionPaddings = null;
        mKeymasterSignaturePaddings = null;
        mKeymasterDigests = null;
        mKeySizeBits = 0;
        mSpec = null;
        mRSAPublicExponent = null;
        mRng = null;
        mKeyStore = null;
    }

    private void initAlgorithmSpecificParameters() throws InvalidAlgorithmParameterException {
        AlgorithmParameterSpec algSpecificSpec = mSpec.getAlgorithmParameterSpec();
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
            {
                BigInteger publicExponent = null;
                if (algSpecificSpec instanceof RSAKeyGenParameterSpec) {
                    RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) algSpecificSpec;
                    if (mKeySizeBits == -1) {
                        mKeySizeBits = rsaSpec.getKeysize();
                    } else if (mKeySizeBits != rsaSpec.getKeysize()) {
                        throw new InvalidAlgorithmParameterException("RSA key size must match "
                                + " between " + mSpec + " and " + algSpecificSpec
                                + ": " + mKeySizeBits + " vs " + rsaSpec.getKeysize());
                    }
                    publicExponent = rsaSpec.getPublicExponent();
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                        "RSA may only use RSAKeyGenParameterSpec");
                }
                if (publicExponent == null) {
                    publicExponent = RSAKeyGenParameterSpec.F4;
                }
                if (publicExponent.compareTo(BigInteger.ZERO) < 1) {
                    throw new InvalidAlgorithmParameterException(
                            "RSA public exponent must be positive: " + publicExponent);
                }
                if ((publicExponent.signum() == -1)
                        || (publicExponent.compareTo(KeymasterArguments.UINT64_MAX_VALUE) > 0)) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported RSA public exponent: " + publicExponent
                            + ". Maximum supported value: " + KeymasterArguments.UINT64_MAX_VALUE);
                }
                mRSAPublicExponent = publicExponent.longValue();
                break;
            }
            case KeymasterDefs.KM_ALGORITHM_EC:
                if (algSpecificSpec instanceof ECGenParameterSpec) {
                    ECGenParameterSpec ecSpec = (ECGenParameterSpec) algSpecificSpec;
                    String curveName = ecSpec.getName();
                    Integer ecSpecKeySizeBits = SUPPORTED_EC_NIST_CURVE_NAME_TO_SIZE.get(
                            curveName.toLowerCase(Locale.US));
                    if (ecSpecKeySizeBits == null) {
                        throw new InvalidAlgorithmParameterException(
                                "Unsupported EC curve name: " + curveName
                                + ". Supported: " + SUPPORTED_EC_NIST_CURVE_NAMES);
                    }
                    if (mKeySizeBits == -1) {
                        mKeySizeBits = ecSpecKeySizeBits;
                    } else if (mKeySizeBits != ecSpecKeySizeBits) {
                        throw new InvalidAlgorithmParameterException("EC key size must match "
                                + " between " + mSpec + " and " + algSpecificSpec
                                + ": " + mKeySizeBits + " vs " + ecSpecKeySizeBits);
                    }
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                        "EC may only use ECGenParameterSpec");
                }
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        try {
            return generateKeyPairHelper();
        } catch (GenerateRkpKeyException e) {
            try {
                return generateKeyPairHelper();
            } catch (GenerateRkpKeyException f) {
                throw new ProviderException("Failed to provision new attestation keys.");
            }
        }
    }

    private KeyPair generateKeyPairHelper() throws GenerateRkpKeyException {
        if (mKeyStore == null || mSpec == null) {
            throw new IllegalStateException("Not initialized");
        }

        final @SecurityLevel int securityLevel =
                mSpec.isStrongBoxBacked()
                        ? SecurityLevel.STRONGBOX
                        : SecurityLevel.TRUSTED_ENVIRONMENT;

        final int flags =
                mSpec.isCriticalToDeviceEncryption()
                        ? IKeystoreSecurityLevel
                                .KEY_FLAG_AUTH_BOUND_WITHOUT_CRYPTOGRAPHIC_LSKF_BINDING
                        : 0;

        byte[] additionalEntropy =
                KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                        mRng, (mKeySizeBits + 7) / 8);

        KeyDescriptor descriptor = new KeyDescriptor();
        descriptor.alias = mEntryAlias;
        descriptor.domain = mEntryNamespace == KeyProperties.NAMESPACE_APPLICATION
                ? Domain.APP
                : Domain.SELINUX;
        descriptor.nspace = mEntryNamespace;
        descriptor.blob = null;

        boolean success = false;
        try {
            KeyStoreSecurityLevel iSecurityLevel = mKeyStore.getSecurityLevel(securityLevel);

            KeyMetadata metadata = iSecurityLevel.generateKey(descriptor, mAttestKeyDescriptor,
                    constructKeyGenerationArguments(), flags, additionalEntropy);

            AndroidKeyStorePublicKey publicKey =
                    AndroidKeyStoreProvider.makeAndroidKeyStorePublicKeyFromKeyEntryResponse(
                            descriptor, metadata, iSecurityLevel, mKeymasterAlgorithm);
            GenerateRkpKey keyGen = new GenerateRkpKey(ActivityThread
                    .currentApplication());
            try {
                if (mSpec.getAttestationChallenge() != null) {
                    keyGen.notifyKeyGenerated(securityLevel);
                }
            } catch (RemoteException e) {
                // This is not really an error state, and necessarily does not apply to non RKP
                // systems or hybrid systems where RKP is not currently turned on.
                Log.d(TAG, "Couldn't connect to the RemoteProvisioner backend.");
            }
            success = true;
            return new KeyPair(publicKey, publicKey.getPrivateKey());
        } catch (android.security.KeyStoreException e) {
            switch(e.getErrorCode()) {
                case KeymasterDefs.KM_ERROR_HARDWARE_TYPE_UNAVAILABLE:
                    throw new StrongBoxUnavailableException("Failed to generated key pair.", e);
                case ResponseCode.OUT_OF_KEYS:
                    GenerateRkpKey keyGen = new GenerateRkpKey(ActivityThread
                            .currentApplication());
                    try {
                        keyGen.notifyEmpty(securityLevel);
                    } catch (RemoteException f) {
                        throw new ProviderException("Failed to talk to RemoteProvisioner", f);
                    }
                    throw new GenerateRkpKeyException();
                default:
                    ProviderException p = new ProviderException("Failed to generate key pair.", e);
                    if ((mSpec.getPurposes() & KeyProperties.PURPOSE_WRAP_KEY) != 0) {
                        throw new SecureKeyImportUnavailableException(p);
                    }
                    throw p;
            }
        } catch (UnrecoverableKeyException | IllegalArgumentException
                    | DeviceIdAttestationException e) {
            throw new ProviderException(
                    "Failed to construct key object from newly generated key pair.", e);
        } finally {
            if (!success) {
                try {
                    mKeyStore.deleteKey(descriptor);
                } catch (KeyStoreException e) {
                    if (e.getErrorCode() != ResponseCode.KEY_NOT_FOUND) {
                        Log.e(TAG, "Failed to delete newly generated key after "
                                + "generation failed unexpectedly.", e);
                    }
                }
            }
        }
    }

    private void addAttestationParameters(@NonNull List<KeyParameter> params)
            throws ProviderException, IllegalArgumentException, DeviceIdAttestationException {
        byte[] challenge = mSpec.getAttestationChallenge();

        if (challenge != null) {
            params.add(KeyStore2ParameterUtils.makeBytes(
                    KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE, challenge
            ));

            if (mSpec.isDevicePropertiesAttestationIncluded()) {
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_BRAND,
                        Build.BRAND.getBytes(StandardCharsets.UTF_8)
                ));
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_DEVICE,
                        Build.DEVICE.getBytes(StandardCharsets.UTF_8)
                ));
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_PRODUCT,
                        Build.PRODUCT.getBytes(StandardCharsets.UTF_8)
                ));
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_MANUFACTURER,
                        Build.MANUFACTURER.getBytes(StandardCharsets.UTF_8)
                ));
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_MODEL,
                        Build.MODEL.getBytes(StandardCharsets.UTF_8)
                ));
            }

            int[] idTypes = mSpec.getAttestationIds();
            if (idTypes.length == 0) {
                return;
            }
            final Set<Integer> idTypesSet = new ArraySet<>(idTypes.length);
            for (int idType : idTypes) {
                idTypesSet.add(idType);
            }
            TelephonyManager telephonyService = null;
            if (idTypesSet.contains(AttestationUtils.ID_TYPE_IMEI)
                    || idTypesSet.contains(AttestationUtils.ID_TYPE_MEID)) {
                telephonyService =
                    (TelephonyManager) android.app.AppGlobals.getInitialApplication()
                            .getSystemService(Context.TELEPHONY_SERVICE);
                if (telephonyService == null) {
                    throw new DeviceIdAttestationException("Unable to access telephony service");
                }
            }
            for (final Integer idType : idTypesSet) {
                switch (idType) {
                    case AttestationUtils.ID_TYPE_SERIAL:
                        params.add(KeyStore2ParameterUtils.makeBytes(
                                KeymasterDefs.KM_TAG_ATTESTATION_ID_SERIAL,
                                Build.getSerial().getBytes(StandardCharsets.UTF_8)
                        ));
                        break;
                    case AttestationUtils.ID_TYPE_IMEI: {
                        final String imei = telephonyService.getImei(0);
                        if (imei == null) {
                            throw new DeviceIdAttestationException("Unable to retrieve IMEI");
                        }
                        params.add(KeyStore2ParameterUtils.makeBytes(
                                KeymasterDefs.KM_TAG_ATTESTATION_ID_IMEI,
                                imei.getBytes(StandardCharsets.UTF_8)
                        ));
                        break;
                    }
                    case AttestationUtils.ID_TYPE_MEID: {
                        final String meid = telephonyService.getMeid(0);
                        if (meid == null) {
                            throw new DeviceIdAttestationException("Unable to retrieve MEID");
                        }
                        params.add(KeyStore2ParameterUtils.makeBytes(
                                KeymasterDefs.KM_TAG_ATTESTATION_ID_MEID,
                                meid.getBytes(StandardCharsets.UTF_8)
                        ));
                        break;
                    }
                    case AttestationUtils.USE_INDIVIDUAL_ATTESTATION: {
                        params.add(KeyStore2ParameterUtils.makeBool(
                                KeymasterDefs.KM_TAG_DEVICE_UNIQUE_ATTESTATION));
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("Unknown device ID type " + idType);
                }
            }
        }
    }

    private Collection<KeyParameter> constructKeyGenerationArguments()
            throws DeviceIdAttestationException, IllegalArgumentException {
        List<KeyParameter> params = new ArrayList<>();
        params.add(KeyStore2ParameterUtils.makeInt(KeymasterDefs.KM_TAG_KEY_SIZE, mKeySizeBits));
        params.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm
        ));
        ArrayUtils.forEach(mKeymasterPurposes, (purpose) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_PURPOSE, purpose
            ));
        });
        ArrayUtils.forEach(mKeymasterBlockModes, (blockMode) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_BLOCK_MODE, blockMode
            ));
        });
        ArrayUtils.forEach(mKeymasterEncryptionPaddings, (padding) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_PADDING, padding
            ));
        });
        ArrayUtils.forEach(mKeymasterSignaturePaddings, (padding) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_PADDING, padding
            ));
        });
        ArrayUtils.forEach(mKeymasterDigests, (digest) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_DIGEST, digest
            ));
        });

        KeyStore2ParameterUtils.addUserAuthArgs(params, mSpec);

        if (mSpec.getKeyValidityStart() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_ACTIVE_DATETIME, mSpec.getKeyValidityStart()
            ));
        }
        if (mSpec.getKeyValidityForOriginationEnd() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                    mSpec.getKeyValidityForOriginationEnd()
            ));
        }
        if (mSpec.getKeyValidityForConsumptionEnd() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                    mSpec.getKeyValidityForConsumptionEnd()
            ));
        }
        if (mSpec.getCertificateNotAfter() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_CERTIFICATE_NOT_AFTER,
                    mSpec.getCertificateNotAfter()
            ));
        }
        if (mSpec.getCertificateNotBefore() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_CERTIFICATE_NOT_BEFORE,
                    mSpec.getCertificateNotBefore()
            ));
        }
        if (mSpec.getCertificateSerialNumber() != null) {
            params.add(KeyStore2ParameterUtils.makeBignum(
                    KeymasterDefs.KM_TAG_CERTIFICATE_SERIAL,
                    mSpec.getCertificateSerialNumber()
            ));
        }
        if (mSpec.getCertificateSubject() != null) {
            params.add(KeyStore2ParameterUtils.makeBytes(
                    KeymasterDefs.KM_TAG_CERTIFICATE_SUBJECT,
                    mSpec.getCertificateSubject().getEncoded()
            ));
        }

        if (mSpec.getMaxUsageCount() != KeyProperties.UNRESTRICTED_USAGE_COUNT) {
            params.add(KeyStore2ParameterUtils.makeInt(
                    KeymasterDefs.KM_TAG_USAGE_COUNT_LIMIT,
                    mSpec.getMaxUsageCount()
            ));
        }

        addAlgorithmSpecificParameters(params);

        if (mSpec.isUniqueIdIncluded()) {
            params.add(KeyStore2ParameterUtils.makeBool(KeymasterDefs.KM_TAG_INCLUDE_UNIQUE_ID));
        }

        addAttestationParameters(params);

        return params;
    }

    private void addAlgorithmSpecificParameters(List<KeyParameter> params) {
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
                params.add(KeyStore2ParameterUtils.makeLong(
                        KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, mRSAPublicExponent
                ));
                break;
            case KeymasterDefs.KM_ALGORITHM_EC:
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    private static int getDefaultKeySize(int keymasterAlgorithm) {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                return EC_DEFAULT_KEY_SIZE;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                return RSA_DEFAULT_KEY_SIZE;
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    private static void checkValidKeySize(
            int keymasterAlgorithm,
            int keySize,
            boolean isStrongBoxBacked)
            throws InvalidAlgorithmParameterException {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                if (isStrongBoxBacked && keySize != 256) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported StrongBox EC key size: "
                            + keySize + " bits. Supported: 256");
                }
                if (!SUPPORTED_EC_NIST_CURVE_SIZES.contains(keySize)) {
                    throw new InvalidAlgorithmParameterException("Unsupported EC key size: "
                            + keySize + " bits. Supported: " + SUPPORTED_EC_NIST_CURVE_SIZES);
                }
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                if (keySize < RSA_MIN_KEY_SIZE || keySize > RSA_MAX_KEY_SIZE) {
                    throw new InvalidAlgorithmParameterException("RSA key size must be >= "
                            + RSA_MIN_KEY_SIZE + " and <= " + RSA_MAX_KEY_SIZE);
                }
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    /**
     * Returns the {@code Signature} algorithm to be used for signing a certificate using the
     * specified key or {@code null} if the key cannot be used for signing a certificate.
     */
    @Nullable
    private static String getCertificateSignatureAlgorithm(
            int keymasterAlgorithm,
            int keySizeBits,
            KeyGenParameterSpec spec) {
        // Constraints:
        // 1. Key must be authorized for signing without user authentication.
        // 2. Signature digest must be one of key's authorized digests.
        // 3. For RSA keys, the digest output size must not exceed modulus size minus space overhead
        //    of RSA PKCS#1 signature padding scheme (about 30 bytes).
        // 4. For EC keys, the there is no point in using a digest whose output size is longer than
        //    key/field size because the digest will be truncated to that size.

        if ((spec.getPurposes() & KeyProperties.PURPOSE_SIGN) == 0) {
            // Key not authorized for signing
            return null;
        }
        if (spec.isUserAuthenticationRequired()) {
            // Key not authorized for use without user authentication
            return null;
        }
        if (!spec.isDigestsSpecified()) {
            // Key not authorized for any digests -- can't sign
            return null;
        }
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
            {
                Set<Integer> availableKeymasterDigests = getAvailableKeymasterSignatureDigests(
                        spec.getDigests(),
                        AndroidKeyStoreBCWorkaroundProvider.getSupportedEcdsaSignatureDigests());

                int bestKeymasterDigest = -1;
                int bestDigestOutputSizeBits = -1;
                for (int keymasterDigest : availableKeymasterDigests) {
                    int outputSizeBits = KeymasterUtils.getDigestOutputSizeBits(keymasterDigest);
                    if (outputSizeBits == keySizeBits) {
                        // Perfect match -- use this digest
                        bestKeymasterDigest = keymasterDigest;
                        bestDigestOutputSizeBits = outputSizeBits;
                        break;
                    }
                    // Not a perfect match -- check against the best digest so far
                    if (bestKeymasterDigest == -1) {
                        // First digest tested -- definitely the best so far
                        bestKeymasterDigest = keymasterDigest;
                        bestDigestOutputSizeBits = outputSizeBits;
                    } else {
                        // Prefer output size to be as close to key size as possible, with output
                        // sizes larger than key size preferred to those smaller than key size.
                        if (bestDigestOutputSizeBits < keySizeBits) {
                            // Output size of the best digest so far is smaller than key size.
                            // Anything larger is a win.
                            if (outputSizeBits > bestDigestOutputSizeBits) {
                                bestKeymasterDigest = keymasterDigest;
                                bestDigestOutputSizeBits = outputSizeBits;
                            }
                        } else {
                            // Output size of the best digest so far is larger than key size.
                            // Anything smaller is a win, as long as it's not smaller than key size.
                            if ((outputSizeBits < bestDigestOutputSizeBits)
                                    && (outputSizeBits >= keySizeBits)) {
                                bestKeymasterDigest = keymasterDigest;
                                bestDigestOutputSizeBits = outputSizeBits;
                            }
                        }
                    }
                }
                if (bestKeymasterDigest == -1) {
                    return null;
                }
                return KeyProperties.Digest.fromKeymasterToSignatureAlgorithmDigest(
                        bestKeymasterDigest) + "WithECDSA";
            }
            case KeymasterDefs.KM_ALGORITHM_RSA:
            {
                // Check whether this key is authorized for PKCS#1 signature padding.
                // We use Bouncy Castle to generate self-signed RSA certificates. Bouncy Castle
                // only supports RSA certificates signed using PKCS#1 padding scheme. The key needs
                // to be authorized for PKCS#1 padding or padding NONE which means any padding.
                boolean pkcs1SignaturePaddingSupported =
                        com.android.internal.util.ArrayUtils.contains(
                                KeyProperties.SignaturePadding.allToKeymaster(
                                        spec.getSignaturePaddings()),
                                KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN);
                if (!pkcs1SignaturePaddingSupported) {
                    // Key not authorized for PKCS#1 signature padding -- can't sign
                    return null;
                }

                Set<Integer> availableKeymasterDigests = getAvailableKeymasterSignatureDigests(
                        spec.getDigests(),
                        AndroidKeyStoreBCWorkaroundProvider.getSupportedEcdsaSignatureDigests());

                // The amount of space available for the digest is less than modulus size by about
                // 30 bytes because padding must be at least 11 bytes long (00 || 01 || PS || 00,
                // where PS must be at least 8 bytes long), and then there's also the 15--19 bytes
                // overhead (depending the on chosen digest) for encoding digest OID and digest
                // value in DER.
                int maxDigestOutputSizeBits = keySizeBits - 30 * 8;
                int bestKeymasterDigest = -1;
                int bestDigestOutputSizeBits = -1;
                for (int keymasterDigest : availableKeymasterDigests) {
                    int outputSizeBits = KeymasterUtils.getDigestOutputSizeBits(keymasterDigest);
                    if (outputSizeBits > maxDigestOutputSizeBits) {
                        // Digest too long (signature generation will fail) -- skip
                        continue;
                    }
                    if (bestKeymasterDigest == -1) {
                        // First digest tested -- definitely the best so far
                        bestKeymasterDigest = keymasterDigest;
                        bestDigestOutputSizeBits = outputSizeBits;
                    } else {
                        // The longer the better
                        if (outputSizeBits > bestDigestOutputSizeBits) {
                            bestKeymasterDigest = keymasterDigest;
                            bestDigestOutputSizeBits = outputSizeBits;
                        }
                    }
                }
                if (bestKeymasterDigest == -1) {
                    return null;
                }
                return KeyProperties.Digest.fromKeymasterToSignatureAlgorithmDigest(
                        bestKeymasterDigest) + "WithRSA";
            }
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    private static Set<Integer> getAvailableKeymasterSignatureDigests(
            @KeyProperties.DigestEnum String[] authorizedKeyDigests,
            @KeyProperties.DigestEnum String[] supportedSignatureDigests) {
        Set<Integer> authorizedKeymasterKeyDigests = new HashSet<Integer>();
        for (int keymasterDigest : KeyProperties.Digest.allToKeymaster(authorizedKeyDigests)) {
            authorizedKeymasterKeyDigests.add(keymasterDigest);
        }
        Set<Integer> supportedKeymasterSignatureDigests = new HashSet<Integer>();
        for (int keymasterDigest
                : KeyProperties.Digest.allToKeymaster(supportedSignatureDigests)) {
            supportedKeymasterSignatureDigests.add(keymasterDigest);
        }
        Set<Integer> result = new HashSet<Integer>(supportedKeymasterSignatureDigests);
        result.retainAll(authorizedKeymasterKeyDigests);
        return result;
    }
}
