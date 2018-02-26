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

package android.security.keystore.recovery;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.security.cert.CertPath;
import java.util.List;

/**
 * A snapshot of a version of the keystore. Two events can trigger the generation of a new snapshot:
 *
 * <ul>
 *     <li>The user's lock screen changes. (A key derived from the user's lock screen is used to
 *         protected the keychain, which is why this forces a new snapshot.)
 *     <li>A key is added to or removed from the recoverable keychain.
 * </ul>
 *
 * <p>The snapshot data is also encrypted with the remote trusted hardware's public key, so even
 * the recovery agent itself should not be able to decipher the data. The recovery agent sends an
 * instance of this to the remote trusted hardware whenever a new snapshot is generated. During a
 * recovery flow, the recovery agent retrieves a snapshot from the remote trusted hardware. It then
 * sends it to the framework, where it is decrypted using the user's lock screen from their previous
 * device.
 *
 * @hide
 */
@SystemApi
public final class KeyChainSnapshot implements Parcelable {
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    private static final long DEFAULT_COUNTER_ID = 1L;

    private int mSnapshotVersion;
    private int mMaxAttempts = DEFAULT_MAX_ATTEMPTS;
    private long mCounterId = DEFAULT_COUNTER_ID;
    private byte[] mServerParams;
    private byte[] mPublicKey;  // The raw public key bytes used
    private CertPath mCertPath;  // The certificate path including the intermediate certificates
    private List<KeyChainProtectionParams> mKeyChainProtectionParams;
    private List<WrappedApplicationKey> mEntryRecoveryData;
    private byte[] mEncryptedRecoveryKeyBlob;

    /**
     * @hide
     * Deprecated, consider using builder.
     */
    public KeyChainSnapshot(
            int snapshotVersion,
            @NonNull List<KeyChainProtectionParams> keyChainProtectionParams,
            @NonNull List<WrappedApplicationKey> wrappedApplicationKeys,
            @NonNull byte[] encryptedRecoveryKeyBlob) {
        mSnapshotVersion = snapshotVersion;
        mKeyChainProtectionParams =
                Preconditions.checkCollectionElementsNotNull(keyChainProtectionParams,
                        "KeyChainProtectionParams");
        mEntryRecoveryData = Preconditions.checkCollectionElementsNotNull(wrappedApplicationKeys,
                "wrappedApplicationKeys");
        mEncryptedRecoveryKeyBlob = Preconditions.checkNotNull(encryptedRecoveryKeyBlob);
    }

    private KeyChainSnapshot() {

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
     * See implementation for binary key format.
     *
     * @deprecated Use {@link #getTrustedHardwareCertPath} instead.
     * @removed
     */
    @Deprecated
    public @NonNull byte[] getTrustedHardwarePublicKey() {
        return mPublicKey;
    }

    /**
     * CertPath containing the public key used to encrypt {@code encryptedRecoveryKeyBlob}.
     */
    // TODO: Change to @NonNull
    public CertPath getTrustedHardwareCertPath() {
        return mCertPath;
    }

    /**
     * UI and key derivation parameters. Note that combination of secrets may be used.
     */
    public @NonNull List<KeyChainProtectionParams> getKeyChainProtectionParams() {
        return mKeyChainProtectionParams;
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

    public static final Creator<KeyChainSnapshot> CREATOR =
            new Creator<KeyChainSnapshot>() {
        public KeyChainSnapshot createFromParcel(Parcel in) {
            return new KeyChainSnapshot(in);
        }

        public KeyChainSnapshot[] newArray(int length) {
            return new KeyChainSnapshot[length];
        }
    };

    /**
     * Builder for creating {@link KeyChainSnapshot}.
     * @hide
     */
    public static class Builder {
        private KeyChainSnapshot mInstance = new KeyChainSnapshot();

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
         * @deprecated Use {@link #setTrustedHardwareCertPath} instead.
         * @removed
         */
        @Deprecated
        public Builder setTrustedHardwarePublicKey(byte[] publicKey) {
            mInstance.mPublicKey = publicKey;
            return this;
        }

        /**
         * Sets CertPath used to validate the trusted hardware public key. The CertPath should
         * contain a certificate of the trusted hardware public key and any necessary intermediate
         * certificates.
         *
         * @param certPath The public key
         * @return This builder.
         */
        public Builder setTrustedHardwareCertPath(CertPath certPath) {
            mInstance.mCertPath = certPath;
            return this;
        }

        /**
         * Sets UI and key derivation parameters
         *
         * @param recoveryMetadata The UI and key derivation parameters
         * @return This builder.
         */
        public Builder setKeyChainProtectionParams(
                @NonNull List<KeyChainProtectionParams> recoveryMetadata) {
            mInstance.mKeyChainProtectionParams = recoveryMetadata;
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
         * Creates a new {@link KeyChainSnapshot} instance.
         *
         * @return new instance
         * @throws NullPointerException if some required fields were not set.
         */
        @NonNull public KeyChainSnapshot build() {
            Preconditions.checkCollectionElementsNotNull(mInstance.mKeyChainProtectionParams,
                    "recoveryMetadata");
            Preconditions.checkCollectionElementsNotNull(mInstance.mEntryRecoveryData,
                    "entryRecoveryData");
            Preconditions.checkNotNull(mInstance.mEncryptedRecoveryKeyBlob);
            Preconditions.checkNotNull(mInstance.mServerParams);
            return mInstance;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mSnapshotVersion);
        out.writeTypedList(mKeyChainProtectionParams);
        out.writeByteArray(mEncryptedRecoveryKeyBlob);
        out.writeTypedList(mEntryRecoveryData);
        out.writeInt(mMaxAttempts);
        out.writeLong(mCounterId);
        out.writeByteArray(mServerParams);
        out.writeByteArray(mPublicKey);
    }

    /**
     * @hide
     */
    protected KeyChainSnapshot(Parcel in) {
        mSnapshotVersion = in.readInt();
        mKeyChainProtectionParams = in.createTypedArrayList(KeyChainProtectionParams.CREATOR);
        mEncryptedRecoveryKeyBlob = in.createByteArray();
        mEntryRecoveryData = in.createTypedArrayList(WrappedApplicationKey.CREATOR);
        mMaxAttempts = in.readInt();
        mCounterId = in.readLong();
        mServerParams = in.createByteArray();
        mPublicKey = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
