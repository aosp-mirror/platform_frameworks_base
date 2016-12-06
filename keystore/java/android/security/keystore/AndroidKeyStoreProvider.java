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

import android.annotation.NonNull;
import android.security.KeyStore;
import android.security.keymaster.ExportResult;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterDefs;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A provider focused on providing JCA interfaces for the Android KeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreProvider extends Provider {
    public static final String PROVIDER_NAME = "AndroidKeyStore";

    // IMPLEMENTATION NOTE: Class names are hard-coded in this provider to avoid loading these
    // classes when this provider is instantiated and installed early on during each app's
    // initialization process.
    //
    // Crypto operations operating on the AndroidKeyStore keys must not be offered by this provider.
    // Instead, they need to be offered by AndroidKeyStoreBCWorkaroundProvider. See its Javadoc
    // for details.

    private static final String PACKAGE_NAME = "android.security.keystore";

    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");

        // java.security.KeyStore
        put("KeyStore.AndroidKeyStore", PACKAGE_NAME + ".AndroidKeyStoreSpi");

        // java.security.KeyPairGenerator
        put("KeyPairGenerator.EC", PACKAGE_NAME + ".AndroidKeyStoreKeyPairGeneratorSpi$EC");
        put("KeyPairGenerator.RSA", PACKAGE_NAME +  ".AndroidKeyStoreKeyPairGeneratorSpi$RSA");

        // java.security.KeyFactory
        putKeyFactoryImpl("EC");
        putKeyFactoryImpl("RSA");

        // javax.crypto.KeyGenerator
        put("KeyGenerator.AES", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$AES");
        put("KeyGenerator.HmacSHA1", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA1");
        put("KeyGenerator.HmacSHA224", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA224");
        put("KeyGenerator.HmacSHA256", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA256");
        put("KeyGenerator.HmacSHA384", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA384");
        put("KeyGenerator.HmacSHA512", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA512");

        // java.security.SecretKeyFactory
        putSecretKeyFactoryImpl("AES");
        putSecretKeyFactoryImpl("HmacSHA1");
        putSecretKeyFactoryImpl("HmacSHA224");
        putSecretKeyFactoryImpl("HmacSHA256");
        putSecretKeyFactoryImpl("HmacSHA384");
        putSecretKeyFactoryImpl("HmacSHA512");
    }

    /**
     * Installs a new instance of this provider (and the
     * {@link AndroidKeyStoreBCWorkaroundProvider}).
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
     * <p>The following primitives are supported: {@link Cipher} and {@link Mac}.
     *
     * @return KeyStore operation handle or {@code 0} if the provided primitive's KeyStore operation
     *         is not in progress.
     *
     * @throws IllegalArgumentException if the provided primitive is not supported or is not backed
     *         by AndroidKeyStore provider.
     * @throws IllegalStateException if the provided primitive is not initialized.
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

    @NonNull
    public static AndroidKeyStorePublicKey getAndroidKeyStorePublicKey(
            @NonNull String alias,
            int uid,
            @NonNull @KeyProperties.KeyAlgorithmEnum String keyAlgorithm,
            @NonNull byte[] x509EncodedForm) {
        PublicKey publicKey;
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(keyAlgorithm);
            publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(x509EncodedForm));
        } catch (NoSuchAlgorithmException e) {
            throw new ProviderException(
                    "Failed to obtain " + keyAlgorithm + " KeyFactory", e);
        } catch (InvalidKeySpecException e) {
            throw new ProviderException("Invalid X.509 encoding of public key", e);
        }
        if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(keyAlgorithm)) {
            return new AndroidKeyStoreECPublicKey(alias, uid, (ECPublicKey) publicKey);
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(keyAlgorithm)) {
            return new AndroidKeyStoreRSAPublicKey(alias, uid, (RSAPublicKey) publicKey);
        } else {
            throw new ProviderException("Unsupported Android Keystore public key algorithm: "
                    + keyAlgorithm);
        }
    }

    @NonNull
    public static AndroidKeyStorePrivateKey getAndroidKeyStorePrivateKey(
            @NonNull AndroidKeyStorePublicKey publicKey) {
        String keyAlgorithm = publicKey.getAlgorithm();
        if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(keyAlgorithm)) {
            return new AndroidKeyStoreECPrivateKey(
                    publicKey.getAlias(), publicKey.getUid(), ((ECKey) publicKey).getParams());
        } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(keyAlgorithm)) {
            return new AndroidKeyStoreRSAPrivateKey(
                    publicKey.getAlias(), publicKey.getUid(), ((RSAKey) publicKey).getModulus());
        } else {
            throw new ProviderException("Unsupported Android Keystore public key algorithm: "
                    + keyAlgorithm);
        }
    }

    @NonNull
    public static AndroidKeyStorePublicKey loadAndroidKeyStorePublicKeyFromKeystore(
            @NonNull KeyStore keyStore, @NonNull String privateKeyAlias, int uid)
            throws UnrecoverableKeyException {
        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode = keyStore.getKeyCharacteristics(
                privateKeyAlias, null, null, uid, keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw (UnrecoverableKeyException)
                    new UnrecoverableKeyException("Failed to obtain information about private key")
                    .initCause(KeyStore.getKeyStoreException(errorCode));
        }
        ExportResult exportResult = keyStore.exportKey(
                privateKeyAlias, KeymasterDefs.KM_KEY_FORMAT_X509, null, null, uid);
        if (exportResult.resultCode != KeyStore.NO_ERROR) {
            throw (UnrecoverableKeyException)
                    new UnrecoverableKeyException("Failed to obtain X.509 form of public key")
                    .initCause(KeyStore.getKeyStoreException(exportResult.resultCode));
        }
        final byte[] x509EncodedPublicKey = exportResult.exportData;

        Integer keymasterAlgorithm = keyCharacteristics.getEnum(KeymasterDefs.KM_TAG_ALGORITHM);
        if (keymasterAlgorithm == null) {
            throw new UnrecoverableKeyException("Key algorithm unknown");
        }

        String jcaKeyAlgorithm;
        try {
            jcaKeyAlgorithm = KeyProperties.KeyAlgorithm.fromKeymasterAsymmetricKeyAlgorithm(
                    keymasterAlgorithm);
        } catch (IllegalArgumentException e) {
            throw (UnrecoverableKeyException)
                    new UnrecoverableKeyException("Failed to load private key")
                    .initCause(e);
        }

        return AndroidKeyStoreProvider.getAndroidKeyStorePublicKey(
                privateKeyAlias, uid, jcaKeyAlgorithm, x509EncodedPublicKey);
    }

    @NonNull
    public static KeyPair loadAndroidKeyStoreKeyPairFromKeystore(
            @NonNull KeyStore keyStore, @NonNull String privateKeyAlias, int uid)
            throws UnrecoverableKeyException {
        AndroidKeyStorePublicKey publicKey =
                loadAndroidKeyStorePublicKeyFromKeystore(keyStore, privateKeyAlias, uid);
        AndroidKeyStorePrivateKey privateKey =
                AndroidKeyStoreProvider.getAndroidKeyStorePrivateKey(publicKey);
        return new KeyPair(publicKey, privateKey);
    }

    @NonNull
    public static AndroidKeyStorePrivateKey loadAndroidKeyStorePrivateKeyFromKeystore(
            @NonNull KeyStore keyStore, @NonNull String privateKeyAlias, int uid)
            throws UnrecoverableKeyException {
        KeyPair keyPair = loadAndroidKeyStoreKeyPairFromKeystore(keyStore, privateKeyAlias, uid);
        return (AndroidKeyStorePrivateKey) keyPair.getPrivate();
    }

    @NonNull
    public static AndroidKeyStoreSecretKey loadAndroidKeyStoreSecretKeyFromKeystore(
            @NonNull KeyStore keyStore, @NonNull String secretKeyAlias, int uid)
            throws UnrecoverableKeyException {
        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode = keyStore.getKeyCharacteristics(
                secretKeyAlias, null, null, uid, keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw (UnrecoverableKeyException)
                    new UnrecoverableKeyException("Failed to obtain information about key")
                            .initCause(KeyStore.getKeyStoreException(errorCode));
        }

        Integer keymasterAlgorithm = keyCharacteristics.getEnum(KeymasterDefs.KM_TAG_ALGORITHM);
        if (keymasterAlgorithm == null) {
            throw new UnrecoverableKeyException("Key algorithm unknown");
        }

        List<Integer> keymasterDigests = keyCharacteristics.getEnums(KeymasterDefs.KM_TAG_DIGEST);
        int keymasterDigest;
        if (keymasterDigests.isEmpty()) {
            keymasterDigest = -1;
        } else {
            // More than one digest can be permitted for this key. Use the first one to form the
            // JCA key algorithm name.
            keymasterDigest = keymasterDigests.get(0);
        }

        @KeyProperties.KeyAlgorithmEnum String keyAlgorithmString;
        try {
            keyAlgorithmString = KeyProperties.KeyAlgorithm.fromKeymasterSecretKeyAlgorithm(
                    keymasterAlgorithm, keymasterDigest);
        } catch (IllegalArgumentException e) {
            throw (UnrecoverableKeyException)
                    new UnrecoverableKeyException("Unsupported secret key type").initCause(e);
        }

        return new AndroidKeyStoreSecretKey(secretKeyAlias, uid, keyAlgorithmString);
    }

    /**
     * Returns an {@code AndroidKeyStore} {@link java.security.KeyStore}} of the specified UID.
     * The {@code KeyStore} contains keys and certificates owned by that UID. Such cross-UID
     * access is permitted to a few system UIDs and only to a few other UIDs (e.g., Wi-Fi, VPN)
     * all of which are system.
     *
     * <p>Note: the returned {@code KeyStore} is already initialized/loaded. Thus, there is
     * no need to invoke {@code load} on it.
     */
    @NonNull
    public static java.security.KeyStore getKeyStoreForUid(int uid)
            throws KeyStoreException, NoSuchProviderException {
        java.security.KeyStore result =
                java.security.KeyStore.getInstance("AndroidKeyStore", PROVIDER_NAME);
        try {
            result.load(new AndroidKeyStoreLoadStoreParameter(uid));
        } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new KeyStoreException(
                    "Failed to load AndroidKeyStore KeyStore for UID " + uid, e);
        }
        return result;
    }
}
