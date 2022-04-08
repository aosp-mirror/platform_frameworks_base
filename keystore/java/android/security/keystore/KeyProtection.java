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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.KeyguardManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.security.GateKeeper;

import java.security.Key;
import java.security.KeyStore.ProtectionParameter;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * Specification of how a key or key pair is secured when imported into the
 * <a href="{@docRoot}training/articles/keystore.html">Android Keystore system</a>. This class
 * specifies authorized uses of the imported key, such as whether user authentication is required
 * for using the key, what operations the key is authorized for (e.g., decryption, but not signing)
 * with what parameters (e.g., only with a particular padding scheme or digest), and the key's
 * validity start and end dates. Key use authorizations expressed in this class apply only to secret
 * keys and private keys -- public keys can be used for any supported operations.
 *
 * <p>To import a key or key pair into the Android Keystore, create an instance of this class using
 * the {@link Builder} and pass the instance into {@link java.security.KeyStore#setEntry(String, java.security.KeyStore.Entry, ProtectionParameter) KeyStore.setEntry}
 * with the key or key pair being imported.
 *
 * <p>To obtain the secret/symmetric or private key from the Android Keystore use
 * {@link java.security.KeyStore#getKey(String, char[]) KeyStore.getKey(String, null)} or
 * {@link java.security.KeyStore#getEntry(String, java.security.KeyStore.ProtectionParameter) KeyStore.getEntry(String, null)}.
 * To obtain the public key from the Android Keystore use
 * {@link java.security.KeyStore#getCertificate(String)} and then
 * {@link Certificate#getPublicKey()}.
 *
 * <p>To help obtain algorithm-specific public parameters of key pairs stored in the Android
 * Keystore, its private keys implement {@link java.security.interfaces.ECKey} or
 * {@link java.security.interfaces.RSAKey} interfaces whereas its public keys implement
 * {@link java.security.interfaces.ECPublicKey} or {@link java.security.interfaces.RSAPublicKey}
 * interfaces.
 *
 * <p>NOTE: The key material of keys stored in the Android Keystore is not accessible.
 *
 * <p>Instances of this class are immutable.
 *
 * <p><h3>Known issues</h3>
 * A known bug in Android 6.0 (API Level 23) causes user authentication-related authorizations to be
 * enforced even for public keys. To work around this issue extract the public key material to use
 * outside of Android Keystore. For example:
 * <pre> {@code
 * PublicKey unrestrictedPublicKey =
 *         KeyFactory.getInstance(publicKey.getAlgorithm()).generatePublic(
 *                 new X509EncodedKeySpec(publicKey.getEncoded()));
 * }</pre>
 *
 * <p><h3>Example: AES key for encryption/decryption in GCM mode</h3>
 * This example illustrates how to import an AES key into the Android KeyStore under alias
 * {@code key1} authorized to be used only for encryption/decryption in GCM mode with no padding.
 * The key must export its key material via {@link Key#getEncoded()} in {@code RAW} format.
 * <pre> {@code
 * SecretKey key = ...; // AES key
 *
 * KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
 * keyStore.load(null);
 * keyStore.setEntry(
 *         "key1",
 *         new KeyStore.SecretKeyEntry(key),
 *         new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
 *                 .setBlockMode(KeyProperties.BLOCK_MODE_GCM)
 *                 .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
 *                 .build());
 * // Key imported, obtain a reference to it.
 * SecretKey keyStoreKey = (SecretKey) keyStore.getKey("key1", null);
 * // The original key can now be discarded.
 *
 * Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
 * cipher.init(Cipher.ENCRYPT_MODE, keyStoreKey);
 * ...
 * }</pre>
 *
 * <p><h3>Example: HMAC key for generating MACs using SHA-512</h3>
 * This example illustrates how to import an HMAC key into the Android KeyStore under alias
 * {@code key1} authorized to be used only for generating MACs using SHA-512 digest. The key must
 * export its key material via {@link Key#getEncoded()} in {@code RAW} format.
 * <pre> {@code
 * SecretKey key = ...; // HMAC key of algorithm "HmacSHA512".
 *
 * KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
 * keyStore.load(null);
 * keyStore.setEntry(
 *         "key1",
 *         new KeyStore.SecretKeyEntry(key),
 *         new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build());
 * // Key imported, obtain a reference to it.
 * SecretKey keyStoreKey = (SecretKey) keyStore.getKey("key1", null);
 * // The original key can now be discarded.
 *
 * Mac mac = Mac.getInstance("HmacSHA512");
 * mac.init(keyStoreKey);
 * ...
 * }</pre>
 *
 * <p><h3>Example: EC key pair for signing/verification using ECDSA</h3>
 * This example illustrates how to import an EC key pair into the Android KeyStore under alias
 * {@code key2} with the private key authorized to be used only for signing with SHA-256 or SHA-512
 * digests. The use of the public key is unrestricted. Both the private and the public key must
 * export their key material via {@link Key#getEncoded()} in {@code PKCS#8} and {@code X.509} format
 * respectively.
 * <pre> {@code
 * PrivateKey privateKey = ...;   // EC private key
 * Certificate[] certChain = ...; // Certificate chain with the first certificate
 *                                // containing the corresponding EC public key.
 *
 * KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
 * keyStore.load(null);
 * keyStore.setEntry(
 *         "key2",
 *         new KeyStore.PrivateKeyEntry(privateKey, certChain),
 *         new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
 *                 .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
 *                 .build());
 * // Key pair imported, obtain a reference to it.
 * PrivateKey keyStorePrivateKey = (PrivateKey) keyStore.getKey("key2", null);
 * PublicKey publicKey = keyStore.getCertificate("key2").getPublicKey();
 * // The original private key can now be discarded.
 *
 * Signature signature = Signature.getInstance("SHA256withECDSA");
 * signature.initSign(keyStorePrivateKey);
 * ...
 * }</pre>
 *
 * <p><h3>Example: RSA key pair for signing/verification using PKCS#1 padding</h3>
 * This example illustrates how to import an RSA key pair into the Android KeyStore under alias
 * {@code key2} with the private key authorized to be used only for signing using the PKCS#1
 * signature padding scheme with SHA-256 digest and only if the user has been authenticated within
 * the last ten minutes. The use of the public key is unrestricted (see Known Issues). Both the
 * private and the public key must export their key material via {@link Key#getEncoded()} in
 * {@code PKCS#8} and {@code X.509} format respectively.
 * <pre> {@code
 * PrivateKey privateKey = ...;   // RSA private key
 * Certificate[] certChain = ...; // Certificate chain with the first certificate
 *                                // containing the corresponding RSA public key.
 *
 * KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
 * keyStore.load(null);
 * keyStore.setEntry(
 *         "key2",
 *         new KeyStore.PrivateKeyEntry(privateKey, certChain),
 *         new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
 *                 .setDigests(KeyProperties.DIGEST_SHA256)
 *                 .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
 *                 // Only permit this key to be used if the user
 *                 // authenticated within the last ten minutes.
 *                 .setUserAuthenticationRequired(true)
 *                 .setUserAuthenticationValidityDurationSeconds(10 * 60)
 *                 .build());
 * // Key pair imported, obtain a reference to it.
 * PrivateKey keyStorePrivateKey = (PrivateKey) keyStore.getKey("key2", null);
 * PublicKey publicKey = keyStore.getCertificate("key2").getPublicKey();
 * // The original private key can now be discarded.
 *
 * Signature signature = Signature.getInstance("SHA256withRSA");
 * signature.initSign(keyStorePrivateKey);
 * ...
 * }</pre>
 *
 * <p><h3>Example: RSA key pair for encryption/decryption using PKCS#1 padding</h3>
 * This example illustrates how to import an RSA key pair into the Android KeyStore under alias
 * {@code key2} with the private key authorized to be used only for decryption using the PKCS#1
 * encryption padding scheme. The use of public key is unrestricted, thus permitting encryption
 * using any padding schemes and digests. Both the private and the public key must export their key
 * material via {@link Key#getEncoded()} in {@code PKCS#8} and {@code X.509} format respectively.
 * <pre> {@code
 * PrivateKey privateKey = ...;   // RSA private key
 * Certificate[] certChain = ...; // Certificate chain with the first certificate
 *                                // containing the corresponding RSA public key.
 *
 * KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
 * keyStore.load(null);
 * keyStore.setEntry(
 *         "key2",
 *         new KeyStore.PrivateKeyEntry(privateKey, certChain),
 *         new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
 *                 .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
 *                 .build());
 * // Key pair imported, obtain a reference to it.
 * PrivateKey keyStorePrivateKey = (PrivateKey) keyStore.getKey("key2", null);
 * PublicKey publicKey = keyStore.getCertificate("key2").getPublicKey();
 * // The original private key can now be discarded.
 *
 * Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
 * cipher.init(Cipher.DECRYPT_MODE, keyStorePrivateKey);
 * ...
 * }</pre>
 */
public final class KeyProtection implements ProtectionParameter, UserAuthArgs {
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyProperties.PurposeEnum int mPurposes;
    private final @KeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
    private final @KeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;
    private final @KeyProperties.DigestEnum String[] mDigests;
    private final @KeyProperties.BlockModeEnum String[] mBlockModes;
    private final boolean mRandomizedEncryptionRequired;
    private final boolean mUserAuthenticationRequired;
    private final @KeyProperties.AuthEnum int mUserAuthenticationType;
    private final int mUserAuthenticationValidityDurationSeconds;
    private final boolean mUserPresenceRequred;
    private final boolean mUserAuthenticationValidWhileOnBody;
    private final boolean mInvalidatedByBiometricEnrollment;
    private final long mBoundToSecureUserId;
    private final boolean mCriticalToDeviceEncryption;
    private final boolean mUserConfirmationRequired;
    private final boolean mUnlockedDeviceRequired;
    private final boolean mIsStrongBoxBacked;

    private KeyProtection(
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyProperties.PurposeEnum int purposes,
            @KeyProperties.EncryptionPaddingEnum String[] encryptionPaddings,
            @KeyProperties.SignaturePaddingEnum String[] signaturePaddings,
            @KeyProperties.DigestEnum String[] digests,
            @KeyProperties.BlockModeEnum String[] blockModes,
            boolean randomizedEncryptionRequired,
            boolean userAuthenticationRequired,
            @KeyProperties.AuthEnum int userAuthenticationType,
            int userAuthenticationValidityDurationSeconds,
            boolean userPresenceRequred,
            boolean userAuthenticationValidWhileOnBody,
            boolean invalidatedByBiometricEnrollment,
            long boundToSecureUserId,
            boolean criticalToDeviceEncryption,
            boolean userConfirmationRequired,
            boolean unlockedDeviceRequired,
            boolean isStrongBoxBacked) {
        mKeyValidityStart = Utils.cloneIfNotNull(keyValidityStart);
        mKeyValidityForOriginationEnd = Utils.cloneIfNotNull(keyValidityForOriginationEnd);
        mKeyValidityForConsumptionEnd = Utils.cloneIfNotNull(keyValidityForConsumptionEnd);
        mPurposes = purposes;
        mEncryptionPaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(encryptionPaddings));
        mSignaturePaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(signaturePaddings));
        mDigests = ArrayUtils.cloneIfNotEmpty(digests);
        mBlockModes = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(blockModes));
        mRandomizedEncryptionRequired = randomizedEncryptionRequired;
        mUserAuthenticationRequired = userAuthenticationRequired;
        mUserAuthenticationType = userAuthenticationType;
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
        mUserPresenceRequred = userPresenceRequred;
        mUserAuthenticationValidWhileOnBody = userAuthenticationValidWhileOnBody;
        mInvalidatedByBiometricEnrollment = invalidatedByBiometricEnrollment;
        mBoundToSecureUserId = boundToSecureUserId;
        mCriticalToDeviceEncryption = criticalToDeviceEncryption;
        mUserConfirmationRequired = userConfirmationRequired;
        mUnlockedDeviceRequired = unlockedDeviceRequired;
        mIsStrongBoxBacked = isStrongBoxBacked;
    }

    /**
     * Gets the time instant before which the key is not yet valid.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityStart() {
        return Utils.cloneIfNotNull(mKeyValidityStart);
    }

    /**
     * Gets the time instant after which the key is no long valid for decryption and verification.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityForConsumptionEnd() {
        return Utils.cloneIfNotNull(mKeyValidityForConsumptionEnd);
    }

    /**
     * Gets the time instant after which the key is no long valid for encryption and signing.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityForOriginationEnd() {
        return Utils.cloneIfNotNull(mKeyValidityForOriginationEnd);
    }

    /**
     * Gets the set of purposes (e.g., encrypt, decrypt, sign) for which the key can be used.
     * Attempts to use the key for any other purpose will be rejected.
     *
     * <p>See {@link KeyProperties}.{@code PURPOSE} flags.
     */
    public @KeyProperties.PurposeEnum int getPurposes() {
        return mPurposes;
    }

    /**
     * Gets the set of padding schemes (e.g., {@code PKCS7Padding}, {@code PKCS1Padding},
     * {@code NoPadding}) with which the key can be used when encrypting/decrypting. Attempts to use
     * the key with any other padding scheme will be rejected.
     *
     * <p>See {@link KeyProperties}.{@code ENCRYPTION_PADDING} constants.
     */
    @NonNull
    public @KeyProperties.EncryptionPaddingEnum String[] getEncryptionPaddings() {
        return ArrayUtils.cloneIfNotEmpty(mEncryptionPaddings);
    }

    /**
     * Gets the set of padding schemes (e.g., {@code PSS}, {@code PKCS#1}) with which the key
     * can be used when signing/verifying. Attempts to use the key with any other padding scheme
     * will be rejected.
     *
     * <p>See {@link KeyProperties}.{@code SIGNATURE_PADDING} constants.
     */
    @NonNull
    public @KeyProperties.SignaturePaddingEnum String[] getSignaturePaddings() {
        return ArrayUtils.cloneIfNotEmpty(mSignaturePaddings);
    }

    /**
     * Gets the set of digest algorithms (e.g., {@code SHA-256}, {@code SHA-384}) with which the key
     * can be used.
     *
     * <p>See {@link KeyProperties}.{@code DIGEST} constants.
     *
     * @throws IllegalStateException if this set has not been specified.
     *
     * @see #isDigestsSpecified()
     */
    @NonNull
    public @KeyProperties.DigestEnum String[] getDigests() {
        if (mDigests == null) {
            throw new IllegalStateException("Digests not specified");
        }
        return ArrayUtils.cloneIfNotEmpty(mDigests);
    }

    /**
     * Returns {@code true} if the set of digest algorithms with which the key can be used has been
     * specified.
     *
     * @see #getDigests()
     */
    public boolean isDigestsSpecified() {
        return mDigests != null;
    }

    /**
     * Gets the set of block modes (e.g., {@code GCM}, {@code CBC}) with which the key can be used
     * when encrypting/decrypting. Attempts to use the key with any other block modes will be
     * rejected.
     *
     * <p>See {@link KeyProperties}.{@code BLOCK_MODE} constants.
     */
    @NonNull
    public @KeyProperties.BlockModeEnum String[] getBlockModes() {
        return ArrayUtils.cloneIfNotEmpty(mBlockModes);
    }

    /**
     * Returns {@code true} if encryption using this key must be sufficiently randomized to produce
     * different ciphertexts for the same plaintext every time. The formal cryptographic property
     * being required is <em>indistinguishability under chosen-plaintext attack ({@code
     * IND-CPA})</em>. This property is important because it mitigates several classes of
     * weaknesses due to which ciphertext may leak information about plaintext. For example, if a
     * given plaintext always produces the same ciphertext, an attacker may see the repeated
     * ciphertexts and be able to deduce something about the plaintext.
     */
    public boolean isRandomizedEncryptionRequired() {
        return mRandomizedEncryptionRequired;
    }

    /**
     * Returns {@code true} if the key is authorized to be used only if the user has been
     * authenticated.
     *
     * <p>This authorization applies only to secret key and private key operations. Public key
     * operations are not restricted.
     *
     * @see #getUserAuthenticationValidityDurationSeconds()
     * @see Builder#setUserAuthenticationRequired(boolean)
     */
    public boolean isUserAuthenticationRequired() {
        return mUserAuthenticationRequired;
    }

    /**
     * Returns {@code true} if the key is authorized to be used only for messages confirmed by the
     * user.
     *
     * Confirmation is separate from user authentication (see
     * {@link #isUserAuthenticationRequired()}). Keys can be created that require confirmation but
     * not user authentication, or user authentication but not confirmation, or both. Confirmation
     * verifies that some user with physical possession of the device has approved a displayed
     * message. User authentication verifies that the correct user is present and has
     * authenticated.
     *
     * <p>This authorization applies only to secret key and private key operations. Public key
     * operations are not restricted.
     *
     * @see Builder#setUserConfirmationRequired(boolean)
     */
    public boolean isUserConfirmationRequired() {
        return mUserConfirmationRequired;
    }

    public @KeyProperties.AuthEnum int getUserAuthenticationType() {
        return mUserAuthenticationType;
    }

    /**
     * Gets the duration of time (seconds) for which this key is authorized to be used after the
     * user is successfully authenticated. This has effect only if user authentication is required
     * (see {@link #isUserAuthenticationRequired()}).
     *
     * <p>This authorization applies only to secret key and private key operations. Public key
     * operations are not restricted.
     *
     * @return duration in seconds or {@code -1} if authentication is required for every use of the
     *         key.
     *
     * @see #isUserAuthenticationRequired()
     * @see Builder#setUserAuthenticationValidityDurationSeconds(int)
     */
    public int getUserAuthenticationValidityDurationSeconds() {
        return mUserAuthenticationValidityDurationSeconds;
    }

    /**
     * Returns {@code true} if the key is authorized to be used only if a test of user presence has
     * been performed between the {@code Signature.initSign()} and {@code Signature.sign()} calls.
     * It requires that the KeyStore implementation have a direct way to validate the user presence
     * for example a KeyStore hardware backed strongbox can use a button press that is observable
     * in hardware. A test for user presence is tangential to authentication. The test can be part
     * of an authentication step as long as this step can be validated by the hardware protecting
     * the key and cannot be spoofed. For example, a physical button press can be used as a test of
     * user presence if the other pins connected to the button are not able to simulate a button
     * press. There must be no way for the primary processor to fake a button press, or that
     * button must not be used as a test of user presence.
     */
    public boolean isUserPresenceRequired() {
        return mUserPresenceRequred;
    }

    /**
     * Returns {@code true} if the key will be de-authorized when the device is removed from the
     * user's body.  This option has no effect on keys that don't have an authentication validity
     * duration, and has no effect if the device lacks an on-body sensor.
     *
     * <p>Authorization applies only to secret key and private key operations. Public key operations
     * are not restricted.
     *
     * @see #isUserAuthenticationRequired()
     * @see #getUserAuthenticationValidityDurationSeconds()
     * @see Builder#setUserAuthenticationValidWhileOnBody(boolean)
     */
    public boolean isUserAuthenticationValidWhileOnBody() {
        return mUserAuthenticationValidWhileOnBody;
    }

    /**
     * Returns {@code true} if the key is irreversibly invalidated when a new biometric is
     * enrolled or all enrolled biometrics are removed. This has effect only for keys that
     * require biometric user authentication for every use.
     *
     * @see #isUserAuthenticationRequired()
     * @see #getUserAuthenticationValidityDurationSeconds()
     * @see Builder#setInvalidatedByBiometricEnrollment(boolean)
     */
    public boolean isInvalidatedByBiometricEnrollment() {
        return mInvalidatedByBiometricEnrollment;
    }

    /**
     * Return the secure user id that this key should be bound to.
     *
     * Normally an authentication-bound key is tied to the secure user id of the current user
     * (either the root SID from GateKeeper for auth-bound keys with a timeout, or the authenticator
     * id of the current biometric set for keys requiring explicit biometric authorization).
     * If this parameter is set (this method returning non-zero value), the key should be tied to
     * the specified secure user id, overriding the logic above.
     *
     * This is only applicable when {@link #isUserAuthenticationRequired} is {@code true}
     *
     * @see KeymasterUtils#addUserAuthArgs
     * @hide
     */
    @TestApi
    public long getBoundToSpecificSecureUserId() {
        return mBoundToSecureUserId;
    }

    /**
     * Return whether this key is critical to the device encryption flow.
     *
     * @see android.security.KeyStore#FLAG_CRITICAL_TO_DEVICE_ENCRYPTION
     * @hide
     */
    public boolean isCriticalToDeviceEncryption() {
        return mCriticalToDeviceEncryption;
    }

    /**
     * Returns {@code true} if the screen must be unlocked for this key to be used for decryption or
     * signing. Encryption and signature verification will still be available when the screen is
     * locked.
     *
     * @see Builder#setUnlockedDeviceRequired(boolean)
     */
    public boolean isUnlockedDeviceRequired() {
        return mUnlockedDeviceRequired;
    }

    /**
     * Returns {@code true} if the key is protected by a Strongbox security chip.
     * @hide
     */
    public boolean isStrongBoxBacked() {
        return mIsStrongBoxBacked;
    }

    /**
     * Builder of {@link KeyProtection} instances.
     */
    public final static class Builder {
        private @KeyProperties.PurposeEnum int mPurposes;

        private Date mKeyValidityStart;
        private Date mKeyValidityForOriginationEnd;
        private Date mKeyValidityForConsumptionEnd;
        private @KeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
        private @KeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;
        private @KeyProperties.DigestEnum String[] mDigests;
        private @KeyProperties.BlockModeEnum String[] mBlockModes;
        private boolean mRandomizedEncryptionRequired = true;
        private boolean mUserAuthenticationRequired;
        private int mUserAuthenticationValidityDurationSeconds = 0;
        private @KeyProperties.AuthEnum int mUserAuthenticationType =
                KeyProperties.AUTH_BIOMETRIC_STRONG;
        private boolean mUserPresenceRequired = false;
        private boolean mUserAuthenticationValidWhileOnBody;
        private boolean mInvalidatedByBiometricEnrollment = true;
        private boolean mUserConfirmationRequired;
        private boolean mUnlockedDeviceRequired = false;

        private long mBoundToSecureUserId = GateKeeper.INVALID_SECURE_USER_ID;
        private boolean mCriticalToDeviceEncryption = false;
        private boolean mIsStrongBoxBacked = false;

        /**
         * Creates a new instance of the {@code Builder}.
         *
         * @param purposes set of purposes (e.g., encrypt, decrypt, sign) for which the key can be
         *        used. Attempts to use the key for any other purpose will be rejected.
         *
         *        <p>See {@link KeyProperties}.{@code PURPOSE} flags.
         */
        public Builder(@KeyProperties.PurposeEnum int purposes) {
            mPurposes = purposes;
        }

        /**
         * Sets the time instant before which the key is not yet valid.
         *
         * <p>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityStart(Date startDate) {
            mKeyValidityStart = Utils.cloneIfNotNull(startDate);
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid.
         *
         * <p>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityStart(Date)
         * @see #setKeyValidityForConsumptionEnd(Date)
         * @see #setKeyValidityForOriginationEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityEnd(Date endDate) {
            setKeyValidityForOriginationEnd(endDate);
            setKeyValidityForConsumptionEnd(endDate);
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for encryption and signing.
         *
         * <p>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityForConsumptionEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityForOriginationEnd(Date endDate) {
            mKeyValidityForOriginationEnd = Utils.cloneIfNotNull(endDate);
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for decryption and
         * verification.
         *
         * <p>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityForOriginationEnd(Date)
         */
        @NonNull
        public Builder setKeyValidityForConsumptionEnd(Date endDate) {
            mKeyValidityForConsumptionEnd = Utils.cloneIfNotNull(endDate);
            return this;
        }

        /**
         * Sets the set of padding schemes (e.g., {@code OAEPPadding}, {@code PKCS7Padding},
         * {@code NoPadding}) with which the key can be used when encrypting/decrypting. Attempts to
         * use the key with any other padding scheme will be rejected.
         *
         * <p>This must be specified for keys which are used for encryption/decryption.
         *
         * <p>For RSA private keys used by TLS/SSL servers to authenticate themselves to clients it
         * is usually necessary to authorize the use of no/any padding
         * ({@link KeyProperties#ENCRYPTION_PADDING_NONE}) and/or PKCS#1 encryption padding
         * ({@link KeyProperties#ENCRYPTION_PADDING_RSA_PKCS1}). This is because RSA decryption is
         * required by some cipher suites, and some stacks request decryption using no padding
         * whereas others request PKCS#1 padding.
         *
         * <p>See {@link KeyProperties}.{@code ENCRYPTION_PADDING} constants.
         */
        @NonNull
        public Builder setEncryptionPaddings(
                @KeyProperties.EncryptionPaddingEnum String... paddings) {
            mEncryptionPaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }

        /**
         * Sets the set of padding schemes (e.g., {@code PSS}, {@code PKCS#1}) with which the key
         * can be used when signing/verifying. Attempts to use the key with any other padding scheme
         * will be rejected.
         *
         * <p>This must be specified for RSA keys which are used for signing/verification.
         *
         * <p>See {@link KeyProperties}.{@code SIGNATURE_PADDING} constants.
         */
        @NonNull
        public Builder setSignaturePaddings(
                @KeyProperties.SignaturePaddingEnum String... paddings) {
            mSignaturePaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }

        /**
         * Sets the set of digest algorithms (e.g., {@code SHA-256}, {@code SHA-384}) with which the
         * key can be used. Attempts to use the key with any other digest algorithm will be
         * rejected.
         *
         * <p>This must be specified for signing/verification keys and RSA encryption/decryption
         * keys used with RSA OAEP padding scheme because these operations involve a digest. For
         * HMAC keys, the default is the digest specified in {@link Key#getAlgorithm()} (e.g.,
         * {@code SHA-256} for key algorithm {@code HmacSHA256}). HMAC keys cannot be authorized
         * for more than one digest.
         *
         * <p>For private keys used for TLS/SSL client or server authentication it is usually
         * necessary to authorize the use of no digest ({@link KeyProperties#DIGEST_NONE}). This is
         * because TLS/SSL stacks typically generate the necessary digest(s) themselves and then use
         * a private key to sign it.
         *
         * <p>See {@link KeyProperties}.{@code DIGEST} constants.
         */
        @NonNull
        public Builder setDigests(@KeyProperties.DigestEnum String... digests) {
            mDigests = ArrayUtils.cloneIfNotEmpty(digests);
            return this;
        }

        /**
         * Sets the set of block modes (e.g., {@code GCM}, {@code CBC}) with which the key can be
         * used when encrypting/decrypting. Attempts to use the key with any other block modes will
         * be rejected.
         *
         * <p>This must be specified for symmetric encryption/decryption keys.
         *
         * <p>See {@link KeyProperties}.{@code BLOCK_MODE} constants.
         */
        @NonNull
        public Builder setBlockModes(@KeyProperties.BlockModeEnum String... blockModes) {
            mBlockModes = ArrayUtils.cloneIfNotEmpty(blockModes);
            return this;
        }

        /**
         * Sets whether encryption using this key must be sufficiently randomized to produce
         * different ciphertexts for the same plaintext every time. The formal cryptographic
         * property being required is <em>indistinguishability under chosen-plaintext attack
         * ({@code IND-CPA})</em>. This property is important because it mitigates several classes
         * of weaknesses due to which ciphertext may leak information about plaintext. For example,
         * if a given plaintext always produces the same ciphertext, an attacker may see the
         * repeated ciphertexts and be able to deduce something about the plaintext.
         *
         * <p>By default, {@code IND-CPA} is required.
         *
         * <p>When {@code IND-CPA} is required:
         * <ul>
         * <li>transformation which do not offer {@code IND-CPA}, such as symmetric ciphers using
         * {@code ECB} mode or RSA encryption without padding, are prohibited;</li>
         * <li>in transformations which use an IV, such as symmetric ciphers in {@code GCM},
         * {@code CBC}, and {@code CTR} block modes, caller-provided IVs are rejected when
         * encrypting, to ensure that only random IVs are used.</li>
         *
         * <p>Before disabling this requirement, consider the following approaches instead:
         * <ul>
         * <li>If you are generating a random IV for encryption and then initializing a {@code}
         * Cipher using the IV, the solution is to let the {@code Cipher} generate a random IV
         * instead. This will occur if the {@code Cipher} is initialized for encryption without an
         * IV. The IV can then be queried via {@link Cipher#getIV()}.</li>
         * <li>If you are generating a non-random IV (e.g., an IV derived from something not fully
         * random, such as the name of the file being encrypted, or transaction ID, or password,
         * or a device identifier), consider changing your design to use a random IV which will then
         * be provided in addition to the ciphertext to the entities which need to decrypt the
         * ciphertext.</li>
         * <li>If you are using RSA encryption without padding, consider switching to padding
         * schemes which offer {@code IND-CPA}, such as PKCS#1 or OAEP.</li>
         * </ul>
         */
        @NonNull
        public Builder setRandomizedEncryptionRequired(boolean required) {
            mRandomizedEncryptionRequired = required;
            return this;
        }

        /**
         * Sets whether this key is authorized to be used only if the user has been authenticated.
         *
         * <p>By default, the key is authorized to be used regardless of whether the user has been
         * authenticated.
         *
         * <p>When user authentication is required:
         * <ul>
         * <li>The key can only be import if secure lock screen is set up (see
         * {@link KeyguardManager#isDeviceSecure()}). Additionally, if the key requires that user
         * authentication takes place for every use of the key (see
         * {@link #setUserAuthenticationValidityDurationSeconds(int)}), at least one biometric
         * must be enrolled (see {@link BiometricManager#canAuthenticate()}).</li>
         * <li>The use of the key must be authorized by the user by authenticating to this Android
         * device using a subset of their secure lock screen credentials such as
         * password/PIN/pattern or biometric.
         * <a href="{@docRoot}training/articles/keystore.html#UserAuthentication">More
         * information</a>.
         * <li>The key will become <em>irreversibly invalidated</em> once the secure lock screen is
         * disabled (reconfigured to None, Swipe or other mode which does not authenticate the user)
         * or when the secure lock screen is forcibly reset (e.g., by a Device Administrator).
         * Additionally, if the key requires that user authentication takes place for every use of
         * the key, it is also irreversibly invalidated once a new biometric is enrolled or once\
         * no more biometrics are enrolled, unless {@link
         * #setInvalidatedByBiometricEnrollment(boolean)} is used to allow validity after
         * enrollment. Attempts to initialize cryptographic operations using such keys will throw
         * {@link KeyPermanentlyInvalidatedException}.</li> </ul>
         *
         * <p>This authorization applies only to secret key and private key operations. Public key
         * operations are not restricted.
         *
         * @see #setUserAuthenticationValidityDurationSeconds(int)
         * @see KeyguardManager#isDeviceSecure()
         * @see BiometricManager#canAuthenticate()
         */
        @NonNull
        public Builder setUserAuthenticationRequired(boolean required) {
            mUserAuthenticationRequired = required;
            return this;
        }

        /**
         * Sets whether this key is authorized to be used only for messages confirmed by the
         * user.
         *
         * Confirmation is separate from user authentication (see
         * {@link #setUserAuthenticationRequired(boolean)}). Keys can be created that require
         * confirmation but not user authentication, or user authentication but not confirmation,
         * or both. Confirmation verifies that some user with physical possession of the device has
         * approved a displayed message. User authentication verifies that the correct user is
         * present and has authenticated.
         *
         * <p>This authorization applies only to secret key and private key operations. Public key
         * operations are not restricted.
         *
         * See {@link android.security.ConfirmationPrompt} class for
         * more details about user confirmations.
         */
        @NonNull
        public Builder setUserConfirmationRequired(boolean required) {
            mUserConfirmationRequired = required;
            return this;
        }

        /**
         * Sets the duration of time (seconds) for which this key is authorized to be used after the
         * user is successfully authenticated. This has effect if the key requires user
         * authentication for its use (see {@link #setUserAuthenticationRequired(boolean)}).
         *
         * <p>By default, if user authentication is required, it must take place for every use of
         * the key.
         *
         * <p>Cryptographic operations involving keys which require user authentication to take
         * place for every operation can only use biometric authentication. This is achieved by
         * initializing a cryptographic operation ({@link Signature}, {@link Cipher}, {@link Mac})
         * with the key, wrapping it into a {@link BiometricPrompt.CryptoObject}, invoking
         * {@code BiometricPrompt.authenticate} with {@code CryptoObject}, and proceeding with
         * the cryptographic operation only if the authentication flow succeeds.
         *
         * <p>Cryptographic operations involving keys which are authorized to be used for a duration
         * of time after a successful user authentication event can only use secure lock screen
         * authentication. These cryptographic operations will throw
         * {@link UserNotAuthenticatedException} during initialization if the user needs to be
         * authenticated to proceed. This situation can be resolved by the user unlocking the secure
         * lock screen of the Android or by going through the confirm credential flow initiated by
         * {@link KeyguardManager#createConfirmDeviceCredentialIntent(CharSequence, CharSequence)}.
         * Once resolved, initializing a new cryptographic operation using this key (or any other
         * key which is authorized to be used for a fixed duration of time after user
         * authentication) should succeed provided the user authentication flow completed
         * successfully.
         *
         * @param seconds duration in seconds or {@code -1} if user authentication must take place
         *        for every use of the key.
         *
         * @see #setUserAuthenticationRequired(boolean)
         * @see BiometricPrompt
         * @see BiometricPrompt.CryptoObject
         * @see KeyguardManager
         * @deprecated See {@link #setUserAuthenticationParameters(int, int)}
         */
        @Deprecated
        @NonNull
        public Builder setUserAuthenticationValidityDurationSeconds(
                @IntRange(from = -1) int seconds) {
            if (seconds < -1) {
                throw new IllegalArgumentException("seconds must be -1 or larger");
            }
            if (seconds == -1) {
                return setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            }
            return setUserAuthenticationParameters(seconds, KeyProperties.AUTH_DEVICE_CREDENTIAL
                                                            | KeyProperties.AUTH_BIOMETRIC_STRONG);
        }

        /**
         * Sets the duration of time (seconds) and authorization type for which this key is
         * authorized to be used after the user is successfully authenticated. This has effect if
         * the key requires user authentication for its use (see
         * {@link #setUserAuthenticationRequired(boolean)}).
         *
         * <p>By default, if user authentication is required, it must take place for every use of
         * the key.
         *
         * <p>These cryptographic operations will throw {@link UserNotAuthenticatedException} during
         * initialization if the user needs to be authenticated to proceed. This situation can be
         * resolved by the user authenticating with the appropriate biometric or credential as
         * required by the key. See {@link BiometricPrompt.Builder#setAllowedAuthenticators(int)}
         * and {@link BiometricManager.Authenticators}.
         *
         * <p>Once resolved, initializing a new cryptographic operation using this key (or any other
         * key which is authorized to be used for a fixed duration of time after user
         * authentication) should succeed provided the user authentication flow completed
         * successfully.
         *
         * @param timeout duration in seconds or {@code 0} if user authentication must take place
         *        for every use of the key.
         * @param type set of authentication types which can authorize use of the key. See
         *        {@link KeyProperties}.{@code AUTH} flags.
         *
         * @see #setUserAuthenticationRequired(boolean)
         * @see BiometricPrompt
         * @see BiometricPrompt.CryptoObject
         * @see KeyguardManager
         */
        @NonNull
        public Builder setUserAuthenticationParameters(@IntRange(from = 0) int timeout,
                                                       @KeyProperties.AuthEnum int type) {
            if (timeout < 0) {
                throw new IllegalArgumentException("timeout must be 0 or larger");
            }
            mUserAuthenticationValidityDurationSeconds = timeout;
            mUserAuthenticationType = type;
            return this;
        }

        /**
         * Sets whether a test of user presence is required to be performed between the
         * {@code Signature.initSign()} and {@code Signature.sign()} method calls. It requires that
         * the KeyStore implementation have a direct way to validate the user presence for example
         * a KeyStore hardware backed strongbox can use a button press that is observable in
         * hardware. A test for user presence is tangential to authentication. The test can be part
         * of an authentication step as long as this step can be validated by the hardware
         * protecting the key and cannot be spoofed. For example, a physical button press can be
         * used as a test of user presence if the other pins connected to the button are not able
         * to simulate a button press. There must be no way for the primary processor to fake a
         * button press, or that button must not be used as a test of user presence.
         */
        @NonNull
        public Builder setUserPresenceRequired(boolean required) {
            mUserPresenceRequired = required;
            return this;
        }

        /**
         * Sets whether the key will remain authorized only until the device is removed from the
         * user's body up to the limit of the authentication validity period (see
         * {@link #setUserAuthenticationValidityDurationSeconds} and
         * {@link #setUserAuthenticationRequired}). Once the device has been removed from the
         * user's body, the key will be considered unauthorized and the user will need to
         * re-authenticate to use it. For keys without an authentication validity period this
         * parameter has no effect.
         *
         * <p>Similarly, on devices that do not have an on-body sensor, this parameter will have no
         * effect; the device will always be considered to be "on-body" and the key will therefore
         * remain authorized until the validity period ends.
         *
         * @param remainsValid if {@code true}, and if the device supports on-body detection, key
         * will be invalidated when the device is removed from the user's body or when the
         * authentication validity expires, whichever occurs first.
         */
        @NonNull
        public Builder setUserAuthenticationValidWhileOnBody(boolean remainsValid) {
            mUserAuthenticationValidWhileOnBody = remainsValid;
            return this;
        }

        /**
         * Sets whether this key should be invalidated on biometric enrollment.  This
         * applies only to keys which require user authentication (see {@link
         * #setUserAuthenticationRequired(boolean)}) and if no positive validity duration has been
         * set (see {@link #setUserAuthenticationValidityDurationSeconds(int)}, meaning the key is
         * valid for biometric authentication only.
         *
         * <p>By default, {@code invalidateKey} is {@code true}, so keys that are valid for
         * biometric authentication only are <em>irreversibly invalidated</em> when a new
         * biometric is enrolled, or when all existing biometrics are deleted.  That may be
         * changed by calling this method with {@code invalidateKey} set to {@code false}.
         *
         * <p>Invalidating keys on enrollment of a new biometric or unenrollment of all biometrics
         * improves security by ensuring that an unauthorized person who obtains the password can't
         * gain the use of biometric-authenticated keys by enrolling their own biometric.  However,
         * invalidating keys makes key-dependent operations impossible, requiring some fallback
         * procedure to authenticate the user and set up a new key.
         */
        @NonNull
        public Builder setInvalidatedByBiometricEnrollment(boolean invalidateKey) {
            mInvalidatedByBiometricEnrollment = invalidateKey;
            return this;
        }

        /**
         * Set the secure user id that this key should be bound to.
         *
         * Normally an authentication-bound key is tied to the secure user id of the current user
         * (either the root SID from GateKeeper for auth-bound keys with a timeout, or the
         * authenticator id of the current biometric set for keys requiring explicit biometric
         * authorization). If this parameter is set (this method returning non-zero value), the key
         * should be tied to the specified secure user id, overriding the logic above.
         *
         * This is only applicable when {@link #setUserAuthenticationRequired} is set to
         * {@code true}
         *
         * @see KeyProtection#getBoundToSpecificSecureUserId()
         * @hide
         */
        @TestApi
        public Builder setBoundToSpecificSecureUserId(long secureUserId) {
            mBoundToSecureUserId = secureUserId;
            return this;
        }

        /**
         * Set whether this key is critical to the device encryption flow
         *
         * This is a special flag only available to system servers to indicate the current key
         * is part of the device encryption flow.
         *
         * @see android.security.KeyStore#FLAG_CRITICAL_TO_DEVICE_ENCRYPTION
         * @hide
         */
        public Builder setCriticalToDeviceEncryption(boolean critical) {
            mCriticalToDeviceEncryption = critical;
            return this;
        }

        /**
         * Sets whether the keystore requires the screen to be unlocked before allowing decryption
         * using this key. If this is set to {@code true}, any attempt to decrypt or sign using this
         * key while the screen is locked will fail. A locked device requires a PIN, password,
         * biometric, or other trusted factor to access. While the screen is locked, the key can
         * still be used for encryption or signature verification.
         */
        @NonNull
        public Builder setUnlockedDeviceRequired(boolean unlockedDeviceRequired) {
            mUnlockedDeviceRequired = unlockedDeviceRequired;
            return this;
        }

        /**
         * Sets whether this key should be protected by a StrongBox security chip.
         * @hide
         */
        @NonNull
        public Builder setIsStrongBoxBacked(boolean isStrongBoxBacked) {
            mIsStrongBoxBacked = isStrongBoxBacked;
            return this;
        }

        /**
         * Builds an instance of {@link KeyProtection}.
         *
         * @throws IllegalArgumentException if a required field is missing
         */
        @NonNull
        public KeyProtection build() {
            return new KeyProtection(
                    mKeyValidityStart,
                    mKeyValidityForOriginationEnd,
                    mKeyValidityForConsumptionEnd,
                    mPurposes,
                    mEncryptionPaddings,
                    mSignaturePaddings,
                    mDigests,
                    mBlockModes,
                    mRandomizedEncryptionRequired,
                    mUserAuthenticationRequired,
                    mUserAuthenticationType,
                    mUserAuthenticationValidityDurationSeconds,
                    mUserPresenceRequired,
                    mUserAuthenticationValidWhileOnBody,
                    mInvalidatedByBiometricEnrollment,
                    mBoundToSecureUserId,
                    mCriticalToDeviceEncryption,
                    mUserConfirmationRequired,
                    mUnlockedDeviceRequired,
                    mIsStrongBoxBacked);
        }
    }
}
