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
import android.hardware.biometrics.BiometricManager;
import android.hardware.security.keymint.HardwareAuthenticatorType;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.SecurityLevel;
import android.security.GateKeeper;
import android.security.KeyStore2;
import android.security.KeyStoreParameter;
import android.security.KeyStoreSecurityLevel;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.security.keystore.SecureKeyImportUnavailableException;
import android.security.keystore.WrappedKeyEntry;
import android.system.keystore2.AuthenticatorSpec;
import android.system.keystore2.Domain;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.system.keystore2.ResponseCode;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore.Entry;
import java.security.KeyStore.LoadStoreParameter;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.ProtectionParameter;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;

/**
 * A java.security.KeyStore interface for the Android KeyStore. An instance of
 * it can be created via the {@link java.security.KeyStore#getInstance(String)
 * KeyStore.getInstance("AndroidKeyStore")} interface. This returns a
 * java.security.KeyStore backed by this "AndroidKeyStore" implementation.
 * <p>
 * This is built on top of Android's keystore daemon. The convention of alias
 * use is:
 * <p>
 * PrivateKeyEntry will have a Credentials.USER_PRIVATE_KEY as the private key,
 * Credentials.USER_CERTIFICATE as the first certificate in the chain (the one
 * that corresponds to the private key), and then a Credentials.CA_CERTIFICATE
 * entry which will have the rest of the chain concatenated in BER format.
 * <p>
 * TrustedCertificateEntry will just have a Credentials.CA_CERTIFICATE entry
 * with a single certificate.
 *
 * @hide
 */
public class AndroidKeyStoreSpi extends KeyStoreSpi {
    public static final String TAG = "AndroidKeyStoreSpi";
    public static final String NAME = "AndroidKeyStore";

    private KeyStore2 mKeyStore;
    private @KeyProperties.Namespace int mNamespace = KeyProperties.NAMESPACE_APPLICATION;

    @Override
    public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException,
            UnrecoverableKeyException {
        try {
            return AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore(mKeyStore,
                                                                              alias,
                                                                              mNamespace);
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new UnrecoverableKeyException(e.getMessage());
        } catch (UnrecoverableKeyException e) {
            Throwable cause = e.getCause();
            if (cause instanceof android.security.KeyStoreException) {
                if (((android.security.KeyStoreException) cause).getErrorCode()
                        == ResponseCode.KEY_NOT_FOUND) {
                    return null;
                }
            }
            throw e;
        }
    }

    /**
     * Make a key descriptor from the given alias and the mNamespace member.
     * If mNamespace is -1 it sets the domain field to {@link Domain#APP} and {@link Domain#SELINUX}
     * otherwise. The blob field is always set to null and the alias field to {@code alias}
     * @param alias The alias of the new key descriptor.
     * @return A new key descriptor.
     */
    private KeyDescriptor makeKeyDescriptor(@NonNull String alias) {
        KeyDescriptor descriptor = new KeyDescriptor();
        descriptor.domain = getTargetDomain();
        descriptor.nspace = mNamespace; // ignored if Domain.App;
        descriptor.alias = alias;
        descriptor.blob = null;
        return descriptor;
    }

    private @Domain int getTargetDomain() {
        return mNamespace == KeyProperties.NAMESPACE_APPLICATION
                ? Domain.APP
                : Domain.SELINUX;
    }
    private KeyEntryResponse getKeyMetadata(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        KeyDescriptor descriptor = makeKeyDescriptor(alias);

        try {
            return mKeyStore.getKeyEntry(descriptor);
        } catch (android.security.KeyStoreException e) {
            if (e.getErrorCode() != ResponseCode.KEY_NOT_FOUND) {
                Log.w(TAG, "Could not get key metadata from Keystore.", e);
            }
            return null;
        }
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        KeyEntryResponse response = getKeyMetadata(alias);

        if (response == null || response.metadata.certificate == null) {
            return null;
        }

        final X509Certificate leaf = (X509Certificate) toCertificate(response.metadata.certificate);
        if (leaf == null) {
            return null;
        }

        final Certificate[] caList;

        final byte[] caBytes = response.metadata.certificateChain;

        if (caBytes != null) {
            final Collection<X509Certificate> caChain = toCertificates(caBytes);

            caList = new Certificate[caChain.size() + 1];

            final Iterator<X509Certificate> it = caChain.iterator();
            int i = 1;
            while (it.hasNext()) {
                caList[i++] = it.next();
            }
        } else {
            caList = new Certificate[1];
        }

        caList[0] = leaf;

        return caList;
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        KeyEntryResponse response = getKeyMetadata(alias);

        if (response == null) {
            return null;
        }

        byte[] encodedCert = response.metadata.certificate;
        if (encodedCert != null) {
            return toCertificate(encodedCert);
        }

        encodedCert = response.metadata.certificateChain;
        if (encodedCert != null) {
            return toCertificate(encodedCert);
        }

        // This entry/alias does not contain a certificate.
        return null;
    }

