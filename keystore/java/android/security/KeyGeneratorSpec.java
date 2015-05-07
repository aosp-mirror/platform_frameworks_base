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

import android.content.Context;
import android.text.TextUtils;

import java.security.spec.AlgorithmParameterSpec;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * {@link AlgorithmParameterSpec} for initializing a {@code KeyGenerator} that works with
 * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore facility</a>.
 *
 * <p>The Android KeyStore facility is accessed through a {@link KeyGenerator} API using the
 * {@code AndroidKeyStore} provider. The {@code context} passed in may be used to pop up some UI to
 * ask the user to unlock or initialize the Android KeyStore facility.
 *
 * <p>After generation, the {@code keyStoreAlias} is used with the
 * {@link java.security.KeyStore#getEntry(String, java.security.KeyStore.ProtectionParameter)}
 * interface to retrieve the {@link SecretKey}.
 */
public class KeyGeneratorSpec implements AlgorithmParameterSpec {

    private final Context mContext;
    private final String mKeystoreAlias;
    private final int mFlags;
    private final int mKeySize;
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyStoreKeyProperties.PurposeEnum int mPurposes;
    private final @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
    private final @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;
    private final boolean mRandomizedEncryptionRequired;
    private final boolean mUserAuthenticationRequired;
    private final int mUserAuthenticationValidityDurationSeconds;

    private KeyGeneratorSpec(
            Context context,
            String keyStoreAlias,
            int flags,
            int keySize,
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyProperties.PurposeEnum int purposes,
            @KeyStoreKeyProperties.EncryptionPaddingEnum String[] encryptionPaddings,
            @KeyStoreKeyProperties.BlockModeEnum String[] blockModes,
            boolean randomizedEncryptionRequired,
            boolean userAuthenticationRequired,
            int userAuthenticationValidityDurationSeconds) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        } else if (TextUtils.isEmpty(keyStoreAlias)) {
            throw new IllegalArgumentException("keyStoreAlias must not be empty");
        } else if ((userAuthenticationValidityDurationSeconds < 0)
                && (userAuthenticationValidityDurationSeconds != -1)) {
            throw new IllegalArgumentException(
                    "userAuthenticationValidityDurationSeconds must not be negative");
        }

        mContext = context;
        mKeystoreAlias = keyStoreAlias;
        mFlags = flags;
        mKeySize = keySize;
        mKeyValidityStart = keyValidityStart;
        mKeyValidityForOriginationEnd = keyValidityForOriginationEnd;
        mKeyValidityForConsumptionEnd = keyValidityForConsumptionEnd;
        mPurposes = purposes;
        mEncryptionPaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(encryptionPaddings));
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
     * Returns the alias that will be used in the {@code java.security.KeyStore} in conjunction with
     * the {@code AndroidKeyStore}.
     */
    public String getKeystoreAlias() {
        return mKeystoreAlias;
    }

    /**
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Returns the requested key size or {@code -1} if default size should be used.
     */
    public int getKeySize() {
        return mKeySize;
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
     * Gets the time instant after which the key is no longer valid for decryption and verification.
     *
     * @return instant or {@code null} if not restricted.
     */
    public Date getKeyValidityForConsumptionEnd() {
        return mKeyValidityForConsumptionEnd;
    }

    /**
     * Gets the time instant after which the key is no longer valid for encryption and signing.
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
     * successfully authenticated.
     *
     * @return duration in seconds or {@code -1} if not restricted. {@code 0} means authentication
     *         is required for every use of the key.
     */
    public int getUserAuthenticationValidityDurationSeconds() {
        return mUserAuthenticationValidityDurationSeconds;
    }

    /**
     * Returns {@code true} if the key must be encrypted in the {@link java.security.KeyStore}.
     */
    public boolean isEncryptionRequired() {
        return (mFlags & KeyStore.FLAG_ENCRYPTED) != 0;
    }

    public static class Builder {
        private final Context mContext;
        private String mKeystoreAlias;
        private int mFlags;
        private int mKeySize = -1;
        private Date mKeyValidityStart;
        private Date mKeyValidityForOriginationEnd;
        private Date mKeyValidityForConsumptionEnd;
        private @KeyStoreKeyProperties.PurposeEnum int mPurposes;
        private @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
        private @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;
        private boolean mRandomizedEncryptionRequired = true;
        private boolean mUserAuthenticationRequired;
        private int mUserAuthenticationValidityDurationSeconds = -1;

        /**
         * Creates a new instance of the {@code Builder} with the given {@code context}. The
         * {@code context} passed in may be used to pop up some UI to ask the user to unlock or
         * initialize the Android KeyStore facility.
         */
        public Builder(Context context) {
            if (context == null) {
                throw new NullPointerException("context == null");
            }
            mContext = context;
        }

        /**
         * Sets the alias to be used to retrieve the key later from a {@link java.security.KeyStore}
         * instance using the {@code AndroidKeyStore} provider.
         *
         * <p>The alias must be provided. There is no default.
         */
        public Builder setAlias(String alias) {
            if (alias == null) {
                throw new NullPointerException("alias == null");
            }
            mKeystoreAlias = alias;
            return this;
        }

        /**
         * Sets the size (in bits) of the key to be generated.
         *
         * <p>By default, the key size will be determines based on the key algorithm. For example,
         * for {@code HmacSHA256}, the key size will default to {@code 256}.
         */
        public Builder setKeySize(int keySize) {
            mKeySize = keySize;
            return this;
        }

        /**
         * Indicates that this key must be encrypted at rest on storage. Note that enabling this
         * will require that the user enable a strong lock screen (e.g., PIN, password) before
         * creating or using the generated key is successful.
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
         */
        public Builder setEncryptionPaddings(
                @KeyStoreKeyProperties.EncryptionPaddingEnum String... paddings) {
            mEncryptionPaddings = ArrayUtils.cloneIfNotEmpty(paddings);
            return this;
        }

        /**
         * Sets the set of block modes with which the key can be used when encrypting/decrypting.
         * Attempts to use the key with any other block modes will be rejected.
         *
         * <p>This must be specified for encryption/decryption keys.
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
         * <li>block modes which do not offer {@code IND-CPA}, such as {@code ECB}, are prohibited;
         * </li>
         * <li>in block modes which use an IV, such as {@code CBC}, {@code CTR}, and {@code GCM},
         * caller-provided IVs are rejected when encrypting, to ensure that only random IVs are
         * used.</li>
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
         * </ul>
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
         * @param seconds duration in seconds or {@code 0} if the user needs to authenticate for
         *        every use of the key.
         *
         * @see #setUserAuthenticationRequired(boolean)
         */
        public Builder setUserAuthenticationValidityDurationSeconds(int seconds) {
            mUserAuthenticationValidityDurationSeconds = seconds;
            return this;
        }

        /**
         * Builds a new instance instance of {@code KeyGeneratorSpec}.
         *
         * @throws IllegalArgumentException if a required field is missing or violates a constraint.
         */
        public KeyGeneratorSpec build() {
            return new KeyGeneratorSpec(mContext,
                    mKeystoreAlias,
                    mFlags,
                    mKeySize,
                    mKeyValidityStart,
                    mKeyValidityForOriginationEnd,
                    mKeyValidityForConsumptionEnd,
                    mPurposes,
                    mEncryptionPaddings,
                    mBlockModes,
                    mRandomizedEncryptionRequired,
                    mUserAuthenticationRequired,
                    mUserAuthenticationValidityDurationSeconds);
        }
    }
}
