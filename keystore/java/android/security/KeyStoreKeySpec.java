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
 */
public class KeyStoreKeySpec implements KeySpec {
    private final String mKeystoreAlias;
    private final int mKeySize;
    private final boolean mTeeBacked;
    private final @KeyStoreKeyProperties.OriginEnum int mOrigin;
    private final Date mKeyValidityStart;
    private final Date mKeyValidityForOriginationEnd;
    private final Date mKeyValidityForConsumptionEnd;
    private final @KeyStoreKeyProperties.PurposeEnum int mPurposes;
    private final @KeyStoreKeyProperties.EncryptionPaddingEnum String[] mEncryptionPaddings;
    private final @KeyStoreKeyProperties.SignaturePaddingEnum String[] mSignaturePaddings;
    private final @KeyStoreKeyProperties.DigestEnum String[] mDigests;
    private final @KeyStoreKeyProperties.BlockModeEnum String[] mBlockModes;
    private final boolean mUserAuthenticationRequired;
    private final int mUserAuthenticationValidityDurationSeconds;
    private final boolean mUserAuthenticationRequirementTeeEnforced;

    /**
     * @hide
     */
    KeyStoreKeySpec(String keystoreKeyAlias,
            boolean teeBacked,
            @KeyStoreKeyProperties.OriginEnum int origin,
            int keySize,
            Date keyValidityStart,
            Date keyValidityForOriginationEnd,
            Date keyValidityForConsumptionEnd,
            @KeyStoreKeyProperties.PurposeEnum int purposes,
            @KeyStoreKeyProperties.EncryptionPaddingEnum String[] encryptionPaddings,
            @KeyStoreKeyProperties.SignaturePaddingEnum String[] signaturePaddings,
            @KeyStoreKeyProperties.DigestEnum String[] digests,
            @KeyStoreKeyProperties.BlockModeEnum String[] blockModes,
            boolean userAuthenticationRequired,
            int userAuthenticationValidityDurationSeconds,
            boolean userAuthenticationRequirementTeeEnforced) {
        mKeystoreAlias = keystoreKeyAlias;
        mTeeBacked = teeBacked;
        mOrigin = origin;
        mKeySize = keySize;
        mKeyValidityStart = keyValidityStart;
        mKeyValidityForOriginationEnd = keyValidityForOriginationEnd;
        mKeyValidityForConsumptionEnd = keyValidityForConsumptionEnd;
        mPurposes = purposes;
        mEncryptionPaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(encryptionPaddings));
        mSignaturePaddings =
                ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(signaturePaddings));
        mDigests = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(digests));
        mBlockModes = ArrayUtils.cloneIfNotEmpty(ArrayUtils.nullToEmpty(blockModes));
        mUserAuthenticationRequired = userAuthenticationRequired;
        mUserAuthenticationValidityDurationSeconds = userAuthenticationValidityDurationSeconds;
        mUserAuthenticationRequirementTeeEnforced = userAuthenticationRequirementTeeEnforced;
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
    public @KeyStoreKeyProperties.OriginEnum int getOrigin() {
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
    public @KeyStoreKeyProperties.PurposeEnum int getPurposes() {
        return mPurposes;
    }

    /**
     * Gets the set of block modes with which the key can be used.
     */
    public @KeyStoreKeyProperties.BlockModeEnum String[] getBlockModes() {
        return ArrayUtils.cloneIfNotEmpty(mBlockModes);
    }

    /**
     * Gets the set of padding modes with which the key can be used when encrypting/decrypting.
     */
    public @KeyStoreKeyProperties.EncryptionPaddingEnum String[] getEncryptionPaddings() {
        return ArrayUtils.cloneIfNotEmpty(mEncryptionPaddings);
    }

    /**
     * Gets the set of padding modes with which the key can be used when signing/verifying.
     */
    public @KeyStoreKeyProperties.SignaturePaddingEnum String[] getSignaturePaddings() {
        return ArrayUtils.cloneIfNotEmpty(mSignaturePaddings);
    }

    /**
     * Gets the set of digest algorithms with which the key can be used.
     */
    public @KeyStoreKeyProperties.DigestEnum String[] getDigests() {
        return ArrayUtils.cloneIfNotEmpty(mDigests);
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
     * Returns {@code true} if the requirement that this key can only be used if the user has been
     * authenticated if enforced by the TEE.
     *
     * @see #isUserAuthenticationRequired()
     */
    public boolean isUserAuthenticationRequirementTeeEnforced() {
        return mUserAuthenticationRequirementTeeEnforced;
    }
}