    static X509Certificate toCertificate(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificate in keystore", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<X509Certificate> toCertificates(byte[] bytes) {
        try {
            final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            return (Collection<X509Certificate>) certFactory.generateCertificates(
                            new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            Log.w(NAME, "Couldn't parse certificates in keystore", e);
            return new ArrayList<X509Certificate>();
        }
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        KeyEntryResponse response = getKeyMetadata(alias);

        if (response == null) {
            return null;
        }

        if (response.metadata.modificationTimeMs == -1) {
            return null;
        }
        return new Date(response.metadata.modificationTimeMs);
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
            throws KeyStoreException {
        if ((password != null) && (password.length > 0)) {
            throw new KeyStoreException("entries cannot be protected with passwords");
        }

        if (key instanceof PrivateKey) {
            setPrivateKeyEntry(alias, (PrivateKey) key, chain, null);
        } else if (key instanceof SecretKey) {
            setSecretKeyEntry(alias, (SecretKey) key, null);
        } else {
            throw new KeyStoreException("Only PrivateKey and SecretKey are supported");
        }
    }

    private static KeyProtection getLegacyKeyProtectionParameter(PrivateKey key)
            throws KeyStoreException {
        String keyAlgorithm = key.getAlgorithm();
        KeyProtection.Builder specBuilder;
        if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(keyAlgorithm)) {
            specBuilder =
                    new KeyProtection.Builder(
                            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);
            // Authorized to be used with any digest (including no digest).
            // MD5 was never offered for Android Keystore for ECDSA.
            specBuilder.setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA1,
                    KeyProperties.DIGEST_SHA224,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512);
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(keyAlgorithm)) {
            specBuilder =
                    new KeyProtection.Builder(
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
        } else {
            throw new KeyStoreException("Unsupported key algorithm: " + keyAlgorithm);
        }
        specBuilder.setUserAuthenticationRequired(false);

        return specBuilder.build();
    }

    private void setPrivateKeyEntry(String alias, PrivateKey key, Certificate[] chain,
            java.security.KeyStore.ProtectionParameter param) throws KeyStoreException {
        @SecurityLevel int securitylevel = SecurityLevel.TRUSTED_ENVIRONMENT;
        int flags = 0;
        KeyProtection spec;
        if (param == null) {
            spec = getLegacyKeyProtectionParameter(key);
        } else if (param instanceof KeyStoreParameter) {
            spec = getLegacyKeyProtectionParameter(key);
            KeyStoreParameter legacySpec = (KeyStoreParameter) param;
        } else if (param instanceof KeyProtection) {
            spec = (KeyProtection) param;
            if (spec.isCriticalToDeviceEncryption()) {
                // This key is should not be bound to the LSKF even if it is auth bound.
                // This indicates that this key is used in the derivation for of the
                // master key, that is used for the LSKF binding of other auth bound
                // keys. This breaks up a circular dependency while retaining logical
                // authentication binding of the key.
                flags |= IKeystoreSecurityLevel
                        .KEY_FLAG_AUTH_BOUND_WITHOUT_CRYPTOGRAPHIC_LSKF_BINDING;
            }

            if (spec.isStrongBoxBacked()) {
                securitylevel = SecurityLevel.STRONGBOX;
            }
        } else {
            throw new KeyStoreException(
                    "Unsupported protection parameter class:" + param.getClass().getName()
                    + ". Supported: " + KeyProtection.class.getName() + ", "
                    + KeyStoreParameter.class.getName());
        }

        // Make sure the chain exists since this is a PrivateKey
        if ((chain == null) || (chain.length == 0)) {
            throw new KeyStoreException("Must supply at least one Certificate with PrivateKey");
        }

        // Do chain type checking.
        X509Certificate[] x509chain = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) {
            if (!"X.509".equals(chain[i].getType())) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            if (!(chain[i] instanceof X509Certificate)) {
                throw new KeyStoreException("Certificates must be in X.509 format: invalid cert #"
                        + i);
            }

            x509chain[i] = (X509Certificate) chain[i];
        }

        final byte[] userCertBytes;
        try {
            userCertBytes = x509chain[0].getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException("Failed to encode certificate #0", e);
        }

        /*
         * If we have a chain, store it in the CA certificate slot for this
         * alias as concatenated DER-encoded certificates. These can be
         * deserialized by {@link CertificateFactory#generateCertificates}.
         */
        final byte[] chainBytes;
        if (chain.length > 1) {
            /*
             * The chain is passed in as {user_cert, ca_cert_1, ca_cert_2, ...}
             * so we only need the certificates starting at index 1.
             */
            final byte[][] certsBytes = new byte[x509chain.length - 1][];
            int totalCertLength = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                try {
                    certsBytes[i] = x509chain[i + 1].getEncoded();
                    totalCertLength += certsBytes[i].length;
                } catch (CertificateEncodingException e) {
                    throw new KeyStoreException("Failed to encode certificate #" + i, e);
                }
            }

            /*
             * Serialize this into one byte array so we can later call
             * CertificateFactory#generateCertificates to recover them.
             */
            chainBytes = new byte[totalCertLength];
            int outputOffset = 0;
            for (int i = 0; i < certsBytes.length; i++) {
                final int certLength = certsBytes[i].length;
                System.arraycopy(certsBytes[i], 0, chainBytes, outputOffset, certLength);
                outputOffset += certLength;
                certsBytes[i] = null;
            }
        } else {
            chainBytes = null;
        }

