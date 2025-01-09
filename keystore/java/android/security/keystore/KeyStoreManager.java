/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.hardware.security.keymint.TagType;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.security.keystore2.AndroidKeyStoreProvider;
import android.security.keystore2.AndroidKeyStorePublicKey;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyPermission;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.Key;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * This class provides methods for interacting with keys stored within the
 * <a href="/privacy-and-security/keystore">Android Keystore</a>.
 */
@FlaggedApi(android.security.Flags.FLAG_KEYSTORE_GRANT_API)
@SystemService(Context.KEYSTORE_SERVICE)
public final class KeyStoreManager {
    private static final String TAG = "KeyStoreManager";

    private static final Object sInstanceLock = new Object();
    @GuardedBy("sInstanceLock")
    private static KeyStoreManager sInstance;

    private final KeyStore2 mKeyStore2;

    /**
     * Private constructor to ensure only a single instance is created.
     */
    private KeyStoreManager() {
        mKeyStore2 = KeyStore2.getInstance();
    }

    /**
     * Returns the single instance of the {@code KeyStoreManager}.
     *
     * @hide
     */
    public static KeyStoreManager getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new KeyStoreManager();
            }
            return sInstance;
        }
    }

    /**
     * Grants access to the key owned by the calling app stored under the specified {@code alias}
     * to another app on the device with the provided {@code uid}.
     *
     * <p>This method supports granting access to instances of both {@link javax.crypto.SecretKey}
     * and {@link java.security.PrivateKey}. The resulting ID will persist across reboots and can be
     * used by the grantee app for the life of the key or until access is revoked with {@link
     * #revokeKeyAccess(String, int)}.
     *
     * <p>If the provided {@code alias} does not correspond to a key in the Android KeyStore, then
     * an {@link UnrecoverableKeyException} is thrown.
     *
     * @param alias the alias of the key to be granted to another app
     * @param uid   the uid of the app to which the key should be granted
     * @return the ID of the granted key; this can be shared with the specified app, and that
     * app can use {@link #getGrantedKeyFromId(long)} to access the key
     * @throws UnrecoverableKeyException if the specified key cannot be recovered
     * @throws KeyStoreException if an error is encountered when attempting to grant access to
     * the key
     * @see #getGrantedKeyFromId(long)
     */
    public long grantKeyAccess(@NonNull String alias, int uid)
            throws KeyStoreException, UnrecoverableKeyException {
        KeyDescriptor keyDescriptor = createKeyDescriptorFromAlias(alias);
        final int grantAccessVector = KeyPermission.USE | KeyPermission.GET_INFO;
        // When a key is in the GRANT domain, the nspace field of the KeyDescriptor contains its ID.
        KeyDescriptor result = null;
        try {
            result = mKeyStore2.grant(keyDescriptor, uid, grantAccessVector);
        } catch (KeyStoreException e) {
            // If the provided alias does not correspond to a valid key in the KeyStore, then throw
            // an UnrecoverableKeyException to remain consistent with other APIs in this class.
            if (e.getNumericErrorCode() == KeyStoreException.ERROR_KEY_DOES_NOT_EXIST) {
                throw new UnrecoverableKeyException("No key found by the given alias");
            }
            throw e;
        }
        if (result == null) {
            Log.e(TAG, "Received a null KeyDescriptor from grant");
            throw new KeyStoreException(KeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR,
                    "No ID was returned for the grant request for alias " + alias + " to uid "
                            + uid);
        } else if (result.domain != Domain.GRANT) {
            Log.e(TAG, "Received a result outside the grant domain: " + result.domain);
            throw new KeyStoreException(KeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR,
                    "Unable to obtain a grant ID for alias " + alias + " to uid " + uid);
        }
        return result.nspace;
    }

    /**
     * Revokes access to the key in the app's namespace stored under the specified {@code
     * alias} that was previously granted to another app on the device with the provided
     * {@code uid}.
     *
     * <p>If the provided {@code alias} does not correspond to a key in the Android KeyStore, then
     * an {@link UnrecoverableKeyException} is thrown.
     *
     * @param alias the alias of the key to be revoked from another app
     * @param uid   the uid of the app from which the key access should be revoked
     * @throws UnrecoverableKeyException if the specified key cannot be recovered
     * @throws KeyStoreException if an error is encountered when attempting to revoke access
     * to the key
     */
    public void revokeKeyAccess(@NonNull String alias, int uid)
            throws KeyStoreException, UnrecoverableKeyException {
        KeyDescriptor keyDescriptor = createKeyDescriptorFromAlias(alias);
        try {
            mKeyStore2.ungrant(keyDescriptor, uid);
        } catch (KeyStoreException e) {
            // If the provided alias does not correspond to a valid key in the KeyStore, then throw
            // an UnrecoverableKeyException to remain consistent with other APIs in this class.
            if (e.getNumericErrorCode() == KeyStoreException.ERROR_KEY_DOES_NOT_EXIST) {
                throw new UnrecoverableKeyException("No key found by the given alias");
            }
            throw e;
        }
    }

    /**
     * Returns the key with the specified {@code id} that was previously shared with the
     * app.
     *
     * <p>This method can return instances of both {@link javax.crypto.SecretKey} and {@link
     * java.security.PrivateKey}. If a key with the provide {@code id} has not been granted to the
     * caller, then an {@link UnrecoverableKeyException} is thrown.
     *
     * @param id the ID of the key that was shared with the app
     * @return the {@link Key} that was shared with the app
     * @throws UnrecoverableKeyException          if the specified key cannot be recovered
     * @throws KeyPermanentlyInvalidatedException if the specified key was authorized to only
     *                                            be used if the user has been authenticated and a
     *                                            change has been made to the users
     *                                            lockscreen or biometric enrollment that
     *                                            permanently invalidates the key
     * @see #grantKeyAccess(String, int)
     */
    public @NonNull Key getGrantedKeyFromId(long id)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        Key result = AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore(mKeyStore2, null,
                id, Domain.GRANT);
        if (result == null) {
            throw new UnrecoverableKeyException("No key found by the given alias");
        }
        return result;
    }

    /**
     * Returns a {@link KeyPair} containing the public and private key associated with
     * the key that was previously shared with the app under the provided {@code id}.
     *
     * <p>If a {@link java.security.PrivateKey} has not been granted to the caller with the
     * specified {@code id}, then an {@link UnrecoverableKeyException} is thrown.
     *
     * @param id the ID of the private key that was shared with the app
     * @return a KeyPair containing the public and private key shared with the app
     * @throws UnrecoverableKeyException          if the specified key cannot be recovered
     * @throws KeyPermanentlyInvalidatedException if the specified key was authorized to only
     *                                            be used if the user has been authenticated and a
     *                                            change has been made to the users
     *                                            lockscreen or biometric enrollment that
     *                                            permanently invalidates the key
     */
    public @NonNull KeyPair getGrantedKeyPairFromId(long id)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        KeyDescriptor keyDescriptor = createKeyDescriptorFromId(id, Domain.GRANT);
        return AndroidKeyStoreProvider.loadAndroidKeyStoreKeyPairFromKeystore(mKeyStore2,
                keyDescriptor);
    }

    /**
     * Returns a {@link List} of {@link X509Certificate} instances representing the certificate
     * chain for the key that was previously shared with the app under the provided {@code id}.
     *
     * <p>If a {@link java.security.PrivateKey} has not been granted to the caller with the
     * specified {@code id}, then an {@link UnrecoverableKeyException} is thrown.
     *
     * @param id the ID of the asymmetric key that was shared with the app
     * @return a List of X509Certificates with the certificate at index 0 corresponding to
     * the private key shared with the app
     * @throws UnrecoverableKeyException          if the specified key cannot be recovered
     * @throws KeyPermanentlyInvalidatedException if the specified key was authorized to only
     *                                            be used if the user has been authenticated and a
     *                                            change has been made to the users
     *                                            lockscreen or biometric enrollment that
     *                                            permanently invalidates the key
     * @see #grantKeyAccess(String, int)
     */
    // Java APIs should prefer mutable collection return types with the exception being
    // Collection.empty return types.
    @SuppressWarnings("MixedMutabilityReturnType")
    public @NonNull List<X509Certificate> getGrantedCertificateChainFromId(long id)
            throws UnrecoverableKeyException, KeyPermanentlyInvalidatedException {
        KeyDescriptor keyDescriptor = createKeyDescriptorFromId(id, Domain.GRANT);
        KeyPair keyPair = AndroidKeyStoreProvider.loadAndroidKeyStoreKeyPairFromKeystore(mKeyStore2,
                keyDescriptor);
        PublicKey keyStoreKey = keyPair.getPublic();
        if (keyStoreKey instanceof AndroidKeyStorePublicKey) {
            AndroidKeyStorePublicKey androidKeyStorePublicKey =
                    (AndroidKeyStorePublicKey) keyStoreKey;
            byte[] certBytes = androidKeyStorePublicKey.getCertificate();
            X509Certificate cert = getCertificate(certBytes);
            // If the leaf certificate is null, then a chain should not exist either
            if (cert == null) {
                return Collections.emptyList();
            }
            List<X509Certificate> result = new ArrayList<>();
            result.add(cert);
            byte[] certificateChain = androidKeyStorePublicKey.getCertificateChain();
            Collection<X509Certificate> certificates = getCertificates(certificateChain);
            result.addAll(certificates);
            return result;
        } else {
            Log.e(TAG, "keyStoreKey is not of the expected type: " + keyStoreKey);
        }
        return Collections.emptyList();
    }

    /**
     * Returns an {@link X509Certificate} instance from the provided {@code certificate} byte
     * encoding of the certificate, or null if the provided encoding is null.
     */
    private static X509Certificate getCertificate(byte[] certificate) {
        X509Certificate result = null;
        if (certificate != null) {
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                result = (X509Certificate) certificateFactory.generateCertificate(
                        new ByteArrayInputStream(certificate));
            } catch (Exception e) {
                Log.e(TAG, "Caught an exception parsing the certificate: ", e);
            }
        }
        return result;
    }

    /**
     * Returns a {@link Collection} of {@link X509Certificate} instances from the provided
     * {@code certificateChain} byte encoding of the certificates, or null if the provided
     * encoding is null.
     */
    private static Collection<X509Certificate> getCertificates(byte[] certificateChain) {
        if (certificateChain != null) {
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                Collection<X509Certificate> certificates =
                        (Collection<X509Certificate>) certificateFactory.generateCertificates(
                                new ByteArrayInputStream(certificateChain));
                if (certificates == null) {
                    Log.e(TAG, "Received null certificates from a non-null certificateChain");
                    return Collections.emptyList();
                }
                return certificates;
            } catch (Exception e) {
                Log.e(TAG, "Caught an exception parsing the certs: ", e);
            }
        }
        return Collections.emptyList();
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {MODULE_HASH})
    public @interface SupplementaryAttestationInfoTagEnum {}

    /**
     * When passed into getSupplementaryAttestationInfo, getSupplementaryAttestationInfo returns the
     * DER-encoded structure corresponding to the `Modules` schema described in the KeyMint HAL's
     * KeyCreationResult.aidl. The SHA-256 hash of this encoded structure is what's included with
     * the tag in attestations. To ensure the returned encoded structure is the one attested to,
     * clients should verify its SHA-256 hash matches the one in the attestation. Note that the
     * returned structure can vary between boots.
     */
    // TODO(b/380020528): Replace with Tag.MODULE_HASH when KeyMint V4 is frozen.
    public static final int MODULE_HASH = TagType.BYTES | 724;

    /**
     * Returns tag-specific data required to interpret a tag's attested value.
     *
     * When performing key attestation, the obtained attestation certificate contains a list of tags
     * and their corresponding attested values. For some tags, additional information about the
     * attested value can be queried via this API. See individual tags for specifics.
     *
     * @param tag tag for which info is being requested
     * @return tag-specific info
     * @throws KeyStoreException if the requested info is not available
     */
    @FlaggedApi(android.security.keystore2.Flags.FLAG_ATTEST_MODULES)
    public @NonNull byte[] getSupplementaryAttestationInfo(
            @SupplementaryAttestationInfoTagEnum int tag) throws KeyStoreException {
        return mKeyStore2.getSupplementaryAttestationInfo(tag);
    }

    /**
     * Returns a new {@link KeyDescriptor} instance in the app domain / namespace with the {@code
     * alias} set to the provided value.
     */
    private static KeyDescriptor createKeyDescriptorFromAlias(String alias) {
        KeyDescriptor keyDescriptor = new KeyDescriptor();
        keyDescriptor.domain = Domain.APP;
        keyDescriptor.nspace = KeyProperties.NAMESPACE_APPLICATION;
        keyDescriptor.alias = alias;
        keyDescriptor.blob = null;
        return keyDescriptor;
    }

    /**
     * Returns a new {@link KeyDescriptor} instance in the provided {@code domain} with the nspace
     * field set to the provided {@code id}.
     */
    private static KeyDescriptor createKeyDescriptorFromId(long id, int domain) {
        KeyDescriptor keyDescriptor = new KeyDescriptor();
        keyDescriptor.domain = domain;
        keyDescriptor.nspace = id;
        keyDescriptor.alias = null;
        keyDescriptor.blob = null;
        return keyDescriptor;
    }
}
