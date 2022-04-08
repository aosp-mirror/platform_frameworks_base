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

package android.security.keystore;

import android.annotation.Nullable;
import android.security.Credentials;
import android.security.KeyPairGeneratorSpec;
import android.security.KeyStore;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keymaster.KeymasterDefs;

import com.android.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.org.bouncycastle.asn1.ASN1InputStream;
import com.android.org.bouncycastle.asn1.ASN1Integer;
import com.android.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.org.bouncycastle.asn1.DERBitString;
import com.android.org.bouncycastle.asn1.DERInteger;
import com.android.org.bouncycastle.asn1.DERNull;
import com.android.org.bouncycastle.asn1.DERSequence;
import com.android.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import com.android.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.org.bouncycastle.asn1.x509.Certificate;
import com.android.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.org.bouncycastle.asn1.x509.TBSCertificate;
import com.android.org.bouncycastle.asn1.x509.Time;
import com.android.org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import com.android.org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import com.android.org.bouncycastle.jce.X509Principal;
import com.android.org.bouncycastle.jce.provider.X509CertificateObject;
import com.android.org.bouncycastle.x509.X509V3CertificateGenerator;

import libcore.util.EmptyArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private KeyStore mKeyStore;

    private KeyGenParameterSpec mSpec;

    private String mEntryAlias;
    private int mEntryUid;
    private boolean mEncryptionAtRestRequired;
    private @KeyProperties.KeyAlgorithmEnum String mJcaKeyAlgorithm;
    private int mKeymasterAlgorithm = -1;
    private int mKeySizeBits;
    private SecureRandom mRng;

    private int[] mKeymasterPurposes;
    private int[] mKeymasterBlockModes;
    private int[] mKeymasterEncryptionPaddings;
    private int[] mKeymasterSignaturePaddings;
    private int[] mKeymasterDigests;

    private BigInteger mRSAPublicExponent;

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
                    KeyGenParameterSpec.Builder specBuilder;
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
                    encryptionAtRestRequired = legacySpec.isEncryptionRequired();
                    specBuilder.setUserAuthenticationRequired(false);

                    spec = specBuilder.build();
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
            mEntryUid = spec.getUid();
            mSpec = spec;
            mKeymasterAlgorithm = keymasterAlgorithm;
            mEncryptionAtRestRequired = encryptionAtRestRequired;
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
                KeymasterUtils.addUserAuthArgs(new KeymasterArguments(), mSpec);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new InvalidAlgorithmParameterException(e);
            }

            mJcaKeyAlgorithm = jcaKeyAlgorithm;
            mRng = random;
            mKeyStore = KeyStore.getInstance();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void resetAll() {
        mEntryAlias = null;
        mEntryUid = KeyStore.UID_SELF;
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
        mEncryptionAtRestRequired = false;
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
                if (publicExponent.compareTo(KeymasterArguments.UINT64_MAX_VALUE) > 0) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported RSA public exponent: " + publicExponent
                            + ". Maximum supported value: " + KeymasterArguments.UINT64_MAX_VALUE);
                }
                mRSAPublicExponent = publicExponent;
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
        if (mKeyStore == null || mSpec == null) {
            throw new IllegalStateException("Not initialized");
        }

        int flags = (mEncryptionAtRestRequired) ? KeyStore.FLAG_ENCRYPTED : 0;
        if (((flags & KeyStore.FLAG_ENCRYPTED) != 0)
                && (mKeyStore.state() != KeyStore.State.UNLOCKED)) {
            throw new IllegalStateException(
                    "Encryption at rest using secure lock screen credential requested for key pair"
                    + ", but the user has not yet entered the credential");
        }

        if (mSpec.isStrongBoxBacked()) {
            flags |= KeyStore.FLAG_STRONGBOX;
        }
        if (mSpec.isCriticalToDeviceEncryption()) {
            flags |= KeyStore.FLAG_CRITICAL_TO_DEVICE_ENCRYPTION;
        }

        byte[] additionalEntropy =
                KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                        mRng, (mKeySizeBits + 7) / 8);

        Credentials.deleteAllTypesForAlias(mKeyStore, mEntryAlias, mEntryUid);
        final String privateKeyAlias = Credentials.USER_PRIVATE_KEY + mEntryAlias;
        boolean success = false;
        try {
            generateKeystoreKeyPair(
                    privateKeyAlias, constructKeyGenerationArguments(), additionalEntropy, flags);
            KeyPair keyPair = loadKeystoreKeyPair(privateKeyAlias);

            storeCertificateChain(flags, createCertificateChain(privateKeyAlias, keyPair));

            success = true;
            return keyPair;
        } catch (ProviderException e) {
          if ((mSpec.getPurposes() & KeyProperties.PURPOSE_WRAP_KEY) != 0) {
              throw new SecureKeyImportUnavailableException(e);
          } else {
              throw e;
          }
        } finally {
            if (!success) {
                Credentials.deleteAllTypesForAlias(mKeyStore, mEntryAlias, mEntryUid);
            }
        }
    }

    private Iterable<byte[]> createCertificateChain(final String privateKeyAlias, KeyPair keyPair)
            throws ProviderException {
        byte[] challenge = mSpec.getAttestationChallenge();
        if (challenge != null) {
            KeymasterArguments args = new KeymasterArguments();
            args.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE, challenge);
            return getAttestationChain(privateKeyAlias, keyPair, args);
        }

        // Very short certificate chain in the non-attestation case.
        return Collections.singleton(generateSelfSignedCertificateBytes(keyPair));
    }

    private void generateKeystoreKeyPair(final String privateKeyAlias, KeymasterArguments args,
            byte[] additionalEntropy, final int flags) throws ProviderException {
        KeyCharacteristics resultingKeyCharacteristics = new KeyCharacteristics();
        int errorCode = mKeyStore.generateKey(privateKeyAlias, args, additionalEntropy,
                mEntryUid, flags, resultingKeyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            if (errorCode == KeyStore.HARDWARE_TYPE_UNAVAILABLE) {
                throw new StrongBoxUnavailableException("Failed to generate key pair");
            } else {
                throw new ProviderException(
                        "Failed to generate key pair", KeyStore.getKeyStoreException(errorCode));
            }
        }
    }

    private KeyPair loadKeystoreKeyPair(final String privateKeyAlias) throws ProviderException {
        try {
            KeyPair result  = AndroidKeyStoreProvider.loadAndroidKeyStoreKeyPairFromKeystore(
                    mKeyStore, privateKeyAlias, mEntryUid);
            if (!mJcaKeyAlgorithm.equalsIgnoreCase(result.getPrivate().getAlgorithm())) {
                throw new ProviderException(
                        "Generated key pair algorithm does not match requested algorithm: "
                                + result.getPrivate().getAlgorithm() + " vs " + mJcaKeyAlgorithm);
            }
            return result;
        } catch (UnrecoverableKeyException | KeyPermanentlyInvalidatedException e) {
            throw new ProviderException("Failed to load generated key pair from keystore", e);
        }
    }

    private KeymasterArguments constructKeyGenerationArguments() {
        KeymasterArguments args = new KeymasterArguments();
        args.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, mKeySizeBits);
        args.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm);
        args.addEnums(KeymasterDefs.KM_TAG_PURPOSE, mKeymasterPurposes);
        args.addEnums(KeymasterDefs.KM_TAG_BLOCK_MODE, mKeymasterBlockModes);
        args.addEnums(KeymasterDefs.KM_TAG_PADDING, mKeymasterEncryptionPaddings);
        args.addEnums(KeymasterDefs.KM_TAG_PADDING, mKeymasterSignaturePaddings);
        args.addEnums(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigests);

        KeymasterUtils.addUserAuthArgs(args, mSpec);
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_ACTIVE_DATETIME, mSpec.getKeyValidityStart());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                mSpec.getKeyValidityForOriginationEnd());
        args.addDateIfNotNull(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                mSpec.getKeyValidityForConsumptionEnd());
        addAlgorithmSpecificParameters(args);

        if (mSpec.isUniqueIdIncluded())
            args.addBoolean(KeymasterDefs.KM_TAG_INCLUDE_UNIQUE_ID);

        return args;
    }

    private void storeCertificateChain(final int flags, Iterable<byte[]> iterable)
            throws ProviderException {
        Iterator<byte[]> iter = iterable.iterator();
        storeCertificate(
                Credentials.USER_CERTIFICATE, iter.next(), flags, "Failed to store certificate");

        if (!iter.hasNext()) {
            return;
        }

        ByteArrayOutputStream certificateConcatenationStream = new ByteArrayOutputStream();
        while (iter.hasNext()) {
            byte[] data = iter.next();
            certificateConcatenationStream.write(data, 0, data.length);
        }

        storeCertificate(Credentials.CA_CERTIFICATE, certificateConcatenationStream.toByteArray(),
                flags, "Failed to store attestation CA certificate");
    }

    private void storeCertificate(String prefix, byte[] certificateBytes, final int flags,
            String failureMessage) throws ProviderException {
        int insertErrorCode = mKeyStore.insert(
                prefix + mEntryAlias,
                certificateBytes,
                mEntryUid,
                flags);
        if (insertErrorCode != KeyStore.NO_ERROR) {
            throw new ProviderException(failureMessage,
                    KeyStore.getKeyStoreException(insertErrorCode));
        }
    }

    private byte[] generateSelfSignedCertificateBytes(KeyPair keyPair) throws ProviderException {
        try {
            return generateSelfSignedCertificate(keyPair.getPrivate(), keyPair.getPublic())
                    .getEncoded();
        } catch (IOException | CertificateParsingException e) {
            throw new ProviderException("Failed to generate self-signed certificate", e);
        } catch (CertificateEncodingException e) {
            throw new ProviderException(
                    "Failed to obtain encoded form of self-signed certificate", e);
        }
    }

    private Iterable<byte[]> getAttestationChain(String privateKeyAlias,
            KeyPair keyPair, KeymasterArguments args)
                    throws ProviderException {
        KeymasterCertificateChain outChain = new KeymasterCertificateChain();
        int errorCode = mKeyStore.attestKey(privateKeyAlias, args, outChain);
        if (errorCode != KeyStore.NO_ERROR) {
            throw new ProviderException("Failed to generate attestation certificate chain",
                    KeyStore.getKeyStoreException(errorCode));
        }
        Collection<byte[]> chain = outChain.getCertificates();
        if (chain.size() < 2) {
            throw new ProviderException("Attestation certificate chain contained "
                    + chain.size() + " entries. At least two are required.");
        }
        return chain;
    }

    private void addAlgorithmSpecificParameters(KeymasterArguments keymasterArgs) {
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
                keymasterArgs.addUnsignedLong(
                        KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, mRSAPublicExponent);
                break;
            case KeymasterDefs.KM_ALGORITHM_EC:
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    private X509Certificate generateSelfSignedCertificate(PrivateKey privateKey,
            PublicKey publicKey) throws CertificateParsingException, IOException {
        String signatureAlgorithm =
                getCertificateSignatureAlgorithm(mKeymasterAlgorithm, mKeySizeBits, mSpec);
        if (signatureAlgorithm == null) {
            // Key cannot be used to sign a certificate
            return generateSelfSignedCertificateWithFakeSignature(publicKey);
        } else {
            // Key can be used to sign a certificate
            try {
                return generateSelfSignedCertificateWithValidSignature(
                        privateKey, publicKey, signatureAlgorithm);
            } catch (Exception e) {
                // Failed to generate the self-signed certificate with valid signature. Fall back
                // to generating a self-signed certificate with a fake signature. This is done for
                // all exception types because we prefer key pair generation to succeed and end up
                // producing a self-signed certificate with an invalid signature to key pair
                // generation failing.
                return generateSelfSignedCertificateWithFakeSignature(publicKey);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private X509Certificate generateSelfSignedCertificateWithValidSignature(
            PrivateKey privateKey, PublicKey publicKey, String signatureAlgorithm) throws Exception {
        final X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        certGen.setPublicKey(publicKey);
        certGen.setSerialNumber(mSpec.getCertificateSerialNumber());
        certGen.setSubjectDN(mSpec.getCertificateSubject());
        certGen.setIssuerDN(mSpec.getCertificateSubject());
        certGen.setNotBefore(mSpec.getCertificateNotBefore());
        certGen.setNotAfter(mSpec.getCertificateNotAfter());
        certGen.setSignatureAlgorithm(signatureAlgorithm);
        return certGen.generate(privateKey);
    }

    @SuppressWarnings("deprecation")
    private X509Certificate generateSelfSignedCertificateWithFakeSignature(
            PublicKey publicKey) throws IOException, CertificateParsingException {
        V3TBSCertificateGenerator tbsGenerator = new V3TBSCertificateGenerator();
        ASN1ObjectIdentifier sigAlgOid;
        AlgorithmIdentifier sigAlgId;
        byte[] signature;
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                sigAlgOid = X9ObjectIdentifiers.ecdsa_with_SHA256;
                sigAlgId = new AlgorithmIdentifier(sigAlgOid);
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(new DERInteger(0));
                v.add(new DERInteger(0));
                signature = new DERSequence().getEncoded();
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                sigAlgOid = PKCSObjectIdentifiers.sha256WithRSAEncryption;
                sigAlgId = new AlgorithmIdentifier(sigAlgOid, DERNull.INSTANCE);
                signature = new byte[1];
                break;
            default:
                throw new ProviderException("Unsupported key algorithm: " + mKeymasterAlgorithm);
        }

        try (ASN1InputStream publicKeyInfoIn = new ASN1InputStream(publicKey.getEncoded())) {
            tbsGenerator.setSubjectPublicKeyInfo(
                    SubjectPublicKeyInfo.getInstance(publicKeyInfoIn.readObject()));
        }
        tbsGenerator.setSerialNumber(new ASN1Integer(mSpec.getCertificateSerialNumber()));
        X509Principal subject =
                new X509Principal(mSpec.getCertificateSubject().getEncoded());
        tbsGenerator.setSubject(subject);
        tbsGenerator.setIssuer(subject);
        tbsGenerator.setStartDate(new Time(mSpec.getCertificateNotBefore()));
        tbsGenerator.setEndDate(new Time(mSpec.getCertificateNotAfter()));
        tbsGenerator.setSignature(sigAlgId);
        TBSCertificate tbsCertificate = tbsGenerator.generateTBSCertificate();

        ASN1EncodableVector result = new ASN1EncodableVector();
        result.add(tbsCertificate);
        result.add(sigAlgId);
        result.add(new DERBitString(signature));
        return new X509CertificateObject(Certificate.getInstance(new DERSequence(result)));
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
