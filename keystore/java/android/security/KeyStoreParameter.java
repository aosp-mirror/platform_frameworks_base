/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;

import java.security.Key;
import java.security.KeyStore.ProtectionParameter;
import java.util.Date;

import javax.crypto.Cipher;

/**
 * Parameters specifying how to secure and restrict the use of a key being
 * imported into the
 * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore
 * facility</a>. The Android KeyStore facility is accessed through a
 * {@link java.security.KeyStore} API using the {@code AndroidKeyStore}
 * provider. The {@code context} passed in may be used to pop up some UI to ask
 * the user to unlock or initialize the Android KeyStore facility.
 * <p>
 * Any entries placed in the {@code KeyStore} may be retrieved later. Note that
 * there is only one logical instance of the {@code KeyStore} per application
 * UID so apps using the {@code sharedUid} facility will also share a
 * {@code KeyStore}.
 */
public final class KeyStoreParameter implements ProtectionParameter {
    private final Context mContext;
    private final int mFlags;
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyStoreKeyProperties.PurposeEnum int mPurposes;
    private final @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
    private final @KeyStoreKeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;
    private final @KeyStoreKeyProperties.DigestEnum String[] mDigests;
    private final @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;
    private final boolean mRandomizedEncryptionRequired;
    private final boolean mUserAuthenticationRequired;
    private final int mUserAuthenticationValidityDurationSeconds;

