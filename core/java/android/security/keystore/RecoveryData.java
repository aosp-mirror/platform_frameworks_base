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
 * Helper class which returns data necessary to recover keys.
 * Contains
 *
 * <ul>
 * <li>Snapshot version.
 * <li>Recovery metadata with UI and key derivation parameters.
 * <li>List of application keys encrypted by recovery key.
 * <li>Encrypted recovery key.
 * </ul>
 *
 * @hide
 */
public final class RecoveryData implements Parcelable {
    private Integer mSnapshotVersion;
    private List<RecoveryMetadata> mRecoveryMetadata;
    private List<EntryRecoveryData> mEntryRecoveryData;
    private byte[] mEncryptedRecoveryKeyBlob;

    /**
     * @hide
     * Deprecated, consider using builder.
     */
    public RecoveryData(
            int snapshotVersion,
            @NonNull List<RecoveryMetadata> recoveryMetadata,
            @NonNull List<EntryRecoveryData> entryRecoveryData,
            @NonNull byte[] encryptedRecoveryKeyBlob) {
        mSnapshotVersion = snapshotVersion;
        mRecoveryMetadata =
                Preconditions.checkCollectionElementsNotNull(recoveryMetadata, "recoveryMetadata");
        mEntryRecoveryData = Preconditions.checkCollectionElementsNotNull(entryRecoveryData,
                "entryRecoveryData");
        mEncryptedRecoveryKeyBlob = Preconditions.checkNotNull(encryptedRecoveryKeyBlob);
    }

    private RecoveryData() {

    }

    /**
     * Snapshot version for given account. It is incremented when user secret or list of application
     * keys changes.
     */
    public int getSnapshotVersion() {
        return mSnapshotVersion;
    }

    /**
     * UI and key derivation parameters. Note that combination of secrets may be used.
     */
    public @NonNull List<RecoveryMetadata> getRecoveryMetadata() {
        return mRecoveryMetadata;
    }

    /**
     * List of application keys, with key material encrypted by
     * the recovery key ({@link #getEncryptedRecoveryKeyBlob}).
     */
    public @NonNull List<EntryRecoveryData> getEntryRecoveryData() {
        return mEntryRecoveryData;
    }

    /**
     * Recovery key blob, encrypted by user secret and recovery service public key.
     */
    public @NonNull byte[] getEncryptedRecoveryKeyBlob() {
        return mEncryptedRecoveryKeyBlob;
    }

    public static final Parcelable.Creator<RecoveryData> CREATOR =
            new Parcelable.Creator<RecoveryData>() {
        public RecoveryData createFromParcel(Parcel in) {
            return new RecoveryData(in);
        }

        public RecoveryData[] newArray(int length) {
            return new RecoveryData[length];
        }
    };

    /**
     * Builder for creating {@link RecoveryData}.
     */
    public static class Builder {
        private RecoveryData mInstance = new RecoveryData();

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
         * Sets UI and key derivation parameters
         *
         * @param recoveryMetadata The UI and key derivation parameters
         * @return This builder.
         */
        public Builder setRecoveryMetadata(@NonNull List<RecoveryMetadata> recoveryMetadata) {
            mInstance.mRecoveryMetadata = recoveryMetadata;
            return this;
        }

        /**
         * List of application keys.
         *
         * @param entryRecoveryData List of application keys
         * @return This builder.
         */
        public Builder setEntryRecoveryData(List<EntryRecoveryData> entryRecoveryData) {
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
         * Creates a new {@link RecoveryData} instance.
         *
         * @return new instance
         * @throws NullPointerException if some required fields were not set.
         */
        public @NonNull RecoveryData build() {
            Preconditions.checkNotNull(mInstance.mSnapshotVersion);
            Preconditions.checkCollectionElementsNotNull(mInstance.mRecoveryMetadata,
                    "recoveryMetadata");
            Preconditions.checkCollectionElementsNotNull(mInstance.mEntryRecoveryData,
                    "entryRecoveryData");
            Preconditions.checkNotNull(mInstance.mEncryptedRecoveryKeyBlob);
            return mInstance;
        }
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mSnapshotVersion);
        out.writeTypedList(mRecoveryMetadata);
        out.writeByteArray(mEncryptedRecoveryKeyBlob);
        out.writeTypedList(mEntryRecoveryData);
    }

    /**
     * @hide
     */
    protected RecoveryData(Parcel in) {
        mSnapshotVersion = in.readInt();
        mRecoveryMetadata = in.createTypedArrayList(RecoveryMetadata.CREATOR);
        mEncryptedRecoveryKeyBlob = in.createByteArray();
        mEntryRecoveryData = in.createTypedArrayList(EntryRecoveryData.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
