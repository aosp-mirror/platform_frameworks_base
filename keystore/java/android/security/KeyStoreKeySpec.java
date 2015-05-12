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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.security.PrivateKey;
import java.security.spec.KeySpec;
import java.util.Date;

import javax.crypto.SecretKey;

/**
 * Information about a key from the <a href="{@docRoot}training/articles/keystore.html">Android
 * KeyStore</a>. This class describes whether the key material is available in
 * plaintext outside of secure hardware, whether user authentication is required for using the key
 * and whether this requirement is enforced by secure hardware, the key's origin, what uses the key
 * is authorized for (e.g., only in {@code CBC} mode, or signing only), whether the key should be
 * encrypted at rest, the key's and validity start and end dates.
 *
 * <p><h3>Example: Symmetric Key</h3>
 * The following example illustrates how to obtain a {@link KeyStoreKeySpec} describing the provided
 * Android KeyStore {@link SecretKey}.
 * <pre> {@code
 * SecretKey key = ...; // Android KeyStore key
 *
 * SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
 * KeyStoreKeySpec spec;
 * try &#123;
 *     spec = (KeyStoreKeySpec) factory.getKeySpec(key, KeyStoreKeySpec.class);
 * &#125; catch (InvalidKeySpecException e) &#123;
 *     // Not an Android KeyStore key.
 * &#125;
 * }</pre>
 *
 * <p><h3>Example: Private Key</h3>
 * The following example illustrates how to obtain a {@link KeyStoreKeySpec} describing the provided
 * Android KeyStore {@link PrivateKey}.
 * <pre> {@code
 * PrivateKey key = ...; // Android KeyStore key
 *
 * KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
 * KeyStoreKeySpec spec;
 * try &#123;
 *     spec = factory.getKeySpec(key, KeyStoreKeySpec.class);
 * &#125; catch (InvalidKeySpecException e) &#123;
 *     // Not an Android KeyStore key.
 * &#125;
 * }</pre>
 */
public class KeyStoreKeySpec implements KeySpec {
    private final String mKeystoreAlias;
    private final int mKeySize;
    private final boolean mInsideSecureHardware;
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
    private final boolean mUserAuthenticationRequirementEnforcedBySecureHardware;

    /**
     * @hide
     */
    KeyStoreKeySpec(String keystoreKeyAlias,
            boolean insideSecureHardware,
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
            boolean userAuthenticationRequirementEnforcedBySecureHardware) {
        mKeystoreAlias = keystoreKeyAlias;
        mInsideSecureHardware = insideSecureHardware;
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
        mUserAuthenticationRequirementEnforcedBySecureHardware =
                userAuthenticationRequirementEnforcedBySecureHardware;
    }

    /**
     * Gets the entry alias under which the key is stored in the {@code AndroidKeyStore}.
     */
    public String getKeystoreAlias() {
        return mKeystoreAlias;
    }

    /**
     * Returns {@code true} if the key resides inside secure hardware (e.g., Trusted Execution
     * Environment (TEE) or Secure Element (SE)). Key material of such keys is available in
     * plaintext only inside the secure hardware and is not exposed outside of it.
     */
    public boolean isInsideSecureHardware() {
        return mInsideSecureHardware;
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
    @Nullable
    public Date getKeyValidityStart() {
        return mKeyValidityStart;
    }

    /**
     * Gets the time instant after which the key is no long valid for decryption and verification.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
    public Date getKeyValidityForConsumptionEnd() {
        return mKeyValidityForConsumptionEnd;
    }

    /**
     * Gets the time instant after which the key is no long valid for encryption and signing.
     *
     * @return instant or {@code null} if not restricted.
     */
    @Nullable
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
    @NonNull
    public @KeyStoreKeyProperties.BlockModeEnum String[] getBlockModes() {
        return ArrayUtils.cloneIfNotEmpty(mBlockModes);
    }

    /**
     * Gets the set of padding modes with which the key can be used when encrypting/decrypting.
     */
    @NonNull
    public @KeyStoreKeyProperties.EncryptionPaddingEnum String[] getEncryptionPaddings() {
        return ArrayUtils.cloneIfNotEmpty(mEncryptionPaddings);
    }

    /**
     * Gets the set of padding modes with which the key can be used when signing/verifying.
     */
    @NonNull
    public @KeyStoreKeyProperties.SignaturePaddingEnum String[] getSignaturePaddings() {
        return ArrayUtils.cloneIfNotEmpty(mSignaturePaddings);
    }

    /**
     * Gets the set of digest algorithms with which the key can be used.
     */
    @NonNull
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
     * authenticated if enforced by secure hardware (e.g., Trusted Execution Environment (TEE) or
     * Secure Element (SE)).
     *
     * @see #isUserAuthenticationRequired()
     */
    public boolean isUserAuthenticationRequirementEnforcedBySecureHardware() {
        return mUserAuthenticationRequirementEnforcedBySecureHardware;
    }
}
