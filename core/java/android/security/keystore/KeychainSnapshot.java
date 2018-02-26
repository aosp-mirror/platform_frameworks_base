/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * @deprecated Use {@link android.security.keystore.recovery.KeyChainSnapshot}.
 * @hide
 */
public final class KeychainSnapshot implements Parcelable {
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final long DEFAULT_COUNTER_ID = 1L;

    private int mSnapshotVersion;
    private int mMaxAttempts = DEFAULT_MAX_ATTEMPTS;
    private long mCounterId = DEFAULT_COUNTER_ID;
    private byte[] mServerParams;
    private byte[] mPublicKey;
    private List<KeychainProtectionParams> mKeychainProtectionParams;
    private List<WrappedApplicationKey> mEntryRecoveryData;
    private byte[] mEncryptedRecoveryKeyBlob;

    /**
     * @hide
     * Deprecated, consider using builder.
     */
    public KeychainSnapshot(
            int snapshotVersion,
            @NonNull List<KeychainProtectionParams> keychainProtectionParams,
            @NonNull List<WrappedApplicationKey> wrappedApplicationKeys,
            @NonNull byte[] encryptedRecoveryKeyBlob) {
        mSnapshotVersion = snapshotVersion;
        mKeychainProtectionParams =
                Preconditions.checkCollectionElementsNotNull(keychainProtectionParams,
                        "keychainProtectionParams");
        mEntryRecoveryData = Preconditions.checkCollectionElementsNotNull(wrappedApplicationKeys,
                "wrappedApplicationKeys");
        mEncryptedRecoveryKeyBlob = Preconditions.checkNotNull(encryptedRecoveryKeyBlob);
    }

    private KeychainSnapshot() {

    }

    /**
     * Snapshot version for given account. It is incremented when user secret or list of application
     * keys changes.
     */
    public int getSnapshotVersion() {
        return mSnapshotVersion;
    }

    /**
     * Number of user secret guesses allowed during Keychain recovery.
     */
    public int getMaxAttempts() {
        return mMaxAttempts;
    }

    /**
     * CounterId which is rotated together with user secret.
     */
    public long getCounterId() {
        return mCounterId;
    }

    /**
     * Server parameters.
     */
    public @NonNull byte[] getServerParams() {
        return mServerParams;
    }

    /**
     * Public key used to encrypt {@code encryptedRecoveryKeyBlob}.
     *
     * See implementation for binary key format
     */
    // TODO: document key format.
    public @NonNull byte[] getTrustedHardwarePublicKey() {
        return mPublicKey;
    }

    /**
     * UI and key derivation parameters. Note that combination of secrets may be used.
     */
    public @NonNull List<KeychainProtectionParams> getKeychainProtectionParams() {
        return mKeychainProtectionParams;
    }

    /**
     * List of application keys, with key material encrypted by
     * the recovery key ({@link #getEncryptedRecoveryKeyBlob}).
     */
    public @NonNull List<WrappedApplicationKey> getWrappedApplicationKeys() {
        return mEntryRecoveryData;
    }

    /**
     * Recovery key blob, encrypted by user secret and recovery service public key.
     */
    public @NonNull byte[] getEncryptedRecoveryKeyBlob() {
        return mEncryptedRecoveryKeyBlob;
    }

    public static final Parcelable.Creator<KeychainSnapshot> CREATOR =
            new Parcelable.Creator<KeychainSnapshot>() {
        public KeychainSnapshot createFromParcel(Parcel in) {
            return new KeychainSnapshot(in);
        }

        public KeychainSnapshot[] newArray(int length) {
            return new KeychainSnapshot[length];
        }
    };

    /**
     * Builder for creating {@link KeychainSnapshot}.
     *
     * @hide
     */
    public static class Builder {
        private KeychainSnapshot mInstance = new KeychainSnapshot();

        /**
         * Snapshot version for given account.
         *
         * @param snapshotVersion The snapshot version
         * @return This builder.
         */
        public Builder setSnapshotVersion(int snapshotVersion) {
            mInstance.mSnapshotVersion = snapshotVersion;
            return this;
        }

        /**
         * Sets the number of user secret guesses allowed during Keychain recovery.
         *
         * @param maxAttempts The maximum number of guesses.
         * @return This builder.
         */
        public Builder setMaxAttempts(int maxAttempts) {
            mInstance.mMaxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets counter id.
         *
         * @param counterId The counter id.
         * @return This builder.
         */
        public Builder setCounterId(long counterId) {
            mInstance.mCounterId = counterId;
            return this;
        }

        /**
         * Sets server parameters.
         *
         * @param serverParams The server parameters
         * @return This builder.
         */
        public Builder setServerParams(byte[] serverParams) {
            mInstance.mServerParams = serverParams;
            return this;
        }

        /**
         * Sets public key used to encrypt recovery blob.
         *
         * @param publicKey The public key
         * @return This builder.
         */
        public Builder setTrustedHardwarePublicKey(byte[] publicKey) {
            mInstance.mPublicKey = publicKey;
            return this;
        }

        /**
         * Sets UI and key derivation parameters
         *
         * @param recoveryMetadata The UI and key derivation parameters
         * @return This builder.
         */
        public Builder setKeychainProtectionParams(
                @NonNull List<KeychainProtectionParams> recoveryMetadata) {
            mInstance.mKeychainProtectionParams = recoveryMetadata;
            return this;
        }

        /**
         * List of application keys.
         *
         * @param entryRecoveryData List of application keys
         * @return This builder.
         */
        public Builder setWrappedApplicationKeys(List<WrappedApplicationKey> entryRecoveryData) {
            mInstance.mEntryRecoveryData = entryRecoveryData;
            return this;
        }

        /**
         * Sets recovery key blob
         *
         * @param encryptedRecoveryKeyBlob The recovery key blob.
         * @return This builder.
         */
        public Builder setEncryptedRecoveryKeyBlob(@NonNull byte[] encryptedRecoveryKeyBlob) {
            mInstance.mEncryptedRecoveryKeyBlob = encryptedRecoveryKeyBlob;
            return this;
        }


        /**
         * Creates a new {@link KeychainSnapshot} instance.
         *
         * @return new instance
         * @throws NullPointerException if some required fields were not set.
         */
        @NonNull public KeychainSnapshot build() {
            Preconditions.checkCollectionElementsNotNull(mInstance.mKeychainProtectionParams,
                    "recoveryMetadata");
            Preconditions.checkCollectionElementsNotNull(mInstance.mEntryRecoveryData,
                    "entryRecoveryData");
            Preconditions.checkNotNull(mInstance.mEncryptedRecoveryKeyBlob);
            Preconditions.checkNotNull(mInstance.mServerParams);
            Preconditions.checkNotNull(mInstance.mPublicKey);
            return mInstance;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mSnapshotVersion);
        out.writeTypedList(mKeychainProtectionParams);
        out.writeByteArray(mEncryptedRecoveryKeyBlob);
        out.writeTypedList(mEntryRecoveryData);
    }

    /**
     * @hide
     */
    protected KeychainSnapshot(Parcel in) {
        mSnapshotVersion = in.readInt();
        mKeychainProtectionParams = in.createTypedArrayList(KeychainProtectionParams.CREATOR);
        mEncryptedRecoveryKeyBlob = in.createByteArray();
        mEntryRecoveryData = in.createTypedArrayList(WrappedApplicationKey.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
