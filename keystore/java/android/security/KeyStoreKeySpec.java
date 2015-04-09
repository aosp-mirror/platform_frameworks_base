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
import java.util.Date;

/**
 * Information about a key from the <a href="{@docRoot}training/articles/keystore.html">Android
 * KeyStore</a>.
 *
 * @hide
 */
public class KeyStoreKeySpec implements KeySpec {
    private final String mKeystoreAlias;
    private final int mKeySize;
    private final boolean mTeeBacked;
    private final @KeyStoreKeyCharacteristics.OriginEnum int mOrigin;
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyStoreKeyConstraints.PurposeEnum int mPurposes;
    private final @KeyStoreKeyConstraints.AlgorithmEnum int mAlgorithm;
    private final @KeyStoreKeyConstraints.PaddingEnum int mPaddings;
    private final @KeyStoreKeyConstraints.DigestEnum int mDigests;
    private final @KeyStoreKeyConstraints.BlockModeEnum int mBlockModes;
    private final @KeyStoreKeyConstraints.UserAuthenticatorEnum int mUserAuthenticators;
    private final @KeyStoreKeyConstraints.UserAuthenticatorEnum int mTeeEnforcedUserAuthenticators;
    private final int mUserAuthenticationValidityDurationSeconds;


    /**
     * @hide
     */
    KeyStoreKeySpec(String keystoreKeyAlias,
            boolean teeBacked,
            @KeyStoreKeyCharacteristics.OriginEnum int origin,
            int keySize,
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyConstraints.PurposeEnum int purposes,
            @KeyStoreKeyConstraints.AlgorithmEnum int algorithm,
            @KeyStoreKeyConstraints.PaddingEnum int paddings,
            @KeyStoreKeyConstraints.DigestEnum int digests,
            @KeyStoreKeyConstraints.BlockModeEnum int blockModes,
            @KeyStoreKeyConstraints.UserAuthenticatorEnum int userAuthenticators,
            @KeyStoreKeyConstraints.UserAuthenticatorEnum int teeEnforcedUserAuthenticators,
            int userAuthenticationValidityDurationSeconds) {
        mKeystoreAlias = keystoreKeyAlias;
        mTeeBacked = teeBacked;
        mOrigin = origin;
        mKeySize = keySize;
        mKeyValidityStart = keyValidityStart;
        mKeyValidityForOriginationEnd = keyValidityForOriginationEnd;
        mKeyValidityForConsumptionEnd = keyValidityForConsumptionEnd;
        mPurposes = purposes;
        mAlgorithm = algorithm;
        mPaddings = paddings;
        mDigests = digests;
        mBlockModes = blockModes;
        mUserAuthenticators = userAuthenticators;
        mTeeEnforcedUserAuthenticators = teeEnforcedUserAuthenticators;
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
    }

    /**
     * Gets the entry alias under which the key is stored in the {@code AndroidKeyStore}.
     */
    public String getKeystoreAlias() {
        return mKeystoreAlias;
    }

    /**
     * Returns {@code true} if the key is TEE-backed. Key material of TEE-backed keys is available
     * in plaintext only inside the TEE.
     */
    public boolean isTeeBacked() {
        return mTeeBacked;
    }

    /**
     * Gets the origin of the key.
     */
    public @KeyStoreKeyCharacteristics.OriginEnum int getOrigin() {
        return mOrigin;
    }

    /**
     * Gets the size of the key in bits.
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
     * Gets the set of block modes with which the key can be used.
     */
    public @KeyStoreKeyConstraints.BlockModeEnum int getBlockModes() {
        return mBlockModes;
    }

    /**
     * Gets the set of padding modes with which the key can be used.
     */
    public @KeyStoreKeyConstraints.PaddingEnum int getPaddings() {
        return mPaddings;
    }

    /**
     * Gets the set of digest algorithms with which the key can be used.
     */
    public @KeyStoreKeyConstraints.DigestEnum int getDigests() {
        return mDigests;
    }

    /**
     * Gets the set of user authenticators which protect access to the key. The key can only be used
     * iff the user has authenticated to at least one of these user authenticators.
     *
     * @return user authenticators or {@code 0} if the key can be used without user authentication.
     */
    public @KeyStoreKeyConstraints.UserAuthenticatorEnum int getUserAuthenticators() {
        return mUserAuthenticators;
    }

    /**
     * Gets the set of user authenticators for which the TEE enforces access restrictions for this
     * key. This is a subset of the user authentications returned by
     * {@link #getUserAuthenticators()}.
     */
    public @KeyStoreKeyConstraints.UserAuthenticatorEnum int getTeeEnforcedUserAuthenticators() {
        return mTeeEnforcedUserAuthenticators;
    }

    /**
     * Gets the duration of time (seconds) for which the key can be used after the user
     * successfully authenticates to one of the associated user authenticators.
     *
     * @return duration in seconds or {@code -1} if not restricted. {@code 0} means authentication
     *         is required for every use of the key.
     */
    public int getUserAuthenticationValidityDurationSeconds() {
        return mUserAuthenticationValidityDurationSeconds;
    }
}