    private KeyStoreParameter(
            Context context,
            int flags,
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyProperties.PurposeEnum int purposes,
            @KeyStoreKeyProperties.EncryptionPaddingEnum String[] encryptionPaddings,
            @KeyStoreKeyProperties.SignaturePaddingEnum String[] signaturePaddings,
            @KeyStoreKeyProperties.DigestEnum String[] digests,
            @KeyStoreKeyProperties.BlockModeEnum String[] blockModes,
            boolean randomizedEncryptionRequired,
            boolean userAuthenticationRequired,
            int userAuthenticationValidityDurationSeconds) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        } else if ((userAuthenticationValidityDurationSeconds < 0)
                && (userAuthenticationValidityDurationSeconds != -1)) {
            throw new IllegalArgumentException(
                    "userAuthenticationValidityDurationSeconds must not be negative");
        }

        mContext = context;
        mFlags = flags;
        mKeyValidityStart = keyValidityStart;
        mKeyValidityForOriginationEnd = keyValidityForOriginationEnd;
        mKeyValidityForConsumptionEnd = keyValidityForConsumptionEnd;
        mPurposes = purposes;
        mEncryptionPaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(encryptionPaddings));
        mSignaturePaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(signaturePaddings));
        mDigests = ArrayUtils.cloneIfNotEmpty(digests);
        mBlockModes = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(blockModes));
        mRandomizedEncryptionRequired = randomizedEncryptionRequired;
        mUserAuthenticationRequired = userAuthenticationRequired;
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
    }

    /**
     * Gets the Android context used for operations with this instance.
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Returns {@code true} if this parameter requires entries to be encrypted
     * on the disk.
     */
    public boolean isEncryptionRequired() {
        return (mFlags & KeyStore.FLAG_ENCRYPTED) != 0;
    }

    /**
     * Gets the time instant before which the key is not yet valid.
     *
     * @return instant or {@code null} if not restricted.
     */
    public Date getKeyValidityStart() {
        return mKeyValidityStart;
    }

    /**
     * Gets the time instant after which the key is no long valid for decryption and verification.
     *
     * @return instant or {@code null} if not restricted.
     */
    public Date getKeyValidityForConsumptionEnd() {
        return mKeyValidityForConsumptionEnd;
    }

    /**
     * Gets the time instant after which the key is no long valid for encryption and signing.
     *
     * @return instant or {@code null} if not restricted.
     */
    public Date getKeyValidityForOriginationEnd() {
        return mKeyValidityForOriginationEnd;
    }

    /**
     * Gets the set of purposes for which the key can be used.
     */
    public @KeyStoreKeyProperties.PurposeEnum int getPurposes() {
        return mPurposes;
    }

    /**
     * Gets the set of padding schemes with which the key can be used when encrypting/decrypting.
     */
    public @KeyStoreKeyProperties.EncryptionPaddingEnum String[] getEncryptionPaddings() {
        return ArrayUtils.cloneIfNotEmpty(mEncryptionPaddings);
    }

    /**
     * Gets the set of padding schemes with which the key can be used when signing or verifying
     * signatures.
     */
    public @KeyStoreKeyProperties.SignaturePaddingEnum String[] getSignaturePaddings() {
        return ArrayUtils.cloneIfNotEmpty(mSignaturePaddings);
    }

    /**
     * Gets the set of digest algorithms with which the key can be used.
     *
     * @throws IllegalStateException if this set has not been specified.
     *
     * @see #isDigestsSpecified()
     */
    public @KeyStoreKeyProperties.DigestEnum String[] getDigests() {
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
     * Gets the set of block modes with which the key can be used.
     */
    public @KeyStoreKeyProperties.BlockModeEnum String[] getBlockModes() {
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
     * Returns {@code true} if user authentication is required for this key to be used.
     *
     * @see #getUserAuthenticationValidityDurationSeconds()
     */
    public boolean isUserAuthenticationRequired() {
        return mUserAuthenticationRequired;
    }

    /**
     * Gets the duration of time (seconds) for which this key can be used after the user is
     * successfully authenticated. This has effect only if user authentication is required.
     *
     * @return duration in seconds or {@code -1} if authentication is required for every use of the
     *         key.
     *
     * @see #isUserAuthenticationRequired()
     */
    public int getUserAuthenticationValidityDurationSeconds() {
        return mUserAuthenticationValidityDurationSeconds;
    }

    /**
     * Builder class for {@link KeyStoreParameter} objects.
     * <p>
     * This will build protection parameters for use with the
     * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore
     * facility</a>.
     * <p>
     * This can be used to require that KeyStore entries be stored encrypted.
     * <p>
     * Example:
     *
     * <pre class="prettyprint">
     * KeyStoreParameter params = new KeyStoreParameter.Builder(mContext)
     *         .setEncryptionRequired()
     *         .build();
     * </pre>
     */
    public final static class Builder {
        private final Context mContext;
        private int mFlags;
        private Date mKeyValidityStart;
        private Date mKeyValidityForOriginationEnd;
        private Date mKeyValidityForConsumptionEnd;
        private @KeyStoreKeyProperties.PurposeEnum int mPurposes;
        private @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
        private @KeyStoreKeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;
        private @KeyStoreKeyProperties.DigestEnum String[] mDigests;
        private @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;
        private boolean mRandomizedEncryptionRequired = true;
        private boolean mUserAuthenticationRequired;
        private int mUserAuthenticationValidityDurationSeconds = -1;

        /**
         * Creates a new instance of the {@code Builder} with the given
         * {@code context}. The {@code context} passed in may be used to pop up
         * some UI to ask the user to unlock or initialize the Android KeyStore
         * facility.
         */
        public Builder(Context context) {
            if (context == null) {
                throw new NullPointerException("context == null");
            }
            mContext = context;
        }

        /**
         * Indicates that this key must be encrypted at rest on storage. Note
         * that enabling this will require that the user enable a strong lock
         * screen (e.g., PIN, password) before creating or using the generated
         * key is successful.
         */
        public Builder setEncryptionRequired(boolean required) {
            if (required) {
                mFlags |= KeyStore.FLAG_ENCRYPTED;
            } else {
                mFlags &= ~KeyStore.FLAG_ENCRYPTED;
            }
            return this;
        }

        /**
         * Sets the time instant before which the key is not yet valid.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         *
         * @see #setKeyValidityEnd(Date)
         */
        public Builder setKeyValidityStart(Date startDate) {
            mKeyValidityStart = startDate;
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         *
         * @see #setKeyValidityStart(Date)
         * @see #setKeyValidityForConsumptionEnd(Date)
         * @see #setKeyValidityForOriginationEnd(Date)
         */
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
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         *
         * @see #setKeyValidityForConsumptionEnd(Date)
         */
        public Builder setKeyValidityForOriginationEnd(Date endDate) {
            mKeyValidityForOriginationEnd = endDate;
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for decryption and
         * verification.
         *
         * <p>By default, the key is valid at any instant.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         *
         * @see #setKeyValidityForOriginationEnd(Date)
         */
        public Builder setKeyValidityForConsumptionEnd(Date endDate) {
            mKeyValidityForConsumptionEnd = endDate;
            return this;
        }

        /**
         * Sets the set of purposes for which the key can be used.
         *
         * <p>This must be specified for all keys. There is no default.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         */
        public Builder setPurposes(@KeyStoreKeyProperties.PurposeEnum int purposes) {
            mPurposes = purposes;
            return this;
        }

        /**
         * Sets the set of padding schemes with which the key can be used when
         * encrypting/decrypting. Attempts to use the key with any other padding scheme will be
         * rejected.
         *
         * <p>This must be specified for keys which are used for encryption/decryption.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         */
        public Builder setEncryptionPaddings(
                @KeyStoreKeyProperties.EncryptionPaddingEnum String... paddings) {
            mEncryptionPaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }

        /**
         * Sets the set of padding schemes with which the key can be used when
         * signing/verifying. Attempts to use the key with any other padding scheme will be
         * rejected.
         *
         * <p>This must be specified for RSA keys which are used for signing/verification.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         */
        public Builder setSignaturePaddings(
                @KeyStoreKeyProperties.SignaturePaddingEnum String... paddings) {
            mSignaturePaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }


        /**
         * Sets the set of digests with which the key can be used when signing/verifying or
         * generating MACs. Attempts to use the key with any other digest will be rejected.
         *
         * <p>For HMAC keys, the default is the digest specified in {@link Key#getAlgorithm()}. For
         * asymmetric signing keys this constraint must be specified.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         */
        public Builder setDigests(@KeyStoreKeyProperties.DigestEnum String... digests) {
            mDigests = ArrayUtils.cloneIfNotEmpty(digests);
            return this;
        }

        /**
         * Sets the set of block modes with which the key can be used when encrypting/decrypting.
         * Attempts to use the key with any other block modes will be rejected.
         *
         * <p>This must be specified for encryption/decryption keys.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         */
        public Builder setBlockModes(@KeyStoreKeyProperties.BlockModeEnum String... blockModes) {
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
         * <li>in transformations which use an IV, such as symmetric ciphers in {@code CBC},
         * {@code CTR}, and {@code GCM} block modes, caller-provided IVs are rejected when
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
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         */
        public Builder setRandomizedEncryptionRequired(boolean required) {
            mRandomizedEncryptionRequired = required;
            return this;
        }

        /**
         * Sets whether user authentication is required to use this key.
         *
         * <p>By default, the key can be used without user authentication.
         *
         * <p>When user authentication is required, the user authorizes the use of the key by
         * authenticating to this Android device using a subset of their secure lock screen
         * credentials. Different authentication methods are used depending on whether the every
         * use of the key must be authenticated (as specified by
         * {@link #setUserAuthenticationValidityDurationSeconds(int)}).
         * <a href="{@docRoot}training/articles/keystore.html#UserAuthentication">More
         * information</a>.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         *
         * @see #setUserAuthenticationValidityDurationSeconds(int)
         */
        public Builder setUserAuthenticationRequired(boolean required) {
            mUserAuthenticationRequired = required;
            return this;
        }

        /**
         * Sets the duration of time (seconds) for which this key can be used after the user is
         * successfully authenticated. This has effect only if user authentication is required.
         *
         * <p>By default, the user needs to authenticate for every use of the key.
         *
         * <p><b>NOTE: This has currently no effect on asymmetric key pairs.
         *
         * @param seconds duration in seconds or {@code -1} if the user needs to authenticate for
         *        every use of the key.
         *
         * @see #setUserAuthenticationRequired(boolean)
         */
        public Builder setUserAuthenticationValidityDurationSeconds(int seconds) {
            mUserAuthenticationValidityDurationSeconds = seconds;
            return this;
        }

        /**
         * Builds the instance of the {@code KeyStoreParameter}.
         *
         * @throws IllegalArgumentException if a required field is missing
         * @return built instance of {@code KeyStoreParameter}
         */
        public KeyStoreParameter build() {
            return new KeyStoreParameter(
                    mContext,
                    mFlags,
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
                    mUserAuthenticationValidityDurationSeconds);
        }
    }
}
