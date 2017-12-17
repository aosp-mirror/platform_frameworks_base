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

package android.security.recoverablekeystore;

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
public final class KeyStoreRecoveryData implements Parcelable {
    private final int mSnapshotVersion;
    private final List<KeyStoreRecoveryMetadata> mRecoveryMetadata;
    private final List<KeyEntryRecoveryData> mApplicationKeyBlobs;
    private final byte[] mEncryptedRecoveryKeyBlob;

    public KeyStoreRecoveryData(int snapshotVersion, @NonNull List<KeyStoreRecoveryMetadata>
            recoveryMetadata, @NonNull List<KeyEntryRecoveryData> applicationKeyBlobs,
            @NonNull byte[] encryptedRecoveryKeyBlob) {
        mSnapshotVersion = snapshotVersion;
        mRecoveryMetadata = Preconditions.checkNotNull(recoveryMetadata);
        mApplicationKeyBlobs = Preconditions.checkNotNull(applicationKeyBlobs);
        mEncryptedRecoveryKeyBlob = Preconditions.checkNotNull(encryptedRecoveryKeyBlob);
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
    public @NonNull List<KeyStoreRecoveryMetadata> getRecoveryMetadata() {
        return mRecoveryMetadata;
    }

    /**
     * List of application keys, with key material encrypted by
     * the recovery key ({@link #getEncryptedRecoveryKeyBlob}).
     */
    public @NonNull List<KeyEntryRecoveryData> getApplicationKeyBlobs() {
        return mApplicationKeyBlobs;
    }

    /**
     * Recovery key blob, encrypted by user secret and recovery service public key.
     */
    public @NonNull byte[] getEncryptedRecoveryKeyBlob() {
        return mEncryptedRecoveryKeyBlob;
    }

    public static final Parcelable.Creator<KeyStoreRecoveryData> CREATOR =
            new Parcelable.Creator<KeyStoreRecoveryData>() {
        public KeyStoreRecoveryData createFromParcel(Parcel in) {
            return new KeyStoreRecoveryData(in);
        }

        public KeyStoreRecoveryData[] newArray(int length) {
            return new KeyStoreRecoveryData[length];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mSnapshotVersion);
        out.writeTypedList(mRecoveryMetadata);
        out.writeByteArray(mEncryptedRecoveryKeyBlob);
        out.writeTypedList(mApplicationKeyBlobs);
    }

    protected KeyStoreRecoveryData(Parcel in) {
        mSnapshotVersion = in.readInt();
        mRecoveryMetadata = in.createTypedArrayList(KeyStoreRecoveryMetadata.CREATOR);
        mEncryptedRecoveryKeyBlob = in.createByteArray();
        mApplicationKeyBlobs = in.createTypedArrayList(KeyEntryRecoveryData.CREATOR);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
