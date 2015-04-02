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

import java.security.KeyPairGenerator;
import java.security.KeyStore.ProtectionParameter;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * This provides the optional parameters that can be specified for
 * {@code KeyStore} entries that work with
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
 * <p>
 * Keys may be generated using the {@link KeyPairGenerator} facility with a
 * {@link KeyPairGeneratorSpec} to specify the entry's {@code alias}. A
 * self-signed X.509 certificate will be attached to generated entries, but that
 * may be replaced at a later time by a certificate signed by a real Certificate
 * Authority.
 */
public final class KeyStoreParameter implements ProtectionParameter {
    private int mFlags;
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyStoreKeyConstraints.PurposeEnum Integer mPurposes;
    private final @KeyStoreKeyConstraints.AlgorithmEnum Integer mAlgorithm;
    private final @KeyStoreKeyConstraints.PaddingEnum Integer mPadding;
    private final @KeyStoreKeyConstraints.DigestEnum Integer mDigest;
    private final @KeyStoreKeyConstraints.BlockModeEnum Integer mBlockMode;
    private final Integer mMinSecondsBetweenOperations;
    private final Integer mMaxUsesPerBoot;
    private final Set<Integer> mUserAuthenticators;
    private final Integer mUserAuthenticationValidityDurationSeconds;

