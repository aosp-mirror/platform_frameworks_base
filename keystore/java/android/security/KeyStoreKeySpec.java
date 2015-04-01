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

import java.security.spec.KeySpec;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Information about a key from the <a href="{@docRoot}training/articles/keystore.html">Android
 * KeyStore</a>.
 *
 * @hide
 */
public class KeyStoreKeySpec implements KeySpec {
    private final String mKeystoreAlias;
    private final int mKeySize;
    private final @KeyStoreKeyCharacteristics.OriginEnum int mOrigin;
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyStoreKeyConstraints.PurposeEnum int mPurposes;
    private final @KeyStoreKeyConstraints.AlgorithmEnum int mAlgorithm;
    private final @KeyStoreKeyConstraints.PaddingEnum Integer mPadding;
    private final @KeyStoreKeyConstraints.DigestEnum Integer mDigest;
    private final @KeyStoreKeyConstraints.BlockModeEnum Integer mBlockMode;
    private final Integer mMinSecondsBetweenOperations;
    private final Integer mMaxUsesPerBoot;
    private final Set<Integer> mUserAuthenticators;
    private final Set<Integer> mTeeBackedUserAuthenticators;
    private final Integer mUserAuthenticationValidityDurationSeconds;


    /**
     * @hide
     */
    KeyStoreKeySpec(String keystoreKeyAlias,
            @KeyStoreKeyCharacteristics.OriginEnum int origin,
            int keySize, Date keyValidityStart, Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyConstraints.PurposeEnum int purposes,
            @KeyStoreKeyConstraints.AlgorithmEnum int algorithm,
            @KeyStoreKeyConstraints.PaddingEnum Integer padding,
            @KeyStoreKeyConstraints.DigestEnum Integer digest,
            @KeyStoreKeyConstraints.BlockModeEnum Integer blockMode,
            Integer minSecondsBetweenOperations,
            Integer maxUsesPerBoot,
            Set<Integer> userAuthenticators,
            Set<Integer> teeBackedUserAuthenticators,
            Integer userAuthenticationValidityDurationSeconds) {
        mKeystoreAlias = keystoreKeyAlias;
        mOrigin = origin;
        mKeySize = keySize;
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
        mTeeBackedUserAuthenticators = (teeBackedUserAuthenticators != null)
                ? new HashSet<Integer>(teeBackedUserAuthenticators)
                : Collections.<Integer>emptySet();
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
    }

    /**
     * Gets the entry alias under which the key is stored in the {@code AndroidKeyStore}.
     */
    public String getKeystoreAlias() {
        return mKeystoreAlias;
    }

    /**
     * Gets the origin of the key.
     */
    public @KeyStoreKeyCharacteristics.OriginEnum int getOrigin() {
        return mOrigin;
    }

    /**
     * Gets the key's size in bits.
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
    public @KeyStoreKeyConstraints.PurposeEnum int getPurposes() {
        return mPurposes;
    }

    /**
     * Gets the algorithm of the key.
     */
    public @KeyStoreKeyConstraints.AlgorithmEnum int getAlgorithm() {
        return mAlgorithm;
    }

    /**
     * Gets the only block mode with which the key can be used.
     *
     * @return block mode or {@code null} if the block mode is not restricted.
     */
    public @KeyStoreKeyConstraints.BlockModeEnum Integer getBlockMode() {
        return mBlockMode;
    }

    /**
     * Gets the only padding mode with which the key can be used.
     *
     * @return padding mode or {@code null} if the padding mode is not restricted.
     */
    public @KeyStoreKeyConstraints.PaddingEnum Integer getPadding() {
        return mPadding;
    }

    /**
     * Gets the only digest algorithm with which the key can be used.
     *
     * @return digest algorithm or {@code null} if the digest algorithm is not restricted.
     */
    public @KeyStoreKeyConstraints.DigestEnum Integer getDigest() {
        return mDigest;
    }

    /**
     * Gets the minimum number of seconds that must expire since the most recent use of the key
     * before it can be used again.
     *
     * @return number of seconds or {@code null} if there is no restriction on how frequently a key
     *         can be used.
     */
    public Integer getMinSecondsBetweenOperations() {
        return mMinSecondsBetweenOperations;
    }

    /**
     * Gets the number of times the key can be used without rebooting the device.
     *
     * @return maximum number of times or {@code null} if there is no restriction.
     */
    public Integer getMaxUsesPerBoot() {
        return mMaxUsesPerBoot;
    }

    /**
     * Gets the user authenticators which protect access to the key. The key can only be used iff
     * the user has authenticated to at least one of these user authenticators.
     *
     * @return user authenticators or empty set if the key can be used without user authentication.
     */
    public Set<Integer> getUserAuthenticators() {
        return new HashSet<Integer>(mUserAuthenticators);
    }

    /**
     * Gets the TEE-backed user authenticators which protect access to the key. This is a subset of
     * the user authentications returned by {@link #getUserAuthenticators()}.
     */
    public Set<Integer> getTeeBackedUserAuthenticators() {
        return new HashSet<Integer>(mTeeBackedUserAuthenticators);
    }

    /**
     * Gets the duration of time (seconds) for which the key can be used after the user
     * successfully authenticates to one of the associated user authenticators.
     *
     * @return duration in seconds or {@code null} if not restricted. {@code 0} means authentication
     *         is required for every use of the key.
     */
    public Integer getUserAuthenticationValidityDurationSeconds() {
        return mUserAuthenticationValidityDurationSeconds;
    }
}