        @Domain int targetDomain = getTargetDomain();

        // If the given key is an AndroidKeyStorePrivateKey, we attempt to update
        // its subcomponents with the given certificate and certificate chain.
        if (key instanceof AndroidKeyStorePrivateKey) {
            AndroidKeyStoreKey ksKey = (AndroidKeyStoreKey) key;
            KeyDescriptor descriptor = ksKey.getUserKeyDescriptor();

            // This throws if the request cannot replace the entry.
            assertCanReplace(alias, targetDomain, mNamespace, descriptor);

            try {
                mKeyStore.updateSubcomponents(
                        ((AndroidKeyStorePrivateKey) key).getKeyIdDescriptor(),
                        userCertBytes, chainBytes);
            } catch (android.security.KeyStoreException e) {
                throw new KeyStoreException("Failed to store certificate and certificate chain", e);
            }
            return;
        }

        // Make sure the PrivateKey format is the one we support.
        final String keyFormat = key.getFormat();
        if ((keyFormat == null) || (!"PKCS#8".equals(keyFormat))) {
            throw new KeyStoreException(
                    "Unsupported private key export format: " + keyFormat
                    + ". Only private keys which export their key material in PKCS#8 format are"
                    + " supported.");
        }

        // Make sure we can actually encode the key.
        byte[] pkcs8EncodedPrivateKeyBytes = key.getEncoded();
        if (pkcs8EncodedPrivateKeyBytes == null) {
            throw new KeyStoreException("Private key did not export any key material");
        }

        final List<KeyParameter> importArgs = new ArrayList<>();