    private KeyStoreParameter(int flags,
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyConstraints.PurposeEnum Integer purposes,
            @KeyStoreKeyConstraints.AlgorithmEnum Integer algorithm,
            @KeyStoreKeyConstraints.PaddingEnum Integer padding,
            @KeyStoreKeyConstraints.DigestEnum Integer digest,
            @KeyStoreKeyConstraints.BlockModeEnum Integer blockMode,
            Integer minSecondsBetweenOperations,
            Integer maxUsesPerBoot,
            Set<Integer> userAuthenticators,
            Integer userAuthenticationValidityDurationSeconds) {
        if ((userAuthenticationValidityDurationSeconds != null)
                && (userAuthenticationValidityDurationSeconds < 0)) {
            throw new IllegalArgumentException(
                    "userAuthenticationValidityDurationSeconds must not be negative");
        }

        mFlags = flags;
        mKeyValidityStart = keyValidityStart;
        mKeyValidityForOriginationEnd = keyValidityForOriginationEnd;
        mKeyValidityForConsumptionEnd = keyValidityForConsumptionEnd;
        mPurposes = purposes;
        mAlgorithm = algorithm;
        mPadding = padding;
        mDigest = digest;
        mBlockMode = blockMode;
        mMinSecondsBetweenOperations = minSecondsBetweenOperations;
        mMaxUsesPerBoot = maxUsesPerBoot;
        mUserAuthenticators = (userAuthenticators != null)
                ? new HashSet<Integer>(userAuthenticators)
                : Collections.<Integer>emptySet();
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
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
     * @hide
     */
    public Date getKeyValidityStart() {
        return mKeyValidityStart;
    }

    /**
     * Gets the time instant after which the key is no long valid for decryption and verification.
     *
     * @return instant or {@code null} if not restricted.
     *
     * @hide
     */
    public Date getKeyValidityForConsumptionEnd() {
        return mKeyValidityForConsumptionEnd;
    }

    /**
     * Gets the time instant after which the key is no long valid for encryption and signing.
     *
     * @return instant or {@code null} if not restricted.
     *
     * @hide
     */
    public Date getKeyValidityForOriginationEnd() {
        return mKeyValidityForOriginationEnd;
    }

    /**
     * Gets the set of purposes for which the key can be used to the provided set of purposes.
     *
     * @return set of purposes or {@code null} if the key can be used for any purpose.
     *
     * @hide
     */
    public @KeyStoreKeyConstraints.PurposeEnum Integer getPurposes() {
        return mPurposes;
    }

    /**
     * Gets the algorithm to which the key is restricted.
     *
     * @return algorithm or {@code null} if it's not restricted.
     * @hide
     */
    public @KeyStoreKeyConstraints.AlgorithmEnum Integer getAlgorithm() {
        return mAlgorithm;
    }

    /**
     * Gets the padding scheme to which the key is restricted.
     *
     * @return padding scheme or {@code null} if the padding scheme is not restricted.
     *
     * @hide
     */
    public @KeyStoreKeyConstraints.PaddingEnum Integer getPadding() {
        return mPadding;
    }

    /**
     * Gets the digest to which the key is restricted when generating signatures or Message
     * Authentication Codes (MACs).
     *
     * @return digest or {@code null} if the digest is not restricted.
     *
     * @hide
     */
    public @KeyStoreKeyConstraints.DigestEnum Integer getDigest() {
        return mDigest;
    }

    /**
     * Gets the block mode to which the key is restricted when used for encryption or decryption.
     *
     * @return block more or {@code null} if block mode is not restricted.
     *
     * @hide
     */
    public @KeyStoreKeyConstraints.BlockModeEnum Integer getBlockMode() {
        return mBlockMode;
    }

    /**
     * Gets the minimum number of seconds that must expire since the most recent use of the key
     * before it can be used again.
     *
     * @return number of seconds or {@code null} if there is no restriction on how frequently a key
     *         can be used.
     *
     * @hide
     */
    public Integer getMinSecondsBetweenOperations() {
        return mMinSecondsBetweenOperations;
    }

    /**
     * Gets the number of times the key can be used without rebooting the device.
     *
     * @return maximum number of times or {@code null} if there is no restriction.
     * @hide
     */
    public Integer getMaxUsesPerBoot() {
        return mMaxUsesPerBoot;
    }

    /**
     * Gets the user authenticators which protect access to this key. The key can only be used iff
     * the user has authenticated to at least one of these user authenticators.
     *
     * @return user authenticators or empty set if the key can be used without user authentication.
     *
     * @hide
     */
    public Set<Integer> getUserAuthenticators() {
        return new HashSet<Integer>(mUserAuthenticators);
    }

    /**
     * Gets the duration of time (seconds) for which this key can be used after the user
     * successfully authenticates to one of the associated user authenticators.
     *
     * @return duration in seconds or {@code null} if not restricted. {@code 0} means authentication
     *         is required for every use of the key.
     *
     * @hide
     */
    public Integer getUserAuthenticationValidityDurationSeconds() {
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
        private int mFlags;
        private Date mKeyValidityStart;
        private Date mKeyValidityForOriginationEnd;
        private Date mKeyValidityForConsumptionEnd;
        private @KeyStoreKeyConstraints.PurposeEnum Integer mPurposes;
        private @KeyStoreKeyConstraints.AlgorithmEnum Integer mAlgorithm;
        private @KeyStoreKeyConstraints.PaddingEnum Integer mPadding;
        private @KeyStoreKeyConstraints.DigestEnum Integer mDigest;
        private @KeyStoreKeyConstraints.BlockModeEnum Integer mBlockMode;
        private Integer mMinSecondsBetweenOperations;
        private Integer mMaxUsesPerBoot;
        private Set<Integer> mUserAuthenticators;
        private Integer mUserAuthenticationValidityDurationSeconds;

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

            // Context is currently not used, but will be in the future.
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
         * <b>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityEnd(Date)
         *
         * @hide
         */
        public Builder setKeyValidityStart(Date startDate) {
            mKeyValidityStart = startDate;
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid.
         *
         * <b>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityStart(Date)
         * @see #setKeyValidityForConsumptionEnd(Date)
         * @see #setKeyValidityForOriginationEnd(Date)
         *
         * @hide
         */
        public Builder setKeyValidityEnd(Date endDate) {
            setKeyValidityForOriginationEnd(endDate);
            setKeyValidityForConsumptionEnd(endDate);
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for encryption and signing.
         *
         * <b>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityForConsumptionEnd(Date)
         *
         * @hide
         */
        public Builder setKeyValidityForOriginationEnd(Date endDate) {
            mKeyValidityForOriginationEnd = endDate;
            return this;
        }

        /**
         * Sets the time instant after which the key is no longer valid for decryption and
         * verification.
         *
         * <b>By default, the key is valid at any instant.
         *
         * @see #setKeyValidityForOriginationEnd(Date)
         *
         * @hide
         */
        public Builder setKeyValidityForConsumptionEnd(Date endDate) {
            mKeyValidityForConsumptionEnd = endDate;
            return this;
        }

        /**
         * Restricts the purposes for which the key can be used to the provided set of purposes.
         *
         * <p>By default, the key can be used for encryption, decryption, signing, and verification.
         *
         * @hide
         */
        public Builder setPurposes(@KeyStoreKeyConstraints.PurposeEnum int purposes) {
            mPurposes = purposes;
            return this;
        }

        /**
         * Sets the algorithm of the key.
         *
         * <p>The algorithm of symmetric keys can be deduced from the key itself. Thus, explicitly
         * specifying the algorithm of symmetric keys using this method is not necessary.
         *
         * @hide
         */
        public Builder setAlgorithm(@KeyStoreKeyConstraints.AlgorithmEnum int algorithm) {
            mAlgorithm = algorithm;
            return this;
        }

        /**
         * Restricts the key to being used only with the provided padding scheme. Attempts to use
         * the key with any other padding will be rejected.
         *
         * <p>This restriction must be specified for keys which are used for encryption/decryption.
         *
         * @hide
         */
        public Builder setPadding(@KeyStoreKeyConstraints.PaddingEnum int padding) {
            mPadding = padding;
            return this;
        }

        /**
         * Restricts the key to being used only with the provided digest when generating signatures
         * or Message Authentication Codes (MACs). Attempts to use the key with any other digest
         * will be rejected.
         *
         * <p>For MAC keys, the default is to restrict to the digest specified in the key algorithm
         * name. For asymmetric signing keys this constraint must be specified because there is no
         * default.
         *
         * @see java.security.Key#getAlgorithm()
         *
         * @hide
         */
        public Builder setDigest(@KeyStoreKeyConstraints.DigestEnum int digest) {
            mDigest = digest;
            return this;
        }

        /**
         * Restricts the key to being used only with the provided block mode when encrypting or
         * decrypting. Attempts to use the key with any other block modes will be rejected.
         *
         * <p>This restriction must be specified for keys which are used for encryption/decryption.
         *
         * @hide
         */
        public Builder setBlockMode(@KeyStoreKeyConstraints.BlockModeEnum int blockMode) {
            mBlockMode = blockMode;
            return this;
        }

        /**
         * Sets the minimum number of seconds that must expire since the most recent use of the key
         * before it can be used again.
         *
         * <p>By default, there is no restriction on how frequently a key can be used.
         *
         * @hide
         */
        public Builder setMinSecondsBetweenOperations(int seconds) {
            mMinSecondsBetweenOperations = seconds;
            return this;
        }

        /**
         * Sets the maximum number of times a key can be used without rebooting the device.
         *
         * <p>By default, the key can be used for an unlimited number of times.
         *
         * @hide
         */
        public Builder setMaxUsesPerBoot(int count) {
            mMaxUsesPerBoot = count;
            return this;
        }

        /**
         * Sets the user authenticators which protect access to this key. The key can only be used
         * iff the user has authenticated to at least one of these user authenticators.
         *
         * <p>By default, the key can be used without user authentication.
         *
         * @param userAuthenticators user authenticators or empty list if this key can be accessed
         *        without user authentication.
         *
         * @see #setUserAuthenticationValidityDurationSeconds(int)
         *
         * @hide
         */
        public Builder setUserAuthenticators(Set<Integer> userAuthenticators) {
            mUserAuthenticators =
                    (userAuthenticators != null) ? new HashSet<Integer>(userAuthenticators) : null;
            return this;
        }

        /**
         * Sets the duration of time (seconds) for which this key can be used after the user
         * successfully authenticates to one of the associated user authenticators.
         *
         * <p>By default, the user needs to authenticate for every use of the key.
         *
         * @param seconds duration in seconds or {@code 0} if the user needs to authenticate for
         *        every use of the key.
         *
         * @see #setUserAuthenticators(Set)
         *
         * @hide
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
            return new KeyStoreParameter(mFlags,
                    mKeyValidityStart,
                    mKeyValidityForOriginationEnd,
                    mKeyValidityForConsumptionEnd,
                    mPurposes,
                    mAlgorithm,
                    mPadding,
                    mDigest,
                    mBlockMode,
                    mMinSecondsBetweenOperations,
                    mMaxUsesPerBoot,
                    mUserAuthenticators,
                    mUserAuthenticationValidityDurationSeconds);
        }
    }
}
