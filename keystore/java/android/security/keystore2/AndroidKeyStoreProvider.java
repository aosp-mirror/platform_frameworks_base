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
import android.security.KeyStore;
import android.security.KeyStore2;
import android.security.KeyStoreSecurityLevel;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyStoreCryptoOperation;
import android.system.keystore2.Authorization;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.system.keystore2.ResponseCode;

import java.security.KeyPair;
import java.security.Provider;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * A provider focused on providing JCA interfaces for the Android KeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreProvider extends Provider {
    private static final String PROVIDER_NAME = "AndroidKeyStore";

    // IMPLEMENTATION NOTE: Class names are hard-coded in this provider to avoid loading these
    // classes when this provider is instantiated and installed early on during each app's
    // initialization process.
    //
    // Crypto operations operating on the AndroidKeyStore keys must not be offered by this provider.
    // Instead, they need to be offered by AndroidKeyStoreBCWorkaroundProvider. See its Javadoc
    // for details.

    private static final String PACKAGE_NAME = "android.security.keystore2";

    private static final String DESEDE_SYSTEM_PROPERTY =
            "ro.hardware.keystore_desede";

    // Conscrypt returns the Ed25519 OID as the JCA key algorithm.
    private static final String ED25519_OID = "1.3.101.112";
    // Conscrypt returns "XDH" as the X25519 JCA key algorithm.
    private static final String X25519_ALIAS = "XDH";

    /** @hide **/
    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");

        boolean supports3DES = "true".equals(android.os.SystemProperties.get(DESEDE_SYSTEM_PROPERTY));

        // java.security.KeyStore
        put("KeyStore.AndroidKeyStore", PACKAGE_NAME + ".AndroidKeyStoreSpi");

        // java.security.KeyPairGenerator
        put("KeyPairGenerator.EC", PACKAGE_NAME + ".AndroidKeyStoreKeyPairGeneratorSpi$EC");
        put("KeyPairGenerator.RSA", PACKAGE_NAME +  ".AndroidKeyStoreKeyPairGeneratorSpi$RSA");
        put("KeyPairGenerator.XDH", PACKAGE_NAME +  ".AndroidKeyStoreKeyPairGeneratorSpi$XDH");

        // java.security.KeyFactory
        putKeyFactoryImpl("EC");
        putKeyFactoryImpl("RSA");
        putKeyFactoryImpl("XDH");

        // javax.crypto.KeyGenerator
        put("KeyGenerator.AES", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$AES");
        put("KeyGenerator.HmacSHA1", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA1");
        put("KeyGenerator.HmacSHA224", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA224");
        put("KeyGenerator.HmacSHA256", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA256");
        put("KeyGenerator.HmacSHA384", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA384");
        put("KeyGenerator.HmacSHA512", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA512");

        if (supports3DES) {
            put("KeyGenerator.DESede", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$DESede");
        }

        // javax.crypto.KeyAgreement
        put("KeyAgreement.ECDH", PACKAGE_NAME + ".AndroidKeyStoreKeyAgreementSpi$ECDH");
        put("KeyAgreement.XDH", PACKAGE_NAME + ".AndroidKeyStoreKeyAgreementSpi$XDH");

        // java.security.SecretKeyFactory
        putSecretKeyFactoryImpl("AES");
        if (supports3DES) {
            putSecretKeyFactoryImpl("DESede");
        }
        putSecretKeyFactoryImpl("HmacSHA1");
        putSecretKeyFactoryImpl("HmacSHA224");
        putSecretKeyFactoryImpl("HmacSHA256");
        putSecretKeyFactoryImpl("HmacSHA384");
        putSecretKeyFactoryImpl("HmacSHA512");
    }

    /**
     * Installs a new instance of this provider (and the
     * {@link AndroidKeyStoreBCWorkaroundProvider}).
     * @hide
     */
    public static void install() {
        Provider[] providers = Security.getProviders();
        int bcProviderIndex = -1;
        for (int i = 0; i < providers.length; i++) {
            Provider provider = providers[i];
            if ("BC".equals(provider.getName())) {
                bcProviderIndex = i;
                break;
            }
        }

        Security.addProvider(new AndroidKeyStoreProvider());
        Provider workaroundProvider = new AndroidKeyStoreBCWorkaroundProvider();
        if (bcProviderIndex != -1) {
            // Bouncy Castle provider found -- install the workaround provider above it.
            // insertProviderAt uses 1-based positions.
            Security.insertProviderAt(workaroundProvider, bcProviderIndex + 1);
        } else {
            // Bouncy Castle provider not found -- install the workaround provider at lowest
            // priority.
            Security.addProvider(workaroundProvider);
        }
    }

    private void putSecretKeyFactoryImpl(String algorithm) {
        put("SecretKeyFactory." + algorithm, PACKAGE_NAME + ".AndroidKeyStoreSecretKeyFactorySpi");
    }

    private void putKeyFactoryImpl(String algorithm) {
        put("KeyFactory." + algorithm, PACKAGE_NAME + ".AndroidKeyStoreKeyFactorySpi");
    }

    /**
     * Gets the {@link KeyStore} operation handle corresponding to the provided JCA crypto
     * primitive.
     *
     * <p>The following primitives are supported: {@link Cipher}, {@link Signature} and {@link Mac}.
     *
     * @return KeyStore operation handle or {@code 0} if the provided primitive's KeyStore operation
     *         is not in progress.
     *
     * @throws IllegalArgumentException if the provided primitive is not supported or is not backed
     *         by AndroidKeyStore provider.
     * @throws IllegalStateException if the provided primitive is not initialized.
     * @hide
     */
    public static long getKeyStoreOperationHandle(Object cryptoPrimitive) {
        if (cryptoPrimitive == null) {
            throw new NullPointerException();
        }
        Object spi;
        if (cryptoPrimitive instanceof Signature) {
            spi = ((Signature) cryptoPrimitive).getCurrentSpi();
        } else if (cryptoPrimitive instanceof Mac) {
            spi = ((Mac) cryptoPrimitive).getCurrentSpi();
        } else if (cryptoPrimitive instanceof Cipher) {
            spi = ((Cipher) cryptoPrimitive).getCurrentSpi();
        } else if (cryptoPrimitive instanceof KeyAgreement) {
            spi = ((KeyAgreement) cryptoPrimitive).getCurrentSpi();
        } else {
            throw new IllegalArgumentException("Unsupported crypto primitive: " + cryptoPrimitive
                    + ". Supported: Signature, Mac, Cipher");
        }
        if (spi == null) {
            throw new IllegalStateException("Crypto primitive not initialized");
        } else if (!(spi instanceof KeyStoreCryptoOperation)) {
            throw new IllegalArgumentException(
                    "Crypto primitive not backed by AndroidKeyStore provider: " + cryptoPrimitive
                    + ", spi: " + spi);
        }
        return ((KeyStoreCryptoOperation) spi).getOperationHandle();
    }

    /**
     * This helper function gets called if the key loaded from the keystore daemon
     * is for an asymmetric algorithm. It constructs an instance of {@link AndroidKeyStorePublicKey}
     * which implements {@link PublicKey}.
     *
     * @param descriptor The original key descriptor that was used to load the key.
     *
     * @param metadata The key metadata which includes the public key material, a reference to the
     *                 stored private key material, the key characteristics.
     * @param iSecurityLevel A binder interface that allows using the private key.
     * @param algorithm Must indicate EC or RSA.
     * @return AndroidKeyStorePublicKey
     * @throws UnrecoverableKeyException
     * @hide
     */
    @NonNull
    static AndroidKeyStorePublicKey makeAndroidKeyStorePublicKeyFromKeyEntryResponse(
            @NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata,
            @NonNull KeyStoreSecurityLevel iSecurityLevel, int algorithm)
            throws UnrecoverableKeyException {
        if (metadata.certificate == null) {
            throw new UnrecoverableKeyException("Failed to obtain X.509 form of public key."
                    + " Keystore has no public certificate stored.");
        }
        final byte[] x509PublicCert = metadata.certificate;

        final X509Certificate parsedX509Certificate =
                AndroidKeyStoreSpi.toCertificate(x509PublicCert);
        if (parsedX509Certificate == null) {
            throw new UnrecoverableKeyException("Failed to parse the X.509 certificate containing"
                   + " the public key. This likely indicates a hardware problem.");
        }

        PublicKey publicKey = parsedX509Certificate.getPublicKey();

        String jcaKeyAlgorithm = publicKey.getAlgorithm();

        if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(jcaKeyAlgorithm)) {
            return new AndroidKeyStoreECPublicKey(descriptor, metadata,
                    iSecurityLevel, (ECPublicKey) publicKey);
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(jcaKeyAlgorithm)) {
            return new AndroidKeyStoreRSAPublicKey(descriptor, metadata,
                    iSecurityLevel, (RSAPublicKey) publicKey);
        } else if (ED25519_OID.equalsIgnoreCase(jcaKeyAlgorithm)) {
            final byte[] publicKeyEncoded = publicKey.getEncoded();
            return new AndroidKeyStoreEdECPublicKey(descriptor, metadata, ED25519_OID,
                    iSecurityLevel, publicKeyEncoded);
        } else if (X25519_ALIAS.equalsIgnoreCase(jcaKeyAlgorithm)) {
            return new AndroidKeyStoreXDHPublicKey(descriptor, metadata, X25519_ALIAS,
                    iSecurityLevel, publicKey.getEncoded());
        } else {
            throw new ProviderException("Unsupported Android Keystore public key algorithm: "
                    + jcaKeyAlgorithm);
        }
    }

    /** @hide **/
    @NonNull
    public static AndroidKeyStorePublicKey loadAndroidKeyStorePublicKeyFromKeystore(
            @NonNull KeyStore2 keyStore, @NonNull String privateKeyAlias, int namespace)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        AndroidKeyStoreKey key =
                loadAndroidKeyStoreKeyFromKeystore(keyStore, privateKeyAlias, namespace);
        if (key instanceof AndroidKeyStorePublicKey) {
            return (AndroidKeyStorePublicKey) key;
        } else {
            throw new UnrecoverableKeyException("No asymmetric key found by the given alias.");
        }
    }

    /** @hide **/
    @NonNull
    public static KeyPair loadAndroidKeyStoreKeyPairFromKeystore(
            @NonNull KeyStore2 keyStore, @NonNull KeyDescriptor descriptor)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        AndroidKeyStoreKey key =
                loadAndroidKeyStoreKeyFromKeystore(keyStore, descriptor);
        if (key instanceof AndroidKeyStorePublicKey) {
            AndroidKeyStorePublicKey publicKey = (AndroidKeyStorePublicKey) key;
            return new KeyPair(publicKey, publicKey.getPrivateKey());
        } else {
            throw new UnrecoverableKeyException("No asymmetric key found by the given alias.");
        }
    }

    /** @hide **/
    @NonNull
    public static AndroidKeyStorePrivateKey loadAndroidKeyStorePrivateKeyFromKeystore(
            @NonNull KeyStore2 keyStore, @NonNull String privateKeyAlias, int namespace)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        AndroidKeyStoreKey key =
                loadAndroidKeyStoreKeyFromKeystore(keyStore, privateKeyAlias, namespace);
        if (key instanceof AndroidKeyStorePublicKey) {
            return ((AndroidKeyStorePublicKey) key).getPrivateKey();
        } else {
            throw new UnrecoverableKeyException("No asymmetric key found by the given alias.");
        }
    }

    /** @hide **/
    @NonNull
    public static SecretKey loadAndroidKeyStoreSecretKeyFromKeystore(
            @NonNull KeyStore2 keyStore, @NonNull KeyDescriptor descriptor)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {

        AndroidKeyStoreKey key =
                loadAndroidKeyStoreKeyFromKeystore(keyStore, descriptor);
        if (key instanceof SecretKey) {
            return (SecretKey) key;
        } else {
            throw new UnrecoverableKeyException("No secret key found by the given alias.");
        }
    }

    @NonNull
    private static AndroidKeyStoreSecretKey makeAndroidKeyStoreSecretKeyFromKeyEntryResponse(
            @NonNull KeyDescriptor descriptor,
            @NonNull KeyEntryResponse response, int algorithm, int digest)
            throws UnrecoverableKeyException {
        @KeyProperties.KeyAlgorithmEnum String keyAlgorithmString;
        try {
            keyAlgorithmString = KeyProperties.KeyAlgorithm.fromKeymasterSecretKeyAlgorithm(
                    algorithm, digest);
        } catch (IllegalArgumentException e) {
            throw (UnrecoverableKeyException)
                    new UnrecoverableKeyException("Unsupported secret key type").initCause(e);
        }

        return new AndroidKeyStoreSecretKey(descriptor,
                response.metadata, keyAlgorithmString,
                new KeyStoreSecurityLevel(response.iSecurityLevel));
    }

    /**
     * Loads an an AndroidKeyStoreKey from the AndroidKeyStore backend.
     *
     * @param keyStore The keystore2 backend.
     * @param alias The alias of the key in the Keystore database.
     * @param namespace The a Keystore namespace. This is used by system api only to request
     *         Android system specific keystore namespace, which can be configured
     *         in the device's SEPolicy. Third party apps and most system components
     *         set this parameter to -1 to indicate their application specific namespace.
     *         See <a href="https://source.android.com/security/keystore#access-control">
     *             Keystore 2.0 access control</a>
     * @hide
     **/
    @NonNull
    public static AndroidKeyStoreKey loadAndroidKeyStoreKeyFromKeystore(
            @NonNull KeyStore2 keyStore, @NonNull String alias, int namespace)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        KeyDescriptor descriptor = new KeyDescriptor();
        if (namespace == KeyProperties.NAMESPACE_APPLICATION) {
            descriptor.nspace = KeyProperties.NAMESPACE_APPLICATION; // ignored;
            descriptor.domain = Domain.APP;
        } else {
            descriptor.nspace = namespace;
            descriptor.domain = Domain.SELINUX;
        }
        descriptor.alias = alias;
        descriptor.blob = null;

        final AndroidKeyStoreKey key = loadAndroidKeyStoreKeyFromKeystore(keyStore, descriptor);
        if (key instanceof AndroidKeyStorePublicKey) {
            return ((AndroidKeyStorePublicKey) key).getPrivateKey();
        } else {
            return key;
        }
    }

    private static AndroidKeyStoreKey loadAndroidKeyStoreKeyFromKeystore(
            @NonNull KeyStore2 keyStore, @NonNull KeyDescriptor descriptor)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        KeyEntryResponse response = null;
        try {
            response = keyStore.getKeyEntry(descriptor);
        } catch (android.security.KeyStoreException e) {
            switch (e.getErrorCode()) {
                case ResponseCode.KEY_NOT_FOUND:
                    return null;
                case ResponseCode.KEY_PERMANENTLY_INVALIDATED:
                    throw new KeyPermanentlyInvalidatedException(
                            "User changed or deleted their auth credentials",
                            e);
                default:
                    throw (UnrecoverableKeyException)
                            new UnrecoverableKeyException("Failed to obtain information about key")
                                    .initCause(e);
            }
        }

        if (response.iSecurityLevel == null) {
            // This seems to be a pure certificate entry, nothing to return here.
            return null;
        }

        Integer keymasterAlgorithm = null;
        // We just need one digest for the algorithm name
        int keymasterDigest = -1;
        for (Authorization a : response.metadata.authorizations) {
            switch (a.keyParameter.tag) {
                case KeymasterDefs.KM_TAG_ALGORITHM:
                    keymasterAlgorithm = a.keyParameter.value.getAlgorithm();
                    break;
                case KeymasterDefs.KM_TAG_DIGEST:
                    if (keymasterDigest == -1) keymasterDigest = a.keyParameter.value.getDigest();
                    break;
            }
        }
        if (keymasterAlgorithm == null) {
            throw new UnrecoverableKeyException("Key algorithm unknown");
        }

        if (keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_HMAC ||
                keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_AES ||
                keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_3DES) {
            return makeAndroidKeyStoreSecretKeyFromKeyEntryResponse(descriptor, response,
                    keymasterAlgorithm, keymasterDigest);
        } else if (keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_RSA ||
                keymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_EC) {
            return makeAndroidKeyStorePublicKeyFromKeyEntryResponse(descriptor, response.metadata,
                    new KeyStoreSecurityLevel(response.iSecurityLevel),
                    keymasterAlgorithm);
        } else {
            throw new UnrecoverableKeyException("Key algorithm unknown");
        }
    }
}