        try {
            importArgs.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_ALGORITHM,
                    KeyProperties.KeyAlgorithm.toKeymasterAsymmetricKeyAlgorithm(
                            key.getAlgorithm()))
            );
            KeyStore2ParameterUtils.forEachSetFlag(spec.getPurposes(), (purpose) -> {
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_PURPOSE,
                        KeyProperties.Purpose.toKeymaster(purpose)
                ));
            });
            if (spec.isDigestsSpecified()) {
                for (String digest : spec.getDigests()) {
                    importArgs.add(KeyStore2ParameterUtils.makeEnum(
                            KeymasterDefs.KM_TAG_DIGEST,
                            KeyProperties.Digest.toKeymaster(digest)
                    ));
                }
            }
            for (String blockMode : spec.getBlockModes()) {
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_BLOCK_MODE,
                        KeyProperties.BlockMode.toKeymaster(blockMode)
                ));
            }
            int[] keymasterEncryptionPaddings =
                    KeyProperties.EncryptionPadding.allToKeymaster(
                            spec.getEncryptionPaddings());
            if (((spec.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0)
                    && (spec.isRandomizedEncryptionRequired())) {
                for (int keymasterPadding : keymasterEncryptionPaddings) {
                    if (!KeymasterUtils
                            .isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto(
                                    keymasterPadding)) {
                        throw new KeyStoreException(
                                "Randomized encryption (IND-CPA) required but is violated by"
                                        + " encryption padding mode: "
                                        + KeyProperties.EncryptionPadding.fromKeymaster(
                                        keymasterPadding)
                                        + ". See KeyProtection documentation.");
                    }
                }
            }
            for (int padding : keymasterEncryptionPaddings) {
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_PADDING,
                        padding
                ));
            }
            for (String padding : spec.getSignaturePaddings()) {
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_PADDING,
                        KeyProperties.SignaturePadding.toKeymaster(padding)
                ));
            }
            KeyStore2ParameterUtils.addUserAuthArgs(importArgs, spec);
            if (spec.getKeyValidityStart() != null) {
                importArgs.add(KeyStore2ParameterUtils.makeDate(
                        KeymasterDefs.KM_TAG_ACTIVE_DATETIME, spec.getKeyValidityStart()
                ));
            }
            if (spec.getKeyValidityForOriginationEnd() != null) {
                importArgs.add(KeyStore2ParameterUtils.makeDate(
                        KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                        spec.getKeyValidityForOriginationEnd()
                ));
            }
            if (spec.getKeyValidityForConsumptionEnd() != null) {
                importArgs.add(KeyStore2ParameterUtils.makeDate(
                        KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                        spec.getKeyValidityForConsumptionEnd()
                ));
            }
            if (spec.getMaxUsageCount() != KeyProperties.UNRESTRICTED_USAGE_COUNT) {
                importArgs.add(KeyStore2ParameterUtils.makeInt(
                        KeymasterDefs.KM_TAG_USAGE_COUNT_LIMIT,
                        spec.getMaxUsageCount()
                ));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new KeyStoreException(e);
        }

        try {
            KeyStoreSecurityLevel securityLevelInterface = mKeyStore.getSecurityLevel(
                    securitylevel);

            KeyDescriptor descriptor = makeKeyDescriptor(alias);

            KeyMetadata metadata = securityLevelInterface.importKey(descriptor, null,
                    importArgs, flags, pkcs8EncodedPrivateKeyBytes);

            try {
                mKeyStore.updateSubcomponents(metadata.key, userCertBytes, chainBytes);
            } catch (android.security.KeyStoreException e) {
                mKeyStore.deleteKey(metadata.key);
                throw new KeyStoreException("Failed to store certificate and certificate chain", e);
            }

        } catch (android.security.KeyStoreException e) {
            throw new KeyStoreException("Failed to store private key", e);
        }
    }

    private static void assertCanReplace(String alias, @Domain int targetDomain,
            int targetNamespace, KeyDescriptor descriptor)
            throws KeyStoreException {
        // If
        //  * the alias does not match, or
        //  * the domain does not match, or
        //  * the domain is Domain.SELINUX and the namespaces don not match,
        // then the designated key location is not equivalent to the location of the
        // given key parameter and cannot be updated.
        //
        // Note: mNamespace == KeyProperties.NAMESPACE_APPLICATION implies that the target domain
        // is Domain.APP and Domain.SELINUX is the target domain otherwise.
        if (alias != descriptor.alias
                || descriptor.domain != targetDomain
                || (descriptor.domain == Domain.SELINUX && descriptor.nspace != targetNamespace)) {
            throw new KeyStoreException("Can only replace keys with same alias: " + alias
                    + " != " + descriptor.alias + " in the same target domain: " + targetDomain
                    + " != " + descriptor.domain
                    + (targetDomain == Domain.SELINUX ? " in the same target namespace: "
                    + targetNamespace + " != " + descriptor.nspace : "")
            );
        }
    }

    private void setSecretKeyEntry(String alias, SecretKey key,
            java.security.KeyStore.ProtectionParameter param)
            throws KeyStoreException {
        if ((param != null) && (!(param instanceof KeyProtection))) {
            throw new KeyStoreException(
                    "Unsupported protection parameter class: " + param.getClass().getName()
                    + ". Supported: " + KeyProtection.class.getName());
        }
        KeyProtection params = (KeyProtection) param;

        @SecurityLevel int securityLevel = params.isStrongBoxBacked() ? SecurityLevel.STRONGBOX :
                SecurityLevel.TRUSTED_ENVIRONMENT;
        @Domain int targetDomain = (getTargetDomain());

        if (key instanceof AndroidKeyStoreSecretKey) {
            String keyAliasInKeystore =
                    ((AndroidKeyStoreSecretKey) key).getUserKeyDescriptor().alias;

            KeyDescriptor descriptor = ((AndroidKeyStoreSecretKey) key).getUserKeyDescriptor();

            // This throws if the request cannot replace the existing key.
            assertCanReplace(alias, targetDomain, mNamespace, descriptor);

            // This is the entry where this key is already stored. No need to do anything.
            if (params != null) {
                throw new KeyStoreException("Modifying KeyStore-backed key using protection"
                        + " parameters not supported");
            }
            return;
        }

        if (params == null) {
            throw new KeyStoreException(
                    "Protection parameters must be specified when importing a symmetric key");
        }

        // Not a KeyStore-backed secret key -- import its key material into keystore.
        String keyExportFormat = key.getFormat();
        if (keyExportFormat == null) {
            throw new KeyStoreException(
                    "Only secret keys that export their key material are supported");
        } else if (!"RAW".equals(keyExportFormat)) {
            throw new KeyStoreException(
                    "Unsupported secret key material export format: " + keyExportFormat);
        }
        byte[] keyMaterial = key.getEncoded();
        if (keyMaterial == null) {
            throw new KeyStoreException("Key did not export its key material despite supporting"
                    + " RAW format export");
        }

        final List<KeyParameter> importArgs = new ArrayList<>();

        try {
            int keymasterAlgorithm =
                    KeyProperties.KeyAlgorithm.toKeymasterSecretKeyAlgorithm(
                            key.getAlgorithm());

            importArgs.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_ALGORITHM,
                    keymasterAlgorithm
            ));

            if (keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC) {
                // JCA HMAC key algorithm implies a digest (e.g., HmacSHA256 key algorithm
                // implies SHA-256 digest). Because keymaster HMAC key is authorized only for one
                // digest, we don't let import parameters override the digest implied by the key.
                // If the parameters specify digests at all, they must specify only one digest, the
                // only implied by key algorithm.
                int keymasterImpliedDigest =
                        KeyProperties.KeyAlgorithm.toKeymasterDigest(key.getAlgorithm());
                if (keymasterImpliedDigest == -1) {
                    throw new ProviderException(
                            "HMAC key algorithm digest unknown for key algorithm "
                                    + key.getAlgorithm());
                }

                if (params.isDigestsSpecified()) {
                    // Digest(s) explicitly specified in params -- check that the list consists of
                    // exactly one digest, the one implied by key algorithm.
                    int[] keymasterDigestsFromParams =
                            KeyProperties.Digest.allToKeymaster(params.getDigests());
                    if ((keymasterDigestsFromParams.length != 1)
                            || (keymasterDigestsFromParams[0] != keymasterImpliedDigest)) {
                        throw new KeyStoreException(
                                "Unsupported digests specification: "
                                        + Arrays.asList(params.getDigests()) + ". Only "
                                        + KeyProperties.Digest.fromKeymaster(keymasterImpliedDigest)
                                        + " supported for HMAC key algorithm "
                                        + key.getAlgorithm());
                    }
                }
                int outputBits = KeymasterUtils.getDigestOutputSizeBits(keymasterImpliedDigest);
                if (outputBits == -1) {
                    throw new ProviderException(
                            "HMAC key authorized for unsupported digest: "
                                    + KeyProperties.Digest.fromKeymaster(keymasterImpliedDigest));
                }
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_DIGEST, keymasterImpliedDigest
                ));
                importArgs.add(KeyStore2ParameterUtils.makeInt(
                        KeymasterDefs.KM_TAG_MIN_MAC_LENGTH, outputBits
                ));
            } else {
                if (params.isDigestsSpecified()) {
                    for (String digest : params.getDigests()) {
                        importArgs.add(KeyStore2ParameterUtils.makeEnum(
                                KeymasterDefs.KM_TAG_DIGEST,
                                KeyProperties.Digest.toKeymaster(digest)
                        ));
                    }
                }
            }

            KeyStore2ParameterUtils.forEachSetFlag(params.getPurposes(), (purpose) -> {
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_PURPOSE,
                        KeyProperties.Purpose.toKeymaster(purpose)
                ));
            });

            boolean indCpa = false;
            if ((params.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0) {
                if (((KeyProtection) param).isRandomizedEncryptionRequired()) {
                    indCpa = true;
                } else {
                    importArgs.add(KeyStore2ParameterUtils.makeBool(
                            KeymasterDefs.KM_TAG_CALLER_NONCE
                    ));
                }
            }

            for (String blockMode : params.getBlockModes()) {
                int keymasterBlockMode = KeyProperties.BlockMode.toKeymaster(blockMode);
                if (indCpa
                        && !KeymasterUtils.isKeymasterBlockModeIndCpaCompatibleWithSymmetricCrypto(
                                keymasterBlockMode)) {
                    throw new KeyStoreException(
                            "Randomized encryption (IND-CPA) required but may be violated by"
                                    + " block mode: " + blockMode
                                    + ". See KeyProtection documentation.");

                }
                if (keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_AES
                        && keymasterBlockMode == KeymasterDefs.KM_MODE_GCM) {
                    importArgs.add(KeyStore2ParameterUtils.makeInt(
                            KeymasterDefs.KM_TAG_MIN_MAC_LENGTH,
                            AndroidKeyStoreAuthenticatedAESCipherSpi.GCM
                                    .MIN_SUPPORTED_TAG_LENGTH_BITS
                    ));
                }
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_BLOCK_MODE,
                        keymasterBlockMode
                ));
            }

            if (params.getSignaturePaddings().length > 0) {
                throw new KeyStoreException("Signature paddings not supported for symmetric keys");
            }

            for (String padding : params.getEncryptionPaddings()) {
                importArgs.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_PADDING,
                        KeyProperties.EncryptionPadding.toKeymaster(padding)
                ));
            }

            KeyStore2ParameterUtils.addUserAuthArgs(importArgs, params);

            if (params.getKeyValidityStart() != null) {
                importArgs.add(KeyStore2ParameterUtils.makeDate(
                        KeymasterDefs.KM_TAG_ACTIVE_DATETIME, params.getKeyValidityStart()
                ));
            }
            if (params.getKeyValidityForOriginationEnd() != null) {
                importArgs.add(KeyStore2ParameterUtils.makeDate(
                        KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                        params.getKeyValidityForOriginationEnd()
                ));
            }
            if (params.getKeyValidityForConsumptionEnd() != null) {
                importArgs.add(KeyStore2ParameterUtils.makeDate(
                        KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                        params.getKeyValidityForConsumptionEnd()
                ));
            }
            if (params.getMaxUsageCount() != KeyProperties.UNRESTRICTED_USAGE_COUNT) {
                importArgs.add(KeyStore2ParameterUtils.makeInt(
                        KeymasterDefs.KM_TAG_USAGE_COUNT_LIMIT,
                        params.getMaxUsageCount()
                ));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new KeyStoreException(e);
        }

        int flags = 0;
        if (params.isCriticalToDeviceEncryption()) {
            flags |= IKeystoreSecurityLevel.KEY_FLAG_AUTH_BOUND_WITHOUT_CRYPTOGRAPHIC_LSKF_BINDING;
        }

        try {
            KeyStoreSecurityLevel securityLevelInterface = mKeyStore.getSecurityLevel(
                    securityLevel);

            KeyDescriptor descriptor = makeKeyDescriptor(alias);

            securityLevelInterface.importKey(descriptor, null /* TODO attestationKey */,
                    importArgs, flags, keyMaterial);
        } catch (android.security.KeyStoreException e) {
            throw new KeyStoreException("Failed to import secret key.", e);
        }
    }

    private void setWrappedKeyEntry(String alias, WrappedKeyEntry entry,
            java.security.KeyStore.ProtectionParameter param) throws KeyStoreException {
        if (param != null) {
            throw new KeyStoreException("Protection parameters are specified inside wrapped keys");
        }

        byte[] maskingKey = new byte[32];

        String[] parts = entry.getTransformation().split("/");

        List<KeyParameter> args = new ArrayList<>();

        String algorithm = parts[0];
        if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(algorithm)) {
            args.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_ALGORITHM,
                    KeymasterDefs.KM_ALGORITHM_RSA
            ));
        } else {
            throw new KeyStoreException("Algorithm \"" + algorithm + "\" not supported for "
                    + "wrapping. Only RSA wrapping keys are supported.");
        }

        if (parts.length > 1) {
            String mode = parts[1];
            args.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_BLOCK_MODE,
                    KeyProperties.BlockMode.toKeymaster(mode)
            ));
        }

        if (parts.length > 2) {
            @KeyProperties.EncryptionPaddingEnum int padding =
                    KeyProperties.EncryptionPadding.toKeymaster(parts[2]);
            if (padding != KeymasterDefs.KM_PAD_NONE) {
                args.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_PADDING,
                        padding
                ));
            }
        }

        KeyGenParameterSpec spec = (KeyGenParameterSpec) entry.getAlgorithmParameterSpec();
        if (spec.isDigestsSpecified()) {
            @KeyProperties.DigestEnum int digest =
                    KeyProperties.Digest.toKeymaster(spec.getDigests()[0]);
            if (digest != KeymasterDefs.KM_DIGEST_NONE) {
                args.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_DIGEST,
                        digest
                ));
            }
        }

        KeyDescriptor wrappingkey = makeKeyDescriptor(entry.getWrappingKeyAlias());

        KeyEntryResponse response = null;
        try {
            response = mKeyStore.getKeyEntry(wrappingkey);
        } catch (android.security.KeyStoreException e) {
            throw new KeyStoreException("Failed to import wrapped key. Keystore error code: "
                    + e.getErrorCode(), e);
        }

        KeyDescriptor wrappedKey = makeKeyDescriptor(alias);

        KeyStoreSecurityLevel securityLevel = new KeyStoreSecurityLevel(response.iSecurityLevel);

        final BiometricManager bm = android.app.AppGlobals.getInitialApplication()
                .getSystemService(BiometricManager.class);

        long[] biometricSids = bm.getAuthenticatorIds();

        List<AuthenticatorSpec> authenticatorSpecs = new ArrayList<>();

        AuthenticatorSpec authenticatorSpec = new AuthenticatorSpec();
        authenticatorSpec.authenticatorType = HardwareAuthenticatorType.PASSWORD;
        authenticatorSpec.authenticatorId = GateKeeper.getSecureUserId();
        authenticatorSpecs.add(authenticatorSpec);

        for (long sid : biometricSids) {
            AuthenticatorSpec authSpec = new AuthenticatorSpec();
            authSpec.authenticatorType = HardwareAuthenticatorType.FINGERPRINT;
            authSpec.authenticatorId = sid;
            authenticatorSpecs.add(authSpec);
        }

        try {
            securityLevel.importWrappedKey(
                    wrappedKey, wrappingkey,
                    entry.getWrappedKeyBytes(),
                    null /* masking key is set to 32 bytes if null is given here */,
                    args,
                    authenticatorSpecs.toArray(new AuthenticatorSpec[0]));
        } catch (android.security.KeyStoreException e) {
            switch (e.getErrorCode()) {
                case KeymasterDefs.KM_ERROR_UNIMPLEMENTED: {
                    throw new SecureKeyImportUnavailableException("Could not import wrapped key");
                }
                default:
                    throw new KeyStoreException("Failed to import wrapped key. Keystore error "
                            + "code: " + e.getErrorCode(), e);
            }
        }
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] userKey, Certificate[] chain)
            throws KeyStoreException {
        throw new KeyStoreException("Operation not supported because key encoding is unknown");
    }

    /**
     * This function sets a trusted certificate entry. It fails if the given
     * alias is already taken by an actual key entry. However, if the entry is a
     * trusted certificate it will get silently replaced.
     * @param alias the alias name
     * @param cert the certificate
     *
     * @throws KeyStoreException if the alias is already taken by a secret or private
     *         key entry.
     * @throws KeyStoreException with a nested {@link CertificateEncodingException}
     *         if the {@code cert.getEncoded()} throws.
     * @throws KeyStoreException with a nested {@link android.security.KeyStoreException} if
     *         something went wrong while inserting the certificate into keystore.
     * @throws NullPointerException if cert or alias is null.
     *
     * @hide
     */
    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
        if (isKeyEntry(alias)) {
            throw new KeyStoreException("Entry exists and is not a trusted certificate");
        }

        // We can't set something to null.
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }

        final byte[] encoded;
        try {
            encoded = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new KeyStoreException(e);
        }

        try {
            mKeyStore.updateSubcomponents(makeKeyDescriptor(alias),
                    null /* publicCert - unused when used as pure certificate store. */,
                    encoded);
        } catch (android.security.KeyStoreException e) {
            throw new KeyStoreException("Couldn't insert certificate.", e);
        }
    }

    @Override
    public void engineDeleteEntry(String alias) throws KeyStoreException {
        KeyDescriptor descriptor = makeKeyDescriptor(alias);
        try {
            mKeyStore.deleteKey(descriptor);
        } catch (android.security.KeyStoreException e) {
            if (e.getErrorCode() != ResponseCode.KEY_NOT_FOUND) {
                throw new KeyStoreException("Failed to delete entry: " + alias, e);
            }
        }
    }

    private Set<String> getUniqueAliases() {

        try {
            final KeyDescriptor[] keys = mKeyStore.list(
                    getTargetDomain(),
                    mNamespace
            );
            final Set<String> aliases = new HashSet<>(keys.length);
            for (KeyDescriptor d : keys) {
                aliases.add(d.alias);
            }
            return aliases;
        } catch (android.security.KeyStoreException e) {
            Log.e(TAG, "Failed to list keystore entries.", e);
            return null;
        }
    }

    @Override
    public Enumeration<String> engineAliases() {
        return Collections.enumeration(getUniqueAliases());
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        return getKeyMetadata(alias) != null;
    }

    @Override
    public int engineSize() {
        return getUniqueAliases().size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return isKeyEntry(alias);
    }

    private boolean isKeyEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }

        KeyEntryResponse response = getKeyMetadata(alias);
        // If response is null, there is no such entry.
        // If response.iSecurityLevel is null, there is no private or secret key material stored.
        return response != null && response.iSecurityLevel != null;
    }


    @Override
    public boolean engineIsCertificateEntry(String alias) {
        if (alias == null) {
            throw new NullPointerException("alias == null");
        }
        KeyEntryResponse response = getKeyMetadata(alias);
        // If response == null there is no such entry.
        // If there is no certificateChain, then this is not a certificate entry.
        // If there is a private key entry, this is the certificate chain for that
        // key entry and not a CA certificate entry.
        return response != null
                && response.metadata.certificateChain != null
                && response.iSecurityLevel == null;
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        if (cert == null) {
            return null;
        }
        if (!"X.509".equalsIgnoreCase(cert.getType())) {
            Log.e(TAG, "In engineGetCertificateAlias: only X.509 certificates are supported.");
            return null;
        }
        byte[] targetCertBytes;
        try {
            targetCertBytes = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            Log.e(TAG, "While trying to get the alias for a certificate.", e);
            return null;
        }
        if (targetCertBytes == null) {
            return null;
        }

        KeyDescriptor[] keyDescriptors = null;
        try {
            keyDescriptors = mKeyStore.list(
                    getTargetDomain(),
                    mNamespace
            );
        } catch (android.security.KeyStoreException e) {
            Log.w(TAG, "Failed to get list of keystore entries.", e);
        }

        String caAlias = null;
        for (KeyDescriptor d : keyDescriptors) {
            KeyEntryResponse response = getKeyMetadata(d.alias);
            if (response == null) {
                continue;
            }
            /*
             * The KeyStoreSpi documentation says to only compare the first certificate in the
             * chain which is equivalent to the {@code response.metadata.certificate} field.
             * So we look for a hit in this field first. For pure CA certificate entries,
             * we check the {@code response.metadata.certificateChain} field. But we only
             * return a CA alias if there was no hit in the certificate field of any other
             * entry.
             */
            if (response.metadata.certificate != null) {
                if (Arrays.equals(response.metadata.certificate, targetCertBytes)) {
                    return d.alias;
                }
            } else if (response.metadata.certificateChain != null && caAlias == null) {
                if (Arrays.equals(response.metadata.certificateChain, targetCertBytes)) {
                    caAlias =  d.alias;
                }
            }
        }
        return caAlias;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        throw new UnsupportedOperationException("Can not serialize AndroidKeyStore to OutputStream");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        if (stream != null) {
            throw new IllegalArgumentException("InputStream not supported");
        }

        if (password != null) {
            throw new IllegalArgumentException("password not supported");
        }

        // Unfortunate name collision.
        mKeyStore = KeyStore2.getInstance();
        mNamespace = KeyProperties.NAMESPACE_APPLICATION;
    }

    @Override
    public void engineLoad(LoadStoreParameter param) throws IOException,
            NoSuchAlgorithmException, CertificateException {
        @KeyProperties.Namespace int namespace = KeyProperties.NAMESPACE_APPLICATION;
        if (param != null) {
            if (param instanceof AndroidKeyStoreLoadStoreParameter) {
                namespace = ((AndroidKeyStoreLoadStoreParameter) param).getNamespace();
            } else {
                throw new IllegalArgumentException(
                        "Unsupported param type: " + param.getClass());
            }
        }
        mKeyStore = KeyStore2.getInstance();
        mNamespace = namespace;
    }

    @Override
    public void engineSetEntry(String alias, Entry entry, ProtectionParameter param)
            throws KeyStoreException {
        if (entry == null) {
            throw new KeyStoreException("entry == null");
        }

        if (entry instanceof java.security.KeyStore.TrustedCertificateEntry) {
            java.security.KeyStore.TrustedCertificateEntry trE =
                    (java.security.KeyStore.TrustedCertificateEntry) entry;
            // engineSetCertificateEntry does not overwrite if the existing entry
            // is a key entry, but the semantic of engineSetEntry is such that it
            // overwrites any existing entry. Thus we delete any possible existing
            // entry by this alias.
            engineDeleteEntry(alias);
            engineSetCertificateEntry(alias, trE.getTrustedCertificate());
            return;
        }

        if (entry instanceof PrivateKeyEntry) {
            PrivateKeyEntry prE = (PrivateKeyEntry) entry;
            setPrivateKeyEntry(alias, prE.getPrivateKey(), prE.getCertificateChain(), param);
        } else if (entry instanceof SecretKeyEntry) {
            SecretKeyEntry secE = (SecretKeyEntry) entry;
            setSecretKeyEntry(alias, secE.getSecretKey(), param);
        } else if (entry instanceof WrappedKeyEntry) {
            WrappedKeyEntry wke = (WrappedKeyEntry) entry;
            setWrappedKeyEntry(alias, wke, param);
        } else {
            throw new KeyStoreException(
                    "Entry must be a PrivateKeyEntry, SecretKeyEntry, WrappedKeyEntry "
                            + "or TrustedCertificateEntry; was " + entry);
        }
    }
}
